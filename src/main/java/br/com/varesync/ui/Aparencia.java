package br.com.varesync.ui;

import br.com.varesync.model.Preferencias;
import br.com.varesync.model.TamanhoFonte;
import br.com.varesync.model.Tema;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import javafx.beans.InvalidationListener;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.css.StyleOrigin;
import javafx.css.StyleableProperty;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Cell;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumnBase;
import javafx.scene.control.TableView;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.PopupWindow;
import javafx.stage.Window;

/**
 * Tema e tamanho da fonte em todas as janelas do sistema (F21.1, F21.2), sem que cada tela precise lembrar: como o
 * {@link Icone}, observa as janelas que abrem.
 *
 * <ul>
 *   <li><b>Tema escuro:</b> a classe {@code tema-escuro} na raiz de cada cena troca as variáveis de cor da folha.</li>
 *   <li><b>Fonte de 115% e 130%:</b> uma folha a mais ({@link FolhaFonte}) multiplica os tamanhos em px da folha do
 *   sistema, a começar pela fonte da raiz; e as larguras fixas das telas (FXML e código: campos, listas, colunas de
 *   tabela, colunas de grade) são multiplicadas pela mesma escala, para o texto maior caber nelas. A largura original
 *   fica guardada no nó: trocar a fonte de novo parte dela.</li>
 * </ul>
 *
 * A tela de login usa o padrão (claro, 100%): o {@code App} aplica as preferências do usuário só depois de ele entrar.
 */
public final class Aparencia {

    public static final String CLASSE_ESCURO = "tema-escuro";
    /** Classe de estilo de um nó cuja largura a tela controla sozinha (não é multiplicada pela escala da fonte). */
    public static final String LARGURA_FIXA = "largura-fixa";
    private static final String LARGURAS = "varesync.larguras-originais";
    private static final String ROTULO = "varesync.rotulo-ajustado";
    /** Acima desta largura (na fonte de 100%), o rótulo quebra a linha em vez de exigir a largura toda. */
    private static final double LARGURA_ROTULO_LONGO = 360;
    private static final Set<String> CONTROLES_COMPOSTOS = Set.of("table-view", "tree-table-view", "list-view",
            "combo-box-base", "tab-header-area", "date-picker", "spinner", "text-input", "scroll-bar", "menu-bar",
            "split-menu-button", "menu-button", "choice-box");
    /** Largura máxima padrão de uma coluna de tabela no JavaFX: sem limite de verdade, não se escala. */
    private static final double MAXIMO_COLUNA = 5000;
    private static final double TABELA_MINIMA = 120;
    /** Espaço da barra de rolagem vertical da tabela. */
    private static final double BARRA_VERTICAL = 16;
    private static final String POLITICA = "varesync.politica-colunas";
    /** {@link ColumnConstraints} não tem mapa de propriedades: as originais ficam aqui, sem prender a coluna na memória. */
    private static final Map<ColumnConstraints, double[]> PROPRIEDADES_GRADE = new WeakHashMap<>();

    private static Preferencias atual = Preferencias.PADRAO;
    private static String urlFolha;
    private static String css;
    // Cache que se preenche durante o uso, não uma constante: o nome segue o das outras variáveis da classe.
    @SuppressWarnings("checkstyle:ConstantName")
    private static final Map<TamanhoFonte, String> folhas = new EnumMap<>(TamanhoFonte.class);

    private Aparencia() {
    }

    /** Aplica a aparência atual a toda janela que abrir daqui em diante (e a cada troca de cena da janela). */
    public static void instalar() {
        Window.getWindows().addListener((ListChangeListener<Window>) mudanca -> {
            while (mudanca.next()) {
                for (Window janela : mudanca.getAddedSubList()) {
                    observar(janela);
                    aplicar(janela.getScene());
                }
            }
        });
        Window.getWindows().forEach(janela -> {
            observar(janela);
            aplicar(janela.getScene());
        });
    }

    /** Troca a aparência de todas as janelas abertas (no login, no log off e em "Minhas preferências"). */
    public static void aplicar(Preferencias preferencias) {
        atual = preferencias == null ? Preferencias.PADRAO : preferencias;
        for (Window janela : new ArrayList<>(Window.getWindows())) {
            aplicar(janela.getScene());
        }
    }

    public static Preferencias atual() {
        return atual;
    }

    /** Escala das larguras e tamanhos: 1, 1,15 ou 1,3. */
    public static double escala() {
        return atual.fonte().escala().doubleValue();
    }

    /**
     * Largura, na fonte de 100%, que o título de uma coluna de tabela precisa para não ser cortado (o título é em
     * negrito e tem a seta da ordenação ao lado). {@link #definirLargura} aplica a escala.
     */
    public static double larguraDoTitulo(String titulo) {
        Text texto = new Text(titulo);
        texto.setFont(Font.font(Font.getDefault().getFamily(), FontWeight.BOLD,
                FolhaFonte.fonteDaRaiz(css()).doubleValue()));
        return Math.ceil(texto.getLayoutBounds().getWidth()) + 24;
    }

    /**
     * Larguras fixas de uma tela que vai entrar numa janela já aberta (o conteúdo da janela principal): multiplica as
     * larguras originais pela escala atual. Chamar de novo não acumula.
     */
    public static void ajustar(Node raiz) {
        if (raiz != null) {
            ajustarLarguras(raiz, escala());
            if (raiz.getScene() != null && raiz instanceof Parent pai) {
                ajustarRotulos(pai);
            }
        }
    }

    /**
     * Largura de uma coluna criada depois que a tela abriu (relatório genérico): grava a largura original e aplica a
     * escala atual.
     */
    public static void definirLargura(TableColumnBase<?, ?> coluna, double preferida, double minima) {
        coluna.getProperties().put(LARGURAS, new double[] {preferida, minima, coluna.getMaxWidth()});
        coluna.setPrefWidth(preferida * escala());
        coluna.setMinWidth(minima * escala());
    }

    /**
     * Conteúdo de uma janela que o sistema não observa (popup, como a {@link Notificacao}): a folha do sistema, a da
     * fonte e o tema atuais na própria raiz, que ganha a classe {@code root} para ter as variáveis de cor.
     */
    public static void estilizarAvulso(Parent raiz, String classe) {
        raiz.getStyleClass().addAll("root", classe);
        if (atual.tema() == Tema.ESCURO) {
            raiz.getStyleClass().add(CLASSE_ESCURO);
        }
        raiz.getStylesheets().add(urlFolha());
        String folha = folha(atual.fonte());
        if (folha != null) {
            raiz.getStylesheets().add(folha);
        }
    }

    // ---- janelas e cenas ----

    private static void observar(Window janela) {
        if (janela instanceof PopupWindow || janela.getProperties().containsKey(Aparencia.class)) {
            return;
        }
        janela.getProperties().put(Aparencia.class, Boolean.TRUE);
        janela.sceneProperty().addListener((obs, anterior, nova) -> aplicar(nova));
    }

    private static void aplicar(Scene cena) {
        if (cena == null || cena.getRoot() == null || cena.getWindow() instanceof PopupWindow) {
            return;
        }
        Parent raiz = cena.getRoot();
        ObservableList<String> classes = raiz.getStyleClass();
        boolean escuro = atual.tema() == Tema.ESCURO;
        if (escuro && !classes.contains(CLASSE_ESCURO)) {
            classes.add(CLASSE_ESCURO);
        } else if (!escuro) {
            classes.remove(CLASSE_ESCURO);
        }

        // A folha da fonte entra na mesma lista da folha do sistema, logo depois dela (mesmos seletores: vale a última).
        String sistema = urlFolha();
        ObservableList<String> lista;
        if (raiz.getStylesheets().contains(sistema)) {
            lista = raiz.getStylesheets();
        } else {
            lista = cena.getStylesheets();
            if (!lista.contains(sistema)) {
                lista.add(sistema); // janela que não pôs a folha do sistema (ex.: um diálogo padrão)
            }
        }
        raiz.getStylesheets().removeAll(folhas.values());
        cena.getStylesheets().removeAll(folhas.values());
        String folha = folha(atual.fonte());
        if (folha != null) {
            lista.add(folha);
        }
        ajustarLarguras(raiz, escala());
        ajustarRotulos(raiz);
    }

    private static String folha(TamanhoFonte fonte) {
        if (fonte == TamanhoFonte.NORMAL) {
            return null;
        }
        return folhas.computeIfAbsent(fonte, f -> "data:text/css;base64," + Base64.getEncoder().encodeToString(
                FolhaFonte.gerar(css(), f.escala()).getBytes(StandardCharsets.UTF_8)));
    }

    private static String urlFolha() {
        if (urlFolha == null) {
            URL url = Aparencia.class.getResource(Dialogos.CSS);
            urlFolha = url == null ? Dialogos.CSS : url.toExternalForm();
        }
        return urlFolha;
    }

    private static String css() {
        if (css == null) {
            try (InputStream entrada = Aparencia.class.getResourceAsStream(Dialogos.CSS)) {
                css = entrada == null ? "" : new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("Falha ao ler " + Dialogos.CSS, e);
            }
        }
        return css;
    }

    // ---- rótulos e botões: nada cortado com "…" (F21.2) ----

    /**
     * Com a folha já aplicada: botão, caixa de marcar e rótulo curto não encolhem abaixo do texto (a largura que ele
     * pede vira a mínima); rótulo longo (dica de atalhos, descrição) quebra a linha em vez de ser cortado. Rótulos com
     * largura mínima dada pela tela, que já quebram a linha ou que fazem parte de um controle (título de coluna, célula,
     * aba, caixa de escolha) ficam como estão.
     */
    private static void ajustarRotulos(Parent raiz) {
        raiz.applyCss();
        double limiteLongo = LARGURA_ROTULO_LONGO * escala();
        for (Node no : descendentes(raiz)) {
            if (!(no instanceof Labeled rotulo) || no instanceof Cell<?> || rotulo.getText() == null
                    || rotulo.getText().isBlank() || interno(no)) {
                continue;
            }
            boolean nosso = rotulo.getProperties().containsKey(ROTULO);
            if (!nosso && (rotulo.isWrapText() || rotulo.getMinWidth() != Region.USE_COMPUTED_SIZE)) {
                continue; // a tela já decidiu
            }
            if (nosso && rotulo.isWrapText()) {
                continue; // já quebra a linha
            }
            rotulo.getProperties().put(ROTULO, Boolean.TRUE);
            double preferida = rotulo.prefWidth(-1);
            boolean longo = (rotulo instanceof Label || rotulo instanceof Hyperlink) && preferida > limiteLongo;
            if (longo) {
                rotulo.setMinWidth(Region.USE_COMPUTED_SIZE);
                rotulo.setWrapText(true);
                if (rotulo.getMinHeight() == Region.USE_COMPUTED_SIZE) {
                    rotulo.setMinHeight(Region.USE_PREF_SIZE);
                }
            } else {
                rotulo.setMinWidth(Math.ceil(preferida));
            }
        }
    }

    /** Rótulo que faz parte de um controle composto (tabela, lista, aba, caixa de escolha, data, rolagem). */
    private static boolean interno(Node no) {
        for (Node pai = no.getParent(); pai != null; pai = pai.getParent()) {
            for (String classe : pai.getStyleClass()) {
                if (CONTROLES_COMPOSTOS.contains(classe)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Node> descendentes(Parent raiz) {
        List<Node> nos = new ArrayList<>();
        List<Parent> pendentes = new ArrayList<>(List.of(raiz));
        while (!pendentes.isEmpty()) {
            Parent pai = pendentes.remove(pendentes.size() - 1);
            for (Node filho : pai.getChildrenUnmodifiable()) {
                nos.add(filho);
                if (filho instanceof Parent p) {
                    pendentes.add(p);
                }
            }
        }
        return nos;
    }


    // ---- telas divididas: lista em cima, detalhe embaixo (F21.5) ----

    /** Linhas que a lista de cima mostra, no mínimo, em qualquer fonte. */
    public static final int LINHAS_DA_LISTA = 5;
    private static final String ALTURA_ORIGINAL = "varesync.altura-minima-original";

    /**
     * Na tela dividida, a primeira tabela do item de cima mostra pelo menos {@link #LINHAS_DA_LISTA} linhas e o título
     * (a linha da tabela tem 2em, como no modena); os itens de baixo podem encolher até zero, para a divisória descer
     * até onde a pessoa quiser e a lista nunca ficar com duas linhas na fonte de 130%.
     */
    private static void listaComCincoLinhas(SplitPane divisao, double escala) {
        if (divisao.getItems().isEmpty()) {
            return;
        }
        TableView<?> lista = primeiraTabela(divisao.getItems().get(0));
        if (lista == null) {
            return;
        }
        Object original = lista.getProperties().computeIfAbsent(ALTURA_ORIGINAL, k -> lista.getMinHeight());
        double linha = 2 * FolhaFonte.fonteDaRaiz(css()).doubleValue() * escala;
        double minima = Math.ceil(linha * (LINHAS_DA_LISTA + 1) + 4);
        lista.setMinHeight(Math.max(minima, (Double) original));
        for (int i = 1; i < divisao.getItems().size(); i++) {
            if (divisao.getItems().get(i) instanceof Region detalhe) {
                detalhe.setMinHeight(0);
            }
        }
    }

    private static TableView<?> primeiraTabela(Node no) {
        if (no instanceof TableView<?> tabela) {
            return tabela;
        }
        if (no instanceof SplitPane || no instanceof TabPane) {
            return null; // a lista fica no primeiro nível do item de cima
        }
        if (no instanceof Parent pai) {
            for (Node filho : pai.getChildrenUnmodifiable()) {
                TableView<?> achada = primeiraTabela(filho);
                if (achada != null) {
                    return achada;
                }
            }
        }
        return null;
    }
    // ---- larguras fixas ----

    private static void ajustarLarguras(Node no, double escala) {
        if (no instanceof Region regiao) {
            escalar(regiao, escala);
        }
        if (no instanceof SplitPane divisao && divisao.getOrientation() == javafx.geometry.Orientation.VERTICAL) {
            listaComCincoLinhas(divisao, escala);
        }
        if (no instanceof TableView<?> tabela) {
            tabela.getColumns().forEach(c -> escalar(c, escala));
            podeEncolher(tabela);
        } else if (no instanceof TreeTableView<?> arvore) {
            arvore.getColumns().forEach(c -> escalar(c, escala));
            podeEncolher(arvore);
        }
        if (no instanceof GridPane grade) {
            for (ColumnConstraints coluna : grade.getColumnConstraints()) {
                escalar(coluna, escala);
            }
        }
        List<Node> filhos = new ArrayList<>();
        if (no instanceof TabPane abas) {
            for (Tab aba : abas.getTabs()) {
                filhos.add(aba.getContent());
            }
        } else if (no instanceof ScrollPane rolagem) {
            filhos.add(rolagem.getContent());
        } else if (no instanceof SplitPane divisao) {
            filhos.addAll(divisao.getItems());
        } else if (no instanceof TitledPane titulo) {
            filhos.add(titulo.getContent());
        }
        if (no instanceof Parent pai) {
            filhos.addAll(pai.getChildrenUnmodifiable());
        }
        for (Node filho : filhos) {
            if (filho != null) {
                ajustarLarguras(filho, escala);
            }
        }
    }

    /** Só as larguras dadas pela tela (FXML ou código); as que vêm da folha já crescem pela {@link FolhaFonte}. */
    private static void escalar(Region regiao, double escala) {
        if (regiao.getStyleClass().contains(LARGURA_FIXA)) {
            return;
        }
        double[] originais = (double[]) regiao.getProperties().get(LARGURAS);
        if (originais == null) {
            originais = new double[] {
                    dadaPelaTela(regiao, regiao.prefWidthProperty(), regiao.getPrefWidth()),
                    dadaPelaTela(regiao, regiao.minWidthProperty(), regiao.getMinWidth()),
                    dadaPelaTela(regiao, regiao.maxWidthProperty(), regiao.getMaxWidth())};
            regiao.getProperties().put(LARGURAS, originais);
        }
        if (fixa(originais[0]) && !regiao.prefWidthProperty().isBound()) {
            regiao.setPrefWidth(originais[0] * escala);
        }
        if (fixa(originais[1]) && !regiao.minWidthProperty().isBound()) {
            regiao.setMinWidth(originais[1] * escala);
        } else if (regiao instanceof ComboBoxBase<?> && fixa(originais[0]) && !regiao.minWidthProperty().isBound()) {
            regiao.setMinWidth(originais[0] * escala); // caixa de escolha e data: a largura fixa vira a mínima
        }
        if (fixa(originais[2]) && !regiao.maxWidthProperty().isBound()) {
            regiao.setMaxWidth(originais[2] * escala);
        }
    }

    private static double dadaPelaTela(Region regiao, Object propriedade, double valor) {
        if (propriedade instanceof StyleableProperty<?> estilizavel) {
            StyleOrigin origem = estilizavel.getStyleOrigin();
            if (origem != null && origem != StyleOrigin.USER) {
                return Region.USE_COMPUTED_SIZE; // veio de uma folha de estilo
            }
        }
        return valor;
    }

    private static void escalar(TableColumnBase<?, ?> coluna, double escala) {
        double[] originais = (double[]) coluna.getProperties().get(LARGURAS);
        if (originais == null) {
            originais = new double[] {coluna.getPrefWidth(), coluna.getMinWidth(), coluna.getMaxWidth()};
            coluna.getProperties().put(LARGURAS, originais);
        }
        if (!coluna.prefWidthProperty().isBound()) {
            coluna.setPrefWidth(originais[0] * escala);
        }
        // O título da coluna nunca é cortado: a coluna não encolhe abaixo dele (se não couber, a tabela rola).
        double titulo = coluna.getText() == null || coluna.getText().isBlank() || !coluna.getColumns().isEmpty()
                ? 0 : larguraDoTitulo(coluna.getText());
        double minima = Math.max(fixa(originais[1]) ? originais[1] : 0, titulo);
        if (minima > 0 && !coluna.minWidthProperty().isBound()) {
            coluna.setMinWidth(minima * escala);
            if (coluna.getPrefWidth() < minima * escala && !coluna.prefWidthProperty().isBound()) {
                coluna.setPrefWidth(minima * escala);
            }
        }
        if (originais[2] < MAXIMO_COLUNA && !coluna.maxWidthProperty().isBound()) {
            coluna.setMaxWidth(Math.max(originais[2] * escala, coluna.getMinWidth()));
        }
        coluna.getColumns().forEach(c -> escalar(c, escala));
    }

    private static void escalar(ColumnConstraints coluna, double escala) {
        double[] originais = PROPRIEDADES_GRADE.get(coluna);
        if (originais == null) {
            originais = new double[] {coluna.getPrefWidth(), coluna.getMinWidth(), coluna.getMaxWidth()};
            PROPRIEDADES_GRADE.put(coluna, originais);
        }
        if (fixa(originais[0])) {
            coluna.setPrefWidth(originais[0] * escala);
        }
        if (fixa(originais[1])) {
            coluna.setMinWidth(originais[1] * escala);
        }
        if (fixa(originais[2])) {
            coluna.setMaxWidth(Math.max(originais[2] * escala, coluna.getMinWidth()));
        }
    }

    /**
     * A tabela encolhe e rola, em vez de exigir a soma das larguras mínimas das colunas (que, com os títulos inteiros,
     * alargaria a tela inteira além da janela).
     */
    private static void podeEncolher(Region tabela) {
        if (tabela.getMinWidth() == Region.USE_COMPUTED_SIZE) {
            tabela.setMinWidth(TABELA_MINIMA);
        }
        if (tabela instanceof TableView<?> tv) {
            rolarQuandoNaoCouber(tv);
            ColunasDeValor.instalar(tv); // F21.5: coluna de valor com a largura do maior valor
        }
    }

    /**
     * Com a política "constrained" o JavaFX nunca mostra a rolagem de lado: as colunas que passam da borda ficariam
     * fora de alcance. Quando as larguras mínimas (com o título inteiro) não cabem, a tabela passa à política sem
     * restrição, que rola; quando voltam a caber, volta à política da tela.
     */
    private static void rolarQuandoNaoCouber(TableView<?> tabela) {
        if (!tabela.getProperties().containsKey(POLITICA)) {
            tabela.getProperties().put(POLITICA, tabela.getColumnResizePolicy());
            InvalidationListener reavaliar = o -> avaliarPolitica(tabela);
            tabela.widthProperty().addListener(reavaliar);
            tabela.getVisibleLeafColumns().addListener(reavaliar);
        }
        avaliarPolitica(tabela);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void avaliarPolitica(TableView tabela) {
        Object original = tabela.getProperties().get(POLITICA);
        if (original == null || original == TableView.UNCONSTRAINED_RESIZE_POLICY || tabela.getWidth() <= 0) {
            return;
        }
        double minimas = 0;
        for (Object coluna : tabela.getVisibleLeafColumns()) {
            minimas += ((TableColumnBase<?, ?>) coluna).getMinWidth();
        }
        double util = tabela.getWidth() - tabela.snappedLeftInset() - tabela.snappedRightInset() - BARRA_VERTICAL;
        Object politica = minimas > util ? TableView.UNCONSTRAINED_RESIZE_POLICY : original;
        if (tabela.getColumnResizePolicy() != politica) {
            tabela.setColumnResizePolicy((javafx.util.Callback) politica);
        }
    }

    private static boolean fixa(double largura) {
        return largura > 0 && largura < Double.MAX_VALUE && !Double.isInfinite(largura);
    }
}
