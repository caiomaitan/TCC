package br.com.varesync.report;

import br.com.varesync.util.Dinheiro;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * R15 — Alterações de preço (7.3): mudanças de preço de venda no período, de {@code produto_historico_preco}
 * (gravado pelo trigger), com o código de barras principal para trocar as etiquetas de gôndola. O registro do
 * cadastro (sem preço anterior) e as alterações só de custo ficam de fora. Da mais antiga para a mais recente.
 */
public final class RelAlteracoesPreco implements Relatorio<RelAlteracoesPreco.Linha> {

    public record Linha(OffsetDateTime dataHora, long produtoId, String codigoBarras, String produto,
                        BigDecimal precoAnterior, BigDecimal precoNovo, String usuario) {

        /** (novo − anterior) ÷ anterior × 100; "—" se o anterior era zero. */
        public BigDecimal variacao() {
            return Dinheiro.percentualDe(precoNovo.subtract(precoAnterior), precoAnterior).orElse(null);
        }
    }

    @Override
    public String codigo() {
        return "R15";
    }

    @Override
    public String nome() {
        return "Alterações de preço";
    }

    @Override
    public String descricao() {
        return "Preços de venda alterados no período, com o código de barras, para trocar as etiquetas de gôndola.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.PERIODO);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros)
            throws SQLException {
        String sql = """
                SELECT h.criado_em, h.produto_id, cb.codigo, p.nome, h.preco_anterior, h.preco_novo, u.nome AS usuario
                  FROM produto_historico_preco h
                  JOIN produto p ON p.id = h.produto_id
                  JOIN usuario u ON u.id = h.usuario_id
                  LEFT JOIN produto_codigo_barras cb ON cb.produto_id = p.id AND cb.principal
                 WHERE h.criado_em >= ? AND h.criado_em < ?
                   AND h.preco_anterior IS NOT NULL
                   AND h.preco_anterior <> h.preco_novo
                 ORDER BY h.criado_em, h.id
                """;
        List<Linha> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Consultas.periodo(ps, 1, filtros, parametros);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    linhas.add(new Linha(rs.getObject("criado_em", OffsetDateTime.class), rs.getLong("produto_id"),
                            rs.getString("codigo"), rs.getString("nome"), rs.getBigDecimal("preco_anterior"),
                            rs.getBigDecimal("preco_novo"), rs.getString("usuario")));
                }
            }
        }
        return linhas;
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        return List.of(
                Coluna.<Linha>de("Data/hora", TipoColuna.DATA_HORA, Linha::dataHora),
                Coluna.<Linha>de("Código", TipoColuna.INTEIRO, Linha::produtoId).comLargura(0.8),
                Coluna.<Linha>texto("Código de barras", l -> l.codigoBarras() == null ? "" : l.codigoBarras())
                        .comLargura(1.6),
                Coluna.texto("Produto", Linha::produto),
                Coluna.moeda("Preço anterior", Linha::precoAnterior).semTotal(),
                Coluna.moeda("Preço novo", Linha::precoNovo).semTotal(),
                Coluna.percentual("Variação %", Linha::variacao),
                Coluna.<Linha>texto("Usuário", Linha::usuario).comLargura(1.6));
    }
}
