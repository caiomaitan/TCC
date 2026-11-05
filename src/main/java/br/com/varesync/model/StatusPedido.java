package br.com.varesync.model;

import java.util.EnumSet;
import java.util.Set;

/** Situação de {@code pedido_compra.status} (mesmos valores de {@code ck_pc_status}), F16.3. */
public enum StatusPedido {
    RASCUNHO("Rascunho"),
    ENVIADO("Enviado"),
    PARCIAL("Parcial"),
    RECEBIDO("Recebido"),
    CANCELADO("Cancelado");

    /** Contam como pendente na sugestão e aceitam entrada vinculada. */
    public static final Set<StatusPedido> EM_ABERTO = EnumSet.of(ENVIADO, PARCIAL);
    /** Podem ser cancelados. */
    public static final Set<StatusPedido> CANCELAVEIS = EnumSet.of(RASCUNHO, ENVIADO, PARCIAL);

    private final String rotulo;

    StatusPedido(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }

    public boolean emAberto() {
        return EM_ABERTO.contains(this);
    }
}
