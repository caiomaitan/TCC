package br.com.varesync.ui;

import javafx.concurrent.Task;

/** Executa {@link Task}s fora da FX Application Thread. */
public final class Tarefas {

    private Tarefas() {
    }

    public static <T> Task<T> executar(String nome, Task<T> tarefa) {
        Thread thread = new Thread(tarefa, "varesync-" + nome);
        thread.setDaemon(true);
        thread.start();
        return tarefa;
    }
}
