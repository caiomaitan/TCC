package br.com.varesync.controller;

import br.com.varesync.model.Comprovante;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.VendaResumo;
import br.com.varesync.service.AcessoService;
import br.com.varesync.service.Liberacao;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.Sessao;
import br.com.varesync.service.VendaService;
import br.com.varesync.ui.DialogosPdv;
import br.com.varesync.ui.LiberacaoSupervisorDialogo;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.ui.TratadorExcecoes;
import br.com.varesync.util.Formatos;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Vendas da sessão de caixa aberta (F9, 6.6): ver/reimprimir o comprovante e cancelar a venda finalizada, com
 * CANCELAR_VENDA ou a liberação de supervisor e motivo obrigatório. A venda cancelada sai do faturamento e do dinheiro
 * esperado, e os itens voltam ao estoque.
 */
public class VendasSessaoController {

    private static final String CANCELADA = "inativo";

    private final VendaService vendaService;
    private final AcessoService acessoService;
    private final Sessao sessao;
    private final Consumer<Comprovante> mostrarComprovante;

    @FXML private VBox raiz;
    @FXML private Label titulo;
    @FXML private TableView<VendaResumo> tabelaVendas;
    @FXML private TableColumn<VendaResumo, String> colunaNumero;
    @FXML private TableColumn<VendaResumo, String> colunaHora;
    @FXML private TableColumn<VendaResumo, String> colunaOperador;
    @FXML private TableColumn<VendaResumo, String> colunaItens;
    @FXML private TableColumn<VendaResumo, String> colunaTotal;
    @FXML private TableColumn<VendaResumo, String> colunaSituacao;
    @FXML private Button botaoComprovante;
    @FXML private Button botaoCancelar;
    @FXML private Label mensagem;

    public VendasSessaoController(VendaService vendaService, AcessoService acessoService, Sessao sessao,
                                  Consumer<Comprovante> mostrarComprovante) {
        this.vendaService = vendaService;
        this.acessoService = acessoService;
        this.sessao = sessao;
        this.mostrarComprovante = mostrarComprovante;
    }

    @FXML
    private void initialize() {
        colunaNumero.setCellValueFactory(c -> texto(String.valueOf(c.getValue().id())));
        colunaHora.setCellValueFactory(c -> texto(Formatos.dataHora(c.getValue().dataHora())));
        colunaOperador.setCellValueFactory(c -> texto(c.getValue().operador()));
        colunaItens.setCellValueFactory(c -> texto(String.valueOf(c.getValue().itens())));
        colunaTotal.setCellValueFactory(c -> texto(Formatos.moeda(c.getValue().total())));
        colunaSituacao.setCellValueFactory(c -> texto(c.getValue().status().descricao()));
        tabelaVendas.setRowFactory(t -> {
            TableRow<VendaResumo> linha = new TableRow<>() {
                @Override
                protected void updateItem(VendaResumo venda, boolean vazio) {
                    super.updateItem(venda, vazio);
                    getStyleClass().remove(CANCELADA);
                    if (!vazio && venda != null && venda.cancelada()) {
                        getStyleClass().add(CANCELADA);
                    }
                }
            };
            linha.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !linha.isEmpty()) {
                    verComprovante();
                }
            });
            return linha;
        });
        botaoComprovante.disableProperty().bind(tabelaVendas.getSelectionModel().selectedItemProperty().isNull());
        botaoCancelar.disableProperty().bind(tabelaVendas.getSelectionModel().selectedItemProperty().isNull());
        tabelaVendas.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                e.consume();
                verComprovante();
            } else if (e.getCode() == KeyCode.DELETE) {
                e.consume();
                cancelarVenda();
            }
        });
        carregar();
        Platform.runLater(tabelaVendas::requestFocus);
    }

    private void carregar() {
        tabelaVendas.getItems().setAll(vendaService.vendasDaSessao(sessao));
        long finalizadas = tabelaVendas.getItems().stream().filter(v -> !v.cancelada()).count();
        titulo.setText("Vendas da sessão de caixa (" + finalizadas + " finalizada"
                + (finalizadas == 1 ? "" : "s") + ")");
        tabelaVendas.getSelectionModel().selectFirst();
    }

    @FXML
    private void verComprovante() {
        VendaResumo venda = tabelaVendas.getSelectionModel().getSelectedItem();
        if (venda == null) {
            return;
        }
        try {
            mostrarComprovante.accept(vendaService.comprovante(sessao, venda.id()));
        } catch (RuntimeException e) {
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(e));
        }
    }

    @FXML
    private void cancelarVenda() {
        VendaResumo venda = tabelaVendas.getSelectionModel().getSelectedItem();
        if (venda == null) {
            return;
        }
        if (venda.cancelada()) {
            Mensagem.erro(mensagem, "A venda nº " + venda.id() + " já está cancelada.");
            return;
        }
        try {
            Liberacao liberacao = null;
            if (!sessao.pode(Permissao.CANCELAR_VENDA)) {
                Optional<Liberacao> concedida = LiberacaoSupervisorDialogo.solicitar(
                        "Cancelar a venda nº " + venda.id(),
                        (login, senha) -> acessoService.liberar(login, senha, Permissao.CANCELAR_VENDA));
                if (concedida.isEmpty()) {
                    return;
                }
                liberacao = concedida.get();
            }
            Liberacao liberada = liberacao;
            Optional<Boolean> cancelada = DialogosPdv.motivoCancelamento("Cancelar a venda nº " + venda.id(),
                    "Venda de " + Formatos.moeda(venda.total()) + " às " + Formatos.hora(venda.dataHora())
                            + ". Os itens voltam ao estoque e a venda sai do caixa. Esta ação não pode ser desfeita.",
                    motivo -> {
                        vendaService.cancelar(sessao, venda.id(), motivo, liberada);
                        return Boolean.TRUE;
                    });
            if (cancelada.isPresent()) {
                carregar();
                Mensagem.sucesso(mensagem, "Venda nº " + venda.id() + " cancelada.");
            }
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    @FXML
    private void fechar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }

    private static ReadOnlyStringWrapper texto(String valor) {
        return new ReadOnlyStringWrapper(valor);
    }
}
