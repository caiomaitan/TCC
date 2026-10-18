package br.com.varesync.util;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * {@link Clock} posicionado à mão. Usado pelo gerador de dados de demonstração (6.14) para gravar, pelos próprios
 * services, vendas e movimentos em datas passadas. Fora do gerador o sistema usa o relógio do computador.
 */
public final class RelogioAjustavel extends Clock {

    private volatile Instant agora;
    private final ZoneId zona;

    public RelogioAjustavel(Instant inicio, ZoneId zona) {
        this.agora = inicio;
        this.zona = zona;
    }

    public void ajustar(Instant instante) {
        agora = instante;
    }

    @Override
    public ZoneId getZone() {
        return zona;
    }

    @Override
    public Clock withZone(ZoneId novaZona) {
        return new RelogioAjustavel(agora, novaZona);
    }

    @Override
    public Instant instant() {
        return agora;
    }
}
