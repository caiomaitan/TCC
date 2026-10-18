package br.com.varesync.report;

import br.com.varesync.service.ErroInesperadoException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Exportação CSV genérica (7.4): separador {@code ;}, decimal com vírgula, UTF-8 com BOM (o Excel reconhece os
 * acentos). Primeira linha com os títulos, depois as linhas e a linha de totais. Valores numéricos vão sem "R$",
 * sem "%" e sem separador de milhar, para o Excel ler como número.
 */
public final class ExportadorCsv {

    static final String BOM = "﻿";
    private static final String SEPARADOR = ";";
    private static final String FIM_DE_LINHA = "\r\n";

    private ExportadorCsv() {
    }

    public static <T> String gerar(ResultadoRelatorio<T> resultado) {
        StringBuilder csv = new StringBuilder(BOM);
        List<Coluna<T>> colunas = resultado.colunas();
        linha(csv, colunas.stream().map(Coluna::titulo).toList());
        for (T item : resultado.linhas()) {
            linha(csv, colunas.stream().map(c -> c.csv(item)).toList());
        }
        if (resultado.temTotais()) {
            List<String> totais = new ArrayList<>();
            boolean rotulo = false;
            for (Coluna<T> c : colunas) {
                if (c.totalizavel()) {
                    totais.add(c.csvTotal(resultado.linhas()));
                } else {
                    totais.add(rotulo ? "" : "Total");
                    rotulo = true;
                }
            }
            linha(csv, totais);
        }
        return csv.toString();
    }

    public static <T> void salvar(ResultadoRelatorio<T> resultado, Path destino) {
        try {
            Files.writeString(destino, gerar(resultado), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ErroInesperadoException(e);
        }
    }

    private static void linha(StringBuilder csv, List<String> valores) {
        for (int i = 0; i < valores.size(); i++) {
            if (i > 0) {
                csv.append(SEPARADOR);
            }
            csv.append(campo(valores.get(i)));
        }
        csv.append(FIM_DE_LINHA);
    }

    /** Entre aspas quando tem separador, aspas ou quebra de linha (RFC 4180). */
    static String campo(String valor) {
        if (valor == null) {
            return "";
        }
        if (valor.contains(SEPARADOR) || valor.contains("\"") || valor.contains("\n") || valor.contains("\r")) {
            return "\"" + valor.replace("\"", "\"\"") + "\"";
        }
        return valor;
    }
}
