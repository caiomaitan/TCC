package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.model.LinhaIbpt;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Leitura da tabela do IBPT (F22.3), sem banco. */
class TabelaIbptTest {

    static final String CABECALHO = "codigo;ex;tipo;descricao;nacionalfederal;importadosfederal;estadual;municipal;"
            + "vigenciainicio;vigenciafim;chave;versao;fonte\n";

    /** Três linhas de NCM (uma com ex), uma de NBS e uma de LC 116, como no arquivo do IBPT. */
    static final String ARQUIVO = CABECALHO
            + "22021000;;0;Água mineral com adição de açúcar;13,45;15,45;18,00;0,00;01/10/2026;31/12/2026;A1B2C3;"
            + "26.2.A;IBPT/empresometro.com.br\n"
            + "22021000;01;0;Refrigerante (ex 01);11.50;13.50;18.00;0.00;01/10/2026;31/12/2026;A1B2C3;26.2.A;"
            + "IBPT/empresometro.com.br\n"
            + "09012100;;0;Café torrado, não descafeinado;8,03;10,03;7,00;0,00;01/10/2026;31/12/2026;A1B2C3;26.2.A;"
            + "IBPT/empresometro.com.br\n"
            + "1.0101;;1;Serviço de construção (NBS);13,45;0;0;2,00;01/10/2026;31/12/2026;A1B2C3;26.2.A;"
            + "IBPT/empresometro.com.br\n"
            + "0101;;2;Análise e desenvolvimento de sistemas (LC 116);13,45;0;0;2,00;01/10/2026;31/12/2026;A1B2C3;"
            + "26.2.A;IBPT/empresometro.com.br\n";

    static byte[] iso(String texto) {
        return texto.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void leOArquivoDoIbptEmIso88591SoComAsLinhasDeNcm() {
        TabelaFiscal.LeituraIbpt l = TabelaFiscal.lerIbpt(iso(ARQUIVO));

        assertThat(l.linhas()).hasSize(3);
        assertThat(l.puladas()).as("NBS e LC 116 ficam de fora").isEqualTo(2);
        assertThat(l.versao()).isEqualTo("26.2.A");
        assertThat(l.inicio()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(l.fim()).isEqualTo(LocalDate.of(2026, 12, 31));
        LinhaIbpt agua = l.linhas().get(0);
        assertThat(agua.codigo()).isEqualTo("22021000");
        assertThat(agua.ex()).isEmpty();
        assertThat(agua.descricao()).isEqualTo("Água mineral com adição de açúcar");
        assertThat(agua.nacionalFederal()).isEqualByComparingTo("13.45");
        assertThat(agua.importadosFederal()).isEqualByComparingTo("15.45");
        assertThat(agua.estadual()).isEqualByComparingTo("18");
        assertThat(agua.municipal()).isEqualByComparingTo("0");
        assertThat(agua.chave()).isEqualTo("A1B2C3");
        assertThat(agua.fonte()).isEqualTo("IBPT/empresometro.com.br");
        assertThat(l.linhas().get(1).ex()).isEqualTo("01");
        assertThat(l.linhas().get(1).nacionalFederal()).as("decimal com ponto").isEqualByComparingTo("11.5");
    }

    @Test
    void aceitaUtf8ComBomEColunasEmOutraOrdem() {
        String arquivo = "﻿Tipo;Codigo;Descricao;Estadual;Municipal;NacionalFederal;ImportadosFederal;"
                + "VigenciaInicio;VigenciaFim\r\n"
                + "0;2202.10.00;Água;18;0;13,45;15,45;01/10/2026;31/12/2026\r\n";
        TabelaFiscal.LeituraIbpt l = TabelaFiscal.lerIbpt(arquivo.getBytes(StandardCharsets.UTF_8));
        assertThat(l.linhas()).singleElement().satisfies(linha -> {
            assertThat(linha.codigo()).isEqualTo("22021000");
            assertThat(linha.descricao()).isEqualTo("Água");
            assertThat(linha.ex()).isEmpty();
            assertThat(linha.versao()).isEmpty();
        });
    }

    @Test
    void recusaOArquivoForaDoLeiauteOuComErroNaLinha() {
        assertThatThrownBy(() -> TabelaFiscal.lerIbpt(iso("codigo;descricao\n22021000;Água\n")))
                .isInstanceOf(NegocioException.class).hasMessageContaining("leiaute da tabela do IBPT")
                .hasMessageContaining("tipo");
        assertThatThrownBy(() -> TabelaFiscal.lerIbpt(iso(CABECALHO
                + "2202100;;0;Curto;13,45;15,45;18;0;01/10/2026;31/12/2026;X;1;IBPT\n"
                + "22021000;;0;Data ruim;13,45;15,45;18;0;31/02/2026;31/12/2026;X;1;IBPT\n"
                + "22021000;;0;Ok;abc;15,45;18;0;01/10/2026;31/12/2026;X;1;IBPT\n")))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Nada foi importado: 3 erros")
                .hasMessageContaining("linha 2: NCM \"2202100\" não tem 8 dígitos")
                .hasMessageContaining("linha 3: vigenciainicio \"31/02/2026\" não é data")
                .hasMessageContaining("linha 4: nacionalfederal \"abc\" não é número");
        assertThatThrownBy(() -> TabelaFiscal.lerIbpt(iso(CABECALHO
                + "22021000;;0;A;13,45;15,45;18;0;01/10/2026;31/12/2026;X;1;IBPT\n"
                + "22021000;;0;B;13,45;15,45;18;0;01/10/2026;31/12/2026;X;1;IBPT\n")))
                .hasMessageContaining("linha 3: o NCM 2202.10.00 repete o da linha 2");
        assertThatThrownBy(() -> TabelaFiscal.lerIbpt(iso(CABECALHO
                + "1.0101;;1;Só serviço;13,45;0;0;2;01/10/2026;31/12/2026;X;1;IBPT\n")))
                .hasMessageContaining("nenhuma linha de NCM");
        assertThatThrownBy(() -> TabelaFiscal.lerIbpt(iso(CABECALHO
                + "22021000;;0;A;13,45;15,45;18;0;31/12/2026;01/10/2026;X;1;IBPT\n")))
                .hasMessageContaining("vigência termina antes de começar");
        assertThatThrownBy(() -> TabelaFiscal.lerIbpt(new byte[0])).hasMessageContaining("vazio");
    }

    @Test
    void ufNoNomeDoArquivo() {
        assertThat(TabelaFiscal.ufDoArquivo("TabelaIBPTaxSP26.2.A.csv")).contains("SP");
        assertThat(TabelaFiscal.ufDoArquivo("tabelaibptaxmg26.2.a.csv")).contains("MG");
        assertThat(TabelaFiscal.ufDoArquivo("TabelaIBPTaxXX26.2.A.csv")).isEmpty();
        assertThat(TabelaFiscal.ufDoArquivo("ibpt.csv")).isEmpty();
        assertThat(TabelaFiscal.ufDoArquivo(null)).isEmpty();
    }
}
