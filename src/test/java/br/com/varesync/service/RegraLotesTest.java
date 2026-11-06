package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.dao.LoteDAO;
import br.com.varesync.model.Lote;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Distribuição nos lotes (F17.2), sem banco: FEFO com o "SEM LOTE" primeiro, devolução e cancelamento. */
class RegraLotesTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 10, 6);
    private static final OffsetDateTime CRIADO = OffsetDateTime.of(2026, 10, 1, 8, 0, 0, 0, ZoneOffset.UTC);

    private static Lote lote(long id, String codigo, Integer dias, String saldo) {
        return new Lote(id, 1, codigo, dias == null ? null : HOJE.plusDays(dias), new BigDecimal(saldo),
                CRIADO.plusMinutes(id));
    }

    private static Lote semLote(long id, String saldo) {
        return lote(id, "", null, saldo);
    }

    private static RegraLotes.Parte parte(Long id, String quantidade) {
        return new RegraLotes.Parte(id, new BigDecimal(quantidade));
    }

    private static BigDecimal q(String valor) {
        return new BigDecimal(valor);
    }

    private static List<String> texto(List<RegraLotes.Parte> partes) {
        return partes.stream().map(p -> p.loteId() + "=" + p.quantidade().stripTrailingZeros().toPlainString())
                .toList();
    }

    /** Aceite FEFO: A (10 dias, 20) e B (40 dias, 20); saída de 25 → A 20 e B 5. */
    @Test
    void fefoDoAceite() {
        List<Lote> lotes = List.of(lote(2, "B", 40, "20"), lote(1, "A", 10, "20"));
        assertThat(texto(RegraLotes.saida(lotes, q("25")))).containsExactly("1=20", "2=5");
    }

    /** Aceite SEM LOTE primeiro: 5 no "SEM LOTE" e A com 20; saída de 8 → SEM LOTE 5 e A 3. */
    @Test
    void semLotePrimeiroDoAceite() {
        List<Lote> lotes = List.of(semLote(9, "5"), lote(1, "A", 10, "20"));
        assertThat(texto(RegraLotes.saida(lotes, q("8")))).containsExactly("9=5", "1=3");
    }

    /** Aceite negativo: só A (20) e saída de 30 → A 20 e o "SEM LOTE" (que ainda não existe) com 10. */
    @Test
    void faltaSaiDoSemLoteQuePodeFicarNegativo() {
        assertThat(texto(RegraLotes.saida(List.of(lote(1, "A", 10, "20")), q("30"))))
                .containsExactly("1=20", "null=10");
        // O "SEM LOTE" que já existe (positivo) recebe as duas partes numa linha só.
        assertThat(texto(RegraLotes.saida(List.of(semLote(9, "2"), lote(1, "A", 10, "3")), q("8"))))
                .containsExactly("9=5", "1=3");
        // Negativo não é consumido primeiro; a falta aumenta o negativo.
        assertThat(texto(RegraLotes.saida(List.of(semLote(9, "-4"), lote(1, "A", 10, "3")), q("5"))))
                .containsExactly("1=3", "9=2");
    }

    /** Empate na validade: o lote mais antigo primeiro; lote zerado é pulado. */
    @Test
    void empateNaValidadeSaiDoMaisAntigo() {
        List<Lote> lotes = List.of(lote(3, "C", 10, "4"), lote(2, "B", 10, "4"), lote(1, "Z", 5, "0"));
        assertThat(texto(RegraLotes.saida(lotes, q("6")))).containsExactly("2=4", "3=2");
    }

    /**
     * Aceite devolução parcial: a venda de 25 saiu A 20 e B 5; devolver 3 volta para B; depois mais 4 → B 2 e A 2.
     */
    @Test
    void devolucaoNaOrdemInversaDescontandoAsAnteriores() {
        List<LoteDAO.Linha> venda = List.of(new LoteDAO.Linha(1, q("-20")), new LoteDAO.Linha(2, q("-5")));
        assertThat(texto(RegraLotes.devolucao(venda, Map.of(), null, q("3")))).containsExactly("2=3");
        assertThat(texto(RegraLotes.devolucao(venda, Map.of(2L, q("3")), null, q("4"))))
                .containsExactly("2=2", "1=2");
    }

    /** Venda sem linhas (controle desligado na venda): tudo para o "SEM LOTE". */
    @Test
    void vendaSemControleVoltaParaOSemLote() {
        assertThat(texto(RegraLotes.devolucao(List.of(), Map.of(), 9L, q("3")))).containsExactly("9=3");
        assertThat(texto(RegraLotes.devolucao(List.of(), Map.of(), null, q("3")))).containsExactly("null=3");
    }

    /** Aceite cancelamento: a venda de 25 volta 20 para A e 5 para B. */
    @Test
    void cancelamentoVoltaParaOsMesmosLotes() {
        List<LoteDAO.Linha> venda = List.of(new LoteDAO.Linha(1, q("-20")), new LoteDAO.Linha(2, q("-5")));
        List<RegraLotes.Parte> partes = RegraLotes.cancelamento(venda, null, q("25"));
        assertThat(partes).containsExactlyInAnyOrder(parte(1L, "20"), parte(2L, "5"));
    }
}
