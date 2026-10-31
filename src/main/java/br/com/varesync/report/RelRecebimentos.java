package br.com.varesync.report;

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
 * R22 — Recebimentos (F14.4): recebimentos do crediário no período, com principal, multa, juros, desconto e total.
 * Filtrado por forma, mostra os recebimentos que usaram a forma, inteiros, e o valor na forma. Estornados ficam de fora,
 * como no dinheiro esperado do caixa.
 */
public final class RelRecebimentos implements Relatorio<RelRecebimentos.Linha> {

    public record Linha(OffsetDateTime dataHora, long recebimentoId, String cliente, String usuario, String formas,
                        BigDecimal principal, BigDecimal multa, BigDecimal juros, BigDecimal desconto, BigDecimal total,
                        BigDecimal naForma) {
    }

    @Override
    public String codigo() {
        return "R22";
    }

    @Override
    public String nome() {
        return "Recebimentos";
    }

    @Override
    public String descricao() {
        return "Recebimentos do crediário por período e forma, com principal, multa e juros. Estornados ficam de fora.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.PERIODO, TipoFiltro.FORMA_PAGAMENTO);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros) throws SQLException {
        Opcao forma = filtros.crediario().forma();
        Long formaId = forma == null ? null : forma.id();
        String sql = """
                SELECT r.id, r.data_hora, c.nome AS cliente, u.nome AS usuario, r.valor_principal, r.multa, r.juros,
                       r.desconto, r.valor_total,
                       (SELECT string_agg(f.descricao, ' + ' ORDER BY rp.id)
                          FROM recebimento_pagamento rp
                          JOIN forma_pagamento f ON f.id = rp.forma_pagamento_id
                         WHERE rp.recebimento_id = r.id) AS formas,
                       (SELECT sum(rp.valor)
                          FROM recebimento_pagamento rp
                         WHERE rp.recebimento_id = r.id
                           AND (CAST(? AS BIGINT) IS NULL OR rp.forma_pagamento_id = ?)) AS na_forma
                  FROM recebimento r
                  JOIN cliente c ON c.id = r.cliente_id
                  JOIN usuario u ON u.id = r.usuario_id
                 WHERE NOT r.estornado
                   AND r.data_hora >= ? AND r.data_hora < ?
                   AND (CAST(? AS BIGINT) IS NULL
                        OR EXISTS (SELECT 1 FROM recebimento_pagamento rp
                                    WHERE rp.recebimento_id = r.id AND rp.forma_pagamento_id = ?))
                 ORDER BY r.data_hora, r.id
                """;
        List<Linha> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Consultas.opcional(ps, 1, formaId);
            Consultas.periodo(ps, 3, filtros, parametros);
            Consultas.opcional(ps, 5, formaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    linhas.add(new Linha(rs.getObject("data_hora", OffsetDateTime.class), rs.getLong("id"),
                            rs.getString("cliente"), rs.getString("usuario"), rs.getString("formas"),
                            rs.getBigDecimal("valor_principal"), rs.getBigDecimal("multa"), rs.getBigDecimal("juros"),
                            rs.getBigDecimal("desconto"), rs.getBigDecimal("valor_total"),
                            rs.getBigDecimal("na_forma")));
                }
            }
        }
        return linhas;
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        List<Coluna<Linha>> colunas = new ArrayList<>(List.of(
                Coluna.<Linha>de("Data/hora", TipoColuna.DATA_HORA, Linha::dataHora).comLargura(2),
                Coluna.<Linha>codigo("Nº", Linha::recebimentoId).comLargura(0.6),
                Coluna.<Linha>texto("Cliente", Linha::cliente).comLargura(1.9),
                Coluna.<Linha>texto("Operador", Linha::usuario).comLargura(1.3),
                Coluna.<Linha>texto("Formas", Linha::formas).comLargura(1.5),
                Coluna.moeda("Principal", Linha::principal),
                Coluna.moeda("Multa", Linha::multa),
                Coluna.moeda("Juros", Linha::juros),
                Coluna.moeda("Desconto", Linha::desconto),
                Coluna.moeda("Total", Linha::total)));
        if (filtros.crediario().forma() != null) {
            colunas.add(Coluna.moeda("Na forma", Linha::naForma));
        }
        return colunas;
    }
}
