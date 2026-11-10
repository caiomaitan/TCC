package br.com.varesync.ui;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Folha que aumenta a fonte (F21.1, F21.2): para cada regra da folha do sistema com tamanho em px (fonte, largura e
 * altura mínimas e preferidas), a mesma regra com o tamanho multiplicado pela escala. Entra depois da folha do sistema,
 * com os mesmos seletores (mesma especificidade), e por isso vale no lugar dela. A regra {@code .root} leva junto o
 * tamanho da fonte da raiz, que os controles do modena usam como base.
 */
public final class FolhaFonte {

    /** Propriedades de tamanho que acompanham a fonte. */
    static final Set<String> PROPRIEDADES = Set.of("-fx-font-size", "-fx-min-width", "-fx-pref-width",
            "-fx-max-width", "-fx-min-height", "-fx-pref-height", "-fx-max-height");

    static final BigDecimal FONTE_JA_GRANDE = new BigDecimal("40");
    private static final Pattern COMENTARIO = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern REGRA = Pattern.compile("([^{}]+)\\{([^{}]*)}");
    private static final Pattern DECLARACAO = Pattern.compile("(-fx-[a-z-]+)\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)(px)?\\s*;");

    private FolhaFonte() {
    }

    /**
     * @param css   a folha do sistema
     * @param escala 1,15 para 115%, 1,30 para 130%
     * @return a folha com os tamanhos multiplicados (vazia com escala 1)
     */
    public static String gerar(String css, BigDecimal escala) {
        if (escala.compareTo(BigDecimal.ONE) == 0) {
            return "";
        }
        StringBuilder saida = new StringBuilder("/* VareSync: fonte de ")
                .append(escala.movePointRight(2).stripTrailingZeros().toPlainString()).append("% (F21.1) */\n");
        Matcher regra = REGRA.matcher(COMENTARIO.matcher(css).replaceAll(""));
        while (regra.find()) {
            String seletor = regra.group(1).trim();
            StringBuilder declaracoes = new StringBuilder();
            Matcher declaracao = DECLARACAO.matcher(regra.group(2));
            while (declaracao.find()) {
                String propriedade = declaracao.group(1);
                if (PROPRIEDADES.contains(propriedade) && !jaGrande(propriedade, declaracao.group(2))) {
                    BigDecimal valor = new BigDecimal(declaracao.group(2)).multiply(escala)
                            .setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
                    declaracoes.append("    ").append(propriedade).append(": ").append(valor.toPlainString())
                            .append("px;\n");
                }
            }
            if (!declaracoes.isEmpty()) {
                saida.append(seletor).append(" {\n").append(declaracoes).append("}\n");
            }
        }
        return saida.toString();
    }

    /**
     * Texto que já é grande (40 px ou mais: o total do PDV e o troco) não cresce: ele já passa do tamanho de texto
     * grande e, maior, deixaria de caber no painel do PDV em 1280×720.
     */
    static boolean jaGrande(String propriedade, String valor) {
        return propriedade.equals("-fx-font-size") && new BigDecimal(valor).compareTo(FONTE_JA_GRANDE) >= 0;
    }

    /** Tamanho da fonte da raiz na folha do sistema, em px (13,5 se a regra {@code .root} não o define). */
    public static BigDecimal fonteDaRaiz(String css) {
        Matcher regra = REGRA.matcher(COMENTARIO.matcher(css).replaceAll(""));
        while (regra.find()) {
            if (regra.group(1).trim().toLowerCase(Locale.ROOT).equals(".root")) {
                Matcher declaracao = DECLARACAO.matcher(regra.group(2));
                while (declaracao.find()) {
                    if (declaracao.group(1).equals("-fx-font-size")) {
                        return new BigDecimal(declaracao.group(2));
                    }
                }
            }
        }
        return new BigDecimal("13.5");
    }
}
