package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Extrato do crediário do cliente (F14.4): limite, bloqueio, saldo devedor, as parcelas e os lançamentos.
 *
 * @param limiteDisponivel limite − saldo devedor (negativo = passou do limite)
 * @param vencido          parte do saldo devedor com vencimento antes de hoje
 * @param lancamentos      vendas a prazo, pagamentos e abatimentos, do mais antigo para o mais recente
 */
public record ExtratoCrediario(BigDecimal limite, boolean bloqueado, BigDecimal saldoDevedor, BigDecimal vencido,
                               BigDecimal limiteDisponivel, List<Parcela> parcelas, List<Lancamento> lancamentos) {

    /** Uma parcela: o que foi pago e abatido, e a situação. */
    public record Parcela(long tituloId, long vendaId, int parcela, int totalParcelas, LocalDate emissao,
                          LocalDate vencimento, BigDecimal valor, BigDecimal pago, BigDecimal abatido,
                          BigDecimal emAberto, StatusTitulo status, long diasAtraso) {

        public String rotulo() {
            return parcela + "/" + totalParcelas;
        }
    }

    /**
     * Um lançamento.
     *
     * @param valor    efeito no saldo devedor: + venda a prazo e estorno; − pagamento, abatimento e cancelamento
     * @param encargos multa + juros − desconto pagos junto (zero nos demais)
     * @param saldo    saldo devedor depois do lançamento
     */
    public record Lancamento(OffsetDateTime dataHora, String tipo, String documento, BigDecimal valor,
                             BigDecimal encargos, BigDecimal saldo) {
    }

    public ExtratoCrediario {
        parcelas = List.copyOf(parcelas);
        lancamentos = List.copyOf(lancamentos);
    }
}
