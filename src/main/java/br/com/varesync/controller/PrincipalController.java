package br.com.varesync.controller;

import br.com.varesync.config.AppConfig;
import br.com.varesync.util.Formatos;
import java.time.Clock;
import java.time.OffsetDateTime;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Janela principal: menu lateral (preenchido por permissão a partir da Fase 1),
 * área de conteúdo e barra de status.
 */
public class PrincipalController {

    private static final String SEM_INFORMACAO = "—";

    private final AppConfig config;
    private final Clock clock;
    private Timeline relogio;

    @FXML private VBox menu;
    @FXML private Label statusUsuario;
    @FXML private Label statusTerminal;
    @FXML private Label statusCaixa;
    @FXML private Label statusDataHora;
    @FXML private Label statusConexao;

    public PrincipalController(AppConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    @FXML
    private void initialize() {
        statusUsuario.setText("Usuário: " + SEM_INFORMACAO);
        statusTerminal.setText("Terminal: " + config.terminalId());
        statusCaixa.setText("Caixa: " + SEM_INFORMACAO);
        statusConexao.setText("Banco: conectado (" + descreverBanco(config.url()) + ")");
        atualizarDataHora();
        relogio = new Timeline(new KeyFrame(Duration.seconds(1), e -> atualizarDataHora()));
        relogio.setCycleCount(Animation.INDEFINITE);
        relogio.play();
    }

    public void encerrar() {
        if (relogio != null) {
            relogio.stop();
        }
    }

    private void atualizarDataHora() {
        statusDataHora.setText(Formatos.dataHora(OffsetDateTime.now(clock)));
    }

    /** "jdbc:postgresql://localhost:5432/varesync?x=y" → "localhost:5432/varesync". */
    static String descreverBanco(String url) {
        String semPrefixo = url.replaceFirst("^jdbc:postgresql://", "");
        int parametros = semPrefixo.indexOf('?');
        return parametros >= 0 ? semPrefixo.substring(0, parametros) : semPrefixo;
    }
}
