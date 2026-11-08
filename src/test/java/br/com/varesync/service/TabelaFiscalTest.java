package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.model.CodigoCest;
import br.com.varesync.model.CodigoNcm;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Leitura das tabelas NCM (CSV e JSON oficial do Siscomex) e CEST (F19.3). Sem banco. */
class TabelaFiscalTest {

    /** Trecho no formato do JSON da tabela NCM vigente (Siscomex): capítulo, posição, subposições e itens. */
    static final String JSON_SISCOMEX = """
            {
              "Data_Ultima_Atualizacao_NCM": "Vigente a partir de 01/04/2025",
              "Ato": "Resolução Gecex nº 272/2021",
              "Nomenclaturas": [
                {"Codigo": "22", "Descricao": "Bebidas, líquidos alcoólicos e vinagres.", "Data_Inicio": "01/04/2022",
                 "Data_Fim": "31/12/9999", "Tipo_Ato_Ini": "Res Camex", "Numero_Ato_Ini": "000272", "Ano_Ato_Ini": "2021"},
                {"Codigo": "22.02", "Descricao": "Águas, incluindo as águas minerais e as águas gaseificadas, adicionadas de açúcar..."},
                {"Codigo": "2202.10.00", "Descricao": "- Águas, incluindo as águas minerais e as águas gaseificadas, adicionadas de açúcar ou de outros edulcorantes ou aromatizadas"},
                {"Codigo": "2202.9", "Descricao": "- Outras:"},
                {"Codigo": "2202.91.00", "Descricao": "-- Cerveja sem álcool"},
                {"Codigo": "2203.00.00", "Descricao": "Cervejas de malte."},
                {"Codigo": "0901.21.00", "Descricao": "-- Não descafeinado"}
              ]
            }
            """;

    private static byte[] utf8(String texto) {
        return texto.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void jsonOficialSoComOsCodigosDe8DigitosSemPontos() {
        TabelaFiscal.LeituraNcm leitura = TabelaFiscal.lerNcm(utf8(JSON_SISCOMEX));
        assertThat(leitura.formato()).isEqualTo("JSON");
        assertThat(leitura.pulados()).as("capítulo 22, posição 22.02 e subposição 2202.9").isEqualTo(3);
        assertThat(leitura.codigos()).extracting(CodigoNcm::codigo)
                .containsExactly("22021000", "22029100", "22030000", "09012100");
        assertThat(leitura.codigos().get(1).descricao()).as("sem os traços do nível").isEqualTo("Cerveja sem álcool");
    }

    @Test
    void jsonComoListaNaRaizEComMarcacoes() {
        TabelaFiscal.LeituraNcm leitura = TabelaFiscal.lerNcm(utf8("""
                [{"codigo": "2203.00.00", "descricao": "<i>Cervejas</i> de malte."}]
                """));
        assertThat(leitura.codigos()).containsExactly(new CodigoNcm("22030000", "Cervejas de malte."));
    }

    @Test
    void jsonSemNomenclaturasOuInvalido() {
        assertThatThrownBy(() -> TabelaFiscal.lerNcm(utf8("{\"Ato\": \"x\"}"))).hasMessageContaining("Nomenclaturas");
        assertThatThrownBy(() -> TabelaFiscal.lerNcm(utf8("{\"Nomenclaturas\": [")))
                .hasMessageContaining("não é um JSON válido");
        assertThatThrownBy(() -> TabelaFiscal.lerNcm(utf8("{\"Nomenclaturas\": [{\"Codigo\": \"22\"}]}")))
                .hasMessageContaining("nenhum NCM de 8 dígitos");
    }

    @Test
    void csvComCabecalhoPontosENiveisIntermediarios() {
        TabelaFiscal.LeituraNcm leitura = TabelaFiscal.lerNcm(utf8("""
                ﻿código;descrição
                22;Bebidas
                2203.00.00;Cervejas de malte.
                22021000;"Águas; com açúcar"

                0901.21.00;-- Não descafeinado
                """));
        assertThat(leitura.formato()).isEqualTo("CSV");
        assertThat(leitura.pulados()).isEqualTo(1);
        assertThat(leitura.codigos()).containsExactly(new CodigoNcm("22030000", "Cervejas de malte."),
                new CodigoNcm("22021000", "Águas; com açúcar"), new CodigoNcm("09012100", "Não descafeinado"));
    }

    @Test
    void csvEmWindows1252() {
        byte[] conteudo = "2203.00.00;Cervejas de malte (ação)\r\n".getBytes(PlanilhaProdutos.WINDOWS_1252);
        assertThat(TabelaFiscal.lerNcm(conteudo).codigos())
                .containsExactly(new CodigoNcm("22030000", "Cervejas de malte (ação)"));
    }

    @Test
    void csvComErroNaoImportaNadaEApontaALinha() {
        assertThatThrownBy(() -> TabelaFiscal.lerNcm(utf8("""
                codigo;descricao
                2203.00.00;Cervejas
                22O3.00.00;letra O no lugar do zero
                2203.00.00;repetido
                """)))
                .hasMessageContaining("Nada foi importado: 2 erros")
                .hasMessageContaining("linha 3: código \"22O3.00.00\" não é um NCM")
                .hasMessageContaining("linha 4: o NCM 2203.00.00 repete o da linha 2");
        assertThatThrownBy(() -> TabelaFiscal.lerNcm(utf8("  "))).hasMessage("O arquivo está vazio.");
    }

    @Test
    void cestComVariosNcmEPrefixos() {
        var linhas = TabelaFiscal.lerCest(utf8("""
                cest;ncm;descricao
                03.001.00;2201.10.00, 2201.90.00;Água mineral
                03.007.00;2202.10.00;Refrigerante
                17.099.00;0901;Café
                03.007.00;2202.10.00;Refrigerante repetido
                """));
        assertThat(linhas).containsExactly(new CodigoCest("0300100", "22011000", "Água mineral"),
                new CodigoCest("0300100", "22019000", "Água mineral"),
                new CodigoCest("0300700", "22021000", "Refrigerante"),
                new CodigoCest("1709900", "0901", "Café"));
    }

    @Test
    void cestComErro() {
        assertThatThrownBy(() -> TabelaFiscal.lerCest(utf8("""
                03.001.00;2201.10.00;Água
                03.001;2201;curto
                03.002.00;;sem NCM
                03.003.00;2;NCM de 1 dígito
                """)))
                .hasMessageContaining("3 erros")
                .hasMessageContaining("linha 2: CEST \"03.001\" não tem 7 dígitos")
                .hasMessageContaining("linha 3: falta o NCM do CEST 03.002.00")
                .hasMessageContaining("linha 4: NCM \"2\" inválido");
    }
}
