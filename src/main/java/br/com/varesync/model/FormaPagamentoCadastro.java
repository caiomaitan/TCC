package br.com.varesync.model;

/** Forma de pagamento como o cadastro de Configurações a mostra (ativas e desativadas). */
public record FormaPagamentoCadastro(int id, String descricao, TipoFormaPagamento tipo, boolean ativa) {
}
