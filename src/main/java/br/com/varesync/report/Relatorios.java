package br.com.varesync.report;

import java.util.List;

/** Catálogo dos relatórios disponíveis na tela (7.3), na ordem dos códigos. */
public final class Relatorios {

    private static final List<Relatorio<?>> TODOS = List.of(new RelVendasPeriodo(), new RelMaisVendidos(),
            new RelEstoqueAtual(), new RelMovimentacao(), new RelRentabilidadeProduto());

    private Relatorios() {
    }

    public static List<Relatorio<?>> todos() {
        return TODOS;
    }
}
