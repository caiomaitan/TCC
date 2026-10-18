package br.com.varesync.controller;

import br.com.varesync.model.InconsistenciaEstoque;
import br.com.varesync.service.EstoqueService;
import br.com.varesync.service.GeradorDemo;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.Dialogos;
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
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;

/**
 * Configurações (6.13, só supervisor). Por enquanto: o diagnóstico do estoque (6.4), que mostra
 * {@code vw_estoque_inconsistente} (deve estar vazia), e o gerador de dados de demonstração (6.14). Os dois
 * rodam em {@link Task}.
 */
public class ConfiguracoesController {

    private final EstoqueService estoqueService;
    private final GeradorDemo geradorDemo;
    private final Sessao sessao;

    @FXML private Button botaoVerificar;
    @FXML private ProgressIndicator progresso;
    @FXML private Label resultado;
    @FXML private TableView<InconsistenciaEstoque> tabelaInconsistencias;
    @FXML private TableColumn<InconsistenciaEstoque, String> colunaProduto;
    @FXML private TableColumn<InconsistenciaEstoque, String> colunaRegistrado;
    @FXML private TableColumn<InconsistenciaEstoque, String> colunaMovimentos;
    @FXML private TableColumn<InconsistenciaEstoque, String> colunaDiferenca;
    @FXML private Button botaoGerarDemo;
    @FXML private ProgressBar progressoDemo;
    @FXML private Label situacaoDemo;
    @FXML private TextArea resultadoDemo;

    public ConfiguracoesController(EstoqueService estoqueService, GeradorDemo geradorDemo, Sessao sessao) {
        this.estoqueService = estoqueService;
        this.geradorDemo = geradorDemo;
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

    /**
     * Gerador de dados de demonstração (6.14): confere o banco (sem vendas, sem caixa aberto), pede confirmação e
     * gera em {@link Task} com a barra de progresso. No fim mostra os usuários criados e as senhas provisórias.
     */
    @FXML
    private void gerarDemo() {
        try {
            geradorDemo.conferir(sessao);
        } catch (RuntimeException e) {
            Mensagem.erro(situacaoDemo, TratadorExcecoes.mensagem(e));
            return;
        }
        if (!Dialogos.confirmar("Gerar os dados de demonstração neste banco? Serão criados usuários, cerca de 80 "
                + "produtos e " + GeradorDemo.DIAS + " dias de vendas, caixas e movimentos de estoque. Isso leva "
                + "alguns minutos e não pode ser desfeito.")) {
            return;
        }
        Task<GeradorDemo.Resultado> tarefa = new Task<>() {
            @Override
            protected GeradorDemo.Resultado call() {
                return geradorDemo.gerar(sessao, (feito, total, mensagem) -> {
                    updateProgress(feito, total);
                    updateMessage(mensagem);
                });
            }
        };
        botaoGerarDemo.setDisable(true);
        botaoVerificar.setDisable(true);
        progressoDemo.setVisible(true);
        Mensagem.limpar(situacaoDemo);
        progressoDemo.progressProperty().bind(tarefa.progressProperty());
        situacaoDemo.textProperty().bind(tarefa.messageProperty());
        tarefa.setOnSucceeded(e -> {
            concluirDemo();
            GeradorDemo.Resultado r = tarefa.getValue();
            Mensagem.sucesso(situacaoDemo, "Dados de demonstração gerados: " + r.produtos() + " produtos e "
                    + r.vendas() + " vendas de " + Formatos.data(r.inicio()) + " a " + Formatos.data(r.fim()) + ".");
            resultadoDemo.setText(textoResultado(r));
            resultadoDemo.setVisible(true);
            resultadoDemo.setManaged(true);
        });
        tarefa.setOnFailed(e -> {
            concluirDemo();
            Mensagem.erro(situacaoDemo, TratadorExcecoes.mensagem(tarefa.getException())
                    + " Os dados gravados até a falha continuam no banco; para recomeçar, recrie o banco de "
                    + "demonstração (README).");
        });
        Tarefas.executar("dados-demonstracao", tarefa);
    }

    private void concluirDemo() {
        progressoDemo.progressProperty().unbind();
        situacaoDemo.textProperty().unbind();
        progressoDemo.setVisible(false);
        botaoGerarDemo.setDisable(false);
        botaoVerificar.setDisable(false);
    }

    static String textoResultado(GeradorDemo.Resultado r) {
        StringBuilder texto = new StringBuilder("Usuários criados (anote as senhas: elas não serão mostradas de "
                + "novo; cada um troca a senha no primeiro login):\n\n");
        for (GeradorDemo.UsuarioDemo u : r.usuarios()) {
            texto.append(String.format("  %-8s %-18s %-20s senha: %s%n", u.login(), u.nome(), u.perfil(), u.senha()));
        }
        texto.append("\nVendas: ").append(r.vendas()).append(" (").append(r.vendasCanceladas())
                .append(" canceladas, ").append(r.itensCancelados()).append(" itens cancelados durante a venda)")
                .append("\nInventário aplicado: nº ").append(r.inventarioId())
                .append("\nPeríodo: ").append(Formatos.data(r.inicio())).append(" a ").append(Formatos.data(r.fim()));
        return texto.toString();
    }
}
