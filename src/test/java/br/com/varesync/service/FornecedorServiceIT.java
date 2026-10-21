package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.DadosEntrada;
import br.com.varesync.model.DadosFornecedor;
import br.com.varesync.model.Fornecedor;
import br.com.varesync.model.Permissao;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Cadastro de fornecedores (6.10, Fase 7): CPF/CNPJ alfanumérico sem máscara, único, busca, inativar e excluir. */
class FornecedorServiceIT extends IntegracaoBase {

    private AcessoTeste t;
    private FornecedorService fornecedores;
    private Sessao admin;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        fornecedores = t.fornecedores;
        admin = t.supervisor();
    }

    private static DadosFornecedor dados(String razao, String documento) {
        return new DadosFornecedor(razao, null, documento, null, null, null, null, null, null);
    }

    @Test
    void gravaSemMascaraComCnpjAlfanumericoEMostraComMascara() {
        long id = fornecedores.criar(admin, new DadosFornecedor("  Distribuidora   Teste Ltda ", "Teste",
                "12.abc.345/01de-35", "(16) 3333-4444", "Compras@Teste.com.br", "Paulo", "Ribeirão Preto", "sp",
                "Entrega às segundas"));
        Fornecedor f = fornecedores.buscar(admin, id);
        assertThat(f.razaoSocial()).isEqualTo("Distribuidora Teste Ltda");
        assertThat(f.cpfCnpj()).isEqualTo("12ABC34501DE35");
        assertThat(f.documentoFormatado()).isEqualTo("12.ABC.345/01DE-35");
        assertThat(f.telefone()).isEqualTo("1633334444");
        assertThat(f.telefoneFormatado()).isEqualTo("(16) 3333-4444");
        assertThat(f.email()).isEqualTo("compras@teste.com.br");
        assertThat(f.uf()).isEqualTo("SP");
        assertThat(f.cidadeUf()).isEqualTo("Ribeirão Preto/SP");
        assertThat(f.ativo()).isTrue();
        assertThat(f.criadoEm()).isEqualTo(OffsetDateTime.now(t.relogio));
    }

    @Test
    void validacoesDoCadastro() {
        assertThatThrownBy(() -> fornecedores.criar(admin, dados("  ", null)))
                .hasMessage("Informe a razão social do fornecedor.");
        assertThatThrownBy(() -> fornecedores.criar(admin, dados("Fornecedor", "12ABC34501DE34")))
                .hasMessage("CNPJ 12.ABC.345/01DE-34 inválido: o dígito verificador não confere.");
        assertThatThrownBy(() -> fornecedores.criar(admin, dados("Fornecedor", "529.982.247-24")))
                .hasMessage("CPF 529.982.247-24 inválido: o dígito verificador não confere.");
        assertThatThrownBy(() -> fornecedores.criar(admin, new DadosFornecedor("Fornecedor", null, null, "3333-4444",
                null, null, null, null, null))).hasMessageStartingWith("Telefone inválido");
        assertThatThrownBy(() -> fornecedores.criar(admin, new DadosFornecedor("Fornecedor", null, null, null,
                "sem-arroba", null, null, null, null))).hasMessageStartingWith("E-mail inválido");
        assertThatThrownBy(() -> fornecedores.criar(admin, new DadosFornecedor("Fornecedor", null, null, null, null,
                null, null, "XX", null))).hasMessageStartingWith("UF inválida");
        long cpf = fornecedores.criar(admin, dados("Produtor Rural José", "52998224725"));
        assertThat(fornecedores.buscar(admin, cpf).documentoFormatado()).isEqualTo("529.982.247-25");
    }

    @Test
    void cpfCnpjRepetidoDizQualFornecedorJaOTem() {
        long id = fornecedores.criar(admin, dados("Distribuidora Teste Ltda", "11222333000181"));
        assertThatThrownBy(() -> fornecedores.criar(admin, dados("Outra", "11.222.333/0001-81")))
                .hasMessage("Não foi possível salvar: o CNPJ 11.222.333/0001-81 já pertence ao fornecedor "
                        + "\"Distribuidora Teste Ltda\" (código " + id + ").");
        // Alterar o próprio fornecedor mantendo o documento é permitido.
        t.relogio.avancar(Duration.ofMinutes(1));
        fornecedores.alterar(admin, id, dados("Distribuidora Teste S.A.", "11222333000181"));
        Fornecedor alterado = fornecedores.buscar(admin, id);
        assertThat(alterado.razaoSocial()).isEqualTo("Distribuidora Teste S.A.");
        assertThat(alterado.atualizadoEm()).isEqualTo(OffsetDateTime.now(t.relogio));
    }

    @Test
    void buscaPorNomeSemAcentoPorCidadeEPeloDocumento() {
        long a = fornecedores.criar(admin, new DadosFornecedor("Laticínios Água Boa", "Água Boa", "11222333000181",
                null, null, null, "Franca", "SP", null));
        long b = fornecedores.criar(admin, dados("Bebidas Sertãozinho", "12ABC34501DE35"));
        assertThat(fornecedores.pesquisar(admin, "agua", false)).extracting(Fornecedor::id).containsExactly(a);
        assertThat(fornecedores.pesquisar(admin, "FRANCA", false)).extracting(Fornecedor::id).containsExactly(a);
        assertThat(fornecedores.pesquisar(admin, "12.abc", false)).extracting(Fornecedor::id).containsExactly(b);
        assertThat(fornecedores.pesquisar(admin, "", false)).extracting(Fornecedor::id).containsExactly(b, a);
        fornecedores.alterarSituacao(admin, b, false);
        assertThat(fornecedores.pesquisar(admin, "", false)).extracting(Fornecedor::id).containsExactly(a);
        assertThat(fornecedores.pesquisar(admin, "", true)).extracting(Fornecedor::id).containsExactly(b, a);
    }

    @Test
    void comEntradaNaoExcluiEOfereceInativar() {
        long livre = fornecedores.criar(admin, dados("Sem entradas", null));
        fornecedores.excluir(admin, livre);
        assertThat(fornecedores.pesquisar(admin, "", true)).isEmpty();

        long usado = fornecedores.criar(admin, dados("Com entrada", null));
        t.compras.criar(admin, new DadosEntrada(usado, "1", null, null));
        assertThatThrownBy(() -> fornecedores.excluir(admin, usado)).isInstanceOf(RegistroEmUsoException.class)
                .hasMessageContaining("tem 1 entrada de mercadoria").hasMessageContaining("inativá-lo");
        fornecedores.alterarSituacao(admin, usado, false);
        assertThat(fornecedores.buscar(admin, usado).ativo()).isFalse();
    }

    @Test
    void semAPermissaoFornecedoresOServiceRecusa() {
        t.usuarios.criar(admin, "comprador", "Comprador", false, EnumSet.of(Permissao.COMPRAS), "inicial1",
                "inicial1");
        Sessao comprador = t.acesso.entrar("comprador", "inicial1");
        assertThatThrownBy(() -> fornecedores.criar(comprador, dados("X", null)))
                .isInstanceOf(PermissaoNegadaException.class)
                .hasMessage("Você não tem permissão para usar a rotina Fornecedores. Procure o supervisor.");
        assertThatThrownBy(() -> fornecedores.pesquisar(comprador, "", true))
                .isInstanceOf(PermissaoNegadaException.class);
    }
}
