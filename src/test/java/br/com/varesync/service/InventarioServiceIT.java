package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Categoria;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.InclusaoInventario;
import br.com.varesync.model.InventarioResumo;
import br.com.varesync.model.ItemContagem;
import br.com.varesync.model.ItemDivergencia;
import br.com.varesync.model.MovimentoKardex;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.ResultadoInventario;
import br.com.varesync.model.StatusInventario;
import br.com.varesync.model.TipoAjuste;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.model.TipoMovimento;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Inventário (6.7, Fase 5): criar e incluir itens, contagem cega, divergências (R13), aplicar pela diferença
 * capturada na contagem (A.8) e cancelar. Ao fim de cada teste, {@code IntegracaoBase} confere que
 * {@code vw_estoque_inconsistente} está vazia.
 */
class InventarioServiceIT extends IntegracaoBase {

    private static final String SEM_PERMISSAO =
            "Você não tem permissão para usar a rotina Inventário. Procure o supervisor.";

    private AcessoTeste t;
    private InventarioService inventarios;
    private Sessao admin;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        inventarios = t.inventarios;
        admin = t.supervisor();
    }

    private static BigDecimal v(String valor) {
        return new BigDecimal(valor);
    }

    private long produto(String nome, String unidade, String custo, String inicial, String codigo, Long categoria) {
        List<CodigoBarras> codigos = codigo == null ? List.of() : List.of(new CodigoBarras(codigo, true));
        return t.produtos.criar(admin, new DadosProduto(nome, categoria, unidade, v("9.90"), v(custo), null, null,
                codigos, inicial == null ? null : v(inicial)));
    }

    private long categoria(String nome) {
        return t.categorias.listar(admin, false).stream().filter(c -> c.nome().equals(nome)).findFirst()
                .orElseThrow().id();
    }

    private long criarCom(long... produtos) {
        long id = inventarios.criar(admin, "Conferência da semana");
        for (long p : produtos) {
            inventarios.incluirProduto(admin, id, p);
        }
        return id;
    }

    private ItemContagem item(long inventario, long produto) {
        return inventarios.itensContagem(admin, inventario).stream().filter(i -> i.produtoId() == produto)
                .findFirst().orElseThrow();
    }

    private ItemContagem contar(long inventario, long produto, String quantidade) {
        return inventarios.registrarContagem(admin, inventario, item(inventario, produto).itemId(), v(quantidade));
    }

    private ItemDivergencia divergencia(ResultadoInventario r, long produto) {
        return r.itens().stream().filter(i -> i.produtoId() == produto).findFirst().orElseThrow();
    }

    private BigDecimal saldo(long produtoId) throws Exception {
        return umValor("SELECT quantidade FROM produto_estoque WHERE produto_id = " + produtoId);
    }

    private BigDecimal umValor(String sql) throws Exception {
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as(sql).isTrue();
            return rs.getBigDecimal(1);
        }
    }

    private List<List<Object>> linhas(String sql) throws Exception {
        List<List<Object>> linhas = new ArrayList<>();
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            int colunas = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<Object> linha = new ArrayList<>();
                for (int c = 1; c <= colunas; c++) {
                    Object valor = rs.getObject(c);
                    linha.add(valor instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : valor);
                }
                linhas.add(linha);
            }
        }
        return linhas;
    }

    /** Venda real no PDV (caixa aberto no terminal de teste), paga em dinheiro. */
    private void vender(long produtoId, String quantidade) {
        if (t.caixa.caixaAberto(admin).isEmpty()) {
            t.caixa.abrir(admin, v("0"));
        }
        Carrinho carrinho = new Carrinho();
        t.vendas.adicionarItem(admin, carrinho, t.vendas.produtoParaVenda(admin, produtoId), v(quantidade));
        Recebimento recebimento = new Recebimento(carrinho.total());
        recebimento.adicionar(t.vendas.formasAtivas(admin).stream()
                .filter(f -> f.tipo() == TipoFormaPagamento.DINHEIRO).findFirst().orElseThrow(), carrinho.total());
        t.vendas.finalizar(admin, carrinho, recebimento);
    }

    private Sessao operador(String login, Permissao... permissoes) {
        EnumSet<Permissao> conjunto = EnumSet.noneOf(Permissao.class);
        conjunto.addAll(List.of(permissoes));
        t.usuarios.criar(admin, login, "Operador " + login, false, conjunto, "inicial1", "inicial1");
        return t.acesso.entrar(login, "inicial1");
    }

    // ----- Critérios de aceite da Fase 5 -----

    /** A.8: sistema 10, contagem 8, sai 1 depois da contagem; aplicar −2 → saldo 7 (igualar daria 8). */
    @Test
    void cenarioA8VendaDepoisDaContagemContinuaDescontada() throws Exception {
        long cafe = produto("Café 500 g", "UN", "21.50", "10", "7891234567895", null);
        long inv = criarCom(cafe);

        ItemContagem contado = contar(inv, cafe, "8");
        assertThat(contado.quantidadeContada()).isEqualByComparingTo("8");
        vender(cafe, "1"); // depois da contagem: sistema 9, físico 7
        assertThat(saldo(cafe)).isEqualByComparingTo("9");

        ResultadoInventario aplicado = inventarios.aplicar(admin, inv);

        assertThat(saldo(cafe)).isEqualByComparingTo("7");
        ItemDivergencia linha = divergencia(aplicado, cafe);
        assertThat(linha.estoqueSistema()).isEqualByComparingTo("10");
        assertThat(linha.diferenca()).isEqualByComparingTo("-2");
        assertThat(linha.valorDiferenca()).isEqualByComparingTo("-43.00");
        assertThat(aplicado.inventario().status()).isEqualTo(StatusInventario.APLICADO);
        assertThat(aplicado.inventario().aplicadoPorNome()).isEqualTo("Ana Admin");
        assertThat(aplicado.inventario().aplicadoEm().toInstant()).isEqualTo(t.relogio.instant());

        assertThat(linhas("""
                SELECT m.tipo, m.quantidade, m.saldo_anterior, m.saldo_posterior, m.custo_unitario, m.motivo
                  FROM movimento_estoque m JOIN inventario_item ii ON ii.id = m.inventario_item_id
                 WHERE ii.inventario_id = %d""".formatted(inv)))
                .containsExactly(Arrays.<Object>asList("INVENTARIO", "-2", "9", "7", "21.5", null));
        List<MovimentoKardex> kardex = t.estoque.kardex(admin, cafe, t.estoque.periodoPadrao()).movimentos();
        assertThat(kardex).extracting(MovimentoKardex::tipo).containsExactly(TipoMovimento.AJUSTE_ENTRADA,
                TipoMovimento.VENDA, TipoMovimento.INVENTARIO);
        assertThat(kardex.get(2).origem()).isEqualTo("Inventário nº " + inv);
        assertThat(kardex.get(1).origem()).startsWith("Venda nº ");
        assertThat(t.auditoria(AcaoAuditoria.INVENTARIO_APLICADO)).containsExactly("Inventário nº " + inv
                + " (Conferência da semana) aplicado: 1 item, 1 contados, 1 com diferença (sobra de R$ 0,00, "
                + "falta de R$ 43,00).");
    }

    @Test
    void itemNaoContadoNaoAlteraOSaldo() throws Exception {
        long cafe = produto("Café 500 g", "UN", "21.50", "10", null, null);
        long arroz = produto("Arroz 5 kg", "UN", "20.00", "6", null, null);
        long inv = criarCom(cafe, arroz);
        contar(inv, cafe, "12");

        ResultadoInventario r = inventarios.aplicar(admin, inv);

        assertThat(saldo(cafe)).isEqualByComparingTo("12");
        assertThat(saldo(arroz)).isEqualByComparingTo("6");
        assertThat(t.contar("SELECT count(*) FROM movimento_estoque WHERE produto_id = " + arroz)).isEqualTo(1);
        assertThat(r.itensNaoContados()).isEqualTo(1);
        assertThat(r.valorSobra()).isEqualByComparingTo("43.00");
        assertThat(divergencia(r, arroz).contado()).isFalse();
        assertThat(t.auditoria(AcaoAuditoria.INVENTARIO_APLICADO)).singleElement().asString()
                .endsWith("2 itens, 1 contados, 1 com diferença (sobra de R$ 43,00, falta de R$ 0,00), "
                        + "1 não contados (ignorados).");
    }

    @Test
    void inventarioAplicadoFicaSomenteLeitura() throws Exception {
        long cafe = produto("Café 500 g", "UN", "21.50", "10", null, null);
        long arroz = produto("Arroz 5 kg", "UN", "20.00", "6", null, null);
        long feijao = produto("Feijão 1 kg", "UN", "8.00", "3", null, null);
        long inv = criarCom(cafe, arroz);
        contar(inv, cafe, "9");
        long itemArroz = item(inv, arroz).itemId();
        inventarios.aplicar(admin, inv);
        String mensagem = "O inventário nº " + inv + " já foi aplicado e não pode mais ser alterado.";

        assertThatThrownBy(() -> inventarios.registrarContagem(admin, inv, itemArroz, v("5")))
                .isInstanceOf(NegocioException.class).hasMessage(mensagem);
        assertThatThrownBy(() -> inventarios.incluirProduto(admin, inv, feijao)).hasMessage(mensagem);
        assertThatThrownBy(() -> inventarios.incluirTodosAtivos(admin, inv)).hasMessage(mensagem);
        assertThatThrownBy(() -> inventarios.removerItem(admin, inv, itemArroz)).hasMessage(mensagem);
        assertThatThrownBy(() -> inventarios.aplicar(admin, inv)).hasMessage(mensagem);
        assertThatThrownBy(() -> inventarios.cancelar(admin, inv)).hasMessage(mensagem);

        assertThat(saldo(cafe)).isEqualByComparingTo("9");
        assertThat(saldo(arroz)).isEqualByComparingTo("6");
        assertThat(inventarios.itensContagem(admin, inv)).hasSize(2);
        assertThat(inventarios.buscar(admin, inv).status()).isEqualTo(StatusInventario.APLICADO);
        assertThat(t.contar("SELECT count(*) FROM movimento_estoque WHERE tipo = 'INVENTARIO'")).isEqualTo(1);
    }

    @Test
    void produtoNaoEntraEmDoisInventariosAbertos() throws Exception {
        long bebidas = categoria("Bebidas");
        long refri = produto("Refrigerante cola 2 L", "UN", "3.20", "10", "7891000315507", bebidas);
        long agua = produto("Água mineral 500 ml", "UN", "0.90", "24", null, bebidas);
        long primeiro = criarCom(refri);
        long segundo = inventarios.criar(admin, "Bebidas");

        assertThatThrownBy(() -> inventarios.incluirProduto(admin, segundo, refri))
                .isInstanceOf(NegocioException.class)
                .hasMessage("O produto Refrigerante cola 2 L já está no inventário nº " + primeiro
                        + ", que está aberto. Um produto não pode estar em dois inventários abertos.");
        assertThatThrownBy(() -> inventarios.incluirPorCodigo(admin, segundo, "7891000315507"))
                .hasMessageContaining("já está no inventário nº " + primeiro);
        assertThatThrownBy(() -> inventarios.incluirProduto(admin, primeiro, refri))
                .hasMessage("O produto Refrigerante cola 2 L já está neste inventário.");

        InclusaoInventario porCategoria = inventarios.incluirCategoria(admin, segundo, bebidas);
        assertThat(porCategoria.incluidos()).isEqualTo(1);
        assertThat(porCategoria.emOutroInventario())
                .containsExactly("Refrigerante cola 2 L (inventário nº " + primeiro + ")");
        assertThat(inventarios.itensContagem(admin, segundo)).extracting(ItemContagem::produtoId)
                .containsExactly(agua);
        assertThat(t.contar("SELECT count(*) FROM inventario_item WHERE produto_id = " + refri)).isEqualTo(1);

        // aplicado (ou cancelado) o primeiro, o produto fica livre
        contar(primeiro, refri, "10");
        inventarios.aplicar(admin, primeiro);
        assertThat(inventarios.incluirProduto(admin, segundo, refri).incluidos()).isEqualTo(1);
    }

    @Test
    void contagemCapturaSistemaCustoQuemEQuandoERecontagemSobrescreve() throws Exception {
        long cafe = produto("Café 500 g", "UN", "21.50", "10", null, null);
        Sessao conferente = operador("conferente", Permissao.INVENTARIO);
        long inv = criarCom(cafe);
        OffsetDateTime primeiraHora = OffsetDateTime.now(t.relogio);

        inventarios.registrarContagem(admin, inv, item(inv, cafe).itemId(), v("8"));
        ResultadoInventario antes = inventarios.resultado(admin, inv);
        assertThat(divergencia(antes, cafe).estoqueSistema()).isEqualByComparingTo("10");
        assertThat(divergencia(antes, cafe).custoUnitario()).isEqualByComparingTo("21.50");
        assertThat(divergencia(antes, cafe).contadoPorNome()).isEqualTo("Ana Admin");
        assertThat(divergencia(antes, cafe).contadoEm().toInstant()).isEqualTo(primeiraHora.toInstant());

        // muda o saldo e o custo; uma hora depois, outra pessoa reconta
        t.estoque.ajustar(admin, cafe, TipoAjuste.ENTRADA, v("2"), "Devolução de cliente", null);
        t.produtos.alterar(admin, cafe, new DadosProduto("Café 500 g", null, "UN", v("9.90"), v("23.0000"), null,
                null, List.of(), null));
        t.relogio.avancar(Duration.ofHours(1));
        ItemContagem recontado = inventarios.registrarContagem(conferente, inv, item(inv, cafe).itemId(), v("11"));

        assertThat(recontado.quantidadeContada()).isEqualByComparingTo("11");
        assertThat(recontado.contadoPorNome()).isEqualTo("Operador conferente");
        ItemDivergencia depois = divergencia(inventarios.resultado(admin, inv), cafe);
        assertThat(depois.estoqueSistema()).isEqualByComparingTo("12");
        assertThat(depois.diferenca()).isEqualByComparingTo("-1");
        assertThat(depois.custoUnitario()).isEqualByComparingTo("23.00");
        assertThat(depois.contadoEm().toInstant()).isEqualTo(primeiraHora.plusHours(1).toInstant());
        assertThat(t.contar("SELECT count(*) FROM inventario_item WHERE inventario_id = " + inv)).isEqualTo(1);
    }

    @Test
    void custoDoMovimentoEOCustoCapturadoNaContagem() throws Exception {
        long cafe = produto("Café 500 g", "UN", "21.50", "10", null, null);
        long inv = criarCom(cafe);
        contar(inv, cafe, "13");
        t.produtos.alterar(admin, cafe, new DadosProduto("Café 500 g", null, "UN", v("9.90"), v("30.0000"), null,
                null, List.of(), null));

        ResultadoInventario r = inventarios.aplicar(admin, inv);

        assertThat(umValor("SELECT custo_unitario FROM movimento_estoque WHERE tipo = 'INVENTARIO'"))
                .isEqualByComparingTo("21.50");
        assertThat(r.valorSobra()).isEqualByComparingTo("64.50");
        assertThat(saldo(cafe)).isEqualByComparingTo("13");
    }

    @Test
    void contagemCegaLocalizaPeloCodigoDeBarrasEPeloCodigoInterno() {
        long cafe = produto("Café 500 g", "UN", "21.50", "10", "7891234567895", null);
        long arroz = produto("Arroz 5 kg", "UN", "20.00", "6", null, null);
        long fora = produto("Feijão 1 kg", "UN", "8.00", "3", "7891000315507", null);
        long inv = criarCom(cafe, arroz);

        assertThat(inventarios.localizar(admin, inv, "7891234567895").produtoId()).isEqualTo(cafe);
        assertThat(inventarios.localizar(admin, inv, " " + arroz + " ").produtoId()).isEqualTo(arroz);
        assertThatThrownBy(() -> inventarios.localizar(admin, inv, "7891000315507"))
                .isInstanceOf(NegocioException.class)
                .hasMessage("O produto Feijão 1 kg não está neste inventário.");
        assertThatThrownBy(() -> inventarios.localizar(admin, inv, String.valueOf(fora)))
                .hasMessage("O produto Feijão 1 kg não está neste inventário.");
        assertThatThrownBy(() -> inventarios.localizar(admin, inv, "7891234567890"))
                .hasMessage("Produto não encontrado.");
        assertThatThrownBy(() -> inventarios.localizar(admin, inv, "café"))
                .hasMessage("Leia o código de barras ou digite o código interno do produto.");

        // a aba Contagem não tem o estoque do sistema: só código, código de barras, nome, unidade e a contagem
        assertThat(Arrays.stream(ItemContagem.class.getRecordComponents()).map(c -> c.getName()))
                .doesNotContain("estoqueSistema", "saldo", "diferenca")
                .containsExactly("itemId", "produtoId", "codigoBarras", "nome", "unidade", "unidadeFracionada",
                        "quantidadeContada", "contadoEm", "contadoPorNome");
        assertThat(inventarios.itensContagem(admin, inv)).extracting(ItemContagem::nome)
                .containsExactly("Arroz 5 kg", "Café 500 g");
    }

    @Test
    void quantidadeContadaValidada() throws Exception {
        long cafe = produto("Café 500 g", "UN", "21.50", "10", null, null);
        long queijo = produto("Queijo muçarela", "KG", "28.0000", "2", null, null);
        long inv = criarCom(cafe, queijo);
        long itemCafe = item(inv, cafe).itemId();

        assertThatThrownBy(() -> inventarios.registrarContagem(admin, inv, itemCafe, null))
                .isInstanceOf(NegocioException.class).hasMessage("Informe a quantidade contada.");
        assertThatThrownBy(() -> inventarios.registrarContagem(admin, inv, itemCafe, v("-1")))
                .hasMessage("A quantidade contada não pode ser negativa.");
        assertThatThrownBy(() -> inventarios.registrarContagem(admin, inv, itemCafe, v("1.5")))
                .hasMessage("A quantidade contada deve ser um número inteiro: a unidade do produto não aceita "
                        + "quantidade fracionada (1,5).");
        assertThatThrownBy(() -> contar(inv, queijo, "0.3456"))
                .hasMessage("A quantidade contada pode ter no máximo 3 casas decimais.");
        assertThatThrownBy(() -> inventarios.registrarContagem(admin, inv, 999_999L, v("1")))
                .hasMessage("Item não encontrado neste inventário.");
        assertThat(item(inv, cafe).contado()).isFalse();

        assertThat(contar(inv, queijo, "1.655").quantidadeContada()).isEqualByComparingTo("1.655");
        assertThat(contar(inv, cafe, "0").quantidadeContada()).isEqualByComparingTo("0");
        inventarios.aplicar(admin, inv);
        assertThat(saldo(queijo)).isEqualByComparingTo("1.655");
        assertThat(saldo(cafe)).isEqualByComparingTo("0");
    }

    @Test
    void aplicarExigeItemContadoEItemSemDiferencaNaoGeraMovimento() throws Exception {
        long cafe = produto("Café 500 g", "UN", "21.50", "10", null, null);
        long vazio = inventarios.criar(admin, "Sem itens");
        assertThatThrownBy(() -> inventarios.aplicar(admin, vazio)).isInstanceOf(NegocioException.class)
                .hasMessage("Nenhum item foi contado. Conte ao menos um item antes de aplicar, ou cancele o "
                        + "inventário.");
        long inv = criarCom(cafe);
        assertThatThrownBy(() -> inventarios.aplicar(admin, inv)).hasMessageStartingWith("Nenhum item foi contado.");

        contar(inv, cafe, "10");
        ResultadoInventario r = inventarios.aplicar(admin, inv);

        assertThat(r.itensComDiferenca()).isZero();
        assertThat(t.contar("SELECT count(*) FROM movimento_estoque WHERE tipo = 'INVENTARIO'")).isZero();
        assertThat(inventarios.buscar(admin, inv).status()).isEqualTo(StatusInventario.APLICADO);
        assertThat(saldo(cafe)).isEqualByComparingTo("10");
    }

    @Test
    void cancelarNaoMexeNoEstoqueELiberaOsProdutos() throws Exception {
        long cafe = produto("Café 500 g", "UN", "21.50", "10", null, null);
        long inv = criarCom(cafe);
        contar(inv, cafe, "3");

        inventarios.cancelar(admin, inv);

        assertThat(inventarios.buscar(admin, inv).status()).isEqualTo(StatusInventario.CANCELADO);
        assertThat(saldo(cafe)).isEqualByComparingTo("10");
        assertThat(t.contar("SELECT count(*) FROM movimento_estoque WHERE tipo = 'INVENTARIO'")).isZero();
        assertThat(t.auditoria(AcaoAuditoria.INVENTARIO_CANCELADO)).containsExactly("Inventário nº " + inv
                + " (Conferência da semana) cancelado: 1 item, 1 contados. O estoque não foi alterado.");
        assertThatThrownBy(() -> contar(inv, cafe, "4"))
                .hasMessage("O inventário nº " + inv + " foi cancelado e não pode mais ser alterado.");
        assertThatThrownBy(() -> inventarios.aplicar(admin, inv)).hasMessageContaining("foi cancelado");

        long outro = inventarios.criar(admin, "Recontagem");
        assertThat(inventarios.incluirProduto(admin, outro, cafe).incluidos()).isEqualTo(1);
        assertThat(inventarios.listar(admin, false)).extracting(InventarioResumo::id).containsExactly(outro);
        assertThat(inventarios.listar(admin, true)).extracting(InventarioResumo::id).containsExactly(outro, inv);
    }

    @Test
    void incluirPorCategoriaETodosOsAtivosIgnoraInativos() throws Exception {
        long bebidas = categoria("Bebidas");
        long mercearia = categoria("Mercearia");
        long refri = produto("Refrigerante cola 2 L", "UN", "3.20", "10", null, bebidas);
        long cafe = produto("Café 500 g", "UN", "21.50", "10", "7891234567895", mercearia);
        long arroz = produto("Arroz 5 kg", "UN", "20.00", "6", null, mercearia);
        long velho = produto("Biscoito antigo", "UN", "1.00", null, null, mercearia);
        t.produtos.alterarSituacao(admin, velho, false);
        long inv = inventarios.criar(admin, "Geral");

        assertThat(inventarios.incluirCategoria(admin, inv, mercearia).mensagem())
                .isEqualTo("2 produtos incluídos.");
        assertThat(inventarios.incluirTodosAtivos(admin, inv).mensagem())
                .isEqualTo("1 produto incluído. 2 já estavam neste inventário.");
        assertThat(inventarios.itensContagem(admin, inv)).extracting(ItemContagem::produtoId)
                .containsExactlyInAnyOrder(refri, cafe, arroz);
        assertThatThrownBy(() -> inventarios.incluirProduto(admin, inv, velho))
                .isInstanceOf(NegocioException.class)
                .hasMessageStartingWith("O produto Biscoito antigo está inativo e não entra no inventário.");
        assertThatThrownBy(() -> inventarios.incluirPorCodigo(admin, inv, "7891000315507"))
                .hasMessage("Produto não encontrado.");
        assertThat(inventarios.pesquisarProdutos(admin, "biscoito")).isEmpty();
        assertThat(inventarios.pesquisarProdutos(admin, "CAFE")).extracting(p -> p.id()).containsExactly(cafe);
        assertThat(inventarios.categorias(admin)).extracting(Categoria::nome).contains("Bebidas", "Mercearia");

        InventarioResumo resumo = inventarios.buscar(admin, inv);
        assertThat(resumo.totalItens()).isEqualTo(3);
        assertThat(resumo.itensContados()).isZero();
        assertThat(resumo.criadoPorNome()).isEqualTo("Ana Admin");
    }

    @Test
    void removerItemDoInventarioAberto() throws Exception {
        long cafe = produto("Café 500 g", "UN", "21.50", "10", null, null);
        long arroz = produto("Arroz 5 kg", "UN", "20.00", "6", null, null);
        long inv = criarCom(cafe, arroz);
        contar(inv, cafe, "7");

        inventarios.removerItem(admin, inv, item(inv, cafe).itemId());

        assertThat(inventarios.itensContagem(admin, inv)).extracting(ItemContagem::produtoId).containsExactly(arroz);
        assertThatThrownBy(() -> inventarios.removerItem(admin, inv, 999_999L))
                .hasMessage("Item não encontrado neste inventário.");
        long outro = inventarios.criar(admin, "Outro");
        assertThat(inventarios.incluirProduto(admin, outro, cafe).incluidos()).isEqualTo(1);
    }

    @Test
    void descricaoObrigatoriaComAte80Caracteres() {
        assertThatThrownBy(() -> inventarios.criar(admin, "   ")).isInstanceOf(NegocioException.class)
                .hasMessage("Informe a descrição do inventário.");
        assertThatThrownBy(() -> inventarios.criar(admin, "x".repeat(81)))
                .hasMessage("A descrição pode ter no máximo 80 caracteres.");
        long id = inventarios.criar(admin, "  Mercearia   —  outubro ");
        InventarioResumo resumo = inventarios.buscar(admin, id);
        assertThat(resumo.descricao()).isEqualTo("Mercearia — outubro");
        assertThat(resumo.status()).isEqualTo(StatusInventario.ABERTO);
        assertThat(resumo.criadoEm().toInstant()).isEqualTo(t.relogio.instant());
        assertThatThrownBy(() -> inventarios.buscar(admin, 999_999L)).hasMessage("Inventário não encontrado.");
    }

    @Test
    void semPermissaoInventarioOServiceRecusa() {
        long cafe = produto("Café 500 g", "UN", "21.50", "10", null, null);
        long inv = criarCom(cafe);
        Sessao estoquista = operador("estoquista", Permissao.ESTOQUE, Permissao.PRODUTOS);

        assertThatThrownBy(() -> inventarios.listar(estoquista, true))
                .isInstanceOf(PermissaoNegadaException.class).hasMessage(SEM_PERMISSAO);
        assertThatThrownBy(() -> inventarios.criar(estoquista, "Teste")).hasMessage(SEM_PERMISSAO);
        assertThatThrownBy(() -> inventarios.registrarContagem(estoquista, inv, item(inv, cafe).itemId(), v("1")))
                .hasMessage(SEM_PERMISSAO);
        assertThatThrownBy(() -> inventarios.aplicar(estoquista, inv)).hasMessage(SEM_PERMISSAO);
        assertThatThrownBy(() -> inventarios.cancelar(estoquista, inv)).hasMessage(SEM_PERMISSAO);
        assertThatThrownBy(() -> inventarios.resultado(estoquista, inv)).hasMessage(SEM_PERMISSAO);
    }

    @Test
    void resultadoR13ComTotaisDeSobraEFalta() {
        long cafe = produto("Café 500 g", "UN", "21.50", "10", null, null);
        long queijo = produto("Queijo muçarela", "KG", "28.0000", "2", null, null);
        long arroz = produto("Arroz 5 kg", "UN", "20.00", "6", null, null);
        long inv = criarCom(cafe, queijo, arroz);
        contar(inv, cafe, "7");      // −3 × 21,50 = −64,50
        contar(inv, queijo, "2.345"); // +0,345 × 28,00 = 9,66

        ResultadoInventario r = inventarios.resultado(admin, inv);

        assertThat(r.inventario().titulo()).isEqualTo("Inventário nº " + inv + " — Conferência da semana");
        assertThat(r.itens()).extracting(ItemDivergencia::nome)
                .containsExactly("Arroz 5 kg", "Café 500 g", "Queijo muçarela");
        assertThat(divergencia(r, queijo).valorDiferenca()).isEqualByComparingTo("9.66");
        assertThat(r.valorSobra()).isEqualByComparingTo("9.66");
        assertThat(r.valorFalta()).isEqualByComparingTo("64.50");
        assertThat(r.valorLiquido()).isEqualByComparingTo("-54.84");
        assertThat(r.itensNaoContados()).isEqualTo(1);
        assertThat(r.inventario().itensContados()).isEqualTo(2);
    }

    @Test
    void enumDoInventarioIgualAoCheckDoBanco() throws Exception {
        String definicao;
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_inventario_status'");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            definicao = rs.getString(1);
        }
        Set<String> valores = new HashSet<>();
        Matcher m = Pattern.compile("'(\\w+)'").matcher(definicao);
        while (m.find()) {
            valores.add(m.group(1));
        }
        assertThat(Arrays.stream(StatusInventario.values()).map(Enum::name).collect(Collectors.toSet()))
                .isEqualTo(valores);
    }

    /**
     * Aceite F10 (inventário grande, F10.8): com 5 mil itens, gravar uma contagem leva menos de 300 ms. A tela grava
     * a contagem e relê só a linha do inventário na lista; a aba Contagem troca só a linha do item, sem reler os 5 mil
     * (a releitura inteira, que era feita antes, também é medida, para comparação).
     */
    @Test
    void comCincoMilItensGravarUmaContagemLevaMenosDe300ms() throws Exception {
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
            st.executeUpdate("INSERT INTO produto (nome, unidade, preco_venda, custo, criado_por, atualizado_por) "
                    + "SELECT 'Produto ' || g, 'UN', 1.99, 1, " + admin.usuarioId() + ", " + admin.usuarioId()
                    + " FROM generate_series(1, 5000) g");
            st.executeUpdate("ANALYZE produto");
        }
        long inv = inventarios.criar(admin, "Inventário geral");
        assertThat(inventarios.incluirTodosAtivos(admin, inv).incluidos()).isEqualTo(5000);
        List<ItemContagem> itens = inventarios.itensContagem(admin, inv);
        assertThat(itens).hasSize(5000);
        inventarios.registrarContagem(admin, inv, itens.get(0).itemId(), v("1")); // aquece conexões e caches
        inventarios.buscar(admin, inv);

        long maior = 0;
        for (int i = 1; i <= 20; i++) {
            long inicio = System.nanoTime();
            ItemContagem gravado = inventarios.registrarContagem(admin, inv, itens.get(i * 200).itemId(), v("3"));
            InventarioResumo resumo = inventarios.buscar(admin, inv);
            maior = Math.max(maior, System.nanoTime() - inicio);
            assertThat(gravado.contado()).isTrue();
            assertThat(resumo.itensContados()).isEqualTo(i + 1);
        }
        long ms = Duration.ofNanos(maior).toMillis();
        long inicioReleitura = System.nanoTime();
        assertThat(inventarios.itensContagem(admin, inv)).hasSize(5000);
        long releitura = Duration.ofNanos(System.nanoTime() - inicioReleitura).toMillis();
        System.out.printf("Métrica F10.8: gravar uma contagem com 5 mil itens — pior de 20: %d ms "
                + "(reler a aba inteira: %d ms)%n", ms, releitura);
        assertThat(ms).isLessThan(300);
    }
}
