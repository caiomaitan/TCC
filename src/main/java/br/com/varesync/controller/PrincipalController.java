package br.com.varesync.controller;

import br.com.varesync.config.AppConfig;
import br.com.varesync.model.Permissao;
import br.com.varesync.service.AcessoService;
import br.com.varesync.service.EstoqueService;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.DialogoSenha;
import br.com.varesync.ui.Tarefas;
import br.com.varesync.ui.TratadorExcecoes;
import br.com.varesync.util.Formatos;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Callback;
import javafx.util.Duration;

/**
 * Janela principal: menu lateral só com o permitido ao usuário, área de conteúdo e barra de status.
 * Rodapé do menu: Alterar senha, Log off e Sair.
 */
public class PrincipalController {

    private static final String SEM_INFORMACAO = "—";
    private static final String ITEM_SELECIONADO = "selecionado";

    private final AppConfig config;
    private final Clock clock;
    private final AcessoService acessoService;
    private final EstoqueService estoqueService;
    private final Navegacao navegacao;
    private final Sessao sessao;
    private final Callback<Class<?>, Object> fabricaControllers;
    private final Map<ItemMenu, Button> botoes = new EnumMap<>(ItemMenu.class);
    private Timeline relogio;

    @FXML private Label nomeLoja;
    @FXML private VBox menu;
    @FXML private StackPane conteudo;
    @FXML private Label statusUsuario;
    @FXML private Label statusTerminal;
    @FXML private Label statusCaixa;
    @FXML private Label statusDataHora;
    @FXML private Label statusConexao;

    public PrincipalController(AppConfig config, Clock clock, AcessoService acessoService,
                               EstoqueService estoqueService, Navegacao navegacao, Sessao sessao,
                               Callback<Class<?>, Object> fabricaControllers) {
        this.config = config;
        this.clock = clock;
        this.acessoService = acessoService;
        this.estoqueService = estoqueService;
        this.navegacao = navegacao;
        this.sessao = sessao;
        this.fabricaControllers = fabricaControllers;
    }

    @FXML
    private void initialize() {
        nomeLoja.setText(acessoService.nomeEmpresa());
        for (ItemMenu item : ItemMenu.visiveis(sessao)) {
            Button botao = new Button(item.rotulo());
            botao.getStyleClass().add("item-menu");
            botao.setMaxWidth(Double.MAX_VALUE);
            botao.setOnAction(e -> abrir(item));
            botoes.put(item, botao);
            menu.getChildren().add(botao);
        }

        statusUsuario.setText("Usuário: " + sessao.usuario().descricao());
        statusTerminal.setText("Terminal: " + config.terminalId());
        statusCaixa.setText("Caixa: " + SEM_INFORMACAO);
        statusConexao.setText("Banco: conectado (" + descreverBanco(config.url()) + ")");
        atualizarDataHora();
        relogio = new Timeline(new KeyFrame(Duration.seconds(1), e -> atualizarDataHora()));
        relogio.setCycleCount(Animation.INDEFINITE);
        relogio.play();

        abrir(ItemMenu.PAINEL);
    }

    public void encerrar() {
        if (relogio != null) {
            relogio.stop();
        }
    }

    private void abrir(ItemMenu item) {
        botoes.values().forEach(b -> b.getStyleClass().remove(ITEM_SELECIONADO));
        Button botao = botoes.get(item);
        if (botao != null) {
            botao.getStyleClass().add(ITEM_SELECIONADO);
        }
        Node tela;
        if (item == ItemMenu.PAINEL) {
            VBox painel = aviso("Olá, " + sessao.usuario().nome() + "!", "Use o menu ao lado para acessar as rotinas.");
            carregarAlertas(painel);
            tela = painel;
        } else if (item.fxml() == null) {
            tela = aviso(item.rotulo(), "Esta rotina ainda está em construção.");
        } else {
            tela = carregar(item.fxml()).getRoot();
        }
        conteudo.getChildren().setAll(tela);
    }

    private FXMLLoader carregar(String fxml) {
        FXMLLoader loader = new FXMLLoader(PrincipalController.class.getResource(fxml));
        loader.setControllerFactory(fabricaControllers);
        try {
            loader.<Parent>load();
            return loader;
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao carregar " + fxml, e);
        }
    }

    private static VBox aviso(String titulo, String texto) {
        Label rotuloTitulo = new Label(titulo);
        rotuloTitulo.getStyleClass().add("titulo");
        Label rotuloTexto = new Label(texto);
        rotuloTexto.getStyleClass().add("subtitulo");
        VBox caixa = new VBox(8, rotuloTitulo, rotuloTexto);
        caixa.setAlignment(Pos.CENTER);
        return caixa;
    }

    /**
     * Alerta de reposição (6.4): produtos abaixo do mínimo, para quem tem a rotina Estoque. Clicar abre o Estoque
     * já filtrado. A contagem roda em {@link Task}; os demais alertas e indicadores do painel são da Fase 6.
     */
    private void carregarAlertas(VBox painel) {
        if (!sessao.pode(Permissao.ESTOQUE)) {
            return;
        }
        Task<Integer> tarefa = new Task<>() {
            @Override
            protected Integer call() {
                return estoqueService.contarAbaixoDoMinimo(sessao);
            }
        };
        tarefa.setOnSucceeded(e -> {
            int abaixo = tarefa.getValue();
            if (abaixo > 0) {
                Hyperlink alerta = new Hyperlink((abaixo == 1 ? "1 produto está" : abaixo + " produtos estão")
                        + " abaixo do estoque mínimo — ver no Estoque");
                alerta.getStyleClass().add("alerta-painel");
                alerta.setOnAction(a -> abrirEstoqueAbaixoDoMinimo());
                painel.getChildren().add(alerta);
            }
        });
        tarefa.setOnFailed(e -> TratadorExcecoes.mensagem(tarefa.getException()));
        Tarefas.executar("alertas-painel", tarefa);
    }

    private void abrirEstoqueAbaixoDoMinimo() {
        botoes.values().forEach(b -> b.getStyleClass().remove(ITEM_SELECIONADO));
        Button botao = botoes.get(ItemMenu.ESTOQUE);
        if (botao != null) {
            botao.getStyleClass().add(ITEM_SELECIONADO);
        }
        FXMLLoader loader = carregar(ItemMenu.ESTOQUE.fxml());
        loader.<EstoqueController>getController().mostrarAbaixoDoMinimo();
        conteudo.getChildren().setAll(loader.<Parent>getRoot());
    }

    @FXML
    private void alterarSenha() {
        boolean alterada = DialogoSenha.alterarPropria(
                (atual, nova, confirmacao) -> acessoService.trocarSenha(sessao, atual, nova, confirmacao));
        if (alterada) {
            Dialogos.alerta(Alert.AlertType.INFORMATION, "Senha alterada.");
        }
    }

    @FXML
    private void logoff() {
        navegacao.logoff();
    }

    @FXML
    private void sair() {
        // O aviso de caixa aberto neste terminal entra com o caixa (Fase 4).
        if (Dialogos.confirmar("Deseja sair do VareSync?")) {
            navegacao.sair();
        }
    }

    private void atualizarDataHora() {
        statusDataHora.setText(Formatos.dataHora(OffsetDateTime.now(clock)));
    }

    /** "jdbc:postgresql://localhost:5432/varesync?x=y" → "localhost:5432/varesync". */
    static String descreverBanco(String url) {
        String semPrefixo = url.replaceFirst("^jdbc:postgresql://", "");
        int parametros = semPrefixo.indexOf('?');
        return parametros >= 0 ? semPrefixo.substring(0, parametros) : semPrefixo;
    }
}
