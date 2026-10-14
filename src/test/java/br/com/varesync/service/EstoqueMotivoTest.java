package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.model.TipoAjuste;
import org.junit.jupiter.api.Test;

/** Motivo gravado no ajuste manual (6.4): obrigatório; na perda, da lista, gravado como "Vencimento: observação". */
class EstoqueMotivoTest {

    @Test
    void entradaESaidaAceitamTextoLivreComObservacao() {
        assertThat(EstoqueService.comporMotivo(TipoAjuste.ENTRADA, "  Devolução   de cliente ", null))
                .isEqualTo("Devolução de cliente");
        assertThat(EstoqueService.comporMotivo(TipoAjuste.SAIDA, "Transferência", " filial  2 "))
                .isEqualTo("Transferência: filial 2");
    }

    @Test
    void perdaUsaOMotivoDaListaSemDiferenciarMaiusculas() {
        assertThat(EstoqueService.comporMotivo(TipoAjuste.PERDA, "vencimento", "lote 12"))
                .isEqualTo("Vencimento: lote 12");
        assertThat(EstoqueService.comporMotivo(TipoAjuste.PERDA, "FURTO/DESVIO", "")).isEqualTo("Furto/desvio");
        assertThat(EstoqueService.comporMotivo(TipoAjuste.PERDA, "Outros", "caiu da prateleira"))
                .isEqualTo("Outros: caiu da prateleira");
    }

    @Test
    void recusaMotivoVazioForaDaListaOuOutrosSemObservacao() {
        assertThatThrownBy(() -> EstoqueService.comporMotivo(TipoAjuste.ENTRADA, " ", "só observação"))
                .isInstanceOf(NegocioException.class).hasMessage("Informe o motivo do ajuste.");
        assertThatThrownBy(() -> EstoqueService.comporMotivo(TipoAjuste.PERDA, null, null))
                .hasMessage("Informe o motivo do ajuste.");
        assertThatThrownBy(() -> EstoqueService.comporMotivo(TipoAjuste.PERDA, "Quebra", null))
                .hasMessage("Escolha o motivo da perda: Vencimento, Avaria, Furto/desvio, Consumo interno, Outros.");
        assertThatThrownBy(() -> EstoqueService.comporMotivo(TipoAjuste.PERDA, "Outros", "  "))
                .hasMessage("Com o motivo \"Outros\", descreva a perda na observação.");
    }

    @Test
    void motivoComObservacaoCabeEm200Caracteres() {
        String motivo = "Acerto de contagem"; // 18 + ": " = 20
        assertThat(EstoqueService.comporMotivo(TipoAjuste.ENTRADA, motivo, "x".repeat(180))).hasSize(200);
        assertThatThrownBy(() -> EstoqueService.comporMotivo(TipoAjuste.ENTRADA, motivo, "x".repeat(181)))
                .hasMessage("O motivo e a observação podem ter juntos no máximo 200 caracteres.");
    }
}
