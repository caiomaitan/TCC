package br.com.varesync.util;

import java.util.Optional;

/**
 * Códigos de barras GTIN-8, GTIN-12 (UPC), GTIN-13 (EAN) e GTIN-14 (A.1).
 * DV: sem o último dígito, da direita para a esquerda, pesos 3, 1, 3, 1…; DV = (10 − soma mod 10) mod 10.
 */
public final class Gtin {

    private Gtin() {
    }

    /** Dígito verificador do código sem o DV (só dígitos). */
    public static int digitoVerificador(String semDigito) {
        if (!Textos.soDigitos(semDigito)) {
            throw new IllegalArgumentException("O código deve ter só dígitos.");
        }
        int soma = 0;
        int peso = 3;
        for (int i = semDigito.length() - 1; i >= 0; i--) {
            soma += (semDigito.charAt(i) - '0') * peso;
            peso = peso == 3 ? 1 : 3;
        }
        return (10 - soma % 10) % 10;
    }

    public static boolean valido(String codigo) {
        return validar(codigo).isEmpty();
    }

    /** @return a mensagem de erro para o usuário, ou vazio se o código é válido */
    public static Optional<String> validar(String codigo) {
        if (codigo == null || codigo.isEmpty()) {
            return Optional.of("Informe o código de barras.");
        }
        if (!Textos.soDigitos(codigo)) {
            return Optional.of("O código de barras deve ter só números.");
        }
        int tamanho = codigo.length();
        if (tamanho != 8 && tamanho != 12 && tamanho != 13 && tamanho != 14) {
            return Optional.of("O código de barras deve ter 8, 12, 13 ou 14 dígitos.");
        }
        int informado = codigo.charAt(tamanho - 1) - '0';
        if (digitoVerificador(codigo.substring(0, tamanho - 1)) != informado) {
            return Optional.of("Código de barras " + codigo + " inválido: o dígito verificador não confere.");
        }
        return Optional.empty();
    }
}
