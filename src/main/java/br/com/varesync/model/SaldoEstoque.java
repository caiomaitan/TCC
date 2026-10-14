package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Saldo de um produto (linha de {@code vw_estoque_atual}): cabeçalho da consulta de estoque (6.4).
 * O valor em estoque é saldo × custo médio, com 2 casas.
 */
public record SaldoEstoque(long produtoId, String nome, String categoriaNome, String unidade, boolean unidadeFracionada,
                           boolean ativo, BigDecimal saldo, BigDecimal estoqueMinimo, BigDecimal custo,
                           BigDecimal valorEmEstoque, boolean abaixoDoMinimo, OffsetDateTime ultimaMovimentacao) {

    /** "Arroz 5 kg (código 3)". */
    public String descricao() {
        return nome + " (código " + produtoId + ")";
    }
}
