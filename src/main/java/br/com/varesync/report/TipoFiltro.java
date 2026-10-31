package br.com.varesync.report;

/** Filtros que um relatório pode usar (7.3); a tela mostra só os do relatório escolhido. */
public enum TipoFiltro {
    PERIODO,
    OPERADOR,
    CATEGORIA,
    TOP_N,
    CRITERIO,
    SO_ABAIXO_MINIMO,
    INCLUIR_INATIVOS,
    PRODUTO,
    TIPO_MOVIMENTO,
    DETALHAR_VENDAS,
    DIAS,
    FORNECEDOR,
    SITUACAO_TITULO,
    CLIENTE,
    VENCIMENTO,
    FORMA_PAGAMENTO
}
