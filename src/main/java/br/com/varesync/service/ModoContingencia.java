package br.com.varesync.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modo contingência do terminal (F23.5), em memória: depois de uma emissão sem resposta da SEFAZ, as vendas seguintes
 * saem em contingência off-line, sem tentar a autorização, com dhCont = o início do modo, até o transmissor conseguir
 * falar com a SEFAZ (status 107). Um por terminal (montado no {@code AppContext}); a entrada e a saída vão para a
 * auditoria por quem chama.
 *
 * <p>Quem observa (o aviso fixo do PDV, F23.9) é avisado logo que o modo muda, na thread de quem mudou (a da venda ou
 * a do transmissor): a tela troca de thread por conta própria.
 */
public final class ModoContingencia {

    private static final Logger LOG = LoggerFactory.getLogger(ModoContingencia.class);

    private OffsetDateTime inicio;
    private final List<Consumer<Optional<OffsetDateTime>>> observadores = new CopyOnWriteArrayList<>();

    public synchronized boolean ativo() {
        return inicio != null;
    }

    public synchronized Optional<OffsetDateTime> inicio() {
        return Optional.ofNullable(inicio);
    }

    /**
     * Passa a avisar {@code observador} a cada entrada (com o início) e saída (vazio) do modo.
     *
     * @return o que para de avisar (a tela chama ao fechar)
     */
    public Runnable observar(Consumer<Optional<OffsetDateTime>> observador) {
        observadores.add(observador);
        return () -> observadores.remove(observador);
    }

    /** Entra no modo; devolve se entrou agora (falso se já estava). */
    boolean entrar(OffsetDateTime quando) {
        synchronized (this) {
            if (inicio != null) {
                return false;
            }
            inicio = quando;
        }
        avisar(Optional.of(quando));
        return true;
    }

    /** Sai do modo; devolve se saiu agora (falso se não estava). */
    boolean sair() {
        synchronized (this) {
            if (inicio == null) {
                return false;
            }
            inicio = null;
        }
        avisar(Optional.empty());
        return true;
    }

    private void avisar(Optional<OffsetDateTime> estado) {
        for (Consumer<Optional<OffsetDateTime>> o : observadores) {
            try {
                o.accept(estado);
            } catch (RuntimeException e) {
                LOG.warn("Falha ao avisar a mudança do modo contingência", e);
            }
        }
    }
}
