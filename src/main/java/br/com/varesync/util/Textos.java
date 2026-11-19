package br.com.varesync.util;

import java.util.Locale;
import java.util.regex.Pattern;

/** Normalização de textos digitados (nomes, logins e termos de busca). */
public final class Textos {

    /**
     * Mesma tabela do {@code translate(...)} da coluna gerada {@code produto.nome_busca} (V1),
     * para que o termo normalizado em Java case com o que o banco gravou.
     */
    private static final String COM_ACENTO = "ÁÀÂÃÄáàâãäÉÈÊËéèêëÍÌÎÏíìîïÓÒÔÕÖóòôõöÚÙÛÜúùûüÇçÑñ";
    private static final String SEM_ACENTO = "AAAAAaaaaaEEEEeeeeIIIIiiiiOOOOOoooooUUUUuuuuCcNn";

    private static final Pattern ESPACOS = Pattern.compile("\\s+");
    private static final Pattern SO_DIGITOS = Pattern.compile("\\d+");
    private static final Pattern NAO_DIGITO = Pattern.compile("\\D");

    private Textos() {
    }

    public static boolean vazio(String texto) {
        return texto == null || texto.isBlank();
    }

    /** Remove só os espaços das pontas. Nulo vira "". */
    public static String vazioSeNulo(String texto) {
        return texto == null ? "" : texto.strip();
    }

    /** Remove espaços das pontas e troca sequências de espaços por um só. Nulo vira "". */
    public static String normalizarEspacos(String texto) {
        if (texto == null) {
            return "";
        }
        return ESPACOS.matcher(texto.strip()).replaceAll(" ");
    }

    /** Remove os acentos da tabela usada pelo banco em {@code nome_busca}. */
    public static String semAcento(String texto) {
        if (texto == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(texto.length());
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            int pos = COM_ACENTO.indexOf(c);
            sb.append(pos >= 0 ? SEM_ACENTO.charAt(pos) : c);
        }
        return sb.toString();
    }

    /** Termo de busca: espaços normalizados, sem acento e minúsculo (mesma regra de {@code nome_busca}). */
    public static String normalizarBusca(String texto) {
        return semAcento(normalizarEspacos(texto)).toLowerCase(Locale.ROOT);
    }

    /** Login: sem espaços nas pontas e minúsculo. */
    public static String normalizarLogin(String texto) {
        return texto == null ? "" : texto.strip().toLowerCase(Locale.ROOT);
    }

    public static boolean soDigitos(String texto) {
        return texto != null && SO_DIGITOS.matcher(texto).matches();
    }

    public static String apenasDigitos(String texto) {
        return texto == null ? "" : NAO_DIGITO.matcher(texto).replaceAll("");
    }
}
