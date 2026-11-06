package br.com.varesync.model;

import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Conteúdo líquido da embalagem (F18.1, opcional): quantidade e unidade, para o preço por kg ou litro da etiqueta.
 * {@link #SEM} (os dois nulos) é o produto sem conteúdo líquido.
 */
public record ConteudoLiquido(BigDecimal quantidade, UnidadeConteudo unidade) {

    /** Sem conteúdo líquido informado. */
    public static final ConteudoLiquido SEM = new ConteudoLiquido(null, null);

    public boolean vazio() {
        return quantidade == null && unidade == null;
    }

    /** Mesma quantidade (sem olhar a escala) e mesma unidade. */
    public boolean igual(ConteudoLiquido outro) {
        if (vazio() || outro.vazio()) {
            return vazio() == outro.vazio();
        }
        return unidade == outro.unidade && quantidade.compareTo(outro.quantidade) == 0;
    }

    /**
     * Preço por kg ou litro = preço ÷ (quantidade na medida), 2 casas, HALF_UP. Ex.: 500 g a R$ 8,99 → R$ 17,98 o kg.
     *
     * @throws IllegalStateException se não houver conteúdo
     */
    public BigDecimal precoPorMedida(BigDecimal preco) {
        if (vazio() || quantidade.signum() <= 0) {
            throw new IllegalStateException("Produto sem conteúdo líquido");
        }
        BigDecimal naMedida = quantidade.divide(unidade.porMedida(), 10, RoundingMode.HALF_UP);
        return preco.divide(naMedida, Dinheiro.ESCALA_DINHEIRO, Dinheiro.ARREDONDAMENTO);
    }

    /** "500 g", "1,5 L"; vazio sem conteúdo. */
    public String descricao() {
        if (vazio()) {
            return "";
        }
        BigDecimal sem = quantidade.stripTrailingZeros();
        int casas = Math.max(0, sem.scale());
        return Formatos.numero(quantidade, casas) + " " + unidade.sigla();
    }
}
