package br.com.varesync.service.nfce;

import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.TipoEmissaoNfce;
import br.com.varesync.model.TributoItem;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Tudo o que a montagem da NFC-e precisa (F23.3), já lido do banco: a identificação da nota, o emitente, a venda (itens
 * não cancelados com os tributos gravados na F22, pagamentos e troco) e o responsável técnico.
 *
 * @param consumidorDocumento CPF ou CNPJ na nota (só dígitos) ou nulo
 * @param respTecnico         vazio = grupo omitido
 * @param versaoSistema       verProc
 */
public record NotaNfce(Identificacao identificacao, EmitenteNfce emitente, String consumidorDocumento,
                       List<Item> itens, List<Pagamento> pagamentos, BigDecimal troco, BigDecimal total,
                       Configuracoes.RespTecnico respTecnico, String versaoSistema) {

    public NotaNfce {
        itens = List.copyOf(itens);
        pagamentos = List.copyOf(pagamentos);
    }

    /**
     * Identificação: série, número, cNF, tpEmis, dhEmi e, em contingência, dhCont e xJust; a chave já montada com
     * esses dados.
     */
    public record Identificacao(int serie, int numero, String codigoNumerico, TipoEmissaoNfce tipoEmissao,
                                OffsetDateTime emitidaEm, OffsetDateTime contingenciaEm, String justificativa,
                                String chave) {
    }

    /**
     * Item não cancelado da venda.
     *
     * @param codigo     código interno do produto (cProd)
     * @param gtin       código de barras principal, ou nulo
     * @param desconto   desconto do item + o rateado
     * @param tributo    os tributos gravados na venda (F22), ou nulo quando o produto não tinha os dados fiscais
     */
    public record Item(int sequencia, long codigo, String descricao, String gtin, String unidade,
                       BigDecimal quantidade, BigDecimal valorBruto, BigDecimal desconto, TributoItem tributo) {
    }

    /**
     * Pagamento da venda.
     *
     * @param tpag      o meio da NFC-e (F22.1)
     * @param descricao a descrição da forma (vai em xPag no 99)
     * @param valor     o valor abatido (no dinheiro, sem o troco)
     */
    public record Pagamento(String tpag, String descricao, BigDecimal valor) {
    }
}
