package br.com.varesync.ui;

import br.com.varesync.service.NegocioException;
import br.com.varesync.service.TradutorErros;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tratamento global de exceções: registra no log e mostra um alerta amigável.
 * Erro técnico vai para o log com stack trace; a tela mostra só a mensagem traduzida.
 */
public final class TratadorExcecoes {

    private static final Logger log = LoggerFactory.getLogger(TratadorExcecoes.class);

    private TratadorExcecoes() {
    }

    /** Instala o tratador para todas as threads, inclusive a FX Application Thread. */
    public static void instalar() {
        Thread.setDefaultUncaughtExceptionHandler((thread, erro) -> mostrar(erro));
        Thread.currentThread().setUncaughtExceptionHandler((thread, erro) -> mostrar(erro));
    }

    public static void mostrar(Throwable erro) {
        RuntimeException traduzida = TradutorErros.traduzir(erro);
        if (traduzida instanceof NegocioException) {
            log.info("Regra de negócio: {}", traduzida.getMessage());
            exibir(Alert.AlertType.WARNING, traduzida.getMessage());
        } else {
            log.error("Erro inesperado", erro);
            exibir(Alert.AlertType.ERROR, traduzida.getMessage());
        }
    }

    private static void exibir(Alert.AlertType tipo, String mensagem) {
        try {
            // Sempre via runLater: showAndWait não pode rodar durante animação ou layout.
            Platform.runLater(() -> Dialogos.alerta(tipo, mensagem));
        } catch (IllegalStateException toolkitIndisponivel) {
            log.warn("Interface indisponível para exibir o alerta: {}", mensagem);
        }
    }
}
