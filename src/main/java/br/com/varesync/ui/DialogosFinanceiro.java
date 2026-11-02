package br.com.varesync.ui;

import br.com.varesync.model.CategoriaDespesa;
import br.com.varesync.model.ContaFinanceira;
import br.com.varesync.model.DadosTituloPagar;
import br.com.varesync.model.Fornecedor;
import br.com.varesync.model.GrupoDespesa;
import br.com.varesync.model.TipoConta;
import br.com.varesync.model.TituloPagar;
import br.com.varesync.service.NegocioException;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

/**
 * Diálogos da tela Financeiro (F15, parte A). A gravação é feita pela ação recebida; {@code NegocioException} mantém o
 * diálogo aberto com a mensagem. Enter confirma, Esc cancela.
 */
public final class DialogosFinanceiro {

    private static final DateTimeFormatter MES = DateTimeFormatter.ofPattern("MM/uuuu");

    /** Dados do cadastro de conta. */
    public record DadosConta(String nome, TipoConta tipo, boolean ativa, boolean padrao) {
    }

    /** Conta, data, valor, juros e desconto do pagamento. */
    public record DadosPagamento(Long contaId, LocalDate data, BigDecimal valor, BigDecimal juros, BigDecimal desconto) {
    }

    /** Conta de origem, de destino, valor e descrição da transferência. */
    public record DadosTransferencia(long origem, long destino, BigDecimal valor, String descricao) {
    }

    /** Valor com sinal e motivo do lançamento manual. */
    public record DadosLancamento(BigDecimal valor, String motivo) {
    }

    /** Data e total conferido da conciliação. */
    public record DadosConciliacao(LocalDate data, BigDecimal total) {
    }

    private DialogosFinanceiro() {
    }

    /** @param atual conta em edição; nula na nova */
    public static boolean conta(ContaFinanceira atual, Consumer<DadosConta> gravar) {
        DialogoFormulario f = new DialogoFormulario(atual == null ? "Nova conta" : "Conta " + atual.nome(), null, "Salvar");
        TextField nome = new TextField(atual == null ? "" : atual.nome());
        ComboBox<TipoConta> tipo = new ComboBox<>();
        tipo.getItems().setAll(TipoConta.values());
        tipo.setValue(atual == null ? TipoConta.BANCO : atual.tipo());
        CheckBox ativa = new CheckBox("Ativa");
        ativa.setSelected(atual == null || atual.ativa());
        ativa.setDisable(atual == null);
        CheckBox padrao = new CheckBox("Padrão do caixa");
        padrao.setSelected(atual != null && atual.padraoCaixa());
        f.adicionarCampo("Nome *", nome);
        f.adicionarCampo("Tipo *", tipo);
        f.adicionarCampo("", ativa);
        f.adicionarCampo("", padrao);
        return f.mostrar(() -> {
            gravar.accept(new DadosConta(nome.getText(), tipo.getValue(), ativa.isSelected(), padrao.isSelected()));
            return Boolean.TRUE;
        }, null).isPresent();
    }

    /** Valor com sinal (saldo inicial, sem motivo; ajuste, com motivo). */
    public static boolean lancamento(String titulo, boolean pedeMotivo, Consumer<DadosLancamento> gravar) {
        DialogoFormulario f = new DialogoFormulario(titulo, "Valor positivo entra na conta; negativo sai.", "Lançar");
        TextField valor = campoValor();
        TextField motivo = new TextField();
        f.adicionarCampo("Valor (R$) *", valor);
        if (pedeMotivo) {
            f.adicionarCampo("Motivo *", motivo);
        }
        return f.mostrar(() -> {
            gravar.accept(new DadosLancamento(CampoNumero.ler(valor.getText(), "Valor"), motivo.getText()));
            return Boolean.TRUE;
        }, null).isPresent();
    }

    public static boolean transferencia(List<ContaFinanceira> contas, Consumer<DadosTransferencia> gravar) {
        DialogoFormulario f = new DialogoFormulario("Transferência entre contas", null, "Transferir");
        ComboBox<ContaFinanceira> origem = comboConta(contas);
        ComboBox<ContaFinanceira> destino = comboConta(contas);
        TextField valor = campoValor();
        TextField descricao = new TextField();
        f.adicionarCampo("De *", origem);
        f.adicionarCampo("Para *", destino);
        f.adicionarCampo("Valor (R$) *", valor);
        f.adicionarCampo("Descrição", descricao);
        return f.mostrar(() -> {
            if (origem.getValue() == null || destino.getValue() == null) {
                throw new NegocioException("Escolha as duas contas.");
            }
            gravar.accept(new DadosTransferencia(origem.getValue().id(), destino.getValue().id(),
                    CampoNumero.ler(valor.getText(), "Valor"), descricao.getText()));
            return Boolean.TRUE;
        }, null).isPresent();
    }

    /** @param quantidade e total: o que está selecionado */
    public static boolean conciliacao(int quantidade, BigDecimal total, LocalDate hoje,
                                      Consumer<DadosConciliacao> gravar) {
        DialogoFormulario f = new DialogoFormulario("Conciliar recebíveis", quantidade + " recebível(is), total líquido "
                + Formatos.moeda(total) + ". Informe o total do extrato do banco para conferir (opcional).", "Conciliar");
        DatePicker data = campoData(hoje);
        TextField conferido = campoValor();
        f.adicionarCampo("Data do crédito *", data);
        f.adicionarCampo("Total no extrato (R$)", conferido);
        return f.mostrar(() -> {
            gravar.accept(new DadosConciliacao(lerData(data, "Data do crédito"),
                    CampoNumero.ler(conferido.getText(), "Total no extrato")));
            return Boolean.TRUE;
        }, null).isPresent();
    }

    /** @return o líquido que o banco pagou, ou vazio se cancelado */
    public static Optional<BigDecimal> liquido(BigDecimal atual, Function<BigDecimal, BigDecimal> conferir) {
        DialogoFormulario f = new DialogoFormulario("Corrigir o valor líquido",
                "O que o banco pagou. A taxa passa a ser bruto − líquido.", "Usar este valor");
        TextField valor = campoValor();
        valor.setText(Formatos.numero(atual, Dinheiro.ESCALA_DINHEIRO));
        f.adicionarCampo("Líquido (R$) *", valor);
        return f.mostrar(() -> conferir.apply(CampoNumero.ler(valor.getText(), "Líquido")), null);
    }

    /** @return true se gravou */
    public static boolean despesa(List<CategoriaDespesa> categorias, List<Fornecedor> fornecedores, LocalDate hoje,
                                  java.util.function.BiConsumer<DadosTituloPagar, Integer> gravar) {
        DialogoFormulario f = new DialogoFormulario("Nova despesa", "A competência é o mês a que a despesa pertence; vazia, "
                + "vale o mês do vencimento.", "Lançar");
        ComboBox<CategoriaDespesa> categoria = new ComboBox<>();
        categoria.getItems().setAll(categorias.stream().filter(CategoriaDespesa::ativa).toList());
        categoria.setMaxWidth(Double.MAX_VALUE);
        TextField descricao = new TextField();
        TextField valor = campoValor();
        DatePicker vencimento = campoData(hoje);
        TextField competencia = new TextField();
        competencia.setPromptText("mm/aaaa");
        ComboBox<Fornecedor> fornecedor = new ComboBox<>();
        fornecedor.getItems().setAll(fornecedores);
        fornecedor.setMaxWidth(Double.MAX_VALUE);
        fornecedor.setPromptText("Opcional");
        TextField documento = new TextField();
        TextField repeticao = new TextField("0");
        repeticao.getStyleClass().add("campo-numero");
        f.adicionarCampo("Categoria *", categoria);
        f.adicionarCampo("Descrição *", descricao);
        f.adicionarCampo("Valor (R$) *", valor);
        f.adicionarCampo("Vencimento *", vencimento);
        f.adicionarCampo("Competência", competencia);
        f.adicionarCampo("Fornecedor", fornecedor);
        f.adicionarCampo("Documento", documento);
        f.adicionarCampo("Repetir por mais (meses)", repeticao);
        return f.mostrar(() -> {
            int meses;
            try {
                meses = Integer.parseInt(repeticao.getText().strip());
            } catch (NumberFormatException e) {
                throw new NegocioException("Repetir por mais: informe um número inteiro de meses.");
            }
            gravar.accept(new DadosTituloPagar(categoria.getValue() == null ? null : categoria.getValue().id(),
                    descricao.getText(), mes(competencia.getText()), lerData(vencimento, "Vencimento"),
                    fornecedor.getValue() == null ? null : fornecedor.getValue().id(), documento.getText(), null,
                    CampoNumero.ler(valor.getText(), "Valor")), meses);
            return Boolean.TRUE;
        }, null).isPresent();
    }

    public static boolean pagamento(TituloPagar titulo, List<ContaFinanceira> contas, LocalDate hoje,
                                    Consumer<DadosPagamento> gravar) {
        DialogoFormulario f = new DialogoFormulario("Pagar: " + titulo.descricao(), "Em aberto: "
                + Formatos.moeda(titulo.emAberto()) + ". A conta sai com valor + juros − desconto.", "Pagar");
        ComboBox<ContaFinanceira> conta = comboConta(contas);
        contas.stream().filter(c -> c.tipo() == TipoConta.BANCO).findFirst().ifPresent(conta::setValue);
        DatePicker data = campoData(hoje);
        TextField valor = campoValor();
        valor.setText(Formatos.numero(titulo.emAberto(), Dinheiro.ESCALA_DINHEIRO));
        TextField juros = campoValor();
        TextField desconto = campoValor();
        f.adicionarCampo("Conta *", conta);
        f.adicionarCampo("Data *", data);
        f.adicionarCampo("Valor (R$) *", valor);
        f.adicionarCampo("Juros (R$)", juros);
        f.adicionarCampo("Desconto (R$)", desconto);
        return f.mostrar(() -> {
            gravar.accept(new DadosPagamento(conta.getValue() == null ? null : conta.getValue().id(),
                    lerData(data, "Data"), CampoNumero.ler(valor.getText(), "Valor"),
                    CampoNumero.ler(juros.getText(), "Juros"), CampoNumero.ler(desconto.getText(), "Desconto")));
            return Boolean.TRUE;
        }, null).isPresent();
    }

    /** Pede o motivo de um estorno ou cancelamento. */
    public static boolean motivo(String titulo, Consumer<String> gravar) {
        DialogoFormulario f = new DialogoFormulario(titulo, null, "Confirmar");
        TextField motivo = new TextField();
        f.adicionarCampo("Motivo *", motivo);
        return f.mostrar(() -> {
            gravar.accept(motivo.getText());
            return Boolean.TRUE;
        }, null).isPresent();
    }

    /** Nova categoria, com a lista das existentes no texto. */
    public static boolean categoria(List<CategoriaDespesa> categorias,
                                    java.util.function.BiConsumer<String, GrupoDespesa> gravar) {
        StringBuilder existentes = new StringBuilder("Existentes: ");
        categorias.forEach(c -> existentes.append(c.nome()).append(" (").append(c.grupo().rotulo()).append(c.ativa() ? ""
                : ", inativa").append("); "));
        DialogoFormulario f = new DialogoFormulario("Nova categoria de despesa", existentes.toString(), "Criar");
        TextField nome = new TextField();
        ComboBox<GrupoDespesa> grupo = new ComboBox<>();
        grupo.getItems().setAll(GrupoDespesa.OPERACIONAL, GrupoDespesa.IMPOSTO, GrupoDespesa.FINANCEIRA,
                GrupoDespesa.OUTRA, GrupoDespesa.MERCADORIA);
        grupo.setValue(GrupoDespesa.OPERACIONAL);
        f.adicionarCampo("Nome *", nome);
        f.adicionarCampo("Grupo *", grupo);
        f.adicionarCampo("", new Label("O grupo decide a linha do DRE."));
        return f.mostrar(() -> {
            gravar.accept(nome.getText(), grupo.getValue());
            return Boolean.TRUE;
        }, null).isPresent();
    }

    /** "mm/aaaa" → 1º dia do mês; vazio → nulo. */
    static LocalDate mes(String texto) {
        if (Textos.vazio(texto)) {
            return null;
        }
        try {
            return YearMonth.parse(texto.strip(), MES).atDay(1);
        } catch (DateTimeParseException e) {
            throw new NegocioException("Competência inválida: use mm/aaaa.");
        }
    }

    private static ComboBox<ContaFinanceira> comboConta(List<ContaFinanceira> contas) {
        ComboBox<ContaFinanceira> conta = new ComboBox<>();
        conta.getItems().setAll(contas);
        conta.setMaxWidth(Double.MAX_VALUE);
        return conta;
    }

    private static TextField campoValor() {
        TextField campo = new TextField();
        campo.getStyleClass().add("campo-numero");
        campo.setPromptText("0,00");
        return campo;
    }

    private static DatePicker campoData(LocalDate valor) {
        DatePicker data = new DatePicker(valor);
        data.setConverter(new ConversorData());
        data.setMaxWidth(Double.MAX_VALUE);
        return data;
    }

    private static LocalDate lerData(DatePicker campo, String rotulo) {
        LocalDate lida = campo.getConverter().fromString(campo.getEditor().getText());
        if (lida == null) {
            throw new NegocioException(rotulo + ": use dd/mm/aaaa.");
        }
        return lida;
    }
}
