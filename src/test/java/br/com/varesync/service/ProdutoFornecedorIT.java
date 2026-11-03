package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.DadosFornecedor;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.DadosVinculo;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.ProdutoFornecedor;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Fornecedor do produto (F16.1): o primeiro vínculo nasce principal, só um principal por produto, um vínculo por
 * fornecedor, código único por fornecedor, fator e prazo validados, fornecedor inativo recusado e a permissão.
 */
class ProdutoFornecedorIT extends IntegracaoBase {

    private AcessoTeste t;
    private Sessao admin;
    private long leite;
    private long queijo;
    private long vale;
    private long ribeirao;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        admin = t.supervisor();
        leite = t.produtos.criar(admin, new DadosProduto("Leite integral 1 L", null, "UN", new BigDecimal("5.49"),
                new BigDecimal("3.20"), null, null, List.of(), null));
        queijo = t.produtos.criar(admin, new DadosProduto("Queijo muçarela", null, "KG", new BigDecimal("49.90"),
                new BigDecimal("28.00"), null, null, List.of(), null));
        vale = t.fornecedores.criar(admin, new DadosFornecedor("Laticínios Vale do Pardo Ltda", null,
                "AB12CD34EFGH83", null, null, null, null, null, null));
        ribeirao = t.fornecedores.criar(admin, new DadosFornecedor("Distribuidora Ribeirão Alimentos Ltda", null,
                "11222333000181", null, null, null, null, null, null));
    }

    private static DadosVinculo vinculo(long fornecedor, String codigo, String fator, boolean principal) {
        return new DadosVinculo(fornecedor, codigo, "cx", new BigDecimal(fator), 3, principal);
    }

    @Test
    void primeiroVinculoNascePrincipalETrocarOPrincipalTiraODoOutro() throws Exception {
        long v1 = t.vinculos.salvar(admin, leite, null, vinculo(vale, " LEITE-CX12 ", "12", false));
        List<ProdutoFornecedor> lista = t.vinculos.listar(admin, leite);
        assertThat(lista).singleElement().satisfies(v -> {
            assertThat(v.principal()).isTrue();
            assertThat(v.codigoNoFornecedor()).isEqualTo("LEITE-CX12");
            assertThat(v.unidadeCompra()).isEqualTo("CX");
            assertThat(v.fator()).isEqualByComparingTo("12");
            assertThat(v.prazoEntregaDias()).isEqualTo(3);
            assertThat(v.ultimoCusto()).isNull();
        });

        long v2 = t.vinculos.salvar(admin, leite, null, vinculo(ribeirao, null, "6", true));
        assertThat(t.vinculos.listar(admin, leite)).extracting(ProdutoFornecedor::id, ProdutoFornecedor::principal)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(v2, true),
                        org.assertj.core.groups.Tuple.tuple(v1, false));

        // Volta o principal para o primeiro, pela alteração.
        t.vinculos.salvar(admin, leite, v1, vinculo(vale, "LEITE-CX12", "12", true));
        assertThat(t.vinculos.listar(admin, leite).get(0).id()).isEqualTo(v1);
        assertThat(t.contar("SELECT count(*) FROM produto_fornecedor WHERE principal")).isEqualTo(1);

        // Remover o principal deixa o produto sem principal.
        t.vinculos.remover(admin, leite, v1);
        assertThat(t.vinculos.listar(admin, leite)).singleElement()
                .satisfies(v -> assertThat(v.principal()).isFalse());
    }

    @Test
    void fornecedorRepetidoECodigoDeOutroProdutoSaoRecusados() throws Exception {
        t.vinculos.salvar(admin, leite, null, vinculo(vale, "LEITE-CX12", "12", true));
        assertThatThrownBy(() -> t.vinculos.salvar(admin, leite, null, vinculo(vale, null, "1", false)))
                .hasMessageContaining("já é fornecedor deste produto");
        assertThatThrownBy(() -> t.vinculos.salvar(admin, queijo, null, vinculo(vale, "LEITE-CX12", "1", false)))
                .hasMessageContaining("O código LEITE-CX12 de Laticínios Vale do Pardo Ltda já está no produto")
                .hasMessageContaining("Leite integral 1 L");
        // O mesmo código em outro fornecedor pode.
        t.vinculos.salvar(admin, queijo, null, vinculo(ribeirao, "LEITE-CX12", "1", true));
        assertThat(t.contar("SELECT count(*) FROM produto_fornecedor")).isEqualTo(2);
    }

    @Test
    void fatorPrazoEFornecedorInativo() {
        assertThatThrownBy(() -> t.vinculos.salvar(admin, leite, null, vinculo(vale, null, "0.5", true)))
                .hasMessageContaining("O fator deve ser 1 ou mais");
        assertThatThrownBy(() -> t.vinculos.salvar(admin, leite, null, vinculo(vale, null, "2.5", true)))
                .hasMessageContaining("deve ser um número inteiro");
        // Produto de unidade fracionada aceita fator com casas (caixa de 2,5 kg).
        t.vinculos.salvar(admin, queijo, null, vinculo(vale, null, "2.5", true));
        assertThatThrownBy(() -> t.vinculos.salvar(admin, leite, null,
                new DadosVinculo(vale, null, null, BigDecimal.ONE, 366, true)))
                .hasMessageContaining("O prazo de entrega deve estar entre 0 e 365 dias");

        t.fornecedores.alterarSituacao(admin, ribeirao, false);
        assertThatThrownBy(() -> t.vinculos.salvar(admin, leite, null, vinculo(ribeirao, null, "1", true)))
                .hasMessageContaining("está inativo");
        assertThat(t.vinculos.fornecedores(admin)).extracting(f -> f.id()).containsExactly(vale);
    }

    @Test
    void excluirOProdutoOuOFornecedorLevaOsVinculos() throws Exception {
        t.vinculos.salvar(admin, leite, null, vinculo(vale, "LEITE-CX12", "12", true));
        t.vinculos.salvar(admin, queijo, null, vinculo(ribeirao, null, "1", true));
        t.produtos.excluir(admin, leite);
        assertThat(t.contar("SELECT count(*) FROM produto_fornecedor")).isEqualTo(1);
        t.fornecedores.excluir(admin, ribeirao);
        assertThat(t.contar("SELECT count(*) FROM produto_fornecedor")).isZero();
    }

    @Test
    void precisaDaPermissaoDeProdutos() {
        t.usuarios.criar(admin, "comprador", "Comprador", false, EnumSet.of(Permissao.COMPRAS), "inicial1",
                "inicial1");
        Sessao comprador = t.acesso.entrar("comprador", "inicial1");
        assertThatThrownBy(() -> t.vinculos.listar(comprador, leite)).isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> t.vinculos.salvar(comprador, leite, null, vinculo(vale, null, "1", true)))
                .isInstanceOf(PermissaoNegadaException.class);
    }
}
