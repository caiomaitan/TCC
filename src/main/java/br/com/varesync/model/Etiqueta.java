package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Uma etiqueta de gôndola de um produto (F18.1), com o preço vigente no dia da impressão.
 *
 * @param preco            preço que vai em destaque: o promocional vigente ou o de venda
 * @param precoDe          preço de venda quando há promoção ("DE R$ X POR R$ Y"); nulo sem promoção
 * @param promocaoId       promoção vigente; nula sem promoção
 * @param fimPromocao      último dia da promoção ("Oferta válida até"); nulo sem promoção
 * @param codigoBarras     código principal, se for EAN-13, EAN-8 ou UPC-A válido e o produto não for pesável
 * @param plu              PLU do pesável (sem código de barras); nulo nos demais
 * @param conteudo         conteúdo líquido ({@link ConteudoLiquido#SEM} se não informado)
 * @param data             data da impressão
 * @param ultimaImpressao  quando foi a última etiqueta; nulo se nunca teve
 * @param pendente         preço, promoção ou faixa de atacado diferente da última etiqueta, ou nunca teve (com
 *                         estoque > 0)
 * @param quantidade       quantas etiquetas imprimir (padrão 1)
 * @param atacadoQuantidade F20.3: a partir de quantas unidades vale a faixa de atacado da etiqueta (a de menor
 *                         quantidade, só quando o preço dela é menor que o da etiqueta); nula sem faixa
 * @param atacadoPreco     F20.3: o preço dessa faixa; nulo sem faixa
 */
public record Etiqueta(long produtoId, String nome, String unidade, BigDecimal preco, BigDecimal precoDe,
                       Long promocaoId, LocalDate fimPromocao, String codigoBarras, Integer plu,
                       ConteudoLiquido conteudo, LocalDate data, OffsetDateTime ultimaImpressao, boolean pendente,
                       int quantidade, BigDecimal atacadoQuantidade, BigDecimal atacadoPreco) {

    public Etiqueta {
        conteudo = conteudo == null ? ConteudoLiquido.SEM : conteudo;
        if ((atacadoQuantidade == null) != (atacadoPreco == null)) {
            throw new IllegalArgumentException("Faixa de atacado pela metade");
        }
    }

    /** Sem faixa de atacado. */
    public Etiqueta(long produtoId, String nome, String unidade, BigDecimal preco, BigDecimal precoDe,
                    Long promocaoId, LocalDate fimPromocao, String codigoBarras, Integer plu,
                    ConteudoLiquido conteudo, LocalDate data, OffsetDateTime ultimaImpressao, boolean pendente,
                    int quantidade) {
        this(produtoId, nome, unidade, preco, precoDe, promocaoId, fimPromocao, codigoBarras, plu, conteudo, data,
                ultimaImpressao, pendente, quantidade, null, null);
    }

    public boolean promocional() {
        return promocaoId != null;
    }

    public boolean temAtacado() {
        return atacadoQuantidade != null;
    }

    /** "6" na unidade inteira; "2,5" na fracionada (sem os zeros do fim). */
    public String quantidadeAtacado() {
        return atacadoQuantidade == null ? ""
                : atacadoQuantidade.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    /** "A partir de 6 un: R$ 4,99" (F20.3); vazio sem faixa. */
    public String textoAtacado() {
        return atacadoQuantidade == null ? "" : "A partir de " + quantidadeAtacado() + " " + unidade.toLowerCase()
                + ": " + br.com.varesync.util.Formatos.moeda(atacadoPreco);
    }

    public Etiqueta comQuantidade(int outra) {
        return new Etiqueta(produtoId, nome, unidade, preco, precoDe, promocaoId, fimPromocao, codigoBarras, plu,
                conteudo, data, ultimaImpressao, pendente, outra, atacadoQuantidade, atacadoPreco);
    }

    /** Vendido por kg ou litro: o próprio preço já é por medida. */
    public boolean vendidoPorMedida() {
        return "KG".equalsIgnoreCase(unidade) || "L".equalsIgnoreCase(unidade);
    }

    /** Preço por kg ou litro, quando o produto tem conteúdo líquido e não é vendido por kg ou litro; senão nulo. */
    public BigDecimal precoPorMedida() {
        if (conteudo.vazio() || vendidoPorMedida()) {
            return null;
        }
        return conteudo.precoPorMedida(preco);
    }
}
