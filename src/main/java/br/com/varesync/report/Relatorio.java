package br.com.varesync.report;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/**
 * Um relatório (7.4): nome, filtros que usa, consulta parametrizada que devolve as linhas (records) e as colunas.
 * A consulta roda na transação aberta pelo {@code RelatorioService}; vendas e lucro partem de
 * {@code vw_venda_item_rentabilidade}, estoque de {@code vw_estoque_atual} (7.2).
 *
 * @param <T> record de uma linha
 */
public interface Relatorio<T> {

    /** "R01". */
    String codigo();

    String nome();

    /** Uma frase para a tela, abaixo da escolha do relatório. */
    String descricao();

    Set<TipoFiltro> filtros();

    List<T> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros) throws SQLException;

    /** As colunas podem depender dos filtros (ex.: R01 por dia ou por venda). */
    List<Coluna<T>> colunas(Filtros filtros);

    /** "R01 — Vendas por período". */
    default String titulo() {
        return codigo() + " — " + nome();
    }
}
