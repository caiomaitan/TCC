package br.com.varesync.model;

/**
 * Alertas da NFC-e no painel (F23.10), só para quem tem FISCAL; o clique abre a aba Notas fiscais já filtrada.
 *
 * @param aTransmitir        NFC-e em contingência ou sem resposta
 * @param aTransmitirAntiga  alguma delas é de um dia anterior (o alerta fica em vermelho: o prazo da contingência é o
 *                           primeiro dia útil seguinte à emissão)
 * @param rejeitadas         rejeitadas de venda finalizada, a corrigir e reenviar
 * @param diasCertificado    dias até o vencimento do certificado deste terminal, quando já está no prazo do alerta
 *                           ({@code nfce.alerta_certificado_dias}); negativo = vencido; nulo = sem alerta
 */
public record AlertasNfce(int aTransmitir, boolean aTransmitirAntiga, int rejeitadas, Long diasCertificado) {

    public static final AlertasNfce NENHUM = new AlertasNfce(0, false, 0, null);

    public boolean certificadoVencido() {
        return diasCertificado != null && diasCertificado < 0;
    }

    public boolean algum() {
        return aTransmitir > 0 || rejeitadas > 0 || diasCertificado != null;
    }
}
