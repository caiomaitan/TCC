package br.com.varesync.ui;

import java.util.List;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Labeled;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;

/**
 * Cartões do painel lado a lado, com a largura dividida por igual. Os que não cabem na largura da tela (fonte de 115%
 * ou 130%, F21.2) descem para a linha de baixo, em vez de passar da borda ou de cortar o valor.
 */
public final class LinhaDeCartoes {

    private LinhaDeCartoes() {
    }

    public static void distribuir(FlowPane linha) {
        Runnable recalcular = () -> recalcular(linha);
        linha.widthProperty().addListener((obs, antes, agora) -> recalcular.run());
        linha.getChildren().addListener((ListChangeListener<Node>) mudanca -> Platform.runLater(recalcular));
    }

    private static void recalcular(FlowPane linha) {
        List<Node> cartoes = linha.getChildren();
        double largura = linha.getWidth() - linha.getInsets().getLeft() - linha.getInsets().getRight();
        if (cartoes.isEmpty() || largura <= 0) {
            return;
        }
        linha.applyCss();
        double espaco = linha.getHgap();
        double minimo = cartoes.stream().mapToDouble(LinhaDeCartoes::conteudo).max().orElse(0);
        int porLinha = (int) Math.max(1, Math.min(cartoes.size(), Math.floor((largura + espaco) / (minimo + espaco))));
        double cada = Math.floor((largura - espaco * (porLinha - 1)) / porLinha);
        for (Node cartao : cartoes) {
            if (cartao instanceof Region r) {
                r.setPrefWidth(cada);
            }
        }
    }

    /** Largura que o cartão precisa: a mínima da folha ou a do texto mais largo (os textos que quebram não contam). */
    private static double conteudo(Node cartao) {
        if (!(cartao instanceof Region r)) {
            return cartao.prefWidth(-1);
        }
        double textos = r.getChildrenUnmodifiable().stream()
                .filter(n -> !(n instanceof Labeled l && l.isWrapText()))
                .mapToDouble(n -> n.prefWidth(-1)).max().orElse(0);
        double minima = r.getMinWidth() > 0 ? r.getMinWidth() : 0;
        return Math.max(minima, textos + r.getInsets().getLeft() + r.getInsets().getRight());
    }
}
