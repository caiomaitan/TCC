package br.com.varesync.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * O que a tela de produto envia para gravar. {@code ProdutoService} normaliza e valida antes de usar.
 *
 * @param categoriaId    nulo = sem categoria
 * @param plu            nulo = não é pesável
 * @param estoqueMinimo  nulo = 0
 * @param estoqueInicial só no cadastro novo; nulo ou zero = sem movimento
 */
public record DadosProduto(String nome, Long categoriaId, String unidade, BigDecimal precoVenda, BigDecimal custo,
                           BigDecimal estoqueMinimo, Integer plu, List<CodigoBarras> codigosBarras,
                           BigDecimal estoqueInicial) {

    public DadosProduto {
        codigosBarras = codigosBarras == null ? List.of() : List.copyOf(codigosBarras);
    }
}
