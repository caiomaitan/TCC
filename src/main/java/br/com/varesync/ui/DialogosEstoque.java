package br.com.varesync.ui;

import br.com.varesync.model.Lote;
import br.com.varesync.model.MotivoPerda;
import br.com.varesync.model.ParteLote;
import br.com.varesync.model.ResultadoAjuste;
import br.com.varesync.model.TipoAjuste;
import br.com.varesync.service.NegocioException;
import br.com.varesync.util.Formatos;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;

/**
 * Diálogos de ajuste manual de estoque (6.4): Entrada/Saída/Perda, "Ajustar saldo para…" e, para produto com controle
 * de validade (F17.3), a escolha do lote no ajuste e a transferência entre lotes.
 * A gravação é feita pela ação recebida; {@code NegocioException} mantém o diálogo aberto com a mensagem.
 */
public final class DialogosEstoque {

    static final List<String> SUGESTOES_SALDO = List.of("Acerto de contagem", "Conferência de estoque");
    static final List<String> SUGESTOES_TRANSFERENCIA = List.of("Lote digitado errado",
            "Contagem do " + Lote.SEM_LOTE, "Separação por validade");

    /** Grava o ajuste com os textos digitados e o lote escolhido (nulo = regra do tipo). */
    @FunctionalInterface
    public interface Ajustar {
        ResultadoAjuste executar(TipoAjuste tipo, String quantidade, String motivo, String observacao,
                                 ParteLote lote);
    }

    /** Grava o novo saldo com os textos digitados. */
    @FunctionalInterface
    public interface AjustarSaldo {
        ResultadoAjuste executar(String novoSaldo, String motivo);
    }

    /** Grava a transferência: destino (lote existente ou novo), quantidade digitada e motivo. */
    @FunctionalInterface
    public interface Transferir {
        void executar(ParteLote destino, String quantidade, String motivo);
    }

    /**
     * Opção da lista de lotes: automático (FEFO), "SEM LOTE", um lote existente ou um lote novo.
     *
     * @param lote nulo nas opções que não são um lote existente
     */
    record OpcaoLote(String rotulo, Lote lote, boolean novo) {

        static final OpcaoLote AUTOMATICO = new OpcaoLote("Automático (FEFO)", null, false);
        static final OpcaoLote SEM_LOTE = new OpcaoLote(Lote.SEM_LOTE, null, false);
        static final OpcaoLote NOVO = new OpcaoLote("Lote novo…", null, true);

        @Override
        public String toString() {
            return rotulo;
        }
    }

    private DialogosEstoque() {
    }

    /**
     * Ajuste sem controle de validade.
     *
     * @param saldoAtual "10 UN", só para orientar
     * @return o ajuste gravado, ou vazio se cancelado
     */
    public static Optional<ResultadoAjuste> ajustar(String produto, String saldoAtual, Ajustar acao) {
        return ajustar(produto, saldoAtual, null, false, acao);
    }

    /**
     * @param saldoAtual  "10 UN", só para orientar
     * @param lotes       lotes do produto com controle de validade (F17.3); nulo = produto sem controle (sem a escolha)
     * @param fracionada  unidade fracionada, para mostrar o saldo dos lotes
     * @return o ajuste gravado, ou vazio se cancelado
     */
    public static Optional<ResultadoAjuste> ajustar(String produto, String saldoAtual, List<Lote> lotes,
                                                    boolean fracionada, Ajustar acao) {
        DialogoFormulario formulario = new DialogoFormulario("Ajustar estoque — " + produto,
                "Saldo atual: " + saldoAtual + ". Digite a quantidade sem sinal: o tipo define se entra ou sai."
                        + (lotes == null ? "" : " Na saída e na perda, \"Automático\" tira dos lotes que vencem "
                        + "primeiro; na perda por vencimento, escolha o lote que venceu."),
                "Registrar");
        ComboBox<TipoAjuste> tipo = new ComboBox<>();
        tipo.getItems().setAll(TipoAjuste.values());
        TextField quantidade = new TextField();
        quantidade.getStyleClass().add("campo-numero");
        ComboBox<String> motivo = new ComboBox<>();
        motivo.setMaxWidth(Double.MAX_VALUE);
        TextField observacao = new TextField();
        observacao.setPromptText("Opcional; obrigatória na perda \"Outros\"");
        ComboBox<OpcaoLote> lote = new ComboBox<>();
        lote.setMaxWidth(Double.MAX_VALUE);
        TextField codigoNovo = new TextField();
        codigoNovo.setPromptText("código (opcional)");
        codigoNovo.setPrefWidth(120);
        DatePicker validadeNova = new DatePicker();
        validadeNova.setConverter(new ConversorData());
        validadeNova.setPromptText("validade");
        validadeNova.setPrefWidth(130);
        HBox novo = new HBox(8, codigoNovo, validadeNova);
        novo.disableProperty().bind(lote.valueProperty().isNotEqualTo(OpcaoLote.NOVO));
        tipo.valueProperty().addListener((obs, anterior, t) -> {
            trocarSugestoes(motivo, t);
            if (lotes != null) {
                trocarLotes(lote, lotes, t, fracionada);
            }
        });
        tipo.setValue(TipoAjuste.ENTRADA);

        formulario.adicionarCampo("Tipo *", tipo);
        formulario.adicionarCampo("Quantidade *", quantidade);
        formulario.adicionarCampo("Motivo *", motivo);
        formulario.adicionarCampo("Observação", observacao);
        if (lotes != null) {
            formulario.adicionarCampo("Lote", lote);
            formulario.adicionarCampo("Lote novo", novo);
        }
        return formulario.mostrar(() -> acao.executar(tipo.getValue(), quantidade.getText(), texto(motivo),
                observacao.getText(), lotes == null ? null : escolha(lote.getValue(), codigoNovo, validadeNova,
                        tipo.getValue(), texto(motivo))), null);
    }

    /**
     * Opções do lote pelo tipo: na entrada, o "SEM LOTE", os lotes existentes e um lote novo; na saída e na perda,
     * "Automático (FEFO)" e os lotes com saldo.
     */
    static void trocarLotes(ComboBox<OpcaoLote> combo, List<Lote> lotes, TipoAjuste tipo, boolean fracionada) {
        List<OpcaoLote> opcoes = new ArrayList<>();
        if (tipo == TipoAjuste.ENTRADA) {
            opcoes.add(OpcaoLote.SEM_LOTE);
            lotes.stream().filter(l -> !l.semLote())
                    .forEach(l -> opcoes.add(new OpcaoLote(rotulo(l, fracionada), l, false)));
            opcoes.add(OpcaoLote.NOVO);
        } else {
            opcoes.add(OpcaoLote.AUTOMATICO);
            lotes.stream().filter(l -> l.saldo().signum() > 0)
                    .forEach(l -> opcoes.add(new OpcaoLote(rotulo(l, fracionada), l, false)));
        }
        combo.getItems().setAll(opcoes);
        combo.setValue(opcoes.get(0));
    }

    /** "L12 (validade 10/12/2026) — saldo 8". */
    static String rotulo(Lote lote, boolean fracionada) {
        return lote.descricao() + " — saldo " + Formatos.quantidade(lote.saldo(), fracionada);
    }

    /** Lote escolhido como o service espera: nulo = regra do tipo (FEFO na saída, "SEM LOTE" na entrada). */
    private static ParteLote escolha(OpcaoLote opcao, TextField codigo, DatePicker validade, TipoAjuste tipo,
                                     String motivo) {
        if (opcao == null || opcao == OpcaoLote.AUTOMATICO || opcao == OpcaoLote.SEM_LOTE) {
            if (tipo == TipoAjuste.PERDA && MotivoPerda.VENCIMENTO.rotulo().equals(motivo)) {
                throw new NegocioException("Na perda por vencimento, escolha o lote que venceu.");
            }
            return null;
        }
        if (opcao.novo()) {
            if (validade.getValue() == null) {
                throw new NegocioException("Informe a validade do lote novo.");
            }
            return ParteLote.novo(codigo.getText(), validade.getValue(), null);
        }
        return ParteLote.existente(opcao.lote().id(), null);
    }

    /**
     * Transferência entre lotes do mesmo produto (F17.3): da origem para um lote existente ou um lote novo.
     *
     * @param origem lote de onde sai a quantidade
     * @param lotes  todos os lotes do produto (o destino é escolhido entre eles, menos a origem)
     * @return verdadeiro se gravou
     */
    public static boolean transferir(String produto, Lote origem, List<Lote> lotes, boolean fracionada,
                                     Transferir acao) {
        DialogoFormulario formulario = new DialogoFormulario("Transferir entre lotes — " + produto,
                "Origem: " + rotulo(origem, fracionada) + ". A transferência não muda o estoque do produto: só "
                        + "passa a quantidade de um lote para outro (para corrigir um lote digitado errado ou passar "
                        + "o " + Lote.SEM_LOTE + " para os lotes certos).", "Transferir");
        ComboBox<OpcaoLote> destino = new ComboBox<>();
        destino.setMaxWidth(Double.MAX_VALUE);
        List<OpcaoLote> opcoes = new ArrayList<>();
        lotes.stream().filter(l -> l.id() != origem.id())
                .forEach(l -> opcoes.add(new OpcaoLote(rotulo(l, fracionada), l, false)));
        if (!origem.semLote() && lotes.stream().noneMatch(Lote::semLote)) {
            opcoes.add(0, OpcaoLote.SEM_LOTE);
        }
        opcoes.add(OpcaoLote.NOVO);
        destino.getItems().setAll(opcoes);
        destino.setValue(opcoes.get(0));
        TextField codigoNovo = new TextField();
        codigoNovo.setPromptText("código (opcional)");
        codigoNovo.setPrefWidth(120);
        DatePicker validadeNova = new DatePicker();
        validadeNova.setConverter(new ConversorData());
        validadeNova.setPromptText("validade");
        validadeNova.setPrefWidth(130);
        HBox novo = new HBox(8, codigoNovo, validadeNova);
        novo.disableProperty().bind(destino.valueProperty().isNotEqualTo(OpcaoLote.NOVO));
        TextField quantidade = new TextField();
        quantidade.getStyleClass().add("campo-numero");
        ComboBox<String> motivo = new ComboBox<>();
        motivo.setMaxWidth(Double.MAX_VALUE);
        motivo.setEditable(true);
        motivo.getItems().setAll(SUGESTOES_TRANSFERENCIA);
        motivo.setPromptText("Escolha ou digite");

        formulario.adicionarCampo("Destino *", destino);
        formulario.adicionarCampo("Lote novo", novo);
        formulario.adicionarCampo("Quantidade *", quantidade);
        formulario.adicionarCampo("Motivo *", motivo);
        return formulario.mostrar(() -> {
            OpcaoLote escolhido = destino.getValue();
            ParteLote alvo;
            if (escolhido == null) {
                throw new NegocioException("Escolha o lote de destino.");
            } else if (escolhido == OpcaoLote.SEM_LOTE) {
                alvo = ParteLote.semLote(null);
            } else if (escolhido.novo()) {
                if (validadeNova.getValue() == null) {
                    throw new NegocioException("Informe a validade do lote novo.");
                }
                alvo = ParteLote.novo(codigoNovo.getText(), validadeNova.getValue(), null);
            } else {
                alvo = ParteLote.existente(escolhido.lote().id(), null);
            }
            acao.executar(alvo, quantidade.getText(), texto(motivo));
            return Boolean.TRUE;
        }, null).isPresent();
    }

    /**
     * @param saldoAtual "10 UN", só para orientar
     * @param previa     texto que explica o movimento que será gerado para o saldo digitado
     * @return o ajuste gravado, ou vazio se cancelado
     */
    public static Optional<ResultadoAjuste> ajustarSaldoPara(String produto, String saldoAtual,
                                                             Function<String, String> previa, AjustarSaldo acao) {
        DialogoFormulario formulario = new DialogoFormulario("Ajustar saldo — " + produto,
                "Saldo atual: " + saldoAtual + ". Informe o saldo correto: o sistema calcula a diferença e registra "
                        + "uma entrada ou uma saída de ajuste.", "Ajustar");
        TextField novoSaldo = new TextField();
        novoSaldo.getStyleClass().add("campo-numero");
        Label movimento = new Label();
        movimento.setWrapText(true);
        movimento.getStyleClass().add("subtitulo");
        novoSaldo.textProperty().addListener((obs, anterior, texto) -> movimento.setText(previa.apply(texto)));
        ComboBox<String> motivo = new ComboBox<>();
        motivo.setMaxWidth(Double.MAX_VALUE);
        motivo.setEditable(true);
        motivo.getItems().setAll(SUGESTOES_SALDO);
        motivo.getEditor().setText(SUGESTOES_SALDO.get(0));

        formulario.adicionarCampo("Novo saldo *", novoSaldo);
        formulario.adicionarCampo("", movimento);
        formulario.adicionarCampo("Motivo *", motivo);
        return formulario.mostrar(() -> acao.executar(novoSaldo.getText(), texto(motivo)), null);
    }

    /** Na perda o motivo vem da lista; nos demais tipos a lista só sugere e o texto é livre. */
    private static void trocarSugestoes(ComboBox<String> motivo, TipoAjuste tipo) {
        motivo.setValue(null);
        motivo.getEditor().clear();
        motivo.setEditable(tipo != null && !tipo.motivoDaLista());
        motivo.getItems().setAll(tipo == null ? List.of() : tipo.sugestoes());
        motivo.setPromptText(tipo != null && tipo.motivoDaLista() ? "Escolha o motivo" : "Escolha ou digite");
    }

    private static String texto(ComboBox<String> motivo) {
        if (motivo.isEditable()) {
            return motivo.getEditor().getText();
        }
        return motivo.getValue() == null ? "" : motivo.getValue();
    }
}
