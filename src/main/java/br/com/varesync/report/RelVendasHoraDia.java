package br.com.varesync.report;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * R10 — Vendas por hora e dia da semana (7.3): grade com uma linha por hora (da primeira à última hora com venda no
 * período) e uma coluna por dia da semana, mais o total da hora. O critério escolhe o que aparece nas células:
 * receita ou nº de vendas. De {@code vw_resultado_item}: vendas finalizadas e, na hora em que foram feitas, as
 * devoluções (descontam a receita, não o nº de vendas); a hora e o dia são os do fuso do sistema, calculados no Java a
 * partir de cada venda ou devolução (como no R01).
 */
public final class RelVendasHoraDia implements Relatorio<RelVendasHoraDia.Linha> {

    /** Domingo primeiro, como no calendário brasileiro. */
    static final List<DayOfWeek> DIAS = List.of(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY);
    private static final List<String> NOMES = List.of("Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb");

    /**
     * Uma hora da grade.
     *
     * @param receitas receita por dia da semana, na ordem de {@link #DIAS}
     * @param vendas   nº de vendas por dia da semana, na mesma ordem
     */
    public record Linha(int hora, List<BigDecimal> receitas, List<Long> vendas) {

        public Linha {
            receitas = List.copyOf(receitas);
            vendas = List.copyOf(vendas);
        }

        /** "11h–12h". */
        public String faixa() {
            return String.format("%02dh–%02dh", hora, (hora + 1) % 24);
        }

        public BigDecimal receitaTotal() {
            return receitas.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public long vendasTotal() {
            return vendas.stream().mapToLong(Long::longValue).sum();
        }
    }

    /**
     * Uma venda ou uma devolução: quando e quanto.
     *
     * @param receita negativa na devolução
     * @param venda   falso na devolução, que entra na receita da hora mas não no nº de vendas
     */
    record Venda(OffsetDateTime dataHora, BigDecimal receita, boolean venda) {

        Venda(OffsetDateTime dataHora, BigDecimal receita) {
            this(dataHora, receita, true);
        }
    }

    @Override
    public String codigo() {
        return "R10";
    }

    @Override
    public String nome() {
        return "Vendas por hora e dia da semana";
    }

    @Override
    public String descricao() {
        return "Grade hora × dia da semana com a receita ou o nº de vendas, para ver os horários de pico.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.PERIODO, TipoFiltro.CRITERIO);
    }

    @Override
    public List<Criterio> criterios() {
        return List.of(Criterio.RECEITA, Criterio.VENDAS);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros)
            throws SQLException {
        String sql = """
                SELECT r.tipo, r.venda_id, r.devolucao_id, r.data_hora, sum(r.receita) AS receita
                  FROM vw_resultado_item r
                 WHERE r.data_hora >= ? AND r.data_hora < ?
                 GROUP BY r.tipo, r.venda_id, r.devolucao_id, r.data_hora
                """;
        List<Venda> vendas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Consultas.periodo(ps, 1, filtros, parametros);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    vendas.add(new Venda(rs.getObject("data_hora", OffsetDateTime.class),
                            rs.getBigDecimal("receita"), "VENDA".equals(rs.getString("tipo"))));
                }
            }
        }
        return grade(vendas, parametros.zona());
    }

    /** Distribui as vendas na grade; horas sem venda entre a primeira e a última aparecem zeradas. */
    static List<Linha> grade(List<Venda> vendas, ZoneId zona) {
        BigDecimal[][] receitas = new BigDecimal[24][7];
        long[][] quantidades = new long[24][7];
        for (BigDecimal[] hora : receitas) {
            Arrays.fill(hora, BigDecimal.ZERO);
        }
        int primeira = 24;
        int ultima = -1;
        for (Venda v : vendas) {
            ZonedDateTime local = v.dataHora().atZoneSameInstant(zona);
            int hora = local.getHour();
            int dia = DIAS.indexOf(local.getDayOfWeek());
            receitas[hora][dia] = receitas[hora][dia].add(v.receita());
            if (v.venda()) {
                quantidades[hora][dia]++;
            }
            primeira = Math.min(primeira, hora);
            ultima = Math.max(ultima, hora);
        }
        List<Linha> linhas = new ArrayList<>();
        for (int hora = primeira; hora <= ultima; hora++) {
            List<Long> porDia = new ArrayList<>();
            for (long q : quantidades[hora]) {
                porDia.add(q);
            }
            linhas.add(new Linha(hora, Arrays.asList(receitas[hora]), porDia));
        }
        return linhas;
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        boolean porVendas = filtros.criterioOuPadrao() == Criterio.VENDAS;
        List<Coluna<Linha>> colunas = new ArrayList<>();
        colunas.add(Coluna.<Linha>texto("Hora", Linha::faixa).comLargura(1.1));
        for (int i = 0; i < DIAS.size(); i++) {
            int dia = i;
            colunas.add(porVendas
                    ? Coluna.<Linha>inteiro(NOMES.get(i), l -> l.vendas().get(dia)).comLargura(1.1)
                    : Coluna.<Linha>moeda(NOMES.get(i), l -> l.receitas().get(dia)).comLargura(1.25));
        }
        colunas.add(porVendas
                ? Coluna.<Linha>inteiro("Total", Linha::vendasTotal).comLargura(1.1)
                : Coluna.<Linha>moeda("Total", Linha::receitaTotal).comLargura(1.4));
        return colunas;
    }
}
