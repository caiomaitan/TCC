package br.com.varesync.model;

/** Ações registradas em {@code auditoria.acao} (4.9), com o nome que a tela de auditoria mostra. */
public enum AcaoAuditoria {
    LOGIN("Login"),
    LOGIN_FALHOU("Login recusado"),
    LOGOUT("Saída do sistema"),
    USUARIO_CRIADO("Usuário criado"),
    USUARIO_ALTERADO("Usuário alterado"),
    PERMISSOES_ALTERADAS("Permissões alteradas"),
    SENHA_REDEFINIDA("Senha redefinida"),
    USUARIO_DESBLOQUEADO("Usuário desbloqueado"),
    PRODUTO_CRIADO("Produto criado"),
    PRODUTO_ALTERADO("Produto alterado"),
    PRODUTO_INATIVADO("Produto inativado"),
    PRODUTO_EXCLUIDO("Produto excluído"),
    ESTOQUE_AJUSTADO("Estoque ajustado"),
    INVENTARIO_APLICADO("Inventário aplicado"),
    INVENTARIO_CANCELADO("Inventário cancelado"),
    CAIXA_ABERTO("Caixa aberto"),
    CAIXA_FECHADO("Caixa fechado"),
    SANGRIA("Sangria"),
    SUPRIMENTO("Suprimento"),
    ITEM_CANCELADO("Item cancelado no PDV"),
    VENDA_ABANDONADA("Venda abandonada"),
    VENDA_CANCELADA("Venda cancelada"),
    DESCONTO_LIBERADO("Desconto acima do limite"),
    ENTRADA_CONFIRMADA("Entrada de mercadoria confirmada"),
    PROMOCAO_ALTERADA("Promoção"),
    CONFIGURACAO_ALTERADA("Configuração alterada"),
    BACKUP_REALIZADO("Backup"),
    DADOS_DEMO_GERADOS("Dados de demonstração"),
    CLIENTE_DADOS_EXPORTADOS("Dados do cliente exportados (LGPD)"),
    CLIENTE_ANONIMIZADO("Cliente anonimizado (LGPD)"),
    CREDITO_AJUSTADO("Crédito do cliente ajustado");

    private final String rotulo;

    AcaoAuditoria(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }
}
