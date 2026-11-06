package br.com.varesync.model;

import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Lote de um produto com controle de validade (F17), com o saldo atual. O "SEM LOTE" tem código vazio e validade
 * nula: guarda a mercadoria de validade desconhecida e é o único que pode ficar negativo.
 *
 * @param codigo   vazio quando o lote não tem código
 * @param validade nula só no "SEM LOTE"
 */
public record Lote(long id, long produtoId, String codigo, LocalDate validade, BigDecimal saldo,
                   OffsetDateTime criadoEm) {

    /** Rótulo do lote de validade desconhecida. */
    public static final String SEM_LOTE = "SEM LOTE";

    public boolean semLote() {
        return validade == null && codigo.isEmpty();
    }

    /** "SEM LOTE", "L123 (validade 10/12/2026)" ou "Validade 10/12/2026" (lote sem código). */
    public String descricao() {
        return descrever(codigo, validade);
    }

    public static String descrever(String codigo, LocalDate validade) {
        String cod = codigo == null ? "" : codigo;
        if (validade == null) {
            return cod.isEmpty() ? SEM_LOTE : cod;
        }
        String data = Formatos.data(validade);
        return cod.isEmpty() ? "Validade " + data : cod + " (validade " + data + ")";
    }
}
