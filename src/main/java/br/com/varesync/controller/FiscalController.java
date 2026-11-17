package br.com.varesync.controller;

import br.com.varesync.model.AlvoPerfil;
import br.com.varesync.model.Categoria;
import br.com.varesync.model.DadosFiscais;
import br.com.varesync.model.OrigemMercadoria;
import br.com.varesync.model.PerfilTributario;
import br.com.varesync.model.ProdutoFiscal;
import br.com.varesync.service.FiscalService;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.Sessao;
import br.com.varesync.service.ValidacaoFiscal;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.ui.Tarefas;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.stage.FileChooser;

/**
 * Cadastro fiscal (F19.2 e F19.3, permissão FISCAL), aberto pelo item Fiscal do menu, pelo botão "Fiscal…" de Produtos
 * e pelo "Abrir produto" do R30 (F19.4 e F19.5). Aba Produtos: NCM,
 * CEST, origem e perfil do produto selecionado e a aplicação de um perfil em lote (à seleção, a uma categoria inteira
 * ou a um NCM); aba Perfis tributários ({@link PerfisTributariosController}); aba Tabelas NCM e CEST, com a
 * importação em {@link Task}.
 */
public class FiscalController {

    /** "Todas" e "Todos" dos filtros. */
    private static final Categoria TODAS = new Categoria(0, "Todas", true);
    private static final PerfilTributario TODOS = new PerfilTributario(0, "Todos", "", "", null, null, null, "", null,
            "", null, null, null, null, true, 0);

    private final FiscalService service;
    private final Sessao sessao;
    private ProdutoFiscal selecionado;

    @FXML private Label situacaoEmpresa;
    @FXML private TabPane abas;
    @FXML private Tab abaProdutos;
    @FXML private Tab abaPerfis;
    @FXML private Tab abaTabelas;
    @FXML private Tab abaNotas;
    @FXML private Node notas;
    @FXML private NotasFiscaisController notasController;
    @FXML private Node perfis;
    @FXML private PerfisTributariosController perfisController;
    @FXML private TextField campoBusca;
    @FXML private ComboBox<Categoria> filtroCategoria;
    @FXML private ComboBox<PerfilTributario> filtroPerfil;
    @FXML private CheckBox campoSoPendentes;
    @FXML private CheckBox campoInativos;
    @FXML private TableView<ProdutoFiscal> tabela;
    @FXML private TableColumn<ProdutoFiscal, Long> colunaCodigo;
    @FXML private TableColumn<ProdutoFiscal, String> colunaNome;
    @FXML private TableColumn<ProdutoFiscal, String> colunaCategoria;
    @FXML private TableColumn<ProdutoFiscal, String> colunaNcm;
    @FXML private TableColumn<ProdutoFiscal, String> colunaCest;
    @FXML private TableColumn<ProdutoFiscal, String> colunaOrigem;
    @FXML private TableColumn<ProdutoFiscal, String> colunaPerfil;
    @FXML private Label resumo;
    @FXML private Label tituloProduto;
    @FXML private GridPane gradeProduto;
    @FXML private TextField campoNcm;
    @FXML private Label descricaoNcm;
    @FXML private TextField campoCest;
    @FXML private ComboBox<Integer> campoOrigem;
    @FXML private ComboBox<PerfilTributario> campoPerfil;
    @FXML private Button botaoSalvar;
    @FXML private ComboBox<PerfilTributario> campoPerfilLote;
    @FXML private ToggleGroup grupoAlvo;
    @FXML private RadioButton alvoSelecao;
    @FXML private RadioButton alvoCategoria;
    @FXML private RadioButton alvoNcm;
    @FXML private ComboBox<Categoria> campoCategoriaLote;
    @FXML private TextField campoNcmLote;
    @FXML private Button botaoAplicar;
    @FXML private Label situacaoNcm;
    @FXML private Label situacaoCest;
    @FXML private Button botaoImportarNcm;
    @FXML private Button botaoImportarCest;
    @FXML private Button botaoImportarIbpt;
    @FXML private Label situacaoIbpt;
    @FXML private ProgressIndicator progresso;
    @FXML private Label mensagemTabelas;
    @FXML private Label mensagem;
    @FXML private Label rotuloEsc;

    public FiscalController(FiscalService service, Sessao sessao) {
        this.service = service;
        this.sessao = sessao;
    }

    /** "Empresa: Simples Nacional · UF SP · IE 123456789", com o caminho para completar o que falta. */
    static String empresa(br.com.varesync.model.Configuracoes.Loja loja) {
        String uf = loja.uf().isEmpty() ? "UF não informada" : "UF " + loja.uf();
        String ie = loja.ie().isEmpty() ? "IE não informada" : "IE " + loja.ie();
        return "Empresa: " + loja.regime().rotulo() + " · " + uf + " · " + ie
                + (loja.uf().isEmpty() || loja.ie().isEmpty() ? " (complete em Configurações › Loja)" : "")
                + ". A NFC-e (só em homologação, sem valor fiscal) usa estes dados.";
    }

    @FXML
    private void initialize() {
        situacaoEmpresa.setText(empresa(service.empresa(sessao)));
        progresso.managedProperty().bind(progresso.visibleProperty());
        colunaCodigo.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().id()));
        texto(colunaNome, ProdutoFiscal::nome);
        texto(colunaCategoria, p -> p.categoriaNome() == null ? "" : p.categoriaNome());
        texto(colunaNcm, p -> p.ncm() == null ? "—" : ValidacaoFiscal.formatarNcm(p.ncm()));
        texto(colunaCest, p -> p.cest() == null ? "" : ValidacaoFiscal.formatarCest(p.cest()));
        texto(colunaOrigem, p -> OrigemMercadoria.rotulo(p.origem()));
        texto(colunaPerfil, p -> p.perfilNome() == null ? "—" : p.perfilNome());
        tabela.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        tabela.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(ProdutoFiscal p, boolean vazio) {
                super.updateItem(p, vazio);
                getStyleClass().removeAll("inativo", "linha-aviso");
                if (!vazio && p != null) {
                    if (!p.ativo()) {
                        getStyleClass().add("inativo");
                    } else if (p.ncm() == null || p.perfilId() == null) {
                        getStyleClass().add("linha-aviso");
                    }
                }
            }
        });
        tabela.getSelectionModel().selectedItemProperty().addListener((o, a, p) -> mostrar(p));
        tabela.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<ProdutoFiscal>) m -> atualizarSelecao());

        campoOrigem.getItems().add(null);
        campoOrigem.getItems().addAll(OrigemMercadoria.codigos());
        campoOrigem.setCellFactory(l -> celulaOrigem(false));
        campoOrigem.setButtonCell(celulaOrigem(true));
        campoPerfil.setCellFactory(l -> celulaPerfil("(sem perfil)", false));
        campoPerfil.setButtonCell(celulaPerfil("(sem perfil)", true));
        campoPerfilLote.setCellFactory(l -> celulaPerfil("Escolha o perfil", false));
        campoPerfilLote.setButtonCell(celulaPerfil("Escolha o perfil", true));
        campoNcm.textProperty().addListener((o, a, n) -> descricaoNcm.setText(descricao(n)));
        campoCategoriaLote.valueProperty().addListener((o, a, c) -> {
            if (c != null) {
                alvoCategoria.setSelected(true);
            }
        });
        campoNcmLote.textProperty().addListener((o, a, n) -> {
            if (n != null && !n.isBlank()) {
                alvoNcm.setSelected(true);
            }
        });

        filtroCategoria.setOnAction(e -> pesquisar());
        filtroPerfil.setOnAction(e -> pesquisar());
        campoSoPendentes.setOnAction(e -> pesquisar());
        campoInativos.setOnAction(e -> pesquisar());
        abas.getSelectionModel().selectedItemProperty().addListener((o, a, aba) -> {
            if (aba == abaProdutos) {
                carregarPerfis();
                pesquisar();
            } else if (aba == abaTabelas) {
                carregarTabelas();
            }
        });

        List<Categoria> categorias = service.categorias(sessao); // pela FISCAL: quem só tem FISCAL abre pelo menu
        filtroCategoria.getItems().setAll(TODAS);
        filtroCategoria.getItems().addAll(categorias);
        filtroCategoria.setValue(TODAS);
        campoCategoriaLote.getItems().setAll(categorias);
        carregarPerfis();
        pesquisar();
        mostrar(null);
        Platform.runLater(campoBusca::requestFocus);
    }

    /** Aberto pelo menu (F19.5), na área de conteúdo da janela principal: sem o aviso "Esc fecha a janela". */
    public void naJanelaPrincipal() {
        rotuloEsc.setVisible(false);
        rotuloEsc.setManaged(false);
    }

    /** Alertas da NFC-e no painel (F23.10): a aba Notas fiscais, sem período, filtrada nas situações. */
    public void mostrarNotasFiscais(java.util.Set<br.com.varesync.model.StatusNfce> situacoes) {
        abas.getSelectionModel().select(abaNotas);
        notasController.mostrar(situacoes);
    }

    /**
     * "Abrir produto" do R30 (F19.4): a aba Produtos, sem filtros, com o produto buscado pelo código e selecionado.
     */
    public void mostrarProduto(long produtoId) {
        abas.getSelectionModel().select(abaProdutos);
        campoSoPendentes.setSelected(false);
        campoInativos.setSelected(true);
        filtroCategoria.setValue(TODAS);
        filtroPerfil.setValue(TODOS);
        campoBusca.setText(String.valueOf(produtoId));
        pesquisar();
        tabela.getItems().stream().filter(p -> p.id() == produtoId).findFirst().ifPresent(p -> {
            tabela.getSelectionModel().clearSelection();
            tabela.getSelectionModel().select(p);
            tabela.scrollTo(p);
        });
        Platform.runLater(campoNcm::requestFocus);
    }

    private static void texto(TableColumn<ProdutoFiscal, String> coluna, Function<ProdutoFiscal, String> valor) {
        coluna.setCellValueFactory(c -> new ReadOnlyStringWrapper(valor.apply(c.getValue())));
    }

    /** @param botao célula do botão do combo: sem valor escolhido, mostra o texto do vazio (a lista não tem linha vazia) */
    private static ListCell<Integer> celulaOrigem(boolean botao) {
        return new ListCell<>() {
            @Override
            protected void updateItem(Integer origem, boolean vazio) {
                super.updateItem(origem, vazio);
                setText(vazio && !botao ? null : origem == null ? "(não informada)" : OrigemMercadoria.rotulo(origem));
            }
        };
    }

    private static ListCell<PerfilTributario> celulaPerfil(String vazioTexto, boolean botao) {
        return new ListCell<>() {
            @Override
            protected void updateItem(PerfilTributario p, boolean vazio) {
                super.updateItem(p, vazio);
                setText(vazio && !botao ? null : p == null ? vazioTexto : p.toString());
            }
        };
    }

    /** Recarrega os perfis dos filtros e das escolhas, mantendo o que estava escolhido. */
    private void carregarPerfis() {
        List<PerfilTributario> todos = service.perfis(sessao, false);
        Long filtro = filtroPerfil.getValue() == null || filtroPerfil.getValue() == TODOS ? null
                : filtroPerfil.getValue().id();
        filtroPerfil.getItems().setAll(TODOS);
        filtroPerfil.getItems().addAll(todos);
        filtroPerfil.setValue(todos.stream().filter(p -> Objects.equals(p.id(), filtro)).findFirst().orElse(TODOS));
        Long lote = campoPerfilLote.getValue() == null ? null : campoPerfilLote.getValue().id();
        List<PerfilTributario> ativos = todos.stream().filter(PerfilTributario::ativo).toList();
        campoPerfilLote.getItems().setAll(ativos);
        campoPerfilLote.setValue(ativos.stream().filter(p -> Objects.equals(p.id(), lote)).findFirst().orElse(null));
        mostrar(selecionado);
    }

    @FXML
    private void pesquisar() {
        Categoria categoria = filtroCategoria.getValue();
        PerfilTributario perfil = filtroPerfil.getValue();
        Long manter = selecionado == null ? null : selecionado.id();
        try {
            tabela.getItems().setAll(service.produtos(sessao, campoBusca.getText(),
                    categoria == null || categoria == TODAS ? null : categoria.id(),
                    perfil == null || perfil == TODOS ? null : perfil.id(), campoSoPendentes.isSelected(),
                    campoInativos.isSelected()));
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
            return;
        }
        long pendentes = tabela.getItems().stream().filter(p -> p.ativo() && (p.ncm() == null || p.perfilId() == null))
                .count();
        int total = tabela.getItems().size();
        resumo.setText((total == 1 ? "1 produto" : total + " produtos") + (pendentes == 0 ? ""
                : ", " + pendentes + " sem NCM ou sem perfil (em amarelo)"));
        if (manter != null) {
            tabela.getItems().stream().filter(p -> p.id() == manter).findFirst()
                    .ifPresent(p -> tabela.getSelectionModel().select(p));
        }
        if (tabela.getSelectionModel().getSelectedItem() == null) {
            mostrar(null);
        }
        atualizarSelecao();
    }

    private void atualizarSelecao() {
        int n = tabela.getSelectionModel().getSelectedItems().size();
        alvoSelecao.setText(n == 0 ? "Aos produtos selecionados na lista (nenhum)"
                : n == 1 ? "Ao produto selecionado na lista" : "Aos " + n + " produtos selecionados na lista");
    }

    private void mostrar(ProdutoFiscal p) {
        selecionado = p;
        gradeProduto.setDisable(p == null);
        botaoSalvar.setDisable(p == null);
        List<PerfilTributario> escolhas = new ArrayList<>();
        escolhas.add(null);
        for (PerfilTributario perfil : filtroPerfil.getItems()) {
            if (perfil != TODOS && (perfil.ativo() || (p != null && Objects.equals(perfil.id(), p.perfilId())))) {
                escolhas.add(perfil);
            }
        }
        campoPerfil.getItems().setAll(escolhas);
        if (p == null) {
            tituloProduto.setText("Produto selecionado: nenhum");
            campoNcm.setText("");
            campoCest.setText("");
            campoOrigem.setValue(null);
            campoPerfil.setValue(null);
            return;
        }
        tituloProduto.setText("Produto selecionado: " + p.nome() + " (código " + p.id() + ")");
        campoNcm.setText(p.ncm() == null ? "" : ValidacaoFiscal.formatarNcm(p.ncm()));
        campoCest.setText(p.cest() == null ? "" : ValidacaoFiscal.formatarCest(p.cest()));
        campoOrigem.setValue(p.origem());
        campoPerfil.setValue(escolhas.stream().filter(e -> e != null && Objects.equals(e.id(), p.perfilId()))
                .findFirst().orElse(null));
    }

    private String descricao(String ncm) {
        try {
            String d = service.descricaoNcm(sessao, ncm);
            return d.length() > 80 ? d.substring(0, 79) + "…" : d;
        } catch (NegocioException e) {
            return "";
        }
    }

    @FXML
    private void salvar() {
        if (selecionado == null) {
            return;
        }
        PerfilTributario perfil = campoPerfil.getValue();
        try {
            service.salvarDadosFiscais(sessao, selecionado.id(), new DadosFiscais(campoNcm.getText(),
                    campoCest.getText(), campoOrigem.getValue(), perfil == null ? null : perfil.id()));
            String nome = selecionado.nome();
            pesquisar();
            Mensagem.sucesso(mensagem, "Dados fiscais de \"" + nome + "\" salvos.");
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    @FXML
    private void aplicar() {
        Mensagem.limpar(mensagem);
        PerfilTributario perfil = campoPerfilLote.getValue();
        if (perfil == null) {
            Mensagem.erro(mensagem, "Escolha o perfil a aplicar.");
            return;
        }
        AlvoPerfil alvo;
        String descricao;
        if (alvoCategoria.isSelected()) {
            Categoria c = campoCategoriaLote.getValue();
            if (c == null) {
                Mensagem.erro(mensagem, "Escolha a categoria.");
                return;
            }
            alvo = AlvoPerfil.categoria(c.id());
            descricao = "da categoria \"" + c.nome() + "\" (ativos e inativos)";
        } else if (alvoNcm.isSelected()) {
            alvo = AlvoPerfil.ncm(campoNcmLote.getText());
            descricao = "com o NCM começando por " + (campoNcmLote.getText() == null ? "" : campoNcmLote.getText().strip());
        } else {
            alvo = AlvoPerfil.selecao(tabela.getSelectionModel().getSelectedItems().stream().map(ProdutoFiscal::id)
                    .toList());
            descricao = "selecionados na lista";
        }
        try {
            FiscalService.Previa previa = service.previa(sessao, perfil.id(), alvo);
            if (previa.produtos() == 0) {
                Mensagem.erro(mensagem, "Nenhum produto " + descricao + ".");
                return;
            }
            if (previa.mudam() == 0) {
                Mensagem.informar(mensagem, "Os " + previa.produtos() + " produtos " + descricao + " já têm o perfil \""
                        + perfil.nome() + "\".");
                return;
            }
            if (!Dialogos.confirmar(tabela.getScene().getWindow(), "Aplicar o perfil \"" + perfil.nome() + "\" a "
                    + (previa.mudam() == 1 ? "1 produto" : previa.mudam() + " produtos") + " " + descricao + "?"
                    + (previa.jaComOPerfil() == 0 ? "" : " (" + previa.jaComOPerfil() + " já têm este perfil.)")
                    + " O perfil anterior desses produtos é substituído.")) {
                return;
            }
            int alterados = service.aplicarPerfil(sessao, perfil.id(), alvo);
            pesquisar();
            Mensagem.sucesso(mensagem, "Perfil \"" + perfil.nome() + "\" aplicado a " + (alterados == 1 ? "1 produto"
                    : alterados + " produtos") + ".");
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    // ----- Tabelas NCM e CEST -----

    private void carregarTabelas() {
        FiscalService.Tabelas t = service.tabelas(sessao);
        situacaoNcm.setText(t.ncm() == 0 ? "Tabela NCM: não importada (só o formato é conferido)."
                : "Tabela NCM: " + t.ncm() + " códigos de 8 dígitos.");
        situacaoCest.setText(t.cest() == 0 ? "Tabela CEST: não importada (só o formato é conferido)."
                : "Tabela CEST: " + t.cest() + " códigos.");
        FiscalService.SituacaoIbpt ibpt = service.situacaoIbpt(sessao);
        situacaoIbpt.setText(textoIbpt(ibpt));
        situacaoIbpt.getStyleClass().remove("mensagem-erro");
        if (ibpt.vencida()) {
            situacaoIbpt.getStyleClass().add("mensagem-erro");
        }
    }

    @FXML
    private void importarNcm() {
        File arquivo = escolher("Tabela NCM", "CSV ou JSON da tabela NCM", "*.csv", "*.json", "*.txt");
        if (arquivo != null) {
            importarNcm(arquivo.toPath());
        }
    }

    @FXML
    private void importarCest() {
        File arquivo = escolher("Tabela CEST", "CSV da tabela CEST", "*.csv", "*.txt");
        if (arquivo != null) {
            importarCest(arquivo.toPath());
        }
    }

    @FXML
    private void importarIbpt() {
        File arquivo = escolher("Tabela IBPT", "CSV da tabela do IBPT", "*.csv", "*.txt");
        if (arquivo != null) {
            importarIbpt(arquivo.toPath());
        }
    }

    /**
     * Importa a tabela IBPT do arquivo, em {@link Task}. Quando o nome do arquivo traz uma UF diferente da UF da loja
     * (F22.3), pede confirmação antes.
     */
    void importarIbpt(Path arquivo) {
        String nome = arquivo.getFileName().toString();
        String ufLoja = service.empresa(sessao).uf();
        java.util.Optional<String> ufArquivo = br.com.varesync.service.TabelaFiscal.ufDoArquivo(nome);
        if (ufArquivo.isPresent() && !ufLoja.isEmpty() && !ufArquivo.get().equals(ufLoja)
                && !Dialogos.confirmar(abas.getScene().getWindow(), "O arquivo " + nome + " é da tabela do IBPT de "
                + ufArquivo.get() + ", e a loja é de " + ufLoja + " (Configurações › Loja). Os tributos estaduais "
                + "aproximados mudam de uma UF para outra.\n\nImportar mesmo assim?")) {
            return;
        }
        importar(arquivo, conteudo -> service.importarIbpt(sessao, conteudo, nome));
    }

    /** "Tabela IBPT: versão 25.2.A, vigência de 01/10/2026 a 31/12/2026, 10.512 linhas de NCM (fonte IBPT)." */
    static String textoIbpt(FiscalService.SituacaoIbpt s) {
        if (s.linhas() == 0) {
            return "Tabela IBPT: não importada (a venda sai sem os tributos aproximados).";
        }
        String texto = "Tabela IBPT: versão " + (Textos.vazio(s.versao()) ? "(sem versão)" : s.versao())
                + ", vigência de " + Formatos.data(s.inicio()) + " a " + Formatos.data(s.fim()) + ", " + s.linhas()
                + (s.linhas() == 1 ? " linha de NCM" : " linhas de NCM")
                + (Textos.vazio(s.fonte()) ? "" : " (fonte: " + s.fonte() + ")") + ".";
        return s.vencida() ? texto + " A vigência acabou: baixe a tabela nova no site do IBPT." : texto;
    }

    private File escolher(String titulo, String descricao, String... extensoes) {
        FileChooser escolha = new FileChooser();
        escolha.setTitle(titulo);
        escolha.getExtensionFilters().add(new FileChooser.ExtensionFilter(descricao, extensoes));
        return escolha.showOpenDialog(abas.getScene().getWindow());
    }

    /** Importa a tabela NCM do arquivo, em {@link Task} (o botão "Importar…" chama depois da escolha). */
    void importarNcm(Path arquivo) {
        importar(arquivo, conteudo -> service.importarNcm(sessao, conteudo, arquivo.getFileName().toString()));
    }

    /** Importa a tabela CEST do arquivo, em {@link Task}. */
    void importarCest(Path arquivo) {
        importar(arquivo, conteudo -> service.importarCest(sessao, conteudo, arquivo.getFileName().toString()));
    }

    private void importar(Path arquivo, Function<byte[], FiscalService.Importacao> importacao) {
        Mensagem.limpar(mensagemTabelas);
        Task<FiscalService.Importacao> tarefa = new Task<>() {
            @Override
            protected FiscalService.Importacao call() throws IOException {
                return importacao.apply(Files.readAllBytes(arquivo));
            }
        };
        botaoImportarNcm.setDisable(true);
        botaoImportarCest.setDisable(true);
        botaoImportarIbpt.setDisable(true);
        progresso.setVisible(true);
        tarefa.setOnSucceeded(e -> {
            terminarImportacao();
            FiscalService.Importacao r = tarefa.getValue();
            Mensagem.sucesso(mensagemTabelas, resultado(r, arquivo.getFileName().toString()));
        });
        tarefa.setOnFailed(e -> {
            terminarImportacao();
            Throwable erro = tarefa.getException();
            if (erro instanceof NegocioException) {
                Mensagem.erro(mensagemTabelas, erro.getMessage());
            } else if (erro instanceof IOException) {
                Mensagem.erro(mensagemTabelas, "Não foi possível ler o arquivo " + arquivo.getFileName() + ".");
            } else {
                Mensagem.erro(mensagemTabelas, "Erro inesperado. Os detalhes foram registrados no log.");
                org.slf4j.LoggerFactory.getLogger(FiscalController.class).error("Falha na importação", erro);
            }
        });
        Tarefas.executar("importacao-fiscal", tarefa);
    }

    /** Mensagem do fim da importação: os níveis pulados da NCM, ou as linhas de serviço da IBPT. */
    static String resultado(FiscalService.Importacao r, String arquivo) {
        if ("IBPT".equals(r.tabela())) {
            return "Tabela IBPT importada de " + arquivo + ": " + r.linhas() + (r.linhas() == 1 ? " linha de NCM"
                    : " linhas de NCM") + (r.pulados() == 0 ? "" : r.pulados() == 1 ? " (1 linha de serviço ficou de fora)"
                    : " (" + r.pulados() + " linhas de serviço ficaram de fora)") + ".";
        }
        return "Tabela " + r.tabela() + " importada de " + arquivo + ": " + r.linhas() + " códigos"
                + (r.pulados() == 0 ? "" : r.pulados() == 1 ? " (1 nível intermediário pulado)"
                : " (" + r.pulados() + " níveis intermediários pulados)") + ".";
    }

    private void terminarImportacao() {
        botaoImportarNcm.setDisable(false);
        botaoImportarCest.setDisable(false);
        botaoImportarIbpt.setDisable(false);
        progresso.setVisible(false);
        carregarTabelas();
    }
}
