package br.com.varesync.model;

import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Comprovante não fiscal (6.6): dados da loja, "NÃO É DOCUMENTO FISCAL", número da venda, data/hora, operador,
 * itens, descontos, total, pagamentos, troco e rodapé. {@link #linhas()} é a fonte única da tela e do PDF.
 *
 * @param pagamentos valor entregue por forma (no dinheiro, já com o troco somado)
 * @param cliente    cliente identificado (F12.2), ou nulo
 */
public record Comprovante(Loja loja, long vendaId, OffsetDateTime dataHora, String operador, String terminal,
                          StatusVenda status, OffsetDateTime canceladaEm, String motivoCancelamento,
                          List<Item> itens, BigDecimal valorBruto, BigDecimal descontoItens,
                          BigDecimal descontoVenda, BigDecimal total, List<Pagamento> pagamentos, BigDecimal troco,
                          String rodape, Cliente cliente) {

    public static final String NAO_FISCAL = "NÃO É DOCUMENTO FISCAL";
    /** Largura do comprovante em tela, como numa impressora de 80 mm. */
    public static final int COLUNAS = 48;

    public record Loja(String nome, String documento, String endereco, String telefone) {
    }

    /** Cliente no comprovante (F12.2): nome e CPF já mascarado ({@code ***.456.789-**}); documento vazio se faltar. */
    public record Cliente(String nome, String documento) {
    }

    public record Item(int sequencia, String codigo, String descricao, BigDecimal quantidade, String unidade,
                       boolean fracionada, BigDecimal precoUnitario, BigDecimal valorBruto, BigDecimal desconto,
                       boolean cancelado) {
    }

    public record Pagamento(String forma, BigDecimal valor) {
    }

    public Comprovante {
        itens = List.copyOf(itens);
        pagamentos = List.copyOf(pagamentos);
    }

    public BigDecimal descontos() {
        return descontoItens.add(descontoVenda);
    }

    public boolean cancelada() {
        return status == StatusVenda.CANCELADA;
    }

    /** Linhas na ordem de impressão. */
    public List<LinhaComprovante> linhas() {
        List<LinhaComprovante> linhas = new ArrayList<>();
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
        if (!Textos.vazio(rodape)) {
            linhas.add(LinhaComprovante.separador());
            linhas.add(LinhaComprovante.centro(rodape));
        }
        return linhas;
    }

    /** Texto de largura fixa para a tela (fonte monoespaçada). */
    public String texto() {
        StringBuilder texto = new StringBuilder();
        for (LinhaComprovante linha : linhas()) {
            texto.append(formatar(linha, COLUNAS)).append('\n');
        }
        return texto.toString();
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
