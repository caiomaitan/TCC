package br.com.varesync.service;

import br.com.varesync.dao.ProdutoDAO;
import br.com.varesync.dao.PromocaoDAO;
import br.com.varesync.model.FaixaPreco;
import br.com.varesync.model.ProdutoVenda;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Ponto único de decisão do preço do item no PDV (6.6, item 5): a promoção ativa cujo período contém a data da venda
 * (6.8) ou, sem ela, o preço de venda. O item guarda a promoção que originou o preço ({@code venda_item.promocao_id}).
 * As faixas de preço por quantidade (F20.2) também saem daqui; o carrinho aplica a faixa alcançada quando ela é menor
 * que esse preço.
 */
public class PrecoService {

    private final PromocaoDAO promocaoDAO;
    private final ProdutoDAO produtoDAO;

    public PrecoService(PromocaoDAO promocaoDAO, ProdutoDAO produtoDAO) {
        this.promocaoDAO = promocaoDAO;
        this.produtoDAO = produtoDAO;
    }

    /** Preço do item e a promoção que o originou (nula quando é o preço de venda). */
    public record Preco(BigDecimal valor, Long promocaoId) {

        public boolean promocional() {
            return promocaoId != null;
        }
    }

    /**
     * O menor entre o preço de venda e a promoção vigente (F20.2: "o menor entre o normal, a promoção vigente e a
     * faixa"; a faixa é do carrinho). Promoção igual ou acima do preço normal não vale.
     *
     * @param con  transação de quem chama
     * @param data data da venda, para a vigência da promoção
     */
    public Preco precoVigente(Connection con, ProdutoVenda produto, LocalDate data) throws SQLException {
        Optional<PromocaoDAO.Vigente> promocao = promocaoDAO.vigente(con, produto.id(), data)
                .filter(p -> p.preco().compareTo(produto.precoVenda()) < 0);
        return promocao.map(p -> new Preco(p.preco(), p.id())).orElseGet(() -> new Preco(produto.precoVenda(), null));
    }

    /** Faixas de preço por quantidade do produto (F20.2), da menor quantidade para a maior. */
    public List<FaixaPreco> faixas(Connection con, long produtoId) throws SQLException {
        return produtoDAO.faixasDePreco(con, produtoId);
    }
}
