package br.com.varesync.report;

import br.com.varesync.model.Etiqueta;
import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Texto da etiqueta na impressora térmica (F18.1): o modelo ZPL ou DPL de {@code etiqueta.modelo_termica} com as
 * variáveis trocadas pelos dados do produto, uma etiqueta por unidade da quantidade.
 *
 * <p>Variáveis: {NOME} (ou {NOME:n}, cortado em n caracteres), {PRECO}, {PRECO_DE}, {UNIDADE}, {CODBARRAS}, {PLU},
 * {CODIGO} e {VALIDADE} (fim da promoção). Os preços saem sem "R$" (12,90); a variável que não se aplica sai vazia;
 * texto entre chaves que não é variável fica como está.
 *
 * <p>O texto enviado sai sem acentos e só com caracteres ASCII (o que sobra vira "?"), porque as impressoras
 * térmicas costumam não ter a tabela de caracteres certa. Nos valores, os caracteres de comando do ZPL ({@code ^} e
 * {@code ~}) e os de controle são retirados, para o nome de um produto não virar comando. {@code <STX>} no modelo vira
 * o caractere STX (código 2), que abre os comandos do DPL. As quebras de linha saem como CR LF.
 */
public final class ModeloTermica {

    private static final Pattern VARIAVEL =
            Pattern.compile("\\{(NOME|PRECO_DE|PRECO|UNIDADE|CODBARRAS|PLU|CODIGO|VALIDADE)(?::(\\d{1,3}))?}");
    static final String STX = "<STX>";

    private ModeloTermica() {
    }

    /** Uma etiqueta (sem repetir pela quantidade). */
    public static String aplicar(String modelo, Etiqueta e) {
        Matcher m = VARIAVEL.matcher(modelo);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String nome = m.group(1);
            String valor;
            if (m.group(2) != null && !nome.equals("NOME")) {
                valor = m.group(); // só {NOME:n} aceita o tamanho
            } else {
                valor = limpar(valor(nome, e));
                if (m.group(2) != null) {
                    int n = Integer.parseInt(m.group(2));
                    valor = valor.length() > n ? valor.substring(0, n).stripTrailing() : valor;
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(valor));
        }
        m.appendTail(sb);
        return paraImpressora(sb.toString());
    }

    /** Todas as etiquetas, cada uma repetida pela quantidade. */
    public static String aplicar(String modelo, List<Etiqueta> etiquetas) {
        StringBuilder sb = new StringBuilder();
        for (Etiqueta e : etiquetas) {
            String uma = aplicar(modelo, e);
            if (!uma.endsWith("\r\n")) {
                uma = uma + "\r\n";
            }
            sb.append(uma.repeat(e.quantidade()));
        }
        return sb.toString();
    }

    private static String valor(String variavel, Etiqueta e) {
        return switch (variavel) {
            case "NOME" -> e.nome();
            case "PRECO" -> preco(e.preco());
            case "PRECO_DE" -> e.precoDe() == null ? "" : preco(e.precoDe());
            case "UNIDADE" -> e.unidade();
            case "CODBARRAS" -> e.codigoBarras() == null ? "" : e.codigoBarras();
            case "PLU" -> e.plu() == null ? "" : String.valueOf(e.plu());
            case "CODIGO" -> String.valueOf(e.produtoId());
            case "VALIDADE" -> e.fimPromocao() == null ? "" : Formatos.data(e.fimPromocao());
            default -> throw new IllegalArgumentException(variavel);
        };
    }

    private static String preco(BigDecimal valor) {
        return Formatos.numero(valor, 2);
    }

    /** Sem os caracteres de comando do ZPL e sem caracteres de controle. */
    private static String limpar(String valor) {
        return valor == null ? "" : valor.replaceAll("[\\^~\\p{Cntrl}]", "");
    }

    /** Sem acentos, ASCII, {@code <STX>} como o caractere 2 e quebras de linha CR LF. */
    static String paraImpressora(String texto) {
        String sem = Normalizer.normalize(texto, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        StringBuilder sb = new StringBuilder(sem.length());
        for (int i = 0; i < sem.length(); i++) {
            char c = sem.charAt(i);
            sb.append(c < 128 ? c : '?');
        }
        return sb.toString().replace(STX, "\u0002").replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n");
    }
}
