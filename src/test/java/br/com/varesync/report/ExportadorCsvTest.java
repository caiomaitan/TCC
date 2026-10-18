package br.com.varesync.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** CSV genérico (7.4): ";", decimal com vírgula, UTF-8 com BOM, títulos, linhas e totais. */
class ExportadorCsvTest {

    @Test
    void csvAbreNoExcelComAcentosENumeros() {
        String csv = ExportadorCsv.gerar(DadosFicticios.r03());
        String[] linhas = csv.substring(1).split("\r\n");

        assertThat(csv).startsWith(ExportadorCsv.BOM);
        assertThat(linhas[0]).isEqualTo("Código;Produto;Categoria;Un.;Saldo;Mínimo;Custo médio;Valor em estoque");
        assertThat(linhas[1]).isEqualTo("1;Açúcar refinado 1 kg;Mercearia;UN;10;15;3,6000;36,00");
        assertThat(linhas[2]).isEqualTo("2;Banana prata;Hortifruti;KG;-2,500;5,000;3,4000;-8,50");
        assertThat(linhas[3]).isEqualTo("3;\"Café; \"\"especial\"\" 500 g\";;UN;4;0;14,3000;57,20");
        assertThat(linhas[4]).isEqualTo("Total;;;;;;;93,20");
        assertThat(linhas).hasSize(5);
    }

    @Test
    void percentualEDataSemSimbolos() {
        String csv = ExportadorCsv.gerar(DadosFicticios.r01(true));
        assertThat(csv).contains("101;01/09/2026 11:05;Maria Oliveira;100,00;2,50;50,00;50,00;50,00\r\n");
        assertThat(csv).endsWith("Total;;;1100,00;2,50;950,00;150,00;13,64\r\n");
    }

    @Test
    void salvaEmUtf8ComBom() throws Exception {
        Path destino = Files.createTempFile("relatorio", ".csv");
        try {
            ExportadorCsv.salvar(DadosFicticios.r05(), destino);
            byte[] bytes = Files.readAllBytes(destino);
            assertThat(bytes[0]).isEqualTo((byte) 0xEF);
            assertThat(bytes[1]).isEqualTo((byte) 0xBB);
            assertThat(bytes[2]).isEqualTo((byte) 0xBF);
            assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("Situação");
        } finally {
            Files.deleteIfExists(destino);
        }
    }
}
