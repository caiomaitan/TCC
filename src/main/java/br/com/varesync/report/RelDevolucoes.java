package br.com.varesync.report;

import br.com.varesync.model.FormaReembolso;
import br.com.varesync.util.CpfCnpj;
import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R20 — Devoluções (F13.4): devoluções do período (pela data da devolução), com quem registrou, a venda de origem, o
 * cliente (CPF mascarado), os itens (avariados marcados), o motivo, a forma de reembolso e o valor. O filtro de
 * operador é quem registrou a devolução; quem liberou fica no comprovante e na auditoria. Com crediário (F14.2),
 * mostra o valor abatido e as parcelas da venda em aberto na data do relatório.
 */
public final class RelDevolucoes implements Relatorio<RelDevolucoes.Linha> {

    public record Linha(OffsetDateTime dataHora, long devolucaoId, long vendaId, String usuario, String cliente,
                        String itens, String motivo, FormaReembolso forma, BigDecimal valor, BigDecimal abatido,
                        String parcelas) {

        public String formaRotulo() {
            return forma.curto();
        }
    }

    @Override
    public String codigo() {
        return "R20";
    }

    @Override
    public String nome() {
        return "Devoluções";
    }

    @Override
    public String descricao() {
        return "Devoluções de venda no período, com os itens, o motivo, a forma de reembolso e quem registrou.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.PERIODO, TipoFiltro.OPERADOR);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros)
            throws SQLException {
        String sql = """
                SELECT d.id, d.data_hora, d.venda_id, u.nome AS usuario, c.nome AS cliente, c.cpf_cnpj, d.motivo,
                       d.forma_reembolso, d.valor_total, d.valor_abatido,
                       di.quantidade, p.nome AS produto, p.unidade, um.fracionada, di.volta_estoque
                  FROM devolucao d
                  JOIN usuario u         ON u.id = d.usuario_id
                  LEFT JOIN cliente c    ON c.id = d.cliente_id
                  JOIN devolucao_item di ON di.devolucao_id = d.id
                  JOIN venda_item vi     ON vi.id = di.venda_item_id
                  JOIN produto p         ON p.id = vi.produto_id
                  JOIN unidade_medida um ON um.sigla = p.unidade
                 WHERE d.data_hora >= ? AND d.data_hora < ?
                   AND (CAST(? AS BIGINT) IS NULL OR d.usuario_id = ?)
                 ORDER BY d.data_hora, d.id, vi.sequencia
                """;
        record Cabeca(OffsetDateTime dataHora, long vendaId, String usuario, String cliente, String motivo,
                      FormaReembolso forma, BigDecimal valor, BigDecimal abatido, List<String> itens) {
        }
        Map<Long, Cabeca> devolucoes = new LinkedHashMap<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Consultas.periodo(ps, 1, filtros, parametros);
            Consultas.opcional(ps, 3, filtros.operadorId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    Cabeca cabeca = devolucoes.get(id);
                    if (cabeca == null) {
                        String nome = rs.getString("cliente");
                        String cpf = rs.getString("cpf_cnpj");
                        cabeca = new Cabeca(rs.getObject("data_hora", OffsetDateTime.class), rs.getLong("venda_id"),
                                rs.getString("usuario"), nome == null ? "" : cpf == null ? nome
                                : nome + " (" + CpfCnpj.ocultar(cpf) + ")", rs.getString("motivo"),
                                FormaReembolso.valueOf(rs.getString("forma_reembolso")),
                                rs.getBigDecimal("valor_total"), rs.getBigDecimal("valor_abatido"), new ArrayList<>());
                        devolucoes.put(id, cabeca);
                    }
                    cabeca.itens().add(Formatos.quantidade(rs.getBigDecimal("quantidade"), rs.getBoolean("fracionada"))
                            + " " + rs.getString("unidade") + " x " + rs.getString("produto")
                            + (rs.getBoolean("volta_estoque") ? "" : " (avariado)"));
                }
            }
        }
        List<Linha> linhas = new ArrayList<>();
        for (Map.Entry<Long, Cabeca> e : devolucoes.entrySet()) {
            Cabeca c = e.getValue();
            linhas.add(new Linha(c.dataHora(), e.getKey(), c.vendaId(), c.usuario(), c.cliente(),
                    String.join("; ", c.itens()), c.motivo(), c.forma(), c.valor(), c.abatido(),
                    c.abatido().signum() > 0 ? parcelasEmAberto(con, c.vendaId()) : ""));
        }
        return linhas;
    }

    /** Parcelas da venda em aberto agora ("1/3: R$ 33,34; 2/3: R$ 13,33"), ou "Nenhuma". */
    private static String parcelasEmAberto(Connection con, long vendaId) throws SQLException {
        String sql = """
                SELECT parcela, total_parcelas, valor - valor_pago - valor_abatido AS em_aberto
                  FROM titulo_receber
                 WHERE venda_id = ? AND status = 'ABERTO'
                 ORDER BY parcela
                """;
        List<String> parcelas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, vendaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    parcelas.add(rs.getInt("parcela") + "/" + rs.getInt("total_parcelas") + ": "
                            + Formatos.moeda(rs.getBigDecimal("em_aberto")));
                }
            }
        }
        return parcelas.isEmpty() ? "Nenhuma" : String.join("; ", parcelas);
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        return List.of(
                Coluna.<Linha>de("Data/hora", TipoColuna.DATA_HORA, Linha::dataHora).comLargura(1.8),
                Coluna.<Linha>codigo("Devolução", Linha::devolucaoId).comLargura(1.1),
                Coluna.<Linha>codigo("Venda", Linha::vendaId).comLargura(0.7),
                Coluna.<Linha>texto("Registrada por", Linha::usuario).comLargura(1.3),
                Coluna.<Linha>texto("Cliente", Linha::cliente).comLargura(1.5),
                Coluna.<Linha>texto("Itens", Linha::itens).comLargura(1.9),
                Coluna.<Linha>texto("Motivo", Linha::motivo).comLargura(1.2),
                Coluna.<Linha>texto("Reembolso", Linha::formaRotulo).comLargura(1.3),
                Coluna.moeda("Valor", Linha::valor),
                Coluna.moeda("Abatido", Linha::abatido),
                Coluna.<Linha>texto("Parcelas em aberto", Linha::parcelas).comLargura(1.5));
    }
}
