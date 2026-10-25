package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.CustoAtualizado;
import br.com.varesync.model.DadosEntrada;
import br.com.varesync.model.DadosFornecedor;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.EntradaResumo;
import br.com.varesync.model.HistoricoPreco;
import br.com.varesync.model.ItemEntrada;
import br.com.varesync.model.MovimentoKardex;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.ResultadoEntrada;
import br.com.varesync.model.StatusEntrada;
import br.com.varesync.model.SugestaoReajuste;
import br.com.varesync.model.TipoAjuste;
import br.com.varesync.model.TipoMovimento;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Entrada de mercadoria (6.10, Fase 7): custo médio ponderado com o banco (A.5), histórico de custo, nota repetida,
 * entrada confirmada imutável, aviso do total, sugestão de reajuste e permissão. Ao fim de cada teste,
 * {@code IntegracaoBase} confere que {@code vw_estoque_inconsistente} está vazia.
 */
class EntradaServiceIT extends IntegracaoBase {

    private AcessoTeste t;
    private EntradaService compras;
    private Sessao admin;
    private long fornecedor;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        compras = t.compras;
        admin = t.supervisor();
        fornecedor = t.fornecedores.criar(admin, new DadosFornecedor("Distribuidora Teste Ltda", "Teste",
                "12ABC34501DE35", null, null, null, null, null, null));
    }

    private static BigDecimal v(String valor) {
        return new BigDecimal(valor);
    }

    private long produto(String nome, String unidade, String preco, String custo, String inicial, String codigo) {
        List<CodigoBarras> codigos = codigo == null ? List.of() : List.of(new CodigoBarras(codigo, true));
        return t.produtos.criar(admin, new DadosProduto(nome, null, unidade, v(preco), v(custo), null, null, codigos,
                inicial == null ? null : v(inicial)));
    }

    private long nota(String numero) {
        return compras.criar(admin, new DadosEntrada(fornecedor, numero, LocalDate.now(t.relogio), null));
    }

    private BigDecimal valor(String sql) throws Exception {
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as(sql).isTrue();
            return rs.getBigDecimal(1);
        }
    }

    private BigDecimal saldo(long produto) throws Exception {
        return valor("SELECT quantidade FROM produto_estoque WHERE produto_id = " + produto);
    }

    private BigDecimal custo(long produto) throws Exception {
        return valor("SELECT custo FROM produto WHERE id = " + produto);
    }

    // ----- Critérios de aceite da Fase 7 -----

    /** A.5: saldo 7 a 3,2000 + entrada de 12 a 3,5000 → 3,3895; saldo 19, último custo 3,5000, kardex "Entrada nº X". */
    @Test
    void custoMedioDoApendiceA5ComOBanco() throws Exception {
        long cafe = produto("Café 500 g", "UN", "18.90", "3.2000", "7", "7891000315507");
        long id = nota("1234");
        compras.adicionarItem(admin, id, cafe, v("12"), v("3.5000"), null);
        t.relogio.avancar(Duration.ofMinutes(5));

        ResultadoEntrada r = compras.confirmar(admin, id);

        assertThat(custo(cafe)).isEqualByComparingTo("3.3895");
        assertThat(valor("SELECT ultimo_custo FROM produto WHERE id = " + cafe)).isEqualByComparingTo("3.5");
        assertThat(saldo(cafe)).isEqualByComparingTo("19");
        assertThat(r.custos()).singleElement().satisfies(c -> {
            assertThat(c.saldoAnterior()).isEqualByComparingTo("7");
            assertThat(c.custoAnterior()).isEqualByComparingTo("3.2");
            assertThat(c.custoNovo()).isEqualTo(v("3.3895"));
        });
        assertThat(r.entrada().status()).isEqualTo(StatusEntrada.CONFIRMADA);
        assertThat(r.entrada().confirmadoEm()).isEqualTo(OffsetDateTime.now(t.relogio));
        assertThat(r.entrada().confirmadoPorNome()).isEqualTo("Ana Admin");

        List<MovimentoKardex> kardex = t.estoque.kardex(admin, cafe, t.estoque.periodoPadrao()).movimentos();
        MovimentoKardex entrada = kardex.get(kardex.size() - 1);
        assertThat(entrada.tipo()).isEqualTo(TipoMovimento.ENTRADA_COMPRA);
        assertThat(entrada.quantidade()).isEqualByComparingTo("12");
        assertThat(entrada.custoUnitario()).isEqualByComparingTo("3.5");
        assertThat(entrada.saldoPosterior()).isEqualByComparingTo("19");
        assertThat(entrada.origem()).isEqualTo("Entrada nº " + id);
    }

    @Test
    void outrosVetoresDeA5SaldoPositivoZeroENegativo() throws Exception {
        long a = produto("Produto A", "UN", "9.90", "2.5000", "10", null);
        long zero = produto("Produto zero", "UN", "9.90", "3.2000", null, null);
        long negativo = produto("Produto negativo", "UN", "9.90", "3.2000", null, null);
        t.estoque.ajustar(admin, negativo, TipoAjuste.SAIDA, v("3"), "Venda sem estoque", null);
        long id = nota("77");
        compras.adicionarItem(admin, id, a, v("20"), v("3.10"), null);
        compras.adicionarItem(admin, id, zero, v("12"), v("3.5"), null);
        compras.adicionarItem(admin, id, negativo, v("12"), v("3.5"), null);
        compras.confirmar(admin, id);

        assertThat(custo(a)).isEqualByComparingTo("2.9000");
        assertThat(custo(zero)).as("saldo 0 usa o custo da entrada").isEqualByComparingTo("3.5000");
        assertThat(custo(negativo)).as("saldo −3 usa o custo da entrada").isEqualByComparingTo("3.5000");
        assertThat(saldo(negativo)).isEqualByComparingTo("9");
    }

    @Test
    void historicoDeCustoComUsuarioEData() throws Exception {
        long cafe = produto("Café 500 g", "UN", "18.90", "3.2000", "7", null);
        t.relogio.avancar(Duration.ofDays(1));
        long id = nota("1234");
        compras.adicionarItem(admin, id, cafe, v("12"), v("3.5"), null);
        compras.confirmar(admin, id);

        List<HistoricoPreco> historico = t.produtos.historico(admin, cafe);
        HistoricoPreco ultimo = historico.get(0);
        assertThat(ultimo.custoAnterior()).isEqualByComparingTo("3.2");
        assertThat(ultimo.custoNovo()).isEqualByComparingTo("3.3895");
        assertThat(ultimo.precoAnterior()).isEqualByComparingTo(ultimo.precoNovo());
        assertThat(ultimo.usuarioNome()).isEqualTo("Ana Admin");
        assertThat(ultimo.criadoEm()).isEqualTo(OffsetDateTime.now(t.relogio));
        assertThat(t.auditoria(AcaoAuditoria.ENTRADA_CONFIRMADA)).singleElement().asString()
                .startsWith("Entrada nº " + id + " confirmada: Distribuidora Teste Ltda, NF 1234, 1 item, R$ 42,00")
                .contains("\"Café 500 g\" (código " + cafe + ") de R$ 3,2000 para R$ 3,3895");
    }

    @Test
    void notaRepetidaDoMesmoFornecedorERecusadaComMensagemClara() {
        long primeira = nota("1234");
        assertThatThrownBy(() -> nota(" 1234 ")).isInstanceOf(NegocioException.class)
                .hasMessage("Esta nota já foi lançada (entrada nº " + primeira + "): NF 1234 de Distribuidora Teste "
                        + "Ltda.");
        long outroFornecedor = t.fornecedores.criar(admin, new DadosFornecedor("Outro Fornecedor", null, null, null,
                null, null, null, null, null));
        long mesmaNumeracao = compras.criar(admin, new DadosEntrada(outroFornecedor, "1234", null, null));
        assertThat(mesmaNumeracao).isNotEqualTo(primeira);
        long semNumero = compras.criar(admin, new DadosEntrada(fornecedor, "  ", null, null));
        assertThat(compras.buscar(admin, semNumero).numeroDocumento()).isNull();
        // Corrigir outra entrada para a mesma nota também é recusado.
        long outra = nota("999");
        assertThatThrownBy(() -> compras.alterarDados(admin, outra, new DadosEntrada(fornecedor, "1234", null, null)))
                .hasMessageStartingWith("Esta nota já foi lançada (entrada nº " + primeira + ")");
    }

    @Test
    void entradaConfirmadaEImutavel() throws Exception {
        long cafe = produto("Café 500 g", "UN", "18.90", "3.2000", "7", null);
        long id = nota("1234");
        long item = compras.adicionarItem(admin, id, cafe, v("12"), v("3.5"), null);
        compras.confirmar(admin, id);
        String mensagem = "A entrada nº " + id + " já foi confirmada e não pode mais ser alterada. Para corrigir o "
                + "estoque, use Estoque › Ajustar estoque.";
        long outro = produto("Açúcar 1 kg", "UN", "4.99", "3.60", null, null);
        assertThatThrownBy(() -> compras.adicionarItem(admin, id, outro, v("1"), v("1"), null)).hasMessage(mensagem);
        assertThatThrownBy(() -> compras.alterarItem(admin, id, item, v("1"), v("1"), null)).hasMessage(mensagem);
        assertThatThrownBy(() -> compras.removerItem(admin, id, item)).hasMessage(mensagem);
        assertThatThrownBy(() -> compras.alterarDados(admin, id, new DadosEntrada(fornecedor, "1", null, null)))
                .hasMessage(mensagem);
        assertThatThrownBy(() -> compras.excluir(admin, id)).hasMessage(mensagem);
        assertThatThrownBy(() -> compras.confirmar(admin, id)).hasMessage(mensagem);
        assertThat(saldo(cafe)).as("não entrou duas vezes").isEqualByComparingTo("19");
    }

    // ----- Itens e nota -----

    @Test
    void valorTotalDaLinhaCalculaOCustoComQuatroCasas() {
        long refri = produto("Refrigerante 2 L", "UN", "9.90", "5.00", null, null);
        long id = nota("1");
        long item = compras.adicionarItem(admin, id, refri, v("12"), null, v("10.00"));
        ItemEntrada lido = compras.itens(admin, id).get(0);
        assertThat(lido.custoUnitario()).isEqualTo(v("0.8333"));
        assertThat(lido.valorTotal()).isEqualByComparingTo("10.00");
        compras.alterarItem(admin, id, item, v("3"), v("1.2345"), null);
        lido = compras.itens(admin, id).get(0);
        assertThat(lido.quantidade()).isEqualByComparingTo("3");
        assertThat(lido.valorTotal()).as("round(3 × 1,2345) = 3,70").isEqualTo(v("3.70"));
    }

    @Test
    void validacoesDosItens() {
        long un = produto("Sabão em barra", "UN", "4.99", "2.00", null, "7891234567895");
        long kg = produto("Queijo muçarela", "KG", "49.90", "30.00", null, null);
        long inativo = produto("Produto inativo", "UN", "4.99", "2.00", null, null);
        t.produtos.alterarSituacao(admin, inativo, false);
        long id = nota("1");
        assertThatThrownBy(() -> compras.adicionarItem(admin, id, un, v("1.5"), v("2"), null))
                .hasMessageContaining("deve ser um número inteiro");
        assertThatThrownBy(() -> compras.adicionarItem(admin, id, un, v("0"), v("2"), null))
                .hasMessage("A quantidade deve ser maior que zero.");
        assertThatThrownBy(() -> compras.adicionarItem(admin, id, un, v("1"), null, null))
                .hasMessage("Informe o custo unitário ou o valor total da linha.");
        assertThatThrownBy(() -> compras.adicionarItem(admin, id, un, v("1"), v("-1"), null))
                .hasMessage("O custo unitário não pode ser negativo.");
        assertThatThrownBy(() -> compras.adicionarItem(admin, id, un, v("1"), v("1.12345"), null))
                .hasMessage("O custo unitário pode ter no máximo 4 casas decimais.");
        assertThatThrownBy(() -> compras.adicionarItem(admin, id, inativo, v("1"), v("1"), null))
                .hasMessageContaining("está inativo");
        compras.adicionarItem(admin, id, kg, v("2.345"), v("30.10"), null);
        compras.adicionarItem(admin, id, un, v("10"), v("2.1"), null);
        assertThatThrownBy(() -> compras.adicionarItem(admin, id, un, v("1"), v("2"), null))
                .hasMessage("O produto Sabão em barra já está nesta entrada. Altere a quantidade da linha em vez de "
                        + "lançá-lo de novo.");
        assertThat(compras.produtoPorCodigo(admin, "7891234567895").id()).isEqualTo(un);
        assertThat(compras.produtoPorCodigo(admin, String.valueOf(kg)).id()).isEqualTo(kg);
        assertThatThrownBy(() -> compras.produtoPorCodigo(admin, String.valueOf(inativo)))
                .hasMessageContaining("está inativo");
        assertThat(compras.buscar(admin, id).somaItens()).as("2,345 × 30,10 = 70,58 + 21,00")
                .isEqualByComparingTo("91.58");
    }

    @Test
    void validacoesDaNotaEEntradaSemItens() {
        assertThatThrownBy(() -> compras.criar(admin, new DadosEntrada(null, "1", null, null)))
                .hasMessage("Escolha o fornecedor.");
        assertThatThrownBy(() -> compras.criar(admin, new DadosEntrada(fornecedor, "1",
                LocalDate.now(t.relogio).plusDays(1), null))).hasMessage("A data de emissão não pode ser depois de hoje.");
        assertThatThrownBy(() -> compras.criar(admin, new DadosEntrada(fornecedor, "123456789012345678901", null,
                null))).hasMessage("O número do documento pode ter no máximo 20 caracteres.");
        assertThatThrownBy(() -> compras.criar(admin, new DadosEntrada(fornecedor, "1", null, v("-1"))))
                .hasMessage("O valor total da nota não pode ser negativo.");
        t.fornecedores.alterarSituacao(admin, fornecedor, false);
        assertThatThrownBy(() -> nota("1")).hasMessage("O fornecedor \"Distribuidora Teste Ltda\" está inativo.");
        t.fornecedores.alterarSituacao(admin, fornecedor, true);

        long id = nota("1");
        assertThatThrownBy(() -> compras.confirmar(admin, id))
                .hasMessage("A entrada não tem itens. Inclua os produtos da nota antes de confirmar.");
        // Fornecedor inativado depois continua valendo na própria entrada.
        t.fornecedores.alterarSituacao(admin, fornecedor, false);
        compras.alterarDados(admin, id, new DadosEntrada(fornecedor, "2", null, v("10")));
        assertThat(compras.fornecedores(admin, fornecedor)).extracting(f -> f.id()).containsExactly(fornecedor);
        assertThat(compras.fornecedores(admin, null)).isEmpty();
        compras.excluir(admin, id);
        assertThat(compras.listar(admin, true)).isEmpty();
    }

    @Test
    void avisoQuandoASomaDosItensDifereDoTotalDaNota() {
        long cafe = produto("Café 500 g", "UN", "18.90", "3.2000", null, null);
        long id = compras.criar(admin, new DadosEntrada(fornecedor, "1", null, v("50.00")));
        compras.adicionarItem(admin, id, cafe, v("12"), v("3.5"), null);
        EntradaResumo resumo = compras.buscar(admin, id);
        assertThat(resumo.somaItens()).isEqualByComparingTo("42.00");
        assertThat(resumo.avisoTotal()).hasValue("A soma dos itens (R$ 42,00) é diferente do valor total da nota "
                + "(R$ 50,00): faltam R$ 8,00 nos itens. Confira as quantidades e os custos.");
        // O aviso não impede a confirmação.
        assertThat(compras.confirmar(admin, id).entrada().status()).isEqualTo(StatusEntrada.CONFIRMADA);
    }

    @Test
    void sugereReajusteQuandoAMargemFicaAbaixoDaMinima() {
        // Margem mínima da V2: 15%. Preço 4,00 e custo 3,20 = 20%; com custo 3,60, a margem cai para 10%.
        long acucar = produto("Açúcar 1 kg", "UN", "4.00", "3.20", null, null);
        long cafe = produto("Café 500 g", "UN", "18.90", "3.20", null, null);
        long id = nota("1");
        compras.adicionarItem(admin, id, acucar, v("10"), v("3.60"), null);
        compras.adicionarItem(admin, id, cafe, v("10"), v("3.50"), null);
        ResultadoEntrada r = compras.confirmar(admin, id);
        assertThat(r.margemMinimaPct()).isEqualByComparingTo("15");
        assertThat(r.custos()).extracting(CustoAtualizado::produtoId).containsExactly(acucar, cafe);
        assertThat(r.sugestoes()).singleElement().satisfies(s -> {
            assertThat(s.produtoId()).isEqualTo(acucar);
            assertThat(s.custoNovo()).isEqualByComparingTo("3.60");
            assertThat(s.margem()).isEqualByComparingTo("10.00");
            assertThat(s.precoSugerido()).as("3,60 ÷ 0,85 = 4,2353 → 4,24 (para cima)").isEqualByComparingTo("4.24");
        });
        assertThat(r.sugestoes()).extracting(SugestaoReajuste::produtoNome).containsExactly("Açúcar 1 kg");
    }

    @Test
    void semAPermissaoComprasOServiceRecusa() {
        t.usuarios.criar(admin, "cadastro", "Cadastro", false, EnumSet.of(Permissao.FORNECEDORES), "inicial1",
                "inicial1");
        Sessao cadastro = t.acesso.entrar("cadastro", "inicial1");
        String mensagem = "Você não tem permissão para usar a rotina Compras. Procure o supervisor.";
        assertThatThrownBy(() -> compras.criar(cadastro, new DadosEntrada(fornecedor, "1", null, null)))
                .isInstanceOf(PermissaoNegadaException.class).hasMessage(mensagem);
        long id = nota("1");
        assertThatThrownBy(() -> compras.confirmar(cadastro, id)).hasMessage(mensagem);
        assertThatThrownBy(() -> compras.listar(cadastro, true)).hasMessage(mensagem);
    }
}
