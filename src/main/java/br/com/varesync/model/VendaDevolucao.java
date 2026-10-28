package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Venda aberta para devolução (F13.4): cabeçalho, cliente, prazo, itens e as devoluções já feitas.
 *
 * @param clienteDocumento CPF mascarado ou CNPJ (vazio sem documento); nulo sem cliente
 * @param prazoAte         último dia da devolução sem liberação de supervisor ({@code devolucao.prazo_dias})
 * @param foraDoPrazo      hoje passou de {@code prazoAte}
 */
public record VendaDevolucao(long id, OffsetDateTime dataHora, String operador, StatusVenda status,
                             BigDecimal valorTotal, Long clienteId, String clienteNome, String clienteDocumento,
                             boolean clienteAnonimizado, LocalDate prazoAte, boolean foraDoPrazo,
                             List<ItemDevolucao> itens, List<DevolucaoResumo> devolucoes) {

    public VendaDevolucao {
        itens = List.copyOf(itens);
        devolucoes = List.copyOf(devolucoes);
    }

    /** Ainda há algo a devolver (venda finalizada com algum item disponível)? */
    public boolean temDisponivel() {
        return status == StatusVenda.FINALIZADA && itens.stream().anyMatch(i -> i.disponivel().signum() > 0);
    }

    /** Vale-troca possível: sem cliente (identifica-se na hora) ou com cliente não anonimizado. */
    public boolean aceitaValeTroca() {
        return clienteId == null || !clienteAnonimizado;
    }
}
