package br.com.varesync.report;

import br.com.varesync.model.ItemPedido;
import br.com.varesync.model.PedidoCompra;
import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.Set;

/**
 * PDF do pedido de compra para enviar ao fornecedor (F16.3), pela infraestrutura genérica: os itens com o código no
 * fornecedor, a quantidade na unidade de venda e também na unidade de compra (quantidade ÷ fator), o custo previsto e o
 * valor. O fornecedor, a previsão, a situação e a observação vão no cabeçalho, no lugar dos filtros. Não fica no
 * catálogo da tela Relatórios: as linhas vêm do {@code PedidoCompraService} (permissão COMPRAS).
 */
public final class RelPedidoCompra implements Relatorio<ItemPedido> {

    private final PedidoCompra pedido;
    private final String documentoFornecedor;

    /** @param documentoFornecedor CNPJ/CPF do fornecedor com máscara; vazio sem ele */
    public RelPedidoCompra(PedidoCompra pedido, String documentoFornecedor) {
        this.pedido = pedido;
        this.documentoFornecedor = documentoFornecedor;
    }

    /** O pedido pronto para exportar. */
    public static ResultadoRelatorio<ItemPedido> exportacao(PedidoCompra pedido, String documentoFornecedor,
                                                            List<ItemPedido> itens, Emissao emissao) {
        return ResultadoRelatorio.de(new RelPedidoCompra(pedido, documentoFornecedor), Filtros.vazios(), itens,
                emissao);
    }

    @Override
    public String codigo() {
        return "PC";
    }

    @Override
    public String nome() {
        return "Pedido de compra";
    }

    /** "Pedido de compra nº 12". */
    @Override
    public String titulo() {
        return "Pedido de compra nº " + pedido.id();
    }

    @Override
    public String descricao() {
        return "Itens do pedido para enviar ao fornecedor, com as quantidades também na unidade de compra.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return Set.of();
    }

    @Override
    public List<ItemPedido> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros) {
        throw new UnsupportedOperationException("As linhas vêm do PedidoCompraService");
    }

    /** "Fornecedor: X (CNPJ 11.222.333/0001-81) · Previsão de entrega: 10/11/2026 · Situação: Enviado em … · Obs.". */
    @Override
    public String descreverFiltros(Filtros filtros) {
        StringBuilder texto = new StringBuilder("Fornecedor: ").append(pedido.fornecedorNome());
        if (documentoFornecedor != null && !documentoFornecedor.isBlank()) {
            texto.append(" (CNPJ/CPF ").append(documentoFornecedor).append(')');
        }
        texto.append(" · Previsão de entrega: ").append(pedido.previsaoEntrega() == null ? "não informada"
                : Formatos.data(pedido.previsaoEntrega()));
        texto.append(" · Situação: ").append(pedido.status().rotulo());
        if (pedido.enviadoEm() != null) {
            texto.append(" em ").append(Formatos.dataHora(pedido.enviadoEm()));
        }
        if (pedido.observacao() != null) {
            texto.append(" · Observação: ").append(pedido.observacao());
        }
        return texto.toString();
    }

    /** "12 CX"; sem unidade de compra cadastrada, "12 un. de compra"; fracionada, com 3 casas. */
    public static String naUnidadeDeCompra(ItemPedido i) {
        BigDecimal q = i.quantidadeNaUnidadeDeCompra();
        return Formatos.quantidade(q, q.stripTrailingZeros().scale() > 0) + " "
                + (i.unidadeCompra() == null ? "un. de compra" : i.unidadeCompra());
    }

    @Override
    public List<Coluna<ItemPedido>> colunas(Filtros filtros) {
        return List.of(
                Coluna.<ItemPedido>codigo("Código", ItemPedido::produtoId).comLargura(0.8),
                Coluna.<ItemPedido>texto("Cód. no fornecedor",
                        i -> i.codigoNoFornecedor() == null ? "" : i.codigoNoFornecedor()).comLargura(1.4),
                Coluna.texto("Produto", ItemPedido::produtoNome),
                Coluna.quantidade("Quantidade", ItemPedido::quantidade, ItemPedido::fracionada),
                Coluna.texto("Un.", ItemPedido::unidade).comLargura(0.6),
                Coluna.<ItemPedido>texto("Na unidade de compra", RelPedidoCompra::naUnidadeDeCompra).comLargura(1.6),
                Coluna.<ItemPedido>de("Custo previsto", TipoColuna.CUSTO, ItemPedido::custoPrevisto),
                Coluna.moeda("Valor", ItemPedido::valor));
    }
}
