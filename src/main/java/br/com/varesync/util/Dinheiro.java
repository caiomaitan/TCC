package br.com.varesync.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Regras numéricas do sistema: escalas, arredondamento e leitura de valores digitados.
 * Dinheiro 2 casas, custo unitário 4, quantidade 3, percentual 2; sempre HALF_UP.
 */
public final class Dinheiro {

    public static final int ESCALA_DINHEIRO = 2;
    public static final int ESCALA_CUSTO = 4;
    public static final int ESCALA_QUANTIDADE = 3;
    public static final int ESCALA_PERCENTUAL = 2;
    public static final RoundingMode ARREDONDAMENTO = RoundingMode.HALF_UP;

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(ESCALA_DINHEIRO);
    private static final BigDecimal CEM = BigDecimal.valueOf(100);

    private static final Pattern INTEIRO = Pattern.compile("\\d+");
    private static final Pattern DECIMAL_VIRGULA = Pattern.compile("\\d+,\\d+");
    private static final Pattern DECIMAL_PONTO = Pattern.compile("\\d+\\.\\d+");
    private static final Pattern MILHAR_COM_VIRGULA = Pattern.compile("\\d{1,3}(\\.\\d{3})+,\\d+");
    private static final Pattern MILHAR_SEM_DECIMAIS = Pattern.compile("\\d{1,3}(\\.\\d{3}){2,}");

    private Dinheiro() {
    }

    public static BigDecimal dinheiro(BigDecimal valor) {
        return valor.setScale(ESCALA_DINHEIRO, ARREDONDAMENTO);
    }

    public static BigDecimal custo(BigDecimal valor) {
        return valor.setScale(ESCALA_CUSTO, ARREDONDAMENTO);
    }

    public static BigDecimal quantidade(BigDecimal valor) {
        return valor.setScale(ESCALA_QUANTIDADE, ARREDONDAMENTO);
    }

    public static BigDecimal percentual(BigDecimal valor) {
        return valor.setScale(ESCALA_PERCENTUAL, ARREDONDAMENTO);
    }

    /** Valor em dinheiro de quantidade × preço (ex.: 0,345 × 39,90 = 13,7655 → 13,77). */
    public static BigDecimal multiplicar(BigDecimal quantidade, BigDecimal valorUnitario) {
        return dinheiro(quantidade.multiply(valorUnitario));
    }

    /** Divisão com escala e HALF_UP; vazio quando o divisor é zero (a tela exibe "—"). */
    public static Optional<BigDecimal> dividir(BigDecimal dividendo, BigDecimal divisor, int escala) {
        if (divisor.signum() == 0) {
            return Optional.empty();
        }
        return Optional.of(dividendo.divide(divisor, escala, ARREDONDAMENTO));
    }

    /** parte ÷ total × 100, com 2 casas; vazio quando o total é zero. */
    public static Optional<BigDecimal> percentualDe(BigDecimal parte, BigDecimal total) {
        return dividir(parte.multiply(CEM), total, ESCALA_PERCENTUAL);
    }

    public static boolean ehZero(BigDecimal valor) {
        return valor.signum() == 0;
    }

    public static boolean ehNegativo(BigDecimal valor) {
        return valor.signum() < 0;
    }

    /**
     * Lê um número digitado pelo usuário, sem arredondar.
     * Aceita {@code 1.234,56}, {@code 1234,56}, {@code 1234.56}, {@code 1.234.567}, prefixo "R$" e sinal negativo.
     * Um único ponto sem vírgula é separador decimal ({@code 1.5} = 1,5).
     *
     * @throws IllegalArgumentException se o texto não for um número válido
     */
    public static BigDecimal ler(String texto) {
        if (texto == null) {
            throw new IllegalArgumentException("Informe um valor.");
        }
        String limpo = texto.replace("R$", "").replace(' ', ' ').replace(" ", "").trim();
        boolean negativo = limpo.startsWith("-");
        if (negativo) {
            limpo = limpo.substring(1);
        }
        String normalizado;
        if (INTEIRO.matcher(limpo).matches() || DECIMAL_PONTO.matcher(limpo).matches()) {
            normalizado = limpo;
        } else if (DECIMAL_VIRGULA.matcher(limpo).matches()) {
            normalizado = limpo.replace(',', '.');
        } else if (MILHAR_COM_VIRGULA.matcher(limpo).matches()) {
            normalizado = limpo.replace(".", "").replace(',', '.');
        } else if (MILHAR_SEM_DECIMAIS.matcher(limpo).matches()) {
            normalizado = limpo.replace(".", "");
        } else {
            throw new IllegalArgumentException("Valor inválido: \"" + texto.trim() + "\".");
        }
        BigDecimal valor = new BigDecimal(normalizado);
        return negativo ? valor.negate() : valor;
    }
}
