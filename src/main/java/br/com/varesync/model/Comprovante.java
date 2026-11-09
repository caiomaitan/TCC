package br.com.varesync.model;

import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Comprovante não fiscal (6.6): dados da loja, "NÃO É DOCUMENTO FISCAL", número da venda, data/hora, operador,
 * itens, descontos, total, pagamentos, troco e rodapé. {@link #linhas()} é a fonte única da tela e do PDF.
 *
 * @param pagamentos valor entregue por forma (no dinheiro, já com o troco somado)
 * @param cliente    cliente identificado (F12.2), ou nulo
 * @param credito    cashback gerado e saldo de crédito atual do cliente (F12.5), ou nulo
 * @param crediario  parcelas do crediário (F14.2); vazia sem crediário e na venda cancelada
 */
public record Comprovante(Loja loja, long vendaId, OffsetDateTime dataHora, String operador, String terminal,
                          StatusVenda status, OffsetDateTime canceladaEm, String motivoCancelamento,
                          List<Item> itens, BigDecimal valorBruto, BigDecimal descontoItens,
                          BigDecimal descontoVenda, BigDecimal total, List<Pagamento> pagamentos, BigDecimal troco,
                          String rodape, Cliente cliente, Credito credito, List<ParcelaCrediario> crediario)
        implements ComprovanteImpresso {

    public static final String NAO_FISCAL = "NÃO É DOCUMENTO FISCAL";
    /** Largura do comprovante em tela, como numa impressora de 80 mm. */
    public static final int COLUNAS = 48;

    public record Loja(String nome, String documento, String endereco, String telefone) {
    }

    /** Cliente no comprovante (F12.2): nome e CPF já mascarado ({@code ***.456.789-**}); documento vazio se faltar. */
    public record Cliente(String nome, String documento) {
    }

    /** Crédito do cliente no comprovante (F12.5): cashback gerado por esta venda e o saldo no momento da emissão. */
    public record Credito(BigDecimal cashback, BigDecimal saldo) {
    }

    /**
     * @param atacadoAPartirDe quantidade mínima da faixa de preço por quantidade que deu o preço (F20.2); nula sem
     *                         faixa
     */
    public record Item(int sequencia, String codigo, String descricao, BigDecimal quantidade, String unidade,
                       boolean fracionada, BigDecimal precoUnitario, BigDecimal valorBruto, BigDecimal desconto,
                       boolean cancelado, BigDecimal atacadoAPartirDe) {

        /** Item sem faixa de atacado. */
        public Item(int sequencia, String codigo, String descricao, BigDecimal quantidade, String unidade,
                    boolean fracionada, BigDecimal precoUnitario, BigDecimal valorBruto, BigDecimal desconto,
                    boolean cancelado) {
            this(sequencia, codigo, descricao, quantidade, unidade, fracionada, precoUnitario, valorBruto, desconto,
                    cancelado, null);
        }

        /** "Preço de atacado (a partir de 6 un)" (F20.2); vazio sem faixa. */
        public String textoAtacado() {
            return atacadoAPartirDe == null ? "" : "Preço de atacado (a partir de "
                    + Formatos.quantidade(atacadoAPartirDe, fracionada) + " " + unidade.toLowerCase() + ")";
        }
    }

    public record Pagamento(String forma, BigDecimal valor) {
    }

    /** Uma parcela do crediário: número, total de parcelas, vencimento e valor (no comprovante da devolução, o em aberto). */
    public record ParcelaCrediario(int parcela, int totalParcelas, LocalDate vencimento, BigDecimal valor) {

        LinhaComprovante linha() {
            return LinhaComprovante.normal("  " + parcela + "/" + totalParcelas + " vence " + Formatos.data(vencimento),
                    Formatos.moeda(valor));
        }
    }

    public Comprovante {
        itens = List.copyOf(itens);
        pagamentos = List.copyOf(pagamentos);
        crediario = List.copyOf(crediario);
    }

    /** Venda sem crediário. */
    public Comprovante(Loja loja, long vendaId, OffsetDateTime dataHora, String operador, String terminal,
                       StatusVenda status, OffsetDateTime canceladaEm, String motivoCancelamento, List<Item> itens,
                       BigDecimal valorBruto, BigDecimal descontoItens, BigDecimal descontoVenda, BigDecimal total,
                       List<Pagamento> pagamentos, BigDecimal troco, String rodape, Cliente cliente, Credito credito) {
        this(loja, vendaId, dataHora, operador, terminal, status, canceladaEm, motivoCancelamento, itens, valorBruto,
                descontoItens, descontoVenda, total, pagamentos, troco, rodape, cliente, credito, List.of());
    }

    public BigDecimal descontos() {
        return descontoItens.add(descontoVenda);
    }

    public boolean cancelada() {
        return status == StatusVenda.CANCELADA;
    }

    @Override
    public String titulo() {
        return "Comprovante da venda nº " + vendaId;
    }

    @Override
    public String nomeArquivo() {
        return "comprovante-venda-" + vendaId + ".pdf";
    }

    /** Linhas na ordem de impressão. */
    @Override
    public List<LinhaComprovante> linhas() {
        List<LinhaComprovante> linhas = new ArrayList<>();
        cabecalho(linhas, loja);
        linhas.add(LinhaComprovante.negrito("Venda nº " + vendaId, Formatos.dataHora(dataHora)));
        linhas.add(LinhaComprovante.normal("Operador: " + operador, "Terminal: " + terminal));
        if (cliente != null) {
            linhas.add(LinhaComprovante.texto("Cliente: " + cliente.nome()));
            if (!Textos.vazio(cliente.documento())) {
                linhas.add(LinhaComprovante.texto((cliente.documento().length() == 14 ? "CPF: " : "CNPJ: ")
                        + cliente.documento()));
            }
        }
        if (cancelada()) {
            linhas.add(LinhaComprovante.centroNegrito("VENDA CANCELADA em " + Formatos.dataHora(canceladaEm)));
            linhas.add(LinhaComprovante.texto("Motivo: " + motivoCancelamento));
        }
        linhas.add(LinhaComprovante.separador());
        for (Item item : itens) {
            String numero = String.format("%03d", item.sequencia());
            linhas.add(LinhaComprovante.texto(numero + " " + item.codigo() + " " + item.descricao()));
            String quantidade = "    " + Formatos.quantidade(item.quantidade(), item.fracionada()) + " "
                    + item.unidade() + " x " + Formatos.moeda(item.precoUnitario());
            if (item.cancelado()) {
                linhas.add(LinhaComprovante.normal(quantidade, "ITEM CANCELADO"));
                continue;
            }
            linhas.add(LinhaComprovante.normal(quantidade, Formatos.moeda(item.valorBruto())));
            if (item.atacadoAPartirDe() != null) {
                linhas.add(LinhaComprovante.texto("    " + item.textoAtacado()));
            }
            if (item.desconto().signum() > 0) {
                linhas.add(LinhaComprovante.normal("    Desconto no item", "-" + Formatos.moeda(item.desconto())));
            }
        }
        linhas.add(LinhaComprovante.separador());
        linhas.add(LinhaComprovante.normal("Subtotal", Formatos.moeda(valorBruto)));
        if (descontoItens.signum() > 0) {
            linhas.add(LinhaComprovante.normal("Descontos nos itens", "-" + Formatos.moeda(descontoItens)));
        }
        if (descontoVenda.signum() > 0) {
            linhas.add(LinhaComprovante.normal("Desconto na venda", "-" + Formatos.moeda(descontoVenda)));
        }
        linhas.add(LinhaComprovante.negrito("TOTAL", Formatos.moeda(total)));
        for (Pagamento pagamento : pagamentos) {
            linhas.add(LinhaComprovante.normal(pagamento.forma(), Formatos.moeda(pagamento.valor())));
        }
        if (troco.signum() > 0) {
            linhas.add(LinhaComprovante.negrito("Troco", Formatos.moeda(troco)));
        }
        if (!crediario.isEmpty()) {
            linhas.add(LinhaComprovante.separador());
            linhas.add(LinhaComprovante.texto("Parcelas do crediário:"));
            crediario.forEach(p -> linhas.add(p.linha()));
        }
        if (credito != null) {
            linhas.add(LinhaComprovante.separador());
            if (credito.cashback().signum() != 0) {
                linhas.add(LinhaComprovante.normal(cancelada() ? "Cashback (estornado)" : "Cashback gerado",
                        Formatos.moeda(credito.cashback())));
            }
            linhas.add(LinhaComprovante.normal("Saldo de crédito", Formatos.moeda(credito.saldo())));
        }
        if (!Textos.vazio(rodape)) {
            linhas.add(LinhaComprovante.separador());
            linhas.add(LinhaComprovante.centro(rodape));
        }
        return linhas;
    }

    /** Dados da loja e "NÃO É DOCUMENTO FISCAL", no alto de todo comprovante (também o da devolução). */
    static void cabecalho(List<LinhaComprovante> linhas, Loja loja) {
        linhas.add(LinhaComprovante.centroNegrito(loja.nome()));
        if (!Textos.vazio(loja.documento())) {
            linhas.add(LinhaComprovante.centro("CNPJ/CPF: " + loja.documento()));
        }
        if (!Textos.vazio(loja.endereco())) {
            linhas.add(LinhaComprovante.centro(loja.endereco()));
        }
        if (!Textos.vazio(loja.telefone())) {
            linhas.add(LinhaComprovante.centro("Telefone: " + loja.telefone()));
        }
        linhas.add(LinhaComprovante.separador());
        linhas.add(LinhaComprovante.centroNegrito(NAO_FISCAL));
        linhas.add(LinhaComprovante.separador());
    }

    static String formatar(LinhaComprovante linha, int colunas) {
        return switch (linha.estilo()) {
            case SEPARADOR -> "-".repeat(colunas);
            case CENTRO, CENTRO_NEGRITO -> centralizar(linha.esquerda(), colunas);
            case NORMAL, NEGRITO, TEXTO -> lado(linha.esquerda(), linha.direita(), colunas);
        };
    }

    private static String centralizar(String texto, int colunas) {
        String t = texto.length() > colunas ? texto.substring(0, colunas) : texto;
        return " ".repeat((colunas - t.length()) / 2) + t;
    }

    /** Texto à esquerda e valor à direita; o texto é cortado se não couber. */
    private static String lado(String esquerda, String direita, int colunas) {
        if (direita.isEmpty()) {
            return esquerda.length() > colunas ? esquerda.substring(0, colunas) : esquerda;
        }
        int espacoTexto = colunas - direita.length() - 1;
        String texto = esquerda.length() > espacoTexto ? esquerda.substring(0, Math.max(0, espacoTexto)) : esquerda;
        return texto + " ".repeat(colunas - texto.length() - direita.length()) + direita;
    }
}
