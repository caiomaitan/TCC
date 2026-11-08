package br.com.varesync.service;

import br.com.varesync.util.Dinheiro;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Formato dos dados fiscais (F19.1 a F19.3). Só o formato de cada campo é conferido aqui: as combinações erradas (CST
 * fora do regime, CFOP sem ST, alíquota numa situação isenta…) são do R30 (F19.4). Cada método devolve o valor
 * normalizado (sem pontos, traços e espaços nos códigos; nulo quando vazio) ou recusa com a mensagem para o usuário.
 */
public final class ValidacaoFiscal {

    public static final int TAMANHO_NOME_PERFIL = 60;
    /** "SEM CBENEF": texto que algumas UFs pedem quando o CST exige código e a operação não tem benefício. */
    public static final String SEM_CBENEF = "SEM CBENEF";
    public static final String ISENTO = "ISENTO";
    private static final int IE_MAXIMO = 14;
    private static final BigDecimal CEM = BigDecimal.valueOf(100);
    private static final Pattern SEPARADORES = Pattern.compile("[.\\-/\\s]");
    private static final Pattern BENEFICIO = Pattern.compile("[A-Z]{2}[A-Z0-9]{6}");

    private ValidacaoFiscal() {
    }

    /** UF da loja: vazia ou uma das 27 siglas. */
    public static String uf(String valor) {
        String uf = valor == null ? "" : valor.strip().toUpperCase(Locale.ROOT);
        if (!uf.isEmpty() && !ValidacaoFornecedor.UFS.contains(uf)) {
            throw new NegocioException("UF da loja inválida: use a sigla do estado (ex.: SP).");
        }
        return uf;
    }

    /** IE da loja: vazia, "ISENTO" ou só dígitos (até 14; pontos, traços e barras são retirados). */
    public static String ie(String valor) {
        String texto = valor == null ? "" : valor.strip();
        if (texto.isEmpty()) {
            return "";
        }
        if (texto.equalsIgnoreCase(ISENTO)) {
            return ISENTO;
        }
        String ie = SEPARADORES.matcher(texto).replaceAll("");
        if (ie.isEmpty() || ie.length() > IE_MAXIMO || !ie.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new NegocioException("A inscrição estadual tem só dígitos (até " + IE_MAXIMO + ") ou é \"" + ISENTO
                    + "\".");
        }
        return ie;
    }

    /** NCM: vazio = nulo; senão 8 dígitos, com ou sem os pontos (2203.00.00). */
    public static String ncm(String valor) {
        return codigo(valor, 8, "O NCM tem 8 dígitos (ex.: 2203.00.00).");
    }

    /** CEST: vazio = nulo; senão 7 dígitos, com ou sem os pontos (03.001.00). */
    public static String cest(String valor) {
        return codigo(valor, 7, "O CEST tem 7 dígitos (ex.: 03.001.00).");
    }

    /** CFOP: obrigatório, 4 dígitos (5.102 ou 5102). */
    public static String cfop(String valor) {
        return obrigatorio(codigo(valor, 4, "O CFOP tem 4 dígitos (ex.: 5102)."), "Informe o CFOP.");
    }

    /** CST do ICMS (regime normal): 2 dígitos. */
    public static String cstIcms(String valor) {
        return obrigatorio(codigo(valor, 2, "O CST do ICMS tem 2 dígitos (ex.: 00, 60)."), "Informe o CST do ICMS.");
    }

    /** CSOSN (Simples Nacional): 3 dígitos. */
    public static String csosn(String valor) {
        return obrigatorio(codigo(valor, 3, "O CSOSN tem 3 dígitos (ex.: 102, 500)."), "Informe o CSOSN.");
    }

    /** CST do PIS ou da COFINS: 2 dígitos. */
    public static String cstPisCofins(String valor, String imposto) {
        return obrigatorio(codigo(valor, 2, "O CST do " + imposto + " tem 2 dígitos (ex.: 01, 49)."),
                "Informe o CST do " + imposto + ".");
    }

    /** Reforma tributária: CST do IBS e da CBS, opcional, 3 dígitos. */
    public static String cstIbsCbs(String valor) {
        return codigo(valor, 3, "O CST do IBS e da CBS tem 3 dígitos (ex.: 000).");
    }

    /** Reforma tributária: cClassTrib, opcional, 6 dígitos. */
    public static String cclassTrib(String valor) {
        return codigo(valor, 6, "O cClassTrib tem 6 dígitos (ex.: 000001).");
    }

    /**
     * Código de benefício fiscal (cBenef), opcional: 8 caracteres que começam pela sigla de uma UF (SP010360) ou
     * "SEM CBENEF". A UF do código diferente da UF da loja é aviso do R30, não erro aqui.
     */
    public static String codigoBeneficio(String valor) {
        String texto = valor == null ? "" : valor.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        if (texto.isEmpty()) {
            return null;
        }
        if (texto.equals(SEM_CBENEF)) {
            return texto;
        }
        if (!BENEFICIO.matcher(texto).matches() || !ValidacaoFornecedor.UFS.contains(texto.substring(0, 2))) {
            throw new NegocioException("O código de benefício tem 8 caracteres e começa pela sigla da UF (ex.: "
                    + "SP010360), ou é \"" + SEM_CBENEF + "\".");
        }
        return texto;
    }

    /** Alíquota em %: nula = 0; de 0 a 100, com até 2 casas. */
    public static BigDecimal aliquota(BigDecimal valor, String rotulo) {
        BigDecimal v = valor == null ? BigDecimal.ZERO : valor;
        if (v.signum() < 0 || v.compareTo(CEM) > 0) {
            throw new NegocioException("A alíquota " + rotulo + " deve estar entre 0 e 100%.");
        }
        if (ValidacaoProduto.casas(v) > Dinheiro.ESCALA_PERCENTUAL) {
            throw new NegocioException("A alíquota " + rotulo + " pode ter no máximo " + Dinheiro.ESCALA_PERCENTUAL
                    + " casas decimais.");
        }
        return v.setScale(Dinheiro.ESCALA_PERCENTUAL, Dinheiro.ARREDONDAMENTO);
    }

    /** Redução da base do ICMS em %: nula ou zero = sem redução; senão maior que 0 e menor que 100, até 2 casas. */
    public static BigDecimal reducaoBase(BigDecimal valor) {
        if (valor == null || valor.signum() == 0) {
            return null;
        }
        if (valor.signum() < 0 || valor.compareTo(CEM) >= 0) {
            throw new NegocioException("A redução da base do ICMS deve ser maior que 0 e menor que 100%.");
        }
        if (ValidacaoProduto.casas(valor) > Dinheiro.ESCALA_PERCENTUAL) {
            throw new NegocioException("A redução da base do ICMS pode ter no máximo " + Dinheiro.ESCALA_PERCENTUAL
                    + " casas decimais.");
        }
        return valor.setScale(Dinheiro.ESCALA_PERCENTUAL, Dinheiro.ARREDONDAMENTO);
    }

    /** Origem da mercadoria: nula ou de 0 a 8. */
    public static Integer origem(Integer valor) {
        if (valor != null && (valor < 0 || valor > 8)) {
            throw new NegocioException("A origem da mercadoria vai de 0 a 8.");
        }
        return valor;
    }

    /** Nome do perfil: obrigatório, espaços duplos retirados, até 60 caracteres. */
    public static String nomePerfil(String valor) {
        String nome = br.com.varesync.util.Textos.normalizarEspacos(valor);
        if (nome.isEmpty()) {
            throw new NegocioException("Informe o nome do perfil tributário.");
        }
        if (nome.length() > TAMANHO_NOME_PERFIL) {
            throw new NegocioException("O nome do perfil pode ter no máximo " + TAMANHO_NOME_PERFIL + " caracteres.");
        }
        return nome;
    }

    /** Só os dígitos, sem pontos, traços, barras e espaços; nulo se não for isso. */
    public static String semSeparadores(String valor) {
        String texto = valor == null ? "" : SEPARADORES.matcher(valor.strip()).replaceAll("");
        return texto.chars().allMatch(c -> c >= '0' && c <= '9') ? texto : null;
    }

    /** "22030000" → "2203.00.00"; outro tamanho fica como está. */
    public static String formatarNcm(String ncm) {
        return ncm != null && ncm.length() == 8
                ? ncm.substring(0, 4) + "." + ncm.substring(4, 6) + "." + ncm.substring(6) : ncm == null ? "" : ncm;
    }

    /** "0300100" → "03.001.00"; outro tamanho fica como está. */
    public static String formatarCest(String cest) {
        return cest != null && cest.length() == 7
                ? cest.substring(0, 2) + "." + cest.substring(2, 5) + "." + cest.substring(5) : cest == null ? "" : cest;
    }

    private static String codigo(String valor, int digitos, String mensagem) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        String codigo = semSeparadores(valor);
        if (codigo == null || codigo.length() != digitos) {
            throw new NegocioException(mensagem);
        }
        return codigo;
    }

    private static String obrigatorio(String valor, String mensagem) {
        if (valor == null) {
            throw new NegocioException(mensagem);
        }
        return valor;
    }
}
