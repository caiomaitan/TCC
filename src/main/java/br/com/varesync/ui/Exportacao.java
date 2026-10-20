package br.com.varesync.ui;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Exportação de relatório para arquivo (7.4): a janela "Salvar como" com o nome sugerido e a gravação em
 * {@link Task}, com os botões desabilitados enquanto grava e o resultado na mensagem da tela. Usada pela tela
 * Relatórios, pelo fechamento de caixa (R11) e pelo inventário (R13).
 */
public final class Exportacao {

    private Exportacao() {
    }

    /**
     * Pergunta onde salvar.
     *
     * @param formato  "CSV" ou "PDF"
     * @param extensao "csv" ou "pdf"
     * @return vazio se a pessoa desistiu
     */
    public static Optional<Path> escolher(Window dona, String formato, String extensao, String nomeSugerido) {
        FileChooser escolha = new FileChooser();
        escolha.setTitle("Exportar " + formato);
        escolha.getExtensionFilters().add(new FileChooser.ExtensionFilter(formato, "*." + extensao));
        escolha.setInitialFileName(nomeSugerido);
        File arquivo = escolha.showSaveDialog(dona);
        return Optional.ofNullable(arquivo).map(File::toPath);
    }

    /**
     * Grava em {@link Task}: desabilita os botões, mostra "Exportando…" e depois "CSV salvo em …" ou o erro.
     *
     * @return a tarefa já iniciada
     */
    public static Task<Void> gravar(String formato, Path destino, Consumer<Path> gravacao, Label mensagem,
                                    List<? extends Node> botoes) {
        Task<Void> tarefa = new Task<>() {
            @Override
            protected Void call() {
                gravacao.accept(destino);
                return null;
            }
        };
        botoes.forEach(b -> b.setDisable(true));
        Mensagem.informar(mensagem, "Exportando " + formato + "…");
        tarefa.setOnSucceeded(e -> {
            botoes.forEach(b -> b.setDisable(false));
            Mensagem.sucesso(mensagem, formato + " salvo em " + destino + ".");
        });
        tarefa.setOnFailed(e -> {
            botoes.forEach(b -> b.setDisable(false));
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(tarefa.getException()));
        });
        return Tarefas.executar("exportar", tarefa);
    }
}
