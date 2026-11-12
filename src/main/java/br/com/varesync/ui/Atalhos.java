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
     * @param curto  texto curto da barra de atalhos e do rodapé da tela ("buscar", "ajustar estoque"); nulo = o
     *               atalho só aparece na ajuda (F21.5)
     * @param grupo  qual rodapé mostra o atalho, quando a tela tem mais de um (o {@code userData} do rótulo); nulo =
     *               o rodapé sem grupo
     */
    public record Atalho(Tecla tecla, String descricao, BooleanSupplier quando, Runnable acao, String curto,
                         String grupo) {

        public Atalho(Tecla tecla, String descricao, BooleanSupplier quando, Runnable acao) {
            this(tecla, descricao, quando, acao, null, null);
        }

        boolean vale() {
            return quando == null || quando.getAsBoolean();
        }
    }

    /** Classe de estilo do rótulo de rodapé (e da barra do PDV) que mostra os atalhos da tela (F21.5). */
    public static final String CLASSE_DICA = "dica-atalhos";
    private static final String TEXTO_FIXO = "varesync.dica-texto-fixo";
    static final String SEPARADOR = " · ";

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

    /** O último atalho registrado aparece na barra e no rodapé sem grupo, com este texto curto. */
    public Atalhos curto(String texto) {
        return curto(texto, null);
    }

    /** O último atalho registrado aparece no rodapé do {@code grupo} (uma aba da tela), com este texto curto. */
    public Atalhos curto(String texto, String grupo) {
        if (lista.isEmpty()) {
            throw new IllegalStateException("Registre o atalho antes do texto curto.");
        }
        Atalho a = lista.remove(lista.size() - 1);
        lista.add(new Atalho(a.tecla(), a.descricao(), a.quando(), a.acao(), texto, grupo));
        return this;
    }

    public List<Atalho> lista() {
        return Collections.unmodifiableList(lista);
    }

    /**
     * Texto da barra de atalhos ou do rodapé (F21.5): os atalhos com texto curto do grupo, na ordem do registro ("F4
     * ajustar estoque · F5 ajustar saldo para…"), seguidos do texto fixo do rodapé, se houver.
     */
    public static String dica(List<Atalho> atalhos, String grupo, String textoFixo) {
        List<String> partes = new ArrayList<>();
        for (Atalho atalho : atalhos) {
            if (atalho.curto() != null && java.util.Objects.equals(atalho.grupo(), grupo)) {
                String parte = atalho.tecla().texto() + " " + atalho.curto();
                if (!partes.contains(parte)) {
                    partes.add(parte);
                }
            }
        }
        if (textoFixo != null && !textoFixo.isBlank()) {
            partes.add(textoFixo.strip());
        }
        return String.join(SEPARADOR, partes);
    }

    /**
     * Preenche os rótulos de dica da tela (classe {@link #CLASSE_DICA}) pelo registro: o texto que o rótulo trazia
     * (no FXML) é o texto fixo, que vai depois dos atalhos; o {@code userData} do rótulo, se houver, é o grupo.
     * Chamar de novo não repete o texto fixo.
     */
    public static void preencherDicas(Node raiz) {
        List<Atalho> atalhos = daTela(raiz);
        for (Node no : nos(raiz)) {
            if (no instanceof javafx.scene.control.Labeled rotulo && no.getStyleClass().contains(CLASSE_DICA)) {
                Object fixo = rotulo.getProperties().computeIfAbsent(TEXTO_FIXO,
                        k -> rotulo.getText() == null ? "" : rotulo.getText());
                String grupo = rotulo.getUserData() == null ? null : rotulo.getUserData().toString();
                rotulo.setText(dica(atalhos, grupo, (String) fixo));
            }
        }
    }

    /** Todos os nós da tela, também os das abas que não estão à vista (como {@link #daTela}). */
    static List<Node> nos(Node raiz) {
        List<Node> nos = new ArrayList<>();
        percorrer(raiz, nos, new IdentityHashMap<>());
        return nos;
    }

    private static void percorrer(Node no, List<Node> nos, Map<Node, Boolean> vistos) {
        if (no == null || vistos.put(no, Boolean.TRUE) != null) {
            return;
        }
        nos.add(no);
        if (no instanceof TabPane abas) {
            for (Tab aba : abas.getTabs()) {
                percorrer(aba.getContent(), nos, vistos);
            }
        } else if (no instanceof ScrollPane rolagem) {
            percorrer(rolagem.getContent(), nos, vistos);
        } else if (no instanceof SplitPane divisoria) {
            divisoria.getItems().forEach(item -> percorrer(item, nos, vistos));
        } else if (no instanceof TitledPane painel) {
            percorrer(painel.getContent(), nos, vistos);
        }
        if (no instanceof Parent pai) {
            for (Node filho : pai.getChildrenUnmodifiable()) {
                percorrer(filho, nos, vistos);
            }
        }
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
        for (Node no : nos(raiz)) {
            if (no.getProperties().get(CHAVE) instanceof Atalhos registro) {
                atalhos.addAll(registro.lista);
            }
        }
        return atalhos;
    }
}
