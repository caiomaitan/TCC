package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Conta a pagar (F15.3), com os nomes da categoria e do fornecedor. Em aberto = valor − pago. */
public record TituloPagar(long id, long categoriaId, String categoria, String descricao, LocalDate competencia,
                          LocalDate vencimento, Long fornecedorId, String fornecedor, Long entradaId, String documento,
                          LocalDate emissao, BigDecimal valor, BigDecimal valorPago, StatusTituloPagar status) {

    public BigDecimal emAberto() {
        return status == StatusTituloPagar.CANCELADO ? BigDecimal.ZERO : valor.subtract(valorPago);
    }
}
