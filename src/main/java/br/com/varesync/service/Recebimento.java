package br.com.varesync.service;

import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.PlanoCrediario;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pagamento da venda (F10, 6.6): combina várias formas e mostra total, pago, falta e troco. Só dinheiro pode passar
 * do que falta (gera troco); as demais formas ficam limitadas ao que falta. Quitado quando pago ≥ total. O crédito do
 * cliente (F12.4) exige cliente identificado e fica limitado também ao saldo disponível; o crediário (F14.2) também
 * exige cliente identificado.
 */
public final class Recebimento {

    /** Valor entregue numa forma de pagamento. */
    public record Parcela(FormaPagamento forma, BigDecimal valor) {
    }

    private final BigDecimal total;
    /** Saldo de crédito que o cliente pode usar; nulo sem cliente identificado. */
    private final BigDecimal creditoDisponivel;
    private final List<Parcela> parcelas = new ArrayList<>();
    private PlanoCrediario planoCrediario;
    private Liberacao liberacaoCrediario;

    /** Venda sem cliente identificado: não aceita o crédito do cliente. */
    public Recebimento(BigDecimal total) {
        this(total, null);
    }

    /** @param creditoDisponivel saldo de crédito do cliente identificado (negativo vale zero); nulo sem cliente */
    public Recebimento(BigDecimal total, BigDecimal creditoDisponivel) {
        this.total = Dinheiro.dinheiro(total);
        this.creditoDisponivel = creditoDisponivel == null ? null : creditoDisponivel.max(Dinheiro.ZERO);
    }

    /**
     * Registra o valor entregue na forma escolhida.
     *
     * @throws NegocioException valor inválido, venda já paga, forma que não aceita troco passando do que falta ou
     *                          crédito do cliente sem cliente ou acima do saldo
     */
    public void adicionar(FormaPagamento forma, BigDecimal valor) {
        if (forma == null) {
            throw new NegocioException("Escolha a forma de pagamento.");
        }
        BigDecimal quantia = CaixaService.valor(valor, "o valor", false);
        if (quitado()) {
            throw new NegocioException("A venda já está paga. Finalize a venda.");
        }
        if (forma.crediario() && creditoDisponivel == null) {
            throw new NegocioException("Para vender no crediário, identifique o cliente (F11).");
        }
        if (forma.creditoCliente()) {
            if (creditoDisponivel == null) {
                throw new NegocioException("Para pagar com o crédito do cliente, identifique o cliente (F11).");
            }
            BigDecimal restante = creditoDisponivel.subtract(creditoUsado());
            if (quantia.compareTo(restante) > 0) {
                throw new NegocioException("Saldo de crédito insuficiente: o cliente tem "
                        + Formatos.moeda(restante) + " disponível.");
            }
        }
        if (!forma.dinheiro() && quantia.compareTo(falta()) > 0) {
            throw new NegocioException("Só o pagamento em dinheiro pode passar do que falta. Em " + forma.descricao()
                    + ", informe até " + Formatos.moeda(falta()) + ".");
        }
        parcelas.add(new Parcela(forma, quantia));
    }

    /** Saldo de crédito do cliente para esta venda; vazio sem cliente identificado. */
    public Optional<BigDecimal> creditoDisponivel() {
        return Optional.ofNullable(creditoDisponivel);
    }

    /** Quanto foi pago com o crédito do cliente (nunca gera troco). */
    public BigDecimal creditoUsado() {
        return parcelas.stream().filter(p -> p.forma().creditoCliente()).map(Parcela::valor)
                .reduce(Dinheiro.ZERO, BigDecimal::add);
    }

    /** Quanto ficou no crediário (F14.2). */
    public BigDecimal crediarioUsado() {
        return parcelas.stream().filter(p -> p.forma().crediario()).map(Parcela::valor)
                .reduce(Dinheiro.ZERO, BigDecimal::add);
    }

    /** Parcelas e primeiro vencimento da parte no crediário; nulo enquanto não informado. */
    public PlanoCrediario planoCrediario() {
        return planoCrediario;
    }

    public void definirPlanoCrediario(PlanoCrediario plano) {
        this.planoCrediario = plano;
    }

    /** Liberação para passar do limite ou vender a cliente em atraso; nula sem ela. */
    public Liberacao liberacaoCrediario() {
        return liberacaoCrediario;
    }

    public void liberarCrediario(Liberacao liberacao) {
        this.liberacaoCrediario = liberacao;
    }

    public void remover(int indice) {
        parcelas.remove(indice);
    }

    public List<Parcela> parcelas() {
        return Collections.unmodifiableList(parcelas);
    }

    public BigDecimal total() {
        return total;
    }

    public BigDecimal pago() {
        return parcelas.stream().map(Parcela::valor).reduce(Dinheiro.ZERO, BigDecimal::add);
    }

    public BigDecimal falta() {
        BigDecimal falta = total.subtract(pago());
        return falta.signum() > 0 ? falta : Dinheiro.ZERO;
    }

    public BigDecimal troco() {
        BigDecimal troco = pago().subtract(total);
        return troco.signum() > 0 ? troco : Dinheiro.ZERO;
    }

    /** Pago ≥ total (total zero dispensa pagamento). */
    public boolean quitado() {
        return pago().compareTo(total) >= 0;
    }

    /**
     * O que vai para {@code venda_pagamento}: uma linha por forma, com o valor abatido da venda. No dinheiro,
     * recebido − troco (6.6). A soma é igual ao total.
     */
    public List<Parcela> valoresAbatidos() {
        Map<Integer, Parcela> porForma = new LinkedHashMap<>();
        for (Parcela p : parcelas) {
            porForma.merge(p.forma().id(), p, (a, b) -> new Parcela(a.forma(), a.valor().add(b.valor())));
        }
        List<Parcela> abatidos = new ArrayList<>(porForma.values());
        BigDecimal troco = troco();
        // O troco só existe porque o dinheiro passou do que faltava: sai das formas em dinheiro, da última para trás.
        for (int i = abatidos.size() - 1; i >= 0 && troco.signum() > 0; i--) {
            Parcela p = abatidos.get(i);
            if (!p.forma().dinheiro()) {
                continue;
            }
            BigDecimal descontado = p.valor().min(troco);
            troco = troco.subtract(descontado);
            BigDecimal restante = p.valor().subtract(descontado);
            if (restante.signum() == 0) {
                abatidos.remove(i);
            } else {
                abatidos.set(i, new Parcela(p.forma(), restante));
            }
        }
        return abatidos;
    }
}
