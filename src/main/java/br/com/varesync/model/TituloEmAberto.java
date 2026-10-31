package br.com.varesync.model;

import br.com.varesync.util.CalculoEncargos;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Título em aberto na tela de recebimento (F14.3): a parcela e os encargos na data de hoje. */
public record TituloEmAberto(long tituloId, long vendaId, int parcela, int totalParcelas, LocalDate vencimento,
                             BigDecimal valor, BigDecimal valorPago, BigDecimal valorAbatido,
                             CalculoEncargos.Encargos encargos) {

    /** "1/3". */
    public String parcelaRotulo() {
        return parcela + "/" + totalParcelas;
    }

    public long diasAtraso() {
        return Math.max(0, encargos.diasAtraso());
    }
}
