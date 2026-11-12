package br.com.varesync.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.model.ConteudoLiquido;
import br.com.varesync.model.Etiqueta;
import br.com.varesync.service.NegocioException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Texto ZPL ou DPL da impressora térmica (F18.1, aceite "Térmica"): variáveis, {NOME:n}, retirada dos acentos, uma
 * etiqueta por unidade e envio pela impressora falsa (nenhum teste manda nada para impressora de verdade). Sem banco.
 */
class ModeloTermicaTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 11, 6);

    private static Etiqueta acucar() {
        return new Etiqueta(12, "Açúcar Cristal União 1 kg", "UN", new BigDecimal("4.79"), new BigDecimal("5.49"),
                3L, LocalDate.of(2026, 11, 13), "7891000315507", null, ConteudoLiquido.SEM, HOJE, null, true, 1);
    }

    private static Etiqueta queijo() {
        return new Etiqueta(30, "Queijo Mussarela", "KG", new BigDecimal("39.90"), null, null, null, null, 123,
                ConteudoLiquido.SEM, HOJE, null, true, 1);
    }

    @Test
    void zplDeUmProdutoEmPromocaoConfereComOEsperado() {
        String modelo = """
                ^XA
                ^FO20,15^A0N,30,26^FD{NOME:20}^FS
                ^FO20,85^FD{PRECO_DE}^FS
                ^FO20,112^FDR$ {PRECO}^FS
                ^FO360,140^FD{UNIDADE}^FS
                ^FO20,190^BEN,50,Y,N^FD{CODBARRAS}^FS
                ^FO330,195^FD{VALIDADE}^FS
                ^FO330,225^FDCod. {CODIGO} {PLU}^FS
                ^XZ""";
        assertThat(ModeloTermica.aplicar(modelo, acucar())).isEqualTo(
                "^XA\r\n"
                        + "^FO20,15^A0N,30,26^FDAcucar Cristal Uniao^FS\r\n"
                        + "^FO20,85^FD5,49^FS\r\n"
                        + "^FO20,112^FDR$ 4,79^FS\r\n"
                        + "^FO360,140^FDUN^FS\r\n"
                        + "^FO20,190^BEN,50,Y,N^FD7891000315507^FS\r\n"
                        + "^FO330,195^FD13/11/2026^FS\r\n"
                        + "^FO330,225^FDCod. 12 ^FS\r\n"
                        + "^XZ");
    }

    @Test
    void pesavelSemPromocaoDeixaVaziasAsVariaveisQueNaoSeAplicam() {
        String modelo = "[{NOME}][{PRECO}][{PRECO_DE}][{UNIDADE}][{CODBARRAS}][{PLU}][{CODIGO}][{VALIDADE}]";
        assertThat(ModeloTermica.aplicar(modelo, queijo()))
                .isEqualTo("[Queijo Mussarela][39,90][][KG][][123][30][]");
    }

    /** F20.3: {QTD_ATACADO} e {PRECO_ATACADO}; sem faixa, a linha delas não sai (F21.5). */
    @Test
    void faixaDeAtacadoNasVariaveisDaTermica() {
        String modelo = "^FDA partir de {QTD_ATACADO} un: {PRECO_ATACADO}^FS|{PRECO}";
        Etiqueta comFaixa = new Etiqueta(12, "Refrigerante", "UN", new BigDecimal("5.49"), null, null, null, null,
                null, ConteudoLiquido.SEM, HOJE, null, true, 1, new BigDecimal("6.000"), new BigDecimal("4.99"));
        assertThat(ModeloTermica.aplicar(modelo, comFaixa)).isEqualTo("^FDA partir de 6 un: 4,99^FS|5,49");
        // F21.5: sem faixa, a linha do modelo que usa as variáveis de atacado não sai (antes saía com elas vazias).
        assertThat(ModeloTermica.aplicar("[{QTD_ATACADO}][{PRECO_ATACADO}]", queijo())).isEmpty();
    }

    @Test
    void nomeCortadoSemEspacoNoFimETextoSemAcentos() {
        assertThat(ModeloTermica.aplicar("{NOME:7}|{NOME:0}|{NOME:999}", acucar()))
                .isEqualTo("Acucar||Acucar Cristal Uniao 1 kg");
        assertThat(ModeloTermica.aplicar("Promoção ÇÃO º ½ €", acucar())).isEqualTo("Promocao CAO o 1?2 ?");
    }

    @Test
    void comandosNoNomeNaoViramComandoDaImpressora() {
        Etiqueta estranho = new Etiqueta(1, "Pão ^XZ~JA doce\u0007", "UN", BigDecimal.ONE, null, null, null, null,
                null, ConteudoLiquido.SEM, HOJE, null, true, 1);
        assertThat(ModeloTermica.aplicar("^FD{NOME}^FS", estranho)).isEqualTo("^FDPao XZJA doce^FS");
    }

    @Test
    void variavelDesconhecidaOuTamanhoForaDoNomeFicamComoEstao() {
        assertThat(ModeloTermica.aplicar("{PESO} {PRECO:3} {nome}", acucar())).isEqualTo("{PESO} {PRECO:3} {nome}");
    }

    @Test
    void dplComStxEUmaEtiquetaPorUnidade() {
        String modelo = "<STX>L\nD11\n1911A1001900020{NOME:10}\nE";
        String texto = ModeloTermica.aplicar(modelo, List.of(acucar().comQuantidade(2), queijo()));
        String acucar = "\u0002L\r\nD11\r\n1911A1001900020Acucar Cri\r\nE\r\n";
        String queijo = "\u0002L\r\nD11\r\n1911A1001900020Queijo Mus\r\nE\r\n";
        assertThat(texto).isEqualTo(acucar + acucar + queijo);
    }

    @Test
    void exemplosZplEDplPreenchem() {
        String zpl = ModeloTermica.aplicar(ImpressaoTermica.Exemplo.ZPL.texto(), acucar());
        assertThat(zpl).startsWith("^XA").contains("^FDAcucar Cristal Uniao 1 kg^FS", "R$ 4,79", "7891000315507")
                .doesNotContain("{");
        String dpl = ModeloTermica.aplicar(ImpressaoTermica.Exemplo.DPL.texto(), acucar());
        assertThat(dpl).startsWith("\u0002L\r\n").contains("Acucar Cristal Uniao 1 kg", "7891000315507")
                .doesNotContain("{", "<STX>");
    }

    /** F21.5: os exemplos trazem a linha de atacado; sem faixa, ela não sai (nada de "A partir de  un:"). */
    @Test
    void exemplosTrazemALinhaDeAtacadoSoComFaixa() {
        Etiqueta fardo = new Etiqueta(40, "Refrigerante Cola 2 L", "UN", new BigDecimal("8.99"), null, null, null,
                "7894900011517", null, ConteudoLiquido.SEM, HOJE, null, true, 1, new BigDecimal("6.000"),
                new BigDecimal("7.99"));
        for (ImpressaoTermica.Exemplo exemplo : ImpressaoTermica.Exemplo.values()) {
            assertThat(exemplo.texto()).as(exemplo.name()).contains("{QTD_ATACADO}", "{PRECO_ATACADO}");
            assertThat(ModeloTermica.aplicar(exemplo.texto(), fardo)).as(exemplo.name())
                    .contains("A partir de 6 UN: R$ 7,99");
            String semFaixa = ModeloTermica.aplicar(exemplo.texto(), acucar());
            assertThat(semFaixa).as(exemplo.name()).doesNotContain("A partir de", "{");
            assertThat(semFaixa.lines().count()).as(exemplo.name())
                    .isEqualTo(exemplo.texto().lines().count() - 1);
        }
    }

    @Test
    void linhaDeAtacadoSaiInteiraSemFaixa() {
        String modelo = "A\n{NOME:5} {QTD_ATACADO}\nB {PRECO_ATACADO}\nC";
        assertThat(ModeloTermica.aplicar(modelo, acucar())).isEqualTo("A\r\nC");
        assertThat(ModeloTermica.semLinhasDeAtacado("x {QTD_ATACADO}")).isEmpty();
        assertThat(ModeloTermica.semLinhasDeAtacado("{PRECO} {UNIDADE}\r\n")).isEqualTo("{PRECO} {UNIDADE}\r\n");
    }

    @Test
    void envioPelaImpressoraFalsa() {
        List<String> enviados = new ArrayList<>();
        ImpressaoTermica termica = new ImpressaoTermica((impressora, dados) ->
                enviados.add(impressora + "|" + new String(dados, StandardCharsets.US_ASCII)));
        assertThat(termica.imprimir(" Zebra GC420t ", "{PRECO};", List.of(acucar().comQuantidade(3)))).isEqualTo(3);
        assertThat(enviados).containsExactly("Zebra GC420t|4,79;\r\n4,79;\r\n4,79;\r\n");

        termica.imprimirTeste("Zebra", "{NOME:13} {PRECO} {PRECO_DE} {VALIDADE}", HOJE);
        assertThat(enviados.get(1)).isEqualTo("Zebra|PRODUTO TESTE 12,90 14,90 13/11/2026\r\n");

        assertThatThrownBy(() -> termica.imprimir("", "{NOME}", List.of(acucar())))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Escolha a impressora térmica");
        assertThatThrownBy(() -> termica.imprimir("Zebra", " ", List.of(acucar())))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Cadastre o modelo");
        assertThatThrownBy(() -> termica.imprimir("Zebra", "{NOME}", List.of(acucar().comQuantidade(0))))
                .isInstanceOf(NegocioException.class).hasMessageContaining("entre 1 e 999");
        assertThat(enviados).hasSize(2);
    }
}
