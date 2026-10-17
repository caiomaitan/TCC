package br.com.varesync.controller;

import br.com.varesync.model.FormaPagamento;
import br.com.varesync.service.Carrinho;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.Recebimento;
import br.com.varesync.service.Sessao;
import br.com.varesync.service.VendaService;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.ui.TratadorExcecoes;
import br.com.varesync.util.Formatos;
import java.util.Optional;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Pagamento da venda (F10, 6.6): várias formas, total, pago, falta e troco; finaliza quando pago ≥ total. A
 * finalização é do {@link VendaService}: se falhar, nada é gravado, a mensagem aparece aqui e o carrinho continua.
 */
public class PagamentoController {

    private final VendaService vendaService;
    private final Sessao sessao;
    private final Carrinho carrinho;
    private final Recebimento recebimento;
    private VendaService.VendaFinalizada resultado;

    @FXML private VBox raiz;
    @FXML private Label total;
    @FXML private Label pago;
    @FXML private Label falta;
    @FXML private Label troco;
    @FXML private ComboBox<FormaPagamento> forma;
    @FXML private TextField valor;
    @FXML private Button botaoAdicionar;
    @FXML private TableView<Recebimento.Parcela> tabelaParcelas;
    @FXML private TableColumn<Recebimento.Parcela, String> colunaForma;
    @FXML private TableColumn<Recebimento.Parcela, String> colunaValor;
    @FXML private Button botaoRemover;
    @FXML private Button botaoFinalizar;
    @FXML private Label mensagem;

    public PagamentoController(VendaService vendaService, Sessao sessao, Carrinho carrinho) {
        this.vendaService = vendaService;
        this.sessao = sessao;
        this.carrinho = carrinho;
        this.recebimento = new Recebimento(carrinho.total());
    }

    /** A venda finalizada, ou vazio se a pessoa voltou à venda. */
    public Optional<VendaService.VendaFinalizada> resultado() {
        return Optional.ofNullable(resultado);
    }

    @FXML
    private void initialize() {
        colunaForma.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().forma().descricao()));
        colunaValor.setCellValueFactory(c -> new ReadOnlyStringWrapper(Formatos.moeda(c.getValue().valor())));
        forma.getItems().setAll(vendaService.formasAtivas(sessao));
        forma.getSelectionModel().selectFirst();
        botaoRemover.disableProperty().bind(tabelaParcelas.getSelectionModel().selectedItemProperty().isNull());

        raiz.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.F10) {
                e.consume();
                finalizar();
            } else if (e.getCode() == KeyCode.DELETE && tabelaParcelas.isFocused()) {
                e.consume();
                remover();
            }
        });
        atualizar();
    }

    @FXML
    private void adicionar() {
        try {
            recebimento.adicionar(forma.getValue(), PdvController.lerNumero(valor.getText(), "o valor"));
            Mensagem.limpar(mensagem);
            atualizar();
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
            valor.requestFocus();
            valor.selectAll();
        }
    }

    @FXML
    private void remover() {
        int indice = tabelaParcelas.getSelectionModel().getSelectedIndex();
        if (indice >= 0) {
            recebimento.remover(indice);
            atualizar();
        }
    }

    @FXML
    private void finalizar() {
        if (!recebimento.quitado()) {
            Mensagem.erro(mensagem, "Falta receber " + Formatos.moeda(recebimento.falta()) + ".");
            valor.requestFocus();
            return;
        }
        try {
            resultado = vendaService.finalizar(sessao, carrinho, recebimento);
            fechar();
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        } catch (RuntimeException e) {
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(e));
        }
    }

    @FXML
    private void voltar() {
        fechar();
    }

    private void atualizar() {
        tabelaParcelas.getItems().setAll(recebimento.parcelas());
        total.setText(Formatos.moeda(recebimento.total()));
        pago.setText(Formatos.moeda(recebimento.pago()));
        falta.setText(Formatos.moeda(recebimento.falta()));
        troco.setText(Formatos.moeda(recebimento.troco()));
        boolean quitado = recebimento.quitado();
        botaoFinalizar.setDisable(!quitado);
        botaoFinalizar.setDefaultButton(quitado);
        forma.setDisable(quitado);
        valor.setDisable(quitado);
        botaoAdicionar.setDisable(quitado);
        if (quitado) {
            valor.clear();
            Platform.runLater(botaoFinalizar::requestFocus);
        } else {
            valor.setText(Formatos.numero(recebimento.falta(), 2));
            Platform.runLater(() -> {
                valor.requestFocus();
                valor.selectAll();
            });
        }
    }

    private void fechar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }
}
