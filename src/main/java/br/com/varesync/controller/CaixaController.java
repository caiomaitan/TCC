package br.com.varesync.controller;

import br.com.varesync.model.CaixaMovimento;
import br.com.varesync.model.CaixaSessao;
import br.com.varesync.model.RelatorioFechamento;
import br.com.varesync.report.FechamentoCaixaPdf;
import br.com.varesync.service.AcessoService;
import br.com.varesync.service.CaixaService;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.ui.TratadorExcecoes;
import br.com.varesync.util.Formatos;
import java.util.Optional;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;

/**
 * Caixa do terminal (6.5), no menu Caixa: situação, abertura com fundo de troco, sangria/suprimento (F8) e
 * fechamento às cegas (F12) com o relatório R11. O PDV usa as mesmas operações ({@link AcoesCaixa}).
 */
public class CaixaController {

    private final CaixaService caixaService;
    private final Sessao sessao;
    private final AcoesCaixa acoes;
    private Runnable aoMudar = () -> { };
    private CaixaSessao caixa;

    @FXML private VBox raiz;
    @FXML private Label situacao;
    @FXML private Label numero;
    @FXML private Label abertoPor;
    @FXML private Label abertoEm;
    @FXML private Label fundoTroco;
    @FXML private Label mensagem;
    @FXML private Button botaoAbrir;
    @FXML private Button botaoMovimento;
    @FXML private Button botaoFechar;
    @FXML private Button botaoUltimoFechamento;
    @FXML private TableView<CaixaMovimento> tabelaMovimentos;
    @FXML private TableColumn<CaixaMovimento, String> colunaHora;
    @FXML private TableColumn<CaixaMovimento, String> colunaTipo;
    @FXML private TableColumn<CaixaMovimento, String> colunaValor;
    @FXML private TableColumn<CaixaMovimento, String> colunaMotivo;
    @FXML private TableColumn<CaixaMovimento, String> colunaUsuario;

    public CaixaController(CaixaService caixaService, AcessoService acessoService, FechamentoCaixaPdf fechamentoPdf,
                           Sessao sessao) {
        this.caixaService = caixaService;
        this.acoes = new AcoesCaixa(caixaService, acessoService, fechamentoPdf, sessao);
        this.sessao = sessao;
    }

    /** Chamado depois de abrir ou fechar o caixa (a janela principal atualiza a barra de status). */
    public void aoMudar(Runnable acao) {
        this.aoMudar = acao;
    }

    @FXML
    private void initialize() {
        colunaHora.setCellValueFactory(c -> new ReadOnlyStringWrapper(Formatos.dataHora(c.getValue().criadoEm())));
        colunaTipo.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().tipo().descricao()));
        colunaValor.setCellValueFactory(c -> new ReadOnlyStringWrapper(Formatos.moeda(c.getValue().valor())));
        colunaMotivo.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().motivo()));
        colunaUsuario.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().usuarioNome()));

        raiz.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.F8 && caixa != null) {
                e.consume();
                movimentar();
            } else if (e.getCode() == KeyCode.F12 && caixa != null) {
                e.consume();
                fechar();
            }
        });
        atualizar();
    }

    private void atualizar() {
        caixa = caixaService.caixaAberto(sessao).orElse(null);
        boolean aberto = caixa != null;
        if (aberto) {
            situacao.setText("Caixa aberto");
            numero.setText(String.valueOf(caixa.id()));
            abertoPor.setText(caixa.abertoPorNome());
            abertoEm.setText(Formatos.dataHora(caixa.abertoEm()));
            fundoTroco.setText(Formatos.moeda(caixa.valorAbertura()));
            tabelaMovimentos.getItems().setAll(caixaService.movimentosDoCaixaAberto(sessao));
        } else {
            situacao.setText("Caixa fechado — abra o caixa para vender, registrar sangria ou suprimento");
            numero.setText(Formatos.SEM_VALOR);
            abertoPor.setText(Formatos.SEM_VALOR);
            abertoEm.setText(Formatos.SEM_VALOR);
            fundoTroco.setText(Formatos.SEM_VALOR);
            tabelaMovimentos.getItems().clear();
        }
        botaoAbrir.setVisible(!aberto);
        botaoAbrir.setManaged(!aberto);
        botaoAbrir.setDefaultButton(!aberto);
        botaoMovimento.setDisable(!aberto);
        botaoFechar.setDisable(!aberto);
        Platform.runLater(() -> (aberto ? botaoMovimento : botaoAbrir).requestFocus());
    }

    @FXML
    private void abrir() {
        acoes.abrir().ifPresent(c -> {
            atualizar();
            aoMudar.run();
            Mensagem.sucesso(mensagem, "Caixa nº " + c.id() + " aberto com fundo de troco de "
                    + Formatos.moeda(c.valorAbertura()) + ".");
        });
    }

    @FXML
    private void movimentar() {
        acoes.movimentar().ifPresent(texto -> {
            atualizar();
            Mensagem.sucesso(mensagem, texto);
        });
    }

    @FXML
    private void fechar() {
        acoes.fechar().ifPresent(relatorio -> {
            atualizar();
            aoMudar.run();
            Mensagem.sucesso(mensagem, "Caixa nº " + relatorio.sessao().id() + " fechado: "
                    + relatorio.situacaoNaFrase() + ".");
            acoes.mostrarRelatorio(raiz.getScene().getWindow(), relatorio);
        });
    }

    @FXML
    private void verUltimoFechamento() {
        try {
            Optional<RelatorioFechamento> ultimo = caixaService.ultimoFechamento(sessao);
            if (ultimo.isEmpty()) {
                Mensagem.informar(mensagem, "Nenhum caixa foi fechado neste terminal.");
                return;
            }
            acoes.mostrarRelatorio(raiz.getScene().getWindow(), ultimo.get());
        } catch (RuntimeException e) {
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(e));
        }
    }
}
