package br.com.varesync.controller;

import br.com.varesync.model.DadosPerfil;
import br.com.varesync.model.PerfilTributario;
import br.com.varesync.model.RegimeTributario;
import br.com.varesync.service.FiscalService;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.RegistroEmUsoException;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.util.function.Function;
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
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

/**
 * Perfis tributários (F19.2), aba da janela Cadastro fiscal. A situação do ICMS é o CST (regime normal) ou o CSOSN
 * (Simples Nacional); o perfil novo começa pelo tipo do regime da loja. Excluir só o perfil sem produtos; em uso, a
 * tela oferece inativar.
 */
public class PerfisTributariosController {

    static final String TIPO_CST = "CST (regime normal)";
    static final String TIPO_CSOSN = "CSOSN (Simples Nacional)";

    private final FiscalService service;
    private final Sessao sessao;
    private PerfilTributario editando;

    @FXML private TableView<PerfilTributario> tabelaPerfis;
    @FXML private TableColumn<PerfilTributario, String> colunaPerfilNome;
    @FXML private TableColumn<PerfilTributario, String> colunaPerfilCfop;
    @FXML private TableColumn<PerfilTributario, String> colunaPerfilIcms;
    @FXML private TableColumn<PerfilTributario, String> colunaPerfilPisCofins;
    @FXML private TableColumn<PerfilTributario, Long> colunaPerfilProdutos;
    @FXML private Button botaoExcluirPerfil;
    @FXML private Label tituloPerfil;
    @FXML private GridPane gradePerfil;
    @FXML private TextField campoNomePerfil;
    @FXML private TextField campoCfop;
    @FXML private ComboBox<String> campoTipoIcms;
    @FXML private TextField campoSituacaoIcms;
    @FXML private TextField campoAliquotaIcms;
    @FXML private TextField campoReducao;
    @FXML private TextField campoCstPis;
    @FXML private TextField campoAliquotaPis;
    @FXML private TextField campoCstCofins;
    @FXML private TextField campoAliquotaCofins;
    @FXML private TextField campoBeneficio;
    @FXML private TextField campoCstIbsCbs;
    @FXML private TextField campoCclassTrib;
    @FXML private CheckBox campoPerfilAtivo;
    @FXML private Button botaoSalvarPerfil;
    @FXML private Label mensagemPerfil;

    public PerfisTributariosController(FiscalService service, Sessao sessao) {
        this.service = service;
        this.sessao = sessao;
    }

    @FXML
    private void initialize() {
        texto(colunaPerfilNome, PerfilTributario::toString);
        texto(colunaPerfilCfop, PerfilTributario::cfop);
        texto(colunaPerfilIcms, p -> p.situacaoIcms() + " · " + Formatos.percentual(p.aliquotaIcms())
                + (p.reducaoBaseIcms() == null ? "" : " (red. " + Formatos.percentual(p.reducaoBaseIcms()) + ")"));
        texto(colunaPerfilPisCofins, p -> p.cstPis() + " / " + p.cstCofins());
        colunaPerfilProdutos.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().produtos()));
        tabelaPerfis.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(PerfilTributario p, boolean vazio) {
                super.updateItem(p, vazio);
                getStyleClass().remove("inativo");
                if (!vazio && p != null && !p.ativo()) {
                    getStyleClass().add("inativo");
                }
            }
        });
        tabelaPerfis.getSelectionModel().selectedItemProperty().addListener((o, a, p) -> {
            if (p != null) {
                mostrar(p);
            }
        });
        campoTipoIcms.getItems().setAll(TIPO_CST, TIPO_CSOSN);
        campoTipoIcms.valueProperty().addListener((o, a, tipo) ->
                campoSituacaoIcms.setPromptText(TIPO_CSOSN.equals(tipo) ? "102" : "00"));
        carregar(null);
        novo();
    }

    private static void texto(TableColumn<PerfilTributario, String> coluna,
                              Function<PerfilTributario, String> valor) {
        coluna.setCellValueFactory(c -> new ReadOnlyStringWrapper(valor.apply(c.getValue())));
    }

    private void carregar(Long selecionar) {
        tabelaPerfis.getItems().setAll(service.perfis(sessao, false));
        if (selecionar != null) {
            tabelaPerfis.getItems().stream().filter(p -> p.id() == selecionar).findFirst()
                    .ifPresent(p -> tabelaPerfis.getSelectionModel().select(p));
        }
    }

    /** Formulário vazio, com o tipo de situação do ICMS do regime da loja. */
    @FXML
    private void novo() {
        editando = null;
        tabelaPerfis.getSelectionModel().clearSelection();
        tituloPerfil.setText("Novo perfil");
        boolean simples = service.empresa(sessao).regime() == RegimeTributario.SIMPLES;
        campoNomePerfil.setText("");
        campoCfop.setText("");
        campoTipoIcms.setValue(simples ? TIPO_CSOSN : TIPO_CST);
        campoSituacaoIcms.setText("");
        campoAliquotaIcms.setText("");
        campoReducao.setText("");
        campoCstPis.setText("");
        campoAliquotaPis.setText("");
        campoCstCofins.setText("");
        campoAliquotaCofins.setText("");
        campoBeneficio.setText("");
        campoCstIbsCbs.setText("");
        campoCclassTrib.setText("");
        campoPerfilAtivo.setSelected(true);
        botaoExcluirPerfil.setDisable(true);
        Mensagem.limpar(mensagemPerfil);
        campoNomePerfil.requestFocus();
    }

    private void mostrar(PerfilTributario p) {
        editando = p;
        tituloPerfil.setText("Perfil \"" + p.nome() + "\"" + (p.produtos() == 0 ? " (nenhum produto)"
                : p.produtos() == 1 ? " (1 produto)" : " (" + p.produtos() + " produtos)"));
        campoNomePerfil.setText(p.nome());
        campoCfop.setText(p.cfop());
        campoTipoIcms.setValue(p.usaCsosn() ? TIPO_CSOSN : TIPO_CST);
        campoSituacaoIcms.setText(p.usaCsosn() ? p.csosn() : p.cstIcms());
        campoAliquotaIcms.setText(percentual(p.aliquotaIcms()));
        campoReducao.setText(percentual(p.reducaoBaseIcms()));
        campoCstPis.setText(p.cstPis());
        campoAliquotaPis.setText(percentual(p.aliquotaPis()));
        campoCstCofins.setText(p.cstCofins());
        campoAliquotaCofins.setText(percentual(p.aliquotaCofins()));
        campoBeneficio.setText(p.codigoBeneficio() == null ? "" : p.codigoBeneficio());
        campoCstIbsCbs.setText(p.cstIbsCbs() == null ? "" : p.cstIbsCbs());
        campoCclassTrib.setText(p.cclassTrib() == null ? "" : p.cclassTrib());
        campoPerfilAtivo.setSelected(p.ativo());
        botaoExcluirPerfil.setDisable(false);
        Mensagem.limpar(mensagemPerfil);
    }

    private static String percentual(BigDecimal valor) {
        return valor == null ? "" : Formatos.numero(valor, Dinheiro.ESCALA_PERCENTUAL);
    }

    /** Campo de percentual: vazio = nulo; texto que não é número vira mensagem com o nome do campo. */
    private static BigDecimal numero(TextField campo, String rotulo) {
        String texto = campo.getText() == null ? "" : campo.getText().strip();
        if (texto.isEmpty()) {
            return null;
        }
        try {
            return Dinheiro.ler(texto.replace("%", ""));
        } catch (IllegalArgumentException e) {
            throw new NegocioException(rotulo + ": informe um número (ex.: 18 ou 18,00).");
        }
    }

    @FXML
    private void salvar() {
        try {
            DadosPerfil dados = new DadosPerfil(campoNomePerfil.getText(), campoCfop.getText(),
                    TIPO_CSOSN.equals(campoTipoIcms.getValue()), campoSituacaoIcms.getText(),
                    numero(campoAliquotaIcms, "Alíquota do ICMS"), numero(campoReducao, "Redução da base"),
                    campoCstPis.getText(), numero(campoAliquotaPis, "Alíquota do PIS"), campoCstCofins.getText(),
                    numero(campoAliquotaCofins, "Alíquota da COFINS"), campoBeneficio.getText(),
                    campoCstIbsCbs.getText(), campoCclassTrib.getText(), campoPerfilAtivo.isSelected());
            boolean novo = editando == null;
            long id = service.salvarPerfil(sessao, novo ? null : editando.id(), dados);
            carregar(id);
            Mensagem.sucesso(mensagemPerfil, novo ? "Perfil criado." : "Perfil salvo.");
        } catch (NegocioException e) {
            Mensagem.erro(mensagemPerfil, e.getMessage());
        }
    }

    @FXML
    private void excluir() {
        PerfilTributario p = editando;
        if (p == null) {
            return;
        }
        if (!Dialogos.confirmar(tabelaPerfis.getScene().getWindow(), "Excluir o perfil \"" + p.nome() + "\"?")) {
            return;
        }
        try {
            service.excluirPerfil(sessao, p.id());
            carregar(null);
            novo();
            Mensagem.sucesso(mensagemPerfil, "Perfil \"" + p.nome() + "\" excluído.");
        } catch (RegistroEmUsoException e) {
            if (p.ativo() && Dialogos.confirmar(tabelaPerfis.getScene().getWindow(), e.getMessage()
                    + "\n\nInativar o perfil agora?")) {
                campoPerfilAtivo.setSelected(false);
                salvar();
            } else {
                Mensagem.erro(mensagemPerfil, e.getMessage());
            }
        } catch (NegocioException e) {
            Mensagem.erro(mensagemPerfil, e.getMessage());
        }
    }
}
