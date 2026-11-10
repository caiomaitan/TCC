package br.com.varesync.ui;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.model.TamanhoFonte;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Fonte de 115% e 130% (F21.2): a folha extra multiplica os tamanhos em px da folha do sistema. */
class FolhaFonteTest {

    private static final String CSS = """
            /* comentário com { chaves } e -fx-font-size: 99px; */
            .root {
                -vs-texto: #1f2933;
                -fx-font-size: 13.5px;
            }
            .titulo { -fx-font-size: 20px; -fx-font-weight: bold; }
            .pdv-painel .pdv-total,
            .troco { -fx-font-size: 30px; -fx-text-fill: -vs-texto; }
            .total-pdv { -fx-font-size: 52px; }
            .cartao { -fx-min-width: 150; -fx-padding: 10 12 10 12; }
            .sem-tamanho { -fx-text-fill: -vs-texto; }
            """;

    @Test
    void com130MultiplicaFonteELarguraMinimaComOsMesmosSeletores() {
        String folha = FolhaFonte.gerar(CSS, new BigDecimal("1.30"));

        assertThat(folha).contains(".root {\n    -fx-font-size: 17.55px;\n}")
                .contains(".titulo {\n    -fx-font-size: 26px;\n}")
                .contains(".pdv-painel .pdv-total,\n.troco {\n    -fx-font-size: 39px;\n}")
                .contains(".cartao {\n    -fx-min-width: 195px;\n}")
                .doesNotContain("99px", "sem-tamanho", "total-pdv", "67.6", "-fx-padding", "-fx-text-fill", "-fx-font-weight");
    }

    @Test
    void com115ArredondaEmDuasCasas() {
        assertThat(FolhaFonte.gerar(CSS, new BigDecimal("1.15")))
                .contains("-fx-font-size: 15.53px;") // 13,5 × 1,15 = 15,525
                .contains("-fx-font-size: 23px;");    // 20 × 1,15
    }

    @Test
    void com100NaoGeraNada() {
        assertThat(FolhaFonte.gerar(CSS, BigDecimal.ONE)).isEmpty();
        assertThat(FolhaFonte.gerar(CSS, TamanhoFonte.NORMAL.escala())).isEmpty();
    }

    @Test
    void fonteDaRaiz() {
        assertThat(FolhaFonte.fonteDaRaiz(CSS)).isEqualByComparingTo("13.5");
        assertThat(FolhaFonte.fonteDaRaiz(".x { -fx-font-size: 20px; }")).isEqualByComparingTo("13.5");
    }

    /** Na folha do sistema, todo tamanho de fonte é em px (os em seriam multiplicados duas vezes). */
    @Test
    void folhaDoSistemaTemTodosOsTamanhosEmPxEUmaRegraPorTamanho() throws Exception {
        String css;
        try (InputStream entrada = FolhaFonteTest.class.getResourceAsStream(Dialogos.CSS)) {
            css = new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher tamanho = Pattern.compile("-fx-(font-size|min-width|pref-width|max-width|min-height|pref-height"
                + "|max-height)\\s*:\\s*([^;]+);").matcher(css.replaceAll("(?s)/\\*.*?\\*/", ""));
        int tamanhos = 0;
        while (tamanho.find()) {
            assertThat(tamanho.group(2).trim()).as(tamanho.group()).matches("[0-9]+(\\.[0-9]+)?px");
            tamanhos++;
        }
        String folha = FolhaFonte.gerar(css, TamanhoFonte.MAIOR.escala());
        long jaGrandes = Pattern.compile("-fx-font-size\\s*:\\s*(4\\d|[5-9]\\d|\\d{3,})(\\.\\d+)?px").matcher(css).results().count();
        assertThat(jaGrandes).as("total do PDV e troco").isEqualTo(2);
        assertThat(Pattern.compile("px;").matcher(folha).results().count()).isEqualTo(tamanhos - jaGrandes);
        assertThat(FolhaFonte.fonteDaRaiz(css)).isEqualByComparingTo("13.5");
        assertThat(folha).contains(".root {\n    -fx-font-size: 17.55px;\n}");
    }

    @Test
    void escalasDosTamanhos() {
        assertThat(TamanhoFonte.NORMAL.escala()).isEqualByComparingTo("1");
        assertThat(TamanhoFonte.GRANDE.escala()).isEqualByComparingTo("1.15");
        assertThat(TamanhoFonte.MAIOR.escala()).isEqualByComparingTo("1.30");
        assertThat(TamanhoFonte.dePercentual(130)).isEqualTo(TamanhoFonte.MAIOR);
    }
}
