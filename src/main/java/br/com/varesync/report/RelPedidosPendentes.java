package br.com.varesync.report;

import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * R27 — Pedidos pendentes (F16.4): um item por linha dos pedidos de compra ENVIADO e PARCIAL que ainda têm o que
 * receber (quantidade − recebida &gt; 0), com o valor pendente a custo previsto. Os pedidos com a previsão de entrega
 * antes de hoje aparecem como atrasados. Pela previsão (sem previsão por último), pedido e item.
 */
public final class RelPedidosPendentes implements Relatorio<RelPedidosPendentes.Linha> {

    static final String ATRASADO = "Atrasado";

    /** Um item pendente de um pedido. */
    public record Linha(long pedidoId, String fornecedor, String situacao, OffsetDateTime enviadoEm,
                        LocalDate previsaoEntrega, boolean atrasado, long produtoId, String produto, String unidade,
                        boolean fracionada, BigDecimal pedida, BigDecimal recebida, BigDecimal pendente,
                        BigDecimal custoPrevisto, BigDecimal valorPendente) {
    }

    @Override
    public String codigo() {
        return "R27";
    }

    @Override
    public String nome() {
        return "Pedidos pendentes";
    }

    @Override
    public String descricao() {
        return "O que ainda falta receber dos pedidos de compra enviados e parciais, com o valor a custo previsto e os "
                + "atrasados (previsão de entrega antes de hoje).";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.FORNECEDOR);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros) throws SQLException {
        String sql = """
                SELECT p.id, f.razao_social, p.status, p.enviado_em, p.previsao_entrega, i.produto_id, pr.nome,
                       pr.unidade, u.fracionada, i.quantidade, i.quantidade_recebida,
                       i.quantidade - i.quantidade_recebida AS pendente, i.custo_previsto,
                       round((i.quantidade - i.quantidade_recebida) * i.custo_previsto, 2) AS valor_pendente
                  FROM pedido_compra p
                  JOIN pedido_compra_item i ON i.pedido_id = p.id
                  JOIN fornecedor f         ON f.id = p.fornecedor_id
                  JOIN produto pr           ON pr.id = i.produto_id
                  JOIN unidade_medida u     ON u.sigla = pr.unidade
                 WHERE p.status IN ('ENVIADO', 'PARCIAL')
                   AND i.quantidade_recebida < i.quantidade
                   AND (CAST(? AS BIGINT) IS NULL OR p.fornecedor_id = ?)
                 ORDER BY p.previsao_entrega NULLS LAST, p.id, i.id
                """;
        List<Linha> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Consultas.opcional(ps, 1, filtros.fornecedorId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LocalDate previsao = rs.getObject("previsao_entrega", LocalDate.class);
                    linhas.add(new Linha(rs.getLong("id"), rs.getString("razao_social"),
                            "PARCIAL".equals(rs.getString("status")) ? "Parcial" : "Enviado",
                            rs.getObject("enviado_em", OffsetDateTime.class), previsao,
                            previsao != null && previsao.isBefore(parametros.hoje()), rs.getLong("produto_id"),
                            rs.getString("nome"), rs.getString("unidade"), rs.getBoolean("fracionada"),
                            rs.getBigDecimal("quantidade"), rs.getBigDecimal("quantidade_recebida"),
                            rs.getBigDecimal("pendente"), rs.getBigDecimal("custo_previsto"),
                            rs.getBigDecimal("valor_pendente")));
                }
            }
        }
        return linhas;
    }

    static String previsao(Linha l) {
        if (l.previsaoEntrega() == null) {
            return "";
        }
        return Formatos.data(l.previsaoEntrega()) + (l.atrasado() ? " (" + ATRASADO + ")" : "");
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        return List.of(
                Coluna.<Linha>codigo("Pedido", Linha::pedidoId).comLargura(1.0),
                Coluna.<Linha>texto("Fornecedor", Linha::fornecedor).comLargura(2.2),
                Coluna.<Linha>texto("Situação", Linha::situacao).comLargura(1.1),
                Coluna.<Linha>de("Enviado em", TipoColuna.DATA, l -> l.enviadoEm() == null ? null
                        : l.enviadoEm().atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()).comLargura(1.6),
                Coluna.<Linha>texto("Previsão", RelPedidosPendentes::previsao).comLargura(1.7)
                        .comTotal(linhas -> linhas.stream().filter(Linha::atrasado).count() + " atrasado(s)"),
                Coluna.<Linha>texto("Produto", Linha::produto).comLargura(2.4),
                Coluna.texto("Un.", Linha::unidade).comLargura(0.6),
                Coluna.<Linha>quantidade("Pedida", Linha::pedida, Linha::fracionada).comLargura(1.2),
                Coluna.<Linha>quantidade("Recebida", Linha::recebida, Linha::fracionada).comLargura(1.2),
                Coluna.<Linha>quantidade("Pendente", Linha::pendente, Linha::fracionada).comLargura(1.2),
                Coluna.<Linha>de("Custo previsto", TipoColuna.CUSTO, Linha::custoPrevisto),
                Coluna.moeda("Valor pendente", Linha::valorPendente));
    }
}
