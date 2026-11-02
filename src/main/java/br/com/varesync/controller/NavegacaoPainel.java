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

    /** Financeiro, aba Contas, com a conta de saldo negativo selecionada. */
    void abrirContasFinanceiras();
}
