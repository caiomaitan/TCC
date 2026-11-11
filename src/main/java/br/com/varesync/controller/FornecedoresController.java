package br.com.varesync.controller;

import br.com.varesync.model.DadosFornecedor;
import br.com.varesync.model.Fornecedor;
import br.com.varesync.service.FornecedorService;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.RegistroEmUsoException;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.util.CpfCnpj;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.util.List;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
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
 * Fornecedores (6.10): lista com busca à esquerda e formulário à direita. CPF/CNPJ e telefone ganham a máscara ao
 * sair do campo; o {@link FornecedorService} grava sem máscara e confere a permissão FORNECEDORES.
 */
public class FornecedoresController {

    static final List<String> UFS = List.of("", "AC", "AL", "AM", "AP", "BA", "CE", "DF", "ES", "GO", "MA", "MG",
            "MS", "MT", "PA", "PB", "PE", "PI", "PR", "RJ", "RN", "RO", "RR", "RS", "SC", "SE", "SP", "TO");

    private final FornecedorService fornecedorService;
    private final Sessao sessao;
    /** Fornecedor em edição; nulo no cadastro novo. */
    private Fornecedor emEdicao;

    @FXML private HBox raiz;
    @FXML private TextField campoBusca;
    @FXML private CheckBox filtroInativos;
    @FXML private TableView<Fornecedor> tabela;
    @FXML private TableColumn<Fornecedor, Long> colunaCodigo;
    @FXML private TableColumn<Fornecedor, String> colunaRazao;
    @FXML private TableColumn<Fornecedor, String> colunaFantasia;
    @FXML private TableColumn<Fornecedor, String> colunaDocumento;
    @FXML private TableColumn<Fornecedor, String> colunaCidade;
    @FXML private TableColumn<Fornecedor, String> colunaTelefone;
    @FXML private TableColumn<Fornecedor, String> colunaSituacao;
    @FXML private Label resumo;
    @FXML private Label tituloFormulario;
    @FXML private TextField campoRazao;
    @FXML private TextField campoFantasia;
    @FXML private TextField campoDocumento;
    @FXML private TextField campoTelefone;
    @FXML private TextField campoEmail;
    @FXML private TextField campoContato;
    @FXML private TextField campoCidade;
    @FXML private ComboBox<String> campoUf;
    @FXML private TextArea campoObservacao;
    @FXML private Label situacao;
    @FXML private Label mensagem;
    @FXML private Button botaoExcluir;
    @FXML private Button botaoSituacao;

    public FornecedoresController(FornecedorService fornecedorService, Sessao sessao) {
        this.fornecedorService = fornecedorService;
        this.sessao = sessao;
    }

    @FXML
    private void initialize() {
        colunaCodigo.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().id()));
        coluna(colunaRazao, Fornecedor::razaoSocial);
        coluna(colunaFantasia, f -> f.nomeFantasia() == null ? "" : f.nomeFantasia());
        coluna(colunaDocumento, Fornecedor::documentoFormatado);
        coluna(colunaCidade, Fornecedor::cidadeUf);
        coluna(colunaTelefone, Fornecedor::telefoneFormatado);
        coluna(colunaSituacao, f -> f.ativo() ? "Ativo" : "Inativo");
        tabela.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(Fornecedor f, boolean vazio) {
                super.updateItem(f, vazio);
                getStyleClass().remove("inativo");
                if (!vazio && f != null && !f.ativo()) {
                    getStyleClass().add("inativo");
                }
            }
        });
        tabela.getSelectionModel().selectedItemProperty().addListener((obs, anterior, atual) -> {
            if (atual != null) {
                editar(atual);
            }
        });
        campoUf.getItems().setAll(UFS);
        campoDocumento.focusedProperty().addListener((obs, antes, focado) -> {
            if (!focado) {
                mascararDocumento();
            }
        });
        campoTelefone.focusedProperty().addListener((obs, antes, focado) -> {
            if (!focado) {
                mascararTelefone();
            }
        });
        Atalhos.de(raiz).em(Tecla.ctrl(KeyCode.N), "Novo fornecedor", this::novo);
        recarregar(null);
        novo();
    }

    /** Busca global (Ctrl+K, F21.3): mostra o cadastro do fornecedor achado (também se estiver inativo). */
    public void mostrarFornecedor(long id) {
        campoBusca.clear();
        filtroInativos.setSelected(true);
        recarregar(id);
        tabela.scrollTo(tabela.getSelectionModel().getSelectedIndex());
    }

    private static void coluna(TableColumn<Fornecedor, String> coluna, Function<Fornecedor, String> valor) {
        coluna.setCellValueFactory(c -> new ReadOnlyStringWrapper(valor.apply(c.getValue())));
    }

    @FXML
    private void pesquisar() {
        recarregar(emEdicao == null ? null : emEdicao.id());
    }

    /** Refaz a busca e seleciona o fornecedor {@code id}, se ele estiver na lista. */
    private void recarregar(Long id) {
        List<Fornecedor> lista = fornecedorService.pesquisar(sessao, campoBusca.getText(), filtroInativos.isSelected());
        tabela.getItems().setAll(lista);
        resumo.setText(lista.size() == 1 ? "1 fornecedor" : lista.size() + " fornecedores");
        if (id != null) {
            tabela.getItems().stream().filter(f -> f.id() == id).findFirst()
                    .ifPresent(f -> tabela.getSelectionModel().select(f));
        }
    }

    @FXML
    private void novo() {
        emEdicao = null;
        tabela.getSelectionModel().clearSelection();
        tituloFormulario.setText("Novo fornecedor");
        for (TextField campo : List.of(campoRazao, campoFantasia, campoDocumento, campoTelefone, campoEmail,
                campoContato, campoCidade)) {
            campo.clear();
        }
        campoUf.setValue("");
        campoObservacao.clear();
        situacao.setText("");
        botaoExcluir.setDisable(true);
        botaoSituacao.setDisable(true);
        botaoSituacao.setText("Inativar");
        Mensagem.limpar(mensagem);
        Platform.runLater(campoRazao::requestFocus);
    }

    private void editar(Fornecedor f) {
        emEdicao = f;
        tituloFormulario.setText("Fornecedor " + f.id());
        campoRazao.setText(f.razaoSocial());
        campoFantasia.setText(texto(f.nomeFantasia()));
        campoDocumento.setText(f.documentoFormatado());
        campoTelefone.setText(f.telefoneFormatado());
        campoEmail.setText(texto(f.email()));
        campoContato.setText(texto(f.contato()));
        campoCidade.setText(texto(f.cidade()));
        campoUf.setValue(f.uf() == null ? "" : f.uf());
        campoObservacao.setText(texto(f.observacao()));
        situacao.setText((f.ativo() ? "Ativo" : "Inativo") + " — última alteração em "
                + Formatos.dataHora(f.atualizadoEm()));
        botaoExcluir.setDisable(false);
        botaoSituacao.setDisable(false);
        botaoSituacao.setText(f.ativo() ? "Inativar" : "Reativar");
        Mensagem.limpar(mensagem);
    }

    @FXML
    private void salvar() {
        DadosFornecedor dados = new DadosFornecedor(campoRazao.getText(), campoFantasia.getText(),
                campoDocumento.getText(), campoTelefone.getText(), campoEmail.getText(), campoContato.getText(),
                campoCidade.getText(), campoUf.getValue(), campoObservacao.getText());
        try {
            long id;
            if (emEdicao == null) {
                id = fornecedorService.criar(sessao, dados);
            } else {
                id = emEdicao.id();
                fornecedorService.alterar(sessao, id, dados);
            }
            recarregar(id);
            if (tabela.getSelectionModel().getSelectedItem() == null) {
                editar(fornecedorService.buscar(sessao, id)); // fora do filtro atual
            }
            Mensagem.sucesso(mensagem, "Fornecedor salvo.");
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    @FXML
    private void excluir() {
        if (emEdicao == null) {
            return;
        }
        Fornecedor f = emEdicao;
        if (!Dialogos.confirmar("Excluir o fornecedor \"" + f.razaoSocial() + "\"?")) {
            return;
        }
        try {
            fornecedorService.excluir(sessao, f.id());
            recarregar(null);
            novo();
            Mensagem.sucesso(mensagem, "Fornecedor excluído.");
        } catch (RegistroEmUsoException e) {
            if (f.ativo() && Dialogos.confirmar(e.getMessage() + "\n\nInativar o fornecedor agora?")) {
                mudarSituacao(f, false);
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
        if (emEdicao.ativo() && !Dialogos.confirmar("Inativar o fornecedor \"" + emEdicao.razaoSocial() + "\"? Ele "
                + "sai da escolha de novas entradas, mas continua nas entradas lançadas e nos relatórios.")) {
            return;
        }
        mudarSituacao(emEdicao, !emEdicao.ativo());
    }

    private void mudarSituacao(Fornecedor f, boolean ativo) {
        try {
            fornecedorService.alterarSituacao(sessao, f.id(), ativo);
            if (!ativo) {
                filtroInativos.setSelected(true); // continua à vista, esmaecido
            }
            recarregar(f.id());
            Mensagem.sucesso(mensagem, ativo ? "Fornecedor reativado." : "Fornecedor inativado.");
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

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

    private static String texto(String valor) {
        return valor == null ? "" : valor;
    }
}
