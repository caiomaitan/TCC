package br.com.varesync.service;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Executa um programa externo (o {@code pg_dump} do backup). Separado para os testes não dependerem dele. */
public interface ExecutorProcesso {

    /** @param saida texto da saída padrão e de erro, juntas */
    record Resultado(int codigo, String saida) {
    }

    /**
     * @param comando  programa e argumentos (nunca a senha: ela vai no ambiente)
     * @param ambiente variáveis acrescentadas ao ambiente do processo
     * @throws IOException programa não encontrado, falha ao iniciar ou tempo esgotado
     */
    Resultado executar(List<String> comando, Map<String, String> ambiente, Duration limite) throws IOException;

    /** Executor real, pelo {@link ProcessBuilder}. */
    static ExecutorProcesso sistema() {
        return (comando, ambiente, limite) -> {
            ProcessBuilder pb = new ProcessBuilder(comando).redirectErrorStream(true);
            pb.environment().putAll(ambiente);
            Process processo = pb.start();
            processo.getOutputStream().close();
            StringBuilder saida = new StringBuilder();
            Thread leitor = Thread.ofVirtual().start(() -> {
                try {
                    saida.append(new String(processo.getInputStream().readAllBytes()));
                } catch (IOException e) {
                    saida.append(e.getMessage());
                }
            });
            try {
                if (!processo.waitFor(limite.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    processo.destroyForcibly();
                    throw new IOException("Tempo esgotado: " + comando.get(0));
                }
                leitor.join(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                processo.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("Interrompido: " + comando.get(0), e);
            }
            synchronized (saida) {
                return new Resultado(processo.exitValue(), saida.toString().strip());
            }
        };
    }
}
