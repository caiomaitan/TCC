package br.com.varesync.ui;

import br.com.varesync.model.ClienteVenda;
import br.com.varesync.service.NegocioException;
import br.com.varesync.util.CpfCnpj;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;

/**
 * Diálogos de cliente (F12): identificação no PDV (F11), com busca, cadastro rápido e remoção, e a confirmação da
 * anonimização (LGPD) pelo nome digitado. Enter confirma, Esc cancela.
 */
public final class DialogosCliente {

    /** O que a pessoa escolheu no F11: o cliente da venda, ou nulo para tirar a identificação. */
    public record Escolha(ClienteVenda cliente) {
    }

    private DialogosCliente() {
    }

    /**
     * Identificação do cliente no PDV (F11): CPF/CNPJ completo + Enter identifica direto; nome (ou parte do
     * documento ou do telefone) + Enter lista os clientes; ↓ vai para a lista, Enter ou duplo clique escolhe.
     *
     * @param atual         cliente já identificado, ou nulo (sem ele, não aparece "Remover cliente")
     * @param pesquisa      texto → clientes ativos; {@code NegocioException} aparece em vermelho
     * @param cadastroRapido (nome, CPF/CNPJ) → cliente cadastrado, com a permissão PDV
     * @return a escolha, ou vazio se cancelado
     */
    public static Optional<Escolha> identificar(ClienteVenda atual, Function<String, List<ClienteVenda>> pesquisa,
                                                BiFunction<String, String, ClienteVenda> cadastroRapido) {
        Dialog<Escolha> dialogo = new Dialog<>();
        dialogo.setTitle(Dialogos.TITULO);
        dialogo.setHeaderText("Identificar cliente (F11)");
        ButtonType identificar = new ButtonType("Identificar", ButtonBar.ButtonData.OK_DONE);
        ButtonType cadastrar = new ButtonType("Cadastro rápido…", ButtonBar.ButtonData.LEFT);
        ButtonType remover = new ButtonType("Remover cliente", ButtonBar.ButtonData.OTHER);
        dialogo.getDialogPane().getButtonTypes().addAll(identificar, cadastrar);
        if (atual != null) {
            dialogo.getDialogPane().getButtonTypes().add(remover);
        }
        dialogo.getDialogPane().getButtonTypes().add(new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE));

        Label situacao = new Label(atual == null ? "Nenhum cliente identificado nesta venda."
                : "Cliente desta venda: " + atual.descricao() + " · crédito " + Formatos.moeda(atual.saldoCredito()));
        TextField termo = new TextField();
        termo.setPromptText("CPF/CNPJ (Enter identifica) ou nome do cliente");
        Label mensagem = new Label();
        mensagem.setWrapText(true);
        mensagem.setMaxWidth(560);
        mensagem.getStyleClass().add("mensagem-erro");
        TableView<ClienteVenda> tabela = new TableView<>();
        tabela.setPlaceholder(new Label("Digite o CPF/CNPJ ou o nome e tecle Enter."));
        tabela.setPrefSize(680, 280);
        tabela.getColumns().add(coluna("Código", 70, c -> String.valueOf(c.id()), true));
        tabela.getColumns().add(coluna("Nome", 320, ClienteVenda::nome, false));
        tabela.getColumns().add(coluna("CPF/CNPJ", 160, ClienteVenda::documentoOculto, false));
        tabela.getColumns().add(coluna("Crédito", 100, c -> Formatos.moeda(c.saldoCredito()), true));
        tabela.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        Runnable pesquisar = () -> {
            try {
                List<ClienteVenda> encontrados = pesquisa.apply(termo.getText());
                tabela.getItems().setAll(encontrados);
                if (documentoCompleto(termo.getText()) && encontrados.size() == 1) {
                    dialogo.setResult(new Escolha(encontrados.get(0)));
                    dialogo.close();
                    return;
                }
                mensagem.setText(!encontrados.isEmpty() ? "" : documentoCompleto(termo.getText())
                        ? "Nenhum cliente ativo com o " + rotuloDocumento(termo.getText()) + " "
                        + CpfCnpj.formatar(CpfCnpj.normalizar(termo.getText()))
                        + ". Use \"Cadastro rápido…\" (o documento já vai preenchido)."
                        : "Nenhum cliente encontrado.");
                if (!encontrados.isEmpty()) {
                    tabela.getSelectionModel().selectFirst();
                    tabela.requestFocus();
                }
            } catch (NegocioException erro) {
                mensagem.setText(erro.getMessage());
            }
        };
        termo.setOnAction(e -> {
            e.consume(); // Enter no campo pesquisa; não aciona o botão de identificar
            pesquisar.run();
        });
        termo.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.DOWN && !tabela.getItems().isEmpty()) {
                e.consume();
                tabela.requestFocus();
            }
        });
        tabela.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2
                    && tabela.getSelectionModel().getSelectedItem() != null) {
                dialogo.setResult(new Escolha(tabela.getSelectionModel().getSelectedItem()));
                dialogo.close();
            }
        });
        Button botaoIdentificar = (Button) dialogo.getDialogPane().lookupButton(identificar);
        botaoIdentificar.disableProperty().bind(tabela.getSelectionModel().selectedItemProperty().isNull());
        Button botaoCadastrar = (Button) dialogo.getDialogPane().lookupButton(cadastrar);
        botaoCadastrar.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume(); // o diálogo continua aberto se o cadastro rápido for cancelado
            String texto = termo.getText() == null ? "" : termo.getText().strip();
            boolean documento = !CpfCnpj.normalizar(texto).isEmpty()
                    && CpfCnpj.normalizar(texto).chars().anyMatch(Character::isDigit);
            cadastroRapido(documento ? "" : texto, documento ? texto : "", cadastroRapido).ifPresent(novo -> {
                dialogo.setResult(new Escolha(novo));
                dialogo.close();
            });
        });
        dialogo.setResultConverter(tipo -> tipo == identificar
                ? new Escolha(tabela.getSelectionModel().getSelectedItem())
                : tipo == remover ? new Escolha(null) : null);

        dialogo.getDialogPane().setContent(new VBox(10, situacao, termo, tabela, mensagem));
        Dialogos.estilizar(dialogo.getDialogPane());
        Platform.runLater(termo::requestFocus);
        return dialogo.showAndWait();
    }

    /** Cadastro rápido (nome e CPF/CNPJ) sem sair do PDV. @return o cliente cadastrado, ou vazio se cancelado */
    static Optional<ClienteVenda> cadastroRapido(String nome, String documento,
                                                 BiFunction<String, String, ClienteVenda> cadastrar) {
        DialogoFormulario formulario = new DialogoFormulario("Cadastro rápido de cliente",
                "Nome e CPF/CNPJ; os demais dados podem ser completados depois, em Clientes.", "Cadastrar e identificar");
        TextField campoNome = new TextField(nome);
        TextField campoDocumento = new TextField(documento);
        campoDocumento.setPromptText("CPF ou CNPJ (opcional)");
        formulario.adicionarCampo("Nome *", campoNome);
        formulario.adicionarCampo("CPF/CNPJ", campoDocumento);
        return formulario.mostrar(() -> cadastrar.apply(campoNome.getText(), campoDocumento.getText()), null);
    }

    /**
     * Confirmação da anonimização (F12.6): a pessoa digita o nome do cliente; o service confere.
     *
     * @return true se anonimizou
     */
    public static boolean confirmarAnonimizacao(long id, String nome, Consumer<String> anonimizar) {
        DialogoFormulario formulario = new DialogoFormulario("Anonimizar o cliente nº " + id,
                "O nome, o CPF/CNPJ, os contatos, o endereço, a data de nascimento e a observação de \"" + nome
                        + "\" serão apagados, e o cliente ficará inativo. As vendas continuam. Isto não pode ser "
                        + "desfeito.\n\nPara confirmar, digite o nome do cliente.", "Anonimizar");
        TextField campo = new TextField();
        formulario.adicionarCampo("Nome do cliente *", campo);
        return formulario.mostrar(() -> {
            anonimizar.accept(campo.getText());
            return Boolean.TRUE;
        }, null).orElse(false);
    }

    /**
     * Ajuste manual do crédito (F12.3, só o supervisor): valor positivo credita, negativo debita; motivo obrigatório.
     *
     * @param ajustar (valor, motivo) → novo saldo; {@code NegocioException} aparece no diálogo
     * @return o novo saldo, ou vazio se cancelado
     */
    public static Optional<BigDecimal> ajustarCredito(long id, String nome, BigDecimal saldo, boolean anonimizado,
                                                      BiFunction<BigDecimal, String, BigDecimal> ajustar) {
        DialogoFormulario formulario = new DialogoFormulario("Ajustar o crédito do cliente nº " + id,
                nome + " tem saldo de " + Formatos.moeda(saldo) + ". " + (anonimizado
                        ? "O cliente foi anonimizado (LGPD): o ajuste só pode zerar o saldo."
                        : "Valor positivo credita e negativo debita (ex.: -5,00).")
                        + " O ajuste fica no extrato e na auditoria.", "Ajustar");
        TextField valor = new TextField();
        valor.setPromptText("Ex.: 5,00 ou -5,00");
        if (anonimizado) {
            valor.setText(Formatos.numero(saldo.negate(), Dinheiro.ESCALA_DINHEIRO));
        }
        TextField motivo = new TextField();
        formulario.adicionarCampo("Valor (R$) *", valor);
        formulario.adicionarCampo("Motivo *", motivo);
        return formulario.mostrar(() -> ajustar.apply(CampoNumero.ler(valor.getText(), "Valor"), motivo.getText()),
                null);
    }

    private static boolean documentoCompleto(String texto) {
        String documento = CpfCnpj.normalizar(texto);
        return (documento.length() == 11 || documento.length() == 14) && CpfCnpj.validar(documento).isEmpty();
    }

    private static String rotuloDocumento(String texto) {
        return CpfCnpj.normalizar(texto).length() == 11 ? "CPF" : "CNPJ";
    }

    private static TableColumn<ClienteVenda, String> coluna(String titulo, double largura,
                                                           Function<ClienteVenda, String> valor, boolean numero) {
        TableColumn<ClienteVenda, String> coluna = new TableColumn<>(titulo);
        coluna.setPrefWidth(largura);
        coluna.setCellValueFactory(c -> new ReadOnlyStringWrapper(valor.apply(c.getValue())));
        if (numero) {
            coluna.getStyleClass().add("coluna-numero");
        }
        return coluna;
    }
}
