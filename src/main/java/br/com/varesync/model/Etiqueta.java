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
 * @param pendente         preço ou promoção diferente da última etiqueta, ou nunca teve (com estoque > 0)
 * @param quantidade       quantas etiquetas imprimir (padrão 1)
 */
public record Etiqueta(long produtoId, String nome, String unidade, BigDecimal preco, BigDecimal precoDe,
                       Long promocaoId, LocalDate fimPromocao, String codigoBarras, Integer plu,
                       ConteudoLiquido conteudo, LocalDate data, OffsetDateTime ultimaImpressao, boolean pendente,
                       int quantidade) {

    public Etiqueta {
        conteudo = conteudo == null ? ConteudoLiquido.SEM : conteudo;
    }

    public boolean promocional() {
        return promocaoId != null;
    }

    public Etiqueta comQuantidade(int outra) {
        return new Etiqueta(produtoId, nome, unidade, preco, precoDe, promocaoId, fimPromocao, codigoBarras, plu,
                conteudo, data, ultimaImpressao, pendente, outra);
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
