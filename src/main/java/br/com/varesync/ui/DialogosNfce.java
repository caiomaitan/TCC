package br.com.varesync.ui;

import br.com.varesync.service.NegocioException;
import br.com.varesync.util.Textos;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;

/** Diálogos da aba Notas fiscais (F23.9): a faixa a inutilizar e o mês do ZIP do contador. */
public final class DialogosNfce {

    private DialogosNfce() {
    }

    /** A faixa de números a inutilizar, com a justificativa (15 a 255 caracteres). */
    public record Faixa(int serie, int inicial, int fim, String justificativa) {
    }

    /**
     * Pede a série, a faixa e a justificativa. Só o formato é conferido aqui; o resto (números sem NFC-e, prazo do dia
     * 10) vem do serviço, depois, com a resposta da SEFAZ.
     */
    public static Optional<Faixa> faixaParaInutilizar(int serieAtual) {
        DialogoFormulario f = new DialogoFormulario("Inutilizar números de NFC-e",
                "Só números sem NFC-e (pulados na numeração). O pedido vai à SEFAZ de homologação e pode ser feito "
                        + "até o dia 10 do mês seguinte ao da numeração.", "Inutilizar");
        TextField serie = new TextField(String.valueOf(serieAtual));
        TextField inicial = new TextField();
        TextField fim = new TextField();
        TextArea justificativa = new TextArea();
        justificativa.setPrefRowCount(3);
        justificativa.setWrapText(true);
        justificativa.setPromptText("De 15 a 255 caracteres");
        f.adicionarCampo("Série *", serie);
        f.adicionarCampo("Número inicial *", inicial);
        f.adicionarCampo("Número final", fim);
        f.adicionarCampo("Justificativa *", justificativa);
        inicial.textProperty().addListener((o, a, n) -> {
            if (fim.getText().isBlank() || fim.getText().equals(a)) {
                fim.setText(n);
            }
        });
        return f.mostrar(() -> {
            int s = inteiro(serie.getText(), "a série");
            int i = inteiro(inicial.getText(), "o número inicial");
            int u = fim.getText().isBlank() ? i : inteiro(fim.getText(), "o número final");
            String texto = Textos.normalizarEspacos(justificativa.getText());
            if (texto.length() < 15 || texto.length() > 255) {
                throw new NegocioException("A justificativa precisa ter de 15 a 255 caracteres (exigência da SEFAZ).");
            }
            if (u < i) {
                throw new NegocioException("O número final não pode ser menor que o inicial.");
            }
            return new Faixa(s, i, u, texto);
        }, null);
    }

    /** O mês do ZIP com os XML, entre os últimos 13 (o atual primeiro). */
    public static Optional<YearMonth> mesParaExportar(YearMonth atual) {
        DialogoFormulario f = new DialogoFormulario("Exportar XML do mês",
                "Um ZIP com as NFC-e autorizadas e canceladas do mês, os eventos e as inutilizações, para o contador.",
                "Escolher onde salvar…");
        ComboBox<YearMonth> mes = new ComboBox<>();
        List<YearMonth> meses = new ArrayList<>();
        for (int i = 0; i <= 12; i++) {
            meses.add(atual.minusMonths(i));
        }
        mes.getItems().setAll(meses);
        mes.setValue(atual);
        mes.setCellFactory(l -> celulaMes());
        mes.setButtonCell(celulaMes());
        f.adicionarCampo("Mês", mes);
        return f.mostrar(mes::getValue, null);
    }

    /** "11/2026". */
    public static String mes(YearMonth m) {
        return String.format("%02d/%d", m.getMonthValue(), m.getYear());
    }

    private static ListCell<YearMonth> celulaMes() {
        return new ListCell<>() {
            @Override
            protected void updateItem(YearMonth m, boolean vazio) {
                super.updateItem(m, vazio);
                setText(vazio || m == null ? null : mes(m));
            }
        };
    }

    private static int inteiro(String texto, String campo) {
        String limpo = texto == null ? "" : texto.strip();
        if (!limpo.matches("\\d{1,9}")) {
            throw new NegocioException("Informe " + campo + " só com números.");
        }
        return Integer.parseInt(limpo);
    }
}
