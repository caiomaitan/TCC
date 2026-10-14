package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Categoria;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.HistoricoPreco;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.Produto;
import br.com.varesync.model.ProdutoResumo;
import br.com.varesync.model.SituacaoProduto;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Cadastro de produtos (6.3) e os critérios de aceite da Fase 2. */
class ProdutoServiceIT extends IntegracaoBase {

    private static final String CAFE = "7891000315507";
    private static final String OUTRO_CODIGO = "7891234567895";

    private AcessoTeste t;
    private ProdutoService produtos;
    private Sessao admin;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        produtos = t.produtos;
        admin = t.supervisor();
    }

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    private static DadosProduto dados(String nome, String unidade, String preco, String custo, String estoqueInicial,
                                      CodigoBarras... codigos) {
        return new DadosProduto(nome, null, unidade, bd(preco), bd(custo), null, null, List.of(codigos),
                estoqueInicial == null ? null : bd(estoqueInicial));
    }

    private static CodigoBarras codigo(String codigo) {
        return new CodigoBarras(codigo, false);
    }

    private long categoria(String nome) {
        return t.categorias.listar(admin, true).stream().filter(c -> c.nome().equals(nome))
                .mapToLong(Categoria::id).findFirst().orElseThrow();
    }

    private Sessao operador(String login, Permissao... permissoes) {
        EnumSet<Permissao> conjunto = EnumSet.noneOf(Permissao.class);
        conjunto.addAll(List.of(permissoes));
        t.usuarios.criar(admin, login, "Operador " + login, false, conjunto, "inicial1", "inicial1");
        return t.acesso.entrar(login, "inicial1");
    }

    // ----- Critérios de aceite da Fase 2 -----

    @Test
    void estoqueInicial10GeraSaldo10EUmAjusteDeEntrada() throws Exception {
        long id = produtos.criar(admin, dados("Arroz tipo 1 5 kg", "UN", "27.90", "21.5000", "10", codigo(CAFE)));

        Produto arroz = produtos.buscar(admin, id);
        assertThat(arroz.estoqueAtual()).isEqualByComparingTo("10");
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement("""
                     SELECT tipo, quantidade, saldo_anterior, saldo_posterior, custo_unitario, motivo, usuario_id,
                            criado_em
                       FROM movimento_estoque
                      WHERE produto_id = ?
                     """)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("tipo")).isEqualTo("AJUSTE_ENTRADA");
                assertThat(rs.getBigDecimal("quantidade")).isEqualByComparingTo("10");
                assertThat(rs.getBigDecimal("saldo_anterior")).isEqualByComparingTo("0");
                assertThat(rs.getBigDecimal("saldo_posterior")).isEqualByComparingTo("10");
                assertThat(rs.getBigDecimal("custo_unitario")).isEqualByComparingTo("21.5");
                assertThat(rs.getString("motivo")).isEqualTo(ProdutoService.MOTIVO_ESTOQUE_INICIAL);
                assertThat(rs.getLong("usuario_id")).isEqualTo(admin.usuarioId());
                assertThat(rs.getObject("criado_em", OffsetDateTime.class).toInstant())
                        .isEqualTo(t.relogio.instant());
                assertThat(rs.next()).as("um único movimento").isFalse();
            }
        }
        assertThat(t.auditoria(AcaoAuditoria.PRODUTO_CRIADO)).containsExactly("Produto \"Arroz tipo 1 5 kg\" (código "
                + id + ") criado: preço R$ 27,90, custo R$ 21,5000, unidade UN, estoque inicial 10");
    }

    @Test
    void semEstoqueInicialNaoHaMovimento() throws Exception {
        long id = produtos.criar(admin, dados("Feijão carioca 1 kg", "UN", "8.49", "6.10", null));
        long zero = produtos.criar(admin, dados("Feijão preto 1 kg", "UN", "8.99", "6.30", "0"));

        assertThat(produtos.buscar(admin, id).estoqueAtual()).isEqualByComparingTo("0");
        assertThat(produtos.buscar(admin, zero).estoqueAtual()).isEqualByComparingTo("0");
        assertThat(t.contar("SELECT count(*) FROM movimento_estoque")).isZero();
    }

    @Test
    void codigoDeBarrasComDigitoErradoERecusado() throws Exception {
        assertThatThrownBy(() -> produtos.criar(admin,
                dados("Café torrado 500 g", "UN", "18.90", "12.00", null, codigo("7891000315508"))))
                .isInstanceOf(NegocioException.class)
                .hasMessage("Código de barras 7891000315508 inválido: o dígito verificador não confere.");
        assertThat(t.contar("SELECT count(*) FROM produto")).isZero();
    }

    @Test
    void codigoRepetidoInformaOProdutoDono() {
        long cafe = produtos.criar(admin, dados("Café 500 g", "UN", "18.90", "12.00", null, codigo(CAFE)));
        String esperado = "Não foi possível salvar: o código de barras " + CAFE + " já pertence ao produto "
                + "\"Café 500 g\" (código " + cafe + ").";

        assertThatThrownBy(() -> produtos.criar(admin,
                dados("Café extraforte 500 g", "UN", "19.90", "13.00", null, codigo(OUTRO_CODIGO), codigo(CAFE))))
                .isInstanceOf(NegocioException.class)
                .hasMessage(esperado);

        long outro = produtos.criar(admin, dados("Café extraforte 500 g", "UN", "19.90", "13.00", null));
        assertThatThrownBy(() -> produtos.alterar(admin, outro,
                dados("Café extraforte 500 g", "UN", "19.90", "13.00", null, codigo(CAFE))))
                .hasMessage(esperado);

        // o próprio produto pode regravar o seu código
        produtos.alterar(admin, cafe, dados("Café 500 g", "UN", "18.90", "12.00", null, codigo(CAFE)));
        assertThat(produtos.buscar(admin, cafe).codigosBarras()).containsExactly(new CodigoBarras(CAFE, true));
    }

    @Test
    void mudancaDePrecoApareceNoHistoricoComUsuario() throws Exception {
        long id = produtos.criar(admin, dados("Refrigerante cola PET 2 L", "UN", "5.49", "3.20", null));
        Sessao carlos = operador("carlos", Permissao.PRODUTOS);
        t.relogio.avancar(Duration.ofMinutes(10));

        produtos.alterar(carlos, id, dados("Refrigerante cola PET 2 L", "UN", "5.99", "3.20", null));

        List<HistoricoPreco> historico = produtos.historico(admin, id);
        assertThat(historico).hasSize(2);
        HistoricoPreco mudanca = historico.get(0);
        assertThat(mudanca.usuarioNome()).isEqualTo("Operador carlos");
        assertThat(mudanca.precoAnterior()).isEqualByComparingTo("5.49");
        assertThat(mudanca.precoNovo()).isEqualByComparingTo("5.99");
        assertThat(mudanca.custoAnterior()).isEqualByComparingTo("3.20");
        assertThat(mudanca.custoNovo()).isEqualByComparingTo("3.20");
        HistoricoPreco cadastro = historico.get(1);
        assertThat(cadastro.usuarioNome()).isEqualTo("Ana Admin");
        assertThat(cadastro.precoAnterior()).isNull();
        assertThat(cadastro.precoNovo()).isEqualByComparingTo("5.49");

        assertThat(t.auditoria(AcaoAuditoria.PRODUTO_ALTERADO)).containsExactly("Produto \"Refrigerante cola PET 2 L\""
                + " (código " + id + "): Preço alterado de R$ 5,49 para R$ 5,99");
        Produto produto = produtos.buscar(admin, id);
        assertThat(produto.atualizadoPorNome()).isEqualTo("Operador carlos");
    }

    /** V3: os horários de cadastro, alteração, saldo, histórico e auditoria são os do Clock, mesmo no passado. */
    @Test
    void alteracaoDePrecoComClockNoPassadoGravaEssaData() throws Exception {
        Instant cadastro = Instant.parse("2026-08-01T12:00:00Z");
        Instant reajuste = Instant.parse("2026-08-15T15:30:00Z");
        t.relogio.avancar(Duration.between(t.relogio.instant(), cadastro));
        long id = produtos.criar(admin, dados("Refrigerante cola PET 2 L", "UN", "5.49", "3.20", "6"));

        assertThat(instante("SELECT criado_em FROM produto WHERE id = ?", id)).isEqualTo(cadastro);
        assertThat(instante("SELECT atualizado_em FROM produto WHERE id = ?", id)).isEqualTo(cadastro);
        assertThat(instante("SELECT atualizado_em FROM produto_estoque WHERE produto_id = ?", id))
                .as("saldo atualizado pelo movimento do estoque inicial").isEqualTo(cadastro);
        assertThat(instante("SELECT criado_em FROM movimento_estoque WHERE produto_id = ?", id)).isEqualTo(cadastro);

        t.relogio.avancar(Duration.between(cadastro, reajuste));
        produtos.alterar(admin, id, dados("Refrigerante cola PET 2 L", "UN", "5.99", "3.20", null));

        assertThat(instante("SELECT atualizado_em FROM produto WHERE id = ?", id)).isEqualTo(reajuste);
        assertThat(produtos.buscar(admin, id).atualizadoEm().toInstant()).isEqualTo(reajuste);
        List<HistoricoPreco> historico = produtos.historico(admin, id);
        assertThat(historico).hasSize(2);
        assertThat(historico.get(0).precoNovo()).isEqualByComparingTo("5.99");
        assertThat(historico.get(0).criadoEm().toInstant()).isEqualTo(reajuste);
        assertThat(historico.get(1).criadoEm().toInstant()).isEqualTo(cadastro);
        assertThat(instante("SELECT max(criado_em) FROM auditoria WHERE entidade_id = ?", id)).isEqualTo(reajuste);
    }

    /** V3: UPDATE que não informa atualizado_em (ex.: feito no DBeaver) continua recebendo now(). */
    @Test
    void updateSemAtualizadoEmUsaAHoraDoBanco() throws Exception {
        long id = produtos.criar(admin, dados("Sabão em pó 1 kg", "UN", "12.90", "8.50", null));
        try (Connection con = dataSource.getConnection()) {
            con.setAutoCommit(false);
            try (PreparedStatement ps = con.prepareStatement("UPDATE produto SET preco_venda = 13.90 WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = con.prepareStatement("""
                    SELECT p.atualizado_em = now(), h.criado_em = now()
                      FROM produto p
                      JOIN produto_historico_preco h ON h.produto_id = p.id AND h.preco_novo = 13.90
                     WHERE p.id = ?
                    """)) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getBoolean(1)).as("produto.atualizado_em = now()").isTrue();
                    assertThat(rs.getBoolean(2)).as("histórico com now()").isTrue();
                }
            }
            con.rollback();
        }
    }

    private Instant instante(String sql, long id) throws Exception {
        try (Connection con = dataSource.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getObject(1, OffsetDateTime.class).toInstant();
            }
        }
    }

    @Test
    void alterarSemMudarPrecoNaoGravaHistoricoNemAuditoria() throws Exception {
        long id = produtos.criar(admin, dados("Sabão em pó 1 kg", "UN", "12.90", "8.50", null));
        produtos.alterar(admin, id, dados("Sabão em pó 1 kg", "UN", "12.90", "8.5000", null));

        assertThat(produtos.historico(admin, id)).hasSize(1);
        assertThat(t.auditoria(AcaoAuditoria.PRODUTO_ALTERADO)).isEmpty();
    }

    @Test
    void produtoMovimentadoNaoExcluiEOfereceInativar() throws Exception {
        long id = produtos.criar(admin, dados("Leite integral 1 L", "UN", "5.29", "3.90", "12"));

        assertThatThrownBy(() -> produtos.excluir(admin, id))
                .isInstanceOf(RegistroEmUsoException.class)
                .hasMessage("O produto \"Leite integral 1 L\" não pode ser excluído porque já tem movimentação de "
                        + "estoque. Você pode inativá-lo: ele sai do PDV e da busca, mas continua nos relatórios.");
        assertThat(produtos.buscar(admin, id).ativo()).isTrue();

        produtos.alterarSituacao(admin, id, false);

        assertThat(produtos.buscar(admin, id).ativo()).isFalse();
        assertThat(produtos.pesquisar(admin, "leite", null, SituacaoProduto.ATIVOS)).isEmpty();
        assertThat(produtos.pesquisar(admin, "leite", null, SituacaoProduto.INATIVOS)).extracting(ProdutoResumo::id)
                .containsExactly(id);
        assertThat(produtos.pesquisar(admin, "leite", null, SituacaoProduto.TODOS)).hasSize(1);
        assertThat(t.auditoria(AcaoAuditoria.PRODUTO_INATIVADO))
                .containsExactly("Produto \"Leite integral 1 L\" (código " + id + ") inativado");
        assertThat(t.auditoria(AcaoAuditoria.PRODUTO_EXCLUIDO)).isEmpty();

        produtos.alterarSituacao(admin, id, true);
        assertThat(produtos.buscar(admin, id).ativo()).isTrue();
        assertThat(t.auditoria(AcaoAuditoria.PRODUTO_ALTERADO))
                .containsExactly("Produto \"Leite integral 1 L\" (código " + id + ") reativado");
    }

    @Test
    void produtoSemVinculoEExcluidoComCodigosEHistorico() throws Exception {
        long id = produtos.criar(admin, dados("Produto cadastrado errado", "UN", "1.00", "0.50", null, codigo(CAFE)));

        produtos.excluir(admin, id);

        assertThat(t.contar("SELECT count(*) FROM produto")).isZero();
        assertThat(t.contar("SELECT count(*) FROM produto_codigo_barras")).isZero();
        assertThat(t.contar("SELECT count(*) FROM produto_historico_preco")).isZero();
        assertThat(t.contar("SELECT count(*) FROM produto_estoque")).isZero();
        assertThat(t.auditoria(AcaoAuditoria.PRODUTO_EXCLUIDO))
                .containsExactly("Produto \"Produto cadastrado errado\" (código " + id + ") excluído");
        // o código ficou livre para outro produto
        produtos.criar(admin, dados("Café 500 g", "UN", "18.90", "12.00", null, codigo(CAFE)));
    }

    @Test
    void buscarAcucarEncontraAcucar() {
        long acucar = produtos.criar(admin, dados("Açúcar refinado 1 kg", "UN", "4.99", "3.40", null, codigo(CAFE)));
        long cafe = produtos.criar(admin, dados("Café 500 g", "UN", "18.90", "12.00", null));
        produtos.criar(admin, dados("Arroz 5 kg", "UN", "27.90", "21.50", null));

        assertThat(produtos.pesquisar(admin, "acucar", null, SituacaoProduto.ATIVOS)).extracting(ProdutoResumo::nome)
                .containsExactly("Açúcar refinado 1 kg");
        assertThat(produtos.pesquisar(admin, "  AÇÚCAR  ", null, SituacaoProduto.ATIVOS)).hasSize(1);
        assertThat(produtos.pesquisar(admin, "ucar ref", null, SituacaoProduto.ATIVOS)).hasSize(1);
        assertThat(produtos.pesquisar(admin, "", null, SituacaoProduto.ATIVOS)).extracting(ProdutoResumo::nome)
                .containsExactly("Açúcar refinado 1 kg", "Arroz 5 kg", "Café 500 g");
        assertThat(produtos.pesquisar(admin, "%", null, SituacaoProduto.ATIVOS)).as("% não é curinga").isEmpty();

        // só dígitos: código interno ou código de barras exato
        assertThat(produtos.pesquisar(admin, String.valueOf(cafe), null, SituacaoProduto.ATIVOS))
                .extracting(ProdutoResumo::id).containsExactly(cafe);
        assertThat(produtos.pesquisar(admin, CAFE, null, SituacaoProduto.ATIVOS))
                .extracting(ProdutoResumo::id).containsExactly(acucar);
        assertThat(produtos.pesquisar(admin, CAFE.substring(0, 12), null, SituacaoProduto.ATIVOS)).isEmpty();
        assertThat(produtos.pesquisar(admin, "1".repeat(20), null, SituacaoProduto.ATIVOS)).isEmpty();
    }

    @Test
    void buscaFiltraPorCategoriaEMostraColunasDaLista() {
        long mercearia = categoria("Mercearia");
        long bebidas = categoria("Bebidas");
        produtos.criar(admin, new DadosProduto("Açúcar 1 kg", mercearia, "UN", bd("4.99"), bd("3.40"), null, null,
                List.of(), bd("7")));
        produtos.criar(admin, new DadosProduto("Água mineral 500 mL", bebidas, "UN", bd("2.00"), bd("0.90"), null,
                null, List.of(), null));

        List<ProdutoResumo> resultado = produtos.pesquisar(admin, "", mercearia, SituacaoProduto.ATIVOS);
        assertThat(resultado).hasSize(1);
        ProdutoResumo linha = resultado.get(0);
        assertThat(linha.nome()).isEqualTo("Açúcar 1 kg");
        assertThat(linha.categoriaNome()).isEqualTo("Mercearia");
        assertThat(linha.precoVenda()).isEqualByComparingTo("4.99");
        assertThat(linha.estoque()).isEqualByComparingTo("7");
        assertThat(linha.atualizadoPorNome()).isEqualTo("Ana Admin");
        assertThat(linha.atualizadoEm()).isNotNull();
    }

    @Test
    void operadorSemProdutosERecusadoNoService() {
        Sessao caixa = operador("caixa1", Permissao.PDV);
        long id = produtos.criar(admin, dados("Arroz 5 kg", "UN", "27.90", "21.50", null));
        String mensagem = "Você não tem permissão para usar a rotina Produtos. Procure o supervisor.";

        assertThatThrownBy(() -> produtos.pesquisar(caixa, "", null, SituacaoProduto.ATIVOS))
                .isInstanceOf(PermissaoNegadaException.class).hasMessage(mensagem);
        assertThatThrownBy(() -> produtos.criar(caixa, dados("Feijão 1 kg", "UN", "8.49", "6.10", null)))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> produtos.alterar(caixa, id, dados("Arroz 5 kg", "UN", "1.00", "21.50", null)))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> produtos.excluir(caixa, id)).isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> produtos.alterarSituacao(caixa, id, false)).isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> produtos.historico(caixa, id)).isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> t.categorias.criar(caixa, "Pet shop")).isInstanceOf(PermissaoNegadaException.class);
        assertThat(produtos.buscar(admin, id).precoVenda()).isEqualByComparingTo("27.90");
    }

    @Test
    void unidadeInteiraNaoAceitaFracaoEKgAceita() throws Exception {
        assertThatThrownBy(() -> produtos.criar(admin, dados("Arroz 5 kg", "UN", "27.90", "21.50", "1.5")))
                .hasMessage("O estoque inicial deve ser um número inteiro: a unidade do produto não aceita "
                        + "quantidade fracionada (1,5).");
        assertThat(t.contar("SELECT count(*) FROM produto")).as("rollback do cadastro").isZero();

        long queijo = produtos.criar(admin, dados("Queijo muçarela", "KG", "39.90", "28.00", "0.345"));
        assertThat(produtos.buscar(admin, queijo).estoqueAtual()).isEqualByComparingTo("0.345");
        assertThat(produtos.buscar(admin, queijo).unidadeFracionada()).isTrue();

        // com saldo fracionado não pode virar unidade inteira
        assertThatThrownBy(() -> produtos.alterar(admin, queijo, dados("Queijo muçarela", "UN", "39.90", "28.00", null)))
                .hasMessageContaining("saldo fracionado");
    }

    @Test
    void validacoesDoCadastro() {
        assertThatThrownBy(() -> produtos.criar(admin, dados("A", "UN", "1.00", "0.50", null)))
                .hasMessage("O nome do produto deve ter ao menos 2 caracteres.");
        assertThatThrownBy(() -> produtos.criar(admin, dados("Arroz", "UN", "-1.00", "0.50", null)))
                .hasMessage("O preço de venda não pode ser negativo.");
        assertThatThrownBy(() -> produtos.criar(admin, dados("Arroz", "XX", "1.00", "0.50", null)))
                .hasMessage("Unidade \"XX\" não cadastrada.");
        assertThatThrownBy(() -> produtos.criar(admin, new DadosProduto("Arroz", null, "UN", null, bd("1"), null, null,
                List.of(), null))).hasMessage("Informe o preço de venda.");
        assertThatThrownBy(() -> produtos.criar(admin, new DadosProduto("Arroz", null, "UN", bd("1"), bd("0.5"),
                bd("2.5"), null, List.of(), null))).hasMessageStartingWith("O estoque mínimo deve ser um número inteiro");

        long categoria = t.categorias.criar(admin, "Sazonais");
        t.categorias.alterarSituacao(admin, categoria, false);
        assertThatThrownBy(() -> produtos.criar(admin, new DadosProduto("Panetone", categoria, "UN", bd("29.90"),
                bd("18"), null, null, List.of(), null))).hasMessage("A categoria \"Sazonais\" está inativa.");
    }

    @Test
    void nomeNormalizadoECodigoPrincipal() {
        long id = produtos.criar(admin, new DadosProduto("  Biscoito   recheado  ", null, "PCT", bd("3.49"),
                bd("2.10"), bd("12"), null, List.of(codigo(CAFE), codigo("12345670")), null));
        Produto biscoito = produtos.buscar(admin, id);
        assertThat(biscoito.nome()).isEqualTo("Biscoito recheado");
        assertThat(biscoito.unidade()).isEqualTo("PCT");
        assertThat(biscoito.estoqueMinimo()).isEqualByComparingTo("12");
        assertThat(biscoito.codigosBarras()).containsExactly(new CodigoBarras(CAFE, true),
                new CodigoBarras("12345670", false));

        produtos.alterar(admin, id, new DadosProduto("Biscoito recheado", null, "PCT", bd("3.49"), bd("2.10"),
                bd("12"), null, List.of(codigo(CAFE), new CodigoBarras("12345670", true)), null));
        assertThat(produtos.buscar(admin, id).codigoPrincipal()).contains("12345670");
    }

    @Test
    void avisosDePrecoENomeRepetidoNaoImpedemSalvar() {
        long acucar = produtos.criar(admin, dados("Açúcar 1 kg", "UN", "4.99", "3.40", null));

        assertThat(produtos.avisos(admin, null, dados("ACUCAR  1 KG", "UN", "3.00", "3.40", null))).containsExactly(
                ProdutoService.AVISO_ABAIXO_DO_CUSTO,
                "Já existe produto com este nome: \"Açúcar 1 kg\" (código " + acucar + ").");
        assertThat(produtos.avisos(admin, acucar, dados("Açúcar 1 kg", "UN", "4.99", "3.40", null))).isEmpty();
        assertThat(produtos.avisos(admin, null, dados("Brinde", "UN", "0", "1.00", null)))
                .containsExactly(ProdutoService.AVISO_PRECO_ZERO);

        // salvar mesmo assim é permitido
        long repetido = produtos.criar(admin, dados("Açúcar 1 kg", "UN", "3.00", "3.40", null));
        assertThat(produtos.buscar(admin, repetido).precoVenda()).isEqualByComparingTo("3.00");
    }

    @Test
    void pluSoComBalancaAtiva() throws Exception {
        DadosProduto comPlu = new DadosProduto("Queijo prato", null, "KG", bd("44.90"), bd("30"), null, 123,
                List.of(), null);
        assertThatThrownBy(() -> produtos.criar(admin, comPlu)).hasMessageContaining("balança ativa");
        assertThat(produtos.balancaAtiva(admin)).isFalse();

        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
            st.executeUpdate("UPDATE configuracao SET valor = 'true' WHERE chave = 'balanca.ativo'");
        }
        long id = produtos.criar(admin, comPlu);
        assertThat(produtos.buscar(admin, id).plu()).isEqualTo(123);
        assertThatThrownBy(() -> produtos.criar(admin, new DadosProduto("Queijo minas", null, "KG", bd("39.90"),
                bd("25"), null, 123, List.of(), null)))
                .hasMessage("O PLU 123 já pertence ao produto \"Queijo prato\" (código " + id + ").");
        assertThatThrownBy(() -> produtos.criar(admin, new DadosProduto("Pão francês", null, "UN", bd("0.80"),
                bd("0.30"), null, 124, List.of(), null))).hasMessageContaining("unidade fracionada");
    }

    @Test
    void custoDoProdutoTemQuatroCasas() {
        long id = produtos.criar(admin, dados("Ovos brancos", "DZ", "9.90", "0.8333", null));
        Produto ovos = produtos.buscar(admin, id);
        assertThat(ovos.custo()).isEqualByComparingTo("0.8333");
        assertThat(ovos.custo().scale()).isEqualTo(4);
    }
}
