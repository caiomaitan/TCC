package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Leitura do XML da NF-e (6.11): emitente, nota, itens, unidade tributável, SEM GTIN e XML inválido. Sem banco. */
class LeitorNfeTest {

    static byte[] recurso(String nome) {
        try (InputStream in = LeitorNfeTest.class.getResourceAsStream("/nfe/" + nome)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void notaDeDistribuicaoComEmitenteNotaEItens() {
        LeitorNfe.Nota nota = LeitorNfe.ler(recurso("nfe-distribuidora.xml"));
        assertThat(nota.chave()).isEqualTo("35261011222333000181550010000012341000012345");
        assertThat(nota.numero()).isEqualTo("1234");
        assertThat(nota.emissao()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(nota.valorTotal()).isEqualByComparingTo("367.00");
        assertThat(nota.emitente()).isEqualTo(new LeitorNfe.Emitente("11222333000181",
                "Distribuidora Ribeirão Alimentos Ltda", "Distribuidora Ribeirão", "Ribeirão Preto", "SP",
                "1633334444"));
        assertThat(nota.itens()).hasSize(4);

        LeitorNfe.Item cafe = nota.itens().get(0);
        assertThat(cafe).isEqualTo(new LeitorNfe.Item(1, "7891000315507", "CAFE TORRADO MOIDO 500G", "UN",
                new java.math.BigDecimal("10.0000"), new java.math.BigDecimal("125.00"), false,
                new LeitorNfe.Comercial("CAFE500", "UN", new java.math.BigDecimal("10.0000"),
                        new java.math.BigDecimal("12.5000000000"))));
        LeitorNfe.Item queijo = nota.itens().get(1);
        assertThat(queijo.codigoBarras()).isNull(); // SEM GTIN
        assertThat(queijo.quantidade()).isEqualByComparingTo("3.5");
        assertThat(queijo.unidade()).isEqualTo("KG");
    }

    /** F15.3: as duplicatas de cobr/dup viram parcelas; a sem vencimento fica de fora. */
    @Test
    void duplicatasDaCobrancaComVencimento() {
        LeitorNfe.Nota nota = LeitorNfe.ler(recurso("nfe-distribuidora.xml"));
        assertThat(nota.duplicatas()).containsExactly(
                new LeitorNfe.Duplicata("001", LocalDate.of(2026, 11, 4), new java.math.BigDecimal("200.00")),
                new LeitorNfe.Duplicata("002", LocalDate.of(2026, 12, 4), new java.math.BigDecimal("167.00")));
        assertThat(LeitorNfe.ler(recurso("nfe-antiga-sem-proc.xml")).duplicatas()).isEmpty();
    }

    @Test
    void unidadeTributavelDiferenteComEanTributavelUsaQTrib() {
        LeitorNfe.Item caixa = LeitorNfe.ler(recurso("nfe-distribuidora.xml")).itens().get(2);
        assertThat(caixa.unidadeTributavel()).isTrue();
        assertThat(caixa.codigoBarras()).isEqualTo("7896004000015"); // EAN da lata, não o DUN-14 da caixa
        assertThat(caixa.unidade()).isEqualTo("UN");
        assertThat(caixa.quantidade()).isEqualByComparingTo("48"); // e não 2 CX
        assertThat(caixa.valorTotal()).isEqualByComparingTo("120.00");
    }

    @Test
    void formatoAntigoSemProcComDEmiECpfEEanVazio() {
        LeitorNfe.Nota nota = LeitorNfe.ler(recurso("nfe-antiga-sem-proc.xml"));
        assertThat(nota.numero()).isEqualTo("77");
        assertThat(nota.emissao()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(nota.emitente().documento()).isEqualTo("52998224725");
        assertThat(nota.emitente().nomeFantasia()).isEmpty();
        assertThat(nota.itens()).singleElement().satisfies(i -> {
            assertThat(i.codigoBarras()).isNull();
            assertThat(i.unidade()).isEqualTo("DZ");
            assertThat(i.unidadeTributavel()).isFalse();
        });
    }

    @Test
    void eanComDigitoErradoOuSemGtinViraNulo() {
        assertThat(LeitorNfe.ean("7891000315507")).isEqualTo("7891000315507");
        assertThat(LeitorNfe.ean("7891000315508")).isNull();
        assertThat(LeitorNfe.ean("SEM GTIN")).isNull();
        assertThat(LeitorNfe.ean(" ")).isNull();
        assertThat(LeitorNfe.ean("17891000315504")).isEqualTo("17891000315504"); // DUN-14
    }

    @Test
    void arquivoQueNaoEXmlDeNfe() {
        assertThatThrownBy(() -> LeitorNfe.ler("não é xml".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(NegocioException.class).hasMessage("O arquivo não é um XML válido de NF-e.");
        assertThatThrownBy(() -> LeitorNfe.ler("<pedido><numero>1</numero></pedido>".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("não é o XML de uma NF-e");
        String semItens = "<NFe><infNFe><ide><nNF>1</nNF></ide><emit><CNPJ>11222333000181</CNPJ></emit>"
                + "</infNFe></NFe>";
        assertThatThrownBy(() -> LeitorNfe.ler(semItens.getBytes(StandardCharsets.UTF_8)))
                .hasMessage("O XML da NF-e não tem itens.");
    }

    @Test
    void doctypeEEntidadesExternasSaoRecusados() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE NFe [<!ENTITY x SYSTEM \"file:///c:/windows/win.ini\">]>"
                + "<NFe><infNFe><ide><nNF>&x;</nNF></ide></infNFe></NFe>";
        assertThatThrownBy(() -> LeitorNfe.ler(xxe.getBytes(StandardCharsets.UTF_8)))
                .hasMessage("O arquivo não é um XML válido de NF-e.");
    }
}
