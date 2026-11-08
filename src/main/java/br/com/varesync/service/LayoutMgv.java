package br.com.varesync.service;

import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Linha do arquivo de itens da balança Toledo ({@code ITENSMGV.TXT}, MGV6), F18.2. Classe isolada e sem banco: o
 * formato precisa ser conferido com o manual da balança da loja antes do uso real (risco registrado no PROGRESSO).
 *
 * <p>Fonte: ajuda do MGV6, "Arquivos de cadastro" (help.toledobrasil.com), layout de itens. Gravamos só a parte fixa,
 * até o EAN-13 do fornecedor (posição 150), como o ACBrCargaBal; os campos da versão 4 que vêm depois (glaciamento,
 * descrições 3 e 4, promoção e as listas entre {@code |}) ficam de fora. Uma linha por item, números com zeros à
 * esquerda, textos com espaços à direita e fim de linha CRLF.
 *
 * <pre>
 * Pos.     Tam. Campo                              Valor gravado
 * 1-2       2   departamento (DD)                  01
 * 3         1   tipo de produto (T)                0 = venda por peso
 * 4-9       6   código do item (CCCCCC)            PLU
 * 10-15     6   preço por kg (PPPPPP)              preço vigente na data, em centavos
 * 16-18     3   dias de validade (VVV)             1 a 990; 000 = não imprime validade
 * 19-43    25   descrição, linha 1 (D1)            maiúsculas, sem acentos, quebrada no último espaço
 * 44-68    25   descrição, linha 2 (D2)            o resto, cortado em 25
 * 69-74     6   informação extra (RRRRRR)          000000
 * 75-78     4   imagem (FFFF)                      0000
 * 79-84     6   informação nutricional (IIIIII)    000000
 * 85        1   imprime data de validade (DV)      1 com dias de validade; 0 sem
 * 86        1   imprime data de embalagem (DE)     igual ao DV
 * 87-90     4   fornecedor (CF)                    0000
 * 91-102   12   lote (L)                           000000000000
 * 103-113  11   EAN-13 especial (G)                00000000000
 * 114       1   versão do preço (Z)                0
 * 115-118   4   som (CS)                           0000
 * 119-122   4   tara pré-determinada (CT)          0000
 * 123-126   4   fracionador (FR)                   0000
 * 127-130   4   campo extra 1 (CE1)                0000
 * 131-134   4   campo extra 2 (CE2)                0000
 * 135-138   4   conservação (CON)                  0000
 * 139-150  12   EAN-13 do fornecedor (EAN)         000000000000
 * </pre>
 */
public final class LayoutMgv {

    public static final String ARQUIVO = "ITENSMGV.TXT";
    public static final String FIM_DE_LINHA = "\r\n";
    public static final int TAMANHO_LINHA = 150;
    public static final int TAMANHO_DESCRICAO = 25;
    /** 991 a 999 têm significado especial no MGV (998 = não imprime datas; 999 = validade digitada na balança). */
    public static final int VALIDADE_MAXIMA = 990;
    public static final int PLU_MAXIMO = 999_999;
    /** Seis dígitos de centavos. */
    public static final BigDecimal PRECO_MAXIMO = new BigDecimal("9999.99");

    private static final String DEPARTAMENTO = "01";
    private static final String TIPO_PESO = "0";

    private LayoutMgv() {
    }

    /** As duas linhas de descrição, sem os espaços do fim. */
    public record Descricao(String linha1, String linha2) {

        /** Como fica guardada na carga ({@code carga_balanca_item.descricao}): as duas linhas juntas por um espaço. */
        public String juntas() {
            return linha2.isEmpty() ? linha1 : linha1 + " " + linha2;
        }
    }

    /**
     * Nome em maiúsculas, sem acentos e só com caracteres ASCII visíveis (os outros viram espaço), quebrado em duas
     * linhas de 25: a linha 1 termina no último espaço que cabe (sem espaço, corta em 25); a linha 2 é o resto,
     * cortado em 25.
     */
    public static Descricao descricao(String nome) {
        String texto = normalizar(nome);
        if (texto.length() <= TAMANHO_DESCRICAO) {
            return new Descricao(texto, "");
        }
        int espaco = texto.lastIndexOf(' ', TAMANHO_DESCRICAO);
        String linha1;
        String resto;
        if (espaco > 0) {
            linha1 = texto.substring(0, espaco);
            resto = texto.substring(espaco + 1);
        } else {
            linha1 = texto.substring(0, TAMANHO_DESCRICAO);
            resto = texto.substring(TAMANHO_DESCRICAO).strip();
        }
        String linha2 = resto.length() <= TAMANHO_DESCRICAO ? resto : resto.substring(0, TAMANHO_DESCRICAO).strip();
        return new Descricao(linha1, linha2);
    }

    /**
     * A linha do item, com 150 caracteres e sem o fim de linha.
     *
     * @param validadeDias nulo ou 0 = sem validade
     * @throws NegocioException se o PLU, o preço ou a validade não cabem no layout
     */
    public static String linha(String nome, int plu, BigDecimal precoKg, Integer validadeDias) {
        if (plu < 1 || plu > PLU_MAXIMO) {
            throw new NegocioException("O PLU de \"" + nome + "\" deve estar entre 1 e " + PLU_MAXIMO + ".");
        }
        if (precoKg == null || precoKg.signum() <= 0) {
            throw new NegocioException("\"" + nome + "\" está sem preço e não pode ir para a balança.");
        }
        if (precoKg.compareTo(PRECO_MAXIMO) > 0) {
            throw new NegocioException("O preço de \"" + nome + "\" (" + Formatos.moeda(precoKg)
                    + " o kg) não cabe na balança: o máximo é " + Formatos.moeda(PRECO_MAXIMO) + ".");
        }
        int dias = validadeDias == null ? 0 : validadeDias;
        if (dias < 0 || dias > VALIDADE_MAXIMA) {
            throw new NegocioException("Os dias de validade de \"" + nome + "\" devem estar entre 1 e "
                    + VALIDADE_MAXIMA + ".");
        }
        long centavos = precoKg.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
        Descricao d = descricao(nome);
        String imprimeData = dias > 0 ? "1" : "0";
        StringBuilder sb = new StringBuilder(TAMANHO_LINHA);
        sb.append(DEPARTAMENTO)                                // DD
                .append(TIPO_PESO)                             // T
                .append(numero(plu, 6))                        // CCCCCC
                .append(numero(centavos, 6))                   // PPPPPP
                .append(numero(dias, 3))                       // VVV
                .append(texto(d.linha1(), TAMANHO_DESCRICAO))  // D1
                .append(texto(d.linha2(), TAMANHO_DESCRICAO))  // D2
                .append(zeros(6))                              // RRRRRR informação extra
                .append(zeros(4))                              // FFFF imagem
                .append(zeros(6))                              // IIIIII informação nutricional
                .append(imprimeData)                           // DV imprime validade
                .append(imprimeData)                           // DE imprime embalagem
                .append(zeros(4))                              // CF fornecedor
                .append(zeros(12))                             // L lote
                .append(zeros(11))                             // G EAN-13 especial
                .append('0')                                   // Z versão do preço
                .append(zeros(4))                              // CS som
                .append(zeros(4))                              // CT tara
                .append(zeros(4))                              // FR fracionador
                .append(zeros(4))                              // CE1
                .append(zeros(4))                              // CE2
                .append(zeros(4))                              // CON conservação
                .append(zeros(12));                            // EAN fornecedor
        if (sb.length() != TAMANHO_LINHA) {
            throw new IllegalStateException("Linha do MGV com " + sb.length() + " caracteres");
        }
        return sb.toString();
    }

    static String normalizar(String nome) {
        String sem = Normalizer.normalize(nome == null ? "" : nome, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                .replace('\u2044', '/'); // ½ vira 1⁄2 (barra de fração)
        StringBuilder sb = new StringBuilder(sem.length());
        for (int i = 0; i < sem.length(); i++) {
            char c = sem.charAt(i);
            sb.append(c >= 0x20 && c < 0x7F ? c : ' ');
        }
        return sb.toString().toUpperCase(Locale.ROOT).replaceAll(" +", " ").strip();
    }

    private static String numero(long valor, int tamanho) {
        String s = Long.toString(valor);
        return "0".repeat(tamanho - s.length()) + s;
    }

    private static String zeros(int tamanho) {
        return "0".repeat(tamanho);
    }

    private static String texto(String valor, int tamanho) {
        return valor + " ".repeat(tamanho - valor.length());
    }
}
