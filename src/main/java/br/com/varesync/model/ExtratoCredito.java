package br.com.varesync.model;

import java.math.BigDecimal;
import java.util.List;

/** Saldo de crédito do cliente e o extrato, do movimento mais antigo para o mais recente (F12.3). */
public record ExtratoCredito(BigDecimal saldo, List<MovimentoCredito> movimentos) {

    public ExtratoCredito {
        movimentos = List.copyOf(movimentos);
    }
}
