package br.com.varesync.model;

import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Comprovante de devolução (F13.2), no mesmo layout de 80 mm do comprovante da venda: loja, "NÃO É DOCUMENTO
 * FISCAL", número da devolução e da venda, quem registrou e quem liberou, cliente, itens devolvidos (avariados
 * marcados), total, forma de reembolso, motivo, cashback estornado e, quando há cliente, o saldo do vale-troca.
 *
 * @param liberadoPor      nome de quem liberou, ou nulo
 * @param cliente          cliente da devolução, ou nulo
 * @param cashbackEstornado zero sem estorno
 * @param saldoCredito     saldo de crédito do cliente na emissão; nulo quando a devolução não mexeu no crédito
 */
public record ComprovanteDevolucao(Comprovante.Loja loja, long devolucaoId, OffsetDateTime dataHora, long vendaId,
                                   OffsetDateTime vendaDataHora, String usuario, String liberadoPor,
                                   Comprovante.Cliente cliente, List<Item> itens, BigDecimal total,
                                   FormaReembolso forma, String motivo, String observacao,
                                   BigDecimal cashbackEstornado, BigDecimal saldoCredito, String rodape)
        implements ComprovanteImpresso {

    public record Item(int sequencia, String codigo, String descricao, BigDecimal quantidade, String unidade,
                       boolean fracionada, BigDecimal valor, boolean avariado) {
    }

    public ComprovanteDevolucao {
        itens = List.copyOf(itens);
    }

    @Override
    public String titulo() {
        return "Comprovante da devolução nº " + devolucaoId;
    }

    @Override
    public String nomeArquivo() {
        return "comprovante-devolucao-" + devolucaoId + ".pdf";
    }

    @Override
    public List<LinhaComprovante> linhas() {
        List<LinhaComprovante> linhas = new ArrayList<>();
        Comprovante.cabecalho(linhas, loja);
        linhas.add(LinhaComprovante.centroNegrito("DEVOLUÇÃO DE MERCADORIA"));
        linhas.add(LinhaComprovante.negrito("Devolução nº " + devolucaoId, Formatos.dataHora(dataHora)));
        linhas.add(LinhaComprovante.normal("Venda nº " + vendaId, Formatos.dataHora(vendaDataHora)));
        linhas.add(LinhaComprovante.texto("Registrada por: " + usuario));
        if (!Textos.vazio(liberadoPor)) {
            linhas.add(LinhaComprovante.texto("Liberada por: " + liberadoPor));
        }
        if (cliente != null) {
            linhas.add(LinhaComprovante.texto("Cliente: " + cliente.nome()));
            if (!Textos.vazio(cliente.documento())) {
                linhas.add(LinhaComprovante.texto((cliente.documento().length() == 14 ? "CPF: " : "CNPJ: ")
                        + cliente.documento()));
            }
        }
        linhas.add(LinhaComprovante.separador());
        for (Item item : itens) {
            linhas.add(LinhaComprovante.texto(String.format("%03d", item.sequencia()) + " " + item.codigo() + " "
                    + item.descricao()));
            linhas.add(LinhaComprovante.normal("    " + Formatos.quantidade(item.quantidade(), item.fracionada()) + " "
                    + item.unidade() + (item.avariado() ? " (avariado)" : ""), Formatos.moeda(item.valor())));
        }
        linhas.add(LinhaComprovante.separador());
        linhas.add(LinhaComprovante.negrito("TOTAL DEVOLVIDO", Formatos.moeda(total)));
        linhas.add(LinhaComprovante.normal("Reembolso", forma.curto()));
        linhas.add(LinhaComprovante.texto("Motivo: " + motivo));
        if (!Textos.vazio(observacao)) {
            linhas.add(LinhaComprovante.texto("Obs.: " + observacao));
        }
        if (cashbackEstornado.signum() > 0 || saldoCredito != null) {
            linhas.add(LinhaComprovante.separador());
            if (cashbackEstornado.signum() > 0) {
                linhas.add(LinhaComprovante.normal("Cashback estornado", "-" + Formatos.moeda(cashbackEstornado)));
            }
            if (saldoCredito != null) {
                linhas.add(LinhaComprovante.normal(forma == FormaReembolso.CREDITO_CLIENTE ? "Saldo do vale-troca"
                        : "Saldo de crédito", Formatos.moeda(saldoCredito)));
            }
        }
        if (!Textos.vazio(rodape)) {
            linhas.add(LinhaComprovante.separador());
            linhas.add(LinhaComprovante.centro(rodape));
        }
        return linhas;
    }

}
