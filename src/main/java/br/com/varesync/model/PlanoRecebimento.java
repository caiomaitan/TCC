package br.com.varesync.model;

import br.com.varesync.util.Dinheiro;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

/** Como um valor se distribui nos títulos (F14.3): por título, principal, multa, juros e desconto; e os totais. */
public record PlanoRecebimento(List<Linha> linhas) {

    /** A parte de um título; {@code emAbertoDepois} é o que fica em aberto, sem encargos. */
    public record Linha(TituloEmAberto titulo, BigDecimal principal, BigDecimal multa, BigDecimal juros,
                        BigDecimal desconto) {

        public BigDecimal total() {
            return principal.add(multa).add(juros).subtract(desconto);
        }

        public BigDecimal emAbertoDepois() {
            return titulo.encargos().emAberto().subtract(principal);
        }
    }

    public PlanoRecebimento {
        linhas = List.copyOf(linhas);
    }

    public BigDecimal principal() {
        return somar(Linha::principal);
    }

    public BigDecimal multa() {
        return somar(Linha::multa);
    }

    public BigDecimal juros() {
        return somar(Linha::juros);
    }

    public BigDecimal desconto() {
        return somar(Linha::desconto);
    }

    /** O que o cliente paga: principal + multa + juros − desconto. */
    public BigDecimal total() {
        return somar(Linha::total);
    }

    private BigDecimal somar(Function<Linha, BigDecimal> parte) {
        return linhas.stream().map(parte).reduce(Dinheiro.ZERO, BigDecimal::add);
    }
}
