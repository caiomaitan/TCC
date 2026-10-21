package br.com.varesync.service;

import br.com.varesync.dao.PromocaoDAO;
import br.com.varesync.model.ProdutoVenda;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Ponto único de decisão do preço do item no PDV (6.6, item 5): a promoção ativa cujo período contém a data da venda
 * (6.8) ou, sem ela, o preço de venda. O item guarda a promoção que originou o preço ({@code venda_item.promocao_id}).
 */
public class PrecoService {

    private final PromocaoDAO promocaoDAO;

    public PrecoService(PromocaoDAO promocaoDAO) {
        this.promocaoDAO = promocaoDAO;
    }

    /** Preço do item e a promoção que o originou (nula quando é o preço de venda). */
    public record Preco(BigDecimal valor, Long promocaoId) {

        public boolean promocional() {
            return promocaoId != null;
        }
    }

    /**
     * @param con  transação de quem chama
     * @param data data da venda, para a vigência da promoção
     */
    public Preco precoVigente(Connection con, ProdutoVenda produto, LocalDate data) throws SQLException {
        Optional<PromocaoDAO.Vigente> promocao = promocaoDAO.vigente(con, produto.id(), data);
        return promocao.map(p -> new Preco(p.preco(), p.id())).orElseGet(() -> new Preco(produto.precoVenda(), null));
    }
}
