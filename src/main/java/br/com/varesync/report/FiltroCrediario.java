package br.com.varesync.report;

import java.time.LocalDate;

/**
 * Filtros do R21 e do R22 (F14.4). Nulo = todos; nas datas, sem limite.
 *
 * @param situacao      situação do título (R21); nula = em aberto
 * @param cliente       parte do nome (sem acento) ou do CPF/CNPJ
 * @param vencimentoDe  vencimento a partir de (R21)
 * @param vencimentoAte vencimento até (R21)
 * @param forma         forma de pagamento (R22)
 */
public record FiltroCrediario(SituacaoTitulo situacao, String cliente, LocalDate vencimentoDe, LocalDate vencimentoAte,
                              Opcao forma) {

    public static final FiltroCrediario VAZIO = new FiltroCrediario(null, null, null, null, null);

    public SituacaoTitulo situacaoOuPadrao() {
        return situacao == null ? SituacaoTitulo.EM_ABERTO : situacao;
    }
}
