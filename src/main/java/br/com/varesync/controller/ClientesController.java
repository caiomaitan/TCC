package br.com.varesync.controller;

import br.com.varesync.model.Cliente;
import br.com.varesync.model.DadosCliente;
import br.com.varesync.model.ExtratoCredito;
import br.com.varesync.model.ExtratoCrediario;
import br.com.varesync.model.HistoricoCliente;
import br.com.varesync.model.MovimentoCredito;
import br.com.varesync.model.Permissao;
import br.com.varesync.report.ExportadorCsv;
import br.com.varesync.report.RelDadosCliente;
import br.com.varesync.report.RelExtratoCrediario;
import br.com.varesync.report.RelatorioPdf;
import br.com.varesync.report.ResultadoRelatorio;
import br.com.varesync.service.ClienteService;
import br.com.varesync.service.CreditoService;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.RegistroEmUsoException;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.CampoNumero;
import br.com.varesync.ui.ConversorData;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.DialogosCliente;
import br.com.varesync.ui.Exportacao;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.ui.TratadorExcecoes;
import br.com.varesync.util.CpfCnpj;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
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
import br.com.varesync.ui.Atalhos;
import br.com.varesync.ui.Atalhos.Tecla;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;

/**
 * Clientes (F12.1, F12.2, F12.3, F12.6): lista com busca à esquerda e, à direita, as abas Cadastro, Compras
 * (histórico, total gasto, ticket médio, última compra e produtos mais comprados), Crédito (saldo, extrato e ajuste,
 * só o supervisor) e LGPD (exportar os dados, anonimizar).
 * CPF/CNPJ, telefone e CEP ganham a máscara ao sair do campo; o {@link ClienteService} grava sem máscara e confere a
 * permissão CLIENTES (anonimizar, só o supervisor).
 */
public class ClientesController {

    private static final ConversorData DATA = new ConversorData();

    private final ClienteService clienteService;
    private final CreditoService creditoService;
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
    @FXML private TableColumn<HistoricoCliente.Compra, String> colunaDevolvido;
    @FXML private TableColumn<HistoricoCliente.Compra, String> colunaValor;
    @FXML private TableView<HistoricoCliente.ProdutoComprado> tabelaProdutos;
    @FXML private TableColumn<HistoricoCliente.ProdutoComprado, String> colunaProduto;
    @FXML private TableColumn<HistoricoCliente.ProdutoComprado, String> colunaVezes;
    @FXML private TableColumn<HistoricoCliente.ProdutoComprado, String> colunaQuantidade;
    @FXML private TableColumn<HistoricoCliente.ProdutoComprado, String> colunaValorProduto;
    @FXML private Tab abaCredito;
    @FXML private Label saldoCredito;
    @FXML private Button botaoAjustarCredito;
    @FXML private TableView<MovimentoCredito> tabelaCredito;
    @FXML private TableColumn<MovimentoCredito, String> colunaCreditoData;
    @FXML private TableColumn<MovimentoCredito, String> colunaCreditoTipo;
    @FXML private TableColumn<MovimentoCredito, String> colunaCreditoOrigem;
    @FXML private TableColumn<MovimentoCredito, String> colunaCreditoValor;
    @FXML private TableColumn<MovimentoCredito, String> colunaCreditoSaldo;
    @FXML private Label detalheCredito;
    @FXML private Tab abaCrediario;
    @FXML private TextField campoLimite;
    @FXML private CheckBox campoBloqueado;
    @FXML private Button botaoSalvarLimite;
    @FXML private Label saldoDevedor;
    @FXML private Label saldoVencido;
    @FXML private Label limiteDisponivel;
    @FXML private TableView<ExtratoCrediario.Parcela> tabelaParcelas;
    @FXML private TableColumn<ExtratoCrediario.Parcela, String> colunaParcelaVenda;
    @FXML private TableColumn<ExtratoCrediario.Parcela, String> colunaParcelaNumero;
    @FXML private TableColumn<ExtratoCrediario.Parcela, String> colunaParcelaVencimento;
    @FXML private TableColumn<ExtratoCrediario.Parcela, String> colunaParcelaValor;
    @FXML private TableColumn<ExtratoCrediario.Parcela, String> colunaParcelaAberto;
    @FXML private TableColumn<ExtratoCrediario.Parcela, String> colunaParcelaSituacao;
    @FXML private Button botaoExtratoPdf;
    @FXML private TableView<ExtratoCrediario.Lancamento> tabelaLancamentos;
    @FXML private TableColumn<ExtratoCrediario.Lancamento, String> colunaLancData;
    @FXML private TableColumn<ExtratoCrediario.Lancamento, String> colunaLancTipo;
    @FXML private TableColumn<ExtratoCrediario.Lancamento, String> colunaLancValor;
    @FXML private TableColumn<ExtratoCrediario.Lancamento, String> colunaLancSaldo;
    @FXML private Label detalheLancamento;
    @FXML private Label lgpdConsentimento;
    @FXML private Button botaoExportarPdf;
    @FXML private Button botaoExportarCsv;
    @FXML private Button botaoAnonimizar;
    @FXML private Label situacao;
    @FXML private Label mensagem;
    @FXML private Button botaoExcluir;
    @FXML private Button botaoSituacao;
    @FXML private Button botaoSalvar;

    public ClientesController(ClienteService clienteService, CreditoService creditoService, RelatorioPdf relatorioPdf,
                              Sessao sessao) {
        this.clienteService = clienteService;
        this.creditoService = creditoService;
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
        colunaDevolvido.setCellValueFactory(c -> celula(c.getValue().devolvido().signum() == 0 ? ""
                : Formatos.moeda(c.getValue().devolvido())));
        colunaValor.setCellValueFactory(c -> celula(Formatos.moeda(c.getValue().valor())));
        colunaProduto.setCellValueFactory(c -> celula(c.getValue().nome()));
        colunaVezes.setCellValueFactory(c -> celula(String.valueOf(c.getValue().compras())));
        colunaQuantidade.setCellValueFactory(c -> celula(Formatos.quantidade(c.getValue().quantidade(),
                c.getValue().fracionada()) + " " + c.getValue().unidade()));
        colunaValorProduto.setCellValueFactory(c -> celula(Formatos.moeda(c.getValue().valor())));
        colunaCreditoData.setCellValueFactory(c -> celula(Formatos.dataHora(c.getValue().criadoEm())));
        colunaCreditoTipo.setCellValueFactory(c -> celula(c.getValue().tipo().rotulo()));
        colunaCreditoOrigem.setCellValueFactory(c -> celula(c.getValue().origem()));
        colunaCreditoValor.setCellValueFactory(c -> celula(Formatos.moeda(c.getValue().valor())));
        colunaCreditoSaldo.setCellValueFactory(c -> celula(Formatos.moeda(c.getValue().saldoPosterior())));
        // O painel é estreito: a linha selecionada aparece inteira abaixo, com quem fez o movimento.
        tabelaCredito.getSelectionModel().selectedItemProperty().addListener((o, a, m) -> detalheCredito.setText(
                m == null ? "Selecione um movimento para ver o detalhe." : Formatos.dataHora(m.criadoEm()) + " — "
                        + m.tipo().rotulo() + " de " + Formatos.moeda(m.valor()) + " (" + m.origem() + "), saldo "
                        + Formatos.moeda(m.saldoAnterior()) + " → " + Formatos.moeda(m.saldoPosterior()) + ", por "
                        + m.usuario() + "."));
        tabelaCredito.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(MovimentoCredito m, boolean vazio) {
                super.updateItem(m, vazio);
                getStyleClass().remove("negativo");
                if (!vazio && m != null && m.saldoPosterior().signum() < 0) {
                    getStyleClass().add("negativo");
                }
            }
        });
        colunaParcelaVenda.setCellValueFactory(c -> celula(String.valueOf(c.getValue().vendaId())));
        colunaParcelaNumero.setCellValueFactory(c -> celula(c.getValue().rotulo()));
        colunaParcelaVencimento.setCellValueFactory(c -> celula(Formatos.data(c.getValue().vencimento())));
        colunaParcelaValor.setCellValueFactory(c -> celula(Formatos.moeda(c.getValue().valor())));
        colunaParcelaAberto.setCellValueFactory(c -> celula(Formatos.moeda(c.getValue().emAberto())));
        colunaParcelaSituacao.setCellValueFactory(c -> celula(c.getValue().diasAtraso() > 0
                ? c.getValue().diasAtraso() + " d atraso" : c.getValue().status().rotulo()));
        colunaLancData.setCellValueFactory(c -> celula(Formatos.dataHora(c.getValue().dataHora())));
        colunaLancTipo.setCellValueFactory(c -> celula(c.getValue().tipo()));
        colunaLancValor.setCellValueFactory(c -> celula(Formatos.moeda(c.getValue().valor())));
        colunaLancSaldo.setCellValueFactory(c -> celula(Formatos.moeda(c.getValue().saldo())));
        // O painel é estreito: o documento (qual parcela de qual venda) aparece inteiro abaixo.
        tabelaLancamentos.getSelectionModel().selectedItemProperty().addListener((o, a, l) -> detalheLancamento.setText(
                l == null ? "Selecione um lançamento para ver o detalhe." : l.tipo() + " — " + l.documento()
                        + (l.encargos().signum() == 0 ? "" : " — encargos " + Formatos.moeda(l.encargos()))));
        boolean gerencia = sessao.pode(Permissao.LIMITE_CREDITO);
        campoLimite.setEditable(gerencia);
        campoBloqueado.setDisable(!gerencia);
        botaoSalvarLimite.setVisible(gerencia);
        botaoSalvarLimite.setManaged(gerencia);
        botaoAjustarCredito.setVisible(sessao.supervisor());
        botaoAjustarCredito.setManaged(sessao.supervisor());

        campoUf.getItems().setAll(FornecedoresController.UFS);
        aoSairDoCampo(campoDocumento, this::mascararDocumento);
        aoSairDoCampo(campoTelefone, this::mascararTelefone);
        aoSairDoCampo(campoCep, this::mascararCep);
        botaoAnonimizar.setVisible(sessao.supervisor());
        botaoAnonimizar.setManaged(sessao.supervisor());
        Atalhos.de(raiz).em(Tecla.ctrl(KeyCode.N), "Novo cliente", this::novo);
        recarregar(null);
        novo();
    }

    /** Busca global (Ctrl+K, F21.3): mostra a ficha do cliente achado (também se estiver inativo). */
    public void mostrarCliente(long id) {
        campoBusca.clear();
        filtroInativos.setSelected(true);
        recarregar(id);
        tabela.scrollTo(tabela.getSelectionModel().getSelectedIndex());
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
        abaCredito.setDisable(true);
        abaCrediario.setDisable(true);
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
        abaCredito.setDisable(false);
        abaCrediario.setDisable(false);
        abaLgpd.setDisable(false);
        mostrarHistorico(clienteService.historico(sessao, c.id()));
        mostrarCredito(creditoService.extrato(sessao, c.id()));
        mostrarCrediario(clienteService.extratoCrediario(sessao, c.id()));
        Mensagem.limpar(mensagem);
    }

    /** Aba Crédito (F12.3): saldo (negativo em vermelho) e o extrato, do mais recente para o mais antigo. */
    private void mostrarCredito(ExtratoCredito extrato) {
        // No anonimizado, o ajuste só serve para zerar o saldo que sobrou (F13.2).
        botaoAjustarCredito.setDisable(emEdicao != null && emEdicao.anonimizado() && extrato.saldo().signum() == 0);
        saldoCredito.setText(Formatos.moeda(extrato.saldo()));
        saldoCredito.getStyleClass().remove("negativo");
        if (extrato.saldo().signum() < 0) {
            saldoCredito.getStyleClass().add("negativo");
        }
        List<MovimentoCredito> recentesPrimeiro = new ArrayList<>(extrato.movimentos());
        Collections.reverse(recentesPrimeiro);
        tabelaCredito.getItems().setAll(recentesPrimeiro);
        detalheCredito.setText(recentesPrimeiro.isEmpty() ? "" : "Selecione um movimento para ver o detalhe.");
    }

    @FXML
    private void ajustarCredito() {
        Cliente c = emEdicao;
        if (c == null) {
            return;
        }
        BigDecimal saldo = creditoService.extrato(sessao, c.id()).saldo();
        DialogosCliente.ajustarCredito(c.id(), c.nome(), saldo, c.anonimizado(),
                (valor, motivo) -> creditoService.ajustar(sessao, c.id(), valor, motivo)).ifPresent(novo -> {
                    mostrarCredito(creditoService.extrato(sessao, c.id()));
                    Mensagem.sucesso(mensagem, "Crédito ajustado. Saldo atual: " + Formatos.moeda(novo) + ".");
                });
    }

    /** Aba Crediário (F14.4): limite, bloqueio, saldo devedor, parcelas e lançamentos, do mais recente para o mais antigo. */
    private void mostrarCrediario(ExtratoCrediario e) {
        campoLimite.setText(Formatos.numero(e.limite(), Dinheiro.ESCALA_DINHEIRO));
        campoBloqueado.setSelected(e.bloqueado());
        saldoDevedor.setText(Formatos.moeda(e.saldoDevedor()));
        saldoVencido.setText(Formatos.moeda(e.vencido()));
        saldoVencido.getStyleClass().remove("negativo");
        if (e.vencido().signum() > 0) {
            saldoVencido.getStyleClass().add("negativo");
        }
        limiteDisponivel.setText(Formatos.moeda(e.limiteDisponivel()));
        limiteDisponivel.getStyleClass().remove("negativo");
        if (e.limiteDisponivel().signum() < 0) {
            limiteDisponivel.getStyleClass().add("negativo");
        }
        tabelaParcelas.getItems().setAll(e.parcelas());
        List<ExtratoCrediario.Lancamento> recentesPrimeiro = new ArrayList<>(e.lancamentos());
        Collections.reverse(recentesPrimeiro);
        tabelaLancamentos.getItems().setAll(recentesPrimeiro);
        detalheLancamento.setText(recentesPrimeiro.isEmpty() ? "" : "Selecione um lançamento para ver o detalhe.");
        boolean anonimizado = emEdicao != null && emEdicao.anonimizado();
        botaoSalvarLimite.setDisable(anonimizado);
    }

    @FXML
    private void salvarLimite() {
        if (emEdicao == null) {
            return;
        }
        try {
            clienteService.alterarCrediario(sessao, emEdicao.id(), CampoNumero.ler(campoLimite.getText(),
                    "Limite de crédito"), campoBloqueado.isSelected());
            mostrarCrediario(clienteService.extratoCrediario(sessao, emEdicao.id()));
            Mensagem.sucesso(mensagem, "Limite e bloqueio do crediário salvos.");
        } catch (RuntimeException e) {
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(e));
        }
    }

    @FXML
    private void exportarExtrato() {
        if (emEdicao != null) {
            Exportacao.escolher(raiz.getScene().getWindow(), "PDF", "pdf",
                    "extrato_crediario_" + emEdicao.id() + ".pdf").ifPresent(this::gravarExtrato);
        }
    }

    /** Lê o extrato do banco e grava o PDF em {@link Task}. */
    Task<Void> gravarExtrato(Path destino) {
        try {
            ResultadoRelatorio<RelExtratoCrediario.Linha> dados = clienteService.exportarExtrato(sessao, emEdicao.id());
            return Exportacao.gravar("PDF", destino, d -> relatorioPdf.salvar(dados, d), mensagem,
                    List.of(botaoExtratoPdf));
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
            return null;
        }
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
