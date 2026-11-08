package br.com.varesync.controller;

import br.com.varesync.model.ItemCargaBalanca;
import br.com.varesync.service.CargaBalancaService;
import br.com.varesync.service.LayoutMgv;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.util.Formatos;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;

/**
 * Carga de balança (F18.2, permissão PRODUTOS), aberta pelo botão "Carga de balança…" de Produtos e pelo alerta do
 * Painel. Mostra os pesáveis que vão para a balança, com a situação em relação à última carga e a linha de cada um no
 * arquivo; "Gerar carga" grava o {@code ITENSMGV.TXT} na pasta de Configurações › Balança e registra a carga.
 */
public class CargaBalancaController {

    private final CargaBalancaService service;
    private final Sessao sessao;
    private List<ItemCargaBalanca> itens = List.of();

    @FXML private Label pasta;
    @FXML private Label ultimaCarga;
    @FXML private CheckBox campoSoPendentes;
    @FXML private TableView<ItemCargaBalanca> tabela;
    @FXML private TableColumn<ItemCargaBalanca, String> colunaPlu;
    @FXML private TableColumn<ItemCargaBalanca, String> colunaNome;
    @FXML private TableColumn<ItemCargaBalanca, String> colunaDescricao;
    @FXML private TableColumn<ItemCargaBalanca, String> colunaPreco;
    @FXML private TableColumn<ItemCargaBalanca, String> colunaValidade;
    @FXML private TableColumn<ItemCargaBalanca, String> colunaUltima;
    @FXML private TableColumn<ItemCargaBalanca, String> colunaSituacao;
    @FXML private Label linhaArquivo;
    @FXML private Label resumo;
    @FXML private Button botaoGerar;
    @FXML private Label mensagem;

    public CargaBalancaController(CargaBalancaService service, Sessao sessao) {
        this.service = service;
        this.sessao = sessao;
    }

    @FXML
    private void initialize() {
        texto(colunaPlu, i -> String.valueOf(i.plu()));
        texto(colunaNome, ItemCargaBalanca::nome);
        texto(colunaDescricao, ItemCargaBalanca::descricao);
        texto(colunaPreco, i -> Formatos.moeda(i.preco()) + (i.emPromocao() ? " (promoção)" : ""));
        texto(colunaValidade, i -> i.validadeDias() == null ? "—" : i.validadeDias() + (i.validadeDias() == 1 ? " dia"
                : " dias"));
        texto(colunaUltima, i -> i.ultimaCargaEm() == null ? "nunca" : Formatos.dataHora(i.ultimaCargaEm()));
        texto(colunaSituacao, i -> i.situacao().rotulo());
        tabela.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(ItemCargaBalanca item, boolean vazio) {
                super.updateItem(item, vazio);
                getStyleClass().remove("linha-aviso");
                if (!vazio && item != null && item.pendente()) {
                    getStyleClass().add("linha-aviso");
                }
            }
        });
        tabela.getSelectionModel().selectedItemProperty().addListener((o, a, item) -> mostrarLinha(item));
        campoSoPendentes.selectedProperty().addListener((o, a, b) -> mostrarItens());
        carregar();
    }

    private static void texto(TableColumn<ItemCargaBalanca, String> coluna,
                              Function<ItemCargaBalanca, String> valor) {
        coluna.setCellValueFactory(c -> new ReadOnlyStringWrapper(valor.apply(c.getValue())));
    }

    /** Recarrega os pesáveis, a pasta e a última carga. */
    @FXML
    private void carregar() {
        Mensagem.limpar(mensagem);
        try {
            itens = service.itens(sessao);
            String configurada = service.pasta(sessao);
            pasta.setText(configurada.isEmpty() ? "não configurada (Configurações › Balança)"
                    : java.nio.file.Path.of(configurada).resolve(LayoutMgv.ARQUIVO).toString());
            OffsetDateTime ultima = service.ultimaCarga(sessao);
            ultimaCarga.setText(ultima == null ? "Nenhuma carga gerada ainda" : "Última carga: "
                    + Formatos.dataHora(ultima));
            mostrarItens();
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    private void mostrarItens() {
        tabela.getItems().setAll(campoSoPendentes.isSelected()
                ? itens.stream().filter(ItemCargaBalanca::pendente).toList() : itens);
        long pendentes = itens.stream().filter(ItemCargaBalanca::pendente).count();
        resumo.setText((itens.size() == 1 ? "1 produto na balança" : itens.size() + " produtos na balança") + ", "
                + (pendentes == 0 ? "nenhum pendente" : pendentes == 1 ? "1 pendente" : pendentes + " pendentes"));
        botaoGerar.setDisable(itens.isEmpty());
        if (!tabela.getItems().isEmpty()) {
            tabela.getSelectionModel().selectFirst();
        } else {
            mostrarLinha(null);
        }
    }

    private void mostrarLinha(ItemCargaBalanca item) {
        if (item == null) {
            linhaArquivo.setText("");
            return;
        }
        try {
            linhaArquivo.setText(LayoutMgv.linha(item.nome(), item.plu(), item.preco(), item.validadeDias()));
        } catch (NegocioException e) {
            linhaArquivo.setText(e.getMessage());
        }
    }

    /** Grava o arquivo (substituindo o anterior) e registra a carga. */
    @FXML
    private void gerar() {
        Mensagem.limpar(mensagem);
        String configurada = service.pasta(sessao);
        if (configurada.isEmpty()) {
            Mensagem.erro(mensagem, "Configure a pasta da carga em Configurações › Balança (a pasta de onde o MGV "
                    + "importa os itens).");
            return;
        }
        if (!Dialogos.confirmar(tabela.getScene().getWindow(), "Gravar o " + LayoutMgv.ARQUIVO + " em " + configurada
                + " com " + (itens.size() == 1 ? "1 produto" : itens.size() + " produtos")
                + "? O arquivo anterior é substituído.")) {
            return;
        }
        try {
            CargaBalancaService.Carga carga = service.gerar(sessao);
            carregar();
            Mensagem.sucesso(mensagem, "Carga gerada: " + (carga.itens() == 1 ? "1 produto" : carga.itens()
                    + " produtos") + " em " + carga.arquivo() + ". Importe o arquivo no MGV para enviar à balança.");
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }
}
