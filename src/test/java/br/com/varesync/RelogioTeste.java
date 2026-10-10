package br.com.varesync;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** {@link Clock} ajustável para os testes (ex.: avançar 5 minutos e conferir o fim do bloqueio). */
public final class RelogioTeste extends Clock {

    private Instant agora;
    private final ZoneId zona;

    public RelogioTeste(Instant inicio) {
        this(inicio, ZoneId.systemDefault());
    }

    private RelogioTeste(Instant inicio, ZoneId zona) {
        this.agora = inicio;
        this.zona = zona;
    }

    public void avancar(Duration duracao) {
        agora = agora.plus(duracao);
    }

    @Override
    public ZoneId getZone() {
        return zona;
    }

    @Override
    public Clock withZone(ZoneId novaZona) {
        return new RelogioTeste(agora, novaZona);
    }

    @Override
    public Instant instant() {
        return agora;
    }
}
