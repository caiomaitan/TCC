package br.com.varesync.report;

import br.com.varesync.model.Periodo;
import br.com.varesync.model.TipoContaMovimento;
import br.com.varesync.service.NegocioException;
import br.com.varesync.util.Dinheiro;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluxo de caixa (F15.4), base da aba Fluxo de caixa do Financeiro e do R24.
 *
 * <ul>
 * <li><b>Realizado:</b> os lançamentos das contas no período, somados por grupo (dia, semana ou mês) e tipo. Ficam de
 * fora as transferências entre contas (não mudam o total) e o saldo inicial, que entra no saldo de partida; os ajustes
 * têm linha própria, com entradas e saídas.</li>
 * <li><b>Previsto</b> (de hoje até o fim do período): recebíveis PREVISTOS pelo líquido na data prevista, títulos a
 * receber e a pagar ABERTOS pelo que falta, no vencimento. O que já devia ter entrado ou saído e continua em aberto
 * vai para o grupo de hoje, o primeiro da projeção, na linha "Em atraso".</li>
 * <li><b>Saldo:</b> o de partida é o saldo atual das contas menos o realizado desde o início do período; somando o
 * realizado chega-se ao saldo atual e, somando o previsto, ao saldo projetado.</li>
 * </ul>
 *
 * <p>O início não pode ser depois de hoje: a projeção parte do saldo atual das contas.
 *
 * @param saldoInicial saldo das contas no início do período (00:00), com o saldo inicial lançado no período
 * @param saldoAtual   soma dos saldos das contas agora
 * @param lancamentos  linhas não vazias, na ordem do grupo, do realizado para o previsto
 * @param grupos       todos os grupos do período, mesmo os vazios (para o gráfico)
 */
public record FluxoCaixa(Periodo periodo, LocalDate hoje, Agrupamento agrupamento, BigDecimal saldoInicial,
                         BigDecimal saldoAtual, List<Lancamento> lancamentos, List<Grupo> grupos) {

    public static final String EM_ATRASO = "Em atraso";
    public static final String AJUSTES = "Ajustes";
    public static final String RECEBIVEIS = "Recebíveis de cartão e PIX";
    public static final String CREDIARIO = "Crediário a receber";
    public static final String CONTAS_A_PAGAR = "Contas a pagar";
    private static final List<String> ORDEM_PREVISTO = List.of(EM_ATRASO, RECEBIVEIS, CREDIARIO, CONTAS_A_PAGAR);

    public enum Parte {
        REALIZADO("Realizado"),
        PREVISTO("Previsto");

        private final String rotulo;

        Parte(String rotulo) {
            this.rotulo = rotulo;
        }

        public String rotulo() {
            return rotulo;
        }
    }

    /** Soma de um tipo de lançamento num grupo; as saídas vêm positivas. */
    public record Lancamento(LocalDate grupo, Parte parte, String descricao, BigDecimal entradas, BigDecimal saidas) {

        public BigDecimal liquido() {
            return entradas.subtract(saidas);
        }
    }

    /** Um dia, uma semana ou um mês, com o saldo das contas no fim dele (realizado + previsto). */
    public record Grupo(LocalDate inicio, String rotulo, BigDecimal entradasRealizadas, BigDecimal saidasRealizadas,
                        BigDecimal entradasPrevistas, BigDecimal saidasPrevistas, BigDecimal saldoFinal) {

        public BigDecimal entradas() {
            return entradasRealizadas.add(entradasPrevistas);
        }

        public BigDecimal saidas() {
            return saidasRealizadas.add(saidasPrevistas);
        }
    }

    public FluxoCaixa {
        lancamentos = List.copyOf(lancamentos);
        grupos = List.copyOf(grupos);
    }

    public BigDecimal saldoFinal() {
        return grupos.isEmpty() ? saldoInicial : grupos.get(grupos.size() - 1).saldoFinal();
    }

    /** Confere o período antes de consultar. */
    public static void conferir(Periodo periodo, LocalDate hoje) {
        if (periodo == null || periodo.inicio() == null || periodo.fim() == null) {
            throw new NegocioException("Informe a data inicial e a final do período.");
        }
        if (periodo.inicio().isAfter(periodo.fim())) {
            throw new NegocioException("A data inicial é depois da final.");
        }
        if (periodo.inicio().isAfter(hoje)) {
            throw new NegocioException("O fluxo de caixa começa hoje ou antes: a projeção parte do saldo atual das "
                    + "contas.");
        }
    }

    public static FluxoCaixa calcular(Connection con, Periodo periodo, Agrupamento agrupamento, LocalDate hoje,
                                      ZoneId zona) throws SQLException {
        conferir(periodo, hoje);
        OffsetDateTime inicio = periodo.inicioEm(zona);
        BigDecimal saldoAtual = valor(con, "SELECT coalesce(sum(saldo), 0) FROM conta_saldo", null);
        BigDecimal desdeOInicio = valor(con, """
                SELECT coalesce(sum(valor), 0) FROM conta_movimento
                 WHERE data_hora >= ? AND tipo NOT IN ('SALDO_INICIAL', 'TRANSFERENCIA')
                """, inicio);
        Map<String, Lancamento> linhas = new LinkedHashMap<>();
        realizado(con, periodo, agrupamento, zona, linhas);
        if (!periodo.fim().isBefore(hoje)) {
            previsto(con, periodo, agrupamento, hoje, linhas);
        }
        List<Lancamento> ordenadas = new ArrayList<>(linhas.values());
        ordenadas.sort(Comparator.comparing(Lancamento::grupo).thenComparing(Lancamento::parte)
                .thenComparingInt(FluxoCaixa::ordem));
        BigDecimal saldoInicial = saldoAtual.subtract(desdeOInicio);
        return new FluxoCaixa(periodo, hoje, agrupamento, saldoInicial, saldoAtual, ordenadas,
                grupos(periodo, agrupamento, saldoInicial, ordenadas));
    }

    private static int ordem(Lancamento l) {
        if (l.parte() == Parte.PREVISTO) {
            return ORDEM_PREVISTO.indexOf(l.descricao());
        }
        for (TipoContaMovimento t : TipoContaMovimento.values()) {
            if (t.rotulo().equals(l.descricao())) {
                return t.ordinal();
            }
        }
        return TipoContaMovimento.values().length; // Ajustes por último
    }

    private static void realizado(Connection con, Periodo periodo, Agrupamento agrupamento, ZoneId zona,
                                  Map<String, Lancamento> linhas) throws SQLException {
        String sql = """
                SELECT data_hora, tipo, valor FROM conta_movimento
                 WHERE data_hora >= ? AND data_hora < ? AND tipo NOT IN ('SALDO_INICIAL', 'TRANSFERENCIA')
                 ORDER BY data_hora, id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, periodo.inicioEm(zona));
            ps.setObject(2, periodo.fimExclusivoEm(zona));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LocalDate dia = rs.getObject("data_hora", OffsetDateTime.class).atZoneSameInstant(zona)
                            .toLocalDate();
                    TipoContaMovimento tipo = TipoContaMovimento.valueOf(rs.getString("tipo"));
                    somar(linhas, agrupamento.inicioDoGrupo(dia, periodo.inicio()), Parte.REALIZADO,
                            tipo == TipoContaMovimento.AJUSTE ? AJUSTES : tipo.rotulo(), rs.getBigDecimal("valor"));
                }
            }
        }
    }

    private static void previsto(Connection con, Periodo periodo, Agrupamento agrupamento, LocalDate hoje,
                                 Map<String, Lancamento> linhas) throws SQLException {
        LocalDate primeiro = agrupamento.inicioDoGrupo(hoje, periodo.inicio());
        String sql = """
                SELECT 'R' AS origem, previsto_para AS data, valor_liquido AS valor
                  FROM recebivel WHERE status = 'PREVISTO' AND previsto_para <= ?
                UNION ALL
                SELECT 'C', vencimento, valor - valor_pago - valor_abatido
                  FROM titulo_receber WHERE status = 'ABERTO' AND vencimento <= ?
                UNION ALL
                SELECT 'P', vencimento, -(valor - valor_pago)
                  FROM titulo_pagar WHERE status = 'ABERTO' AND vencimento <= ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 1; i <= 3; i++) {
                ps.setObject(i, periodo.fim());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LocalDate data = rs.getObject("data", LocalDate.class);
                    BigDecimal valor = rs.getBigDecimal("valor");
                    if (valor.signum() == 0) {
                        continue;
                    }
                    if (data.isBefore(hoje)) {
                        somar(linhas, primeiro, Parte.PREVISTO, EM_ATRASO, valor);
                    } else {
                        somar(linhas, agrupamento.inicioDoGrupo(data, periodo.inicio()), Parte.PREVISTO,
                                switch (rs.getString("origem")) {
                                    case "R" -> RECEBIVEIS;
                                    case "C" -> CREDIARIO;
                                    default -> CONTAS_A_PAGAR;
                                }, valor);
                    }
                }
            }
        }
    }

    private static void somar(Map<String, Lancamento> linhas, LocalDate grupo, Parte parte, String descricao,
                              BigDecimal valor) {
        BigDecimal entrada = valor.signum() > 0 ? valor : Dinheiro.ZERO;
        BigDecimal saida = valor.signum() < 0 ? valor.negate() : Dinheiro.ZERO;
        linhas.merge(grupo + "|" + parte + "|" + descricao, new Lancamento(grupo, parte, descricao, entrada, saida),
                (a, b) -> new Lancamento(grupo, parte, descricao, a.entradas().add(b.entradas()),
                        a.saidas().add(b.saidas())));
    }

    /** Todos os grupos do período, com o saldo acumulado no fim de cada um. */
    static List<Grupo> grupos(Periodo periodo, Agrupamento agrupamento, BigDecimal saldoInicial,
                              List<Lancamento> lancamentos) {
        List<Grupo> grupos = new ArrayList<>();
        BigDecimal saldo = saldoInicial;
        LocalDate dia = periodo.inicio();
        while (!dia.isAfter(periodo.fim())) {
            LocalDate inicio = agrupamento.inicioDoGrupo(dia, periodo.inicio());
            BigDecimal[] somas = {Dinheiro.ZERO, Dinheiro.ZERO, Dinheiro.ZERO, Dinheiro.ZERO};
            for (Lancamento l : lancamentos) {
                if (l.grupo().equals(inicio)) {
                    int i = l.parte() == Parte.REALIZADO ? 0 : 2;
                    somas[i] = somas[i].add(l.entradas());
                    somas[i + 1] = somas[i + 1].add(l.saidas());
                }
            }
            saldo = saldo.add(somas[0]).subtract(somas[1]).add(somas[2]).subtract(somas[3]);
            grupos.add(new Grupo(inicio, agrupamento.rotuloDoGrupo(inicio), somas[0], somas[1], somas[2], somas[3],
                    saldo));
            dia = proximo(agrupamento, inicio);
        }
        return grupos;
    }

    private static LocalDate proximo(Agrupamento agrupamento, LocalDate inicio) {
        return switch (agrupamento) {
            case DIA -> inicio.plusDays(1);
            case SEMANA -> agrupamento.inicioDoGrupo(inicio, LocalDate.MIN).plusWeeks(1);
            case MES -> inicio.withDayOfMonth(1).plusMonths(1);
        };
    }

    private static BigDecimal valor(Connection con, String sql, OffsetDateTime parametro) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            if (parametro != null) {
                ps.setObject(1, parametro);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }
}
