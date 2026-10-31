package br.com.varesync.model;

/**
 * Rotinas e ações liberáveis por usuário. Os nomes são os códigos da tabela {@code permissao} (V2; CLIENTES na V6;
 * DEVOLUCAO na V8; RECEBIMENTO e LIMITE_CREDITO na V10).
 * Supervisor tem todas.
 */
public enum Permissao {
    PRODUTOS("Produtos"),
    FORNECEDORES("Fornecedores"),
    COMPRAS("Compras"),
    ESTOQUE("Estoque"),
    INVENTARIO("Inventário"),
    PDV("PDV"),
    SANGRIA_SUPRIMENTO("Sangria e suprimento"),
    CANCELAR_VENDA("Cancelar venda"),
    DESCONTO_ACIMA_LIMITE("Desconto acima do limite"),
    RELATORIOS("Relatórios"),
    CLIENTES("Clientes"),
    DEVOLUCAO("Devoluções e trocas"),
    RECEBIMENTO("Receber títulos do crediário"),
    LIMITE_CREDITO("Gerenciar crédito do crediário");

    private final String rotulo;

    Permissao(String rotulo) {
        this.rotulo = rotulo;
    }

    /** Nome para a tela e para a descrição da auditoria. */
    public String rotulo() {
        return rotulo;
    }
}
