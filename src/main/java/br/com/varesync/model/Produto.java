package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Produto como está gravado, com o saldo atual (somente leitura) e quem fez a última alteração.
 * O código exibido é o {@code id}.
 */
public final class Produto {

    private final long id;
    private final String nome;
    private final Long categoriaId;
    private final String categoriaNome;
    private final String unidade;
    private final boolean unidadeFracionada;
    private final BigDecimal precoVenda;
    private final BigDecimal custo;
    private final BigDecimal estoqueMinimo;
    private final Integer plu;
    private final boolean ativo;
    private final BigDecimal estoqueAtual;
    private final OffsetDateTime atualizadoEm;
    private final String atualizadoPorNome;
    private final List<CodigoBarras> codigosBarras;

    public Produto(long id, String nome, Long categoriaId, String categoriaNome, String unidade,
                   boolean unidadeFracionada, BigDecimal precoVenda, BigDecimal custo, BigDecimal estoqueMinimo,
                   Integer plu, boolean ativo, BigDecimal estoqueAtual, OffsetDateTime atualizadoEm,
                   String atualizadoPorNome, List<CodigoBarras> codigosBarras) {
        this.id = id;
        this.nome = nome;
        this.categoriaId = categoriaId;
        this.categoriaNome = categoriaNome;
        this.unidade = unidade;
        this.unidadeFracionada = unidadeFracionada;
        this.precoVenda = precoVenda;
        this.custo = custo;
        this.estoqueMinimo = estoqueMinimo;
        this.plu = plu;
        this.ativo = ativo;
        this.estoqueAtual = estoqueAtual;
        this.atualizadoEm = atualizadoEm;
        this.atualizadoPorNome = atualizadoPorNome;
        this.codigosBarras = List.copyOf(codigosBarras);
    }

    public long id() {
        return id;
    }

    public String nome() {
        return nome;
    }

    /** Nulo quando o produto não tem categoria. */
    public Long categoriaId() {
        return categoriaId;
    }

    public String categoriaNome() {
        return categoriaNome;
    }

    public String unidade() {
        return unidade;
    }

    public boolean unidadeFracionada() {
        return unidadeFracionada;
    }

    public BigDecimal precoVenda() {
        return precoVenda;
    }

    /** Custo médio (4 casas). */
    public BigDecimal custo() {
        return custo;
    }

    public BigDecimal estoqueMinimo() {
        return estoqueMinimo;
    }

    /** Código na balança; nulo se não é pesável. */
    public Integer plu() {
        return plu;
    }

    public boolean ativo() {
        return ativo;
    }

    public BigDecimal estoqueAtual() {
        return estoqueAtual;
    }

    public OffsetDateTime atualizadoEm() {
        return atualizadoEm;
    }

    public String atualizadoPorNome() {
        return atualizadoPorNome;
    }

    /** Principal primeiro. */
    public List<CodigoBarras> codigosBarras() {
        return codigosBarras;
    }

    public Optional<String> codigoPrincipal() {
        return codigosBarras.stream().filter(CodigoBarras::principal).map(CodigoBarras::codigo).findFirst();
    }

    /** "Café 500 g (código 12)". */
    public String descricao() {
        return nome + " (código " + id + ")";
    }

    @Override
    public String toString() {
        return "Produto[id=" + id + ", nome=" + nome + "]";
    }
}
