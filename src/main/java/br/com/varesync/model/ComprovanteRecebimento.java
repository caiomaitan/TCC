package br.com.varesync.model;

import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Comprovante de recebimento do crediário (F14.3), no layout de 80 mm do comprovante da venda: cliente, títulos com
 * principal e encargos, o que ficou em aberto em cada um, os totais, as formas de pagamento e o troco.
 *
 * @param estornadoEm quando foi estornado; nulo se não foi
 */
public record ComprovanteRecebimento(Comprovante.Loja loja, long recebimentoId, OffsetDateTime dataHora, String usuario,
                                     String liberadoPor, Comprovante.Cliente cliente, List<Titulo> titulos,
                                     BigDecimal principal, BigDecimal multa, BigDecimal juros, BigDecimal desconto,
                                     BigDecimal total, List<Pagamento> pagamentos, BigDecimal troco,
                                     OffsetDateTime estornadoEm, String rodape) implements ComprovanteImpresso {

    public record Titulo(long vendaId, int parcela, int totalParcelas, LocalDate vencimento, BigDecimal principal,
                         BigDecimal multa, BigDecimal juros, BigDecimal desconto, BigDecimal emAbertoDepois) {
    }

    /** Valor abatido na forma; no dinheiro, já sem o troco. */
    public record Pagamento(String forma, boolean dinheiro, BigDecimal valor) {
    }

    public ComprovanteRecebimento {
        titulos = List.copyOf(titulos);
        pagamentos = List.copyOf(pagamentos);
    }

    @Override
    public String titulo() {
        return "Comprovante do recebimento nº " + recebimentoId;
    }

    @Override
    public String nomeArquivo() {
        return "comprovante-recebimento-" + recebimentoId + ".pdf";
    }

    @Override
    public List<LinhaComprovante> linhas() {
        List<LinhaComprovante> linhas = new ArrayList<>();
        Comprovante.cabecalho(linhas, loja);
        linhas.add(LinhaComprovante.centroNegrito("RECEBIMENTO DE CREDIÁRIO"));
        linhas.add(LinhaComprovante.negrito("Recebimento nº " + recebimentoId, Formatos.dataHora(dataHora)));
        linhas.add(LinhaComprovante.texto("Operador: " + usuario));
        if (!Textos.vazio(liberadoPor)) {
            linhas.add(LinhaComprovante.texto("Liberado por: " + liberadoPor));
        }
        linhas.add(LinhaComprovante.texto("Cliente: " + cliente.nome()));
        if (!Textos.vazio(cliente.documento())) {
            linhas.add(LinhaComprovante.texto((cliente.documento().length() == 14 ? "CPF: " : "CNPJ: ")
                    + cliente.documento()));
        }
        linhas.add(LinhaComprovante.separador());
        for (Titulo t : titulos) {
            linhas.add(LinhaComprovante.texto("Venda nº " + t.vendaId() + " — parcela " + t.parcela() + "/"
                    + t.totalParcelas() + " — vence " + Formatos.data(t.vencimento())));
            linhas.add(LinhaComprovante.normal("    Principal", Formatos.moeda(t.principal())));
            if (t.multa().signum() > 0) {
                linhas.add(LinhaComprovante.normal("    Multa", Formatos.moeda(t.multa())));
            }
            if (t.juros().signum() > 0) {
                linhas.add(LinhaComprovante.normal("    Juros", Formatos.moeda(t.juros())));
            }
            if (t.desconto().signum() > 0) {
                linhas.add(LinhaComprovante.normal("    Desconto", "-" + Formatos.moeda(t.desconto())));
            }
            linhas.add(LinhaComprovante.normal("    Ficou em aberto", Formatos.moeda(t.emAbertoDepois())));
        }
        linhas.add(LinhaComprovante.separador());
        linhas.add(LinhaComprovante.normal("Principal", Formatos.moeda(principal)));
        if (multa.signum() > 0) {
            linhas.add(LinhaComprovante.normal("Multa", Formatos.moeda(multa)));
        }
        if (juros.signum() > 0) {
            linhas.add(LinhaComprovante.normal("Juros", Formatos.moeda(juros)));
        }
        if (desconto.signum() > 0) {
            linhas.add(LinhaComprovante.normal("Desconto nos encargos", "-" + Formatos.moeda(desconto)));
        }
        linhas.add(LinhaComprovante.negrito("TOTAL RECEBIDO", Formatos.moeda(total)));
        for (Pagamento p : pagamentos) {
            // O cliente confere o que entregou: no dinheiro, o valor abatido + o troco (como no comprovante da venda).
            linhas.add(LinhaComprovante.normal(p.forma(), Formatos.moeda(p.dinheiro() ? p.valor().add(troco)
                    : p.valor())));
        }
        if (troco.signum() > 0) {
            linhas.add(LinhaComprovante.normal("Troco", Formatos.moeda(troco)));
        }
        if (estornadoEm != null) {
            linhas.add(LinhaComprovante.separador());
            linhas.add(LinhaComprovante.centroNegrito("RECEBIMENTO ESTORNADO"));
            linhas.add(LinhaComprovante.centro("em " + Formatos.dataHora(estornadoEm)));
        }
        if (!Textos.vazio(rodape)) {
            linhas.add(LinhaComprovante.separador());
            linhas.add(LinhaComprovante.centro(rodape));
        }
        return linhas;
    }
}
