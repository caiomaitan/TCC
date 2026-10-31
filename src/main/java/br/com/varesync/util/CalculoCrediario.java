package br.com.varesync.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Contas do crediário (F14.2): parcelas iguais com os centavos na primeira e abatimento da última para a primeira. */
public final class CalculoCrediario {

    /** Dias entre uma parcela e a seguinte. */
    public static final int INTERVALO_DIAS = 30;

    /** Parcela calculada: número (a partir de 1), vencimento e valor. */
    public record Parcela(int numero, LocalDate vencimento, BigDecimal valor) {
    }

    /** Quanto abater de uma parcela. */
    public record Abatimento(int indice, BigDecimal valor) {
    }

    private CalculoCrediario() {
    }

    /**
     * Parcelas iguais, com os centavos que sobram na primeira: R$ 100,00 em 3× = 33,34 + 33,33 + 33,33. A primeira
     * vence em {@code primeiroVencimento}; as demais, de 30 em 30 dias a partir dela.
     *
     * @throws IllegalArgumentException se o valor não der ao menos um centavo por parcela
     */
    public static List<Parcela> parcelar(BigDecimal valor, int parcelas, LocalDate primeiroVencimento) {
        BigDecimal igual = valor.divide(BigDecimal.valueOf(parcelas), Dinheiro.ESCALA_DINHEIRO, RoundingMode.DOWN);
        if (igual.signum() <= 0) {
            throw new IllegalArgumentException("Valor pequeno demais para " + parcelas + " parcelas: " + valor);
        }
        BigDecimal primeira = valor.subtract(igual.multiply(BigDecimal.valueOf(parcelas - 1L)));
        List<Parcela> lista = new ArrayList<>();
        for (int i = 0; i < parcelas; i++) {
            lista.add(new Parcela(i + 1, primeiroVencimento.plusDays((long) i * INTERVALO_DIAS),
                    i == 0 ? primeira : igual));
        }
        return lista;
    }

    /**
     * Distribui o valor pelo que está em aberto em cada parcela, da última para a primeira.
     *
     * @param emAberto em aberto por parcela, da primeira para a última
     * @return só as parcelas abatidas (índice em {@code emAberto} e valor), da última para a primeira; a soma é
     *         o menor entre o valor e o total em aberto
     */
    public static List<Abatimento> abater(BigDecimal valor, List<BigDecimal> emAberto) {
        List<Abatimento> abatimentos = new ArrayList<>();
        BigDecimal resta = valor;
        for (int i = emAberto.size() - 1; i >= 0 && resta.signum() > 0; i--) {
            BigDecimal parte = resta.min(emAberto.get(i));
            if (parte.signum() > 0) {
                abatimentos.add(new Abatimento(i, parte));
                resta = resta.subtract(parte);
            }
        }
        return Collections.unmodifiableList(abatimentos);
    }
}
