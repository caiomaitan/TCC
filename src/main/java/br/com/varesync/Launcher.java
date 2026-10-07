package br.com.varesync;

/**
 * Ponto de entrada. Não estende {@code Application} de propósito: assim o JavaFX roda
 * pelo classpath, sem {@code module-info}, sem o erro "JavaFX runtime components are missing".
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        App.main(args);
    }
}
