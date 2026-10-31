package br.com.varesync.controller;

import br.com.varesync.model.ClienteVenda;
import br.com.varesync.model.DadosDevolucao;
import br.com.varesync.model.FormaReembolso;
import br.com.varesync.model.ItemDevolucao;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.VendaDevolucao;
import br.com.varesync.service.AcessoService;
import br.com.varesync.service.ClienteService;
import br.com.varesync.service.DevolucaoService;
import br.com.varesync.service.Liberacao;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.CampoNumero;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.DialogosCliente;
import br.com.varesync.ui.Janelas;
import br.com.varesync.ui.LiberacaoSupervisorDialogo;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.HPos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Devolver itens de uma venda (F13.2 e F13.4), aberta pela tela Devoluções e pelo "Devolver itens" do F9 do PDV:
 * quantidade e avariado por item, com o valor proporcional na hora; motivo; forma de reembolso (sem vale-troca para
 * cliente anonimizado); no vale-troca de uma venda sem cliente, o cliente identificado ou cadastrado (F11). Sem a
 * permissão DEVOLUCAO, ou fora do prazo, pede a liberação (de um supervisor, fora do prazo). Toda regra está no
 * {@link DevolucaoService}.
 */
public class DevolucaoController {

    static final List<String> MOTIVOS = List.of("Defeito", "Arrependimento", "Troca por outro produto",
            "Produto vencido", "Embalagem danificada", "Cobrança errada");

    private final DevolucaoService devolucaoService;
    private final ClienteService clienteService;
    private final AcessoService acessoService;
    private final Sessao sessao;
    private VendaDevolucao venda;
    private ClienteVenda clienteValeTroca;
    private DevolucaoService.DevolucaoRegistrada resultado;

    /** Uma linha da grade: o item, a quantidade a devolver e se está avariado (nulos no item sem saldo). */
    record Linha(ItemDevolucao item, TextField quantidade, CheckBox avariado, Label valor) {
    }

    private final List<Linha> linhas = new ArrayList<>();

    @FXML private VBox raiz;
    @FXML private Label titulo;
    @FXML private Label cabecalho;
    @FXML private Label aviso;
    @FXML private GridPane gradeItens;
    @FXML private ComboBox<String> campoMotivo;
    @FXML private ComboBox<FormaReembolso> campoForma;
    @FXML private Label cliente;
    @FXML private Button botaoCliente;
    @FXML private TextField campoObservacao;
    @FXML private Label total;
    @FXML private Label abatimento;
    @FXML private Button botaoRegistrar;
    @FXML private Label mensagem;

    public DevolucaoController(DevolucaoService devolucaoService, ClienteService clienteService,
                               AcessoService acessoService, Sessao sessao) {
        this.devolucaoService = devolucaoService;
        this.clienteService = clienteService;
        this.acessoService = acessoService;
        this.sessao = sessao;
    }

    /**
     * Abre a janela da devolução da venda e espera ela fechar.
     *
     * @return a devolução registrada, ou vazio se a pessoa fechou sem registrar
     */
    public static Optional<DevolucaoService.DevolucaoRegistrada> abrir(Window dona, DevolucaoService devolucaoService,
                                                                       ClienteService clienteService,
                                                                       AcessoService acessoService, Sessao sessao,
                                                                       long vendaId) {
        VendaDevolucao venda = devolucaoService.venda(sessao, vendaId); // venda inexistente: erro antes da janela
        DevolucaoController c = Janelas.abrirModal(dona, "/fxml/devolucao.fxml", "Devolução da venda nº " + vendaId,
                tipo -> new DevolucaoController(devolucaoService, clienteService, acessoService, sessao),
                (DevolucaoController controller) -> controller.mostrar(venda));
        return Optional.ofNullable(c.resultado);
    }

    @FXML
    private void initialize() {
        campoMotivo.getItems().setAll(MOTIVOS);
        campoForma.valueProperty().addListener((o, antes, depois) -> atualizarCliente());
        raiz.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.F10) {
                e.consume();
                registrar();
            } else if (e.getCode() == KeyCode.F11 && botaoCliente.isVisible()) {
                e.consume();
                identificarCliente();
            }
        });
    }

    void mostrar(VendaDevolucao v) {
        this.venda = v;
        titulo.setText("Devolução da venda nº " + v.id());
        cabecalho.setText("Venda de " + Formatos.dataHora(v.dataHora()) + " · operador " + v.operador() + " · total "
                + Formatos.moeda(v.valorTotal()) + " · " + (v.clienteId() == null ? "sem cliente"
                : "cliente " + v.clienteNome() + (Textos.vazio(v.clienteDocumento()) ? ""
                : " (" + v.clienteDocumento() + ")")) + " · devolução sem liberação até "
                + Formatos.data(v.prazoAte()) + (v.devolucoes().isEmpty() ? ""
                : " · " + v.devolucoes().size() + " devolução(ões) anterior(es)"));
        List<String> avisos = new ArrayList<>();
        if (v.foraDoPrazo()) {
            avisos.add("Fora do prazo de devolução: é preciso a liberação de um supervisor.");
        }
        if (!v.aceitaValeTroca()) {
            avisos.add("Cliente anonimizado (LGPD): a devolução não pode virar vale-troca.");
        }
        if (!v.temDisponivel()) {
            avisos.add("Todos os itens desta venda já foram devolvidos.");
        }
        aviso.setText(String.join(" ", avisos));
        aviso.setVisible(!avisos.isEmpty());
        aviso.setManaged(!avisos.isEmpty());

        List<FormaReembolso> formas = new ArrayList<>(FormaReembolso.escolhiveis());
        if (!v.aceitaValeTroca()) {
            formas.remove(FormaReembolso.CREDITO_CLIENTE);
        }
        campoForma.getItems().setAll(formas);
        campoForma.setValue(FormaReembolso.DINHEIRO);
        montarGrade();
        botaoRegistrar.setDisable(!v.temDisponivel());
        atualizarTotal();
        Platform.runLater(() -> linhas.stream().filter(l -> l.quantidade() != null).findFirst()
                .ifPresent(l -> l.quantidade().requestFocus()));
    }

    private void montarGrade() {
        gradeItens.getChildren().clear();
        linhas.clear();
        String[] titulos = {"Item", "Produto", "Vendida", "Já devolvida", "Devolver", "Avariado", "Valor"};
        for (int c = 0; c < titulos.length; c++) {
            Label rotulo = new Label(titulos[c]);
            rotulo.getStyleClass().add("cabecalho-grade");
            gradeItens.add(rotulo, c, 0);
            if (c >= 2 && c != 5) {
                GridPane.setHalignment(rotulo, HPos.RIGHT);
            }
        }
        int linha = 1;
        for (ItemDevolucao item : venda.itens()) {
            gradeItens.add(new Label(String.format("%03d", item.sequencia())), 0, linha);
            Label produto = new Label(item.descricao() + (item.cancelado() ? " (cancelado na venda)" : ""));
            produto.setMaxWidth(320);
            gradeItens.add(produto, 1, linha);
            gradeItens.add(direita(quantidade(item.quantidade(), item)), 2, linha);
            gradeItens.add(direita(quantidade(item.quantidadeDevolvida(), item)), 3, linha);
            Label valor = direita("");
            valor.setMinWidth(90);
            if (item.disponivel().signum() > 0) {
                TextField quantidade = new TextField();
                quantidade.setPrefWidth(90);
                quantidade.getStyleClass().add("campo-numero");
                quantidade.setPromptText("até " + Formatos.quantidade(item.disponivel(), item.fracionada()));
                quantidade.textProperty().addListener((o, antes, depois) -> atualizarTotal());
                CheckBox avariado = new CheckBox();
                gradeItens.add(quantidade, 4, linha);
                gradeItens.add(avariado, 5, linha);
                linhas.add(new Linha(item, quantidade, avariado, valor));
            } else {
                gradeItens.add(direita("—"), 4, linha);
                linhas.add(new Linha(item, null, null, valor));
            }
            gradeItens.add(valor, 6, linha);
            linha++;
        }
    }

    /** Valor de cada linha e o total, com a quantidade que já dá para ler; quantidade inválida mostra "—". */
    private void atualizarTotal() {
        BigDecimal soma = Dinheiro.ZERO;
        for (Linha l : linhas) {
            if (l.quantidade() == null) {
                continue;
            }
            Optional<BigDecimal> q = quantidadeDigitada(l);
            if (q.isEmpty()) {
                l.valor().setText(Textos.vazio(l.quantidade().getText()) ? "" : "—");
                continue;
            }
            BigDecimal valor = l.item().valor(q.get());
            l.valor().setText(Formatos.moeda(valor));
            soma = soma.add(valor);
        }
        total.setText(Formatos.moeda(soma));
        BigDecimal abatido = abatido(soma);
        boolean temAbatimento = venda != null && venda.crediarioEmAberto().signum() > 0;
        abatimento.setVisible(temAbatimento);
        abatimento.setManaged(temAbatimento);
        if (temAbatimento) {
            abatimento.setText(textoAbatimento(soma, abatido));
        }
        // Abatida por inteiro, a devolução fica com a forma ABATIMENTO: não há o que escolher.
        campoForma.setDisable(abatido.signum() > 0 && abatido.compareTo(soma) == 0);
    }

    private BigDecimal abatido(BigDecimal valor) {
        return venda == null ? Dinheiro.ZERO : valor.min(venda.crediarioEmAberto());
    }

    /** Como o valor se divide entre as parcelas em aberto e a forma escolhida (F14.2). */
    private String textoAbatimento(BigDecimal valor, BigDecimal abatido) {
        if (valor.signum() == 0) {
            return "Esta venda tem " + Formatos.moeda(venda.crediarioEmAberto()) + " em parcelas em aberto: a "
                    + "devolução abate primeiro essas parcelas.";
        }
        BigDecimal restante = valor.subtract(abatido);
        return "Abate " + Formatos.moeda(abatido) + " das parcelas em aberto"
                + (restante.signum() == 0 ? "; nada a reembolsar."
                : "; " + Formatos.moeda(restante) + " na forma de reembolso escolhida.");
    }

    private static Optional<BigDecimal> quantidadeDigitada(Linha l) {
        String texto = l.quantidade().getText();
        if (Textos.vazio(texto)) {
            return Optional.empty();
        }
        try {
            BigDecimal q = Dinheiro.ler(texto.strip());
            return q.signum() > 0 && q.compareTo(l.item().disponivel()) <= 0 ? Optional.of(q) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private void atualizarCliente() {
        boolean valeTroca = campoForma.getValue() == FormaReembolso.CREDITO_CLIENTE;
        boolean precisaIdentificar = valeTroca && venda != null && venda.clienteId() == null;
        botaoCliente.setVisible(precisaIdentificar);
        botaoCliente.setManaged(precisaIdentificar);
        if (venda == null) {
            return;
        }
        if (venda.clienteId() != null) {
            cliente.setText(venda.clienteNome() + (Textos.vazio(venda.clienteDocumento()) ? ""
                    : " (" + venda.clienteDocumento() + ")") + (valeTroca ? " — recebe o vale-troca" : ""));
        } else if (clienteValeTroca != null) {
            cliente.setText(clienteValeTroca.descricao() + (valeTroca ? " — recebe o vale-troca" : ""));
        } else {
            cliente.setText(valeTroca ? "Identifique quem recebe o vale-troca." : "Sem cliente");
        }
    }

    /** Vale-troca de venda sem cliente: identifica ou cadastra quem recebe o crédito (mesmo diálogo do F11). */
    @FXML
    private void identificarCliente() {
        DialogosCliente.identificar(clienteValeTroca, texto -> clienteService.pesquisarParaVenda(sessao, texto),
                (nome, documento) -> clienteService.cadastrarRapido(sessao, nome, documento)).ifPresent(escolha -> {
                    clienteValeTroca = escolha.cliente();
                    atualizarCliente();
                });
    }

    @FXML
    private void registrar() {
        if (botaoRegistrar.isDisabled()) {
            return;
        }
        Mensagem.limpar(mensagem);
        try {
            DadosDevolucao dados = lerDados();
            BigDecimal valor = dados.itens().stream()
                    .map(i -> linhas.stream().filter(l -> l.item().vendaItemId() == i.vendaItemId()).findFirst()
                            .orElseThrow().item().valor(i.quantidade()))
                    .reduce(Dinheiro.ZERO, BigDecimal::add);
            Optional<Liberacao> liberacao = pedirLiberacao();
            if (liberacao == null) {
                return;
            }
            BigDecimal abatido = abatido(valor);
            String reembolso = abatido.signum() == 0 ? " em " + dados.forma().rotulo().toLowerCase()
                    : abatido.compareTo(valor) == 0 ? ", abatidos das parcelas em aberto"
                    : " (" + Formatos.moeda(abatido) + " abatidos das parcelas em aberto e "
                    + Formatos.moeda(valor.subtract(abatido)) + " em " + dados.forma().rotulo().toLowerCase() + ")";
            if (!Dialogos.confirmar(raiz.getScene().getWindow(), "Devolver " + dados.itens().size() + " item(ns) da "
                    + "venda nº " + venda.id() + ", " + Formatos.moeda(valor) + reembolso
                    + "? A devolução não pode ser desfeita.")) {
                return;
            }
            resultado = devolucaoService.registrar(sessao, dados, liberacao.orElse(null));
            fechar();
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    /**
     * Liberação que a devolução precisa: de um supervisor fora do prazo; sem a permissão DEVOLUCAO, de quem a tem.
     *
     * @return vazio se não precisa; nulo se a pessoa desistiu
     */
    private Optional<Liberacao> pedirLiberacao() {
        if (venda.foraDoPrazo() && !sessao.supervisor()) {
            Optional<Liberacao> l = LiberacaoSupervisorDialogo.solicitar("A devolução fora do prazo (venda nº "
                    + venda.id() + ")", (login, senha) -> acessoService.liberarSupervisor(login, senha,
                    Permissao.DEVOLUCAO));
            return l.isEmpty() ? null : l;
        }
        if (!sessao.pode(Permissao.DEVOLUCAO)) {
            Optional<Liberacao> l = LiberacaoSupervisorDialogo.solicitar("Devolver itens da venda nº " + venda.id(),
                    (login, senha) -> acessoService.liberar(login, senha, Permissao.DEVOLUCAO));
            return l.isEmpty() ? null : l;
        }
        return Optional.empty();
    }

    /** Itens com quantidade, motivo, forma, cliente do vale-troca e observação; número inválido vira mensagem. */
    DadosDevolucao lerDados() {
        List<DadosDevolucao.Item> itens = new ArrayList<>();
        for (Linha l : linhas) {
            if (l.quantidade() == null || Textos.vazio(l.quantidade().getText())) {
                continue;
            }
            BigDecimal q = CampoNumero.ler(l.quantidade().getText(), "Quantidade de \"" + l.item().descricao() + "\"");
            itens.add(new DadosDevolucao.Item(l.item().vendaItemId(), q, l.avariado().isSelected()));
        }
        Long clienteId = venda.clienteId() == null && clienteValeTroca != null
                && campoForma.getValue() == FormaReembolso.CREDITO_CLIENTE ? clienteValeTroca.id() : null;
        String motivo = campoMotivo.getEditor().getText();
        return new DadosDevolucao(venda.id(), itens, motivo, campoForma.getValue(), clienteId,
                campoObservacao.getText());
    }

    @FXML
    private void fechar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }

    private static Label direita(String texto) {
        Label rotulo = new Label(texto);
        rotulo.setMaxWidth(Double.MAX_VALUE);
        rotulo.getStyleClass().add("coluna-numero");
        GridPane.setHalignment(rotulo, HPos.RIGHT);
        return rotulo;
    }

    private static String quantidade(BigDecimal valor, ItemDevolucao item) {
        return Formatos.quantidade(valor, item.fracionada()) + " " + item.unidade();
    }
}
