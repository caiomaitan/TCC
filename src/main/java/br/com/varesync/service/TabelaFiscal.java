package br.com.varesync.service;

import br.com.varesync.model.CodigoCest;
import br.com.varesync.model.CodigoNcm;
import br.com.varesync.model.LinhaIbpt;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Leitura das tabelas de apoio do cadastro fiscal (F19.3), sem gravar nada:
 * <ul>
 *   <li><b>NCM</b> de um CSV ({@code código;descrição}) ou do JSON oficial da tabela NCM (Siscomex: objeto com a lista
 *       {@code Nomenclaturas}, cada uma com {@code Codigo} e {@code Descricao}). Os códigos vêm com ou sem pontos
 *       (0000.00.00); os níveis intermediários (capítulo, posição, subposição) são pulados: só entram os de 8 dígitos.</li>
 *   <li><b>CEST</b> de um CSV ({@code cest;ncm;descrição}), em que o NCM pode ser o começo do código (2 a 8 dígitos) ou
 *       vários separados por vírgula ou espaço.</li>
 * </ul>
 * O CSV usa {@code ;}, em UTF-8 (com ou sem BOM) ou Windows-1252, com cabeçalho opcional. Uma linha com erro recusa o
 * arquivo inteiro (nada é importado), com o número da linha.
 */
public final class TabelaFiscal {

    private static final int ERROS_MOSTRADOS = 5;
    private static final Pattern MARCAS = Pattern.compile("<[^>]*>");
    private static final Pattern TRACOS_INICIAIS = Pattern.compile("^[\\s\\-–—]+");
    private static final Pattern VARIOS_NCM = Pattern.compile("[,;|\\s]+");

    private TabelaFiscal() {
    }

    /**
     * @param formato  "CSV" ou "JSON"
     * @param pulados  linhas com código que não é de 8 dígitos (níveis intermediários da tabela)
     */
    public record LeituraNcm(List<CodigoNcm> codigos, String formato, int pulados) {
    }

    /** Lê o NCM do CSV ou do JSON (reconhecido pelo primeiro caractere: chave ou colchete). */
    public static LeituraNcm lerNcm(byte[] conteudo) {
        String texto = texto(conteudo);
        String inicio = texto.stripLeading();
        if (inicio.isEmpty()) {
            throw new NegocioException("O arquivo está vazio.");
        }
        return inicio.charAt(0) == '{' || inicio.charAt(0) == '[' ? ncmJson(texto) : ncmCsv(texto);
    }

    private static LeituraNcm ncmCsv(String texto) {
        Map<String, Integer> linhaDoCodigo = new LinkedHashMap<>();
        List<CodigoNcm> codigos = new ArrayList<>();
        List<String> erros = new ArrayList<>();
        int pulados = 0;
        boolean primeira = true;
        for (Registro r : registros(texto)) {
            if (r.vazio()) {
                continue;
            }
            String bruto = r.campo(0);
            String codigo = ValidacaoFiscal.semSeparadores(bruto);
            if (primeira) {
                primeira = false;
                if (codigo == null || codigo.isEmpty()) {
                    continue; // cabeçalho
                }
            }
            if (codigo == null || codigo.isEmpty()) {
                erros.add("linha " + r.linha() + ": código \"" + bruto + "\" não é um NCM");
                continue;
            }
            if (codigo.length() != 8) {
                pulados++;
                continue;
            }
            Integer anterior = linhaDoCodigo.putIfAbsent(codigo, r.linha());
            if (anterior != null) {
                erros.add("linha " + r.linha() + ": o NCM " + ValidacaoFiscal.formatarNcm(codigo) + " repete o da linha "
                        + anterior);
                continue;
            }
            codigos.add(new CodigoNcm(codigo, descricao(r.campo(1))));
        }
        recusarSeHouverErros(erros);
        if (codigos.isEmpty()) {
            throw new NegocioException("O arquivo não tem nenhum NCM de 8 dígitos (código;descrição).");
        }
        return new LeituraNcm(List.copyOf(codigos), "CSV", pulados);
    }

    private static LeituraNcm ncmJson(String texto) {
        JsonNode raiz;
        try {
            raiz = new ObjectMapper().readTree(texto);
        } catch (JsonProcessingException e) {
            throw new NegocioException("O arquivo não é um JSON válido da tabela NCM.");
        }
        JsonNode lista = raiz.isArray() ? raiz : campo(raiz, "Nomenclaturas");
        if (lista == null || !lista.isArray()) {
            throw new NegocioException("O JSON não tem a lista \"Nomenclaturas\" da tabela NCM do Siscomex.");
        }
        Map<String, Integer> posicaoDoCodigo = new LinkedHashMap<>();
        List<CodigoNcm> codigos = new ArrayList<>();
        List<String> erros = new ArrayList<>();
        int pulados = 0;
        int posicao = 0;
        for (JsonNode item : lista) {
            posicao++;
            JsonNode codigoNo = campo(item, "Codigo");
            String bruto = codigoNo == null || codigoNo.isNull() ? "" : codigoNo.asText();
            String codigo = ValidacaoFiscal.semSeparadores(bruto);
            if (codigo == null || codigo.isEmpty()) {
                erros.add("item " + posicao + ": código \"" + bruto + "\" não é um NCM");
                continue;
            }
            if (codigo.length() != 8) {
                pulados++;
                continue;
            }
            Integer anterior = posicaoDoCodigo.putIfAbsent(codigo, posicao);
            if (anterior != null) {
                erros.add("item " + posicao + ": o NCM " + ValidacaoFiscal.formatarNcm(codigo) + " repete o do item "
                        + anterior);
                continue;
            }
            JsonNode descricao = campo(item, "Descricao");
            codigos.add(new CodigoNcm(codigo, descricao(descricao == null || descricao.isNull() ? "" : descricao.asText())));
        }
        recusarSeHouverErros(erros);
        if (codigos.isEmpty()) {
            throw new NegocioException("O JSON não tem nenhum NCM de 8 dígitos.");
        }
        return new LeituraNcm(List.copyOf(codigos), "JSON", pulados);
    }

    /** Campo do objeto pelo nome, sem diferenciar maiúsculas; nulo se faltar. */
    private static JsonNode campo(JsonNode objeto, String nome) {
        if (objeto == null || !objeto.isObject()) {
            return null;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = objeto.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getKey().equalsIgnoreCase(nome)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** Lê a tabela CEST ({@code cest;ncm;descrição}); um CEST com vários NCM vira uma linha por NCM. */
    public static List<CodigoCest> lerCest(byte[] conteudo) {
        String texto = texto(conteudo);
        Set<String> vistos = new LinkedHashSet<>();
        List<CodigoCest> linhas = new ArrayList<>();
        List<String> erros = new ArrayList<>();
        boolean primeira = true;
        for (Registro r : registros(texto)) {
            if (r.vazio()) {
                continue;
            }
            String brutoCest = r.campo(0);
            String cest = ValidacaoFiscal.semSeparadores(brutoCest);
            if (primeira) {
                primeira = false;
                if (cest == null || cest.isEmpty()) {
                    continue; // cabeçalho
                }
            }
            if (cest == null || cest.length() != 7) {
                erros.add("linha " + r.linha() + ": CEST \"" + brutoCest + "\" não tem 7 dígitos");
                continue;
            }
            String brutoNcm = r.campo(1).replace(".", "");
            List<String> ncms = new ArrayList<>();
            for (String parte : VARIOS_NCM.split(brutoNcm.strip())) {
                if (!parte.isEmpty()) {
                    ncms.add(parte);
                }
            }
            if (ncms.isEmpty()) {
                erros.add("linha " + r.linha() + ": falta o NCM do CEST " + ValidacaoFiscal.formatarCest(cest));
                continue;
            }
            for (String ncm : ncms) {
                if (ncm.length() < 2 || ncm.length() > 8 || !ncm.chars().allMatch(c -> c >= '0' && c <= '9')) {
                    erros.add("linha " + r.linha() + ": NCM \"" + ncm + "\" inválido (2 a 8 dígitos)");
                } else if (vistos.add(cest + "|" + ncm)) {
                    linhas.add(new CodigoCest(cest, ncm, descricao(r.campo(2))));
                }
            }
        }
        recusarSeHouverErros(erros);
        if (linhas.isEmpty()) {
            throw new NegocioException("O arquivo não tem nenhum CEST (cest;ncm;descrição).");
        }
        return List.copyOf(linhas);
    }

    // ----- Tabela IBPT (F22.3) -----

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/uuuu")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern UF_NO_ARQUIVO = Pattern.compile("(?i)TabelaIBPTax([A-Z]{2})");
    private static final List<String> COLUNAS_IBPT_OBRIGATORIAS = List.of("codigo", "tipo", "nacionalfederal",
            "importadosfederal", "estadual", "municipal", "vigenciainicio", "vigenciafim");
    private static final int ESCALA_ALIQUOTA_IBPT = 4;
    private static final int TAMANHO_EX = 3;

    /**
     * Linhas de NCM lidas do arquivo do IBPT.
     *
     * @param puladas linhas de serviço (NBS e LC 116, tipo diferente de 0), que ficam de fora
     * @param versao  versão da primeira linha
     * @param inicio  menor início de vigência
     * @param fim     maior fim de vigência
     */
    public record LeituraIbpt(List<LinhaIbpt> linhas, int puladas, String versao, LocalDate inicio, LocalDate fim,
                              String fonte) {
    }

    /**
     * Lê o CSV que o IBPT fornece por UF ("De Olho no Imposto"): as colunas pelo nome do cabeçalho, separador
     * {@code ;}, UTF-8 (com ou sem BOM) ou ISO-8859-1, decimal com ponto ou vírgula e datas dd/mm/aaaa. Só as linhas
     * de tipo 0 (NCM). Uma linha com erro recusa o arquivo inteiro.
     */
    public static LeituraIbpt lerIbpt(byte[] conteudo) {
        List<Registro> registros = registros(texto(conteudo)).stream().filter(r -> !r.vazio()).toList();
        if (registros.isEmpty()) {
            throw new NegocioException("O arquivo está vazio.");
        }
        Map<String, Integer> coluna = new LinkedHashMap<>();
        List<String> cabecalho = registros.get(0).campos();
        for (int i = 0; i < cabecalho.size(); i++) {
            coluna.putIfAbsent(cabecalho.get(i).strip().toLowerCase(java.util.Locale.ROOT), i);
        }
        List<String> faltam = COLUNAS_IBPT_OBRIGATORIAS.stream().filter(c -> !coluna.containsKey(c)).toList();
        if (!faltam.isEmpty()) {
            throw new NegocioException("O arquivo não está no leiaute da tabela do IBPT: falta"
                    + (faltam.size() == 1 ? " a coluna " : "m as colunas ") + String.join(", ", faltam)
                    + " no cabeçalho (codigo;ex;tipo;descricao;nacionalfederal;importadosfederal;estadual;municipal;"
                    + "vigenciainicio;vigenciafim;chave;versao;fonte).");
        }
        Map<String, Integer> linhaDaChave = new LinkedHashMap<>();
        List<LinhaIbpt> linhas = new ArrayList<>();
        List<String> erros = new ArrayList<>();
        int puladas = 0;
        for (Registro r : registros.subList(1, registros.size())) {
            java.util.function.Function<String, String> campo = nome -> coluna.containsKey(nome)
                    ? r.campo(coluna.get(nome)) : "";
            if (!"0".equals(campo.apply("tipo"))) {
                puladas++;
                continue;
            }
            List<String> errosDaLinha = new ArrayList<>();
            String codigo = ValidacaoFiscal.semSeparadores(campo.apply("codigo"));
            if (codigo == null || codigo.length() != 8) {
                errosDaLinha.add("NCM \"" + campo.apply("codigo") + "\" não tem 8 dígitos");
            }
            String ex = campo.apply("ex");
            if (!ex.isEmpty() && (ex.length() > TAMANHO_EX || !ex.chars().allMatch(Character::isDigit))) {
                errosDaLinha.add("ex \"" + ex + "\" inválido");
            }
            BigDecimal nacional = aliquotaIbpt(campo.apply("nacionalfederal"), "nacionalfederal", errosDaLinha);
            BigDecimal importados = aliquotaIbpt(campo.apply("importadosfederal"), "importadosfederal", errosDaLinha);
            BigDecimal estadual = aliquotaIbpt(campo.apply("estadual"), "estadual", errosDaLinha);
            BigDecimal municipal = aliquotaIbpt(campo.apply("municipal"), "municipal", errosDaLinha);
            LocalDate inicio = data(campo.apply("vigenciainicio"), "vigenciainicio", errosDaLinha);
            LocalDate fim = data(campo.apply("vigenciafim"), "vigenciafim", errosDaLinha);
            if (inicio != null && fim != null && fim.isBefore(inicio)) {
                errosDaLinha.add("vigência termina antes de começar");
            }
            if (errosDaLinha.isEmpty()) {
                Integer anterior = linhaDaChave.putIfAbsent(codigo + "|" + ex, r.linha());
                if (anterior != null) {
                    errosDaLinha.add("o NCM " + ValidacaoFiscal.formatarNcm(codigo) + (ex.isEmpty() ? "" : " ex " + ex)
                            + " repete o da linha " + anterior);
                }
            }
            if (!errosDaLinha.isEmpty()) {
                erros.add("linha " + r.linha() + ": " + String.join(", ", errosDaLinha));
                continue;
            }
            linhas.add(new LinhaIbpt(codigo, ex, descricao(campo.apply("descricao")), nacional, importados, estadual,
                    municipal, inicio, fim, limitar(campo.apply("chave"), 20), limitar(campo.apply("versao"), 20),
                    limitar(campo.apply("fonte"), 100)));
        }
        recusarSeHouverErros(erros);
        if (linhas.isEmpty()) {
            throw new NegocioException("O arquivo não tem nenhuma linha de NCM (tipo 0).");
        }
        LocalDate inicio = linhas.stream().map(LinhaIbpt::vigenciaInicio).min(LocalDate::compareTo).orElseThrow();
        LocalDate fim = linhas.stream().map(LinhaIbpt::vigenciaFim).max(LocalDate::compareTo).orElseThrow();
        return new LeituraIbpt(List.copyOf(linhas), puladas, linhas.get(0).versao(), inicio, fim,
                linhas.get(0).fonte());
    }

    /** A UF no nome do arquivo do IBPT (TabelaIBPTaxSP25.2.A.csv → SP); vazia se o nome não trouxer uma UF. */
    public static java.util.Optional<String> ufDoArquivo(String nomeArquivo) {
        if (nomeArquivo == null) {
            return java.util.Optional.empty();
        }
        java.util.regex.Matcher m = UF_NO_ARQUIVO.matcher(nomeArquivo);
        if (!m.find()) {
            return java.util.Optional.empty();
        }
        String uf = m.group(1).toUpperCase(java.util.Locale.ROOT);
        try {
            return java.util.Optional.ofNullable(ValidacaoFiscal.uf(uf)).filter(u -> !u.isEmpty());
        } catch (NegocioException e) {
            return java.util.Optional.empty();
        }
    }

    /** Percentual de 0 a 100, com ponto ou vírgula decimal, até 4 casas. */
    private static BigDecimal aliquotaIbpt(String texto, String nome, List<String> erros) {
        String t = texto.strip();
        if (t.contains(",")) {
            t = t.replace(".", "").replace(',', '.');
        }
        try {
            BigDecimal v = new BigDecimal(t);
            if (v.signum() < 0 || v.compareTo(BigDecimal.valueOf(100)) > 0
                    || v.stripTrailingZeros().scale() > ESCALA_ALIQUOTA_IBPT) {
                erros.add(nome + " \"" + texto + "\" fora de 0 a 100 ou com mais de 4 casas");
                return null;
            }
            return v.setScale(ESCALA_ALIQUOTA_IBPT, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            erros.add(nome + " \"" + texto + "\" não é número");
            return null;
        }
    }

    private static LocalDate data(String texto, String nome, List<String> erros) {
        try {
            return LocalDate.parse(texto.strip(), DATA);
        } catch (DateTimeParseException e) {
            erros.add(nome + " \"" + texto + "\" não é data dd/mm/aaaa");
            return null;
        }
    }

    private static String limitar(String texto, int tamanho) {
        return texto.length() > tamanho ? texto.substring(0, tamanho) : texto;
    }

    /** Sem marcações HTML, sem os traços do começo ("-- Reprodutores" → "Reprodutores") e sem espaços duplos. */
    static String descricao(String texto) {
        String semMarcas = MARCAS.matcher(texto == null ? "" : texto).replaceAll("");
        return TRACOS_INICIAIS.matcher(semMarcas).replaceAll("").replaceAll("\\s+", " ").strip();
    }

    private static void recusarSeHouverErros(List<String> erros) {
        if (erros.isEmpty()) {
            return;
        }
        String lista = String.join("; ", erros.subList(0, Math.min(ERROS_MOSTRADOS, erros.size())));
        throw new NegocioException("Nada foi importado: " + (erros.size() == 1 ? "1 erro" : erros.size() + " erros")
                + " no arquivo (" + lista + (erros.size() > ERROS_MOSTRADOS ? "; …" : "") + ").");
    }

    /** UTF-8 (sem o BOM) ou, se não for, Windows-1252. */
    private static String texto(byte[] conteudo) {
        String texto;
        try {
            texto = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(conteudo)).toString();
        } catch (CharacterCodingException e) {
            texto = new String(conteudo, PlanilhaProdutos.WINDOWS_1252);
        }
        return texto.startsWith("﻿") ? texto.substring(1) : texto;
    }

    private record Registro(int linha, List<String> campos) {

        boolean vazio() {
            return campos.stream().allMatch(String::isBlank);
        }

        String campo(int i) {
            return i < campos.size() ? campos.get(i).strip() : "";
        }
    }

    /** Registros separados por {@code ;}, com campos entre aspas (que podem ter {@code ;} e aspas dobradas). */
    private static List<Registro> registros(String texto) {
        List<Registro> registros = new ArrayList<>();
        List<String> campos = new ArrayList<>();
        StringBuilder campo = new StringBuilder();
        boolean aspas = false;
        int linha = 1;
        int inicio = 1;
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (aspas) {
                if (c == '"' && i + 1 < texto.length() && texto.charAt(i + 1) == '"') {
                    campo.append('"');
                    i++;
                } else if (c == '"') {
                    aspas = false;
                } else {
                    if (c == '\n') {
                        linha++;
                    }
                    campo.append(c);
                }
            } else if (c == '"' && campo.toString().isBlank()) {
                campo.setLength(0);
                aspas = true;
            } else if (c == ';') {
                campos.add(campo.toString());
                campo.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < texto.length() && texto.charAt(i + 1) == '\n') {
                    i++;
                }
                campos.add(campo.toString());
                registros.add(new Registro(inicio, List.copyOf(campos)));
                campos.clear();
                campo.setLength(0);
                linha++;
                inicio = linha;
            } else {
                campo.append(c);
            }
        }
        if (campo.length() > 0 || !campos.isEmpty()) {
            campos.add(campo.toString());
            registros.add(new Registro(inicio, List.copyOf(campos)));
        }
        return registros;
    }
}
