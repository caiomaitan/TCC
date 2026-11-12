package br.com.varesync.controller;

import br.com.varesync.config.AppConfig;
import br.com.varesync.model.CaixaSessao;
import br.com.varesync.model.ItemBusca;
import br.com.varesync.model.PrimeirosPassos;
import br.com.varesync.model.Permissao;
import br.com.varesync.service.BuscaGlobalService;
import br.com.varesync.ui.Atalhos;
import br.com.varesync.ui.Atalhos.Tecla;
import br.com.varesync.ui.PainelAjuda;
import br.com.varesync.ui.Notificacao;
import java.util.List;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import br.com.varesync.report.FechamentoCaixaPdf;
import br.com.varesync.service.AcessoService;
import br.com.varesync.service.BackupService;
import br.com.varesync.service.CaixaService;
import br.com.varesync.service.PreferenciaService;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.Aparencia;
import br.com.varesync.ui.DialogoPreferencias;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.DialogoSenha;
import br.com.varesync.ui.Tarefas;
import br.com.varesync.ui.TratadorExcecoes;
import br.com.varesync.util.Formatos;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Callback;
import javafx.util.Duration;

/**
 * Janela principal: menu lateral só com o permitido ao usuário, área de conteúdo e barra de status.
 * Rodapé do menu: Alterar senha, Log off e Sair.
 */
public class PrincipalController {

    private static final String SEM_INFORMACAO = "—";
    private static final String ITEM_SELECIONADO = "selecionado";
    private static final double LARGURA_MINIMA_PDV = 1280;
    private static final double ALTURA_MINIMA_PDV = 720;

    private final AppConfig config;
    private final Clock clock;
    private final AcessoService acessoService;
    private final PreferenciaService preferenciaService;
    private final CaixaService caixaService;
    private final BackupService backupService;
    private final FechamentoCaixaPdf fechamentoPdf;
    private final Navegacao navegacao;
    private final BuscaGlobalService buscaService;
    private final AcoesBusca acoesBusca;
    private PainelAjuda painelAjuda;
    /** Trocada quando o acesso do usuário muda em outro computador (F10.1). */
    private Sessao sessao;
    private final Callback<Class<?>, Object> fabricaControllers;
    private final Map<ItemMenu, Button> botoes = new EnumMap<>(ItemMenu.class);
    private ItemMenu itemAtual;
    private Timeline relogio;
    /** PDV aberto (janela modal), para abandonar a venda no log off forçado ou na troca de usuário. */
    private PdvController pdvAberto;
    private boolean encerrado;
    /** "Primeiros passos…" da Ajuda: o próximo Painel mostra o assistente mesmo com tudo feito (F21.4). */
    private boolean reabrirPassos;

    @FXML private BorderPane raiz;
    @FXML private Label nomeLoja;
    @FXML private VBox menu;
    @FXML private StackPane conteudo;
    @FXML private Label statusUsuario;
    @FXML private Label statusTerminal;
    @FXML private Label statusCaixa;
    @FXML private Label statusDataHora;
    @FXML private Label statusConexao;

    public PrincipalController(AppConfig config, Clock clock, AcessoService acessoService,
                               PreferenciaService preferenciaService, CaixaService caixaService, BackupService backupService, FechamentoCaixaPdf fechamentoPdf,
                               Navegacao navegacao, Sessao sessao,
                               Callback<Class<?>, Object> fabricaControllers, BuscaGlobalService buscaService,
                               AcoesBusca acoesBusca) {
        this.buscaService = buscaService;
        this.acoesBusca = acoesBusca;
        this.config = config;
        this.clock = clock;
        this.acessoService = acessoService;
        this.preferenciaService = preferenciaService;
        this.caixaService = caixaService;
        this.backupService = backupService;
        this.fechamentoPdf = fechamentoPdf;
        this.navegacao = navegacao;
        this.sessao = sessao;
        this.fabricaControllers = fabricaControllers;
    }

    @FXML
    private void initialize() {
        nomeLoja.setText(acessoService.nomeEmpresa());
        montarMenu();

        statusUsuario.setText("Usuário: " + sessao.usuario().descricao());
        statusTerminal.setText("Terminal: " + config.terminalId());
        atualizarCaixa();
        statusConexao.setText("Banco: conectado (" + descreverBanco(config.url()) + ")");
        atualizarDataHora();
        relogio = new Timeline(new KeyFrame(Duration.seconds(1), e -> atualizarDataHora()));
        relogio.setCycleCount(Animation.INDEFINITE);
        relogio.play();

        // Valem em todas as telas da janela principal (F21.3); o PDV, em janela própria, tem os dele.
        Atalhos.de(raiz)
                .em(Tecla.de(KeyCode.F1), "Ajuda desta tela (abre e fecha)", this::alternarAjuda)
                .em(Tecla.ctrl(KeyCode.K), "Buscar produto, cliente, venda, fornecedor ou tela", this::buscar);

        abrir(ItemMenu.PAINEL);
        // Foco inicial no primeiro item do menu (o ScrollPane do menu não entra na ordem do Tab).
        Platform.runLater(botoes.get(ItemMenu.PAINEL)::requestFocus);
    }

    /** Itens do menu lateral: só as rotinas que a sessão permite; o item aberto continua marcado. */
    private void montarMenu() {
        botoes.clear();
        menu.getChildren().clear();
        for (ItemMenu item : ItemMenu.visiveis(sessao)) {
            Button botao = new Button(item.rotulo());
            botao.getStyleClass().add("item-menu");
            botao.setMaxWidth(Double.MAX_VALUE);
            botao.setOnAction(e -> abrir(item));
            if (item == itemAtual) {
                botao.getStyleClass().add(ITEM_SELECIONADO);
            }
            botoes.put(item, botao);
            menu.getChildren().add(botao);
        }
    }

    /**
     * O acesso do usuário mudou em outro computador (F10.1): a sessão passa a ser esta, com as permissões atuais, e o
     * menu é refeito. A tela aberta continua; se ela deixou de ser permitida, a próxima ação é recusada pelo service.
     */
    public void trocarSessao(Sessao nova) {
        sessao = nova;
        montarMenu();
        statusUsuario.setText("Usuário: " + sessao.usuario().descricao());
    }

    /** Log off forçado (acesso encerrado pelo supervisor, F10.1): abandona a venda do PDV aberto, sem perguntar. */
    public void encerrarSessao() {
        if (pdvAberto != null) {
            pdvAberto.encerrarSessao();
        }
    }

    /**
     * "Trocar usuário" na tela bloqueada (F10.2): com venda em andamento no PDV, pergunta (por cima de {@code dono})
     * se abandona.
     *
     * @return false se a pessoa preferiu continuar com a venda
     */
    public boolean liberarParaTrocaDeUsuario(Window dono) {
        return pdvAberto == null || pdvAberto.liberarParaTrocaDeUsuario(dono);
    }

    public void encerrar() {
        encerrado = true;
        if (relogio != null) {
            relogio.stop();
        }
    }

    private void abrir(ItemMenu item) {
        if (item.janelaPropria()) {
            abrirPdv();
            return;
        }
        botoes.values().forEach(b -> b.getStyleClass().remove(ITEM_SELECIONADO));
        Button botao = botoes.get(item);
        if (botao != null) {
            botao.getStyleClass().add(ITEM_SELECIONADO);
        }
        itemAtual = item;
        Node tela;
        if (item == ItemMenu.PAINEL) {
            FXMLLoader loader = carregar("/fxml/painel.fxml");
            loader.<PainelController>getController().preparar(navegacaoPainel(), reabrirPassos);
            reabrirPassos = false;
            tela = loader.getRoot();
        } else if (item.fxml() == null) {
            tela = aviso(item.rotulo(), "Esta rotina ainda está em construção.");
        } else {
            FXMLLoader loader = carregar(item.fxml());
            if (loader.getController() instanceof CaixaController caixa) {
                caixa.aoMudar(this::atualizarCaixa);
            }
            if (loader.getController() instanceof FiscalController fiscal) {
                fiscal.naJanelaPrincipal();
            }
            tela = loader.getRoot();
        }
        mostrarTela(tela);
    }

    /** A tela é sempre o primeiro filho da área de conteúdo; a ajuda, quando aberta, fica por cima dela. */
    private void mostrarTela(Node tela) {
        boolean ajuda = ajudaAberta();
        conteudo.getChildren().setAll(tela);
        Atalhos.preencherDicas(tela); // os rodapés com atalhos saem do registro (F21.5)
        Aparencia.ajustar(tela); // larguras fixas e rótulos na fonte do usuário (F21.2)
        if (ajuda) {
            mostrarAjuda(); // a ajuda acompanha a tela aberta
        }
    }

    // ----- Ajuda (F1) e busca global (Ctrl+K), F21.3 -----

    private void alternarAjuda() {
        if (ajudaAberta()) {
            fecharAjuda();
        } else {
            mostrarAjuda();
        }
    }

    private boolean ajudaAberta() {
        return painelAjuda != null && conteudo.getChildren().contains(painelAjuda);
    }

    private void fecharAjuda() {
        conteudo.getChildren().remove(painelAjuda);
    }

    /**
     * A ajuda da tela aberta, com os atalhos registrados nela e os da janela principal. O painel fica por cima da
     * borda direita da tela, sem espremê-la: as telas têm larguras mínimas e, na fonte de 130%, a tela espremida
     * passaria da borda da janela.
     */
    private void mostrarAjuda() {
        if (painelAjuda == null) {
            painelAjuda = new PainelAjuda(this::fecharAjuda);
            painelAjuda.setMaxWidth(Region.USE_PREF_SIZE);
            StackPane.setAlignment(painelAjuda, Pos.TOP_RIGHT);
        }
        ItemMenu item = itemAtual == null ? ItemMenu.PAINEL : itemAtual;
        Node tela = conteudo.getChildren().isEmpty() ? null : conteudo.getChildren().get(0);
        painelAjuda.mostrar(item.ajuda(), tela == null || tela == painelAjuda ? List.of() : Atalhos.daTela(tela),
                Atalhos.de(raiz).lista());
        if (sessao.supervisor()) {
            // F21.4: o assistente "Primeiros passos" pode ser reaberto pela Ajuda, mesmo com tudo feito.
            painelAjuda.adicionarLink("Primeiros passos…", () -> {
                fecharAjuda();
                reabrirPassos = true;
                abrir(ItemMenu.PAINEL);
            });
        }
        if (!conteudo.getChildren().contains(painelAjuda)) {
            conteudo.getChildren().add(painelAjuda);
        }
    }

    /** Ctrl+K: a busca global; o resultado escolhido abre aqui, na janela principal. */
    private void buscar() {
        BuscaGlobal.abrir(raiz.getScene().getWindow(), buscaService, sessao).ifPresent(escolha -> {
            try {
                abrirResultado(escolha);
            } catch (RuntimeException e) {
                TratadorExcecoes.mostrar(e);
            }
        });
    }

    private void abrirResultado(BuscaGlobal.Escolha escolha) {
        if (escolha.tela() != null) {
            abrir(escolha.tela());
            return;
        }
        ItemBusca item = escolha.item();
        switch (item.grupo()) {
            case PRODUTOS -> {
                if (sessao.pode(Permissao.PRODUTOS)) {
                    abrirFiltrado(ItemMenu.PRODUTOS).<ProdutosController>getController().abrirProduto(item.id());
                } else {
                    abrirFiltrado(ItemMenu.ESTOQUE).<EstoqueController>getController().mostrarProduto(item.id());
                }
            }
            case CLIENTES -> abrirFiltrado(ItemMenu.CLIENTES).<ClientesController>getController()
                    .mostrarCliente(item.id());
            case FORNECEDORES -> abrirFiltrado(ItemMenu.FORNECEDORES).<FornecedoresController>getController()
                    .mostrarFornecedor(item.id());
            case VENDAS -> acoesBusca.segundaVia(raiz.getScene().getWindow(), sessao, item.id());
            case TELAS -> { } // as telas vêm em escolha.tela()
        }
    }

    /**
     * PDV em janela própria, maximizada e modal sobre a principal (8.1). Sem caixa aberto no terminal, pede a abertura
     * antes de vender (6.5); se a pessoa desistir, o PDV não abre.
     */
    private void abrirPdv() {
        try {
            if (caixaService.caixaAberto(sessao).isEmpty()) {
                Optional<CaixaSessao> aberto =
                        new AcoesCaixa(caixaService, acessoService, fechamentoPdf, sessao).abrir();
                atualizarCaixa();
                if (aberto.isEmpty()) {
                    return;
                }
            }
            FXMLLoader loader = carregar(ItemMenu.PDV.fxml());
            Stage janela = new Stage();
            janela.initOwner(menu.getScene().getWindow());
            janela.initModality(Modality.WINDOW_MODAL);
            janela.setTitle(Dialogos.TITULO + " — PDV");
            Scene cena = new Scene(loader.getRoot());
            cena.getStylesheets().add(PrincipalController.class.getResource(Dialogos.CSS).toExternalForm());
            janela.setScene(cena);
            janela.setMinWidth(LARGURA_MINIMA_PDV);
            janela.setMinHeight(ALTURA_MINIMA_PDV);
            janela.setMaximized(true);
            pdvAberto = loader.getController();
            pdvAberto.preparar(janela);
            janela.showAndWait();
        } catch (RuntimeException e) {
            TratadorExcecoes.mostrar(e);
        } finally {
            pdvAberto = null;
        }
        if (encerrado) {
            return; // o PDV fechou pelo log off (sessão encerrada ou troca de usuário)
        }
        atualizarCaixa();
        if (itemAtual == ItemMenu.PAINEL) {
            abrir(ItemMenu.PAINEL); // o alerta de caixa aberto pode ter mudado
        }
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

    /** Alertas e atalhos do Painel (7.5): abrem a rotina, já filtrada quando o alerta pede. */
    private NavegacaoPainel navegacaoPainel() {
        return new NavegacaoPainel() {
            @Override
            public void abrir(ItemMenu item) {
                PrincipalController.this.abrir(item);
            }

            @Override
            public void abrirEstoqueAbaixoDoMinimo() {
                FXMLLoader loader = abrirFiltrado(ItemMenu.ESTOQUE);
                loader.<EstoqueController>getController().mostrarAbaixoDoMinimo();
            }

            @Override
            public void abrirProdutosAbaixoDoCusto() {
                FXMLLoader loader = abrirFiltrado(ItemMenu.PRODUTOS);
                loader.<ProdutosController>getController().mostrarAbaixoDoCusto();
            }

            @Override
            public void abrirBackup() {
                FXMLLoader loader = abrirFiltrado(ItemMenu.CONFIGURACOES);
                loader.<ConfiguracoesController>getController().mostrarBackup();
            }

            @Override
            public void abrirTitulosVencidos() {
                FXMLLoader loader = abrirFiltrado(ItemMenu.RELATORIOS);
                loader.<RelatoriosController>getController().mostrarTitulosVencidos();
            }

            @Override
            public void abrirContasAPagar(LocalDate de, LocalDate ate) {
                FXMLLoader loader = abrirFiltrado(ItemMenu.FINANCEIRO);
                loader.<FinanceiroController>getController().mostrarContasAPagar(de, ate);
            }

            @Override
            public void abrirRupturas() {
                FXMLLoader loader = abrirFiltrado(ItemMenu.COMPRAS);
                loader.<ComprasController>getController().mostrarRupturas();
            }

            @Override
            public void abrirContasFinanceiras() {
                FXMLLoader loader = abrirFiltrado(ItemMenu.FINANCEIRO);
                loader.<FinanceiroController>getController().mostrarContas();
            }

            @Override
            public void abrirValidade(int dias, boolean vencidos) {
                if (sessao.pode(Permissao.RELATORIOS)) {
                    FXMLLoader loader = abrirFiltrado(ItemMenu.RELATORIOS);
                    loader.<RelatoriosController>getController().mostrarValidade(dias, vencidos);
                } else {
                    abrirFiltrado(ItemMenu.ESTOQUE);
                }
            }

            @Override
            public void abrirSemLoteNegativo() {
                FXMLLoader loader = abrirFiltrado(ItemMenu.ESTOQUE);
                loader.<EstoqueController>getController().mostrarSemLoteNegativo();
            }

            @Override
            public void abrirEtiquetasPendentes() {
                FXMLLoader loader = abrirFiltrado(ItemMenu.PRODUTOS);
                loader.<ProdutosController>getController().abrirEtiquetas();
            }

            @Override
            public void abrirCargaBalanca() {
                FXMLLoader loader = abrirFiltrado(ItemMenu.PRODUTOS);
                loader.<ProdutosController>getController().abrirCargaBalanca();
            }

            @Override
            public void abrirPasso(PrimeirosPassos.Etapa etapa) {
                switch (etapa) {
                    case PRODUTOS -> PrincipalController.this.abrir(ItemMenu.PRODUTOS);
                    case USUARIOS -> PrincipalController.this.abrir(ItemMenu.USUARIOS);
                    default -> abrirFiltrado(ItemMenu.CONFIGURACOES).<ConfiguracoesController>getController()
                            .mostrarSecao(switch (etapa) {
                                case LOJA -> ConfiguracoesController.Secao.LOJA;
                                case FORMAS -> ConfiguracoesController.Secao.FORMAS;
                                case BALANCA -> ConfiguracoesController.Secao.BALANCA;
                                default -> ConfiguracoesController.Secao.BACKUP;
                            });
                }
            }

            @Override
            public void abrirInconsistenciasFiscais() {
                if (sessao.pode(Permissao.RELATORIOS)) {
                    FXMLLoader loader = abrirFiltrado(ItemMenu.RELATORIOS);
                    loader.<RelatoriosController>getController().mostrarInconsistenciasFiscais();
                } else {
                    PrincipalController.this.abrir(ItemMenu.FISCAL);
                }
            }
        };
    }

    private FXMLLoader abrirFiltrado(ItemMenu item) {
        itemAtual = item;
        botoes.values().forEach(b -> b.getStyleClass().remove(ITEM_SELECIONADO));
        Button botao = botoes.get(item);
        if (botao != null) {
            botao.getStyleClass().add(ITEM_SELECIONADO);
        }
        FXMLLoader loader = carregar(item.fxml());
        mostrarTela(loader.<Parent>getRoot());
        return loader;
    }

    /** "Minhas preferências" (F21.1): grava o tema e a fonte do usuário e já aplica em todas as janelas abertas. */
    @FXML
    private void minhasPreferencias() {
        DialogoPreferencias.mostrar(Aparencia.atual(), escolhidas -> preferenciaService.salvar(sessao, escolhidas))
                .ifPresent(Aparencia::aplicar);
    }

    @FXML
    private void alterarSenha() {
        boolean alterada = DialogoSenha.alterarPropria(acessoService.politicaSenha().descricao(),
                (atual, nova, confirmacao) -> acessoService.trocarSenha(sessao, atual, nova, confirmacao));
        if (alterada) {
            Notificacao.mostrar(raiz.getScene().getWindow(), "Senha alterada.");
        }
    }

    @FXML
    private void logoff() {
        navegacao.logoff();
    }

    @FXML
    private void sair() {
        if (confirmarSaida()) {
            navegacao.sair();
        }
    }

    /**
     * Confirmação de Sair (botão ou fechar a janela). Com caixa aberto neste terminal, avisa antes (6.1); o caixa
     * continua aberto e quem entrar depois neste computador continua nele. Confirmada a saída, faz o backup automático
     * se ainda não houve backup hoje (6.12).
     */
    public boolean confirmarSaida() {
        Optional<CaixaSessao> aberto;
        try {
            aberto = caixaService.caixaAberto(sessao);
        } catch (RuntimeException e) {
            aberto = Optional.empty(); // sem banco, a saída não fica presa; o erro vai para o log
            TratadorExcecoes.mensagem(e);
        }
        boolean sair;
        if (aberto.isEmpty()) {
            sair = Dialogos.confirmar("Deseja sair do VareSync?");
        } else {
            CaixaSessao caixa = aberto.get();
            sair = Dialogos.confirmar("O caixa nº " + caixa.id() + " deste terminal está aberto (aberto por "
                    + caixa.abertoPorNome() + " em " + Formatos.dataHora(caixa.abertoEm()) + "). Se o expediente "
                    + "acabou, feche o caixa antes de sair (menu Caixa › Fechar caixa).\n\nSair mesmo assim?");
        }
        if (sair) {
            backupAoSair();
        }
        return sair;
    }

    /**
     * Backup automático ao sair, no máximo um por dia (6.12), com uma janela "Fazendo o backup…" enquanto o
     * {@code pg_dump} roda em {@link Task}. Falha não impede a saída: vai para o log e, para o supervisor, num aviso.
     */
    void backupAoSair() {
        boolean pendente;
        try {
            pendente = backupService.automaticoPendente();
        } catch (RuntimeException e) {
            TratadorExcecoes.mensagem(e); // registra no log
            return;
        }
        if (!pendente) {
            return;
        }
        Stage aviso = new Stage();
        aviso.initOwner(conteudo.getScene().getWindow());
        aviso.initModality(Modality.APPLICATION_MODAL);
        aviso.setTitle(Dialogos.TITULO);
        aviso.setOnCloseRequest(Event::consume); // fecha sozinha quando o backup termina
        ProgressIndicator girando = new ProgressIndicator();
        girando.setPrefSize(36, 36);
        Label texto = new Label("Fazendo o backup automático do banco de dados (um por dia)…\nAguarde: o sistema "
                + "fecha em seguida.");
        HBox caixa = new HBox(14, girando, texto);
        caixa.setAlignment(Pos.CENTER_LEFT);
        caixa.setPadding(new Insets(20));
        Scene cena = new Scene(caixa);
        cena.getStylesheets().add(getClass().getResource(Dialogos.CSS).toExternalForm());
        aviso.setScene(cena);

        Task<Optional<BackupService.Resultado>> tarefa = new Task<>() {
            @Override
            protected Optional<BackupService.Resultado> call() {
                return backupService.fazerAutomatico(sessao);
            }
        };
        String[] falha = new String[1];
        tarefa.setOnSucceeded(e -> aviso.hide());
        tarefa.setOnFailed(e -> {
            falha[0] = TratadorExcecoes.mensagem(tarefa.getException());
            aviso.hide();
        });
        Tarefas.executar("backup-automatico", tarefa);
        aviso.showAndWait();
        if (falha[0] != null && sessao.supervisor()) {
            Dialogos.alerta(Alert.AlertType.WARNING, "O backup automático não foi feito: " + falha[0]
                    + "\n\nO sistema será fechado mesmo assim. Faça o backup em Configurações › Backup.");
        }
    }

    /** Barra de status: situação do caixa deste terminal (7.5). */
    private void atualizarCaixa() {
        try {
            statusCaixa.setText(caixaService.caixaAberto(sessao)
                    .map(c -> "Caixa: aberto (nº " + c.id() + ")")
                    .orElse("Caixa: fechado"));
        } catch (RuntimeException e) {
            statusCaixa.setText("Caixa: " + SEM_INFORMACAO);
            TratadorExcecoes.mensagem(e);
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
