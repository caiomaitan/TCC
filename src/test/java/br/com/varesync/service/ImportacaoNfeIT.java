package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.DadosFornecedor;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.EntradaResumo;
import br.com.varesync.model.Fornecedor;
import br.com.varesync.model.ItemEntrada;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.StatusEntrada;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Importação do XML da NF-e na entrada de mercadoria (6.11): fornecedor pelo CNPJ (criado se não existir), vínculo
 * pelo código de barras, unidade tributável, item sem vínculo, produto repetido somado, nota repetida e a entrada
 * ABERTA que depois é confirmada normalmente.
 */
class ImportacaoNfeIT extends IntegracaoBase {

    private static final byte[] XML = LeitorNfeTest.recurso("nfe-distribuidora.xml");

    private AcessoTeste t;
    private Sessao admin;
    private long cafe;
    private long refrigerante;
    private long queijo;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        admin = t.supervisor();
        cafe = produto("Café torrado 500 g", "UN", "7891000315507");
        refrigerante = produto("Refrigerante lata 350 ml", "UN", "7896004000015");
        queijo = produto("Queijo muçarela", "KG", null);
    }

    private long produto(String nome, String unidade, String codigo) {
        return t.produtos.criar(admin, new DadosProduto(nome, null, unidade, new BigDecimal("20.00"),
                new BigDecimal("10.00"), null, null, codigo == null ? List.of() : List.of(new CodigoBarras(codigo,
                true)), null));
    }

    @Test
    void previaVinculaPeloCodigoDeBarrasEMostraOFornecedorACadastrar() throws Exception {
        ImportacaoNfeService.Previa previa = t.nfe.ler(admin, XML);
        assertThat(previa.fornecedor()).isNull();
        assertThat(previa.entradaExistente()).isNull();
        assertThat(previa.itens()).extracting(i -> i.produto() == null ? null : i.produto().id())
                .containsExactly(cafe, null, refrigerante, cafe);
        assertThat(previa.semVinculo()).isEqualTo(1);
        assertThat(t.contar("SELECT count(*) FROM fornecedor")).isZero(); // a prévia não grava
    }

    @Test
    void geraEntradaAbertaComFornecedorNovoItensSomadosEUnidadeTributavel() throws Exception {
        assertThatThrownBy(() -> t.nfe.gerarEntrada(admin, XML, Map.of()))
                .isInstanceOf(NegocioException.class)
                .hasMessageContaining("O item 2 da nota (\"QUEIJO MUSSARELA PECA\") não tem produto");
        assertThat(t.contar("SELECT count(*) FROM fornecedor")).isZero(); // nada gravado

        long entradaId = t.nfe.gerarEntrada(admin, XML, Map.of(2, queijo));
        EntradaResumo entrada = t.compras.buscar(admin, entradaId);
        assertThat(entrada.status()).isEqualTo(StatusEntrada.ABERTA);
        assertThat(entrada.numeroDocumento()).isEqualTo("1234");
        assertThat(entrada.dataEmissao()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(entrada.valorTotalNota()).isEqualByComparingTo("367.00");
        // F15.3: as duplicatas do XML vêm como parcelas (a sem vencimento fica de fora).
        assertThat(t.compras.parcelas(admin, entradaId)).containsExactly(
                new br.com.varesync.model.ParcelaEntrada(1, LocalDate.of(2026, 11, 4), new java.math.BigDecimal("200.00")),
                new br.com.varesync.model.ParcelaEntrada(2, LocalDate.of(2026, 12, 4), new java.math.BigDecimal("167.00")));

        Fornecedor fornecedor = t.fornecedores.buscar(admin, entrada.fornecedorId());
        assertThat(fornecedor.razaoSocial()).isEqualTo("Distribuidora Ribeirão Alimentos Ltda");
        assertThat(fornecedor.nomeFantasia()).isEqualTo("Distribuidora Ribeirão");
        assertThat(fornecedor.cpfCnpj()).isEqualTo("11222333000181");
        assertThat(fornecedor.cidade()).isEqualTo("Ribeirão Preto");
        assertThat(fornecedor.uf()).isEqualTo("SP");
        assertThat(fornecedor.telefone()).isEqualTo("1633334444");
        assertThat(fornecedor.observacao()).contains("NF-e nº 1234");

        List<ItemEntrada> itens = t.compras.itens(admin, entradaId);
        assertThat(itens).hasSize(3);
        ItemEntrada linhaCafe = itens.stream().filter(i -> i.produtoId() == cafe).findFirst().orElseThrow();
        assertThat(linhaCafe.quantidade()).isEqualByComparingTo("12"); // itens 1 e 4 somados
        assertThat(linhaCafe.valorTotal()).isEqualByComparingTo("149.00");
        assertThat(linhaCafe.custoUnitario()).isEqualByComparingTo("12.4167"); // 149 ÷ 12, 4 casas
        ItemEntrada linhaRefri = itens.stream().filter(i -> i.produtoId() == refrigerante).findFirst().orElseThrow();
        assertThat(linhaRefri.quantidade()).isEqualByComparingTo("48"); // qTrib: 2 CX = 48 latas
        assertThat(linhaRefri.custoUnitario()).isEqualByComparingTo("2.5000");
        ItemEntrada linhaQueijo = itens.stream().filter(i -> i.produtoId() == queijo).findFirst().orElseThrow();
        assertThat(linhaQueijo.quantidade()).isEqualByComparingTo("3.5");
        assertThat(linhaQueijo.custoUnitario()).isEqualByComparingTo("28.0000");

        // Confirmada pelo caminho normal da Fase 7: estoque entra.
        t.compras.confirmar(admin, entradaId);
        assertThat(t.estoque.saldo(admin, refrigerante).saldo()).isEqualByComparingTo("48");

        // Mesma nota de novo: a prévia avisa e a geração é recusada.
        ImportacaoNfeService.Previa repetida = t.nfe.ler(admin, XML);
        assertThat(repetida.fornecedor().id()).isEqualTo(fornecedor.id());
        assertThat(repetida.entradaExistente()).isEqualTo(entradaId);
        assertThatThrownBy(() -> t.nfe.gerarEntrada(admin, XML, Map.of(2, queijo)))
                .hasMessageContaining("Esta nota já foi lançada (entrada nº " + entradaId + ")");
        assertThat(t.contar("SELECT count(*) FROM fornecedor")).isEqualTo(1);
    }

    @Test
    void fornecedorJaCadastradoEReusadoEItemComQuantidadeInvalidaParaAUnidadeERecusado() throws Exception {
        long existente = t.fornecedores.criar(admin, new DadosFornecedor("Distribuidora Ribeirão (cadastro antigo)",
                null, "11.222.333/0001-81", null, null, null, null, null, null));
        long unidadeInteira = produto("Queijo em peça (UN)", "UN", null);
        assertThatThrownBy(() -> t.nfe.gerarEntrada(admin, XML, Map.of(2, unidadeInteira)))
                .hasMessageStartingWith("Item 2 da nota: ");
        long entradaId = t.nfe.gerarEntrada(admin, XML, Map.of(2, queijo));
        assertThat(t.compras.buscar(admin, entradaId).fornecedorId()).isEqualTo(existente);
        assertThat(t.contar("SELECT count(*) FROM fornecedor")).isEqualTo(1);
    }

    @Test
    void notaAntigaComCpfEItemSemCodigoEscolhido() throws Exception {
        byte[] antiga = LeitorNfeTest.recurso("nfe-antiga-sem-proc.xml");
        long ovos = t.produtos.criar(admin, new DadosProduto("Ovos brancos dúzia", null, "DZ", new BigDecimal("12"),
                new BigDecimal("9"), null, null, List.of(), null));
        long entradaId = t.nfe.gerarEntrada(admin, antiga, Map.of(1, ovos));
        Fornecedor f = t.fornecedores.buscar(admin, t.compras.buscar(admin, entradaId).fornecedorId());
        assertThat(f.cpfCnpj()).isEqualTo("52998224725");
        assertThat(f.uf()).isEqualTo("SP");
        assertThat(f.telefone()).isNull(); // "3333" não é telefone válido
        assertThat(t.compras.itens(admin, entradaId)).singleElement()
                .satisfies(i -> assertThat(i.quantidade()).isEqualByComparingTo("30"));
    }

    @Test
    void precisaDaPermissaoDeCompras() {
        t.usuarios.criar(admin, "estoquista", "Estoquista", false, EnumSet.of(Permissao.ESTOQUE), "inicial1",
                "inicial1");
        Sessao estoquista = t.acesso.entrar("estoquista", "inicial1");
        assertThatThrownBy(() -> t.nfe.ler(estoquista, XML)).isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> t.nfe.gerarEntrada(estoquista, XML, Map.of()))
                .isInstanceOf(PermissaoNegadaException.class);
    }
}
