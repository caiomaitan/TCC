package br.com.varesync.service.nfce;

import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.RegimeTributario;
import br.com.varesync.model.TipoEmissaoNfce;
import br.com.varesync.model.TributoItem;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

/** Notas de exemplo para os testes da montagem (F23.3). */
final class NotasTeste {

    static final OffsetDateTime EMISSAO = OffsetDateTime.of(2026, 11, 14, 10, 30, 0, 0, ZoneOffset.ofHours(-3));

    private NotasTeste() {
    }

    static BigDecimal v(String valor) {
        return new BigDecimal(valor);
    }

    static EmitenteNfce emitente(RegimeTributario regime) {
        return new EmitenteNfce(CertificadoTeste.CNPJ, "EMPRESA TESTE LTDA", "Mercado Bom Preço", "Rua das Flores",
                "100", "Sala 2", "Centro", "3543402", "Ribeirão Preto", "SP", 35, "14010100", "1633334444",
                "123456789012", regime);
    }

    /** Simples, CSOSN 102, PIS e COFINS 49, com os tributos aproximados. */
    static TributoItem simples102(String ncm) {
        return new TributoItem(ncm, null, 0, "5102", null, "102", null, "49", "49", null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, null, null, null, null, null, null,
                v("1.35"), v("1.80"), v("0.00"), "26.2.A");
    }

    /** Normal, CST 00 com 18%, PIS e COFINS 01 e IBS/CBS 000, como no aceite da F22 (2 × R$ 5,00 − R$ 0,50). */
    static TributoItem normalCst00() {
        return new TributoItem("22030000", null, 0, "5102", "00", null, null, "01", "01", "000", "000001",
                v("9.50"), v("18.00"), null, v("1.71"), BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, null, null,
                v("7.79"), v("1.65"), v("0.13"), v("7.79"), v("7.60"), v("0.59"),
                v("7.07"), v("0.9"), v("0.1"), v("0"), null, v("0.06"), v("0.01"), v("0.00"),
                null, null, null, null);
    }

    static NotaNfce.Identificacao identificacao(int numero, TipoEmissaoNfce tipo) {
        String cnf = "87654321";
        String chave = ChaveAcesso.montar(35, YearMonth.from(EMISSAO), CertificadoTeste.CNPJ, 1, numero,
                tipo.codigo(), cnf);
        boolean contingencia = tipo == TipoEmissaoNfce.CONTINGENCIA_OFFLINE;
        return new NotaNfce.Identificacao(1, numero, cnf, tipo, EMISSAO, contingencia ? EMISSAO : null,
                contingencia ? "SEFAZ indisponivel - sem resposta da autorizacao" : null, chave);
    }

    /**
     * Simples: arroz com desconto (2 × R$ 5,49 − R$ 1,00), queijo da balança (0,345 kg por R$ 13,77, código de uso
     * interno que começa por 2) e uma caixa de 12 refrigerantes por R$ 59,90 (preço da embalagem); pagos com R$ 50,00
     * em dinheiro (troco) e o resto no cartão de débito.
     */
    static NotaNfce simplesComDescontoBalancaEmbalagem(TipoEmissaoNfce tipo, String documento) {
        List<NotaNfce.Item> itens = List.of(
                new NotaNfce.Item(1, 11, "Arroz Tipo 1 5 kg", "7891000315507", "UN", v("2.000"), v("10.98"),
                        v("1.00"), simples102("10063021")),
                new NotaNfce.Item(2, 12, "Queijo Mussarela kg", "2001230013776", "KG", v("0.345"), v("13.77"),
                        v("0.00"), simples102("04061010")),
                new NotaNfce.Item(3, 13, "Refrigerante Cola 350 ml", "7891234567895", "UN", v("12.000"), v("59.90"),
                        v("0.00"), simples102("22021000")));
        // total = 10,98 + 13,77 + 59,90 − 1,00 = 83,65
        List<NotaNfce.Pagamento> pagamentos = List.of(
                new NotaNfce.Pagamento("01", "Dinheiro", v("43.65")),
                new NotaNfce.Pagamento("04", "Cartão de débito", v("40.00")));
        return new NotaNfce(identificacao(123, tipo), emitente(RegimeTributario.SIMPLES), documento, itens,
                pagamentos, v("6.35"), v("83.65"), Configuracoes.RespTecnico.VAZIO, "2.0.0-SNAPSHOT");
    }

    static NotaNfce normal(TributoItem tributo, BigDecimal desconto) {
        List<NotaNfce.Item> itens = List.of(new NotaNfce.Item(1, 21, "Cerveja Pilsen 350 ml", "7891234567895", "UN",
                v("2.000"), v("10.00"), desconto, tributo));
        BigDecimal total = v("10.00").subtract(desconto);
        return new NotaNfce(identificacao(124, TipoEmissaoNfce.NORMAL), emitente(RegimeTributario.NORMAL), null,
                itens, List.of(new NotaNfce.Pagamento("17", "PIX", total)), BigDecimal.ZERO, total,
                new Configuracoes.RespTecnico("11222333000181", "Fulano de Tal", "suporte@exemplo.com.br",
                        "16999998888"), "2.0.0");
    }
}
