package br.com.varesync.ui;

import java.util.regex.Pattern;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.util.Callback;

/**
 * Tabelas sem valor cortado (F21.5, pendência da parte A): a coluna de valor (dinheiro, quantidade, percentual, data
 * ou hora) tem pelo menos a largura do maior valor dela; se as colunas não cabem, a tabela rola de lado
 * ({@link Aparencia}). Texto longo pode sair com "…": parado o mouse sobre a célula, a dica mostra o texto inteiro.
 *
 * <p>O texto de cada linha sai da própria fábrica de células da coluna, como a tabela o desenha; a coluna é de valor
 * quando todos os textos não vazios dela têm cara de valor.
 */
public final class ColunasDeValor {

    private static final String INSTALADO = "varesync.colunas-de-valor";
    private static final String PENDENTE = "varesync.colunas-de-valor-pendente";
    /** Linhas lidas por coluna (as tabelas da tela trazem até algumas centenas; o cadastro de produtos, milhares). */
    static final int LINHAS_MAXIMAS = 2000;
    /** Folga além do texto: o recuo da célula dos dois lados e o arredondamento da fonte. */
    private static final double FOLGA = 18;

    /**
     * Valor: "R$ 1.234,56", "-R$ 3,20", "12,5%", "0,345 kg", "12 UN", "1.234", "06/10/2026", "06/10/2026 13:00",
     * "13:00", "▲ 2,50 p.p." e "—". O número pode ter sinal e uma unidade curta depois.
     */
    private static final Pattern VALOR = Pattern.compile(
            "^(—|[-−+]?\\s*(R\\$\\s?)?[-−]?\\d{1,3}(\\.\\d{3})*(,\\d+)?\\s?(%|p\\.p\\.|[A-Za-z]{1,3})?"
                    + "|\\d{2}/\\d{2}/\\d{4}( \\d{2}:\\d{2}(:\\d{2})?)?|\\d{2}:\\d{2}(:\\d{2})?|[-−]?\\d+(,\\d+)?)$");

    private ColunasDeValor() {
    }

    /** O texto tem cara de valor (número, dinheiro, percentual, quantidade, data, hora ou "—"). */
    static boolean pareceValor(String texto) {
        return texto != null && VALOR.matcher(texto.strip()).matches();
    }

    /** Liga a tabela: recalcula quando as linhas, as colunas ou a fonte mudam; põe a dica do texto cortado. */
    public static void instalar(TableView<?> tabela) {
        if (tabela.getProperties().containsKey(INSTALADO)) {
            agendar(tabela);
            return;
        }
        tabela.getProperties().put(INSTALADO, Boolean.TRUE);
        InvalidationListener mudou = o -> agendar(tabela);
        tabela.itemsProperty().addListener((obs, antes, depois) -> {
            if (antes != null) {
                antes.removeListener(mudou);
            }
            if (depois != null) {
                depois.addListener(mudou);
            }
            agendar(tabela);
        });
        if (tabela.getItems() != null) {
            tabela.getItems().addListener(mudou);
        }
        tabela.getVisibleLeafColumns().addListener(mudou);
        tabela.sceneProperty().addListener(mudou);
        tabela.addEventFilter(MouseEvent.MOUSE_MOVED, e -> dicaDoTextoCortado(tabela, e));
        agendar(tabela);
    }

    /** Uma vez por pulso do JavaFX, depois que a tabela já tem a folha aplicada. */
    private static void agendar(TableView<?> tabela) {
        if (tabela.getProperties().put(PENDENTE, Boolean.TRUE) == null) {
            Platform.runLater(() -> {
                tabela.getProperties().remove(PENDENTE);
                ajustar(tabela);
            });
        }
    }

    /**
     * Recalcula a largura mínima das colunas de valor da tabela: só aumenta (a {@link Aparencia} volta a mínima à da
     * tela quando a fonte muda e chama de novo).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void ajustar(TableView tabela) {
        if (tabela.getScene() == null || tabela.getItems() == null) {
            return;
        }
        Font fonte = fonteDasCelulas(tabela);
        for (Object objeto : tabela.getVisibleLeafColumns()) {
            TableColumn coluna = (TableColumn) objeto;
            if (coluna.minWidthProperty().isBound()) {
                continue;
            }
            double conteudo = Math.ceil(larguraDosValores(tabela, coluna, fonte));
            if (conteudo > coluna.getMinWidth()) {
                coluna.setMinWidth(conteudo);
                if (coluna.getPrefWidth() < conteudo && !coluna.prefWidthProperty().isBound()) {
                    coluna.setPrefWidth(conteudo);
                }
                if (coluna.getMaxWidth() < conteudo && !coluna.maxWidthProperty().isBound()) {
                    coluna.setMaxWidth(conteudo);
                }
            }
        }
        Aparencia.avaliarPolitica(tabela); // com as mínimas novas, a tabela pode precisar rolar de lado
    }

    /** Largura do maior texto com cara de valor; zero se a coluna tem algum texto que não é valor. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static double larguraDosValores(TableView tabela, TableColumn coluna, Font fonte) {
        Callback fabrica = coluna.getCellFactory();
        if (fabrica == null) {
            return 0;
        }
        TableCell celula;
        try {
            celula = (TableCell) fabrica.call(coluna);
        } catch (RuntimeException e) {
            return 0;
        }
        TableRow linha = new TableRow();
        linha.updateTableView(tabela);
        celula.updateTableView(tabela);
        celula.updateTableColumn(coluna);
        celula.updateTableRow(linha);
        Text medida = new Text();
        medida.setFont(fonte);
        double maior = 0;
        boolean algum = false;
        ObservableList<?> itens = tabela.getItems();
        int total = Math.min(itens.size(), LINHAS_MAXIMAS);
        try {
            for (int i = 0; i < total; i++) {
                linha.updateIndex(i);
                celula.updateIndex(i);
                String texto = celula.getText();
                if (celula.getGraphic() != null) {
                    return 0; // célula com controle ou desenho: fica como a tela fez
                }
                if (texto == null || texto.isBlank()) {
                    continue;
                }
                if (!pareceValor(texto)) {
                    return 0;
                }
                algum = true;
                medida.setText(texto.strip());
                maior = Math.max(maior, medida.getLayoutBounds().getWidth());
            }
        } catch (RuntimeException e) {
            return 0; // a fábrica da coluna depende de algo que só existe na tela: fica como está
        } finally {
            celula.updateIndex(-1);
            linha.updateIndex(-1);
        }
        return algum ? maior + FOLGA : 0;
    }

    /** A fonte das células da tabela (a da folha, na escala do usuário). */
    private static Font fonteDasCelulas(TableView<?> tabela) {
        for (Node no : tabela.lookupAll(".table-cell")) {
            if (no instanceof TableCell<?, ?> celula && celula.getFont() != null) {
                return celula.getFont();
            }
        }
        return Font.font(Font.getDefault().getFamily(), Font.getDefault().getSize() * Aparencia.escala());
    }

    /** Parado o mouse sobre uma célula com texto cortado ("…"), a dica da tabela mostra o texto inteiro. */
    private static void dicaDoTextoCortado(TableView<?> tabela, MouseEvent e) {
        TableCell<?, ?> celula = celulaEm(e.getPickResult().getIntersectedNode());
        String inteiro = celula == null ? null : celula.getText();
        boolean cortado = inteiro != null && !inteiro.isBlank() && cortado(celula, inteiro);
        Tooltip dica = tabela.getTooltip();
        if (cortado) {
            if (dica == null || !Boolean.TRUE.equals(dica.getProperties().get(ColunasDeValor.class))) {
                dica = new Tooltip();
                dica.getProperties().put(ColunasDeValor.class, Boolean.TRUE);
                dica.setWrapText(true);
                dica.setMaxWidth(560 * Aparencia.escala());
                dica.setShowDelay(javafx.util.Duration.millis(500));
                tabela.setTooltip(dica);
            }
            dica.setText(inteiro);
        } else if (dica != null && Boolean.TRUE.equals(dica.getProperties().get(ColunasDeValor.class))) {
            tabela.setTooltip(null);
        }
    }

    private static TableCell<?, ?> celulaEm(Node no) {
        for (Node atual = no; atual != null; atual = atual.getParent()) {
            if (atual instanceof TableCell<?, ?> celula) {
                return celula;
            }
            if (atual instanceof TableView<?>) {
                return null;
            }
        }
        return null;
    }

    /** O texto desenhado na célula difere do texto dela (o JavaFX trocou o fim por "…"). */
    static boolean cortado(TableCell<?, ?> celula, String inteiro) {
        for (Node filho : celula.getChildrenUnmodifiable()) {
            if (filho instanceof Text texto) {
                return !inteiro.equals(texto.getText());
            }
        }
        Insets recuo = celula.getInsets();
        return celula.getWidth() > 0 && celula.prefWidth(-1) > celula.getWidth() + recuo.getLeft() + 0.5;
    }
}
