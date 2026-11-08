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
 * @param conteudo          F18.1: conteúdo líquido; nulo = não muda (no cadastro novo, sem);
 *                          {@link ConteudoLiquido#SEM} = sem conteúdo líquido
 * @param validadeBalancaDias F18.2: dias de validade impressos pela balança, só no pesável com PLU; nulo = não muda
 *                          (no cadastro novo, sem); 0 = sem validade
 */
public record DadosProduto(String nome, Long categoriaId, String unidade, BigDecimal precoVenda, BigDecimal custo,
                           BigDecimal estoqueMinimo, Integer plu, List<CodigoBarras> codigosBarras,
                           BigDecimal estoqueInicial, Boolean controlaValidade, ParteLote loteInicial,
                           ConteudoLiquido conteudo, Integer validadeBalancaDias) {

    public DadosProduto {
        codigosBarras = codigosBarras == null ? List.of() : List.copyOf(codigosBarras);
    }

    /** Sem mexer na validade da balança. */
    public DadosProduto(String nome, Long categoriaId, String unidade, BigDecimal precoVenda, BigDecimal custo,
                        BigDecimal estoqueMinimo, Integer plu, List<CodigoBarras> codigosBarras,
                        BigDecimal estoqueInicial, Boolean controlaValidade, ParteLote loteInicial,
                        ConteudoLiquido conteudo) {
        this(nome, categoriaId, unidade, precoVenda, custo, estoqueMinimo, plu, codigosBarras, estoqueInicial,
                controlaValidade, loteInicial, conteudo, null);
    }

    /** Sem mexer no conteúdo líquido nem na validade da balança. */
    public DadosProduto(String nome, Long categoriaId, String unidade, BigDecimal precoVenda, BigDecimal custo,
                        BigDecimal estoqueMinimo, Integer plu, List<CodigoBarras> codigosBarras,
                        BigDecimal estoqueInicial, Boolean controlaValidade, ParteLote loteInicial) {
        this(nome, categoriaId, unidade, precoVenda, custo, estoqueMinimo, plu, codigosBarras, estoqueInicial,
                controlaValidade, loteInicial, null, null);
    }

    /** Sem mexer no controle de validade, no conteúdo líquido nem na validade da balança. */
    public DadosProduto(String nome, Long categoriaId, String unidade, BigDecimal precoVenda, BigDecimal custo,
                        BigDecimal estoqueMinimo, Integer plu, List<CodigoBarras> codigosBarras,
                        BigDecimal estoqueInicial) {
        this(nome, categoriaId, unidade, precoVenda, custo, estoqueMinimo, plu, codigosBarras, estoqueInicial, null,
                null, null, null);
    }
}
