package br.com.varesync.config;

/**
 * Resultado do "Testar conexão" (F11.5): versão do PostgreSQL do servidor e o tempo de resposta, da abertura da
 * conexão até a resposta de uma consulta.
 *
 * @param versaoServidor por exemplo "16.4"
 * @param milissegundos  tempo de resposta
 */
public record ConexaoTestada(String versaoServidor, long milissegundos) {

    /** Texto da tela de conexão: "Conexão bem-sucedida: PostgreSQL 16.4, resposta em 12 ms." */
    public String descricao() {
        return "Conexão bem-sucedida: PostgreSQL " + versaoServidor + ", resposta em " + milissegundos + " ms.";
    }
}
