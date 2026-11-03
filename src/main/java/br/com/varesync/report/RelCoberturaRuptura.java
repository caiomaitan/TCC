package br.com.varesync.report;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * R28 — Cobertura e ruptura (F16.2): todos os produtos ativos com o saldo, a média de venda por dia e a cobertura em
 * dias (saldo ÷ média, pela conta de {@link SugestaoCompra}); em ruptura, os de saldo ≤ 0 que venderam nos últimos 30
 * dias. Primeiro as rupturas, depois a menor cobertura; sem venda na média, por último.
 */
public final class RelCoberturaRuptura implements Relatorio<SugestaoCompra.Linha> {

    static final String RUPTURA = "Ruptura";
    static final String SEM_VENDA = "Sem venda na média";

    @Override
    public String codigo() {
        return "R28";
    }

    @Override
    public String nome() {
        return "Cobertura e ruptura";
    }

    @Override
    public String descricao() {
        return "Quantos dias o estoque dura na venda média de cada produto e quais estão em ruptura (sem saldo e com "
                + "venda nos últimos 30 dias).";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.FORNECEDOR, TipoFiltro.CATEGORIA);
    }

    @Override
    public List<SugestaoCompra.Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros)
            throws SQLException {
        return SugestaoCompra.consultar(con, filtros.fornecedorId(), filtros.categoriaId(), parametros.hoje(),
                        parametros.zona(), parametros.compras()).stream()
                .sorted(Comparator.comparing((SugestaoCompra.Linha l) -> !l.ruptura())
                        .thenComparing(SugestaoCompra.Linha::coberturaDias,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(SugestaoCompra.Linha::produto, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    static String situacao(SugestaoCompra.Linha l) {
        if (l.ruptura()) {
            return RUPTURA;
        }
        return l.coberturaDias() == null ? SEM_VENDA : "";
    }

    @Override
    public List<Coluna<SugestaoCompra.Linha>> colunas(Filtros filtros) {
        return List.of(
                Coluna.<SugestaoCompra.Linha>codigo("Código", SugestaoCompra.Linha::produtoId).comLargura(0.8),
                Coluna.texto("Produto", SugestaoCompra.Linha::produto),
                Coluna.<SugestaoCompra.Linha>texto("Categoria", l -> l.categoria() == null ? "" : l.categoria())
                        .comLargura(1.6),
                Coluna.texto("Un.", SugestaoCompra.Linha::unidade).comLargura(0.6),
                Coluna.quantidade("Saldo", SugestaoCompra.Linha::saldo, SugestaoCompra.Linha::fracionada),
                Coluna.<SugestaoCompra.Linha>quantidade("Média/dia", SugestaoCompra.Linha::mediaDia, l -> true),
                Coluna.<SugestaoCompra.Linha>de("Cobertura (dias)", TipoColuna.INTEIRO,
                        SugestaoCompra.Linha::coberturaDias).comLargura(1.5),
                Coluna.<SugestaoCompra.Linha>texto("Situação", RelCoberturaRuptura::situacao).comLargura(1.6)
                        .comTotal(linhas -> linhas.stream().filter(SugestaoCompra.Linha::ruptura).count()
                                + " em ruptura"));
    }
}
