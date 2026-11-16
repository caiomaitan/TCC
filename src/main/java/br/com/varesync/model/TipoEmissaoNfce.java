package br.com.varesync.model;

/** tpEmis da NFC-e (F23.1): 1 emissão normal ou 9 contingência off-line ({@code ck_nfce_tipo_emissao}). */
public enum TipoEmissaoNfce {
    NORMAL(1),
    CONTINGENCIA_OFFLINE(9);

    private final int codigo;

    TipoEmissaoNfce(int codigo) {
        this.codigo = codigo;
    }

    public int codigo() {
        return codigo;
    }

    public static TipoEmissaoNfce de(int codigo) {
        for (TipoEmissaoNfce t : values()) {
            if (t.codigo == codigo) {
                return t;
            }
        }
        throw new IllegalArgumentException("tpEmis desconhecido: " + codigo);
    }
}
