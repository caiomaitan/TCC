package br.com.varesync.service;

import br.com.varesync.model.Configuracoes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O transmissor em segundo plano (F23.5): com {@code nfce.ativa}, roda a cada {@code nfce.retransmitir_minutos} em todo
 * terminal com alguém logado. Liga no login e desliga no log off (pelo {@code App}); confere a configuração a cada
 * minuto, para valer a mudança sem reiniciar. Numa thread própria (daemon), nunca na do JavaFX; erro vai para o log e a
 * próxima rodada tenta de novo.
 */
public final class TransmissorAgendado implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TransmissorAgendado.class);
    static final Duration INTERVALO_CONFERENCIA = Duration.ofMinutes(1);

    private final Supplier<Configuracoes.Nfce> configuracao;
    private final Consumer<Sessao> transmitir;
    private final Clock clock;
    private ScheduledExecutorService executor;
    private Supplier<Sessao> sessao = () -> null;
    private Instant ultima;

    /**
     * @param configuracao a seção NFC-e das configurações, lida a cada rodada
     * @param transmitir   uma passada do transmissor ({@link TransmissorNfce#transmitir})
     */
    public TransmissorAgendado(Supplier<Configuracoes.Nfce> configuracao, Consumer<Sessao> transmitir, Clock clock) {
        this.configuracao = configuracao;
        this.transmitir = transmitir;
        this.clock = clock;
    }

    /** Liga no login: a sessão é lida a cada rodada (log off = nulo = nada a fazer). */
    public synchronized void iniciar(Supplier<Sessao> sessaoAtual) {
        parar();
        sessao = sessaoAtual;
        ultima = null;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "transmissor-nfce");
            t.setDaemon(true);
            return t;
        });
        long segundos = INTERVALO_CONFERENCIA.toSeconds();
        executor.scheduleWithFixedDelay(this::rodada, segundos, segundos, TimeUnit.SECONDS);
    }

    public synchronized void parar() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        sessao = () -> null;
    }

    private void rodada() {
        try {
            executarSeDevido();
        } catch (RuntimeException e) {
            LOG.error("Falha no transmissor das NFC-e; tenta de novo na próxima rodada", e);
        }
    }

    /** Roda o transmissor se a NFC-e está ligada, há alguém logado e já passou o intervalo; devolve se rodou. */
    boolean executarSeDevido() {
        Sessao atual;
        synchronized (this) {
            atual = sessao.get();
        }
        if (atual == null) {
            return false;
        }
        Configuracoes.Nfce nfce = configuracao.get();
        if (!nfce.ativa()) {
            return false;
        }
        Instant agora = clock.instant();
        synchronized (this) {
            if (ultima != null && agora.isBefore(ultima.plus(Duration.ofMinutes(nfce.retransmitirMinutos())))) {
                return false;
            }
            ultima = agora;
        }
        transmitir.accept(atual);
        return true;
    }

    @Override
    public void close() {
        parar();
    }
}
