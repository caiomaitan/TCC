package br.com.varesync.controller;

import br.com.varesync.service.ImportacaoProdutosService;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.Exportacao;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.ui.Tarefas;
import br.com.varesync.ui.TratadorExcecoes;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.input.TransferMode;
import javafx.stage.FileChooser;

/**
 * Importação de produtos por planilha CSV (6.11), aberta pela tela Produtos: baixar o modelo, escolher o arquivo,
 * pré-visualizar com a validação linha a linha e importar. Leitura, validação e importação rodam em {@link Task}.
 */
public class ImportacaoProdutosController {

    private final ImportacaoProdutosService importacaoService;
    private final Sessao sessao;
    /** Conteúdo do arquivo escolhido; a importação valida de novo a partir dele. */
    private byte[] conteudo;
    private ImportacaoProdutosService.Previa previa;
    private boolean importou;

    @FXML private Button botaoModelo;
    @FXML private Button botaoArquivo;
    @FXML private Label arquivo;
    @FXML private ProgressIndicator progresso;
    @FXML private TableView<ImportacaoProdutosService.Linha> tabela;
    @FXML private TableColumn<ImportacaoProdutosService.Linha, Integer> colunaLinha;
    @FXML private TableColumn<ImportacaoProdutosService.Linha, String> colunaNome;
    @FXML private TableColumn<ImportacaoProdutosService.Linha, String> colunaCodigo;
    @FXML private TableColumn<ImportacaoProdutosService.Linha, String> colunaUnidade;
    @FXML private TableColumn<ImportacaoProdutosService.Linha, String> colunaCategoria;
    @FXML private TableColumn<ImportacaoProdutosService.Linha, String> colunaCusto;
    @FXML private TableColumn<ImportacaoProdutosService.Linha, String> colunaPreco;
    @FXML private TableColumn<ImportacaoProdutosService.Linha, String> colunaMinimo;
    @FXML private TableColumn<ImportacaoProdutosService.Linha, String> colunaInicial;
    @FXML private TableColumn<ImportacaoProdutosService.Linha, String> colunaSituacao;
    @FXML private Label detalhe;
    @FXML private Label resumo;
    @FXML private CheckBox ignorarErros;
    @FXML private Button botaoImportar;
    @FXML private Label mensagem;

    public ImportacaoProdutosController(ImportacaoProdutosService importacaoService, Sessao sessao) {
        this.importacaoService = importacaoService;
        this.sessao = sessao;
    }

    @FXML
    private void initialize() {
        colunaLinha.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().numero()));
        texto(colunaNome, ImportacaoProdutosService.Linha::nome);
        texto(colunaCodigo, ImportacaoProdutosService.Linha::codigoBarras);
        texto(colunaUnidade, ImportacaoProdutosService.Linha::unidade);
        texto(colunaCategoria, l -> l.categoria() == null ? null : l.categoria() + (l.categoriaNova() ? " (nova)" : ""));
        texto(colunaCusto, l -> l.custo() == null ? null : "R$ " + Formatos.numero(l.custo(), Dinheiro.ESCALA_CUSTO));
        texto(colunaPreco, l -> l.preco() == null ? null : Formatos.moeda(l.preco()));
        texto(colunaMinimo, l -> quantidade(l.estoqueMinimo()));
        texto(colunaInicial, l -> quantidade(l.estoqueInicial()));
        texto(colunaSituacao, ImportacaoProdutosController::situacao);
        tabela.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(ImportacaoProdutosService.Linha l, boolean vazio) {
                super.updateItem(l, vazio);
                getStyleClass().removeAll("linha-erro", "linha-aviso");
                if (!vazio && l != null) {
                    if (!l.valida()) {
                        getStyleClass().add("linha-erro");
                    } else if (!l.avisos().isEmpty()) {
                        getStyleClass().add("linha-aviso");
                    }
                }
            }
        });
        tabela.getSelectionModel().selectedItemProperty().addListener((obs, a, l) -> mostrarDetalhe(l));
        ignorarErros.selectedProperty().addListener((obs, a, n) -> atualizarResumo());
        // Também dá para arrastar o arquivo do Windows Explorer para a lista.
        tabela.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles() && !progresso.isVisible()) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        tabela.setOnDragDropped(e -> {
            List<File> arquivos = e.getDragboard().getFiles();
            boolean aceito = arquivos.size() == 1;
            if (aceito) {
                carregar(arquivos.get(0).toPath());
            }
            e.setDropCompleted(aceito);
            e.consume();
        });
        atualizarResumo();
    }

    private static void texto(TableColumn<ImportacaoProdutosService.Linha, String> coluna,
                              Function<ImportacaoProdutosService.Linha, String> valor) {
        coluna.setCellValueFactory(c -> new ReadOnlyStringWrapper(valor.apply(c.getValue())));
    }

    private static String quantidade(BigDecimal valor) {
        return valor == null ? null : Formatos.numero(valor, valor.stripTrailingZeros().scale() > 0
                ? Dinheiro.ESCALA_QUANTIDADE : 0);
    }

    /** A situação completa da linha selecionada (na coluna ela pode ficar cortada). */
    private void mostrarDetalhe(ImportacaoProdutosService.Linha l) {
        detalhe.getStyleClass().remove("mensagem-erro");
        if (l == null) {
            detalhe.setText("");
            return;
        }
        detalhe.setText("Linha " + l.numero() + ": " + situacao(l));
        if (!l.valida()) {
            detalhe.getStyleClass().add("mensagem-erro");
        }
    }

    /** "OK", os avisos ou os erros ("custo: …; nome: …"). */
    static String situacao(ImportacaoProdutosService.Linha l) {
        if (!l.valida()) {
            return "Erro — " + l.erros().stream().map(Object::toString).collect(Collectors.joining("; "));
        }
        return l.avisos().isEmpty() ? "OK" : "OK, com aviso: " + String.join("; ", l.avisos());
    }

    @FXML
    private void baixarModelo() {
        Exportacao.escolher(tabela.getScene().getWindow(), "CSV", "csv", "modelo_produtos.csv")
                .ifPresent(destino -> Exportacao.gravar("Modelo", destino, p -> {
                    try {
                        Files.write(p, importacaoService.modelo(sessao));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }, mensagem, botoes()));
    }

    @FXML
    private void escolherArquivo() {
        FileChooser escolha = new FileChooser();
        escolha.setTitle("Escolher planilha de produtos");
        escolha.getExtensionFilters().add(new FileChooser.ExtensionFilter("Planilha CSV", "*.csv", "*.txt"));
        File escolhido = escolha.showOpenDialog(tabela.getScene().getWindow());
        if (escolhido != null) {
            carregar(escolhido.toPath());
        }
    }

    /** Lê o arquivo (escolhido ou arrastado para a lista) e mostra a prévia, em {@link Task}. */
    private void carregar(Path caminho) {
        importou = false;
        validar(caminho.getFileName().toString(), () -> {
            try {
                return Files.readAllBytes(caminho);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private interface Leitura {
        byte[] ler();
    }

    private void validar(String nome, Leitura leitura) {
        Task<ImportacaoProdutosService.Previa> tarefa = new Task<>() {
            @Override
            protected ImportacaoProdutosService.Previa call() {
                byte[] lido = leitura.ler();
                ImportacaoProdutosService.Previa p = importacaoService.previa(sessao, lido);
                conteudoLido = lido;
                return p;
            }
        };
        ocupar(true);
        Mensagem.informar(mensagem, "Lendo e conferindo a planilha…");
        tarefa.setOnSucceeded(e -> {
            ocupar(false);
            conteudo = conteudoLido;
            previa = tarefa.getValue();
            arquivo.setText(nome + " (" + previa.codificacao() + ", " + previa.linhas().size() + " linha"
                    + (previa.linhas().size() == 1 ? "" : "s") + ")");
            tabela.getItems().setAll(previa.linhas());
            // A primeira linha com erro já fica selecionada, com o erro inteiro embaixo da lista.
            previa.linhas().stream().filter(l -> !l.valida()).findFirst().ifPresentOrElse(l -> {
                tabela.getSelectionModel().select(l);
                tabela.scrollTo(l);
            }, () -> tabela.getSelectionModel().clearSelection());
            Mensagem.limpar(mensagem);
            atualizarResumo();
        });
        tarefa.setOnFailed(e -> {
            ocupar(false);
            conteudo = null;
            previa = null;
            tabela.getItems().clear();
            arquivo.setText(nome);
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(tarefa.getException()));
            atualizarResumo();
        });
        Tarefas.executar("previa-importacao", tarefa);
    }

    /** Conteúdo lido pela última tarefa (passado da thread da tarefa para a da tela no sucesso). */
    private volatile byte[] conteudoLido;

    private void atualizarResumo() {
        if (previa == null) {
            resumo.setText("Escolha a planilha (.csv) para conferir os dados antes de importar.");
            botaoImportar.setDisable(true);
            return;
        }
        long erros = previa.comErro();
        StringBuilder texto = new StringBuilder();
        texto.append(previa.validas()).append(previa.validas() == 1 ? " linha pronta" : " linhas prontas");
        if (erros > 0) {
            texto.append(", ").append(erros).append(erros == 1 ? " com erro" : " com erro (em vermelho)");
        }
        List<String> novas = previa.categoriasNovas();
        if (!novas.isEmpty()) {
            texto.append(". Categorias que serão criadas: ").append(String.join(", ", novas));
        }
        texto.append(erros > 0 && !ignorarErros.isSelected()
                ? ". Corrija a planilha ou marque \"Ignorar as linhas com erro\": com erro, nada é importado." : ".");
        resumo.setText(texto.toString());
        if (erros > 0 && !ignorarErros.isSelected()) {
            resumo.getStyleClass().add("mensagem-erro");
        } else {
            resumo.getStyleClass().remove("mensagem-erro");
        }
        botaoImportar.setDisable(importou || previa.validas() == 0 || (erros > 0 && !ignorarErros.isSelected()));
    }

    @FXML
    private void importar() {
        if (conteudo == null || previa == null) {
            return;
        }
        long quantos = previa.validas();
        if (!Dialogos.confirmar("Importar " + quantos + (quantos == 1 ? " produto" : " produtos")
                + (previa.comErro() > 0 ? " e ignorar " + previa.comErro() + " linha(s) com erro" : "") + "?")) {
            return;
        }
        byte[] bytes = conteudo;
        boolean ignorar = ignorarErros.isSelected();
        Task<ImportacaoProdutosService.Resultado> tarefa = new Task<>() {
            @Override
            protected ImportacaoProdutosService.Resultado call() {
                return importacaoService.importar(sessao, bytes, ignorar);
            }
        };
        ocupar(true);
        Mensagem.informar(mensagem, "Importando…");
        tarefa.setOnSucceeded(e -> {
            ocupar(false);
            importou = true;
            ImportacaoProdutosService.Resultado r = tarefa.getValue();
            Mensagem.sucesso(mensagem, r.importados() + (r.importados() == 1 ? " produto importado" : " produtos "
                    + "importados") + " (códigos " + r.primeiroId() + " a " + r.ultimoId() + ")"
                    + (r.ignorados() > 0 ? "; " + r.ignorados() + " linha(s) com erro ignorada(s)" : "")
                    + (r.categoriasCriadas().isEmpty() ? "" : "; categorias criadas: "
                    + String.join(", ", r.categoriasCriadas())) + ".");
            atualizarResumo();
        });
        tarefa.setOnFailed(e -> {
            ocupar(false);
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(tarefa.getException()));
            atualizarResumo();
        });
        Tarefas.executar("importacao-produtos", tarefa);
    }

    private List<Node> botoes() {
        return List.of(botaoModelo, botaoArquivo, botaoImportar);
    }

    private void ocupar(boolean ocupado) {
        progresso.setVisible(ocupado);
        botoes().forEach(b -> b.setDisable(ocupado));
        if (!ocupado) {
            atualizarResumo();
        }
    }

    @FXML
    private void fechar() {
        tabela.getScene().getWindow().hide();
    }
}
