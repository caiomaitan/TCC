package br.com.varesync.service;

import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.TipoValorBalanca;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Gtin;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Etiqueta de balança (6.9, A.7): EAN-13 que começa com o prefixo e tem dígito verificador válido. PLU = os N dígitos
 * depois do prefixo; valor = os dígitos seguintes até o 12º, como inteiro (centavos em {@code PRECO}, gramas em
 * {@code PESO}).
 *
 * @param etiqueta o código lido (13 dígitos)
 */
public record EtiquetaBalanca(String etiqueta, int plu, long valor, TipoValorBalanca tipo) {

    private static final BigDecimal CEM = BigDecimal.valueOf(100);
    private static final BigDecimal MIL = BigDecimal.valueOf(1000);

    /** Quantidade e valor bruto do item lido pela etiqueta. */
    public record Item(BigDecimal quantidade, BigDecimal valorBruto) {
    }

    /** Reconhece a etiqueta; vazio se o código não é etiqueta (tamanho, prefixo ou dígito verificador). */
    public static Optional<EtiquetaBalanca> reconhecer(String codigo, Configuracoes.Balanca balanca) {
        if (codigo == null || codigo.length() != 13 || !codigo.chars().allMatch(Character::isDigit)
                || codigo.charAt(0) - '0' != balanca.prefixo() || !Gtin.valido(codigo)) {
            return Optional.empty();
        }
        int fimPlu = 1 + balanca.digitosCodigo();
        int plu = Integer.parseInt(codigo.substring(1, fimPlu));
        long valor = Long.parseLong(codigo.substring(fimPlu, 12));
        return Optional.of(new EtiquetaBalanca(codigo, plu, valor, balanca.tipoValor()));
    }

    /**
     * {@code PRECO}: o valor da etiqueta é o total do item, sem recálculo; quantidade = total ÷ preço, 3 casas,
     * HALF_UP. {@code PESO}: quantidade = gramas ÷ 1000; total = round(quantidade × preço, 2).
     *
     * @param preco preço por kg (ou pela unidade fracionada) vigente na venda
     * @throws NegocioException etiqueta com valor zero ou quantidade que arredonda para zero
     */
    public Item item(BigDecimal preco) {
        if (valor <= 0) {
            throw new NegocioException("A etiqueta de balança está com " + (tipo == TipoValorBalanca.PRECO
                    ? "valor" : "peso") + " zero. Pese o produto de novo.");
        }
        if (tipo == TipoValorBalanca.PESO) {
            BigDecimal quantidade = BigDecimal.valueOf(valor).divide(MIL, Dinheiro.ESCALA_QUANTIDADE,
                    RoundingMode.HALF_UP);
            return new Item(quantidade, Dinheiro.multiplicar(quantidade, preco));
        }
        if (preco.signum() <= 0) {
            throw new NegocioException("O produto está sem preço de venda e não pode ser vendido.");
        }
        BigDecimal total = BigDecimal.valueOf(valor).divide(CEM, Dinheiro.ESCALA_DINHEIRO, RoundingMode.UNNECESSARY);
        BigDecimal quantidade = total.divide(preco, Dinheiro.ESCALA_QUANTIDADE, Dinheiro.ARREDONDAMENTO);
        if (quantidade.signum() <= 0) {
            throw new NegocioException("O valor da etiqueta (" + br.com.varesync.util.Formatos.moeda(total)
                    + ") é pequeno demais para o preço do produto. Confira a etiqueta.");
        }
        return new Item(quantidade, total);
    }
}
