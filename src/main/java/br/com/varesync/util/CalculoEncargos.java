package br.com.varesync.util;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Contas do recebimento do crediário (F14.3): multa e juros simples do título em atraso, divisão do pagamento parcial e
 * aplicação de um valor nos títulos, do vencimento mais antigo para o mais novo.
 */
public final class CalculoEncargos {

    private static final BigDecimal CEM = BigDecimal.valueOf(100);
    private static final BigDecimal DIAS_MES = BigDecimal.valueOf(30);

    /**
     * Encargos de um título na data do recebimento.
     *
     * @param diasAtraso data do recebimento − vencimento; zero ou negativo = não venceu
     */
    public record Encargos(long diasAtraso, BigDecimal emAberto, BigDecimal multa, BigDecimal juros) {

        public BigDecimal encargos() {
            return multa.add(juros);
        }

        /** Em aberto + multa + juros. */
        public BigDecimal paraQuitar() {
            return emAberto.add(encargos());
        }
    }

    /** O que um valor pago num título quita: principal e encargos. */
    public record Divisao(BigDecimal principal, BigDecimal multa, BigDecimal juros) {
    }

    /** Parte de um recebimento num título ({@code indice} na lista recebida). */
    public record Aplicacao(int indice, BigDecimal principal, BigDecimal multa, BigDecimal juros, BigDecimal desconto) {

        /** Quanto o cliente paga neste título. */
        public BigDecimal total() {
            return principal.add(multa).add(juros).subtract(desconto);
        }
    }

    private CalculoEncargos() {
    }

    /**
     * Até {@code carenciaDias} dias de atraso, sem encargos. Acima disso: multa = round(em aberto × multa% ÷ 100, 2) e
     * juros simples = round(em aberto × juros% ÷ 100 ÷ 30 × dias, 2).
     */
    public static Encargos calcular(BigDecimal emAberto, LocalDate vencimento, LocalDate data, BigDecimal multaPct,
                                    BigDecimal jurosMesPct, int carenciaDias) {
        long dias = ChronoUnit.DAYS.between(vencimento, data);
        if (dias <= carenciaDias) {
            return new Encargos(dias, emAberto, Dinheiro.ZERO, Dinheiro.ZERO);
        }
        BigDecimal multa = emAberto.multiply(multaPct).divide(CEM, Dinheiro.ESCALA_DINHEIRO, Dinheiro.ARREDONDAMENTO);
        // Multiplica antes de dividir: a dízima do ÷ 30 não entra no arredondamento.
        BigDecimal juros = emAberto.multiply(jurosMesPct).multiply(BigDecimal.valueOf(dias))
                .divide(CEM.multiply(DIAS_MES), Dinheiro.ESCALA_DINHEIRO, Dinheiro.ARREDONDAMENTO);
        return new Encargos(dias, emAberto, multa, juros);
    }

    /**
     * Pagamento de um título: os encargos só incidem sobre a parte paga. Principal = round(pago × em aberto ÷ valor para
     * quitar, 2); o resto são encargos; multa = round(encargos × multa ÷ (multa + juros), 2) e juros = encargos − multa.
     *
     * @param pago de um centavo até o valor para quitar
     */
    public static Divisao dividir(BigDecimal pago, Encargos e) {
        if (pago.compareTo(e.paraQuitar()) == 0) {
            return new Divisao(e.emAberto(), e.multa(), e.juros());
        }
        BigDecimal principal = pago.multiply(e.emAberto()).divide(e.paraQuitar(), Dinheiro.ESCALA_DINHEIRO,
                Dinheiro.ARREDONDAMENTO);
        BigDecimal encargos = pago.subtract(principal);
        if (encargos.signum() == 0) {
            return new Divisao(principal, Dinheiro.ZERO, Dinheiro.ZERO);
        }
        BigDecimal multa = encargos.multiply(e.multa()).divide(e.encargos(), Dinheiro.ESCALA_DINHEIRO,
                Dinheiro.ARREDONDAMENTO);
        return new Divisao(principal, multa, encargos.subtract(multa));
    }

    /**
     * Aplica o valor nos títulos, na ordem recebida (do vencimento mais antigo para o mais novo); o título que não é
     * quitado recebe a divisão de {@link #dividir}.
     *
     * @param valor até a soma dos valores para quitar
     * @return só os títulos que receberam algo
     */
    public static List<Aplicacao> aplicarValor(BigDecimal valor, List<Encargos> titulos) {
        List<Aplicacao> aplicacoes = new ArrayList<>();
        BigDecimal resta = valor;
        for (int i = 0; i < titulos.size() && resta.signum() > 0; i++) {
            Encargos e = titulos.get(i);
            BigDecimal pago = resta.min(e.paraQuitar());
            Divisao d = dividir(pago, e);
            aplicacoes.add(new Aplicacao(i, d.principal(), d.multa(), d.juros(), Dinheiro.ZERO));
            resta = resta.subtract(pago);
        }
        return Collections.unmodifiableList(aplicacoes);
    }

    /**
     * Quita todos os títulos, com o desconto nos encargos distribuído na ordem recebida, até os encargos de cada um.
     *
     * @param desconto até a soma dos encargos
     */
    public static List<Aplicacao> quitar(List<Encargos> titulos, BigDecimal desconto) {
        List<Aplicacao> aplicacoes = new ArrayList<>();
        BigDecimal resta = desconto;
        for (int i = 0; i < titulos.size(); i++) {
            Encargos e = titulos.get(i);
            BigDecimal parte = resta.min(e.encargos());
            aplicacoes.add(new Aplicacao(i, e.emAberto(), e.multa(), e.juros(), parte));
            resta = resta.subtract(parte);
        }
        return Collections.unmodifiableList(aplicacoes);
    }

    /** Dias de atraso que aparecem na tela: zero para o título que ainda não venceu. */
    public static long diasDeAtraso(LocalDate vencimento, LocalDate data) {
        return Math.max(0, ChronoUnit.DAYS.between(vencimento, data));
    }
}
