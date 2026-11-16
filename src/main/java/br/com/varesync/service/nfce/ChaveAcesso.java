package br.com.varesync.service.nfce;

import java.time.YearMonth;

/**
 * Chave de acesso da NFC-e (F23.3): UF (2) + AAMM (4) + CNPJ (14) + modelo 65 (2) + série (3) + número (9) + tpEmis (1)
 * + cNF (8) + DV. O DV é o módulo 11 com pesos de 2 a 9 (da direita para a esquerda, reiniciando), e resto 0 ou 1 dá 0.
 */
public final class ChaveAcesso {

    public static final String MODELO_NFCE = "65";

    private ChaveAcesso() {
    }

    public static String montar(int codigoUf, YearMonth emissao, String cnpj, int serie, int numero, int tipoEmissao,
                                String codigoNumerico) {
        if (cnpj == null || !cnpj.matches("[0-9]{14}")) {
            throw new IllegalArgumentException("CNPJ do emitente com 14 dígitos");
        }
        if (codigoNumerico == null || !codigoNumerico.matches("[0-9]{8}")) {
            throw new IllegalArgumentException("cNF com 8 dígitos");
        }
        String semDigito = String.format("%02d%02d%02d%s%s%03d%09d%d%s", codigoUf, emissao.getYear() % 100,
                emissao.getMonthValue(), cnpj, MODELO_NFCE, serie, numero, tipoEmissao, codigoNumerico);
        return semDigito + digito(semDigito);
    }

    /** DV dos 43 primeiros dígitos. */
    public static int digito(String semDigito) {
        if (semDigito.length() != 43 || !semDigito.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("A chave sem o DV tem 43 dígitos");
        }
        int soma = 0;
        int peso = 2;
        for (int i = semDigito.length() - 1; i >= 0; i--) {
            soma += (semDigito.charAt(i) - '0') * peso;
            peso = peso == 9 ? 2 : peso + 1;
        }
        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }

    public static boolean valida(String chave) {
        return chave != null && chave.matches("[0-9]{44}")
                && digito(chave.substring(0, 43)) == chave.charAt(43) - '0';
    }

    /** "3526 1112 3456 ..." — a chave em 11 grupos de 4 dígitos (DANFE, F23.7). */
    public static String emGrupos(String chave) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < chave.length(); i += 4) {
            if (i > 0) {
                s.append(' ');
            }
            s.append(chave, i, Math.min(i + 4, chave.length()));
        }
        return s.toString();
    }
}
