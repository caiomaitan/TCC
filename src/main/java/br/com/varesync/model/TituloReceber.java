package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Uma parcela do crediário ({@code titulo_receber}, F14.1). */
public record TituloReceber(long id, long clienteId, long vendaId, int parcela, int totalParcelas, LocalDate vencimento,
                            BigDecimal valor, BigDecimal valorPago, BigDecimal valorAbatido, StatusTitulo status) {

    /** Valor − pago − abatido; zero no título cancelado. */
    public BigDecimal emAberto() {
        return status == StatusTitulo.CANCELADO ? BigDecimal.ZERO : valor.subtract(valorPago).subtract(valorAbatido);
    }
}
