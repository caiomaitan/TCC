package br.com.varesync.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Rateio do desconto da venda entre os itens (6.6, A.4), pelo método do maior resto em centavos: cada item recebe a
 * parte inteira de (centavos × base ÷ soma das bases); os centavos que sobram vão, um a um, para os itens de maior
 * parte fracionária (empate: maior base; depois a ordem do item). A soma é exatamente o valor rateado.
 */
public final class Rateio {

    private static final BigDecimal CEM = BigDecimal.valueOf(100);

    private Rateio() {
    }

    /**
     * @param valor valor a ratear (≥ 0, até 2 casas, no máximo a soma das bases)
     * @param bases base de cada item, na ordem dos itens (≥ 0, até 2 casas)
     * @return a parte de cada item, na mesma ordem, com 2 casas
     */
    public static List<BigDecimal> ratear(BigDecimal valor, List<BigDecimal> bases) {
        BigInteger centavos = emCentavos(valor);
        List<BigInteger> basesCentavos = bases.stream().map(Rateio::emCentavos).toList();
        BigInteger soma = basesCentavos.stream().reduce(BigInteger.ZERO, BigInteger::add);
        if (centavos.signum() < 0 || basesCentavos.stream().anyMatch(b -> b.signum() < 0)) {
            throw new IllegalArgumentException("Rateio com valor ou base negativa");
        }
        if (centavos.compareTo(soma) > 0) {
            throw new IllegalArgumentException("Valor a ratear (" + valor + ") maior que a soma das bases (" + soma + ")");
        }
        int n = bases.size();
        List<BigInteger> partes = new ArrayList<>(n);
        List<BigInteger> restos = new ArrayList<>(n);
        BigInteger distribuido = BigInteger.ZERO;
        for (BigInteger base : basesCentavos) {
            BigInteger[] divisao = soma.signum() == 0
                    ? new BigInteger[] {BigInteger.ZERO, BigInteger.ZERO}
                    : centavos.multiply(base).divideAndRemainder(soma);
            partes.add(divisao[0]);
            restos.add(divisao[1]); // as frações têm o mesmo denominador: comparar o resto basta
            distribuido = distribuido.add(divisao[0]);
        }
        List<Integer> ordem = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ordem.add(i);
        }
        ordem.sort(Comparator.<Integer, BigInteger>comparing(restos::get).reversed()
                .thenComparing(Comparator.<Integer, BigInteger>comparing(basesCentavos::get).reversed())
                .thenComparing(Comparator.naturalOrder()));
        int sobra = centavos.subtract(distribuido).intValueExact();
        for (int i = 0; i < sobra; i++) {
            int item = ordem.get(i);
            partes.set(item, partes.get(item).add(BigInteger.ONE));
        }
        return partes.stream().map(p -> new BigDecimal(p, Dinheiro.ESCALA_DINHEIRO)).toList();
    }

    private static BigInteger emCentavos(BigDecimal valor) {
        return valor.multiply(CEM).setScale(0, Dinheiro.ARREDONDAMENTO).toBigIntegerExact();
    }
}
