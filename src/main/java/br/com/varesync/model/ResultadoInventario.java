package br.com.varesync.model;

import br.com.varesync.util.Dinheiro;
import java.math.BigDecimal;
import java.util.List;

/**
 * Resultado de inventário (R13, 7.3) e aba Divergências (6.7): itens com sistema, contado, diferença e valor, e os
 * totais de sobra e falta em R$. Os totais somam o valor já arredondado de cada linha, para baterem com a tabela.
 */
public record ResultadoInventario(InventarioResumo inventario, List<ItemDivergencia> itens) {

    public ResultadoInventario {
        itens = List.copyOf(itens);
    }

    /** Soma do valor das diferenças positivas. */
    public BigDecimal valorSobra() {
        return somar(1);
    }

    /** Soma do valor das diferenças negativas, como número positivo. */
    public BigDecimal valorFalta() {
        return somar(-1).negate();
    }

    /** Sobra − falta: positivo, o estoque ficou maior; negativo, menor. */
    public BigDecimal valorLiquido() {
        return valorSobra().subtract(valorFalta());
    }

    public long itensComSobra() {
        return itens.stream().filter(i -> i.contado() && i.diferenca().signum() > 0).count();
    }

    public long itensComFalta() {
        return itens.stream().filter(i -> i.contado() && i.diferenca().signum() < 0).count();
    }

    /** Contados com diferença: geram um movimento cada ao aplicar. */
    public long itensComDiferenca() {
        return itens.stream().filter(ItemDivergencia::temDiferenca).count();
    }

    public long itensContados() {
        return itens.stream().filter(ItemDivergencia::contado).count();
    }

    /** Não contados: ignorados ao aplicar. */
    public long itensNaoContados() {
        return itens.size() - itensContados();
    }

    private BigDecimal somar(int sinal) {
        return itens.stream()
                .filter(i -> i.contado() && i.diferenca().signum() == sinal)
                .map(ItemDivergencia::valorDiferenca)
                .reduce(Dinheiro.ZERO, BigDecimal::add);
    }
}
