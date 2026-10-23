package br.com.varesync.ui;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javafx.collections.ListChangeListener;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Ícone do VareSync na barra de título e na barra de tarefas de todas as janelas (principal, modais, diálogos). */
public final class Icone {

    private static final Logger log = LoggerFactory.getLogger(Icone.class);

    private static final int[] TAMANHOS = {16, 32, 48, 64, 128, 256};

    private Icone() {
    }

    /** Põe o ícone em toda janela que abrir daqui em diante, sem que cada tela precise lembrar. */
    public static void instalar() {
        List<Image> imagens = carregar();
        if (imagens.isEmpty()) {
            return;
        }
        Window.getWindows().addListener((ListChangeListener<Window>) mudanca -> {
            while (mudanca.next()) {
                for (Window janela : mudanca.getAddedSubList()) {
                    if (janela instanceof Stage palco && palco.getIcons().isEmpty()) {
                        palco.getIcons().setAll(imagens);
                    }
                }
            }
        });
    }

    private static List<Image> carregar() {
        List<Image> imagens = new ArrayList<>();
        for (int tamanho : TAMANHOS) {
            String recurso = "/img/varesync-" + tamanho + ".png";
            try (InputStream entrada = Icone.class.getResourceAsStream(recurso)) {
                if (entrada == null) {
                    log.warn("Ícone não encontrado: {}", recurso);
                    continue;
                }
                imagens.add(new Image(entrada));
            } catch (IOException e) {
                log.warn("Falha ao ler o ícone {}", recurso, e);
            }
        }
        return imagens;
    }
}
