package br.com.varesync.controller;

import br.com.varesync.model.CaixaMovimento;
import br.com.varesync.model.CaixaResumo;
import br.com.varesync.model.CaixaSessao;
import br.com.varesync.model.RelatorioFechamento;
import br.com.varesync.model.VendasPorForma;
import br.com.varesync.util.Formatos;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

/**
 * Relatório de fechamento de caixa (R11, 6.5), em janela própria depois do fechamento às cegas: abertura, vendas por
 * forma de pagamento, sangrias, suprimentos, esperado × contado, número de vendas, canceladas e descontos.
 */
public class FechamentoCaixaController {

    private static final String NEGATIVO = "negativo";

    @FXML private Label titulo;
    @FXML private Label terminal;
    @FXML private Label abertura;
    @FXML private Label fechamento;
    @FXML private Label fundoTroco;
    @FXML private Label quantidadeVendas;
    @FXML private Label totalVendido;
    @FXML private Label canceladas;
    @FXML private Label descontos;
    @FXML private Label vendasDinheiro;
    @FXML private Label suprimentos;
    @FXML private Label sangrias;
    @FXML private Label esperado;
    @FXML private Label contado;
    @FXML private Label diferenca;
    @FXML private TableView<VendasPorForma> tabelaFormas;
    @FXML private TableColumn<VendasPorForma, String> colunaForma;
    @FXML private TableColumn<VendasPorForma, String> colunaFormaVendas;
    @FXML private TableColumn<VendasPorForma, String> colunaFormaValor;
    @FXML private TableView<CaixaMovimento> tabelaMovimentos;
    @FXML private TableColumn<CaixaMovimento, String> colunaHora;
    @FXML private TableColumn<CaixaMovimento, String> colunaTipo;
    @FXML private TableColumn<CaixaMovimento, String> colunaValor;
    @FXML private TableColumn<CaixaMovimento, String> colunaMotivo;
    @FXML private TableColumn<CaixaMovimento, String> colunaUsuario;

    @FXML
    private void initialize() {
        colunaForma.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().forma()));
        colunaFormaVendas.setCellValueFactory(c ->
                new ReadOnlyStringWrapper(String.valueOf(c.getValue().quantidadeVendas())));
        colunaFormaValor.setCellValueFactory(c -> new ReadOnlyStringWrapper(Formatos.moeda(c.getValue().valor())));
        colunaHora.setCellValueFactory(c -> new ReadOnlyStringWrapper(Formatos.dataHora(c.getValue().criadoEm())));
        colunaTipo.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().tipo().descricao()));
        colunaValor.setCellValueFactory(c -> new ReadOnlyStringWrapper(Formatos.moeda(c.getValue().valor())));
        colunaMotivo.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().motivo()));
        colunaUsuario.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().usuarioNome()));
    }

    public void mostrar(RelatorioFechamento relatorio) {
        CaixaSessao sessao = relatorio.sessao();
        CaixaResumo resumo = relatorio.resumo();
        titulo.setText("Fechamento do caixa nº " + sessao.id());
        terminal.setText(sessao.terminal());
        abertura.setText(Formatos.dataHora(sessao.abertoEm()) + " — " + sessao.abertoPorNome());
        fechamento.setText(Formatos.dataHora(sessao.fechadoEm()) + " — " + sessao.fechadoPorNome());
        fundoTroco.setText(Formatos.moeda(resumo.valorAbertura()));
        quantidadeVendas.setText(String.valueOf(resumo.quantidadeVendas()));
        totalVendido.setText(Formatos.moeda(resumo.totalVendido()));
        canceladas.setText(String.valueOf(resumo.quantidadeCanceladas()));
        descontos.setText(Formatos.moeda(relatorio.descontos()));
        vendasDinheiro.setText(Formatos.moeda(resumo.vendasDinheiro()));
        suprimentos.setText(Formatos.moeda(resumo.suprimentos()));
        sangrias.setText(Formatos.moeda(resumo.sangrias()));
        esperado.setText(Formatos.moeda(sessao.valorEsperado()));
        contado.setText(Formatos.moeda(sessao.valorContado()));
        diferenca.setText(relatorio.situacao());
        diferenca.getStyleClass().remove(NEGATIVO);
        if (relatorio.diferenca().signum() < 0) {
            diferenca.getStyleClass().add(NEGATIVO);
        }
        tabelaFormas.getItems().setAll(relatorio.vendasPorForma());
        tabelaMovimentos.getItems().setAll(relatorio.movimentos());
    }

    @FXML
    private void fecharJanela() {
        titulo.getScene().getWindow().hide();
    }
}
