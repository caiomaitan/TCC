package br.com.varesync.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TitledPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * Atalhos de teclado de uma tela (F21.3), num registro só: o mesmo registro trata a tecla e dá a lista que a ajuda (F1)
 * mostra, para a lista nunca ficar diferente do que a tela faz.
 *
 * <p>Cada nó tem o seu registro ({@link #de(Node)}), que trata as teclas que passam por ele (filtro de
 * {@code KEY_PRESSED}): o da raiz da tela vale para a tela inteira; o de uma tabela ou de um campo, só com o foco nele.
 * {@link #daTela(Node)} junta os registros de todos os nós da tela, também os das abas que não estão à vista.
 */
public final class Atalhos {

    private static final String CHAVE = "varesync.atalhos";

    /** Tecla com ou sem Ctrl. Sem Shift nem Alt: nenhum atalho do sistema usa. */
    public record Tecla(KeyCode codigo, boolean ctrl) {

        public static Tecla de(KeyCode codigo) {
            return new Tecla(codigo, false);
        }

        public static Tecla ctrl(KeyCode codigo) {
            return new Tecla(codigo, true);
        }

        public boolean combina(KeyEvent e) {
            return e.getCode() == codigo && e.isControlDown() == ctrl && !e.isAltDown() && !e.isMetaDown()
                    && !e.isShiftDown();
        }

        /** Como a tecla aparece na ajuda: "F4", "Ctrl+N", "Enter", "Esc", "↑". */
        public String texto() {
            String nome = switch (codigo) {
                case ENTER -> "Enter";
                case ESCAPE -> "Esc";
                case DELETE -> "Delete";
                case UP -> "↑";
                case DOWN -> "↓";
                case LEFT -> "←";
                case RIGHT -> "→";
                case TAB -> "Tab";
                case SPACE -> "Espaço";
                default -> codigo.getName();
            };
            return ctrl ? "Ctrl+" + nome : nome;
        }
    }

    /**
     * @param quando nulo = sempre; senão o atalho só vale quando a condição é verdadeira (ex.: só na aba Sugestão)
     */
    public record Atalho(Tecla tecla, String descricao, BooleanSupplier quando, Runnable acao) {

        boolean vale() {
            return quando == null || quando.getAsBoolean();
        }
    }

    private final List<Atalho> lista = new ArrayList<>();

    private Atalhos() {
    }

    /** Registro do nó; o primeiro uso instala no nó o filtro que trata as teclas. */
    public static Atalhos de(Node no) {
        Object existente = no.getProperties().get(CHAVE);
        if (existente instanceof Atalhos atalhos) {
            return atalhos;
        }
        Atalhos atalhos = new Atalhos();
        no.getProperties().put(CHAVE, atalhos);
        no.addEventFilter(KeyEvent.KEY_PRESSED, atalhos::tratar);
        return atalhos;
    }

    public Atalhos em(Tecla tecla, String descricao, Runnable acao) {
        return em(tecla, descricao, null, acao);
    }

    public Atalhos em(Tecla tecla, String descricao, BooleanSupplier quando, Runnable acao) {
        lista.add(new Atalho(tecla, descricao, quando, acao));
        return this;
    }

    public List<Atalho> lista() {
        return Collections.unmodifiableList(lista);
    }

    /**
     * Executa o primeiro atalho da tecla que vale agora e consome o evento.
     *
     * @return true se algum atalho tratou a tecla
     */
    public boolean tratar(KeyEvent e) {
        for (Atalho atalho : lista) {
            if (atalho.tecla().combina(e) && atalho.vale()) {
                e.consume();
                atalho.acao().run();
                return true;
            }
        }
        return false;
    }

    /**
     * Todos os atalhos registrados nos nós da tela, de fora para dentro e na ordem do registro. Entram também os nós
     * das abas que não estão à vista e os conteúdos de rolagens e divisórias (antes de a tela ser desenhada eles
     * ainda não são filhos do controle).
     */
    public static List<Atalho> daTela(Node raiz) {
        List<Atalho> atalhos = new ArrayList<>();
        visitar(raiz, atalhos, new IdentityHashMap<>());
        return atalhos;
    }

    private static void visitar(Node no, List<Atalho> atalhos, Map<Node, Boolean> vistos) {
        if (no == null || vistos.put(no, Boolean.TRUE) != null) {
            return;
        }
        if (no.getProperties().get(CHAVE) instanceof Atalhos registro) {
            atalhos.addAll(registro.lista);
        }
        if (no instanceof TabPane abas) {
            for (Tab aba : abas.getTabs()) {
                visitar(aba.getContent(), atalhos, vistos);
            }
        } else if (no instanceof ScrollPane rolagem) {
            visitar(rolagem.getContent(), atalhos, vistos);
        } else if (no instanceof SplitPane divisoria) {
            divisoria.getItems().forEach(item -> visitar(item, atalhos, vistos));
        } else if (no instanceof TitledPane painel) {
            visitar(painel.getContent(), atalhos, vistos);
        }
        if (no instanceof Parent pai) {
            for (Node filho : pai.getChildrenUnmodifiable()) {
                visitar(filho, atalhos, vistos);
            }
        }
    }
}
