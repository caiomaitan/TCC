package br.com.varesync.util;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.util.MarkdownSimples.Bloco;
import br.com.varesync.util.MarkdownSimples.Lista;
import br.com.varesync.util.MarkdownSimples.Paragrafo;
import br.com.varesync.util.MarkdownSimples.TipoTrecho;
import br.com.varesync.util.MarkdownSimples.Titulo;
import br.com.varesync.util.MarkdownSimples.Trecho;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Markdown simples da ajuda (F21.3): títulos, parágrafos, listas, negrito e teclas entre crases. */
class MarkdownSimplesTest {

    @Test
    void titulosParagrafosEListas() {
        List<Bloco> blocos = MarkdownSimples.ler("""
                # Estoque

                Saldo de cada produto
                e o kardex.

                ## Ajustes
                - Entrada
                * Saída
                Texto depois da lista.
                """);

        assertThat(blocos).hasSize(5);
        assertThat(blocos.get(0)).isEqualTo(new Titulo(1, List.of(texto("Estoque"))));
        assertThat(blocos.get(1)).isEqualTo(new Paragrafo(List.of(texto("Saldo de cada produto e o kardex."))));
        assertThat(blocos.get(2)).isEqualTo(new Titulo(2, List.of(texto("Ajustes"))));
        assertThat(blocos.get(3)).isEqualTo(new Lista(List.of(List.of(texto("Entrada")), List.of(texto("Saída")))));
        assertThat(blocos.get(4)).isEqualTo(new Paragrafo(List.of(texto("Texto depois da lista."))));
    }

    @Test
    void negritoETeclas() {
        assertThat(MarkdownSimples.trechos("Tecle `Enter` para **gravar** a contagem")).containsExactly(
                texto("Tecle "), new Trecho(TipoTrecho.TECLA, "Enter"), texto(" para "),
                new Trecho(TipoTrecho.NEGRITO, "gravar"), texto(" a contagem"));
        assertThat(MarkdownSimples.trechos("`3*7891000315507`"))
                .containsExactly(new Trecho(TipoTrecho.TECLA, "3*7891000315507"));
    }

    @Test
    void marcacaoSemParFicaComoTexto() {
        assertThat(MarkdownSimples.trechos("preço ** sem par e ` crase")).containsExactly(
                texto("preço ** sem par e ` crase"));
        assertThat(MarkdownSimples.trechos("vazio `` e ****")).containsExactly(texto("vazio `` e ****"));
    }

    @Test
    void cerquilhaSemEspacoNaoETitulo() {
        assertThat(MarkdownSimples.ler("#5 da fila")).containsExactly(new Paragrafo(List.of(texto("#5 da fila"))));
        assertThat(MarkdownSimples.ler("#### quatro")).containsExactly(new Paragrafo(List.of(texto("#### quatro"))));
    }

    @Test
    void finaisDeLinhaDoWindowsEVazio() {
        assertThat(MarkdownSimples.ler("# A\r\n\r\nB\r\n")).containsExactly(new Titulo(1, List.of(texto("A"))),
                new Paragrafo(List.of(texto("B"))));
        assertThat(MarkdownSimples.ler("")).isEmpty();
        assertThat(MarkdownSimples.textoSimples(MarkdownSimples.trechos("**F1** ajuda"))).isEqualTo("F1 ajuda");
    }

    private static Trecho texto(String texto) {
        return new Trecho(TipoTrecho.TEXTO, texto);
    }
}
