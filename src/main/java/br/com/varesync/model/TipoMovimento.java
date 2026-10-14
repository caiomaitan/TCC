package br.com.varesync.model;

/**
 * Tipos de {@code movimento_estoque.tipo} (mesmos valores de {@code ck_me_tipo}) e o sinal que cada um exige
 * ({@code ck_me_sinal}).
 */
public enum TipoMovimento {
    ENTRADA_COMPRA("Entrada de mercadoria", 1),
    VENDA("Venda", -1),
    CANCELAMENTO_VENDA("Cancelamento de venda", 1),
    AJUSTE_ENTRADA("Ajuste de entrada", 1),
    AJUSTE_SAIDA("Ajuste de saída", -1),
    PERDA("Perda", -1),
    INVENTARIO("Inventário", 0);

    private final String descricao;
    /** 1 = só entra, −1 = só sai, 0 = qualquer sinal (diferente de zero). */
    private final int sinal;

    TipoMovimento(String descricao, int sinal) {
        this.descricao = descricao;
        this.sinal = sinal;
    }

    /** Descrição amigável para o kardex. */
    public String descricao() {
        return descricao;
    }

    /** A quantidade (com sinal) é compatível com o tipo? Zero nunca é. */
    public boolean aceitaQuantidade(int sinalQuantidade) {
        if (sinalQuantidade == 0) {
            return false;
        }
        return sinal == 0 || sinal == sinalQuantidade;
    }

    /** Ajustes e perdas exigem motivo ({@code ck_me_origem}). */
    public boolean exigeMotivo() {
        return this == AJUSTE_ENTRADA || this == AJUSTE_SAIDA || this == PERDA;
    }
}
