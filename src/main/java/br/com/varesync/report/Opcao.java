package br.com.varesync.report;

/** Item escolhido num filtro (operador, categoria, produto), com o nome para o cabeçalho do relatório. */
public record Opcao(long id, String nome) {

    @Override
    public String toString() {
        return nome; // exibido nas listas de escolha
    }
}
