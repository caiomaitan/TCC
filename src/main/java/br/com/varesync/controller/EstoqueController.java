package br.com.varesync.controller;

import br.com.varesync.model.Lote;
import br.com.varesync.model.MovimentoKardex;
import br.com.varesync.model.MovimentoLote;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.ResultadoAjuste;
import br.com.varesync.model.SaldoEstoque;
import br.com.varesync.model.TipoMovimento;
import br.com.varesync.service.EstoqueService;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.CampoNumero;
import br.com.varesync.ui.ConversorData;
import br.com.varesync.ui.DialogosEstoque;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * Consulta de estoque (6.4, Fig. 17 revisada): busca por código, código de barras ou nome; cabeçalho com saldo,
 * mínimo, custo médio e valor em estoque; kardex por período (padrão: últimos 30 dias), começando pela linha
 * "Saldo anterior" (F10.4); ajustes manuais.
 * Saldo abaixo do mínimo em destaque e saldo negativo em vermelho.
 */
public class EstoqueController {

    private static final String NEGATIVO = "negativo";
    private static final String ABAIXO_DO_MINIMO = "abaixo-minimo";
    private static final String SALDO_ANTERIOR = "saldo-anterior";
    private static final String LOTE_CRITICO = "linha-erro";
    private static final String LOTE_VENCENDO = "linha-aviso";

    private final EstoqueService estoqueService;
    private final Sessao sessao;
    /** Produto do cabeçalho e do kardex; nulo sem seleção. */
    private SaldoEstoque produto;
    /** Lotes do produto selecionado (F17.3); nulo sem seleção. */
    private EstoqueService.LotesDoProduto lotes;

    @FXML private VBox raiz;
    @FXML private TextField campoBusca;
    @FXML private CheckBox filtroAbaixoMinimo;
    @FXML private CheckBox filtroSemLoteNegativo;
    @FXML private CheckBox filtroInativos;
    @FXML private TableView<SaldoEstoque> tabelaProdutos;
    @FXML private TableColumn<SaldoEstoque, Long> colunaCodigo;
    @FXML private TableColumn<SaldoEstoque, String> colunaNome;
    @FXML private TableColumn<SaldoEstoque, String> colunaCategoria;
    @FXML private TableColumn<SaldoEstoque, SaldoEstoque> colunaSaldo;
    @FXML private TableColumn<SaldoEstoque, SaldoEstoque> colunaMinimo;
    @FXML private TableColumn<SaldoEstoque, BigDecimal> colunaValor;
    @FXML private Label resumo;
    @FXML private Label tituloProduto;
    @FXML private Label alerta;
    @FXML private Button botaoAjustar;
    @FXML private Button botaoAjustarSaldo;
    @FXML private Label saldoAtual;
    @FXML private Label estoqueMinimo;
    @FXML private Label custoMedio;
    @FXML private Label valorEstoque;
    @FXML private DatePicker dataInicio;
    @FXML private DatePicker dataFim;
    @FXML private Label mensagem;
    @FXML private TableView<MovimentoKardex> tabelaKardex;
    @FXML private Label semMovimentos;
    @FXML private TableColumn<MovimentoKardex, String> colunaDataHora;
    @FXML private TableColumn<MovimentoKardex, String> colunaTipo;
    @FXML private TableColumn<MovimentoKardex, BigDecimal> colunaQuantidade;
    @FXML private TableColumn<MovimentoKardex, BigDecimal> colunaSaldoApos;
    @FXML private TableColumn<MovimentoKardex, String> colunaCusto;
    @FXML private TableColumn<MovimentoKardex, String> colunaUsuario;
    @FXML private TableColumn<MovimentoKardex, String> colunaOrigem;
    @FXML private TabPane abasProduto;
    @FXML private Tab abaLotes;
    @FXML private Label avisoLotes;
    @FXML private CheckBox mostrarZerados;
    @FXML private Button botaoTransferir;
    @FXML private TableView<Lote> tabelaLotes;
    @FXML private Label semLotes;
    @FXML private TableColumn<Lote, String> colunaLote;
    @FXML private TableColumn<Lote, String> colunaValidade;
    @FXML private TableColumn<Lote, BigDecimal> colunaSaldoLote;
    @FXML private TableColumn<Lote, String> colunaDias;
    @FXML private TableColumn<Lote, String> colunaSituacaoLote;
    @FXML private Label tituloRazao;
    @FXML private TableView<MovimentoLote> tabelaRazao;
    @FXML private TableColumn<MovimentoLote, String> colunaRazaoData;
    @FXML private TableColumn<MovimentoLote, String> colunaRazaoTipo;
    @FXML private TableColumn<MovimentoLote, String> colunaRazaoDocumento;
    @FXML private TableColumn<MovimentoLote, BigDecimal> colunaRazaoQuantidade;

    public EstoqueController(EstoqueService estoqueService, Sessao sessao) {
        this.estoqueService = estoqueService;
        this.sessao = sessao;
    }

    @FXML
    private void initialize() {
        colunaCodigo.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().produtoId()));
        colunaNome.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().nome()));
        colunaCategoria.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().categoriaNome() == null ? "" : c.getValue().categoriaNome()));
        colunaSaldo.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        colunaSaldo.setComparator(Comparator.comparing(SaldoEstoque::saldo));
        colunaSaldo.setCellFactory(col -> celulaQuantidade(SaldoEstoque::saldo));
        colunaMinimo.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        colunaMinimo.setComparator(Comparator.comparing(SaldoEstoque::estoqueMinimo));
        colunaMinimo.setCellFactory(col -> celulaQuantidade(SaldoEstoque::estoqueMinimo));
        colunaValor.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().valorEmEstoque()));
        colunaValor.setCellFactory(col -> celulaNumero(Formatos::moeda));
        tabelaProdutos.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(SaldoEstoque saldo, boolean vazio) {
                super.updateItem(saldo, vazio);
                getStyleClass().removeAll(ABAIXO_DO_MINIMO, "inativo");
                if (!vazio && saldo != null) {
                    if (saldo.abaixoDoMinimo()) {
                        getStyleClass().add(ABAIXO_DO_MINIMO);
                    }
                    if (!saldo.ativo()) {
                        getStyleClass().add("inativo");
                    }
                }
            }
        });
        tabelaProdutos.getSelectionModel().selectedItemProperty()
                .addListener((obs, anterior, selecionado) -> mostrar(selecionado));

        tabelaKardex.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(MovimentoKardex linha, boolean vazio) {
                super.updateItem(linha, vazio);
                getStyleClass().remove(SALDO_ANTERIOR);
                if (!vazio && linha != null && linha.linhaSaldoAnterior()) {
                    getStyleClass().add(SALDO_ANTERIOR);
                }
            }
        });
        colunaDataHora.setCellValueFactory(c -> new ReadOnlyStringWrapper(Formatos.dataHora(c.getValue().criadoEm())));
        colunaTipo.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().descricaoTipo()));
        colunaQuantidade.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().quantidade()));
        colunaQuantidade.setCellFactory(col -> celulaKardex(true));
        colunaSaldoApos.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().saldoPosterior()));
        colunaSaldoApos.setCellFactory(col -> celulaKardex(false));
        colunaCusto.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().linhaSaldoAnterior() ? ""
                : custo(c.getValue().custoUnitario())));
        colunaUsuario.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().usuarioNome()));
        colunaOrigem.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().origem()));

        prepararLotes();

        StringConverter<LocalDate> conversor = conversorData();
        dataInicio.setConverter(conversor);
        dataFim.setConverter(conversor);
        Periodo padrao = estoqueService.periodoPadrao();
        dataInicio.setValue(padrao.inicio());
        dataFim.setValue(padrao.fim());

        raiz.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.F4) {
                e.consume();
                ajustar();
            } else if (e.getCode() == KeyCode.F5) {
                e.consume();
                ajustarSaldo();
            }
        });

        mostrar(null);
        pesquisar();
        Platform.runLater(campoBusca::requestFocus);
    }

    /** Abre já filtrando os produtos abaixo do mínimo (alerta do painel). */
    public void mostrarAbaixoDoMinimo() {
        filtroAbaixoMinimo.setSelected(true);
        pesquisar();
    }

    /** Abre já filtrando os produtos com o "SEM LOTE" negativo (alerta do painel, F17.3). */
    public void mostrarSemLoteNegativo() {
        filtroSemLoteNegativo.setSelected(true);
        pesquisar();
        abasProduto.getSelectionModel().select(abaLotes);
    }

    @FXML
    private void pesquisar() {
        Long selecionado = produto == null ? null : produto.produtoId();
        tabelaProdutos.getItems().setAll(estoqueService.pesquisar(sessao, campoBusca.getText(),
                filtroAbaixoMinimo.isSelected(), filtroInativos.isSelected(), filtroSemLoteNegativo.isSelected()));
        long abaixo = tabelaProdutos.getItems().stream().filter(SaldoEstoque::abaixoDoMinimo).count();
        int total = tabelaProdutos.getItems().size();
        resumo.setText((total == 1 ? "1 produto" : total + " produtos")
                + (abaixo == 0 ? "" : " · " + abaixo + " abaixo do mínimo"));
        Optional<SaldoEstoque> mesmo = selecionado == null ? Optional.empty()
                : tabelaProdutos.getItems().stream().filter(s -> s.produtoId() == selecionado).findFirst();
        if (mesmo.isPresent()) {
            tabelaProdutos.getSelectionModel().select(mesmo.get());
        } else if (total == 1) {
            tabelaProdutos.getSelectionModel().selectFirst();
        } else {
            tabelaProdutos.getSelectionModel().clearSelection();
        }
        SaldoEstoque escolhido = tabelaProdutos.getSelectionModel().getSelectedItem();
        if (escolhido != produto) { // o listener da seleção pode já ter mostrado
            mostrar(escolhido);
        }
    }

    /** Preenche o cabeçalho e o kardex do produto (nulo = sem seleção). */
    private void mostrar(SaldoEstoque saldo) {
        produto = saldo;
        Mensagem.limpar(mensagem);
        botaoAjustar.setDisable(saldo == null);
        botaoAjustarSaldo.setDisable(saldo == null);
        alternar(saldoAtual, NEGATIVO, saldo != null && saldo.saldo().signum() < 0);
        alternar(saldoAtual, ABAIXO_DO_MINIMO, saldo != null && saldo.abaixoDoMinimo() && saldo.saldo().signum() >= 0);
        if (saldo == null) {
            tituloProduto.setText("Selecione um produto");
            alerta.setText("");
            saldoAtual.setText(Formatos.SEM_VALOR);
            estoqueMinimo.setText(Formatos.SEM_VALOR);
            custoMedio.setText(Formatos.SEM_VALOR);
            valorEstoque.setText(Formatos.SEM_VALOR);
            semMovimentos.setText("Selecione um produto para ver a movimentação.");
            tabelaKardex.getItems().clear();
            mostrarLotes();
            return;
        }
        tituloProduto.setText(saldo.descricao() + (saldo.ativo() ? "" : " — inativo"));
        alerta.setText(saldo.saldo().signum() < 0 ? "Saldo negativo"
                : saldo.abaixoDoMinimo() ? "Abaixo do estoque mínimo" : "");
        saldoAtual.setText(quantidade(saldo.saldo()));
        estoqueMinimo.setText(quantidade(saldo.estoqueMinimo()));
        custoMedio.setText(custo(saldo.custo()));
        valorEstoque.setText(Formatos.moeda(saldo.valorEmEstoque()));
        atualizarKardex();
        mostrarLotes();
    }

    @FXML
    private void atualizarKardex() {
        if (produto == null) {
            return;
        }
        try {
            tabelaKardex.getItems().setAll(estoqueService.kardex(sessao, produto.produtoId(),
                    new Periodo(dataInicio.getValue(), dataFim.getValue())).linhas());
            semMovimentos.setText("Nenhum movimento no período.");
            Mensagem.limpar(mensagem);
            tabelaKardex.scrollTo(Math.max(0, tabelaKardex.getItems().size() - 1));
        } catch (NegocioException e) {
            tabelaKardex.getItems().clear();
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    // ----- Ajustes (6.4) -----

    @FXML
    private void ajustar() {
        if (produto == null) {
            return;
        }
        long id = produto.produtoId();
        // F17.3: com o controle ligado, o diálogo oferece o lote (lidos agora, com o saldo atual).
        List<Lote> doProduto = estoqueService.lotesDoProduto(sessao, id).controla()
                ? estoqueService.lotes(sessao, id) : null;
        DialogosEstoque.ajustar(produto.descricao(), quantidade(produto.saldo()), doProduto,
                        produto.unidadeFracionada(),
                        (tipo, quantidade, motivo, observacao, lote) -> estoqueService.ajustar(sessao, id, tipo,
                                CampoNumero.ler(quantidade, "Quantidade"), motivo, observacao, lote))
                .ifPresent(this::ajustado);
    }

    // ----- Lotes (F17.3) -----

    private void prepararLotes() {
        colunaLote.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().semLote() ? Lote.SEM_LOTE
                : c.getValue().codigo().isEmpty() ? "(sem código)" : c.getValue().codigo()));
        colunaValidade.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().validade() == null ? "—"
                : Formatos.data(c.getValue().validade())));
        colunaSaldoLote.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().saldo()));
        colunaSaldoLote.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(BigDecimal valor, boolean vazio) {
                super.updateItem(valor, vazio);
                setText(vazio || valor == null || produto == null ? null
                        : Formatos.quantidade(valor, produto.unidadeFracionada()));
            }
        });
        colunaDias.setCellValueFactory(c -> {
            Long dias = lotes == null ? null : lotes.diasParaVencer(c.getValue());
            return new ReadOnlyStringWrapper(dias == null ? "—" : String.valueOf(dias));
        });
        colunaSituacaoLote.setCellValueFactory(c -> new ReadOnlyStringWrapper(lotes == null ? ""
                : lotes.situacao(c.getValue())));
        tabelaLotes.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(Lote lote, boolean vazio) {
                super.updateItem(lote, vazio);
                getStyleClass().removeAll(LOTE_CRITICO, LOTE_VENCENDO);
                if (!vazio && lote != null && lotes != null) {
                    if (lotes.critico(lote)) {
                        getStyleClass().add(LOTE_CRITICO);
                    } else if (lotes.vencendo(lote)) {
                        getStyleClass().add(LOTE_VENCENDO);
                    }
                }
            }
        });
        tabelaLotes.getSelectionModel().selectedItemProperty().addListener((o, a, lote) -> mostrarRazao(lote));
        colunaRazaoData.setCellValueFactory(c -> new ReadOnlyStringWrapper(Formatos.dataHora(c.getValue().criadoEm())));
        colunaRazaoTipo.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().tipo()));
        colunaRazaoDocumento.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().documento()));
        colunaRazaoQuantidade.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().quantidade()));
        colunaRazaoQuantidade.setCellFactory(col -> celulaComSinal());
    }

    /** Lotes do produto selecionado: os zerados só com "Mostrar lotes zerados"; em produto sem controle, o aviso. */
    private void mostrarLotes() {
        Long selecionado = Optional.ofNullable(tabelaLotes.getSelectionModel().getSelectedItem()).map(Lote::id)
                .orElse(null);
        tabelaLotes.getItems().clear();
        tabelaRazao.getItems().clear();
        tituloRazao.setText("Razão do lote");
        if (produto == null) {
            lotes = null;
            avisoLotes.setText("");
            semLotes.setText("Selecione um produto para ver os lotes.");
            botaoTransferir.setDisable(true);
            return;
        }
        lotes = estoqueService.lotesDoProduto(sessao, produto.produtoId());
        if (!lotes.controla()) {
            avisoLotes.setText("Este produto não controla validade e lotes. Para ligar, abra o produto em Produtos e "
                    + "marque \"Controla validade e lotes\".");
            semLotes.setText("Produto sem controle de validade.");
            botaoTransferir.setDisable(true);
            return;
        }
        List<Lote> visiveis = lotes.lotes().stream()
                .filter(l -> mostrarZerados.isSelected() || l.saldo().signum() != 0).toList();
        long ocultos = lotes.lotes().size() - visiveis.size();
        avisoLotes.setText("Vencido e " + Lote.SEM_LOTE + " negativo em vermelho; vencendo em até "
                + lotes.alertaDias() + (lotes.alertaDias() == 1 ? " dia" : " dias") + " em amarelo."
                + (ocultos == 0 ? "" : " " + (ocultos == 1 ? "1 lote zerado oculto." : ocultos + " lotes zerados ocultos.")));
        semLotes.setText("Nenhum lote com saldo. A entrada de mercadoria e o ajuste de entrada criam os lotes.");
        tabelaLotes.getItems().setAll(visiveis);
        visiveis.stream().filter(l -> selecionado != null && l.id() == selecionado).findFirst()
                .ifPresent(l -> tabelaLotes.getSelectionModel().select(l));
        botaoTransferir.setDisable(tabelaLotes.getSelectionModel().getSelectedItem() == null);
    }

    @FXML
    private void atualizarLotes() {
        mostrarLotes();
    }

    private void mostrarRazao(Lote lote) {
        botaoTransferir.setDisable(lote == null || lotes == null || !lotes.controla());
        if (lote == null || produto == null) {
            tabelaRazao.getItems().clear();
            tituloRazao.setText("Razão do lote");
            return;
        }
        tituloRazao.setText("Razão do lote " + lote.descricao());
        tabelaRazao.getItems().setAll(estoqueService.razaoDoLote(sessao, produto.produtoId(), lote.id()));
        tabelaRazao.scrollTo(Math.max(0, tabelaRazao.getItems().size() - 1));
    }

    /** "Transferir…": do lote selecionado para um lote existente ou novo (ESTOQUE, com motivo). */
    @FXML
    private void transferir() {
        Lote origem = tabelaLotes.getSelectionModel().getSelectedItem();
        if (produto == null || origem == null || lotes == null || !lotes.controla()) {
            return;
        }
        long id = produto.produtoId();
        SaldoEstoque doProduto = produto;
        boolean feito = DialogosEstoque.transferir(produto.descricao(), origem, lotes.lotes(),
                produto.unidadeFracionada(), (destino, quantidade, motivo) -> estoqueService.transferirEntreLotes(
                        sessao, id, origem.id(), destino.comQuantidade(CampoNumero.ler(quantidade, "Quantidade")),
                        motivo));
        if (feito) {
            mostrarLotes();
            Mensagem.sucesso(mensagem, "Transferência registrada no produto " + doProduto.nome()
                    + ". O estoque do produto não mudou.");
        }
    }

    /** Quantidade do razão com sinal (+/−), na unidade do produto; negativa em vermelho. */
    private TableCell<MovimentoLote, BigDecimal> celulaComSinal() {
        return new TableCell<>() {
            @Override
            protected void updateItem(BigDecimal valor, boolean vazio) {
                super.updateItem(valor, vazio);
                getStyleClass().remove(NEGATIVO);
                if (vazio || valor == null || produto == null) {
                    setText(null);
                    return;
                }
                setText((valor.signum() < 0 ? "−" : "+")
                        + Formatos.quantidade(valor.abs(), produto.unidadeFracionada()));
                if (valor.signum() < 0) {
                    getStyleClass().add(NEGATIVO);
                }
            }
        };
    }

    @FXML
    private void ajustarSaldo() {
        if (produto == null) {
            return;
        }
        long id = produto.produtoId();
        BigDecimal atual = produto.saldo();
        DialogosEstoque.ajustarSaldoPara(produto.descricao(), quantidade(atual), texto -> previa(atual, texto),
                        (novoSaldo, motivo) -> estoqueService.ajustarSaldoPara(sessao, id,
                                CampoNumero.ler(novoSaldo, "Novo saldo"), motivo))
                .ifPresent(this::ajustado);
    }

    /** Recarrega a lista, o cabeçalho e o kardex e confirma o que foi gravado. */
    private void ajustado(ResultadoAjuste resultado) {
        SaldoEstoque ajustado = produto;
        pesquisar();
        String unidade = " " + ajustado.unidade();
        // o produto pode ter saído da lista pelo filtro (ex.: deixou de estar abaixo do mínimo)
        Mensagem.sucesso(mensagem, "Registrada " + acao(resultado.tipo()) + " de "
                + Formatos.quantidade(resultado.quantidade().abs(), ajustado.unidadeFracionada()) + unidade
                + " em " + ajustado.nome() + ". Saldo: "
                + Formatos.quantidade(resultado.saldoPosterior(), ajustado.unidadeFracionada()) + unidade + ".");
    }

    /** "Será registrada uma saída de 3 UN." enquanto o novo saldo é digitado. */
    private String previa(BigDecimal atual, String texto) {
        if (Textos.vazio(texto)) {
            return "";
        }
        BigDecimal novo;
        try {
            novo = Dinheiro.ler(texto);
        } catch (IllegalArgumentException e) {
            return "";
        }
        BigDecimal diferenca = novo.subtract(atual);
        if (diferenca.signum() == 0) {
            return "O saldo já é este: não há o que ajustar.";
        }
        return "Será registrada uma " + (diferenca.signum() > 0 ? "entrada" : "saída") + " de "
                + quantidade(diferenca.abs()) + ".";
    }

    private static String acao(TipoMovimento tipo) {
        return switch (tipo) {
            case AJUSTE_ENTRADA -> "entrada";
            case AJUSTE_SAIDA -> "saída";
            case PERDA -> "perda";
            default -> tipo.descricao().toLowerCase(Formatos.PT_BR);
        };
    }

    // ----- Formatação -----

    private String quantidade(BigDecimal valor) {
        return Formatos.quantidade(valor, produto.unidadeFracionada()) + " " + produto.unidade();
    }

    private static String custo(BigDecimal valor) {
        return valor == null ? Formatos.SEM_VALOR : "R$ " + Formatos.numero(valor, Dinheiro.ESCALA_CUSTO);
    }

    private static void alternar(Label rotulo, String estilo, boolean ligado) {
        rotulo.getStyleClass().remove(estilo);
        if (ligado) {
            rotulo.getStyleClass().add(estilo);
        }
    }

    /** Quantidade da linha com a unidade do produto; negativa em vermelho. */
    private static TableCell<SaldoEstoque, SaldoEstoque> celulaQuantidade(Function<SaldoEstoque, BigDecimal> valor) {
        return new TableCell<>() {
            @Override
            protected void updateItem(SaldoEstoque saldo, boolean vazio) {
                super.updateItem(saldo, vazio);
                getStyleClass().remove(NEGATIVO);
                if (vazio || saldo == null) {
                    setText(null);
                    return;
                }
                BigDecimal q = valor.apply(saldo);
                setText(Formatos.quantidade(q, saldo.unidadeFracionada()) + " " + saldo.unidade());
                if (q.signum() < 0) {
                    getStyleClass().add(NEGATIVO);
                }
            }
        };
    }

    private static TableCell<SaldoEstoque, BigDecimal> celulaNumero(Function<BigDecimal, String> formato) {
        return new TableCell<>() {
            @Override
            protected void updateItem(BigDecimal valor, boolean vazio) {
                super.updateItem(valor, vazio);
                setText(vazio || valor == null ? null : formato.apply(valor));
            }
        };
    }

    /** Quantidade do kardex: com sinal (+/−) na coluna Quantidade; negativos em vermelho. */
    private TableCell<MovimentoKardex, BigDecimal> celulaKardex(boolean comSinal) {
        return new TableCell<>() {
            @Override
            protected void updateItem(BigDecimal valor, boolean vazio) {
                super.updateItem(valor, vazio);
                getStyleClass().remove(NEGATIVO);
                if (vazio || valor == null || produto == null) {
                    setText(null);
                    return;
                }
                String numero = Formatos.quantidade(valor.abs(), produto.unidadeFracionada());
                if (comSinal) {
                    setText((valor.signum() < 0 ? "−" : "+") + numero);
                } else {
                    setText((valor.signum() < 0 ? "-" : "") + numero);
                }
                if (valor.signum() < 0) {
                    getStyleClass().add(NEGATIVO);
                }
            }
        };
    }

    /** dd/MM/aaaa, independentemente do idioma do computador. */
    private static StringConverter<LocalDate> conversorData() {
        return new ConversorData();
    }
}
