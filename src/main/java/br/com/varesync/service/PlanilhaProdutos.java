package br.com.varesync.service;

import br.com.varesync.util.Textos;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Planilha de produtos em CSV (6.11): colunas {@link #COLUNAS}, separador {@code ;}, decimal {@code ,}, UTF-8 (com ou
 * sem BOM) ou Windows-1252 (o "CSV" do Excel em português). Campos entre aspas podem ter {@code ;}, quebra de linha e
 * aspas dobradas. Só lê o texto: a validação de cada campo é do {@link ImportacaoProdutosService}.
 */
public final class PlanilhaProdutos {

    public static final List<String> COLUNAS = List.of("nome", "codigo_barras", "unidade", "categoria", "custo",
            "preco_venda", "estoque_minimo", "estoque_inicial");
    public static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final char SEPARADOR = ';';

    private PlanilhaProdutos() {
    }

    /**
     * Linha de produto da planilha.
     *
     * @param numero        linha do arquivo onde o registro começa (o cabeçalho é a linha 1)
     * @param campos        exatamente {@code COLUNAS.size()} campos, sem espaços nas pontas (faltando = vazio)
     * @param colunasDemais a linha tinha campos preenchidos além da última coluna
     */
    public record Linha(int numero, List<String> campos, boolean colunasDemais) {

        public String campo(String coluna) {
            return campos.get(COLUNAS.indexOf(coluna));
        }
    }

    /** @param codificacao "UTF-8" ou "Windows-1252" */
    public record Leitura(List<Linha> linhas, String codificacao) {
    }

    /** @throws NegocioException arquivo vazio, cabeçalho diferente do modelo ou sem nenhum produto */
    public static Leitura ler(byte[] conteudo) {
        String codificacao = "UTF-8";
        String texto;
        try {
            texto = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(conteudo)).toString();
        } catch (CharacterCodingException e) {
            texto = new String(conteudo, WINDOWS_1252);
            codificacao = "Windows-1252";
        }
        if (texto.startsWith("﻿")) {
            texto = texto.substring(1);
        }
        List<Registro> registros = registros(texto);
        int primeiro = 0;
        while (primeiro < registros.size() && registros.get(primeiro).vazio()) {
            primeiro++;
        }
        if (primeiro == registros.size()) {
            throw new NegocioException("O arquivo está vazio.");
        }
        conferirCabecalho(registros.get(primeiro));
        List<Linha> linhas = new ArrayList<>();
        for (Registro r : registros.subList(primeiro + 1, registros.size())) {
            if (r.vazio()) {
                continue;
            }
            List<String> campos = new ArrayList<>();
            for (int i = 0; i < COLUNAS.size(); i++) {
                campos.add(i < r.campos().size() ? r.campos().get(i).strip() : "");
            }
            boolean demais = r.campos().stream().skip(COLUNAS.size()).anyMatch(c -> !c.isBlank());
            linhas.add(new Linha(r.linha(), List.copyOf(campos), demais));
        }
        if (linhas.isEmpty()) {
            throw new NegocioException("A planilha não tem nenhum produto: só o cabeçalho.");
        }
        return new Leitura(linhas, codificacao);
    }

    /** Modelo para baixar: cabeçalho e dois exemplos, em UTF-8 com BOM (o Excel abre com os acentos certos). */
    public static byte[] modelo() {
        String texto = "﻿" + String.join(";", COLUNAS) + "\r\n"
                + "Café torrado 500 g;7891000315507;UN;Mercearia;12,5000;18,90;5;24\r\n"
                + "Queijo muçarela;;KG;Frios e laticínios;28,9000;42,90;2;10,500\r\n";
        return texto.getBytes(StandardCharsets.UTF_8);
    }

    private static void conferirCabecalho(Registro cabecalho) {
        List<String> nomes = cabecalho.campos().stream().map(c -> Textos.normalizarBusca(c.strip())).toList();
        int fim = nomes.size();
        while (fim > 0 && nomes.get(fim - 1).isEmpty()) {
            fim--;
        }
        if (!nomes.subList(0, fim).equals(COLUNAS)) {
            throw new NegocioException("A primeira linha (cabeçalho) deve ter as colunas " + String.join(";", COLUNAS)
                    + ", nesta ordem e separadas por ponto e vírgula. Baixe o modelo e copie os dados para ele.");
        }
    }

    private record Registro(int linha, List<String> campos) {

        boolean vazio() {
            return campos.stream().allMatch(String::isBlank);
        }
    }

    /** Divide o texto em registros e campos, respeitando as aspas. */
    private static List<Registro> registros(String texto) {
        List<Registro> registros = new ArrayList<>();
        List<String> campos = new ArrayList<>();
        StringBuilder campo = new StringBuilder();
        boolean entreAspas = false;
        int linha = 1;
        int inicio = 1;
        int i = 0;
        while (i < texto.length()) {
            char c = texto.charAt(i);
            if (entreAspas) {
                if (c == '"') {
                    if (i + 1 < texto.length() && texto.charAt(i + 1) == '"') {
                        campo.append('"');
                        i++;
                    } else {
                        entreAspas = false;
                    }
                } else {
                    if (c == '\n') {
                        linha++;
                    }
                    campo.append(c);
                }
            } else if (c == '"' && campo.toString().isBlank()) {
                campo.setLength(0);
                entreAspas = true;
            } else if (c == SEPARADOR) {
                campos.add(campo.toString());
                campo.setLength(0);
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < texto.length() && texto.charAt(i + 1) == '\n') {
                    i++;
                }
                campos.add(campo.toString());
                registros.add(new Registro(inicio, campos));
                campos = new ArrayList<>();
                campo.setLength(0);
                linha++;
                inicio = linha;
            } else {
                campo.append(c);
            }
            i++;
        }
        if (!campos.isEmpty() || !campo.isEmpty()) {
            campos.add(campo.toString());
            registros.add(new Registro(inicio, campos));
        }
        return registros;
    }
}
