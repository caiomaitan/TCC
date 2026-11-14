package br.com.varesync.report;

import java.util.List;

/**
 * Catálogo dos relatórios da tela Relatórios (7.3), na ordem dos códigos. R11 (fechamento de caixa) e R13
 * (resultado de inventário) ficam nas telas Caixa e Inventário, que escolhem a sessão e o inventário.
 */
public final class Relatorios {

    private static final List<Relatorio<?>> TODOS = List.of(new RelVendasPeriodo(), new RelMaisVendidos(),
            new RelEstoqueAtual(), new RelMovimentacao(), new RelRentabilidadeProduto(),
            new RelRentabilidadeCategoria(), new RelCurvaAbc(), new RelFormasPagamento(), new RelVendasOperador(),
            new RelVendasHoraDia(), new RelSemGiro(), new RelPerdasAjustes(), new RelAlteracoesPreco(),
            new RelCancelamentos(), new RelComprasFornecedor(), new RelClientes(), new RelSaldosCredito(),
            new RelDevolucoes(), new RelTitulosReceber(), new RelRecebimentos(), new RelContasPagar(),
            new RelFluxoCaixa(), new RelDre(), new RelSugestaoCompra(), new RelPedidosPendentes(),
            new RelCoberturaRuptura(), new RelValidade(), new RelInconsistenciasFiscais(), new RelImpostosVendas());

    private Relatorios() {
    }

    public static List<Relatorio<?>> todos() {
        return TODOS;
    }
}
