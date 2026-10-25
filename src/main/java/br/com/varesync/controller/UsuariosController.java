package br.com.varesync.controller;

import br.com.varesync.model.Permissao;
import br.com.varesync.model.PoliticaSenha;
import br.com.varesync.model.Usuario;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.Sessao;
import br.com.varesync.service.UsuarioService;
import br.com.varesync.ui.DialogoSenha;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.util.Formatos;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;

/**
 * Usuários e permissões (Fig. 16 revisada): lista à esquerda e formulário à direita.
 * Só o supervisor chega aqui; o {@link UsuarioService} confere de novo.
 */
public class UsuariosController {

    private final UsuarioService usuarioService;
    private final Sessao sessao;
    private final Clock clock;
    private final Map<Permissao, CheckBox> caixasPermissao = new EnumMap<>(Permissao.class);
    /** Usuário em edição; nulo no cadastro novo. */
    private Usuario emEdicao;

    @FXML private TableView<Usuario> tabela;
    @FXML private TableColumn<Usuario, String> colunaLogin;
    @FXML private TableColumn<Usuario, String> colunaNome;
    @FXML private TableColumn<Usuario, String> colunaPerfil;
    @FXML private TableColumn<Usuario, String> colunaSituacao;
    @FXML private TableColumn<Usuario, String> colunaUltimoLogin;
    @FXML private Label tituloFormulario;
    @FXML private TextField campoLogin;
    @FXML private TextField campoNome;
    @FXML private CheckBox campoSupervisor;
    @FXML private Label avisoSupervisor;
    @FXML private FlowPane painelPermissoes;
    @FXML private GridPane painelSenha;
    @FXML private PasswordField campoSenha;
    @FXML private PasswordField campoConfirmacao;
    @FXML private Label situacao;
    @FXML private Label mensagem;
    @FXML private Button botaoRedefinirSenha;
    @FXML private Button botaoDesbloquear;
    @FXML private Button botaoSituacao;

    public UsuariosController(UsuarioService usuarioService, Sessao sessao, Clock clock) {
        this.usuarioService = usuarioService;
        this.sessao = sessao;
        this.clock = clock;
    }

    @FXML
    private void initialize() {
        coluna(colunaLogin, Usuario::login);
        coluna(colunaNome, Usuario::nome);
        coluna(colunaPerfil, u -> u.supervisor() ? "Supervisor" : "Operador");
        coluna(colunaSituacao, this::descreverSituacao);
        coluna(colunaUltimoLogin, u -> Formatos.dataHora(u.ultimoLoginEm()));
        tabela.getSelectionModel().selectedItemProperty().addListener((obs, anterior, atual) -> {
            if (atual != null) {
                editar(atual);
            }
        });

        for (Permissao permissao : Permissao.values()) {
            CheckBox caixa = new CheckBox(permissao.rotulo());
            caixa.setMinWidth(190);
            caixasPermissao.put(permissao, caixa);
            painelPermissoes.getChildren().add(caixa);
        }
        campoSupervisor.selectedProperty().addListener((obs, anterior, marcado) -> {
            painelPermissoes.setDisable(marcado);
            avisoSupervisor.setVisible(marcado);
        });
        avisoSupervisor.managedProperty().bind(avisoSupervisor.visibleProperty());
        painelSenha.managedProperty().bind(painelSenha.visibleProperty());

        recarregar(null);
        novo();
    }

    private static void coluna(TableColumn<Usuario, String> coluna, Function<Usuario, String> valor) {
        coluna.setCellValueFactory(c -> new ReadOnlyStringWrapper(valor.apply(c.getValue())));
    }

    private String descreverSituacao(Usuario usuario) {
        if (!usuario.ativo()) {
            return "Inativo";
        }
        if (usuario.bloqueadoEm(OffsetDateTime.now(clock))) {
            return "Bloqueado até " + Formatos.hora(usuario.bloqueadoAte());
        }
        return usuario.deveTrocarSenha() ? "Ativo (senha provisória)" : "Ativo";
    }

    /** Recarrega a lista e seleciona o usuário {@code id}, se informado. */
    private void recarregar(Long id) {
        tabela.getItems().setAll(usuarioService.listar(sessao));
        if (id != null) {
            tabela.getItems().stream().filter(u -> u.id() == id).findFirst()
                    .ifPresent(u -> tabela.getSelectionModel().select(u));
        }
    }

    @FXML
    private void novo() {
        emEdicao = null;
        tabela.getSelectionModel().clearSelection();
        tituloFormulario.setText("Novo usuário");
        campoLogin.setText("");
        campoLogin.setDisable(false);
        campoNome.setText("");
        campoSupervisor.setSelected(false);
        caixasPermissao.values().forEach(c -> c.setSelected(false));
        campoSenha.clear();
        campoConfirmacao.clear();
        PoliticaSenha politica = usuarioService.politicaSenha();
        campoSenha.setPromptText("ao menos " + politica.tamanhoMinimo() + " caracteres");
        campoSenha.setTooltip(new Tooltip(politica.descricao()));
        painelSenha.setVisible(true);
        situacao.setText("");
        botaoRedefinirSenha.setDisable(true);
        botaoDesbloquear.setDisable(true);
        botaoSituacao.setDisable(true);
        botaoSituacao.setText("Inativar");
        Mensagem.limpar(mensagem);
        Platform.runLater(campoLogin::requestFocus);
    }

    private void editar(Usuario usuario) {
        emEdicao = usuario;
        tituloFormulario.setText("Editar usuário");
        campoLogin.setText(usuario.login());
        campoLogin.setDisable(true); // o login não muda depois de criado
        campoNome.setText(usuario.nome());
        campoSupervisor.setSelected(usuario.supervisor());
        caixasPermissao.forEach((p, caixa) -> caixa.setSelected(usuario.permissoes().contains(p)));
        painelSenha.setVisible(false);
        situacao.setText("Situação: " + descreverSituacao(usuario));
        boolean euMesmo = usuario.id() == sessao.usuarioId();
        botaoRedefinirSenha.setDisable(false);
        botaoDesbloquear.setDisable(!usuario.bloqueadoEm(OffsetDateTime.now(clock))
                && usuario.tentativasFalhas() == 0);
        botaoSituacao.setText(usuario.ativo() ? "Inativar" : "Reativar");
        botaoSituacao.setDisable(euMesmo && usuario.ativo());
        Mensagem.limpar(mensagem);
    }

    private Set<Permissao> permissoesMarcadas() {
        Set<Permissao> marcadas = EnumSet.noneOf(Permissao.class);
        caixasPermissao.forEach((p, caixa) -> {
            if (caixa.isSelected()) {
                marcadas.add(p);
            }
        });
        return marcadas;
    }

    @FXML
    private void salvar() {
        try {
            long id;
            if (emEdicao == null) {
                id = usuarioService.criar(sessao, campoLogin.getText(), campoNome.getText(),
                        campoSupervisor.isSelected(), permissoesMarcadas(), campoSenha.getText(),
                        campoConfirmacao.getText());
            } else {
                id = emEdicao.id();
                usuarioService.alterar(sessao, id, campoNome.getText(), campoSupervisor.isSelected(),
                        permissoesMarcadas());
            }
            recarregar(id);
            Mensagem.sucesso(mensagem, "Usuário salvo.");
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    @FXML
    private void redefinirSenha() {
        if (emEdicao == null) {
            return;
        }
        long id = emEdicao.id();
        boolean redefinida = DialogoSenha.definirProvisoria(emEdicao.descricao(),
                usuarioService.politicaSenha().descricao(), (nova, confirmacao) -> usuarioService.redefinirSenha(sessao, id, nova, confirmacao));
        if (redefinida) {
            recarregar(id);
            Mensagem.sucesso(mensagem, "Senha provisória definida. O usuário terá de trocá-la ao entrar.");
        }
    }

    @FXML
    private void desbloquear() {
        if (emEdicao == null) {
            return;
        }
        long id = emEdicao.id();
        executar(() -> usuarioService.desbloquear(sessao, id), id, "Usuário desbloqueado.");
    }

    @FXML
    private void alternarSituacao() {
        if (emEdicao == null) {
            return;
        }
        long id = emEdicao.id();
        boolean ativar = !emEdicao.ativo();
        if (!ativar && !Dialogos.confirmar("Inativar o usuário " + emEdicao.descricao()
                + "? Ele não poderá mais entrar no sistema.")) {
            return;
        }
        executar(() -> usuarioService.alterarSituacao(sessao, id, ativar), id,
                ativar ? "Usuário reativado." : "Usuário inativado.");
    }

    private void executar(Runnable acao, long id, String sucesso) {
        try {
            acao.run();
            recarregar(id);
            Mensagem.sucesso(mensagem, sucesso);
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }
}
