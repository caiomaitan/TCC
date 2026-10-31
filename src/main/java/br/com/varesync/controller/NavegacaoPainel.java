package br.com.varesync.controller;

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
}
