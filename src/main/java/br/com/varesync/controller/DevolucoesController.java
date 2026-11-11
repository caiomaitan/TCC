package br.com.varesync.controller;

import br.com.varesync.model.DevolucaoResumo;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.VendaDevolvivel;
import br.com.varesync.report.ComprovantePdf;
import br.com.varesync.report.ImpressaoComprovante;
import br.com.varesync.service.AcessoService;
import br.com.varesync.service.ClienteService;
import br.com.varesync.service.DevolucaoService;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.ConversorData;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.ui.TratadorExcecoes;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.util.Optional;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import br.com.varesync.ui.Atalhos;
import br.com.varesync.ui.Atalhos.Tecla;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Tela Devoluções (F13.4, permissão DEVOLUCAO): localiza a venda finalizada pelo número, pelo período (padrão: os
 * últimos 30 dias) ou pelo cliente; "Devolver itens…" abre a devolução ({@link DevolucaoController}); a lista de baixo
 * mostra as devoluções já feitas da venda, com o comprovante.
 */
public class DevolucoesController {

    private final DevolucaoService devolucaoService;
    private final ClienteService clienteService;
    private final AcessoService acessoService;
    private final ComprovantePdf comprovantePdf;
    private final ImpressaoComprovante impressao;
    private final Sessao sessao;

    @FXML private VBox raiz;
    @FXML private TextField campoVenda;
    @FXML private DatePicker dataInicio;
    @FXML private DatePicker dataFim;
    @FXML private TextField campoCliente;
    @FXML private TableView<VendaDevolvivel> tabelaVendas;
    @FXML private TableColumn<VendaDevolvivel, String> colunaVenda;
    @FXML private TableColumn<VendaDevolvivel, String> colunaData;
    @FXML private TableColumn<VendaDevolvivel, String> colunaOperador;
    @FXML private TableColumn<VendaDevolvivel, String> colunaCliente;
    @FXML private TableColumn<VendaDevolvivel, String> colunaTotal;
    @FXML private TableColumn<VendaDevolvivel, String> colunaDevolvido;
    @FXML private Button botaoDevolver;
    @FXML private Label resumo;
    @FXML private TableView<DevolucaoResumo> tabelaDevolucoes;
    @FXML private TableColumn<DevolucaoResumo, String> colunaDevolucao;
    @FXML private TableColumn<DevolucaoResumo, String> colunaDevolucaoData;
    @FXML private TableColumn<DevolucaoResumo, String> colunaUsuario;
    @FXML private TableColumn<DevolucaoResumo, String> colunaForma;
    @FXML private TableColumn<DevolucaoResumo, String> colunaItens;
    @FXML private TableColumn<DevolucaoResumo, String> colunaValor;
    @FXML private TableColumn<DevolucaoResumo, String> colunaMotivo;
    @FXML private Button botaoComprovante;
    @FXML private Label mensagem;

    public DevolucoesController(DevolucaoService devolucaoService, ClienteService clienteService,
                                AcessoService acessoService, ComprovantePdf comprovantePdf,
                                ImpressaoComprovante impressao, Sessao sessao) {
        this.devolucaoService = devolucaoService;
        this.clienteService = clienteService;
        this.acessoService = acessoService;
        this.comprovantePdf = comprovantePdf;
        this.impressao = impressao;
        this.sessao = sessao;
    }

    @FXML
    private void initialize() {
        colunaVenda.setCellValueFactory(c -> texto(String.valueOf(c.getValue().id())));
        colunaData.setCellValueFactory(c -> texto(Formatos.dataHora(c.getValue().dataHora())));
        colunaOperador.setCellValueFactory(c -> texto(c.getValue().operador()));
        colunaCliente.setCellValueFactory(c -> texto(c.getValue().cliente() == null ? "" : c.getValue().cliente()));
        colunaTotal.setCellValueFactory(c -> texto(Formatos.moeda(c.getValue().total())));
        colunaDevolvido.setCellValueFactory(c -> texto(c.getValue().devolvido().signum() == 0 ? ""
                : Formatos.moeda(c.getValue().devolvido())));
        colunaDevolucao.setCellValueFactory(c -> texto(String.valueOf(c.getValue().id())));
        colunaDevolucaoData.setCellValueFactory(c -> texto(Formatos.dataHora(c.getValue().dataHora())));
        colunaUsuario.setCellValueFactory(c -> texto(c.getValue().usuario()));
        colunaForma.setCellValueFactory(c -> texto(c.getValue().forma().rotulo()));
        colunaItens.setCellValueFactory(c -> texto(String.valueOf(c.getValue().itens())));
        colunaValor.setCellValueFactory(c -> texto(Formatos.moeda(c.getValue().valor())));
        colunaMotivo.setCellValueFactory(c -> texto(c.getValue().motivo()));

        dataInicio.setConverter(new ConversorData());
        dataFim.setConverter(new ConversorData());
        Periodo padrao = devolucaoService.periodoPadrao();
        dataInicio.setValue(padrao.inicio());
        dataFim.setValue(padrao.fim());

        tabelaVendas.setRowFactory(t -> {
            TableRow<VendaDevolvivel> linha = new TableRow<>();
            linha.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !linha.isEmpty()) {
                    devolver();
                }
            });
            return linha;
        });
        Atalhos.de(tabelaVendas).em(Tecla.de(KeyCode.ENTER), "Devolver itens da venda selecionada (na lista)",
                this::devolver);
        tabelaVendas.getSelectionModel().selectedItemProperty().addListener((o, antes, depois) -> mostrarDevolucoes());
        botaoDevolver.disableProperty().bind(tabelaVendas.getSelectionModel().selectedItemProperty().isNull());
        botaoComprovante.disableProperty()
                .bind(tabelaDevolucoes.getSelectionModel().selectedItemProperty().isNull());
        buscar();
        Platform.runLater(campoVenda::requestFocus);
    }

    @FXML
    private void buscar() {
        Mensagem.limpar(mensagem);
        try {
            Long vendaId = null;
            String numero = Textos.normalizarEspacos(campoVenda.getText());
            if (!numero.isEmpty()) {
                if (!Textos.soDigitos(numero) || numero.length() > 18) {
                    throw new NegocioException("Venda nº: digite só o número da venda.");
                }
                vendaId = Long.parseLong(numero);
            }
            Periodo periodo = vendaId != null ? null : new Periodo(dataInicio.getValue(), dataFim.getValue());
            if (periodo != null && (periodo.inicio() == null || periodo.fim() == null)) {
                throw new NegocioException("Informe as datas do período (dd/mm/aaaa) ou o número da venda.");
            }
            if (periodo != null && periodo.inicio().isAfter(periodo.fim())) {
                throw new NegocioException("A data inicial não pode ser depois da data final.");
            }
            tabelaVendas.getItems().setAll(devolucaoService.pesquisarVendas(sessao, vendaId, periodo,
                    campoCliente.getText()));
            int n = tabelaVendas.getItems().size();
            resumo.setText(n + (n == 1 ? " venda" : " vendas") + (n >= DevolucaoService.LIMITE_BUSCA
                    ? " (as mais recentes; use um período menor ou o número da venda)" : ""));
            tabelaVendas.getSelectionModel().selectFirst();
            if (n == 0) {
                mostrarDevolucoes();
            }
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    private void mostrarDevolucoes() {
        VendaDevolvivel venda = tabelaVendas.getSelectionModel().getSelectedItem();
        if (venda == null) {
            tabelaDevolucoes.getItems().clear();
            return;
        }
        try {
            tabelaDevolucoes.getItems().setAll(devolucaoService.venda(sessao, venda.id()).devolucoes());
        } catch (RuntimeException e) {
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(e));
        }
    }

    @FXML
    private void devolver() {
        VendaDevolvivel venda = tabelaVendas.getSelectionModel().getSelectedItem();
        if (venda == null) {
            return;
        }
        Window dona = raiz.getScene().getWindow();
        try {
            Optional<DevolucaoService.DevolucaoRegistrada> feita = DevolucaoController.abrir(dona, devolucaoService,
                    clienteService, acessoService, sessao, venda.id());
            if (feita.isEmpty()) {
                return;
            }
            DevolucaoService.DevolucaoRegistrada d = feita.get();
            int indice = tabelaVendas.getSelectionModel().getSelectedIndex();
            buscar();
            tabelaVendas.getSelectionModel().select(Math.min(indice, tabelaVendas.getItems().size() - 1));
            Mensagem.sucesso(mensagem, "Devolução nº " + d.id() + " registrada: " + Formatos.moeda(d.valor())
                    + (d.cashbackEstornado().signum() > 0 ? "; cashback estornado de "
                    + Formatos.moeda(d.cashbackEstornado()) : "") + ".");
            if (Dialogos.confirmar(dona, "Devolução nº " + d.id() + " registrada. Mostrar o comprovante?")) {
                ComprovanteController.abrir(dona, d.comprovante(), comprovantePdf, impressao);
            }
        } catch (RuntimeException e) {
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(e));
        }
    }

    @FXML
    private void verComprovante() {
        DevolucaoResumo devolucao = tabelaDevolucoes.getSelectionModel().getSelectedItem();
        if (devolucao == null) {
            return;
        }
        try {
            ComprovanteController.abrir(raiz.getScene().getWindow(),
                    devolucaoService.comprovante(sessao, devolucao.id()), comprovantePdf, impressao);
        } catch (RuntimeException e) {
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(e));
        }
    }

    private static ReadOnlyStringWrapper texto(String valor) {
        return new ReadOnlyStringWrapper(valor);
    }
}
