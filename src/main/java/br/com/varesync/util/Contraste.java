package br.com.varesync.util;

import java.util.Locale;

/**
 * Razão de contraste entre duas cores, pela fórmula das WCAG 2.x (F21.2): (L1 + 0,05) ÷ (L2 + 0,05), com L a
 * luminância relativa. AA pede 4,5:1 no texto normal e 3:1 no texto grande e nos contornos (foco).
 *
 * <p>Usa {@code double}: é cálculo de cor, não de dinheiro, custo, quantidade ou percentual de negócio.
 */
public final class Contraste {

    public static final double AA_TEXTO = 4.5;
    public static final double AA_TEXTO_GRANDE = 3.0;
    public static final double AA_CONTORNO = 3.0;

    private Contraste() {
    }

    /** @param corA e corB em {@code #rgb}, {@code #rrggbb} ou {@code #rrggbbaa} (a transparência é ignorada) */
    public static double razao(String corA, String corB) {
        double la = luminancia(corA);
        double lb = luminancia(corB);
        double clara = Math.max(la, lb);
        double escura = Math.min(la, lb);
        return (clara + 0.05) / (escura + 0.05);
    }

    /** Luminância relativa (0 = preto, 1 = branco). */
    public static double luminancia(String cor) {
        int[] rgb = rgb(cor);
        return 0.2126 * canal(rgb[0]) + 0.7152 * canal(rgb[1]) + 0.0722 * canal(rgb[2]);
    }

    private static double canal(int valor) {
        double c = valor / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    static int[] rgb(String cor) {
        String hex = cor.trim().toLowerCase(Locale.ROOT);
        if (!hex.matches("#([0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})")) {
            throw new IllegalArgumentException("Cor fora do formato #rrggbb: " + cor);
        }
        hex = hex.substring(1);
        if (hex.length() == 3) {
            hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
        }
        return new int[] {Integer.parseInt(hex.substring(0, 2), 16), Integer.parseInt(hex.substring(2, 4), 16),
                Integer.parseInt(hex.substring(4, 6), 16)};
    }
}
