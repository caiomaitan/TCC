package br.com.varesync.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * O que a tela de produto envia para gravar. {@code ProdutoService} normaliza e valida antes de usar.
 *
 * @param categoriaId       nulo = sem categoria
 * @param plu               nulo = não é pesável
 * @param estoqueMinimo     nulo = 0
 * @param estoqueInicial    só no cadastro novo; nulo ou zero = sem movimento
 * @param controlaValidade  F17: liga ou desliga o controle de validade e lotes; nulo = não muda (no cadastro novo,
 *                          desligado)
 * @param loteInicial       F17: lote do estoque inicial (código e validade), só no cadastro novo com o controle
 *                          ligado; nulo = "SEM LOTE". A quantidade dele é ignorada (vale o estoque inicial)
 */
public record DadosProduto(String nome, Long categoriaId, String unidade, BigDecimal precoVenda, BigDecimal custo,
                           BigDecimal estoqueMinimo, Integer plu, List<CodigoBarras> codigosBarras,
                           BigDecimal estoqueInicial, Boolean controlaValidade, ParteLote loteInicial) {

    public DadosProduto {
        codigosBarras = codigosBarras == null ? List.of() : List.copyOf(codigosBarras);
    }

    /** Sem mexer no controle de validade. */
    public DadosProduto(String nome, Long categoriaId, String unidade, BigDecimal precoVenda, BigDecimal custo,
                        BigDecimal estoqueMinimo, Integer plu, List<CodigoBarras> codigosBarras,
                        BigDecimal estoqueInicial) {
        this(nome, categoriaId, unidade, precoVenda, custo, estoqueMinimo, plu, codigosBarras, estoqueInicial, null,
                null);
    }
}
