package br.com.varesync.controller;

import br.com.varesync.model.InconsistenciaEstoque;
import br.com.varesync.service.EstoqueService;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.ui.Tarefas;
import br.com.varesync.ui.TratadorExcecoes;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

/**
 * Configurações (6.13, só supervisor). Por enquanto só o diagnóstico do estoque (6.4): mostra
 * {@code vw_estoque_inconsistente}, que deve estar vazia. A verificação percorre todo o kardex e roda em
 * {@link Task}.
 */
public class ConfiguracoesController {

    private final EstoqueService estoqueService;
    private final Sessao sessao;

    @FXML private Button botaoVerificar;
    @FXML private ProgressIndicator progresso;
    @FXML private Label resultado;
    @FXML private TableView<InconsistenciaEstoque> tabelaInconsistencias;
    @FXML private TableColumn<InconsistenciaEstoque, String> colunaProduto;
    @FXML private TableColumn<InconsistenciaEstoque, String> colunaRegistrado;
    @FXML private TableColumn<InconsistenciaEstoque, String> colunaMovimentos;
    @FXML private TableColumn<InconsistenciaEstoque, String> colunaDiferenca;

    public ConfiguracoesController(EstoqueService estoqueService, Sessao sessao) {
        this.estoqueService = estoqueService;
        this.sessao = sessao;
    }

    @FXML
    private void initialize() {
        coluna(colunaProduto, i -> i.nome() + " (código " + i.produtoId() + ")");
        coluna(colunaRegistrado, i -> quantidade(i.saldoRegistrado()));
        coluna(colunaMovimentos, i -> quantidade(i.saldoPelosMovimentos()));
        coluna(colunaDiferenca, i -> quantidade(i.diferenca()));
        verificar();
    }

    private static void coluna(TableColumn<InconsistenciaEstoque, String> coluna,
                               Function<InconsistenciaEstoque, String> valor) {
        coluna.setCellValueFactory(c -> new ReadOnlyStringWrapper(valor.apply(c.getValue())));
    }

    /** Sem a unidade do produto aqui: 3 casas sempre, para não esconder diferença fracionada. */
    private static String quantidade(BigDecimal valor) {
        return Formatos.numero(valor, Dinheiro.ESCALA_QUANTIDADE);
    }

    @FXML
    private void verificar() {
        Task<List<InconsistenciaEstoque>> tarefa = new Task<>() {
            @Override
            protected List<InconsistenciaEstoque> call() {
                return estoqueService.diagnostico(sessao);
            }
        };
        botaoVerificar.setDisable(true);
        progresso.setVisible(true);
        Mensagem.informar(resultado, "Verificando…");
        tarefa.setOnSucceeded(e -> {
            concluir();
            List<InconsistenciaEstoque> linhas = tarefa.getValue();
            tabelaInconsistencias.getItems().setAll(linhas);
            if (linhas.isEmpty()) {
                Mensagem.sucesso(resultado, "Tudo certo: o saldo de todos os produtos confere com a soma dos "
                        + "movimentos.");
            } else {
                Mensagem.erro(resultado, (linhas.size() == 1 ? "1 produto tem" : linhas.size() + " produtos têm")
                        + " saldo diferente da soma dos movimentos. Isso indica alteração feita fora do sistema; "
                        + "anote os produtos e procure o suporte.");
            }
        });
        tarefa.setOnFailed(e -> {
            concluir();
            Mensagem.erro(resultado, TratadorExcecoes.mensagem(tarefa.getException()));
        });
        Tarefas.executar("diagnostico-estoque", tarefa);
    }

    private void concluir() {
        botaoVerificar.setDisable(false);
        progresso.setVisible(false);
    }
}
