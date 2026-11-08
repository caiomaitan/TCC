package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Um pesável na carga de balança (F18.2): o que vai agora para a balança e a situação em relação à última carga.
 *
 * @param preco          preço por kg vigente na data (com promoção)
 * @param emPromocao     o preço é de uma promoção vigente
 * @param validadeDias   dias de validade na balança; nulo = sem
 * @param descricao      descrição como vai no arquivo (as duas linhas juntas por um espaço)
 * @param ultimaCargaEm  quando o produto foi para a balança pela última vez; nulo = nunca
 */
public record ItemCargaBalanca(long produtoId, String nome, int plu, BigDecimal preco, boolean emPromocao,
                               Integer validadeDias, String descricao, OffsetDateTime ultimaCargaEm,
                               Situacao situacao) {

    /** Por que o item está pendente (a primeira diferença encontrada), ou {@link #EM_DIA}. */
    public enum Situacao {
        NUNCA_CARREGADO("Nunca carregado"),
        PLU_ALTERADO("PLU alterado"),
        PRECO_ALTERADO("Preço alterado"),
        DESCRICAO_ALTERADA("Nome alterado"),
        VALIDADE_ALTERADA("Validade alterada"),
        EM_DIA("Em dia");

        private final String rotulo;

        Situacao(String rotulo) {
            this.rotulo = rotulo;
        }

        public String rotulo() {
            return rotulo;
        }
    }

    public boolean pendente() {
        return situacao != Situacao.EM_DIA;
    }
}
