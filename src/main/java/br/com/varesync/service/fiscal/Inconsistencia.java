package br.com.varesync.service.fiscal;

/** O que uma regra do R30 achou num produto (F19.4). */
public record Inconsistencia(int regra, Gravidade gravidade, String mensagem) {

    public boolean erro() {
        return gravidade == Gravidade.ERRO;
    }
}
