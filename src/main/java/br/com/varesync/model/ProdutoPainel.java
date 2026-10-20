package br.com.varesync.model;

import br.com.varesync.util.Dinheiro;
import java.math.BigDecimal;
import java.util.Optional;

/** Produto do "top 5 do mês por lucro" do painel (7.5). */
public record ProdutoPainel(long produtoId, String nome, BigDecimal receita, BigDecimal lucro) {

    public Optional<BigDecimal> margem() {
        return Dinheiro.percentualDe(lucro, receita);
    }
}
