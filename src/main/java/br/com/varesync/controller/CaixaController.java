package br.com.varesync.controller;

import br.com.varesync.model.CaixaMovimento;
import br.com.varesync.model.CaixaSessao;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.RelatorioFechamento;
import br.com.varesync.model.TipoCaixaMovimento;
import br.com.varesync.service.AcessoService;
import br.com.varesync.service.CaixaService;
import br.com.varesync.service.Liberacao;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.DialogosCaixa;
import br.com.varesync.ui.Janelas;
import br.com.varesync.ui.LiberacaoSupervisorDialogo;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.ui.TratadorExcecoes;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
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
 * Caixa do terminal (6.5), aberto pelo menu PDV: situação, abertura com fundo de troco, sangria/suprimento (F8) e
 * fechamento às cegas (F12) com o relatório R11. A tela de venda do PDV entra na próxima etapa da Fase 4 e usa os
 * mesmos diálogos.
 */
public class CaixaController {

    private final CaixaService caixaService;
    private final AcessoService acessoService;
    private final Sessao sessao;
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

    public CaixaController(CaixaService caixaService, AcessoService acessoService, Sessao sessao) {
        this.caixaService = caixaService;
        this.acessoService = acessoService;
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
        Optional<CaixaSessao> aberta = DialogosCaixa.abrir(sessao.terminal(),
                texto -> caixaService.abrir(sessao, ler(texto)));
        aberta.ifPresent(c -> {
            atualizar();
            aoMudar.run();
            Mensagem.sucesso(mensagem, "Caixa nº " + c.id() + " aberto com fundo de troco de "
                    + Formatos.moeda(c.valorAbertura()) + ".");
        });
    }

    @FXML
    private void movimentar() {
        Liberacao liberacao = null;
        if (!sessao.pode(Permissao.SANGRIA_SUPRIMENTO)) {
            Optional<Liberacao> concedida = LiberacaoSupervisorDialogo.solicitar("Sangria ou suprimento",
                    (login, senha) -> acessoService.liberar(login, senha, Permissao.SANGRIA_SUPRIMENTO));
            if (concedida.isEmpty()) {
                return;
            }
            liberacao = concedida.get();
        }
        Liberacao liberada = liberacao;
        Optional<String> registrado = DialogosCaixa.movimentar((tipo, valor, motivo) -> {
            caixaService.registrarMovimento(sessao, tipo, ler(valor), motivo, liberada);
            return tipo.descricao() + " de " + Formatos.moeda(Dinheiro.dinheiro(ler(valor))) + " registrad"
                    + (tipo == TipoCaixaMovimento.SUPRIMENTO ? "o" : "a") + ".";
        });
        registrado.ifPresent(texto -> {
            atualizar();
            Mensagem.sucesso(mensagem, texto);
        });
    }

    @FXML
    private void fechar() {
        Optional<RelatorioFechamento> fechamento = DialogosCaixa.fechar(sessao.terminal(), texto -> {
            BigDecimal contado = ler(texto);
            if (contado == null) {
                throw new NegocioException("Informe o dinheiro contado.");
            }
            if (!Dialogos.confirmar("Fechar o caixa com " + Formatos.moeda(Dinheiro.dinheiro(contado))
                    + " contados? Depois de fechado não é possível alterar.")) {
                return null;
            }
            return caixaService.fechar(sessao, contado);
        });
        fechamento.ifPresent(relatorio -> {
            atualizar();
            aoMudar.run();
            Mensagem.sucesso(mensagem, "Caixa nº " + relatorio.sessao().id() + " fechado: "
                    + relatorio.situacaoNaFrase() + ".");
            mostrarRelatorio(relatorio);
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
            mostrarRelatorio(ultimo.get());
        } catch (RuntimeException e) {
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(e));
        }
    }

    private void mostrarRelatorio(RelatorioFechamento relatorio) {
        Janelas.abrirModal(raiz.getScene().getWindow(), "/fxml/fechamento-caixa.fxml",
                "Fechamento de caixa nº " + relatorio.sessao().id(), tipo -> new FechamentoCaixaController(),
                (FechamentoCaixaController c) -> c.mostrar(relatorio));
    }

    /** Vazio → nulo (o service pede o valor); texto inválido → mensagem. */
    private static BigDecimal ler(String texto) {
        if (Textos.vazio(texto)) {
            return null;
        }
        try {
            return Dinheiro.ler(texto);
        } catch (IllegalArgumentException e) {
            throw new NegocioException("Valor inválido (\"" + texto.strip() + "\").");
        }
    }
}
