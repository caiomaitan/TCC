package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Recebimento na lista da sessão de caixa (F14.3). */
public record RecebimentoSessao(long id, OffsetDateTime dataHora, String cliente, String usuario, BigDecimal principal,
                                BigDecimal encargos, BigDecimal desconto, BigDecimal total, BigDecimal troco,
                                String formas, boolean estornado) {

    public String situacao() {
        return estornado ? "Estornado" : "Recebido";
    }
}
