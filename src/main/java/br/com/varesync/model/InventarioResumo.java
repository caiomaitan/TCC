package br.com.varesync.model;

import java.time.OffsetDateTime;

/**
 * Cabeçalho de um inventário (6.7): número, descrição, situação, quem criou e quem aplicou, e quantos itens já foram
 * contados. {@code aplicadoEm} e {@code aplicadoPorNome} são nulos enquanto não aplicado.
 */
public record InventarioResumo(long id, String descricao, StatusInventario status, OffsetDateTime criadoEm,
                               String criadoPorNome, OffsetDateTime aplicadoEm, String aplicadoPorNome,
                               int totalItens, int itensContados) {

    /** "Inventário nº 4 — Mercearia". */
    public String titulo() {
        return "Inventário nº " + id + " — " + descricao;
    }

    public boolean aberto() {
        return status == StatusInventario.ABERTO;
    }

    public int itensNaoContados() {
        return totalItens - itensContados;
    }
}
