package br.com.varesync.ui;

import br.com.varesync.service.NegocioException;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;

/** Leitura de número digitado num campo (valor, quantidade, desconto), com a mensagem pelo nome do campo. */
public final class CampoNumero {

    private CampoNumero() {
    }

    /** Vazio → {@code null} (o service pede o valor); texto inválido → "Rótulo: valor inválido (…)". */
    public static BigDecimal ler(String texto, String rotulo) {
        if (Textos.vazio(texto)) {
            return null;
        }
        try {
            return Dinheiro.ler(texto);
        } catch (IllegalArgumentException e) {
            throw new NegocioException(rotulo + ": valor inválido (\"" + texto.strip() + "\").");
        }
    }
}
