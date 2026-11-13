package br.com.varesync.tela;

import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Cell;
import javafx.scene.control.Control;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeTableView;
import javafx.scene.text.Text;

/**
 * Varredura de cortes (F11.3, regras das conferências da F21): na cena já desenhada, procura
 * <ul>
 *   <li>rótulos, botões, caixas de marcação e títulos com o texto encurtado com "…" — fora das células de tabela e
 *       lista, onde o dado longo pode sair com "…" e a dica mostra o texto inteiro;</li>
 *   <li>controles que passam da borda direita da janela — fora do que rola de lado (tabela, lista, área de
 *       rolagem) e da barra de abas, que rola sozinha. Barra que não cabe não tem "…": por isso a segunda regra;</li>
 *   <li>tabela com a política de ajuste à largura (que não rola de lado) com as colunas passando da área dela: a
 *       última coluna fica escondida, sem "…" e sem barra de rolagem (achado no PDV a 130%).</li>
 * </ul>
 * O que fica inteiro fora da área recortada de um contêiner (uma aba sem altura, por exemplo) não aparece na tela e
 * não conta.
 * Chamar na thread do JavaFX.
 */
final class Cortes {

    /** O ajuste à largura do JavaFX arredonda cada coluna ao pixel: até 3 px a mais é borda, não coluna escondida. */
    static final double TOLERANCIA_COLUNAS = 3;

    private Cortes() {
    }

    /** @return a descrição de cada corte; vazia se a tela está inteira */
    static List<String> procurar(Scene cena) {
        Parent raiz = cena.getRoot();
        raiz.applyCss();
        raiz.layout();
        List<String> cortes = new ArrayList<>();
        double largura = cena.getWidth();
        for (Node n : todos(raiz)) {
            if (!visivel(n)) {
                continue;
            }
            if (n instanceof Labeled l && !dentroDeCelula(n) && l.getText() != null && !l.getText().isBlank()) {
                for (Node filho : l.getChildrenUnmodifiable()) {
                    if (filho instanceof Text texto && texto.getText() != null && !texto.getText().equals(l.getText())
                            && texto.getText().contains(l.getEllipsisString())) {
                        cortes.add(l.getClass().getSimpleName() + " \"" + l.getText().replace('\n', ' ') + "\" saiu \""
                                + texto.getText().replace('\n', ' ') + "\"");
                    }
                }
            }
            if (n instanceof TableView<?> tabela && tabela.getColumnResizePolicy() != TableView.UNCONSTRAINED_RESIZE_POLICY) {
                // Com a política "ajustar à largura" a tabela não rola de lado: coluna além da área some sem aviso.
                double colunas = tabela.getVisibleLeafColumns().stream().mapToDouble(c -> c.getWidth()).sum();
                double util = tabela.getWidth() - tabela.snappedLeftInset() - tabela.snappedRightInset();
                if (colunas > util + TOLERANCIA_COLUNAS) {
                    cortes.add("colunas além da tabela " + (tabela.getId() == null ? "" : tabela.getId() + " ")
                            + "sem rolagem de lado (" + Math.round(colunas) + " px de colunas em " + Math.round(util)
                            + " px)");
                }
            }
            if (n instanceof Control && !dentroDeRolagem(n) && !naBarraDeAbas(n)) {
                Bounds b = n.localToScene(n.getLayoutBounds());
                if (b.getMaxX() > largura + 1 && b.getWidth() > 0) {
                    String texto = n instanceof Labeled l ? "\"" + l.getText() + "\"" : n.getId();
                    cortes.add("passa da borda: " + n.getClass().getSimpleName() + " " + texto + " (até x="
                            + Math.round(b.getMaxX()) + " numa janela de " + Math.round(largura) + ")");
                }
            }
        }
        return cortes;
    }

    static boolean dentroDeCelula(Node n) {
        for (Node p = n.getParent(); p != null; p = p.getParent()) {
            if (p instanceof Cell<?> || p.getStyleClass().contains("combo-box-base")
                    || p.getStyleClass().contains("column-header")) {
                return true;
            }
        }
        return false;
    }

    /** Dentro de algo que rola de lado: passar da borda ali é rolar, não cortar. */
    static boolean dentroDeRolagem(Node n) {
        for (Node p = n.getParent(); p != null; p = p.getParent()) {
            if (p.getClip() != null || p instanceof TableView<?> || p instanceof ListView<?>
                    || p instanceof TreeTableView<?>
                    || (p instanceof ScrollPane sp && sp.getHbarPolicy() != ScrollPane.ScrollBarPolicy.NEVER
                    && !sp.isFitToWidth())) {
                return true;
            }
        }
        return false;
    }

    static boolean naBarraDeAbas(Node n) {
        for (Node p = n.getParent(); p != null; p = p.getParent()) {
            if (p.getStyleClass().contains("tab-header-area")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Visível e com alguma parte dentro da área recortada dos contêineres (aba, divisória, rolagem): o que fica
     * inteiro fora dela não aparece na tela, então não está "cortado".
     */
    static boolean visivel(Node n) {
        for (Node p = n; p != null; p = p.getParent()) {
            if (!p.isVisible() || p.getOpacity() == 0) {
                return false;
            }
        }
        Bounds proprio = n.localToScene(n.getLayoutBounds());
        for (Node p = n.getParent(); p != null; p = p.getParent()) {
            if (p.getClip() != null) {
                Bounds area = p.localToScene(p.getClip().getBoundsInParent());
                double altura = Math.min(proprio.getMaxY(), area.getMaxY()) - Math.max(proprio.getMinY(), area.getMinY());
                double largura = Math.min(proprio.getMaxX(), area.getMaxX()) - Math.max(proprio.getMinX(), area.getMinX());
                if (altura < 1 || largura < 1) {
                    return false;
                }
            }
        }
        return true;
    }

    static List<Node> todos(Node raiz) {
        List<Node> nos = new ArrayList<>();
        nos.add(raiz);
        if (raiz instanceof Parent p) {
            for (Node filho : p.getChildrenUnmodifiable()) {
                nos.addAll(todos(filho));
            }
        }
        return nos;
    }
}
