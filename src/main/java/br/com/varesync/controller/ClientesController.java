package br.com.varesync.controller;

import br.com.varesync.model.Cliente;
import br.com.varesync.model.DadosCliente;
import br.com.varesync.model.HistoricoCliente;
import br.com.varesync.report.ExportadorCsv;
import br.com.varesync.report.RelDadosCliente;
import br.com.varesync.report.RelatorioPdf;
import br.com.varesync.report.ResultadoRelatorio;
import br.com.varesync.service.ClienteService;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.RegistroEmUsoException;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.ConversorData;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.DialogosCliente;
import br.com.varesync.ui.Exportacao;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.util.CpfCnpj;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;

/**
 * Clientes (F12.1, F12.2, F12.6): lista com busca à esquerda e, à direita, as abas Cadastro, Compras (histórico,
 * total gasto, ticket médio, última compra e produtos mais comprados) e LGPD (exportar os dados, anonimizar).
 * CPF/CNPJ, telefone e CEP ganham a máscara ao sair do campo; o {@link ClienteService} grava sem máscara e confere a
 * permissão CLIENTES (anonimizar, só o supervisor).
 */
public class ClientesController {

    private static final KeyCombination CTRL_N = new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN);
    private static final ConversorData DATA = new ConversorData();

    private final ClienteService clienteService;
    private final RelatorioPdf relatorioPdf;
    private final Sessao sessao;
    /** Cliente em edição; nulo no cadastro novo. */
    private Cliente emEdicao;

    @FXML private HBox raiz;
    @FXML private TextField campoBusca;
    @FXML private CheckBox filtroInativos;
    @FXML private TableView<Cliente> tabela;
    @FXML private TableColumn<Cliente, String> colunaCodigo;
    @FXML private TableColumn<Cliente, String> colunaNome;
    @FXML private TableColumn<Cliente, String> colunaDocumento;
    @FXML private TableColumn<Cliente, String> colunaTelefone;
    @FXML private TableColumn<Cliente, String> colunaSituacao;
    @FXML private Label resumo;
    @FXML private Label tituloFormulario;
    @FXML private TabPane abas;
    @FXML private Tab abaCadastro;
    @FXML private Tab abaCompras;
    @FXML private Tab abaLgpd;
    @FXML private TextField campoNome;
    @FXML private TextField campoDocumento;
    @FXML private TextField campoTelefone;
    @FXML private TextField campoEmail;
    @FXML private TextField campoNascimento;
    @FXML private TextField campoCep;
    @FXML private TextField campoLogradouro;
    @FXML private TextField campoNumero;
    @FXML private TextField campoComplemento;
    @FXML private TextField campoBairro;
    @FXML private TextField campoCidade;
    @FXML private ComboBox<String> campoUf;
    @FXML private TextArea campoObservacao;
    @FXML private CheckBox campoConsentimento;
    @FXML private Label consentimentoInfo;
    @FXML private Label totalGasto;
    @FXML private Label numeroCompras;
    @FXML private Label ticketMedio;
    @FXML private Label ultimaCompra;
    @FXML private TableView<HistoricoCliente.Compra> tabelaCompras;
    @FXML private TableColumn<HistoricoCliente.Compra, String> colunaVenda;
    @FXML private TableColumn<HistoricoCliente.Compra, String> colunaData;
    @FXML private TableColumn<HistoricoCliente.Compra, String> colunaItens;
    @FXML private TableColumn<HistoricoCliente.Compra, String> colunaValor;
    @FXML private TableView<HistoricoCliente.ProdutoComprado> tabelaProdutos;
    @FXML private TableColumn<HistoricoCliente.ProdutoComprado, String> colunaProduto;
    @FXML private TableColumn<HistoricoCliente.ProdutoComprado, String> colunaVezes;
    @FXML private TableColumn<HistoricoCliente.ProdutoComprado, String> colunaQuantidade;
    @FXML private TableColumn<HistoricoCliente.ProdutoComprado, String> colunaValorProduto;
    @FXML private Label lgpdConsentimento;
    @FXML private Button botaoExportarPdf;
    @FXML private Button botaoExportarCsv;
    @FXML private Button botaoAnonimizar;
    @FXML private Label situacao;
    @FXML private Label mensagem;
    @FXML private Button botaoExcluir;
    @FXML private Button botaoSituacao;
    @FXML private Button botaoSalvar;

    public ClientesController(ClienteService clienteService, RelatorioPdf relatorioPdf, Sessao sessao) {
        this.clienteService = clienteService;
        this.relatorioPdf = relatorioPdf;
        this.sessao = sessao;
    }

    @FXML
    private void initialize() {
        coluna(colunaCodigo, c -> String.valueOf(c.id()));
        coluna(colunaNome, Cliente::nome);
        coluna(colunaDocumento, Cliente::documentoFormatado);
        coluna(colunaTelefone, Cliente::telefoneFormatado);
        coluna(colunaSituacao, Cliente::situacao);
        tabela.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(Cliente c, boolean vazio) {
                super.updateItem(c, vazio);
                getStyleClass().remove("inativo");
                if (!vazio && c != null && !c.ativo()) {
                    getStyleClass().add("inativo");
                }
            }
        });
        tabela.getSelectionModel().selectedItemProperty().addListener((obs, anterior, atual) -> {
            if (atual != null) {
                editar(atual);
            }
        });
        colunaVenda.setCellValueFactory(c -> celula(String.valueOf(c.getValue().vendaId())));
        colunaData.setCellValueFactory(c -> celula(Formatos.dataHora(c.getValue().dataHora())));
        colunaItens.setCellValueFactory(c -> celula(String.valueOf(c.getValue().itens())));
        colunaValor.setCellValueFactory(c -> celula(Formatos.moeda(c.getValue().valor())));
        colunaProduto.setCellValueFactory(c -> celula(c.getValue().nome()));
        colunaVezes.setCellValueFactory(c -> celula(String.valueOf(c.getValue().compras())));
        colunaQuantidade.setCellValueFactory(c -> celula(Formatos.quantidade(c.getValue().quantidade(),
                c.getValue().fracionada()) + " " + c.getValue().unidade()));
        colunaValorProduto.setCellValueFactory(c -> celula(Formatos.moeda(c.getValue().valor())));

        campoUf.getItems().setAll(FornecedoresController.UFS);
        aoSairDoCampo(campoDocumento, this::mascararDocumento);
        aoSairDoCampo(campoTelefone, this::mascararTelefone);
        aoSairDoCampo(campoCep, this::mascararCep);
        botaoAnonimizar.setVisible(sessao.supervisor());
        botaoAnonimizar.setManaged(sessao.supervisor());
        raiz.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (CTRL_N.match(e)) {
                e.consume();
                novo();
            }
        });
        recarregar(null);
        novo();
    }

    private static void coluna(TableColumn<Cliente, String> coluna, Function<Cliente, String> valor) {
        coluna.setCellValueFactory(c -> new ReadOnlyStringWrapper(valor.apply(c.getValue())));
    }

    private static ReadOnlyStringWrapper celula(String valor) {
        return new ReadOnlyStringWrapper(valor);
    }

    private static void aoSairDoCampo(TextField campo, Runnable acao) {
        campo.focusedProperty().addListener((obs, antes, focado) -> {
            if (!focado) {
                acao.run();
            }
        });
    }

    @FXML
    private void pesquisar() {
        try {
            recarregar(emEdicao == null ? null : emEdicao.id());
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    /** Refaz a busca e seleciona o cliente {@code id}, se ele estiver na lista. */
    private void recarregar(Long id) {
        List<Cliente> lista = clienteService.pesquisar(sessao, campoBusca.getText(), filtroInativos.isSelected());
        tabela.getItems().setAll(lista);
        resumo.setText(lista.size() == 1 ? "1 cliente" : lista.size() + " clientes");
        if (id != null) {
            tabela.getItems().stream().filter(c -> c.id() == id).findFirst()
                    .ifPresent(c -> tabela.getSelectionModel().select(c));
        }
    }

    @FXML
    private void novo() {
        emEdicao = null;
        tabela.getSelectionModel().clearSelection();
        tituloFormulario.setText("Novo cliente");
        for (TextField campo : camposDeTexto()) {
            campo.clear();
        }
        campoUf.setValue("");
        campoObservacao.clear();
        campoConsentimento.setSelected(false);
        consentimentoInfo.setText("");
        situacao.setText("");
        habilitarEdicao(true);
        botaoExcluir.setDisable(true);
        botaoSituacao.setDisable(true);
        botaoSituacao.setText("Inativar");
        abaCompras.setDisable(true);
        abaLgpd.setDisable(true);
        abas.getSelectionModel().select(abaCadastro);
        Mensagem.limpar(mensagem);
        Platform.runLater(campoNome::requestFocus);
    }

    private void editar(Cliente c) {
        emEdicao = c;
        tituloFormulario.setText("Cliente " + c.id() + " — " + c.nome());
        campoNome.setText(c.nome());
        campoDocumento.setText(c.documentoFormatado());
        campoTelefone.setText(c.telefoneFormatado());
        campoEmail.setText(texto(c.email()));
        campoNascimento.setText(DATA.toString(c.dataNascimento()));
        campoCep.setText(c.cepFormatado());
        campoLogradouro.setText(texto(c.logradouro()));
        campoNumero.setText(texto(c.numero()));
        campoComplemento.setText(texto(c.complemento()));
        campoBairro.setText(texto(c.bairro()));
        campoCidade.setText(texto(c.cidade()));
        campoUf.setValue(c.uf() == null ? "" : c.uf());
        campoObservacao.setText(texto(c.observacao()));
        campoConsentimento.setSelected(c.consentimentoMarketing());
        String consentimento = c.consentimentoMarketing()
                ? "Consentimento registrado em " + Formatos.dataHora(c.consentimentoEm()) + "."
                : "Sem consentimento de marketing.";
        consentimentoInfo.setText(c.consentimentoMarketing() ? consentimento : "");
        lgpdConsentimento.setText(c.anonimizado() ? "Cliente anonimizado: não há dados pessoais guardados."
                : consentimento);
        situacao.setText(c.situacao() + " — cadastrado em " + Formatos.dataHora(c.criadoEm()) + " por "
                + c.criadoPorNome() + "; última alteração em " + Formatos.dataHora(c.atualizadoEm()) + " por "
                + c.atualizadoPorNome());
        habilitarEdicao(!c.anonimizado());
        botaoExcluir.setDisable(false);
        botaoSituacao.setDisable(c.anonimizado());
        botaoSituacao.setText(c.ativo() ? "Inativar" : "Reativar");
        botaoAnonimizar.setDisable(c.anonimizado());
        abaCompras.setDisable(false);
        abaLgpd.setDisable(false);
        mostrarHistorico(clienteService.historico(sessao, c.id()));
        Mensagem.limpar(mensagem);
    }

    private void mostrarHistorico(HistoricoCliente h) {
        totalGasto.setText(Formatos.moeda(h.totalGasto()));
        numeroCompras.setText(String.valueOf(h.compras().size()));
        ticketMedio.setText(Formatos.moeda(h.ticketMedio()));
        ultimaCompra.setText(h.ultimaCompra().map(Formatos::dataHora).orElse(Formatos.SEM_VALOR));
        tabelaCompras.getItems().setAll(h.compras());
        tabelaProdutos.getItems().setAll(h.produtos());
    }

    /** Anonimizado: o cadastro fica só para leitura. */
    private void habilitarEdicao(boolean editavel) {
        for (TextField campo : camposDeTexto()) {
            campo.setEditable(editavel);
        }
        campoObservacao.setEditable(editavel);
        campoUf.setDisable(!editavel);
        campoConsentimento.setDisable(!editavel);
        botaoSalvar.setDisable(!editavel);
    }

    private List<TextField> camposDeTexto() {
        return List.of(campoNome, campoDocumento, campoTelefone, campoEmail, campoNascimento, campoCep,
                campoLogradouro, campoNumero, campoComplemento, campoBairro, campoCidade);
    }

    @FXML
    private void salvar() {
        try {
            DadosCliente dados = new DadosCliente(campoNome.getText(), campoDocumento.getText(),
                    campoTelefone.getText(), campoEmail.getText(), nascimento(), campoCep.getText(),
                    campoLogradouro.getText(), campoNumero.getText(), campoComplemento.getText(),
                    campoBairro.getText(), campoCidade.getText(), campoUf.getValue(), campoObservacao.getText(),
                    campoConsentimento.isSelected());
            long id;
            if (emEdicao == null) {
                id = clienteService.criar(sessao, dados);
            } else {
                id = emEdicao.id();
                clienteService.alterar(sessao, id, dados);
            }
            recarregar(id);
            if (tabela.getSelectionModel().getSelectedItem() == null) {
                editar(clienteService.buscar(sessao, id)); // fora do filtro atual
            }
            Mensagem.sucesso(mensagem, "Cliente salvo.");
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    /** Nascimento digitado em dd/mm/aaaa; texto que não é data é recusado aqui, com a mensagem junto do campo. */
    private LocalDate nascimento() {
        if (Textos.vazio(campoNascimento.getText())) {
            return null;
        }
        LocalDate data = DATA.fromString(campoNascimento.getText());
        if (data == null) {
            abas.getSelectionModel().select(abaCadastro);
            throw new NegocioException("Data de nascimento inválida: use dd/mm/aaaa (ex.: 25/03/1980).");
        }
        return data;
    }

    @FXML
    private void excluir() {
        if (emEdicao == null) {
            return;
        }
        Cliente c = emEdicao;
        if (!Dialogos.confirmar("Excluir o cliente \"" + c.nome() + "\"?")) {
            return;
        }
        try {
            clienteService.excluir(sessao, c.id());
            recarregar(null);
            novo();
            Mensagem.sucesso(mensagem, "Cliente excluído.");
        } catch (RegistroEmUsoException e) {
            if (c.ativo() && Dialogos.confirmar(e.getMessage() + "\n\nInativar o cliente agora?")) {
                mudarSituacao(c, false);
            } else {
                Mensagem.erro(mensagem, e.getMessage());
            }
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    @FXML
    private void alternarSituacao() {
        if (emEdicao == null) {
            return;
        }
        if (emEdicao.ativo() && !Dialogos.confirmar("Inativar o cliente \"" + emEdicao.nome() + "\"? Ele sai da "
                + "identificação no PDV, mas continua nas vendas e nos relatórios.")) {
            return;
        }
        mudarSituacao(emEdicao, !emEdicao.ativo());
    }

    private void mudarSituacao(Cliente c, boolean ativo) {
        try {
            clienteService.alterarSituacao(sessao, c.id(), ativo);
            if (!ativo) {
                filtroInativos.setSelected(true); // continua à vista, esmaecido
            }
            recarregar(c.id());
            Mensagem.sucesso(mensagem, ativo ? "Cliente reativado." : "Cliente inativado.");
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    // ----- LGPD -----

    @FXML
    private void exportarPdf() {
        escolherEExportar("PDF", "pdf");
    }

    @FXML
    private void exportarCsv() {
        escolherEExportar("CSV", "csv");
    }

    private void escolherEExportar(String formato, String extensao) {
        if (emEdicao != null) {
            Exportacao.escolher(raiz.getScene().getWindow(), formato, extensao,
                    "dados_cliente_" + emEdicao.id() + "." + extensao).ifPresent(d -> exportar(formato, d));
        }
    }

    /** Lê os dados do banco (a exportação fica na auditoria) e grava o arquivo em {@link Task}. */
    Task<Void> exportar(String formato, Path destino) {
        try {
            ResultadoRelatorio<RelDadosCliente.Linha> dados = clienteService.exportar(sessao, emEdicao.id());
            return Exportacao.gravar(formato, destino, "CSV".equals(formato) ? d -> ExportadorCsv.salvar(dados, d)
                    : d -> relatorioPdf.salvar(dados, d), mensagem, List.of(botaoExportarPdf, botaoExportarCsv));
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
            return null;
        }
    }

    @FXML
    private void anonimizar() {
        if (emEdicao == null || emEdicao.anonimizado()) {
            return;
        }
        Cliente c = emEdicao;
        boolean feito = DialogosCliente.confirmarAnonimizacao(c.id(), c.nome(),
                nomeDigitado -> clienteService.anonimizar(sessao, c.id(), nomeDigitado));
        if (feito) {
            filtroInativos.setSelected(true);
            recarregar(c.id());
            if (tabela.getSelectionModel().getSelectedItem() == null) {
                editar(clienteService.buscar(sessao, c.id()));
            }
            Mensagem.sucesso(mensagem, "Cliente anonimizado. As vendas continuam, como \""
                    + ClienteService.nomeAnonimizado(c.id()) + "\".");
        }
    }

    // ----- Máscaras -----

    /** Máscara ao sair do campo, só quando o tamanho é de CPF ou CNPJ; o resto fica como digitado. */
    private void mascararDocumento() {
        String documento = CpfCnpj.normalizar(campoDocumento.getText());
        if (documento.length() == 11 || documento.length() == 14) {
            campoDocumento.setText(CpfCnpj.formatar(documento));
        }
    }

    private void mascararTelefone() {
        String digitos = Textos.apenasDigitos(campoTelefone.getText());
        if (!Textos.vazio(campoTelefone.getText()) && (digitos.length() == 10 || digitos.length() == 11)) {
            campoTelefone.setText(Formatos.telefone(digitos));
        }
    }

    private void mascararCep() {
        String digitos = Textos.apenasDigitos(campoCep.getText());
        if (digitos.length() == 8) {
            campoCep.setText(digitos.substring(0, 5) + "-" + digitos.substring(5));
        }
    }

    private static String texto(String valor) {
        return valor == null ? "" : valor;
    }
}
