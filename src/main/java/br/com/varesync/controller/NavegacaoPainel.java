package br.com.varesync.controller;

/** Para onde os alertas e atalhos do Painel levam; implementado pela janela principal. */
public interface NavegacaoPainel {

    void abrir(ItemMenu item);

    /** Estoque já filtrado em "Só abaixo do mínimo". */
    void abrirEstoqueAbaixoDoMinimo();

    /** Produtos já filtrados em "Preço abaixo do custo". */
    void abrirProdutosAbaixoDoCusto();
}
