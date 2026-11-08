package br.com.varesync.controller;

import java.time.LocalDate;

/** Para onde os alertas e atalhos do Painel levam; implementado pela janela principal. */
public interface NavegacaoPainel {

    void abrir(ItemMenu item);

    /** Estoque já filtrado em "Só abaixo do mínimo". */
    void abrirEstoqueAbaixoDoMinimo();

    /** Produtos já filtrados em "Preço abaixo do custo". */
    void abrirProdutosAbaixoDoCusto();

    /** Configurações já na aba Backup (alerta de backup atrasado). */
    void abrirBackup();

    /** R21 já filtrado nos títulos vencidos (alerta de títulos vencidos, com RELATORIOS). */
    void abrirTitulosVencidos();

    /** Financeiro, aba Contas a pagar, só as em aberto com vencimento de {@code de} (nulo = sem limite) até {@code ate}. */
    void abrirContasAPagar(LocalDate de, LocalDate ate);

    /** Compras, aba Sugestão de compra, só com os produtos em ruptura (F16.4). */
    void abrirRupturas();

    /** Financeiro, aba Contas, com a conta de saldo negativo selecionada. */
    void abrirContasFinanceiras();

    /**
     * F17.3: R29 já filtrado (lotes que vencem em até {@code dias} dias e, se {@code vencidos}, os vencidos); sem a
     * permissão RELATORIOS, o Estoque.
     */
    void abrirValidade(int dias, boolean vencidos);

    /** Estoque já filtrado em "Só com SEM LOTE negativo" (F17.3). */
    void abrirSemLoteNegativo();

    /** Produtos, com as Etiquetas de gôndola abertas nas pendentes (F18.3). */
    void abrirEtiquetasPendentes();

    /** Produtos, com a Carga de balança aberta (F18.3). */
    void abrirCargaBalanca();
}
