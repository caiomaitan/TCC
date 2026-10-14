package br.com.varesync.controller;

import br.com.varesync.model.Categoria;
import br.com.varesync.service.CategoriaService;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.RegistroEmUsoException;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.Mensagem;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

/** Cadastro simples de categorias (6.3): criar, renomear, inativar/reativar e excluir se não estiver em uso. */
public class CategoriasController {

    private final CategoriaService categoriaService;
    private final Sessao sessao;
    /** Categoria em edição; nula na categoria nova. */
    private Categoria emEdicao;

    @FXML private TableView<Categoria> tabela;
    @FXML private TableColumn<Categoria, String> colunaNome;
    @FXML private TableColumn<Categoria, String> colunaSituacao;
    @FXML private Label tituloFormulario;
    @FXML private TextField campoNome;
    @FXML private Label mensagem;
    @FXML private Button botaoExcluir;
    @FXML private Button botaoSituacao;

    public CategoriasController(CategoriaService categoriaService, Sessao sessao) {
        this.categoriaService = categoriaService;
        this.sessao = sessao;
    }

    @FXML
    private void initialize() {
        colunaNome.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().nome()));
        colunaSituacao.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().ativa() ? "Ativa" : "Inativa"));
        tabela.getSelectionModel().selectedItemProperty().addListener((obs, anterior, atual) -> {
            if (atual != null) {
                editar(atual);
            }
        });
        recarregar(null);
        nova();
    }

    private void recarregar(Long id) {
        tabela.getItems().setAll(categoriaService.listar(sessao, true));
        if (id != null) {
            tabela.getItems().stream().filter(c -> c.id() == id).findFirst()
                    .ifPresent(c -> tabela.getSelectionModel().select(c));
        }
    }

    @FXML
    private void nova() {
        emEdicao = null;
        tabela.getSelectionModel().clearSelection();
        tituloFormulario.setText("Nova");
        campoNome.clear();
        botaoExcluir.setDisable(true);
        botaoSituacao.setDisable(true);
        botaoSituacao.setText("Inativar");
        Mensagem.limpar(mensagem);
        Platform.runLater(campoNome::requestFocus);
    }

    private void editar(Categoria categoria) {
        emEdicao = categoria;
        tituloFormulario.setText("Renomear");
        campoNome.setText(categoria.nome());
        botaoExcluir.setDisable(false);
        botaoSituacao.setDisable(false);
        botaoSituacao.setText(categoria.ativa() ? "Inativar" : "Reativar");
        Mensagem.limpar(mensagem);
    }

    @FXML
    private void salvar() {
        try {
            long id;
            if (emEdicao == null) {
                id = categoriaService.criar(sessao, campoNome.getText());
            } else {
                id = emEdicao.id();
                categoriaService.renomear(sessao, id, campoNome.getText());
            }
            recarregar(id);
            Mensagem.sucesso(mensagem, "Categoria salva.");
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    @FXML
    private void excluir() {
        if (emEdicao == null) {
            return;
        }
        Categoria categoria = emEdicao;
        if (!Dialogos.confirmar("Excluir a categoria \"" + categoria.nome() + "\"?")) {
            return;
        }
        try {
            categoriaService.excluir(sessao, categoria.id());
            recarregar(null);
            nova();
            Mensagem.sucesso(mensagem, "Categoria excluída.");
        } catch (RegistroEmUsoException e) {
            if (categoria.ativa() && Dialogos.confirmar(e.getMessage() + "\n\nInativar a categoria agora?")) {
                mudarSituacao(categoria, false);
            } else {
                Mensagem.erro(mensagem, e.getMessage());
            }
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    @FXML
    private void alternarSituacao() {
        if (emEdicao != null) {
            mudarSituacao(emEdicao, !emEdicao.ativa());
        }
    }

    private void mudarSituacao(Categoria categoria, boolean ativa) {
        try {
            categoriaService.alterarSituacao(sessao, categoria.id(), ativa);
            recarregar(categoria.id());
            Mensagem.sucesso(mensagem, ativa ? "Categoria reativada." : "Categoria inativada.");
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    @FXML
    private void fechar() {
        ((Stage) tabela.getScene().getWindow()).close();
    }
}
