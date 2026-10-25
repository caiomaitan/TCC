package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Kardex de um produto num período (6.4 e F10.4): o saldo anterior ao início do período (o saldo após o último
 * movimento antes dele, ou 0 se não houver) e os movimentos do período, do mais antigo para o mais recente.
 */
public record Kardex(OffsetDateTime inicio, BigDecimal saldoAnterior, List<MovimentoKardex> movimentos) {

    public Kardex {
        movimentos = List.copyOf(movimentos);
    }

    /** As linhas da tela: "Saldo anterior" primeiro, depois os movimentos. */
    public List<MovimentoKardex> linhas() {
        List<MovimentoKardex> linhas = new ArrayList<>(movimentos.size() + 1);
        linhas.add(MovimentoKardex.saldoAnterior(inicio, saldoAnterior));
        linhas.addAll(movimentos);
        return linhas;
    }
}
