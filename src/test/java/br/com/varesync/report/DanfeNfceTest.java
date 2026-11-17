package br.com.varesync.report;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.model.Comprovante;
import br.com.varesync.model.DanfeNfce;
import br.com.varesync.model.LinhaComprovante;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import net.sf.jasperreports.engine.JasperPrint;
import org.junit.jupiter.api.Test;

/**
 * DANFE NFC-e (F23.7): as divisões do manual, os textos de homologação e de contingência, a via do estabelecimento e o
 * QR Code, que é lido de volta pelo ZXing da página gerada pelo JasperReports.
 */
class DanfeNfceTest {

    static final String CHAVE = "35261112345678000195650010000001231876543214";
    static final String QR = "https://www.homologacao.nfce.fazenda.sp.gov.br/qrcode?p=" + CHAVE + "|3|2";
    static final String QR_CONTINGENCIA = "https://www.homologacao.nfce.fazenda.sp.gov.br/qrcode?p="
            + "35261112345678000195650010000001249876543217|3|2|16|10.98|2|52998224725|"
            + "c2Fsdm8gZGUgdW1hIGFzc2luYXR1cmEgZW0gQmFzZTY0IGRlIHRlc3RlIGRvIERBTkZFIE5GQy1l";

    static DanfeNfce danfe(boolean contingencia, String documento, String qr) {
        return new DanfeNfce(new DanfeNfce.Emitente("Empresa Teste Ltda", "12345678000195", "123456789012",
                "Rua das Flores, 100 - Centro - Ribeirão Preto/SP - CEP 14010-100"),
                List.of(new DanfeNfce.Item("1", "Arroz tipo 1 5 kg", new BigDecimal("2.000"), "UN",
                        new BigDecimal("5.49"), new BigDecimal("10.98"))),
                new BigDecimal("10.98"), BigDecimal.ZERO, new BigDecimal("10.98"),
                List.of(new DanfeNfce.Pagamento("Dinheiro", new BigDecimal("20.00"))), new BigDecimal("9.02"), CHAVE,
                "https://www.homologacao.nfce.fazenda.sp.gov.br/consulta", documento, 123, 1,
                OffsetDateTime.parse("2026-11-16T14:30:00-03:00"), contingencia ? null : "135260000000001",
                contingencia ? null : OffsetDateTime.parse("2026-11-16T14:30:02-03:00"), contingencia, false, qr,
                new Comprovante.TributosAproximados(new BigDecimal("1.48"), new BigDecimal("1.98"), BigDecimal.ZERO),
                42);
    }

    private static String texto(DanfeNfce d) {
        return d.texto();
    }

    @Test
    void autorizadaComAsDivisoesDoManual() {
        DanfeNfce d = danfe(false, null, QR);
        String t = texto(d);
        assertThat(t).contains("Empresa Teste Ltda", "CNPJ: 12.345.678/0001-95  IE: 123456789012",
                "Documento Auxiliar da Nota Fiscal", "de Consumidor Eletrônica", "Arroz tipo 1 5 kg",
                "2 UN x R$ 5,49", "Qtde. total de itens", "Valor a pagar R$", "Dinheiro", "Troco R$",
                "Consulte pela Chave de Acesso em", "3526 1112 3456 7800 0195 6500", "1000 0001 2318 7654 3214",
                DanfeNfce.NAO_IDENTIFICADO, "NFC-e nº 000000123  Série 001", "16/11/2026 14:30:00",
                "Protocolo de autorização: 135260000000001", "Data de autorização: 16/11/2026 14:30:02",
                "EMITIDA EM AMBIENTE DE HOMOLOGAÇÃO", "SEM VALOR FISCAL", "Tributos aproximados: Federal R$ 1,48");
        assertThat(t).doesNotContain(DanfeNfce.CONTINGENCIA, DanfeNfce.PENDENTE, DanfeNfce.VIA_ESTABELECIMENTO,
                "NÃO É DOCUMENTO FISCAL");
        assertThat(d.linhas()).filteredOn(l -> l.estilo() == LinhaComprovante.Estilo.QRCODE).singleElement()
                .extracting(LinhaComprovante::esquerda).isEqualTo(QR);
        // Nenhuma linha passa das 48 colunas da bobina.
        assertThat(t.lines()).allMatch(l -> l.length() <= Comprovante.COLUNAS);
    }

    @Test
    void contingenciaSemProtocoloComOsTextosEmDestaqueEAViaDoEstabelecimento() {
        DanfeNfce d = danfe(true, "52998224725", QR_CONTINGENCIA);
        List<LinhaComprovante> linhas = d.linhas();
        assertThat(linhas).filteredOn(l -> l.esquerda().equals(DanfeNfce.CONTINGENCIA)).hasSize(2)
                .allMatch(l -> l.estilo() == LinhaComprovante.Estilo.CENTRO_NEGRITO);
        assertThat(linhas).filteredOn(l -> l.esquerda().equals(DanfeNfce.PENDENTE)).hasSize(2);
        assertThat(texto(d)).contains("CONSUMIDOR - CPF: 529.982.247-25").doesNotContain("Protocolo",
                DanfeNfce.VIA_ESTABELECIMENTO);
        // abaixo do cabeçalho e abaixo da identificação
        int titulo = indice(linhas, "de Consumidor Eletrônica");
        int identificacao = indice(linhas, "NFC-e nº");
        assertThat(indice(linhas, DanfeNfce.CONTINGENCIA)).isGreaterThan(titulo).isLessThan(identificacao);
        assertThat(linhas.subList(identificacao, linhas.size())).anyMatch(l -> l.esquerda()
                .equals(DanfeNfce.CONTINGENCIA));

        DanfeNfce via = d.comoViaDoEstabelecimento();
        assertThat(texto(via)).contains("16/11/2026 14:30:00 - Via do Estabelecimento");
        assertThat(via.nomeArquivo()).isEqualTo("danfe-nfce-123-via-estabelecimento.pdf");
    }

    @Test
    void qrCodeDaPaginaGeradaELidoDeVolta() throws Exception {
        ComprovantePdf pdf = new ComprovantePdf();
        for (String qr : List.of(QR, QR_CONTINGENCIA)) {
            JasperPrint impressao = pdf.preencher(danfe(!qr.equals(QR), null, qr));
            assertThat(LeituraQrCode.lerDaPagina(impressao)).isEqualTo(qr);
        }
        // O PDF sai (a mesma impressão), e o comprovante não fiscal continua sem imagem.
        assertThat(pdf.gerar(danfe(false, null, QR))).startsWith("%PDF".getBytes());
    }

    private static int indice(List<LinhaComprovante> linhas, String inicio) {
        for (int i = 0; i < linhas.size(); i++) {
            if (linhas.get(i).esquerda().startsWith(inicio)) {
                return i;
            }
        }
        return -1;
    }
}
