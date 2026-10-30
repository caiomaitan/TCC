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
 * R01 — Vendas por período (7.3 e F13.3): por dia, nº de vendas, vendas, devoluções, receita líquida, descontos, CMV,
 * lucro, margem e ticket médio; com "detalhar por venda", uma linha por venda e uma por devolução. Parte de
 * {@code vw_resultado_item} (itens não cancelados de vendas finalizadas, menos as devoluções na data delas). O nº de
 * vendas e o ticket médio contam só as vendas. Os dias são agrupados no Java, no fuso do sistema: o total por dia é
 * exatamente a soma das vendas e devoluções do dia.
 */
public final class RelVendasPeriodo implements Relatorio<RelVendasPeriodo.Linha> {

    /**
     * Um dia (vendaId nulo), uma venda ou uma devolução (devolucaoId preenchido).
     *
     * @param vendido   receita das vendas
     * @param devolvido valor das devoluções (positivo)
     * @param descontos desconto no item + rateio do desconto da venda (só das vendas)
     * @param cmv       CMV das vendas menos o das devoluções
     * @param lucro     receita líquida − CMV
     */
    public record Linha(LocalDate dia, Long vendaId, Long devolucaoId, OffsetDateTime dataHora, String operador,
                        long vendas, BigDecimal vendido, BigDecimal devolvido, BigDecimal descontos, BigDecimal cmv,
                        BigDecimal lucro) {

        /** Receita líquida = vendas − devoluções. */
        public BigDecimal receita() {
            return vendido.subtract(devolvido);
        }

        public BigDecimal margem() {
            return Dinheiro.percentualDe(lucro, receita()).orElse(null);
        }

        /** Só sobre as vendas (F13.3). */
        public BigDecimal ticketMedio() {
            return Dinheiro.dividir(vendido, BigDecimal.valueOf(vendas), Dinheiro.ESCALA_DINHEIRO).orElse(null);
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
        return "Por dia: número de vendas, vendas, devoluções, receita líquida, descontos, CMV, lucro, margem e ticket "
                + "médio. Vendas e itens cancelados ficam de fora; as devoluções entram na data em que foram feitas.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.PERIODO, TipoFiltro.OPERADOR, TipoFiltro.DETALHAR_VENDAS);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros)
            throws SQLException {
        String sql = """
                SELECT r.tipo, r.venda_id, r.devolucao_id, r.data_hora, u.nome AS operador, sum(r.receita) AS receita,
                       sum(r.desconto_total) AS descontos, sum(r.cmv) AS cmv, sum(r.lucro_bruto) AS lucro
                  FROM vw_resultado_item r
                  JOIN usuario u ON u.id = r.usuario_id
                 WHERE r.data_hora >= ? AND r.data_hora < ?
                   AND (CAST(? AS BIGINT) IS NULL OR r.usuario_id = ?)
                 GROUP BY r.tipo, r.venda_id, r.devolucao_id, r.data_hora, u.nome
                 ORDER BY r.data_hora, r.venda_id, r.devolucao_id NULLS FIRST
                """;
        List<Linha> porVenda = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Consultas.periodo(ps, 1, filtros, parametros);
            Consultas.opcional(ps, 3, filtros.operadorId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    OffsetDateTime dataHora = rs.getObject("data_hora", OffsetDateTime.class);
                    LocalDate dia = dataHora.atZoneSameInstant(parametros.zona()).toLocalDate();
                    BigDecimal receita = rs.getBigDecimal("receita");
                    boolean venda = "VENDA".equals(rs.getString("tipo"));
                    porVenda.add(new Linha(dia, rs.getLong("venda_id"),
                            venda ? null : rs.getLong("devolucao_id"), dataHora, rs.getString("operador"),
                            venda ? 1 : 0, venda ? receita : Dinheiro.ZERO, venda ? Dinheiro.ZERO : receita.negate(),
                            rs.getBigDecimal("descontos"), rs.getBigDecimal("cmv"), rs.getBigDecimal("lucro")));
                }
            }
        }
        return filtros.detalharVendas() ? porVenda : porDia(porVenda);
    }

    static List<Linha> porDia(List<Linha> vendas) {
        Map<LocalDate, Linha> dias = new LinkedHashMap<>();
        for (Linha v : vendas) {
            Linha dia = new Linha(v.dia(), null, null, null, null, v.vendas(), v.vendido(), v.devolvido(),
                    v.descontos(), v.cmv(), v.lucro());
            dias.merge(v.dia(), dia, (a, b) -> new Linha(a.dia(), null, null, null, null, a.vendas() + b.vendas(),
                    a.vendido().add(b.vendido()), a.devolvido().add(b.devolvido()), a.descontos().add(b.descontos()),
                    a.cmv().add(b.cmv()), a.lucro().add(b.lucro())));
        }
        return List.copyOf(dias.values());
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        List<Coluna<Linha>> colunas = new ArrayList<>();
        if (filtros.detalharVendas()) {
            colunas.add(Coluna.<Linha>codigo("Venda nº", Linha::vendaId).comLargura(0.9));
            colunas.add(Coluna.<Linha>codigo("Devolução nº", Linha::devolucaoId).comLargura(1.0));
            colunas.add(Coluna.<Linha>de("Data/hora", TipoColuna.DATA_HORA, Linha::dataHora));
            colunas.add(Coluna.<Linha>texto("Operador", Linha::operador).comLargura(1.8));
        } else {
            colunas.add(Coluna.<Linha>de("Dia", TipoColuna.DATA, Linha::dia));
            colunas.add(Coluna.<Linha>inteiro("Nº vendas", Linha::vendas).comLargura(1.1));
        }
        colunas.add(Coluna.moeda("Vendas", Linha::vendido));
        colunas.add(Coluna.moeda("Devoluções", Linha::devolvido));
        colunas.add(Coluna.moeda("Receita líquida", Linha::receita));
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

    /** Σ lucro ÷ Σ receita líquida × 100 (A.6). */
    static BigDecimal margemTotal(List<Linha> linhas) {
        return Dinheiro.percentualDe(Coluna.soma(linhas, Linha::lucro), Coluna.soma(linhas, Linha::receita))
                .orElse(null);
    }

    /** Σ vendas ÷ Σ nº de vendas (só as vendas). */
    static BigDecimal ticketTotal(List<Linha> linhas) {
        long vendas = linhas.stream().mapToLong(Linha::vendas).sum();
        return Dinheiro.dividir(Coluna.soma(linhas, Linha::vendido), BigDecimal.valueOf(vendas),
                Dinheiro.ESCALA_DINHEIRO).orElse(null);
    }
}
