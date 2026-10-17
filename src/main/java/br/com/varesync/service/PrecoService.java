package br.com.varesync.service;

import br.com.varesync.model.ProdutoVenda;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;

/**
 * Ponto único de decisão do preço do item no PDV (6.6, item 5): promoção vigente na data da venda (6.8) ou o preço
 * de venda. As promoções entram na Fase 8, que só precisa preencher este método; até lá vale o preço de venda.
 */
public class PrecoService {

    /** Preço do item e a promoção que o originou (nula quando é o preço de venda). */
    public record Preco(BigDecimal valor, Long promocaoId) {
    }

    /**
     * @param con  transação de quem chama (a Fase 8 consulta {@code promocao} nela)
     * @param data data da venda, para a vigência da promoção
     */
    public Preco precoVigente(Connection con, ProdutoVenda produto, LocalDate data) {
        return new Preco(produto.precoVenda(), null);
    }
}
