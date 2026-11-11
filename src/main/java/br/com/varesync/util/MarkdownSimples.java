package br.com.varesync.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown simples dos textos de ajuda (F21.3, {@code resources/ajuda/<tela>.md}), lido sem biblioteca:
 * <ul>
 *   <li>título: linha começada por {@code #}, {@code ##} ou {@code ###};</li>
 *   <li>lista: linhas seguidas começadas por {@code - } ou {@code * };</li>
 *   <li>parágrafo: linhas seguidas (juntadas com espaço), separadas por linha em branco;</li>
 *   <li>no meio do texto: {@code **negrito**} e teclas entre crases ({@code `F2`}).</li>
 * </ul>
 * Marcação sem par (um {@code **} ou uma crase sozinhos) fica como texto.
 */
public final class MarkdownSimples {

    public enum TipoTrecho { TEXTO, NEGRITO, TECLA }

    public record Trecho(TipoTrecho tipo, String texto) {
    }

    public sealed interface Bloco permits Titulo, Paragrafo, Lista {
    }

    /** @param nivel 1 a 3 */
    public record Titulo(int nivel, List<Trecho> trechos) implements Bloco {
    }

    public record Paragrafo(List<Trecho> trechos) implements Bloco {
    }

    public record Lista(List<List<Trecho>> itens) implements Bloco {
    }

    private MarkdownSimples() {
    }

    public static List<Bloco> ler(String texto) {
        List<Bloco> blocos = new ArrayList<>();
        StringBuilder paragrafo = new StringBuilder();
        List<List<Trecho>> lista = new ArrayList<>();
        for (String bruta : texto.replace("\r", "").split("\n", -1)) {
            String linha = bruta.strip();
            boolean item = linha.startsWith("- ") || linha.startsWith("* ");
            int nivel = nivelTitulo(linha);
            if (linha.isEmpty() || item || nivel > 0) {
                fecharParagrafo(blocos, paragrafo);
            }
            if (!item) {
                fecharLista(blocos, lista);
            }
            if (nivel > 0) {
                blocos.add(new Titulo(nivel, trechos(linha.substring(nivel).strip())));
            } else if (item) {
                lista.add(trechos(linha.substring(2).strip()));
            } else if (!linha.isEmpty()) {
                if (!paragrafo.isEmpty()) {
                    paragrafo.append(' ');
                }
                paragrafo.append(linha);
            }
        }
        fecharParagrafo(blocos, paragrafo);
        fecharLista(blocos, lista);
        return blocos;
    }

    /** Trechos de uma linha: texto, {@code **negrito**} e {@code `tecla`}. */
    public static List<Trecho> trechos(String linha) {
        List<Trecho> trechos = new ArrayList<>();
        StringBuilder texto = new StringBuilder();
        int i = 0;
        while (i < linha.length()) {
            if (linha.startsWith("**", i)) {
                int fim = linha.indexOf("**", i + 2);
                if (fim > i + 2) {
                    adicionar(trechos, TipoTrecho.TEXTO, texto);
                    trechos.add(new Trecho(TipoTrecho.NEGRITO, linha.substring(i + 2, fim)));
                    i = fim + 2;
                    continue;
                }
            } else if (linha.charAt(i) == '`') {
                int fim = linha.indexOf('`', i + 1);
                if (fim > i + 1) {
                    adicionar(trechos, TipoTrecho.TEXTO, texto);
                    trechos.add(new Trecho(TipoTrecho.TECLA, linha.substring(i + 1, fim)));
                    i = fim + 1;
                    continue;
                }
            }
            texto.append(linha.charAt(i));
            i++;
        }
        adicionar(trechos, TipoTrecho.TEXTO, texto);
        return trechos;
    }

    /** Texto corrido dos trechos, sem marcação (para conferir e para o título da janela). */
    public static String textoSimples(List<Trecho> trechos) {
        StringBuilder texto = new StringBuilder();
        trechos.forEach(t -> texto.append(t.texto()));
        return texto.toString();
    }

    private static int nivelTitulo(String linha) {
        int nivel = 0;
        while (nivel < linha.length() && linha.charAt(nivel) == '#') {
            nivel++;
        }
        boolean titulo = nivel >= 1 && nivel <= 3 && linha.length() > nivel && linha.charAt(nivel) == ' ';
        return titulo ? nivel : 0;
    }

    private static void adicionar(List<Trecho> trechos, TipoTrecho tipo, StringBuilder texto) {
        if (!texto.isEmpty()) {
            trechos.add(new Trecho(tipo, texto.toString()));
            texto.setLength(0);
        }
    }

    private static void fecharParagrafo(List<Bloco> blocos, StringBuilder paragrafo) {
        if (!paragrafo.isEmpty()) {
            blocos.add(new Paragrafo(trechos(paragrafo.toString())));
            paragrafo.setLength(0);
        }
    }

    private static void fecharLista(List<Bloco> blocos, List<List<Trecho>> lista) {
        if (!lista.isEmpty()) {
            blocos.add(new Lista(List.copyOf(lista)));
            lista.clear();
        }
    }
}
