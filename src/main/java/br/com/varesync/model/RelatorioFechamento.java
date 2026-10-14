package br.com.varesync.model;

import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.util.List;

/**
 * Relatório de fechamento de caixa (R11, 6.5): abertura, vendas por forma de pagamento, sangrias, suprimentos,
 * esperado × contado, número de vendas, canceladas e descontos concedidos.
 */
public record RelatorioFechamento(CaixaSessao sessao, CaixaResumo resumo, List<VendasPorForma> vendasPorForma,
                                  List<CaixaMovimento> movimentos, BigDecimal descontos) {

    public RelatorioFechamento {
        vendasPorForma = List.copyOf(vendasPorForma);
        movimentos = List.copyOf(movimentos);
    }

    /** Contado − esperado: positivo é sobra, negativo é falta. */
    public BigDecimal diferenca() {
        return sessao.valorContado().subtract(sessao.valorEsperado());
    }

    /** "Sobra de R$ 2,00", "Falta de R$ 1,00" ou "Sem diferença". */
    public String situacao() {
        BigDecimal diferenca = diferenca();
        if (diferenca.signum() == 0) {
            return "Sem diferença";
        }
        return (diferenca.signum() > 0 ? "Sobra de " : "Falta de ") + Formatos.moeda(diferenca.abs());
    }

    /** {@link #situacao()} para o meio de uma frase: "falta de R$ 1,00". */
    public String situacaoNaFrase() {
        String situacao = situacao();
        return Character.toLowerCase(situacao.charAt(0)) + situacao.substring(1);
    }
}
