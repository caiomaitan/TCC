package br.com.varesync.controller;

import br.com.varesync.model.Categoria;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.Etiqueta;
import br.com.varesync.model.OrigemEtiqueta;
import br.com.varesync.model.ProdutoResumo;
import br.com.varesync.model.SituacaoProduto;
import br.com.varesync.report.EtiquetasPdf;
import br.com.varesync.report.ImpressaoTermica;
import br.com.varesync.service.CategoriaService;
import br.com.varesync.service.EtiquetaService;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.ProdutoService;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.BuscaProdutoDialogo;
import br.com.varesync.ui.ConversorData;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.Exportacao;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.ui.Tarefas;
import br.com.varesync.ui.TratadorExcecoes;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.util.converter.IntegerStringConverter;

/**
 * Etiquetas de gôndola (F18.1, permissão PRODUTOS), aberta pelo botão "Etiquetas…" de Produtos. Seleção por um
 * critério (pendentes, o padrão; preço alterado desde uma data; produtos de uma entrada; categoria; lista manual),
 * com o preço que vai na etiqueta e a quantidade de cada produto. "Gerar PDF…" e "Imprimir na térmica" registram a
 * impressão; "Marcar como em dia" registra sem imprimir. Depois de cada uma, a lista é recarregada.
 */
public class EtiquetasController {

    /** Critérios de seleção (F18.1). */
    enum Criterio {
        PENDENTES("Pendentes"),
        ALTERADOS("Preço alterado desde"),
        ENTRADA("Produtos de uma entrada"),
        CATEGORIA("Categoria"),
        MANUAL("Lista manual");

        private final String rotulo;

        Criterio(String rotulo) {
            this.rotulo = rotulo;
        }

        @Override
        public String toString() {
            return rotulo;
        }
    }

    private final EtiquetaService etiquetaService;
    private final ProdutoService produtoService;
    private final CategoriaService categoriaService;
    private final EtiquetasPdf etiquetasPdf;
    private final ImpressaoTermica impressaoTermica;
    private final Sessao sessao;
    /** Como recarregar a lista do critério atual (a lista manual recarrega os mesmos produtos). */
    private Supplier<List<Etiqueta>> recarga = List::of;

    @FXML private ComboBox<Criterio> campoCriterio;
    @FXML private HBox painelDesde;
    @FXML private DatePicker campoDesde;
    @FXML private HBox painelEntrada;
    @FXML private TextField campoEntrada;
    @FXML private HBox painelCategoria;
    @FXML private ComboBox<Categoria> campoCategoria;
    @FXML private Button botaoCarregar;
    @FXML private TextField campoProduto;
    @FXML private TableView<Etiqueta> tabela;
    @FXML private TableColumn<Etiqueta, Long> colunaCodigo;
    @FXML private TableColumn<Etiqueta, String> colunaNome;
    @FXML private TableColumn<Etiqueta, String> colunaPreco;
    @FXML private TableColumn<Etiqueta, String> colunaBarras;
    @FXML private TableColumn<Etiqueta, String> colunaUltima;
    @FXML private TableColumn<Etiqueta, Integer> colunaQuantidade;
    @FXML private TextField campoPosicao;
    @FXML private Label dicaPosicao;
    @FXML private Button botaoPdf;
    @FXML private Button botaoTermica;
    @FXML private Button botaoEmDia;
    @FXML private Label resumo;
    @FXML private Label mensagem;

    public EtiquetasController(EtiquetaService etiquetaService, ProdutoService produtoService,
                               CategoriaService categoriaService, EtiquetasPdf etiquetasPdf,
                               ImpressaoTermica impressaoTermica, Sessao sessao) {
        this.etiquetaService = etiquetaService;
        this.produtoService = produtoService;
        this.categoriaService = categoriaService;
        this.etiquetasPdf = etiquetasPdf;
        this.impressaoTermica = impressaoTermica;
        this.sessao = sessao;
    }

    @FXML
    private void initialize() {
        colunaCodigo.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().produtoId()));
        texto(colunaNome, e -> e.nome() + (e.conteudo().vazio() ? "" : " (" + e.conteudo().descricao() + ")"));
        texto(colunaPreco, EtiquetasController::descreverPreco);
        texto(colunaBarras, e -> e.codigoBarras() != null ? e.codigoBarras()
                : e.plu() != null ? "PLU " + e.plu() : "— (vai o código " + e.produtoId() + ")");
        texto(colunaUltima, e -> e.ultimaImpressao() == null ? "nunca"
                : Formatos.dataHora(e.ultimaImpressao()) + (e.pendente() ? " — pendente" : ""));
        colunaQuantidade.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().quantidade()));
        colunaQuantidade.setCellFactory(TextFieldTableCell.forTableColumn(new IntegerStringConverter() {
            @Override
            public Integer fromString(String texto) {
                try {
                    return super.fromString(texto == null ? null : texto.strip());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }));
        colunaQuantidade.setOnEditCommit(e -> alterarQuantidade(e.getRowValue(), e.getNewValue()));
        tabela.setEditable(true);
        tabela.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(Etiqueta etiqueta, boolean vazio) {
                super.updateItem(etiqueta, vazio);
                getStyleClass().remove("linha-aviso");
                if (!vazio && etiqueta != null && etiqueta.pendente()) {
                    getStyleClass().add("linha-aviso");
                }
            }
        });
        tabela.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE) {
                e.consume();
                remover();
            }
        });

        campoCriterio.getItems().setAll(Criterio.values());
        campoCriterio.setValue(Criterio.PENDENTES);
        campoCriterio.valueProperty().addListener((o, a, n) -> atualizarCriterio());
        campoDesde.setConverter(new ConversorData());
        campoDesde.setValue(etiquetaService.hoje().minusDays(7));
        campoCategoria.getItems().setAll(categoriaService.listar(sessao, false));
        campoPosicao.setText("1");
        atualizarCriterio();
        dicaPosicao.setText(dicaFolha(etiquetaService.configuracao(sessao)));
        carregar();
        Platform.runLater(campoCriterio::requestFocus);
    }

    private static void texto(TableColumn<Etiqueta, String> coluna, Function<Etiqueta, String> valor) {
        coluna.setCellValueFactory(c -> new ReadOnlyStringWrapper(valor.apply(c.getValue())));
    }

    /** "R$ 7,99 (DE R$ 9,99, até 13/11/2026)" na promoção; "R$ 8,99 · R$ 17,98 o kg" com conteúdo líquido. */
    static String descreverPreco(Etiqueta e) {
        StringBuilder s = new StringBuilder(Formatos.moeda(e.preco()));
        if (e.promocional()) {
            s.append(" (DE ").append(Formatos.moeda(e.precoDe())).append(", até ")
                    .append(Formatos.data(e.fimPromocao())).append(")");
        }
        if (e.precoPorMedida() != null) {
            s.append(" · ").append(Formatos.moeda(e.precoPorMedida())).append(" o ")
                    .append(e.conteudo().unidade().medida());
        }
        if (e.temAtacado()) {
            s.append(" · ").append(e.textoAtacado()); // F20.3
        }
        return s.toString();
    }

    private static String dicaFolha(Configuracoes.Etiquetas layout) {
        return "de 1 a " + layout.porFolha() + " (folha de " + layout.colunas() + " × " + layout.linhas()
                + ", da esquerda para a direita)";
    }

    private void atualizarCriterio() {
        Criterio c = campoCriterio.getValue();
        mostrar(painelDesde, c == Criterio.ALTERADOS);
        mostrar(painelEntrada, c == Criterio.ENTRADA);
        mostrar(painelCategoria, c == Criterio.CATEGORIA);
        botaoCarregar.setText(c == Criterio.MANUAL ? "Limpar a lista" : "Carregar");
    }

    private static void mostrar(Node no, boolean visivel) {
        no.setVisible(visivel);
        no.setManaged(visivel);
    }

    /** Monta a lista pelo critério escolhido. */
    @FXML
    private void carregar() {
        Mensagem.limpar(mensagem);
        try {
            Criterio c = campoCriterio.getValue();
            switch (c) {
                case PENDENTES -> recarga = () -> etiquetaService.pendentes(sessao);
                case ALTERADOS -> {
                    LocalDate desde = campoDesde.getValue();
                    etiquetaService.alteradosDesde(sessao, desde); // valida antes de trocar a recarga
                    recarga = () -> etiquetaService.alteradosDesde(sessao, desde);
                }
                case ENTRADA -> {
                    String digitado = Textos.normalizarEspacos(campoEntrada.getText());
                    if (!Textos.soDigitos(digitado) || digitado.length() > 18) {
                        throw new NegocioException("Informe o número da entrada de mercadoria.");
                    }
                    long entrada = Long.parseLong(digitado);
                    etiquetaService.daEntrada(sessao, entrada);
                    recarga = () -> etiquetaService.daEntrada(sessao, entrada);
                }
                case CATEGORIA -> {
                    Categoria categoria = campoCategoria.getValue();
                    if (categoria == null) {
                        throw new NegocioException("Escolha a categoria.");
                    }
                    recarga = () -> etiquetaService.daCategoria(sessao, categoria.id());
                }
                case MANUAL -> {
                    recarga = () -> List.of();
                }
            }
            tabela.getItems().setAll(recarga.get());
            atualizarResumo();
            if (c == Criterio.MANUAL) {
                campoProduto.requestFocus();
            }
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    /** Recarrega com o preço de hoje, mantendo as quantidades escolhidas. */
    private void recarregar() {
        List<Etiqueta> antes = new ArrayList<>(tabela.getItems());
        List<Etiqueta> novas;
        if (campoCriterio.getValue() == Criterio.MANUAL) {
            novas = etiquetaService.produtos(sessao, antes.stream().map(Etiqueta::produtoId).toList());
        } else {
            novas = recarga.get();
        }
        tabela.getItems().setAll(novas.stream().map(n -> antes.stream().filter(a -> a.produtoId() == n.produtoId())
                .findFirst().map(a -> n.comQuantidade(a.quantidade())).orElse(n)).toList());
        atualizarResumo();
    }

    /** Lista manual (e qualquer lista): código, código de barras ou nome; com mais de um resultado, a busca. */
    @FXML
    private void adicionarProduto(ActionEvent evento) {
        evento.consume();
        String termo = Textos.normalizarEspacos(campoProduto.getText());
        if (termo.isEmpty()) {
            return;
        }
        try {
            List<ProdutoResumo> achados = produtoService.pesquisar(sessao, termo, null, SituacaoProduto.ATIVOS);
            Optional<ProdutoResumo> escolhido = achados.size() == 1 ? Optional.of(achados.get(0))
                    : BuscaProdutoDialogo.buscar("Incluir produto nas etiquetas", "Incluir", true, termo,
                    t -> produtoService.pesquisar(sessao, t, null, SituacaoProduto.ATIVOS));
            if (escolhido.isEmpty()) {
                return;
            }
            long id = escolhido.get().id();
            Optional<Etiqueta> naLista = tabela.getItems().stream().filter(e -> e.produtoId() == id).findFirst();
            if (naLista.isPresent()) {
                tabela.getSelectionModel().select(naLista.get());
                Mensagem.informar(mensagem, "\"" + naLista.get().nome() + "\" já está na lista: mude a quantidade.");
            } else {
                Etiqueta nova = etiquetaService.produto(sessao, id);
                tabela.getItems().add(nova);
                tabela.getSelectionModel().select(nova);
                Mensagem.limpar(mensagem);
            }
            campoProduto.clear();
            atualizarResumo();
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    @FXML
    private void remover() {
        Etiqueta selecionada = tabela.getSelectionModel().getSelectedItem();
        if (selecionada != null) {
            tabela.getItems().remove(selecionada);
            atualizarResumo();
        }
    }

    private void alterarQuantidade(Etiqueta etiqueta, Integer quantidade) {
        int i = tabela.getItems().indexOf(etiqueta);
        if (i < 0) {
            return;
        }
        if (quantidade == null || quantidade < 1 || quantidade > EtiquetaService.QUANTIDADE_MAXIMA) {
            Mensagem.erro(mensagem, "A quantidade deve ser um número de 1 a " + EtiquetaService.QUANTIDADE_MAXIMA
                    + ".");
            tabela.refresh();
            return;
        }
        tabela.getItems().set(i, etiqueta.comQuantidade(quantidade));
        Mensagem.limpar(mensagem);
        atualizarResumo();
    }

    private void atualizarResumo() {
        int produtos = tabela.getItems().size();
        int etiquetas = tabela.getItems().stream().mapToInt(Etiqueta::quantidade).sum();
        long pendentes = tabela.getItems().stream().filter(Etiqueta::pendente).count();
        resumo.setText((produtos == 1 ? "1 produto" : produtos + " produtos") + ", "
                + (etiquetas == 1 ? "1 etiqueta" : etiquetas + " etiquetas")
                + (pendentes > 0 ? " (" + pendentes + " pendente" + (pendentes == 1 ? ")" : "s)") : ""));
        boolean vazia = produtos == 0;
        botaoPdf.setDisable(vazia);
        botaoTermica.setDisable(vazia);
        botaoEmDia.setDisable(vazia);
    }

    // ----- Impressão -----

    @FXML
    private void gerarPdf() {
        int posicao;
        try {
            posicao = posicao();
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
            return;
        }
        String nome = "etiquetas_" + etiquetaService.hoje().format(DateTimeFormatter.BASIC_ISO_DATE) + ".pdf";
        Exportacao.escolher(tabela.getScene().getWindow(), "PDF", "pdf", nome)
                .ifPresent(destino -> gerarPdf(destino, posicao));
    }

    /** Gera o PDF em {@code destino} (em {@code Task}) e registra a impressão. */
    void gerarPdf(Path destino, int posicao) {
        List<Etiqueta> etiquetas = List.copyOf(tabela.getItems());
        Configuracoes.Etiquetas layout = etiquetaService.configuracao(sessao);
        executar("Gerando o PDF…", () -> {
            etiquetasPdf.salvar(etiquetas, layout, posicao, destino);
            etiquetaService.registrarImpressao(sessao, etiquetas, OrigemEtiqueta.PDF);
            int total = etiquetas.stream().mapToInt(Etiqueta::quantidade).sum();
            int folhas = EtiquetasPdf.folhas(total, layout, posicao);
            return "PDF salvo em " + destino + " (" + total + (total == 1 ? " etiqueta" : " etiquetas") + ", "
                    + folhas + (folhas == 1 ? " folha)." : " folhas).");
        });
    }

    @FXML
    private void imprimirTermica() {
        List<Etiqueta> etiquetas = List.copyOf(tabela.getItems());
        int total = etiquetas.stream().mapToInt(Etiqueta::quantidade).sum();
        if (!Dialogos.confirmar(tabela.getScene().getWindow(), "Enviar " + total
                + (total == 1 ? " etiqueta" : " etiquetas") + " para a impressora térmica?")) {
            return;
        }
        Configuracoes.Etiquetas layout = etiquetaService.configuracao(sessao);
        executar("Enviando para a impressora térmica…", () -> {
            int enviadas = impressaoTermica.imprimir(layout.impressoraTermica(), layout.modeloTermica(), etiquetas);
            etiquetaService.registrarImpressao(sessao, etiquetas, OrigemEtiqueta.TERMICA);
            return enviadas + (enviadas == 1 ? " etiqueta enviada" : " etiquetas enviadas") + " para \""
                    + layout.impressoraTermica() + "\".";
        });
    }

    @FXML
    private void marcarEmDia() {
        List<Long> ids = tabela.getItems().stream().map(Etiqueta::produtoId).toList();
        if (!Dialogos.confirmar(tabela.getScene().getWindow(), "Marcar " + (ids.size() == 1 ? "1 produto"
                : ids.size() + " produtos") + " como em dia, sem imprimir?\n\nUse quando as etiquetas da gôndola já "
                + "estão com o preço certo: eles deixam de ficar pendentes até o próximo preço ou promoção.")) {
            return;
        }
        try {
            int marcados = etiquetaService.marcarEmDia(sessao, ids);
            recarregar();
            Mensagem.sucesso(mensagem, (marcados == 1 ? "1 produto marcado" : marcados + " produtos marcados")
                    + " como em dia.");
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    private int posicao() {
        Configuracoes.Etiquetas layout = etiquetaService.configuracao(sessao);
        String texto = Textos.normalizarEspacos(campoPosicao.getText());
        int posicao = Textos.soDigitos(texto) && texto.length() <= 4 ? Integer.parseInt(texto) : -1;
        if (posicao < 1 || posicao > layout.porFolha()) {
            throw new NegocioException("A posição inicial deve ser " + dicaFolha(layout) + ".");
        }
        return posicao;
    }

    private void executar(String andamento, Supplier<String> trabalho) {
        List<Button> botoes = List.of(botaoPdf, botaoTermica, botaoEmDia);
        Task<String> tarefa = new Task<>() {
            @Override
            protected String call() {
                return trabalho.get();
            }
        };
        botoes.forEach(b -> b.setDisable(true));
        Mensagem.informar(mensagem, andamento);
        tarefa.setOnSucceeded(e -> {
            recarregar();
            Mensagem.sucesso(mensagem, tarefa.getValue());
        });
        tarefa.setOnFailed(e -> {
            atualizarResumo();
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(tarefa.getException()));
        });
        Tarefas.executar("etiquetas", tarefa);
    }
}
