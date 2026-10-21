package br.com.varesync.controller;

import br.com.varesync.model.FiltroPromocao;
import br.com.varesync.model.Promocao;
import br.com.varesync.model.SituacaoProduto;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.ProdutoService;
import br.com.varesync.service.PromocaoService;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.DialogosPromocao;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.ui.TratadorExcecoes;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

/**
 * Promoções (6.8), aberta pela tela Produtos: lista com filtro (vigentes, futuras, encerradas, todas) e busca pelo
 * nome do produto; nova (Ctrl+N), editar (duplo clique ou Enter), ativar/desativar e excluir a que nunca foi usada.
 */
public class PromocoesController {

    private final PromocaoService promocaoService;
    private final ProdutoService produtoService;
    private final Sessao sessao;

    @FXML private ComboBox<FiltroPromocao> filtro;
    @FXML private TextField campoBusca;
    @FXML private TableView<Promocao> tabela;
    @FXML private TableColumn<Promocao, Long> colunaNumero;
    @FXML private TableColumn<Promocao, String> colunaProduto;
    @FXML private TableColumn<Promocao, String> colunaPrecoNormal;
    @FXML private TableColumn<Promocao, String> colunaPrecoPromocional;
    @FXML private TableColumn<Promocao, String> colunaDesconto;
    @FXML private TableColumn<Promocao, LocalDate> colunaInicio;
    @FXML private TableColumn<Promocao, LocalDate> colunaFim;
    @FXML private TableColumn<Promocao, String> colunaSituacao;
    @FXML private TableColumn<Promocao, String> colunaCriadaPor;
    @FXML private Button botaoEditar;
    @FXML private Button botaoSituacao;
    @FXML private Button botaoExcluir;
    @FXML private Label mensagem;

    public PromocoesController(PromocaoService promocaoService, ProdutoService produtoService, Sessao sessao) {
        this.promocaoService = promocaoService;
        this.produtoService = produtoService;
        this.sessao = sessao;
    }

    @FXML
    private void initialize() {
        colunaNumero.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().id()));
        texto(colunaProduto, p -> p.produtoNome() + " (" + p.produtoId() + ")");
        texto(colunaPrecoNormal, p -> Formatos.moeda(p.precoVenda()));
        texto(colunaPrecoPromocional, p -> Formatos.moeda(p.precoPromocional()));
        texto(colunaDesconto, p -> Formatos.percentual(
                Dinheiro.percentualDe(p.precoVenda().subtract(p.precoPromocional()), p.precoVenda())));
        data(colunaInicio, Promocao::inicio);
        data(colunaFim, Promocao::fim);
        texto(colunaSituacao, p -> p.situacao(promocaoService.hoje()));
        texto(colunaCriadaPor, p -> p.criadoPor() + " em " + Formatos.data(p.criadoEm()
                .atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate()));

        tabela.setRowFactory(t -> {
            TableRow<Promocao> linha = new TableRow<>() {
                @Override
                protected void updateItem(Promocao p, boolean vazio) {
                    super.updateItem(p, vazio);
                    getStyleClass().remove("inativo");
                    if (!vazio && p != null && !"Vigente".equals(p.situacao(promocaoService.hoje()))
                            && !"Futura".equals(p.situacao(promocaoService.hoje()))) {
                        getStyleClass().add("inativo");
                    }
                }
            };
            linha.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !linha.isEmpty()) {
                    editar();
                }
            });
            return linha;
        });
        tabela.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && selecionada().isPresent()) {
                e.consume();
                editar();
            }
        });
        tabela.getSelectionModel().selectedItemProperty().addListener((obs, a, p) -> atualizarBotoes());

        filtro.getItems().setAll(FiltroPromocao.values());
        filtro.setValue(FiltroPromocao.VIGENTES);
        filtro.setOnAction(e -> pesquisar());

        KeyCombination atalhoNova = new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN);
        tabela.getParent().addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (atalhoNova.match(e)) {
                e.consume();
                nova();
            }
        });
        pesquisar();
        Platform.runLater(campoBusca::requestFocus);
    }

    private static void texto(TableColumn<Promocao, String> coluna, Function<Promocao, String> valor) {
        coluna.setCellValueFactory(c -> new ReadOnlyStringWrapper(valor.apply(c.getValue())));
    }

    private static void data(TableColumn<Promocao, LocalDate> coluna, Function<Promocao, LocalDate> valor) {
        coluna.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(valor.apply(c.getValue())));
        coluna.setComparator(Comparator.naturalOrder());
        coluna.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(LocalDate d, boolean vazio) {
                super.updateItem(d, vazio);
                setText(vazio || d == null ? null : Formatos.data(d));
            }
        });
    }

    private Optional<Promocao> selecionada() {
        return Optional.ofNullable(tabela.getSelectionModel().getSelectedItem());
    }

    private void atualizarBotoes() {
        Optional<Promocao> p = selecionada();
        botaoEditar.setDisable(p.isEmpty());
        botaoExcluir.setDisable(p.isEmpty());
        botaoSituacao.setDisable(p.isEmpty());
        botaoSituacao.setText(p.map(Promocao::ativa).orElse(true) ? "Desativar" : "Ativar");
    }

    @FXML
    private void pesquisar() {
        Long selecionada = selecionada().map(Promocao::id).orElse(null);
        tabela.getItems().setAll(promocaoService.listar(sessao, filtro.getValue(), campoBusca.getText()));
        if (selecionada != null) {
            tabela.getItems().stream().filter(p -> p.id() == selecionada).findFirst()
                    .ifPresent(p -> tabela.getSelectionModel().select(p));
        }
        atualizarBotoes();
    }

    private DialogosPromocao.Acoes acoes(Long id) {
        return new DialogosPromocao.Acoes(
                codigo -> promocaoService.produto(sessao, codigo),
                termo -> produtoService.pesquisar(sessao, termo, null, SituacaoProduto.ATIVOS),
                produtoId -> promocaoService.produto(sessao, produtoId),
                dados -> promocaoService.avisos(sessao, dados),
                dados -> {
                    if (id == null) {
                        return promocaoService.criar(sessao, dados);
                    }
                    promocaoService.alterar(sessao, id, dados);
                    return id;
                });
    }

    @FXML
    private void nova() {
        DialogosPromocao.formulario(null, promocaoService.hoje(), acoes(null)).ifPresent(id -> {
            mostrarGravada(id);
            Mensagem.sucesso(mensagem, "Promoção nº " + id + " criada.");
        });
    }

    @FXML
    private void editar() {
        selecionada().ifPresent(p -> DialogosPromocao.formulario(p, promocaoService.hoje(), acoes(p.id()))
                .ifPresent(id -> {
                    mostrarGravada(id);
                    Mensagem.sucesso(mensagem, "Promoção nº " + id + " alterada.");
                }));
    }

    /** Depois de gravar: se a promoção sai do filtro atual, mostra todas para ela continuar à vista. */
    private void mostrarGravada(long id) {
        pesquisar();
        if (tabela.getItems().stream().noneMatch(p -> p.id() == id)) {
            filtro.setValue(FiltroPromocao.TODAS);
            campoBusca.clear();
            pesquisar();
        }
        tabela.getItems().stream().filter(p -> p.id() == id).findFirst().ifPresent(p -> {
            tabela.getSelectionModel().select(p);
            tabela.scrollTo(p);
        });
    }

    @FXML
    private void alternarSituacao() {
        selecionada().ifPresent(p -> executar(() -> {
            if (p.ativa() && !Dialogos.confirmar("Desativar a promoção nº " + p.id() + " de \"" + p.produtoNome()
                    + "\"? O preço promocional deixa de valer no PDV.")) {
                return;
            }
            promocaoService.alterarSituacao(sessao, p.id(), !p.ativa());
            mostrarGravada(p.id());
            Mensagem.sucesso(mensagem, "Promoção nº " + p.id() + (p.ativa() ? " desativada." : " ativada."));
        }));
    }

    @FXML
    private void excluir() {
        selecionada().ifPresent(p -> executar(() -> {
            if (!Dialogos.confirmar("Excluir a promoção nº " + p.id() + " de \"" + p.produtoNome() + "\"?")) {
                return;
            }
            promocaoService.excluir(sessao, p.id());
            pesquisar();
            Mensagem.sucesso(mensagem, "Promoção nº " + p.id() + " excluída.");
        }));
    }

    private void executar(Runnable acao) {
        try {
            acao.run();
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        } catch (RuntimeException e) {
            TratadorExcecoes.mostrar(e);
        }
    }

    @FXML
    private void fechar() {
        tabela.getScene().getWindow().hide();
    }

    /** Busca com o texto do campo (Enter). */
    @FXML
    private void buscar() {
        pesquisar();
    }
}
