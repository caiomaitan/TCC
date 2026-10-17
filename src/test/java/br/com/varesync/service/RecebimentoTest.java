package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.TipoFormaPagamento;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Pagamento com várias formas e troco (6.6, 9.1). Vetores de A.10 e do roteiro de aceite da Fase 4. */
class RecebimentoTest {

    static final FormaPagamento DINHEIRO = new FormaPagamento(1, "Dinheiro", TipoFormaPagamento.DINHEIRO);
    static final FormaPagamento PIX = new FormaPagamento(2, "PIX", TipoFormaPagamento.PIX);
    static final FormaPagamento DEBITO = new FormaPagamento(3, "Cartão de débito", TipoFormaPagamento.CARTAO_DEBITO);

    private static BigDecimal v(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    void vendaDeDezesseisReaisDoApendiceA10() {
        Recebimento r = new Recebimento(v("16.00"));
        r.adicionar(PIX, v("10"));
        assertThat(r.falta()).isEqualByComparingTo("6.00");
        assertThat(r.quitado()).isFalse();
        r.adicionar(DINHEIRO, v("10"));

        assertThat(r.pago()).isEqualByComparingTo("20.00");
        assertThat(r.falta()).isEqualByComparingTo("0");
        assertThat(r.troco()).isEqualByComparingTo("4.00");
        assertThat(r.quitado()).isTrue();
        assertThat(r.valoresAbatidos()).containsExactly(
                new Recebimento.Parcela(PIX, v("10.00")), new Recebimento.Parcela(DINHEIRO, v("6.00")));
    }

    @Test
    void aceiteDaFase4DezNoPixECinquentaEmDinheiro() {
        Recebimento r = new Recebimento(v("28.73"));
        r.adicionar(PIX, v("10.00"));
        r.adicionar(DINHEIRO, v("50"));
        assertThat(r.troco()).isEqualByComparingTo("31.27");
        assertThat(r.valoresAbatidos()).containsExactly(
                new Recebimento.Parcela(PIX, v("10.00")), new Recebimento.Parcela(DINHEIRO, v("18.73")));
    }

    @Test
    void soDinheiroPassaDoQueFalta() {
        Recebimento r = new Recebimento(v("16.00"));
        assertThatThrownBy(() -> r.adicionar(PIX, v("16.01")))
                .hasMessage("Só o pagamento em dinheiro pode passar do que falta. Em PIX, informe até R$ 16,00.");
        r.adicionar(DEBITO, v("16.00"));
        assertThat(r.troco()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> r.adicionar(DINHEIRO, v("1"))).hasMessage("A venda já está paga. Finalize a venda.");
    }

    @Test
    void dinheiroEmDuasVezesViraUmaLinhaComOTrocoDescontado() {
        Recebimento r = new Recebimento(v("16.00"));
        r.adicionar(DINHEIRO, v("5"));
        r.adicionar(PIX, v("1"));
        r.adicionar(DINHEIRO, v("20"));
        assertThat(r.troco()).isEqualByComparingTo("10.00");
        assertThat(r.valoresAbatidos()).containsExactly(
                new Recebimento.Parcela(DINHEIRO, v("15.00")), new Recebimento.Parcela(PIX, v("1.00")));
    }

    @Test
    void valorInvalidoERemocaoDeParcela() {
        Recebimento r = new Recebimento(v("10.00"));
        assertThatThrownBy(() -> r.adicionar(DINHEIRO, v("0"))).hasMessage("O valor deve ser maior que zero.");
        assertThatThrownBy(() -> r.adicionar(DINHEIRO, v("1.005")))
                .hasMessage("O valor pode ter no máximo 2 casas decimais.");
        assertThatThrownBy(() -> r.adicionar(null, v("1"))).hasMessage("Escolha a forma de pagamento.");
        r.adicionar(PIX, v("4"));
        r.remover(0);
        assertThat(r.parcelas()).isEmpty();
        assertThat(r.falta()).isEqualByComparingTo("10.00");
    }

    @Test
    void totalZeroDispensaPagamento() {
        Recebimento r = new Recebimento(v("0"));
        assertThat(r.quitado()).isTrue();
        assertThat(r.valoresAbatidos()).isEmpty();
        assertThat(r.troco()).isEqualByComparingTo("0");
    }
}
