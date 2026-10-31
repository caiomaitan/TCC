package br.com.varesync.controller;

import br.com.varesync.model.ClienteVenda;
import br.com.varesync.model.ComprovanteRecebimento;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.PlanoRecebimento;
import br.com.varesync.model.TituloEmAberto;
import br.com.varesync.service.AcessoService;
import br.com.varesync.service.Liberacao;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.Recebimento;
import br.com.varesync.service.RecebimentoService;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.CampoNumero;
import br.com.varesync.ui.LiberacaoSupervisorDialogo;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.ui.TratadorExcecoes;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;

/**
 * Recebimento do crediário (F14.3): escolhe o cliente, vê os títulos com os encargos do dia, seleciona títulos ou digita
 * um valor e paga como no PDV. Toda regra está no {@link RecebimentoService}; aqui ficam os pedidos de liberação: sem
 * RECEBIMENTO, pelo {@link AcoesRecebimento} antes de abrir; sem LIMITE_CREDITO, ao dar desconto.
 */
public class RecebimentoController {

    private final RecebimentoService service;
    private final AcessoService acessoService;
    private final Sessao sessao;
    private final Liberacao liberacao;
    private Liberacao liberacaoDesconto;
    private PlanoRecebimento plano;
    private Recebimento pagamento;
    private BigDecimal valorDigitado;
    private BigDecimal desconto;
    /** Enquanto a tela preenche o campo Valor, ele não conta como digitado. */
    private boolean preenchendo;
    private String valorAplicado = "";
    private String descontoAplicado = "";
    private ComprovanteRecebimento resultado;

    @FXML private VBox raiz;
    @FXML private TextField campoBusca;
    @FXML private ComboBox<ClienteVenda> campoCliente;
    @FXML private TableView<TituloEmAberto> tabelaTitulos;
    @FXML private TableColumn<TituloEmAberto, String> colunaVenda;
    @FXML private TableColumn<TituloEmAberto, String> colunaParcela;
    @FXML private TableColumn<TituloEmAberto, String> colunaVencimento;
    @FXML private TableColumn<TituloEmAberto, String> colunaAtraso;
    @FXML private TableColumn<TituloEmAberto, String> colunaEmAberto;
    @FXML private TableColumn<TituloEmAberto, String> colunaMulta;
    @FXML private TableColumn<TituloEmAberto, String> colunaJuros;
    @FXML private TableColumn<TituloEmAberto, String> colunaQuitar;
    @FXML private TextField campoValor;
    @FXML private TextField campoDesconto;
    @FXML private Label principal;
    @FXML private Label multa;
    @FXML private Label juros;
    @FXML private Label total;
    @FXML private ComboBox<FormaPagamento> forma;
    @FXML private TextField valor;
    @FXML private Button botaoAdicionar;
    @FXML private TableView<Recebimento.Parcela> tabelaPagamentos;
    @FXML private TableColumn<Recebimento.Parcela, String> colunaForma;
    @FXML private TableColumn<Recebimento.Parcela, String> colunaPago;
    @FXML private Label pago;
    @FXML private Label falta;
    @FXML private Label troco;
    @FXML private Button botaoRemover;
    @FXML private Label mensagem;
    @FXML private Button botaoConfirmar;

    /** @param liberacao de quem tem RECEBIMENTO; nula se o próprio usuário tem */
    public RecebimentoController(RecebimentoService service, AcessoService acessoService, Sessao sessao,
                                 Liberacao liberacao) {
        this.service = service;
        this.acessoService = acessoService;
        this.sessao = sessao;
        this.liberacao = liberacao;
    }

    /** O comprovante do recebimento gravado, ou vazio se a pessoa desistiu. */
    public Optional<ComprovanteRecebimento> resultado() {
        return Optional.ofNullable(resultado);
    }

    @FXML
    private void initialize() {
        campoCliente.setConverter(new StringConverter<>() {
            @Override
            public String toString(ClienteVenda c) {
                return c == null ? "" : c.nome() + (c.ativo() ? "" : " (inativo)");
            }

            @Override
            public ClienteVenda fromString(String texto) {
                return null;
            }
        });
        campoCliente.valueProperty().addListener((o, a, c) -> carregarTitulos());
        colunaVenda.setCellValueFactory(c -> celula(String.valueOf(c.getValue().vendaId())));
        colunaParcela.setCellValueFactory(c -> celula(c.getValue().parcelaRotulo()));
        colunaVencimento.setCellValueFactory(c -> celula(Formatos.data(c.getValue().vencimento())));
        colunaAtraso.setCellValueFactory(c -> celula(c.getValue().diasAtraso() == 0 ? ""
                : String.valueOf(c.getValue().diasAtraso())));
        colunaEmAberto.setCellValueFactory(c -> celula(Formatos.moeda(c.getValue().encargos().emAberto())));
        colunaMulta.setCellValueFactory(c -> celula(Formatos.moeda(c.getValue().encargos().multa())));
        colunaJuros.setCellValueFactory(c -> celula(Formatos.moeda(c.getValue().encargos().juros())));
        colunaQuitar.setCellValueFactory(c -> celula(Formatos.moeda(c.getValue().encargos().paraQuitar())));
        colunaForma.setCellValueFactory(c -> celula(c.getValue().forma().descricao()));
        colunaPago.setCellValueFactory(c -> celula(Formatos.moeda(c.getValue().valor())));
        tabelaTitulos.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        tabelaTitulos.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<TituloEmAberto>) m -> {
                    if (!preenchendo) {
                        valorDigitado = null;
                        recalcular();
                    }
                });
        botaoRemover.disableProperty().bind(tabelaPagamentos.getSelectionModel().selectedItemProperty().isNull());
        forma.getItems().setAll(service.formas(sessao));
        forma.getSelectionModel().selectFirst();
        aplicarAoSair(campoValor);
        aplicarAoSair(campoDesconto);
        limparPlano();
        Platform.runLater(campoBusca::requestFocus);
    }

    /** Sair do campo (Tab) com outro texto vale como Enter; sem mudança, os pagamentos informados continuam. */
    private void aplicarAoSair(TextField campo) {
        campo.focusedProperty().addListener((o, antes, focado) -> {
            String aplicado = campo == campoValor ? valorAplicado : descontoAplicado;
            if (!focado && !Textos.normalizarEspacos(campo.getText()).equals(aplicado)) {
                aplicarValor();
            }
        });
    }

    private void lembrarAplicados() {
        valorAplicado = Textos.normalizarEspacos(campoValor.getText());
        descontoAplicado = Textos.normalizarEspacos(campoDesconto.getText());
    }

    private static ReadOnlyStringWrapper celula(String texto) {
        return new ReadOnlyStringWrapper(texto);
    }

    @FXML
    private void pesquisar() {
        try {
            List<ClienteVenda> clientes = service.pesquisarClientes(sessao, campoBusca.getText());
            campoCliente.getItems().setAll(clientes);
            if (clientes.isEmpty()) {
                Mensagem.erro(mensagem, "Nenhum cliente encontrado.");
            } else {
                Mensagem.limpar(mensagem);
                campoCliente.getSelectionModel().selectFirst();
                if (clientes.size() > 1) {
                    Platform.runLater(campoCliente::requestFocus);
                }
            }
        } catch (RuntimeException e) {
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(e));
        }
    }

    private void carregarTitulos() {
        ClienteVenda cliente = campoCliente.getValue();
        preenchendo = true;
        tabelaTitulos.getSelectionModel().clearSelection();
        tabelaTitulos.getItems().setAll(cliente == null ? List.of() : service.titulos(sessao, cliente.id()));
        preenchendo = false;
        valorDigitado = null;
        desconto = null;
        liberacaoDesconto = null;
        campoDesconto.clear();
        if (tabelaTitulos.getItems().isEmpty()) {
            limparPlano();
            if (cliente != null) {
                Mensagem.informar(mensagem, "O cliente não tem títulos em aberto.");
            }
            return;
        }
        Mensagem.limpar(mensagem);
        recalcular();
    }

    private List<Long> selecionados() {
        return tabelaTitulos.getSelectionModel().getSelectedItems().stream().map(TituloEmAberto::tituloId).toList();
    }

    /** Lê o valor e o desconto digitados (Enter) e refaz a distribuição. */
    @FXML
    private void aplicarValor() {
        try {
            BigDecimal novoDesconto = Textos.vazio(campoDesconto.getText()) ? null
                    : CampoNumero.ler(campoDesconto.getText(), "Desconto nos encargos");
            boolean comDesconto = novoDesconto != null && novoDesconto.signum() > 0;
            if (comDesconto && !sessao.pode(Permissao.LIMITE_CREDITO) && liberacaoDesconto == null) {
                liberacaoDesconto = LiberacaoSupervisorDialogo.solicitar("Desconto nos encargos",
                        (login, senha) -> acessoService.liberar(login, senha, Permissao.LIMITE_CREDITO)).orElse(null);
                if (liberacaoDesconto == null) {
                    campoDesconto.clear();
                    novoDesconto = null;
                    comDesconto = false;
                }
            }
            desconto = comDesconto ? novoDesconto : null;
            String digitado = campoValor.getText();
            boolean mudou = plano == null || Textos.vazio(digitado) || CampoNumero.ler(digitado, "Valor a receber")
                    .compareTo(plano.total()) != 0;
            // Com desconto vale só quitar: o valor é o que sobra depois dele, nunca o digitado.
            valorDigitado = comDesconto || Textos.vazio(digitado) ? null
                    : mudou ? CampoNumero.ler(digitado, "Valor a receber") : valorDigitado;
            recalcular();
        } catch (RuntimeException e) {
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(e));
        }
    }

    private void recalcular() {
        ClienteVenda cliente = campoCliente.getValue();
        if (cliente == null || tabelaTitulos.getItems().isEmpty()) {
            limparPlano();
            return;
        }
        try {
            plano = service.simular(sessao, cliente.id(), selecionados(), valorDigitado, desconto);
            pagamento = service.pagamento(plano);
            Mensagem.limpar(mensagem);
            mostrarPlano();
        } catch (NegocioException e) {
            limparPlano();
            Mensagem.erro(mensagem, e.getMessage());
        } catch (RuntimeException e) {
            limparPlano();
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(e));
        }
    }

    private void mostrarPlano() {
        preenchendo = true;
        campoValor.setText(Formatos.numero(plano.total(), Dinheiro.ESCALA_DINHEIRO));
        preenchendo = false;
        principal.setText(Formatos.moeda(plano.principal()));
        multa.setText(Formatos.moeda(plano.multa()));
        juros.setText(Formatos.moeda(plano.juros()));
        total.setText(Formatos.moeda(plano.total()));
        lembrarAplicados();
        atualizarPagamento();
    }

    private void limparPlano() {
        plano = null;
        pagamento = null;
        campoValor.clear();
        principal.setText(Formatos.SEM_VALOR);
        multa.setText(Formatos.SEM_VALOR);
        juros.setText(Formatos.SEM_VALOR);
        total.setText(Formatos.SEM_VALOR);
        lembrarAplicados();
        atualizarPagamento();
    }

    private void atualizarPagamento() {
        boolean comPlano = pagamento != null;
        tabelaPagamentos.getItems().setAll(comPlano ? pagamento.parcelas() : List.of());
        pago.setText(comPlano ? Formatos.moeda(pagamento.pago()) : Formatos.SEM_VALOR);
        falta.setText(comPlano ? Formatos.moeda(pagamento.falta()) : Formatos.SEM_VALOR);
        troco.setText(comPlano ? Formatos.moeda(pagamento.troco()) : Formatos.SEM_VALOR);
        boolean quitado = comPlano && pagamento.quitado();
        botaoConfirmar.setDisable(!quitado);
        botaoConfirmar.setDefaultButton(quitado);
        forma.setDisable(!comPlano || quitado);
        valor.setDisable(!comPlano || quitado);
        botaoAdicionar.setDisable(!comPlano || quitado);
        if (quitado) {
            valor.clear();
            Platform.runLater(botaoConfirmar::requestFocus);
        } else if (comPlano) {
            valor.setText(Formatos.numero(pagamento.falta(), Dinheiro.ESCALA_DINHEIRO));
        } else {
            valor.clear();
        }
    }

    @FXML
    private void adicionar() {
        if (pagamento == null) {
            return;
        }
        try {
            pagamento.adicionar(forma.getValue(), CampoNumero.ler(valor.getText(), "Valor"));
            Mensagem.limpar(mensagem);
            atualizarPagamento();
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
            valor.requestFocus();
            valor.selectAll();
        }
    }

    @FXML
    private void remover() {
        int indice = tabelaPagamentos.getSelectionModel().getSelectedIndex();
        if (indice >= 0 && pagamento != null) {
            pagamento.remover(indice);
            atualizarPagamento();
        }
    }

    @FXML
    private void confirmar() {
        if (pagamento == null || !pagamento.quitado()) {
            return;
        }
        try {
            resultado = service.registrar(sessao, campoCliente.getValue().id(), selecionados(), valorDigitado, desconto,
                    pagamento, liberacao, liberacaoDesconto).comprovante();
            fechar();
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        } catch (RuntimeException e) {
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(e));
        }
    }

    @FXML
    private void cancelar() {
        fechar();
    }

    private void fechar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }
}
