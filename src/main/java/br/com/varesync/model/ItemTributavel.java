package br.com.varesync.model;

import java.math.BigDecimal;

/**
 * Item vendido como entra no cálculo dos tributos (F22.2): a quantidade, o valor líquido e o cadastro fiscal do
 * produto lido na transação da venda.
 *
 * @param st   ST retido do produto por unidade de venda; nulo = sem o dado
 * @param ibpt linha vigente da tabela IBPT para o NCM; nula = sem a linha
 */
public record ItemTributavel(BigDecimal quantidade, BigDecimal valorLiquido, String ncm, String cest, int origem,
                             PerfilTributario perfil, StRetido st, Ibpt ibpt) {

    /** ST retido por unidade de venda (F22.4); cada campo nulo = o produto não tem o dado. */
    public record StRetido(BigDecimal baseUnitaria, BigDecimal aliquota, BigDecimal icmsSubstitutoUnitario,
                           BigDecimal icmsRetidoUnitario) {

        public static final StRetido NENHUM = new StRetido(null, null, null, null);
    }

    /** Alíquotas (%) da linha IBPT e a versão da tabela. */
    public record Ibpt(BigDecimal nacionalFederal, BigDecimal importadosFederal, BigDecimal estadual,
                       BigDecimal municipal, String versao) {
    }
}
