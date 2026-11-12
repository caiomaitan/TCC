package br.com.varesync.controller;

import br.com.varesync.model.PrimeirosPassos;
import br.com.varesync.model.PrimeirosPassos.Etapa;
import br.com.varesync.model.PrimeirosPassos.Passo;
import br.com.varesync.model.PrimeirosPassos.Situacao;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * O assistente "Primeiros passos" no Painel (F21.4): os seis passos com a situação de cada um, o botão que abre a tela
 * do passo e, onde o sistema não confere sozinho, "Revisei", "Só eu uso o sistema" ou "Não uso balança". Some quando
 * todos estão concluídos ou dispensados; reaberto pela Ajuda, aparece mesmo assim, com "Fechar".
 */
final class PrimeirosPassosPainel {

    /** O que o painel pede à tela: abrir o passo e gravar as marcações (cada uma devolve os passos de novo). */
    interface Acoes {
        void abrir(Etapa etapa);

        PrimeirosPassos revisarFormas();

        PrimeirosPassos dispensarUsuarios();

        PrimeirosPassos dispensarBalanca();
    }

    static final String CLASSE = "primeiros-passos";

    private final VBox secao;
    private final Acoes acoes;
    private final Consumer<RuntimeException> erro;
    private boolean sempre;

    PrimeirosPassosPainel(VBox secao, Acoes acoes, Consumer<RuntimeException> erro) {
        this.secao = secao;
        this.acoes = acoes;
        this.erro = erro;
        secao.getStyleClass().addAll(CLASSE, "painel-formulario");
        secao.setSpacing(8);
    }

    /** Reaberto pela Ajuda: aparece mesmo com tudo feito, até "Fechar". */
    void mostrarSempre() {
        sempre = true;
    }

    void mostrar(PrimeirosPassos passos) {
        boolean visivel = sempre || !passos.terminado();
        secao.setVisible(visivel);
        secao.setManaged(visivel);
        secao.getChildren().clear();
        if (!visivel) {
            return;
        }
        Label titulo = new Label("Primeiros passos");
        titulo.getStyleClass().add("titulo-secao");
        Label progresso = new Label(passos.feitos() + " de " + passos.passos().size()
                + (passos.terminado() ? " — tudo pronto. Para ver de novo: Ajuda › Primeiros passos…" : " feitos"));
        progresso.getStyleClass().add("subtitulo");
        progresso.setWrapText(true);
        progresso.setMinHeight(Region.USE_PREF_SIZE);
        Region espaco = new Region();
        HBox.setHgrow(espaco, Priority.ALWAYS);
        HBox cabecalho = new HBox(10, titulo, progresso, espaco);
        cabecalho.setAlignment(Pos.CENTER_LEFT);
        if (sempre) {
            Button fechar = new Button("Fechar");
            fechar.setMinWidth(Region.USE_PREF_SIZE);
            fechar.setOnAction(e -> {
                sempre = false;
                mostrar(passos);
            });
            cabecalho.getChildren().add(fechar);
        }
        secao.getChildren().add(cabecalho);
        for (Passo passo : passos.passos()) {
            secao.getChildren().add(linha(passo));
        }
    }

    private HBox linha(Passo passo) {
        Label marca = new Label(switch (passo.situacao()) {
            case CONCLUIDO -> "✔";
            case DISPENSADO -> "–";
            case PENDENTE -> "○";
        });
        marca.getStyleClass().addAll("passo-marca", "passo-" + passo.situacao().name().toLowerCase());
        marca.setMinWidth(Region.USE_PREF_SIZE);
        Label nome = new Label(passo.etapa().titulo() + " — " + passo.situacao().rotulo().toLowerCase());
        nome.getStyleClass().add("passo-titulo");
        Label detalhe = new Label(passo.pendente() ? passo.etapa().descricao() + " " + passo.detalhe()
                : passo.detalhe());
        detalhe.getStyleClass().add("subtitulo");
        detalhe.setWrapText(true);
        detalhe.setMinHeight(Region.USE_PREF_SIZE);
        VBox textos = new VBox(2, nome, detalhe);
        HBox.setHgrow(textos, Priority.ALWAYS);
        textos.setMaxWidth(Double.MAX_VALUE);

        FlowPane botoes = new FlowPane(8, 6);
        botoes.setAlignment(Pos.CENTER_RIGHT);
        botoes.setPrefWrapLength(320);
        Button abrir = new Button(rotuloAbrir(passo.etapa()));
        abrir.setOnAction(e -> acoes.abrir(passo.etapa()));
        botoes.getChildren().add(abrir);
        if (passo.pendente()) {
            switch (passo.etapa()) {
                case FORMAS -> botoes.getChildren().add(marcar("Revisei", acoes::revisarFormas));
                case USUARIOS -> botoes.getChildren().add(marcar("Só eu uso o sistema", acoes::dispensarUsuarios));
                case BALANCA -> botoes.getChildren().add(marcar("Não uso balança", acoes::dispensarBalanca));
                default -> { }
            }
        }
        HBox linha = new HBox(10, marca, textos, botoes);
        linha.setAlignment(Pos.CENTER_LEFT);
        linha.getStyleClass().add("passo");
        linha.getProperties().put(Etapa.class, passo.etapa());
        linha.getProperties().put(Situacao.class, passo.situacao());
        return linha;
    }

    private Button marcar(String texto, Supplier<PrimeirosPassos> acao) {
        Button botao = new Button(texto);
        botao.getStyleClass().add("botao-primario");
        botao.setOnAction(e -> {
            try {
                mostrar(acao.get());
            } catch (RuntimeException falha) {
                erro.accept(falha);
            }
        });
        return botao;
    }

    static String rotuloAbrir(Etapa etapa) {
        return switch (etapa) {
            case LOJA -> "Abrir os dados da loja";
            case FORMAS -> "Abrir as formas de pagamento";
            case PRODUTOS -> "Abrir Produtos";
            case USUARIOS -> "Abrir Usuários";
            case BALANCA -> "Abrir a balança";
            case BACKUP -> "Abrir o backup";
        };
    }
}
