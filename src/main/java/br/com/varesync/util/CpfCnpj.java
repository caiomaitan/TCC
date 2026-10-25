package br.com.varesync.util;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * CPF e CNPJ do fornecedor e do cliente (6.10, F12.1, A.2, A.3). Gravados sem máscara; exibidos com máscara.
 *
 * <p>CNPJ com 14 posições, numérico ou alfanumérico (novo formato da Receita Federal: letras e números nas 12
 * primeiras posições, dígitos verificadores numéricos). Valor de cada caractere = código ASCII − 48 (dígitos 0–9,
 * letras A–Z 17–42); pesos 2 a 9 da direita para a esquerda, reiniciando; resto = soma mod 11; DV = 0 se resto &lt; 2,
 * senão 11 − resto; o 2º DV inclui o 1º.
 */
public final class CpfCnpj {

    private static final Pattern SEPARADORES = Pattern.compile("[\\s.\\-/]");
    private static final Pattern FORMATO_CNPJ = Pattern.compile("[0-9A-Z]{12}[0-9]{2}");

    private CpfCnpj() {
    }

    /** Tira pontos, traços, barras e espaços e passa as letras para maiúsculas ("12.abc.345/01de-35" → "12ABC34501DE35"). */
    public static String normalizar(String digitado) {
        return digitado == null ? "" : SEPARADORES.matcher(digitado).replaceAll("").toUpperCase(Formatos.PT_BR);
    }

    /**
     * @param documento já normalizado
     * @return a mensagem de erro para o usuário, ou vazio se é um CPF ou CNPJ válido
     */
    public static Optional<String> validar(String documento) {
        if (documento.length() == 11 && Textos.soDigitos(documento)) {
            return cpfValido(documento) ? Optional.empty()
                    : Optional.of("CPF " + formatar(documento) + " inválido: o dígito verificador não confere.");
        }
        if (documento.length() == 14) {
            if (!FORMATO_CNPJ.matcher(documento).matches()) {
                return Optional.of("CNPJ inválido: as 12 primeiras posições aceitam letras e números, e as 2 últimas "
                        + "só números.");
            }
            return cnpjValido(documento) ? Optional.empty()
                    : Optional.of("CNPJ " + formatar(documento) + " inválido: o dígito verificador não confere.");
        }
        return Optional.of("Informe o CPF com 11 dígitos ou o CNPJ com 14 posições.");
    }

    /** CPF com 11 dígitos e DVs corretos; todos os dígitos iguais é inválido. */
    public static boolean cpfValido(String cpf) {
        if (cpf == null || cpf.length() != 11 || !Textos.soDigitos(cpf) || todosIguais(cpf)) {
            return false;
        }
        return digitoCpf(cpf, 9) == cpf.charAt(9) - '0' && digitoCpf(cpf, 10) == cpf.charAt(10) - '0';
    }

    /** CNPJ numérico ou alfanumérico com DVs corretos (A.3); todos os caracteres iguais é inválido. */
    public static boolean cnpjValido(String cnpj) {
        if (cnpj == null || !FORMATO_CNPJ.matcher(cnpj).matches() || todosIguais(cnpj)) {
            return false;
        }
        return digitoCnpj(cnpj, 12) == cnpj.charAt(12) - '0' && digitoCnpj(cnpj, 13) == cnpj.charAt(13) - '0';
    }

    /** "529.982.247-25", "12.ABC.345/01DE-35"; qualquer outro tamanho volta como está. */
    public static String formatar(String documento) {
        if (documento == null) {
            return "";
        }
        if (documento.length() == 11) {
            return documento.substring(0, 3) + "." + documento.substring(3, 6) + "." + documento.substring(6, 9)
                    + "-" + documento.substring(9);
        }
        if (documento.length() == 14) {
            return documento.substring(0, 2) + "." + documento.substring(2, 5) + "." + documento.substring(5, 8)
                    + "/" + documento.substring(8, 12) + "-" + documento.substring(12);
        }
        return documento;
    }

    /**
     * CPF mascarado para o comprovante e as listas do PDV (F12.2): "***.982.247-**", só os 6 dígitos do meio. CNPJ é
     * de empresa e volta inteiro, com máscara; outro tamanho volta como está.
     */
    public static String ocultar(String documento) {
        if (documento != null && documento.length() == 11) {
            return "***." + documento.substring(3, 6) + "." + documento.substring(6, 9) + "-**";
        }
        return formatar(documento);
    }

    /** DV do CPF sobre as {@code posicoes} primeiras: pesos de posicoes+1 até 2; (soma × 10) mod 11, 10 vira 0. */
    private static int digitoCpf(String cpf, int posicoes) {
        int soma = 0;
        for (int i = 0; i < posicoes; i++) {
            soma += (cpf.charAt(i) - '0') * (posicoes + 1 - i);
        }
        int resto = soma * 10 % 11;
        return resto == 10 ? 0 : resto;
    }

    /** DV do CNPJ sobre as {@code posicoes} primeiras (A.3). */
    private static int digitoCnpj(String cnpj, int posicoes) {
        int soma = 0;
        int peso = 2;
        for (int i = posicoes - 1; i >= 0; i--) {
            soma += (cnpj.charAt(i) - 48) * peso;
            peso = peso == 9 ? 2 : peso + 1;
        }
        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }

    private static boolean todosIguais(String texto) {
        return texto.chars().allMatch(c -> c == texto.charAt(0));
    }
}
