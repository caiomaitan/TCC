package br.com.varesync.model;

/**
 * Rotinas e ações liberáveis por usuário. Os nomes são os códigos da tabela {@code permissao} (V2; CLIENTES na V6).
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
    CLIENTES("Clientes");

    private final String rotulo;

    Permissao(String rotulo) {
        this.rotulo = rotulo;
    }

    /** Nome para a tela e para a descrição da auditoria. */
    public String rotulo() {
        return rotulo;
    }
}
