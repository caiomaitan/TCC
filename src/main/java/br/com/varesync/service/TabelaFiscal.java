package br.com.varesync.service;

import br.com.varesync.model.CodigoCest;
import br.com.varesync.model.CodigoNcm;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
