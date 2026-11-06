package br.com.varesync.model;

import br.com.varesync.util.Gtin;
import java.util.Optional;

/** Códigos de barras que a etiqueta de gôndola desenha (F18.1): EAN-13, EAN-8 e UPC-A, com dígito válido. */
public enum TipoCodigoBarras {
    EAN_13(13),
    EAN_8(8),
    UPC_A(12);

    private final int tamanho;

    TipoCodigoBarras(int tamanho) {
        this.tamanho = tamanho;
    }

    public int tamanho() {
        return tamanho;
    }

    /** O tipo do código, se ele puder ir desenhado na etiqueta (GTIN-14 e códigos inválidos não vão). */
    public static Optional<TipoCodigoBarras> de(String codigo) {
        if (codigo == null || !Gtin.valido(codigo)) {
            return Optional.empty();
        }
        for (TipoCodigoBarras t : values()) {
            if (t.tamanho == codigo.length()) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
