package br.com.varesync.ui;

import br.com.varesync.util.MarkdownSimples;
import br.com.varesync.util.MarkdownSimples.Bloco;
import br.com.varesync.util.MarkdownSimples.Lista;
import br.com.varesync.util.MarkdownSimples.Paragrafo;
import br.com.varesync.util.MarkdownSimples.Titulo;
import br.com.varesync.util.MarkdownSimples.Trecho;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;

import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/**
 * Ajuda da tela atual (F1, F21.3), num painel lateral: o texto de {@code resources/ajuda/<tela>.md} desenhado com
 * {@link TextFlow} ({@link MarkdownSimples}) e a lista de atalhos tirada dos {@link Atalhos} registrados na tela.
 */
public final class PainelAjuda extends VBox {

    private final Label titulo = new Label("Ajuda");
    private final VBox corpo = new VBox();

    public PainelAjuda(Runnable fechar) {
        getStyleClass().add("painel-ajuda");
        titulo.getStyleClass().add("ajuda-cabecalho");
        titulo.setWrapText(true);
        titulo.setMinHeight(Region.USE_PREF_SIZE);
        Button botaoFechar = new Button("Fechar (F1)");
        botaoFechar.setMinWidth(Region.USE_PREF_SIZE);
        botaoFechar.setOnAction(e -> fechar.run());
        Region espaco = new Region();
        HBox.setHgrow(espaco, Priority.ALWAYS);
        HBox cabecalho = new HBox(8, titulo, espaco, botaoFechar);
        cabecalho.setAlignment(Pos.CENTER_LEFT);
        cabecalho.getStyleClass().add("ajuda-topo");

        corpo.getStyleClass().add("ajuda-corpo");
        ScrollPane rolagem = new ScrollPane(corpo);
        rolagem.setFitToWidth(true);
        rolagem.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        rolagem.getStyleClass().add("ajuda-rolagem");
        rolagem.setFocusTraversable(false);
        VBox.setVgrow(rolagem, Priority.ALWAYS);
        getChildren().addAll(cabecalho, rolagem);
    }

    /**
     * Mostra a ajuda.
     *
     * @param recurso   o texto da tela ({@code /ajuda/estoque.md})
     * @param daTela    atalhos registrados na tela ({@link Atalhos#daTela})
     * @param globais   atalhos que valem em todas as telas (os da janela principal); vazio no PDV
     */
    public void mostrar(String recurso, List<Atalhos.Atalho> daTela, List<Atalhos.Atalho> globais) {
        List<Bloco> blocos = MarkdownSimples.ler(ler(recurso));
        corpo.getChildren().clear();
        titulo.setText("Ajuda");
        boolean primeiro = true;
        for (Bloco bloco : blocos) {
            if (primeiro && bloco instanceof Titulo t && t.nivel() == 1) {
                titulo.setText("Ajuda — " + MarkdownSimples.textoSimples(t.trechos()));
            } else {
                corpo.getChildren().add(desenhar(bloco));
            }
            primeiro = false;
        }
        if (!daTela.isEmpty()) {
            corpo.getChildren().add(fluxo(List.of(new Trecho(MarkdownSimples.TipoTrecho.TEXTO, "Atalhos desta tela")),
                    "ajuda-titulo-2"));
            corpo.getChildren().add(tabela(daTela));
        }
        if (!globais.isEmpty()) {
            corpo.getChildren().add(fluxo(List.of(new Trecho(MarkdownSimples.TipoTrecho.TEXTO, "Em todas as telas")),
                    "ajuda-titulo-2"));
            corpo.getChildren().add(tabela(globais));
        }
    }

    /** Texto do recurso; se faltar (o teste confere que não falta), um aviso. */
    static String ler(String recurso) {
        try (InputStream in = PainelAjuda.class.getResourceAsStream(recurso)) {
            if (in == null) {
                return "Ainda não há ajuda para esta tela.";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler " + recurso, e);
        }
    }

    private static Node desenhar(Bloco bloco) {
        return switch (bloco) {
            case Titulo t -> fluxo(t.trechos(), "ajuda-titulo-" + Math.min(Math.max(t.nivel(), 2), 3));
            case Paragrafo p -> fluxo(p.trechos(), "ajuda-paragrafo");
            case Lista l -> {
                VBox itens = new VBox();
                itens.getStyleClass().add("ajuda-lista");
                for (List<Trecho> item : l.itens()) {
                    List<Trecho> comMarcador = new ArrayList<>();
                    comMarcador.add(new Trecho(MarkdownSimples.TipoTrecho.TEXTO, "•  "));
                    comMarcador.addAll(item);
                    itens.getChildren().add(fluxo(comMarcador, "ajuda-item"));
                }
                yield itens;
            }
        };
    }

    private static TextFlow fluxo(List<Trecho> trechos, String classe) {
        TextFlow fluxo = new TextFlow();
        fluxo.getStyleClass().add(classe);
        for (Trecho trecho : trechos) {
            switch (trecho.tipo()) {
                case TECLA -> fluxo.getChildren().add(tecla(trecho.texto()));
                case NEGRITO -> fluxo.getChildren().add(texto(trecho.texto(), "ajuda-negrito"));
                case TEXTO -> fluxo.getChildren().add(texto(trecho.texto(), null));
            }
        }
        return fluxo;
    }

    private static Text texto(String conteudo, String classeExtra) {
        Text texto = new Text(conteudo);
        texto.getStyleClass().add("ajuda-texto");
        if (classeExtra != null) {
            texto.getStyleClass().add(classeExtra);
        }
        return texto;
    }

    private static Label tecla(String nome) {
        Label tecla = new Label(nome);
        tecla.getStyleClass().add("tecla");
        tecla.setMinWidth(Region.USE_PREF_SIZE);
        return tecla;
    }

    /**
     * Atalhos em duas colunas (teclas e o que fazem). Teclas com a mesma descrição saem juntas na mesma linha; a
     * mesma tecla com descrições diferentes (Ctrl+N nas abas de Compras) sai em linhas separadas.
     */
    private static GridPane tabela(List<Atalhos.Atalho> atalhos) {
        Map<String, List<String>> porDescricao = new LinkedHashMap<>();
        for (Atalhos.Atalho atalho : atalhos) {
            List<String> teclas = porDescricao.computeIfAbsent(atalho.descricao(), d -> new ArrayList<>());
            if (!teclas.contains(atalho.tecla().texto())) {
                teclas.add(atalho.tecla().texto());
            }
        }
        GridPane grade = new GridPane();
        grade.getStyleClass().add("ajuda-atalhos");
        int linha = 0;
        for (Map.Entry<String, List<String>> entrada : porDescricao.entrySet()) {
            HBox teclas = new HBox(4);
            teclas.setMinWidth(Region.USE_PREF_SIZE);
            entrada.getValue().forEach(t -> teclas.getChildren().add(tecla(t)));
            Label descricao = new Label(entrada.getKey());
            descricao.setWrapText(true);
            descricao.setMinHeight(Region.USE_PREF_SIZE);
            descricao.getStyleClass().add("ajuda-descricao");
            GridPane.setHgrow(descricao, Priority.ALWAYS);
            grade.addRow(linha++, teclas, descricao);
        }
        return grade;
    }
}
