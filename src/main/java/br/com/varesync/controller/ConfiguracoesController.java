package br.com.varesync.controller;

import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.FormaPagamentoCadastro;
import br.com.varesync.model.InconsistenciaEstoque;
import br.com.varesync.model.ParametrosVenda;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.model.TipoValorBalanca;
import br.com.varesync.service.ConfiguracaoService;
import br.com.varesync.service.EstoqueService;
import br.com.varesync.service.GeradorDemo;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.ui.Tarefas;
import br.com.varesync.ui.TratadorExcecoes;
import br.com.varesync.util.CpfCnpj;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Gtin;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.util.StringConverter;

/**
 * Configurações (6.13, só supervisor): dados da loja, parâmetros de venda, margem mínima e dias sem giro, balança
 * (salvos juntos por "Salvar alterações"), formas de pagamento, o diagnóstico do estoque (6.4) e o gerador de dados
 * de demonstração (6.14). Diagnóstico e gerador rodam em {@link Task}.
 */
public class ConfiguracoesController {

    private final ConfiguracaoService configuracaoService;
    private final EstoqueService estoqueService;
    private final GeradorDemo geradorDemo;
    private final Sessao sessao;
    /** O que está gravado (base do "Desfazer" e do que não é editado aqui, como o backup). */
    private Configuracoes gravadas;

    @FXML private TabPane abas;
    @FXML private Tab abaLoja;
    @FXML private Tab abaVenda;
    @FXML private Tab abaRelatorios;
    @FXML private Tab abaBalanca;
    @FXML private Tab abaFormas;
    @FXML private TextField campoNomeLoja;
    @FXML private TextField campoDocumento;
    @FXML private TextField campoEndereco;
    @FXML private TextField campoTelefone;
    @FXML private TextField campoRodape;
    @FXML private CheckBox campoEstoqueNegativo;
    @FXML private TextField campoDescontoMaximo;
    @FXML private CheckBox campoCancelarItem;
    @FXML private TextField campoMargemMinima;
    @FXML private TextField campoDiasSemGiro;
    @FXML private CheckBox campoBalancaAtiva;
    @FXML private TextField campoPrefixo;
    @FXML private ComboBox<Integer> campoDigitos;
    @FXML private ComboBox<TipoValorBalanca> campoTipoValor;
    @FXML private Label exemploEtiqueta;
    @FXML private HBox barraSalvar;
    @FXML private Label mensagemParametros;
    @FXML private Button botaoSalvar;
    @FXML private TableView<FormaPagamentoCadastro> tabelaFormas;
    @FXML private TableColumn<FormaPagamentoCadastro, String> colunaFormaDescricao;
    @FXML private TableColumn<FormaPagamentoCadastro, String> colunaFormaTipo;
    @FXML private TableColumn<FormaPagamentoCadastro, String> colunaFormaSituacao;
    @FXML private Button botaoSituacaoForma;
    @FXML private TextField campoNovaForma;
    @FXML private ComboBox<TipoFormaPagamento> campoTipoForma;
    @FXML private Label mensagemFormas;
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

    public ConfiguracoesController(ConfiguracaoService configuracaoService, EstoqueService estoqueService,
                                   GeradorDemo geradorDemo, Sessao sessao) {
        this.configuracaoService = configuracaoService;
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

        campoDigitos.getItems().setAll(4, 5, 6);
        campoTipoValor.getItems().setAll(TipoValorBalanca.values());
        campoBalancaAtiva.selectedProperty().addListener((o, a, n) -> atualizarBalanca());
        campoPrefixo.textProperty().addListener((o, a, n) -> atualizarBalanca());
        campoDigitos.valueProperty().addListener((o, a, n) -> atualizarBalanca());
        campoTipoValor.valueProperty().addListener((o, a, n) -> atualizarBalanca());
        campoDocumento.focusedProperty().addListener((o, a, focado) -> {
            if (!focado && !Textos.vazio(campoDocumento.getText())
                    && CpfCnpj.validar(CpfCnpj.normalizar(campoDocumento.getText())).isEmpty()) {
                campoDocumento.setText(CpfCnpj.formatar(CpfCnpj.normalizar(campoDocumento.getText())));
            }
        });

        Set<Tab> parametros = Set.of(abaLoja, abaVenda, abaRelatorios, abaBalanca);
        abas.getSelectionModel().selectedItemProperty().addListener((o, a, aba) -> {
            boolean visivel = parametros.contains(aba);
            barraSalvar.setVisible(visivel);
            barraSalvar.setManaged(visivel);
        });

        colunaFormaDescricao.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().descricao()));
        colunaFormaTipo.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().tipo().rotulo()));
        colunaFormaSituacao.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().ativa() ? "Ativa"
                : "Desativada"));
        tabelaFormas.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(FormaPagamentoCadastro f, boolean vazio) {
                super.updateItem(f, vazio);
                getStyleClass().remove("inativo");
                if (!vazio && f != null && !f.ativa()) {
                    getStyleClass().add("inativo");
                }
            }
        });
        tabelaFormas.getSelectionModel().selectedItemProperty().addListener((o, a, f) -> {
            botaoSituacaoForma.setDisable(f == null);
            botaoSituacaoForma.setText(f == null || f.ativa() ? "Desativar" : "Ativar");
        });
        botaoSituacaoForma.setDisable(true);
        campoTipoForma.getItems().setAll(TipoFormaPagamento.values());
        campoTipoForma.setConverter(new StringConverter<>() {
            @Override
            public String toString(TipoFormaPagamento tipo) {
                return tipo == null ? "" : tipo.rotulo();
            }

            @Override
            public TipoFormaPagamento fromString(String texto) {
                return null;
            }
        });
        campoTipoForma.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(TipoFormaPagamento tipo, boolean vazio) {
                super.updateItem(tipo, vazio);
                setText(vazio || tipo == null ? "Tipo" : tipo.rotulo());
            }
        });
        campoTipoForma.setValue(TipoFormaPagamento.OUTROS);

        mostrar(configuracaoService.carregar(sessao));
        carregarFormas(null);
        verificar();
    }

    // ----- Parâmetros (loja, venda, relatórios, balança) -----

    private void mostrar(Configuracoes c) {
        gravadas = c;
        campoNomeLoja.setText(c.loja().nome());
        campoDocumento.setText(c.loja().documento().isEmpty() ? "" : CpfCnpj.formatar(c.loja().documento()));
        campoEndereco.setText(c.loja().endereco());
        campoTelefone.setText(c.loja().telefone().isEmpty() ? "" : Formatos.telefone(c.loja().telefone()));
        campoRodape.setText(c.rodapeComprovante());
        campoEstoqueNegativo.setSelected(c.venda().permitirEstoqueNegativo());
        campoDescontoMaximo.setText(Formatos.numero(c.venda().descontoMaximoPct(), Dinheiro.ESCALA_PERCENTUAL));
        campoCancelarItem.setSelected(c.venda().cancelarItemExigeSupervisor());
        campoMargemMinima.setText(Formatos.numero(c.margemMinimaPct(), Dinheiro.ESCALA_PERCENTUAL));
        campoDiasSemGiro.setText(String.valueOf(c.diasSemGiro()));
        campoBalancaAtiva.setSelected(c.balanca().ativo());
        campoPrefixo.setText(String.valueOf(c.balanca().prefixo()));
        campoDigitos.setValue(c.balanca().digitosCodigo());
        campoTipoValor.setValue(c.balanca().tipoValor());
        atualizarBalanca();
    }

    /** Lê os campos das quatro abas; texto que não é número vira mensagem com o nome do campo. */
    Configuracoes lerCampos() {
        String prefixo = campoPrefixo.getText() == null ? "" : campoPrefixo.getText().strip();
        if (!prefixo.matches("\\d")) {
            throw new NegocioException("O prefixo da etiqueta de balança é um dígito, de 0 a 9 (normalmente 2).");
        }
        return new Configuracoes(new Configuracoes.Loja(campoNomeLoja.getText(), campoDocumento.getText(),
                campoEndereco.getText(), campoTelefone.getText()),
                new ParametrosVenda(campoEstoqueNegativo.isSelected(),
                        numero(campoDescontoMaximo, "Desconto máximo do operador"), campoCancelarItem.isSelected()),
                numero(campoMargemMinima, "Margem mínima"), inteiro(campoDiasSemGiro, "Dias sem giro"),
                new Configuracoes.Balanca(campoBalancaAtiva.isSelected(), Integer.parseInt(prefixo),
                        campoDigitos.getValue() == null ? 0 : campoDigitos.getValue(), campoTipoValor.getValue()),
                gravadas.backup(), campoRodape.getText());
    }

    private static BigDecimal numero(TextField campo, String rotulo) {
        if (Textos.vazio(campo.getText())) {
            throw new NegocioException("Informe: " + rotulo + ".");
        }
        try {
            return Dinheiro.ler(campo.getText().replace("%", "").strip());
        } catch (IllegalArgumentException e) {
            throw new NegocioException(rotulo + ": valor inválido (\"" + campo.getText().strip() + "\").");
        }
    }

    private static int inteiro(TextField campo, String rotulo) {
        try {
            return Integer.parseInt(campo.getText().strip());
        } catch (NumberFormatException | NullPointerException e) {
            throw new NegocioException(rotulo + ": informe um número inteiro.");
        }
    }

    @FXML
    void salvar() {
        try {
            List<String> mudancas = configuracaoService.salvar(sessao, lerCampos());
            mostrar(configuracaoService.carregar(sessao));
            if (mudancas.isEmpty()) {
                Mensagem.informar(mensagemParametros, "Nada mudou.");
            } else {
                Mensagem.sucesso(mensagemParametros, "Configurações salvas: " + String.join("; ", mudancas) + ".");
            }
        } catch (NegocioException e) {
            Mensagem.erro(mensagemParametros, e.getMessage());
        } catch (RuntimeException e) {
            TratadorExcecoes.mostrar(e);
        }
    }

    @FXML
    private void desfazer() {
        mostrar(gravadas);
        Mensagem.informar(mensagemParametros, "Campos voltaram ao que está gravado.");
    }

    /** Liga os campos à caixa "ler etiquetas" e mostra um exemplo de etiqueta com a configuração escolhida. */
    private void atualizarBalanca() {
        boolean ativa = campoBalancaAtiva.isSelected();
        campoPrefixo.setDisable(!ativa);
        campoDigitos.setDisable(!ativa);
        campoTipoValor.setDisable(!ativa);
        String prefixo = campoPrefixo.getText() == null ? "" : campoPrefixo.getText().strip();
        Integer digitos = campoDigitos.getValue();
        TipoValorBalanca tipo = campoTipoValor.getValue();
        if (!ativa || !prefixo.matches("\\d") || digitos == null || tipo == null) {
            exemploEtiqueta.setText(ativa ? "" : "Com a leitura desligada, o PDV não reconhece etiquetas e o campo "
                    + "PLU do cadastro de produto fica fechado.");
            return;
        }
        int casasValor = 11 - digitos;
        String plu = String.format("%0" + digitos + "d", 123);
        String valor = String.format("%0" + casasValor + "d", tipo == TipoValorBalanca.PRECO ? 1377 : 345);
        String semDv = prefixo + plu + valor;
        exemploEtiqueta.setText("Exemplo: " + prefixo + " " + plu + " " + valor + " " + Gtin.digitoVerificador(semDv)
                + " = PLU 123 com " + (tipo == TipoValorBalanca.PRECO ? "total de R$ 13,77" : "345 g") + ".");
    }

    // ----- Formas de pagamento -----

    private void carregarFormas(Integer selecionar) {
        tabelaFormas.getItems().setAll(configuracaoService.formasPagamento(sessao));
        if (selecionar != null) {
            tabelaFormas.getItems().stream().filter(f -> f.id() == selecionar).findFirst()
                    .ifPresent(f -> tabelaFormas.getSelectionModel().select(f));
        }
    }

    @FXML
    void criarForma() {
        try {
            int id = configuracaoService.criarForma(sessao, campoNovaForma.getText(), campoTipoForma.getValue());
            carregarFormas(id);
            Mensagem.sucesso(mensagemFormas, "Forma de pagamento \"" + Textos.normalizarEspacos(campoNovaForma.getText())
                    + "\" criada.");
            campoNovaForma.clear();
        } catch (NegocioException e) {
            Mensagem.erro(mensagemFormas, e.getMessage());
        } catch (RuntimeException e) {
            TratadorExcecoes.mostrar(e);
        }
    }

    @FXML
    private void alternarForma() {
        FormaPagamentoCadastro forma = tabelaFormas.getSelectionModel().getSelectedItem();
        if (forma == null) {
            return;
        }
        if (forma.ativa() && !Dialogos.confirmar("Desativar a forma de pagamento \"" + forma.descricao()
                + "\"? Ela deixa de aparecer no pagamento do PDV.")) {
            return;
        }
        try {
            configuracaoService.alterarSituacaoForma(sessao, forma.id(), !forma.ativa());
            carregarFormas(forma.id());
            Mensagem.sucesso(mensagemFormas, "\"" + forma.descricao() + "\" " + (forma.ativa() ? "desativada."
                    : "ativada."));
        } catch (NegocioException e) {
            Mensagem.erro(mensagemFormas, e.getMessage());
        } catch (RuntimeException e) {
            TratadorExcecoes.mostrar(e);
        }
    }

    // ----- Diagnóstico e dados de demonstração -----

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
        if (!Dialogos.confirmar("Gerar os dados de demonstração neste banco? Serão criados usuários, fornecedores, "
                + "cerca de 80 produtos e " + GeradorDemo.DIAS + " dias de vendas, compras, caixas e movimentos de "
                + "estoque. Isso leva alguns minutos e não pode ser desfeito.")) {
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
                .append("\nFornecedores: ").append(r.fornecedores()).append(" (").append(r.entradas())
                .append(" entradas de mercadoria confirmadas)")
                .append("\nInventário aplicado: nº ").append(r.inventarioId())
                .append("\nPeríodo: ").append(Formatos.data(r.inicio())).append(" a ").append(Formatos.data(r.fim()));
        return texto.toString();
    }
}
