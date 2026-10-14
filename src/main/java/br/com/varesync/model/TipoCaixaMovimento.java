package br.com.varesync.model;

/**
 * Tipos de {@code caixa_movimento.tipo} (mesmos valores de {@code ck_cm_tipo}): sangria retira dinheiro da gaveta,
 * suprimento reforça o troco.
 */
public enum TipoCaixaMovimento {
    SANGRIA("Sangria", AcaoAuditoria.SANGRIA, -1),
    SUPRIMENTO("Suprimento", AcaoAuditoria.SUPRIMENTO, 1);

    private final String descricao;
    private final AcaoAuditoria acaoAuditoria;
    /** Efeito no dinheiro da gaveta: 1 entra, −1 sai. */
    private final int sinal;

    TipoCaixaMovimento(String descricao, AcaoAuditoria acaoAuditoria, int sinal) {
        this.descricao = descricao;
        this.acaoAuditoria = acaoAuditoria;
        this.sinal = sinal;
    }

    public String descricao() {
        return descricao;
    }

    public AcaoAuditoria acaoAuditoria() {
        return acaoAuditoria;
    }

    public int sinal() {
        return sinal;
    }

    @Override
    public String toString() {
        return descricao; // texto da lista de tipos no diálogo
    }
}
