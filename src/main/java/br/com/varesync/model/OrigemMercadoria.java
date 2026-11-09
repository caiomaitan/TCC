package br.com.varesync.model;

import java.util.List;

/** Origem da mercadoria (tabela A do CST do ICMS, 0 a 8), gravada em {@code produto.origem} (F19.2). */
public final class OrigemMercadoria {

    private static final List<String> ROTULOS = List.of(
            "Nacional",
            "Estrangeira — importação direta",
            "Estrangeira — adquirida no mercado interno",
            "Nacional com mais de 40% de conteúdo importado",
            "Nacional, produção conforme processos produtivos básicos",
            "Nacional com até 40% de conteúdo importado",
            "Estrangeira — importação direta, sem similar nacional (lista CAMEX)",
            "Estrangeira — mercado interno, sem similar nacional (lista CAMEX)",
            "Nacional com mais de 70% de conteúdo importado");

    private OrigemMercadoria() {
    }

    /** 0 a 8. */
    public static List<Integer> codigos() {
        return List.of(0, 1, 2, 3, 4, 5, 6, 7, 8);
    }

    /**
     * Origem do produto a partir da origem na NF-e do fornecedor (F19.3, ajuste da parte B): a loja não é a
     * importadora, então a importação direta do fornecedor (1 e 6) vira estrangeira comprada no mercado interno (2 e
     * 7). As outras são copiadas; nula fica nula.
     */
    public static Integer daNotaDoFornecedor(Integer origem) {
        if (origem == null) {
            return null;
        }
        return switch (origem) {
            case 1 -> 2;
            case 6 -> 7;
            default -> origem;
        };
    }

    /** "0 — Nacional"; nula = "—". */
    public static String rotulo(Integer origem) {
        if (origem == null || origem < 0 || origem >= ROTULOS.size()) {
            return "—";
        }
        return origem + " — " + ROTULOS.get(origem);
    }
}
