package br.com.varesync.report;

import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.service.VendaService;
import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R16 — Cancelamentos (7.3): vendas canceladas (pela data do cancelamento) e itens cancelados durante a venda (pela
 * data da venda) no período, com o operador da venda, o motivo, quem cancelou e quem liberou.
 *
 * <ul>
 * <li>Venda: quem cancelou é o usuário da auditoria {@code VENDA_CANCELADA}; quem liberou é
 * {@code venda.cancelada_por} quando é outra pessoa (a liberação do supervisor, 6.6).</li>
 * <li>Item: quem cancelou é o operador da venda (o F6 é dado durante a venda); quem liberou vem da auditoria
 * {@code ITEM_CANCELADO} desse item ("Item 2 cancelado durante a venda: … (liberado por Nome (login))"), a última
 * do mesmo operador e terminal antes da finalização. Itens de vendas abandonadas não aparecem (não há venda).</li>
 * </ul>
 */
public final class RelCancelamentos implements Relatorio<RelCancelamentos.Linha> {

    static final String VENDA = "Venda";
    static final String ITEM = "Item";
    static final String MOTIVO_ITEM = "Cancelado durante a venda";
    private static final Pattern LIBERADO_POR = Pattern.compile("\\(liberado por (.+)\\)\\s*$");

    /** @param liberadoPor vazio quando ninguém precisou liberar */
    public record Linha(OffsetDateTime dataHora, String tipo, long vendaId, String descricao, BigDecimal valor,
                        String operador, String motivo, String canceladoPor, String liberadoPor) {
    }

    @Override
    public String codigo() {
        return "R16";
    }

    @Override
    public String nome() {
        return "Cancelamentos";
    }

    @Override
    public String descricao() {
        return "Vendas canceladas e itens cancelados durante a venda, com o motivo, quem cancelou e quem liberou.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.PERIODO, TipoFiltro.OPERADOR);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros)
            throws SQLException {
        List<Linha> linhas = new ArrayList<>();
        vendas(con, filtros, parametros, linhas);
        itens(con, filtros, parametros, linhas);
        linhas.sort(Comparator.comparing(Linha::dataHora).thenComparing(Linha::vendaId)
                .thenComparing(l -> VENDA.equals(l.tipo())));
        return linhas;
    }

    private static void vendas(Connection con, Filtros filtros, ParametrosRelatorio parametros, List<Linha> linhas)
            throws SQLException {
        String sql = """
                SELECT v.id, v.cancelada_em, v.valor_total, v.motivo_cancelamento, op.nome AS operador,
                       aut.id AS autorizou_id, aut.nome AS autorizou, aut.login AS autorizou_login,
                       fez.id AS fez_id, fez.nome AS fez,
                       (SELECT count(*) FROM venda_item vi WHERE vi.venda_id = v.id AND NOT vi.cancelado) AS itens
                  FROM venda v
                  JOIN usuario op  ON op.id = v.usuario_id
                  JOIN usuario aut ON aut.id = v.cancelada_por
                  LEFT JOIN LATERAL (SELECT a.usuario_id
                                       FROM auditoria a
                                      WHERE a.acao = ? AND a.entidade = ? AND a.entidade_id = v.id
                                      ORDER BY a.id DESC
                                      LIMIT 1) a ON TRUE
                  LEFT JOIN usuario fez ON fez.id = a.usuario_id
                 WHERE v.status = 'CANCELADA'
                   AND v.cancelada_em >= ? AND v.cancelada_em < ?
                   AND (CAST(? AS BIGINT) IS NULL OR v.usuario_id = ?)
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, AcaoAuditoria.VENDA_CANCELADA.name());
            ps.setString(2, VendaService.ENTIDADE_VENDA);
            Consultas.periodo(ps, 3, filtros, parametros);
            Consultas.opcional(ps, 5, filtros.operadorId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long itens = rs.getLong("itens");
                    String fez = rs.getString("fez");
                    boolean outro = fez != null && rs.getLong("fez_id") != rs.getLong("autorizou_id");
                    linhas.add(new Linha(rs.getObject("cancelada_em", OffsetDateTime.class), VENDA, rs.getLong("id"),
                            "Venda inteira (" + itens + (itens == 1 ? " item)" : " itens)"),
                            rs.getBigDecimal("valor_total"), rs.getString("operador"),
                            rs.getString("motivo_cancelamento"), fez == null ? rs.getString("autorizou") : fez,
                            outro ? rs.getString("autorizou") + " (" + rs.getString("autorizou_login") + ")"
                                    : ""));
                }
            }
        }
    }

    private static void itens(Connection con, Filtros filtros, ParametrosRelatorio parametros, List<Linha> linhas)
            throws SQLException {
        String sql = """
                SELECT v.id, v.data_hora, vi.sequencia, vi.quantidade, vi.valor_bruto, p.nome AS produto, p.unidade,
                       um.fracionada, op.nome AS operador, a.descricao AS auditoria
                  FROM venda_item vi
                  JOIN venda v           ON v.id = vi.venda_id
                  JOIN caixa_sessao cs   ON cs.id = v.caixa_sessao_id
                  JOIN produto p         ON p.id = vi.produto_id
                  JOIN unidade_medida um ON um.sigla = p.unidade
                  JOIN usuario op        ON op.id = v.usuario_id
                  LEFT JOIN LATERAL (SELECT a.descricao
                                       FROM auditoria a
                                      WHERE a.acao = ? AND a.usuario_id = v.usuario_id
                                        AND a.terminal = cs.terminal AND a.criado_em <= v.data_hora
                                        AND a.descricao LIKE 'Item ' || vi.sequencia || ' cancelado durante a venda:%'
                                      ORDER BY a.criado_em DESC, a.id DESC
                                      LIMIT 1) a ON TRUE
                 WHERE vi.cancelado
                   AND v.data_hora >= ? AND v.data_hora < ?
                   AND (CAST(? AS BIGINT) IS NULL OR v.usuario_id = ?)
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, AcaoAuditoria.ITEM_CANCELADO.name());
            Consultas.periodo(ps, 2, filtros, parametros);
            Consultas.opcional(ps, 4, filtros.operadorId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String operador = rs.getString("operador");
                    linhas.add(new Linha(rs.getObject("data_hora", OffsetDateTime.class), ITEM, rs.getLong("id"),
                            "Item " + rs.getInt("sequencia") + ": " + Formatos.quantidade(
                                    rs.getBigDecimal("quantidade"), rs.getBoolean("fracionada")) + " "
                                    + rs.getString("unidade") + " x " + rs.getString("produto"),
                            rs.getBigDecimal("valor_bruto"), operador, MOTIVO_ITEM, operador,
                            liberadoPor(rs.getString("auditoria"))));
                }
            }
        }
    }

    /** "… (liberado por Carlos Pereira (carlos))" → "Carlos Pereira (carlos)"; vazio sem liberação. */
    static String liberadoPor(String descricaoAuditoria) {
        if (descricaoAuditoria == null) {
            return "";
        }
        Matcher m = LIBERADO_POR.matcher(descricaoAuditoria);
        return m.find() ? m.group(1) : "";
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        return List.of(
                Coluna.<Linha>de("Data/hora", TipoColuna.DATA_HORA, Linha::dataHora),
                Coluna.<Linha>texto("Tipo", Linha::tipo).comLargura(0.7),
                Coluna.<Linha>de("Venda nº", TipoColuna.INTEIRO, Linha::vendaId).comLargura(1.0),
                Coluna.<Linha>texto("Descrição", Linha::descricao).comLargura(3.6),
                Coluna.moeda("Valor", Linha::valor),
                Coluna.<Linha>texto("Operador", Linha::operador).comLargura(1.6),
                Coluna.<Linha>texto("Motivo", Linha::motivo).comLargura(2.2),
                Coluna.<Linha>texto("Cancelado por", Linha::canceladoPor).comLargura(1.6),
                Coluna.<Linha>texto("Liberado por", Linha::liberadoPor).comLargura(1.8));
    }
}
