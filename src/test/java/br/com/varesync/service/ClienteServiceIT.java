package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Cliente;
import br.com.varesync.model.ClienteVenda;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.DadosCliente;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.HistoricoCliente;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.report.RelDadosCliente;
import br.com.varesync.report.ResultadoRelatorio;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cadastro de clientes (F12.1), histórico de compras (F12.2) e LGPD (F12.6): CPF/CNPJ único e com DV, busca,
 * consentimento com data, excluir ou inativar, exportação dos dados e anonimização que mantém as vendas.
 */
class ClienteServiceIT extends IntegracaoBase {

    private AcessoTeste t;
    private ClienteService clientes;
    private Sessao admin;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        clientes = t.clientes;
        admin = t.supervisor();
    }

    private static DadosCliente dados(String nome, String cpfCnpj) {
        return DadosCliente.rapido(nome, cpfCnpj);
    }

    private static DadosCliente completo(boolean consentimento) {
        return new DadosCliente("  Maria   da Silva ", "529.982.247-25", "(16) 99999-8888", "Maria@Exemplo.com.br",
                LocalDate.of(1985, 3, 25), "14010-000", "Rua das Flores", "120", "apto 3", "Centro",
                "Ribeirão Preto", "sp", "Prefere PIX", consentimento);
    }

    private Sessao usuario(String login, Permissao... permissoes) {
        EnumSet<Permissao> conjunto = EnumSet.noneOf(Permissao.class);
        conjunto.addAll(List.of(permissoes));
        t.usuarios.criar(admin, login, "Usuário " + login, false, conjunto, "inicial1", "inicial1");
        return t.acesso.entrar(login, "inicial1");
    }

    private long produto(String nome, String unidade, String preco, String codigo) {
        List<CodigoBarras> codigos = codigo == null ? List.of() : List.of(new CodigoBarras(codigo, true));
        return t.produtos.criar(admin, new DadosProduto(nome, null, unidade, new BigDecimal(preco),
                new BigDecimal("1.00"), null, null, codigos, new BigDecimal("100")));
    }

    /** Venda paga em dinheiro, identificada para o cliente (nulo = sem cliente). @return número da venda */
    private long vender(Long clienteId, long produtoId, String quantidade) {
        Carrinho carrinho = new Carrinho();
        t.vendas.adicionarItem(admin, carrinho, t.vendas.produtoParaVenda(admin, produtoId),
                new BigDecimal(quantidade));
        if (clienteId != null) {
            t.vendas.identificarCliente(admin, carrinho, clienteId);
        }
        Recebimento r = new Recebimento(carrinho.total());
        r.adicionar(t.vendas.formasAtivas(admin).stream().filter(f -> f.tipo() == TipoFormaPagamento.DINHEIRO)
                .findFirst().orElseThrow(), carrinho.total());
        return t.vendas.finalizar(admin, carrinho, r).vendaId();
    }

    @Test
    void gravaSemMascaraComConsentimentoDatadoEMostraComMascara() {
        long id = clientes.criar(admin, completo(true));
        Cliente c = clientes.buscar(admin, id);
        assertThat(c.nome()).isEqualTo("Maria da Silva");
        assertThat(c.cpfCnpj()).isEqualTo("52998224725");
        assertThat(c.documentoFormatado()).isEqualTo("529.982.247-25");
        assertThat(c.telefoneFormatado()).isEqualTo("(16) 99999-8888");
        assertThat(c.email()).isEqualTo("maria@exemplo.com.br");
        assertThat(c.cepFormatado()).isEqualTo("14010-000");
        assertThat(c.endereco()).isEqualTo("Rua das Flores, 120, apto 3 — Centro — Ribeirão Preto/SP — CEP 14010-000");
        assertThat(c.consentimentoMarketing()).isTrue();
        assertThat(c.consentimentoEm()).isEqualTo(OffsetDateTime.now(t.relogio));
        assertThat(c.criadoEm()).isEqualTo(OffsetDateTime.now(t.relogio));
        assertThat(c.criadoPorNome()).isEqualTo("Ana Admin");
        assertThat(c.situacao()).isEqualTo("Ativo");
    }

    @Test
    void consentimentoMantidoConservaADataEDesmarcadoApaga() {
        long id = clientes.criar(admin, completo(true));
        OffsetDateTime consentiu = OffsetDateTime.now(t.relogio);
        t.relogio.avancar(Duration.ofDays(3));
        clientes.alterar(admin, id, completo(true));
        Cliente alterado = clientes.buscar(admin, id);
        assertThat(alterado.consentimentoEm()).as("a data é a do consentimento, não a da alteração")
                .isEqualTo(consentiu);
        assertThat(alterado.atualizadoEm()).isEqualTo(OffsetDateTime.now(t.relogio));

        t.relogio.avancar(Duration.ofDays(1));
        clientes.alterar(admin, id, completo(false));
        assertThat(clientes.buscar(admin, id).consentimentoMarketing()).isFalse();
        assertThat(clientes.buscar(admin, id).consentimentoEm()).isNull();

        t.relogio.avancar(Duration.ofDays(1));
        clientes.alterar(admin, id, completo(true));
        assertThat(clientes.buscar(admin, id).consentimentoEm()).isEqualTo(OffsetDateTime.now(t.relogio));
    }

    @Test
    void cpfInvalidoERecusadoECpfRepetidoMostraOClienteQueJaOTem() {
        // Aceite F12: CPF inválido é recusado; CPF repetido mostra o cliente que já o tem.
        assertThatThrownBy(() -> clientes.criar(admin, dados("Maria", "529.982.247-24")))
                .hasMessage("CPF 529.982.247-24 inválido: o dígito verificador não confere.");
        assertThatThrownBy(() -> clientes.criar(admin, dados("Maria", "111.111.111-11")))
                .hasMessage("CPF 111.111.111-11 inválido: o dígito verificador não confere.");
        long maria = clientes.criar(admin, dados("Maria da Silva", "52998224725"));
        assertThatThrownBy(() -> clientes.criar(admin, dados("Outra Maria", "529.982.247-25")))
                .hasMessage("Não foi possível salvar: o CPF 529.982.247-25 já pertence ao cliente \"Maria da Silva\" "
                        + "(código " + maria + ").");
        long padaria = clientes.criar(admin, dados("Padaria", "12.ABC.345/01DE-35"));
        assertThatThrownBy(() -> clientes.alterar(admin, padaria, dados("Padaria", "52998224725")))
                .hasMessageContaining("já pertence ao cliente \"Maria da Silva\"");
        // Alterar o próprio cliente mantendo o documento é permitido.
        clientes.alterar(admin, maria, dados("Maria da Silva Souza", "52998224725"));
        assertThat(clientes.buscar(admin, maria).nome()).isEqualTo("Maria da Silva Souza");
        // Clientes sem CPF não se esbarram no documento único.
        clientes.criar(admin, dados("Sem documento 1", null));
        clientes.criar(admin, dados("Sem documento 2", " "));
    }

    @Test
    void buscaPorNomeSemAcentoPorCpfInteiroOuParteEPorTelefone() {
        long jose = clientes.criar(admin, new DadosCliente("José Antônio", "52998224725", "1633334444", null, null,
                null, null, null, null, null, null, null, null, false));
        long padaria = clientes.criar(admin, new DadosCliente("Padaria Boa Massa", "12ABC34501DE35", "16999998888",
                null, null, null, null, null, null, null, null, null, null, false));
        assertThat(clientes.pesquisar(admin, "JOSE ANTONIO", false)).extracting(Cliente::id).containsExactly(jose);
        assertThat(clientes.pesquisar(admin, "529.982.247-25", false)).extracting(Cliente::id).containsExactly(jose);
        assertThat(clientes.pesquisar(admin, "98224", false)).extracting(Cliente::id).containsExactly(jose);
        assertThat(clientes.pesquisar(admin, "12.abc", false)).extracting(Cliente::id).containsExactly(padaria);
        assertThat(clientes.pesquisar(admin, "(16) 99999", false)).extracting(Cliente::id).containsExactly(padaria);
        assertThat(clientes.pesquisar(admin, "3333-4444", false)).extracting(Cliente::id).containsExactly(jose);
        assertThat(clientes.pesquisar(admin, "", false)).extracting(Cliente::id).containsExactly(jose, padaria);
        assertThat(clientes.pesquisar(admin, "50%", false)).as("% digitado vale como texto").isEmpty();
        clientes.alterarSituacao(admin, padaria, false);
        assertThat(clientes.pesquisar(admin, "", false)).extracting(Cliente::id).containsExactly(jose);
        assertThat(clientes.pesquisar(admin, "", true)).extracting(Cliente::id).containsExactly(jose, padaria);
    }

    @Test
    void semVendaExcluiComVendaOfereceInativar() throws Exception {
        long livre = clientes.criar(admin, dados("Sem vendas", null));
        clientes.excluir(admin, livre);
        assertThat(clientes.pesquisar(admin, "", true)).isEmpty();

        t.caixa.abrir(admin, BigDecimal.ZERO);
        long usado = clientes.criar(admin, dados("Com venda", null));
        vender(usado, produto("Refrigerante cola 2 L", "UN", "5.49", "7891000315507"), "1");
        assertThatThrownBy(() -> clientes.excluir(admin, usado)).isInstanceOf(RegistroEmUsoException.class)
                .hasMessageContaining("tem 1 venda").hasMessageContaining("inativá-lo");
        clientes.alterarSituacao(admin, usado, false);
        assertThat(clientes.buscar(admin, usado).situacao()).isEqualTo("Inativo");
        assertThat(t.contar("SELECT count(*) FROM venda WHERE cliente_id = " + usado)).isEqualTo(1);
    }

    @Test
    void historicoComTotalTicketUltimaCompraEProdutosMaisComprados() {
        t.caixa.abrir(admin, BigDecimal.ZERO);
        long refrigerante = produto("Refrigerante cola 2 L", "UN", "5.00", "7891000315507");
        long queijo = produto("Queijo muçarela", "KG", "40.00", null);
        long maria = clientes.criar(admin, dados("Maria da Silva", "52998224725"));
        long primeira = vender(maria, refrigerante, "2");                  // 10,00
        t.relogio.avancar(Duration.ofHours(2));
        vender(null, refrigerante, "5");                                   // sem cliente: fora do histórico
        t.relogio.avancar(Duration.ofHours(2));
        long segunda = vender(maria, queijo, "0.5");                       // 20,00
        t.relogio.avancar(Duration.ofHours(2));
        long terceira = vender(maria, refrigerante, "1");                  // 5,00
        t.vendas.cancelar(admin, terceira, "Cliente desistiu", null);      // cancelada: fora do histórico

        HistoricoCliente h = clientes.historico(admin, maria);
        assertThat(h.compras()).extracting(HistoricoCliente.Compra::vendaId).containsExactly(segunda, primeira);
        assertThat(h.totalGasto()).isEqualByComparingTo("30.00");
        assertThat(h.ticketMedio()).contains(new BigDecimal("15.00"));
        assertThat(h.ultimaCompra()).contains(h.compras().get(0).dataHora());
        assertThat(h.produtos()).extracting(HistoricoCliente.ProdutoComprado::nome)
                .containsExactly("Queijo muçarela", "Refrigerante cola 2 L"); // empate em compras: maior valor
        assertThat(h.produtos().get(1).quantidade()).isEqualByComparingTo("2");

        long semCompras = clientes.criar(admin, dados("Sem compras", null));
        HistoricoCliente vazio = clientes.historico(admin, semCompras);
        assertThat(vazio.totalGasto()).isEqualByComparingTo("0");
        assertThat(vazio.ticketMedio()).isEmpty();
        assertThat(vazio.ultimaCompra()).isEmpty();
    }

    @Test
    void exportacaoTrazCadastroEComprasERegistraNaAuditoriaSemDadosPessoais() throws Exception {
        t.caixa.abrir(admin, BigDecimal.ZERO);
        long maria = clientes.criar(admin, completo(true));
        long venda = vender(maria, produto("Refrigerante cola 2 L", "UN", "5.00", "7891000315507"), "2");

        ResultadoRelatorio<RelDadosCliente.Linha> dados = clientes.exportar(admin, maria);
        assertThat(dados.descricaoFiltros()).startsWith("Cliente nº " + maria + " — Maria da Silva");
        assertThat(dados.loja()).isEqualTo("Mercado Bom Preço");
        assertThat(dados.linhas()).contains(
                new RelDadosCliente.Linha(RelDadosCliente.CADASTRO, "CPF/CNPJ", "529.982.247-25", null),
                new RelDadosCliente.Linha(RelDadosCliente.CADASTRO, "E-mail", "maria@exemplo.com.br", null),
                new RelDadosCliente.Linha(RelDadosCliente.RESUMO, "Total gasto", "", new BigDecimal("10.00")),
                new RelDadosCliente.Linha(RelDadosCliente.PRODUTOS, "Refrigerante cola 2 L", "2 UN em 1 compra",
                        new BigDecimal("10.00")));
        assertThat(dados.linhas()).anySatisfy(l -> assertThat(l.item()).isEqualTo("Venda nº " + venda));
        assertThat(t.auditoria(AcaoAuditoria.CLIENTE_DADOS_EXPORTADOS)).singleElement().satisfies(d -> assertThat(d)
                .isEqualTo("Dados do cliente nº " + maria + " exportados a pedido do titular (cadastro e 1 compra)")
                .doesNotContain("Maria", "529"));
    }

    @Test
    void anonimizarRemoveOsDadosPessoaisEMantemAsVendas() throws Exception {
        // Aceite F12 (parte A): anonimizar remove os dados pessoais e mantém as vendas.
        t.caixa.abrir(admin, BigDecimal.ZERO);
        long maria = clientes.criar(admin, completo(true));
        long venda = vender(maria, produto("Refrigerante cola 2 L", "UN", "5.00", "7891000315507"), "1");

        assertThatThrownBy(() -> clientes.anonimizar(admin, maria, "Maria"))
                .hasMessage("Para confirmar, digite o nome do cliente exatamente como está no cadastro.");
        clientes.anonimizar(admin, maria, "  maria DA silva ");

        Cliente c = clientes.buscar(admin, maria);
        assertThat(c.nome()).isEqualTo("Cliente anonimizado nº " + maria);
        assertThat(c.anonimizado()).isTrue();
        assertThat(c.ativo()).isFalse();
        assertThat(c.situacao()).isEqualTo("Anonimizado");
        assertThat(List.of(c.cpfCnpj() == null, c.telefone() == null, c.email() == null, c.dataNascimento() == null,
                c.cep() == null, c.logradouro() == null, c.numero() == null, c.complemento() == null,
                c.bairro() == null, c.cidade() == null, c.uf() == null, c.observacao() == null,
                c.consentimentoEm() == null)).containsOnly(true);
        assertThat(c.consentimentoMarketing()).isFalse();
        assertThat(t.contar("SELECT count(*) FROM venda WHERE cliente_id = " + maria + " AND id = " + venda))
                .as("a venda continua com o cliente").isEqualTo(1);
        assertThat(clientes.historico(admin, maria).compras()).hasSize(1);
        assertThat(t.vendas.comprovante(admin, venda).texto()).contains("Cliente: Cliente anonimizado nº " + maria)
                .doesNotContain("Maria").doesNotContainPattern("(?m)^CPF:");
        assertThat(t.auditoria(AcaoAuditoria.CLIENTE_ANONIMIZADO)).singleElement().satisfies(d -> assertThat(d)
                .contains("Cliente nº " + maria + " anonimizado", "1 venda mantida").doesNotContain("Maria", "529"));

        // Anonimizado não volta: não edita, não reativa, não é anonimizado de novo, não aparece no PDV.
        assertThatThrownBy(() -> clientes.alterar(admin, maria, dados("Maria", null)))
                .hasMessageContaining("foi anonimizado");
        assertThatThrownBy(() -> clientes.alterarSituacao(admin, maria, true)).hasMessageContaining("foi anonimizado");
        assertThatThrownBy(() -> clientes.anonimizar(admin, maria, c.nome())).hasMessageContaining("foi anonimizado");
        assertThat(clientes.pesquisarParaVenda(admin, "anonimizado")).isEmpty();
        // O CPF ficou livre para um novo cadastro.
        clientes.criar(admin, dados("Maria da Silva", "52998224725"));
    }

    @Test
    void soOSupervisorAnonimizaESemClientesOCadastroERecusado() {
        long id = clientes.criar(admin, dados("Maria da Silva", null));
        Sessao atendente = usuario("atendente", Permissao.CLIENTES);
        assertThatThrownBy(() -> clientes.anonimizar(atendente, id, "Maria da Silva"))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThat(clientes.pesquisar(atendente, "", false)).hasSize(1);

        Sessao caixa = usuario("caixa", Permissao.PDV);
        assertThatThrownBy(() -> clientes.criar(caixa, dados("X", null)))
                .isInstanceOf(PermissaoNegadaException.class)
                .hasMessage("Você não tem permissão para usar a rotina Clientes. Procure o supervisor.");
        assertThatThrownBy(() -> clientes.pesquisar(caixa, "", true)).isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> clientes.exportar(caixa, id)).isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> clientes.historico(caixa, id)).isInstanceOf(PermissaoNegadaException.class);
    }

    @Test
    void pdvBuscaSoAtivosECadastraRapidoSemAPermissaoClientes() {
        Sessao caixa = usuario("caixa", Permissao.PDV);
        long maria = clientes.criar(admin, dados("Maria da Silva", "52998224725"));
        long inativo = clientes.criar(admin, dados("Maria Inativa", "11222333000181"));
        clientes.alterarSituacao(admin, inativo, false);

        assertThat(clientes.pesquisarParaVenda(caixa, "maria")).extracting(ClienteVenda::id).containsExactly(maria);
        assertThat(clientes.pesquisarParaVenda(caixa, "529.982.247-25")).singleElement()
                .satisfies(c -> assertThat(c.documentoOculto()).isEqualTo("***.982.247-**"));
        assertThat(clientes.pesquisarParaVenda(caixa, "11.222.333/0001-81")).as("inativo pelo CNPJ").isEmpty();

        ClienteVenda novo = clientes.cadastrarRapido(caixa, " João  Pedro ", "123.456.789-09");
        assertThat(novo.nome()).isEqualTo("João Pedro");
        assertThat(novo.cpfCnpj()).isEqualTo("12345678909");
        assertThat(novo.ativo()).isTrue();
        assertThat(clientes.buscar(admin, novo.id()).criadoPorNome()).isEqualTo("Usuário caixa");
        assertThatThrownBy(() -> clientes.cadastrarRapido(caixa, "Outro", "52998224725"))
                .hasMessageContaining("já pertence ao cliente \"Maria da Silva\"");
        Sessao semPdv = usuario("estoquista", Permissao.ESTOQUE);
        assertThatThrownBy(() -> clientes.cadastrarRapido(semPdv, "X", null))
                .isInstanceOf(PermissaoNegadaException.class);
    }
}
