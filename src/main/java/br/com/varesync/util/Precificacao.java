package br.com.varesync.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Simulador de preço do cadastro de produto (6.3, A.6): lucro unitário, margem, markup e preço sugerido.
 * Margem = lucro ÷ preço × 100; markup = lucro ÷ custo × 100. Divisor zero → vazio (a tela mostra "—").
 */
public final class Precificacao {

    private static final BigDecimal CEM = BigDecimal.valueOf(100);

    private Precificacao() {
    }

    /** Preço − custo, sem arredondar (custo tem 4 casas). */
    public static BigDecimal lucroUnitario(BigDecimal custo, BigDecimal preco) {
        return preco.subtract(custo);
    }

    /** Lucro ÷ preço × 100, 2 casas. */
    public static Optional<BigDecimal> margem(BigDecimal custo, BigDecimal preco) {
        return Dinheiro.percentualDe(lucroUnitario(custo, preco), preco);
    }

    /** Lucro ÷ custo × 100, 2 casas. */
    public static Optional<BigDecimal> markup(BigDecimal custo, BigDecimal preco) {
        return Dinheiro.percentualDe(lucroUnitario(custo, preco), custo);
    }

    /**
     * Preço que dá a margem desejada: custo ÷ (1 − margem ÷ 100), arredondado <b>para cima</b> no centavo,
     * para a margem nunca ficar abaixo da pedida (custo 3,20 e 30% → 4,58; 4,57 daria 29,98%).
     *
     * @return vazio se a margem for 100% ou mais (não existe preço)
     */
    public static Optional<BigDecimal> precoSugerido(BigDecimal custo, BigDecimal margemDesejadaPct) {
        BigDecimal fator = CEM.subtract(margemDesejadaPct);
        if (fator.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(custo.multiply(CEM).divide(fator, Dinheiro.ESCALA_DINHEIRO, RoundingMode.CEILING));
    }
}
