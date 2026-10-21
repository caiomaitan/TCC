package br.com.varesync.report;

import br.com.varesync.util.Dinheiro;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R01 — Vendas por período (7.3): por dia, nº de vendas, receita, descontos, CMV, lucro, margem e ticket médio; com
 * "detalhar por venda", uma linha por venda. Parte de {@code vw_venda_item_rentabilidade} (só vendas finalizadas e
 * itens não cancelados). Os dias são agrupados no Java, no fuso do sistema, a partir das vendas: o total por dia é
 * exatamente a soma das vendas do dia.
 */
public final class RelVendasPeriodo implements Relatorio<RelVendasPeriodo.Linha> {

    /**
     * Um dia (vendaId nulo) ou uma venda.
     *
     * @param descontos desconto no item + rateio do desconto da venda
     */
    public record Linha(LocalDate dia, Long vendaId, OffsetDateTime dataHora, String operador, long vendas,
                        BigDecimal receita, BigDecimal descontos, BigDecimal cmv, BigDecimal lucro) {

        public BigDecimal margem() {
            return Dinheiro.percentualDe(lucro, receita).orElse(null);
        }

        public BigDecimal ticketMedio() {
            return Dinheiro.dividir(receita, BigDecimal.valueOf(vendas), Dinheiro.ESCALA_DINHEIRO).orElse(null);
        }
    }

    @Override
    public String codigo() {
        return "R01";
    }

    @Override
    public String nome() {
        return "Vendas por período";
    }

    @Override
    public String descricao() {
        return "Por dia: número de vendas, receita, descontos, CMV, lucro, margem e ticket médio. Vendas e itens "
                + "cancelados ficam de fora.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.PERIODO, TipoFiltro.OPERADOR, TipoFiltro.DETALHAR_VENDAS);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros)
            throws SQLException {
        String sql = """
                SELECT r.venda_id, r.data_hora, u.nome AS operador, sum(r.receita) AS receita,
                       sum(r.desconto_total) AS descontos, sum(r.cmv) AS cmv, sum(r.lucro_bruto) AS lucro
                  FROM vw_venda_item_rentabilidade r
                  JOIN usuario u ON u.id = r.usuario_id
                 WHERE r.data_hora >= ? AND r.data_hora < ?
                   AND (CAST(? AS BIGINT) IS NULL OR r.usuario_id = ?)
                 GROUP BY r.venda_id, r.data_hora, u.nome
                 ORDER BY r.data_hora, r.venda_id
                """;
        List<Linha> porVenda = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Consultas.periodo(ps, 1, filtros, parametros);
            Consultas.opcional(ps, 3, filtros.operadorId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    OffsetDateTime dataHora = rs.getObject("data_hora", OffsetDateTime.class);
                    porVenda.add(new Linha(dataHora.atZoneSameInstant(parametros.zona()).toLocalDate(),
                            rs.getLong("venda_id"), dataHora, rs.getString("operador"), 1,
                            rs.getBigDecimal("receita"), rs.getBigDecimal("descontos"), rs.getBigDecimal("cmv"),
                            rs.getBigDecimal("lucro")));
                }
            }
        }
        return filtros.detalharVendas() ? porVenda : porDia(porVenda);
    }

    static List<Linha> porDia(List<Linha> vendas) {
        Map<LocalDate, Linha> dias = new LinkedHashMap<>();
        for (Linha v : vendas) {
            dias.merge(v.dia(), v, (a, b) -> new Linha(a.dia(), null, null, null, a.vendas() + b.vendas(),
                    a.receita().add(b.receita()), a.descontos().add(b.descontos()), a.cmv().add(b.cmv()),
                    a.lucro().add(b.lucro())));
        }
        return dias.values().stream()
                .map(d -> d.vendaId() == null ? d : new Linha(d.dia(), null, null, null, d.vendas(), d.receita(),
                        d.descontos(), d.cmv(), d.lucro()))
                .toList();
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        List<Coluna<Linha>> colunas = new ArrayList<>();
        if (filtros.detalharVendas()) {
            colunas.add(Coluna.<Linha>codigo("Venda nº", Linha::vendaId).comLargura(0.9));
            colunas.add(Coluna.<Linha>de("Data/hora", TipoColuna.DATA_HORA, Linha::dataHora));
            colunas.add(Coluna.<Linha>texto("Operador", Linha::operador).comLargura(2));
        } else {
            colunas.add(Coluna.<Linha>de("Dia", TipoColuna.DATA, Linha::dia));
            colunas.add(Coluna.inteiro("Vendas", Linha::vendas));
        }
        colunas.add(Coluna.moeda("Receita", Linha::receita));
        colunas.add(Coluna.moeda("Descontos", Linha::descontos));
        colunas.add(Coluna.moeda("CMV", Linha::cmv));
        colunas.add(Coluna.moeda("Lucro", Linha::lucro));
        colunas.add(Coluna.percentual("Margem %", Linha::margem).comTotal(RelVendasPeriodo::margemTotal));
        if (!filtros.detalharVendas()) {
            colunas.add(Coluna.<Linha>de("Ticket médio", TipoColuna.MOEDA, Linha::ticketMedio)
                    .comTotal(RelVendasPeriodo::ticketTotal));
        }
        return colunas;
    }

    /** Σ lucro ÷ Σ receita × 100 (A.6). */
    static BigDecimal margemTotal(List<Linha> linhas) {
        return Dinheiro.percentualDe(Coluna.soma(linhas, Linha::lucro), Coluna.soma(linhas, Linha::receita))
                .orElse(null);
    }

    /** Σ receita ÷ Σ vendas. */
    static BigDecimal ticketTotal(List<Linha> linhas) {
        long vendas = linhas.stream().mapToLong(Linha::vendas).sum();
        return Dinheiro.dividir(Coluna.soma(linhas, Linha::receita), BigDecimal.valueOf(vendas),
                Dinheiro.ESCALA_DINHEIRO).orElse(null);
    }
}
