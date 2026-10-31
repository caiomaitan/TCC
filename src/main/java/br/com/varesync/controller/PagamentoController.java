package br.com.varesync.controller;

import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.PlanoCrediario;
import br.com.varesync.service.AcessoService;
import br.com.varesync.service.Carrinho;
import br.com.varesync.service.CrediarioLiberacaoException;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.Recebimento;
import br.com.varesync.service.Sessao;
import br.com.varesync.service.VendaService;
import br.com.varesync.ui.CampoNumero;
import br.com.varesync.ui.ConversorData;
import br.com.varesync.ui.LiberacaoSupervisorDialogo;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.ui.TratadorExcecoes;
import br.com.varesync.util.CalculoCrediario;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Pagamento da venda (F10, 6.6): várias formas, total, pago, falta e troco; finaliza quando pago ≥ total. Com cliente
 * identificado, mostra o crédito disponível para a forma "Crédito do cliente" (F12.4). Na forma "Crediário" (F14.2),
 * pede as parcelas e o vencimento da primeira e, passando do limite, a liberação. A finalização é do {@link VendaService}: se falhar, nada é gravado, a mensagem aparece aqui e o carrinho continua.
 */
public class PagamentoController {

    private final VendaService vendaService;
    private final AcessoService acessoService;
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
    @FXML private HBox linhaCrediario;
    @FXML private ComboBox<Integer> campoParcelas;
    @FXML private DatePicker campoVencimento;
    @FXML private Label limiteCrediario;
    @FXML private Label previaCrediario;

    public PagamentoController(VendaService vendaService, AcessoService acessoService, Sessao sessao,
                               Carrinho carrinho) {
        this.vendaService = vendaService;
        this.acessoService = acessoService;
        this.sessao = sessao;
        this.carrinho = carrinho;
        this.recebimento = vendaService.recebimento(sessao, carrinho);
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
        prepararCrediario();

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
        recebimento.creditoDisponivel().ifPresent(saldo -> Mensagem.informar(mensagem,
                "Crédito do cliente disponível: " + Formatos.moeda(saldo) + "."));
    }

    /** Parcelas, vencimento sugerido e limite disponível; a linha só aparece com a forma Crediário em uso (F14.2). */
    private void prepararCrediario() {
        VendaService.OpcoesCrediario opcoes = vendaService.opcoesCrediario(sessao, carrinho);
        for (int n = 1; n <= opcoes.maxParcelas(); n++) {
            campoParcelas.getItems().add(n);
        }
        campoParcelas.setValue(opcoes.maxParcelas() >= 1 ? 1 : null);
        campoVencimento.setConverter(new ConversorData());
        campoVencimento.setValue(opcoes.primeiroVencimento());
        if (opcoes.limiteDisponivel() != null) {
            limiteCrediario.setText("Limite disponível: " + Formatos.moeda(opcoes.limiteDisponivel().max(Dinheiro.ZERO)));
        }
        forma.valueProperty().addListener((o, antes, depois) -> atualizarCrediario());
        campoParcelas.valueProperty().addListener((o, antes, depois) -> atualizarCrediario());
        campoVencimento.valueProperty().addListener((o, antes, depois) -> atualizarCrediario());
    }

    private void atualizarCrediario() {
        boolean usado = recebimento.crediarioUsado().signum() > 0;
        boolean mostrar = usado || (forma.getValue() != null && forma.getValue().crediario());
        boolean mudou = mostrar != linhaCrediario.isVisible() || usado != previaCrediario.isVisible();
        linhaCrediario.setVisible(mostrar);
        linhaCrediario.setManaged(mostrar);
        previaCrediario.setVisible(usado);
        previaCrediario.setManaged(usado);
        if (mudou && raiz.getScene() != null && raiz.getScene().getWindow() != null) {
            raiz.getScene().getWindow().sizeToScene(); // a janela cresce em vez de espremer a tabela
        }
        Integer parcelas = campoParcelas.getValue();
        LocalDate vencimento = campoVencimento.getValue();
        if (!usado || parcelas == null || vencimento == null) {
            previaCrediario.setText("");
            return;
        }
        BigDecimal valor = recebimento.crediarioUsado();
        // Valor pequeno demais para as parcelas: a finalização explica.
        if (valor.movePointRight(Dinheiro.ESCALA_DINHEIRO).compareTo(BigDecimal.valueOf(parcelas)) < 0) {
            previaCrediario.setText("");
            return;
        }
        previaCrediario.setText(CalculoCrediario.parcelar(valor, parcelas, vencimento).stream()
                .map(p -> p.numero() + "ª " + Formatos.moeda(p.valor()) + " em " + Formatos.data(p.vencimento()))
                .collect(Collectors.joining(" · ")));
    }

    @FXML
    private void adicionar() {
        try {
            recebimento.adicionar(forma.getValue(), CampoNumero.ler(valor.getText(), "Valor"));
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
            recebimento.definirPlanoCrediario(campoParcelas.getValue() == null ? null
                    : new PlanoCrediario(campoParcelas.getValue(), campoVencimento.getValue()));
            resultado = vendaService.finalizar(sessao, carrinho, recebimento);
            fechar();
        } catch (CrediarioLiberacaoException e) {
            Mensagem.erro(mensagem, e.getMessage());
            LiberacaoSupervisorDialogo.solicitar("Crediário acima do limite ou a cliente em atraso",
                    (login, senha) -> acessoService.liberar(login, senha, Permissao.LIMITE_CREDITO))
                    .ifPresent(liberacao -> {
                        recebimento.liberarCrediario(liberacao);
                        finalizar();
                    });
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
        atualizarCrediario();
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
