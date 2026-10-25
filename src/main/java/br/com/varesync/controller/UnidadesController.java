package br.com.varesync.controller;

import br.com.varesync.model.UnidadeCadastro;
import br.com.varesync.model.UnidadeMedida;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.Sessao;
import br.com.varesync.service.UnidadeService;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.Mensagem;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

/**
 * Cadastro de unidades de medida (F10.5), aberto pela tela Produtos: criar, alterar e excluir. Com a unidade em uso, a
 * sigla e "fracionada" ficam travadas na tela (o service também recusa) e só a descrição muda.
 */
public class UnidadesController {

    private final UnidadeService unidadeService;
    private final Sessao sessao;
    /** Unidade em edição; nula na unidade nova. */
    private UnidadeCadastro emEdicao;

    @FXML private TableView<UnidadeCadastro> tabela;
    @FXML private TableColumn<UnidadeCadastro, String> colunaSigla;
    @FXML private TableColumn<UnidadeCadastro, String> colunaDescricao;
    @FXML private TableColumn<UnidadeCadastro, String> colunaFracionada;
    @FXML private TableColumn<UnidadeCadastro, String> colunaProdutos;
    @FXML private Label tituloFormulario;
    @FXML private TextField campoSigla;
    @FXML private TextField campoDescricao;
    @FXML private CheckBox campoFracionada;
    @FXML private Label situacaoUso;
    @FXML private Label mensagem;
    @FXML private Button botaoExcluir;

    public UnidadesController(UnidadeService unidadeService, Sessao sessao) {
        this.unidadeService = unidadeService;
        this.sessao = sessao;
    }

    @FXML
    private void initialize() {
        colunaSigla.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().unidade().sigla()));
        colunaDescricao.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().unidade().descricao()));
        colunaFracionada.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().unidade().fracionada() ? "Sim" : "Não"));
        colunaProdutos.setCellValueFactory(c -> new ReadOnlyStringWrapper(String.valueOf(c.getValue().produtos())));
        tabela.getSelectionModel().selectedItemProperty().addListener((obs, anterior, atual) -> {
            if (atual != null) {
                editar(atual);
            }
        });
        recarregar(null);
        nova();
    }

    private void recarregar(String sigla) {
        tabela.getItems().setAll(unidadeService.listar(sessao));
        if (sigla != null) {
            tabela.getItems().stream().filter(u -> u.unidade().sigla().equals(sigla)).findFirst()
                    .ifPresent(u -> tabela.getSelectionModel().select(u));
        }
    }

    @FXML
    private void nova() {
        emEdicao = null;
        tabela.getSelectionModel().clearSelection();
        tituloFormulario.setText("Nova unidade");
        campoSigla.clear();
        campoDescricao.clear();
        campoFracionada.setSelected(false);
        campoSigla.setDisable(false);
        campoFracionada.setDisable(false);
        situacaoUso.setText("Fracionada aceita quantidade com casas decimais (ex.: KG, L, M).");
        botaoExcluir.setDisable(true);
        Mensagem.limpar(mensagem);
        Platform.runLater(campoSigla::requestFocus);
    }

    private void editar(UnidadeCadastro cadastro) {
        emEdicao = cadastro;
        UnidadeMedida unidade = cadastro.unidade();
        boolean padrao = UnidadeMedida.PADRAO.equals(unidade.sigla());
        tituloFormulario.setText("Alterar " + unidade.sigla());
        campoSigla.setText(unidade.sigla());
        campoDescricao.setText(unidade.descricao());
        campoFracionada.setSelected(unidade.fracionada());
        campoSigla.setDisable(cadastro.emUso() || padrao);
        campoFracionada.setDisable(cadastro.emUso());
        botaoExcluir.setDisable(cadastro.emUso() || padrao);
        situacaoUso.setText(cadastro.emUso()
                ? "Usada por " + (cadastro.produtos() == 1 ? "1 produto" : cadastro.produtos() + " produtos")
                        + ": só a descrição pode mudar, e ela não pode ser excluída."
                : padrao ? "UN é a unidade padrão do cadastro: a sigla não muda e ela não pode ser excluída."
                : "Nenhum produto usa esta unidade: tudo pode mudar, e ela pode ser excluída.");
        Mensagem.limpar(mensagem);
    }

    @FXML
    private void salvar() {
        try {
            UnidadeMedida gravada = emEdicao == null
                    ? unidadeService.criar(sessao, campoSigla.getText(), campoDescricao.getText(),
                            campoFracionada.isSelected())
                    : unidadeService.alterar(sessao, emEdicao.unidade().sigla(), campoSigla.getText(),
                            campoDescricao.getText(), campoFracionada.isSelected());
            recarregar(gravada.sigla());
            Mensagem.sucesso(mensagem, "Unidade " + gravada.sigla() + " salva.");
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    @FXML
    private void excluir() {
        if (emEdicao == null) {
            return;
        }
        UnidadeMedida unidade = emEdicao.unidade();
        if (!Dialogos.confirmar("Excluir a unidade " + unidade.sigla() + " (" + unidade.descricao() + ")?")) {
            return;
        }
        try {
            unidadeService.excluir(sessao, unidade.sigla());
            recarregar(null);
            nova();
            Mensagem.sucesso(mensagem, "Unidade " + unidade.sigla() + " excluída.");
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    @FXML
    private void fechar() {
        ((Stage) tabela.getScene().getWindow()).close();
    }
}
