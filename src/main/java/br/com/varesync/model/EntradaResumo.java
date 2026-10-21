package br.com.varesync.model;

import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Cabeçalho de uma entrada de mercadoria (6.10): fornecedor, nota, situação, quem lançou e quem confirmou, e o número
 * e a soma dos itens. {@code confirmadoEm} e {@code confirmadoPorNome} são nulos enquanto aberta.
 */
public record EntradaResumo(long id, long fornecedorId, String fornecedorNome, String numeroDocumento,
                            LocalDate dataEmissao, BigDecimal valorTotalNota, StatusEntrada status,
                            OffsetDateTime criadoEm, String criadoPorNome, OffsetDateTime confirmadoEm,
                            String confirmadoPorNome, int totalItens, BigDecimal somaItens) {

    public boolean aberta() {
        return status == StatusEntrada.ABERTA;
    }

    /** "Entrada nº 9 — Distribuidora Ribeirão (NF 1234)". */
    public String titulo() {
        return "Entrada nº " + id + " — " + fornecedorNome
                + (numeroDocumento == null ? "" : " (NF " + numeroDocumento + ")");
    }

    /** Valor total da nota − soma dos itens; vazio se a nota não tem valor total informado. */
    public Optional<BigDecimal> diferencaTotal() {
        return valorTotalNota == null ? Optional.empty() : Optional.of(valorTotalNota.subtract(somaItens));
    }

    /**
     * Aviso antes de confirmar (6.10): a soma dos itens não bate com o valor total da nota. Não impede a confirmação.
     */
    public Optional<String> avisoTotal() {
        return diferencaTotal().filter(d -> d.signum() != 0).map(d -> "A soma dos itens ("
                + Formatos.moeda(somaItens) + ") é diferente do valor total da nota (" + Formatos.moeda(valorTotalNota)
                + "): " + (d.signum() > 0 ? "faltam " : "sobram ") + Formatos.moeda(d.abs())
                + " nos itens. Confira as quantidades e os custos.");
    }
}
