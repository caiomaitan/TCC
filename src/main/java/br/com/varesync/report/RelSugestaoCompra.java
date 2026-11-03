package br.com.varesync.report;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * R26 — Sugestão de compra (F16.2): os produtos ativos com sugestão maior que zero, pela conta de
 * {@link SugestaoCompra} (média da venda líquida de devoluções, cobertura, prazo e fator do fornecedor principal,
 * estoque mínimo), por fornecedor principal e nome. O filtro de fornecedor é o do fornecedor principal. O valor
 * estimado (sugestão × último custo) é somado.
 */
public final class RelSugestaoCompra implements Relatorio<SugestaoCompra.Linha> {

    @Override
    public String codigo() {
        return "R26";
    }

    @Override
    public String nome() {
        return "Sugestão de compra";
    }

    @Override
    public String descricao() {
        return "O que comprar para cobrir a venda média (sem as devoluções) até a próxima entrega, sem ficar abaixo do "
                + "estoque mínimo. O fornecedor do filtro é o principal do produto.";
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
                .filter(l -> l.sugestao().signum() > 0)
                .sorted((a, b) -> {
                    // Sem fornecedor principal por último; depois pelo nome do fornecedor e do produto.
                    int f = a.fornecedor() == null ? (b.fornecedor() == null ? 0 : 1)
                            : b.fornecedor() == null ? -1 : a.fornecedor().compareToIgnoreCase(b.fornecedor());
                    return f != 0 ? f : a.produto().compareToIgnoreCase(b.produto());
                })
                .toList();
    }

    @Override
    public List<Coluna<SugestaoCompra.Linha>> colunas(Filtros filtros) {
        return List.of(
                Coluna.<SugestaoCompra.Linha>codigo("Código", SugestaoCompra.Linha::produtoId).comLargura(0.8),
                Coluna.texto("Produto", SugestaoCompra.Linha::produto),
                Coluna.<SugestaoCompra.Linha>texto("Fornecedor principal",
                        l -> l.fornecedor() == null ? "" : l.fornecedor()).comLargura(2.2),
                Coluna.texto("Un.", SugestaoCompra.Linha::unidade).comLargura(0.6),
                Coluna.quantidade("Saldo", SugestaoCompra.Linha::saldo, SugestaoCompra.Linha::fracionada),
                Coluna.<SugestaoCompra.Linha>quantidade("Média/dia", SugestaoCompra.Linha::mediaDia, l -> true),
                Coluna.<SugestaoCompra.Linha>de("Cobertura (dias)", TipoColuna.INTEIRO,
                        SugestaoCompra.Linha::coberturaDias).comLargura(1.5),
                Coluna.quantidade("Pendente", SugestaoCompra.Linha::pendente, SugestaoCompra.Linha::fracionada),
                Coluna.quantidade("Sugestão", SugestaoCompra.Linha::sugestao, SugestaoCompra.Linha::fracionada),
                Coluna.<SugestaoCompra.Linha>de("Último custo", TipoColuna.CUSTO, SugestaoCompra.Linha::ultimoCusto),
                Coluna.moeda("Valor estimado", SugestaoCompra.Linha::valorEstimado));
    }
}
