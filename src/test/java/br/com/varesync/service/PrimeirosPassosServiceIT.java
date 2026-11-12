package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.PrimeirosPassosDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.PrimeirosPassos;
import br.com.varesync.model.PrimeirosPassos.Etapa;
import br.com.varesync.model.PrimeirosPassos.Situacao;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Assistente "Primeiros passos" (F21.4, aceite da parte C): marca os passos concluídos pelos dados, grava o que não
 * dá para conferir sozinho em {@code assistente.*} (com auditoria) e termina quando todos estão concluídos ou
 * dispensados. Só para o supervisor.
 */
class PrimeirosPassosServiceIT extends IntegracaoBase {

    private AcessoTeste t;
    private PrimeirosPassosService passos;
    private Sessao admin;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        admin = t.supervisor();
        passos = new PrimeirosPassosService(new Transacao(dataSource), new ConfiguracaoDAO(), new PrimeirosPassosDAO(),
                t.configuracoes);
    }

    private static Situacao situacao(PrimeirosPassos p, Etapa etapa) {
        return p.passo(etapa).situacao();
    }

    private void sql(String comando) throws Exception {
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
            st.executeUpdate(comando);
        }
    }

    @Test
    void lojaNovaComecaComTudoPendente() {
        PrimeirosPassos p = passos.carregar(admin);

        assertThat(p.passos()).extracting(PrimeirosPassos.Passo::etapa).containsExactly(Etapa.values());
        assertThat(p.passos()).allMatch(PrimeirosPassos.Passo::pendente);
        assertThat(p.terminado()).isFalse();
        assertThat(p.feitos()).isZero();
        // O primeiro acesso só pede o nome da loja.
        assertThat(p.passo(Etapa.LOJA).detalhe()).isEqualTo("Faltam o CNPJ, o endereço e a UF.");
    }

    @Test
    void marcaOsPassosConcluidosPelosDadosESomeQuandoTudoEstaFeito() throws Exception {
        Configuracoes c = t.configuracoes.carregar(admin);
        t.configuracoes.salvar(admin, c.comLoja(new Configuracoes.Loja("Mercado Bom Preço", "11.222.333/0001-81",
                "Rua das Flores, 10, Centro, Ribeirão Preto", "", c.loja().regime(), "SP", "")));
        t.produtos.criar(admin, new DadosProduto("Arroz 5 kg", null, "UN", new BigDecimal("24.90"),
                new BigDecimal("18.00"), null, null, List.of(), null));
        t.usuarios.criar(admin, "maria", "Maria Caixa", false, EnumSet.of(Permissao.PDV), "inicial1", "inicial1");
        sql("UPDATE configuracao SET valor = 'true' WHERE chave = 'balanca.ativo'");
        sql("INSERT INTO auditoria (usuario_id, acao, descricao, criado_em) VALUES (" + admin.usuarioId()
                + ", 'BACKUP_REALIZADO', 'Backup varesync_20261006_1000.backup', now())");

        PrimeirosPassos p = passos.carregar(admin);
        assertThat(situacao(p, Etapa.LOJA)).isEqualTo(Situacao.CONCLUIDO);
        assertThat(situacao(p, Etapa.PRODUTOS)).isEqualTo(Situacao.CONCLUIDO);
        assertThat(p.passo(Etapa.PRODUTOS).detalhe()).isEqualTo("1 produto ativo.");
        assertThat(situacao(p, Etapa.USUARIOS)).isEqualTo(Situacao.CONCLUIDO);
        assertThat(situacao(p, Etapa.BALANCA)).isEqualTo(Situacao.CONCLUIDO);
        assertThat(situacao(p, Etapa.BACKUP)).isEqualTo(Situacao.CONCLUIDO);
        assertThat(situacao(p, Etapa.FORMAS)).as("as formas não se conferem sozinhas").isEqualTo(Situacao.PENDENTE);
        assertThat(p.terminado()).isFalse();

        PrimeirosPassos depois = passos.marcarFormasRevisadas(admin); // "Revisei"
        assertThat(situacao(depois, Etapa.FORMAS)).isEqualTo(Situacao.CONCLUIDO);
        assertThat(depois.terminado()).isTrue();
        assertThat(depois.feitos()).isEqualTo(6);
        assertThat(t.configuracoes.carregar(admin).assistente().formasRevisadas()).isTrue();
        assertThat(t.auditoria(AcaoAuditoria.CONFIGURACAO_ALTERADA)).last().asString()
                .contains("formas de pagamento revisadas (primeiros passos) de não para sim");
    }

    @Test
    void soEuUsoENaoUsoBalancaDispensamOsPassos() throws Exception {
        PrimeirosPassos p = passos.dispensarUsuarios(admin);
        assertThat(situacao(p, Etapa.USUARIOS)).isEqualTo(Situacao.DISPENSADO);
        p = passos.dispensarBalanca(admin);
        assertThat(situacao(p, Etapa.BALANCA)).isEqualTo(Situacao.DISPENSADO);
        assertThat(situacao(p, Etapa.USUARIOS)).as("a marcação anterior continua").isEqualTo(Situacao.DISPENSADO);

        Configuracoes.Assistente gravado = t.configuracoes.carregar(admin).assistente();
        assertThat(gravado).isEqualTo(new Configuracoes.Assistente(false, true, true));

        // Desmarcar pela tela de Configurações volta o passo a pendente.
        Configuracoes c = t.configuracoes.carregar(admin);
        t.configuracoes.salvar(admin, c.comAssistente(new Configuracoes.Assistente(false, false, true)));
        assertThat(situacao(passos.carregar(admin), Etapa.USUARIOS)).isEqualTo(Situacao.PENDENTE);
    }

    @Test
    void todosDispensadosOuConcluidosTerminam() throws Exception {
        Configuracoes c = t.configuracoes.carregar(admin);
        t.configuracoes.salvar(admin, c.comLoja(new Configuracoes.Loja("Mercado", "529.982.247-25", "Rua A, 1", "",
                c.loja().regime(), "MG", "")));
        t.produtos.criar(admin, new DadosProduto("Feijão 1 kg", null, "UN", new BigDecimal("8.90"),
                new BigDecimal("6.00"), null, null, List.of(), null));
        sql("INSERT INTO auditoria (usuario_id, acao, descricao, criado_em) VALUES (" + admin.usuarioId()
                + ", 'BACKUP_REALIZADO', 'Backup', now())");
        passos.marcarFormasRevisadas(admin);
        passos.dispensarUsuarios(admin);
        assertThat(passos.carregar(admin).terminado()).isFalse();

        PrimeirosPassos fim = passos.dispensarBalanca(admin);
        assertThat(fim.terminado()).isTrue();
        assertThat(fim.feitos()).isEqualTo(6);
    }

    @Test
    void produtoInativoNaoContaEUsuarioInativoTambemNao() throws Exception {
        long produto = t.produtos.criar(admin, new DadosProduto("Sal 1 kg", null, "UN", new BigDecimal("2.50"),
                new BigDecimal("1.20"), null, null, List.of(), null));
        t.produtos.alterarSituacao(admin, produto, false);
        long maria = t.usuarios.criar(admin, "maria", "Maria", false, EnumSet.of(Permissao.PDV), "inicial1",
                "inicial1");
        sql("UPDATE usuario SET ativo = FALSE WHERE id = " + maria);

        PrimeirosPassos p = passos.carregar(admin);
        assertThat(situacao(p, Etapa.PRODUTOS)).isEqualTo(Situacao.PENDENTE);
        assertThat(situacao(p, Etapa.USUARIOS)).isEqualTo(Situacao.PENDENTE);
    }

    @Test
    void soOSupervisor() {
        t.usuarios.criar(admin, "gerente", "Gerente", false, EnumSet.allOf(Permissao.class), "inicial1", "inicial1");
        Sessao gerente = t.acesso.trocarSenha(t.acesso.entrar("gerente", "inicial1"), "inicial1", "definitiva1",
                "definitiva1");

        assertThatThrownBy(() -> passos.carregar(gerente)).isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> passos.marcarFormasRevisadas(gerente)).isInstanceOf(PermissaoNegadaException.class);
    }
}
