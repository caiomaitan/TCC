package br.com.varesync.report;

import br.com.varesync.util.Formatos;
import java.time.OffsetDateTime;

/** Cabeçalho e rodapé de um relatório exportado (7.4): nome da loja, quem emitiu e quando. */
public record Emissao(String loja, String emitidoPor, OffsetDateTime emitidoEm) {

    /** "Emitido por Ana Admin em 17/10/2026 14:30". */
    public String texto() {
        return "Emitido por " + emitidoPor + " em " + Formatos.dataHora(emitidoEm);
    }
}
