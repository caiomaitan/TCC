package br.com.varesync.tela;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import org.slf4j.LoggerFactory;

/**
 * O log do roteiro de tela (F23): nos testes o logback só escreve no console, então o que o sistema (SLF4J) e a
 * biblioteca Java_NFe (java.util.logging) registram é capturado aqui, para conferir que nada do certificado nem do XML
 * vai para o log.
 */
final class LogCapturado {

    private final ListAppender<ILoggingEvent> sistema = new ListAppender<>();
    private final List<String> biblioteca = Collections.synchronizedList(new ArrayList<>());

    private LogCapturado() {
    }

    static LogCapturado iniciar() {
        LogCapturado log = new LogCapturado();
        log.sistema.start();
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).addAppender(log.sistema);
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                log.biblioteca.add(r.getLoggerName() + " " + r.getMessage());
            }

            @Override
            public void flush() {
                // nada a descarregar
            }

            @Override
            public void close() {
                // nada a fechar
            }
        };
        handler.setLevel(Level.ALL);
        LogManager.getLogManager().getLogger("").addHandler(handler);
        return log;
    }

    /** Todas as mensagens capturadas (formatadas, com as exceções), uma por linha. */
    String textos() {
        StringBuilder s = new StringBuilder();
        for (ILoggingEvent e : List.copyOf(sistema.list)) {
            s.append(e.getFormattedMessage()).append('\n');
            if (e.getThrowableProxy() != null) {
                s.append(e.getThrowableProxy().getMessage()).append('\n');
            }
        }
        synchronized (biblioteca) {
            biblioteca.forEach(b -> s.append(b).append('\n'));
        }
        return s.toString();
    }
}
