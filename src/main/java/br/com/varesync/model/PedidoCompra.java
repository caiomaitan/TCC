package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Cabeçalho de um pedido de compra (F16.3), com quem criou, enviou e cancelou, o número de itens, o valor previsto
 * (Σ quantidade × custo previsto) e o que falta receber (Σ do pendente de cada item, que nunca é negativo). Os
 * opcionais são nulos quando não houve.
 */
public record PedidoCompra(long id, long fornecedorId, String fornecedorNome, boolean fornecedorAtivo,
                           StatusPedido status, LocalDate previsaoEntrega, String observacao,
                           OffsetDateTime criadoEm, String criadoPorNome, OffsetDateTime enviadoEm,
                           String enviadoPorNome, OffsetDateTime canceladoEm, String canceladoPorNome,
                           int totalItens, BigDecimal valorPrevisto, BigDecimal quantidadePendente) {

    public boolean rascunho() {
        return status == StatusPedido.RASCUNHO;
    }

    /** "Pedido nº 12 — Laticínios Vale do Pardo". */
    public String titulo() {
        return "Pedido nº " + id + " — " + fornecedorNome;
    }
}
