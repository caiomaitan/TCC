package br.com.varesync.controller;

import br.com.varesync.model.MovimentoKardex;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.ResultadoAjuste;
import br.com.varesync.model.SaldoEstoque;
import br.com.varesync.model.TipoMovimento;
import br.com.varesync.service.EstoqueService;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.ConversorData;
import br.com.varesync.ui.DialogosEstoque;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
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
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * Consulta de estoque (6.4, Fig. 17 revisada): busca por código, código de barras ou nome; cabeçalho com saldo,
 * mínimo, custo médio e valor em estoque; kardex por período (padrão: últimos 30 dias); ajustes manuais.
 * Saldo abaixo do mínimo em destaque e saldo negativo em vermelho.
 */
public class EstoqueController {

    private static final String NEGATIVO = "negativo";
    private static final String ABAIXO_DO_MINIMO = "abaixo-minimo";

    private final EstoqueService estoqueService;
    private final Sessao sessao;
    /** Produto do cabeçalho e do kardex; nulo sem seleção. */
    private SaldoEstoque produto;

    @FXML private VBox raiz;
    @FXML private TextField campoBusca;
    @FXML private CheckBox filtroAbaixoMinimo;
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

        colunaDataHora.setCellValueFactory(c -> new ReadOnlyStringWrapper(Formatos.dataHora(c.getValue().criadoEm())));
        colunaTipo.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().tipo().descricao()));
        colunaQuantidade.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().quantidade()));
        colunaQuantidade.setCellFactory(col -> celulaKardex(true));
        colunaSaldoApos.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().saldoPosterior()));
        colunaSaldoApos.setCellFactory(col -> celulaKardex(false));
        colunaCusto.setCellValueFactory(c -> new ReadOnlyStringWrapper(custo(c.getValue().custoUnitario())));
        colunaUsuario.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().usuarioNome()));
        colunaOrigem.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().origem()));

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

    @FXML
    private void pesquisar() {
        Long selecionado = produto == null ? null : produto.produtoId();
        tabelaProdutos.getItems().setAll(estoqueService.pesquisar(sessao, campoBusca.getText(),
                filtroAbaixoMinimo.isSelected(), filtroInativos.isSelected()));
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
    }

    @FXML
    private void atualizarKardex() {
        if (produto == null) {
            return;
        }
        try {
            tabelaKardex.getItems().setAll(estoqueService.kardex(sessao, produto.produtoId(),
                    new Periodo(dataInicio.getValue(), dataFim.getValue())));
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
        DialogosEstoque.ajustar(produto.descricao(), quantidade(produto.saldo()),
                        (tipo, quantidade, motivo, observacao) -> estoqueService.ajustar(sessao, id, tipo,
                                ler(quantidade, "Quantidade"), motivo, observacao))
                .ifPresent(this::ajustado);
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
                                ler(novoSaldo, "Novo saldo"), motivo))
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

    /** Vazio ou texto inválido → mensagem com o nome do campo. */
    private static BigDecimal ler(String texto, String rotulo) {
        if (Textos.vazio(texto)) {
            return null;
        }
        try {
            return Dinheiro.ler(texto);
        } catch (IllegalArgumentException e) {
            throw new NegocioException(rotulo + ": valor inválido (\"" + texto.strip() + "\").");
        }
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
