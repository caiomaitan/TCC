package br.com.varesync.model;

import java.util.List;

/**
 * Comprovante não fiscal de largura fixa (bobina de 80 mm): o da venda (6.6) e o da devolução (F13.2). As mesmas
 * linhas viram o texto da tela, o PDF e a impressão.
 */
public interface ComprovanteImpresso {

    /** Linhas na ordem de impressão. */
    List<LinhaComprovante> linhas();

    /** Título da janela ("Comprovante da venda nº 12"). */
    String titulo();

    /** Nome sugerido do PDF ("comprovante-venda-12.pdf"). */
    String nomeArquivo();

    /** Texto de largura fixa para a tela (fonte monoespaçada). */
    default String texto() {
        StringBuilder texto = new StringBuilder();
        for (LinhaComprovante linha : linhas()) {
            texto.append(Comprovante.formatar(linha, Comprovante.COLUNAS)).append('\n');
        }
        return texto.toString();
    }
}
