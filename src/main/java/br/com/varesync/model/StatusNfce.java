package br.com.varesync.model;

/** Situação da NFC-e (F23.1): os mesmos valores do CHECK {@code ck_nfce_status} da V24. */
public enum StatusNfce {
    PENDENTE("Pendente"),
    AUTORIZADA("Autorizada"),
    REJEITADA("Rejeitada"),
    DENEGADA("Denegada"),
    SEM_RESPOSTA("Sem resposta"),
    CONTINGENCIA("Em contingência"),
    CANCELADA("Cancelada"),
    SUBSTITUIDA("Substituída"),
    INUTILIZADA("Inutilizada");

    private final String rotulo;

    StatusNfce(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }
}
