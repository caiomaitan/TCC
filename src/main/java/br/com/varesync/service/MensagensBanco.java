package br.com.varesync.service;

import java.util.Map;
import java.util.Optional;

/**
 * Mensagens para o usuário por nome de constraint do banco (todas são nomeadas no V1).
 * Usado por {@link TradutorErros}; constraint fora do mapa recebe a mensagem padrão do SQLState.
 */
public final class MensagensBanco {

    public static final String UNICO_PADRAO = "Já existe um registro com estes dados";
    public static final String EM_USO = "Este registro está em uso e não pode ser excluído";
    public static final String CHECK_PADRAO = "Dados inválidos. Confira os campos e tente novamente";

    private static final Map<String, String> MENSAGENS = Map.ofEntries(
            // Acesso
            Map.entry("uq_usuario_login", "Este login já está em uso por outro usuário"),
            Map.entry("ck_usuario_login",
                    "O login deve ter de 3 a 30 caracteres: letras minúsculas, números, ponto, hífen ou sublinhado"),
            Map.entry("ck_usuario_nome", "Informe o nome do usuário"),
            Map.entry("ck_usuario_tentativas", "Número de tentativas de login inválido"),
            // Cadastros
            Map.entry("ck_categoria_nome", "Informe o nome da categoria"),
            Map.entry("ux_categoria_nome", "Já existe uma categoria com este nome"),
            Map.entry("uq_produto_plu", "Este PLU já pertence a outro produto"),
            Map.entry("ck_produto_nome", "O nome do produto deve ter ao menos 2 caracteres"),
            Map.entry("ck_produto_preco", "O preço de venda não pode ser negativo"),
            Map.entry("ck_produto_custo", "O custo não pode ser negativo"),
            Map.entry("ck_produto_estoque_minimo", "O estoque mínimo não pode ser negativo"),
            Map.entry("ck_produto_plu", "O PLU deve estar entre 1 e 999999"),
            Map.entry("uq_pcb_codigo", "Este código de barras já pertence a outro produto"),
            Map.entry("ck_pcb_codigo", "O código de barras deve ter 8, 12, 13 ou 14 dígitos"),
            Map.entry("ux_pcb_principal", "O produto só pode ter um código de barras principal"),
            Map.entry("ck_promocao_preco", "O preço promocional deve ser maior que zero"),
            Map.entry("ck_promocao_periodo", "A data final da promoção não pode ser anterior à data inicial"),
            Map.entry("uq_fornecedor_cpf_cnpj", "Já existe um fornecedor com este CPF/CNPJ"),
            Map.entry("ck_fornecedor_razao", "Informe a razão social do fornecedor"),
            Map.entry("ck_fornecedor_cpf_cnpj", "CPF/CNPJ inválido"),
            Map.entry("ck_fornecedor_uf", "UF inválida: use a sigla do estado com 2 letras"),
            Map.entry("uq_cliente_cpf_cnpj", "Já existe um cliente com este CPF/CNPJ"),
            Map.entry("ck_cliente_nome", "Informe o nome do cliente"),
            Map.entry("ck_cliente_cpf_cnpj", "CPF/CNPJ inválido"),
            Map.entry("ck_cliente_telefone", "Telefone inválido: informe o DDD e o número, com 10 ou 11 dígitos"),
            Map.entry("ck_cliente_cep", "CEP inválido: informe os 8 dígitos"),
            Map.entry("ck_cliente_uf", "UF inválida: use a sigla do estado com 2 letras"),
            Map.entry("ck_cliente_consentimento", "O consentimento de marketing precisa da data"),
            Map.entry("ck_cliente_anonimizado", "Cliente anonimizado não pode ter dados pessoais"),
            // Caixa e vendas
            Map.entry("uq_forma_pagamento_descricao", "Já existe uma forma de pagamento com esta descrição"),
            Map.entry("ck_forma_pagamento_tipo", "Tipo de forma de pagamento inválido"),
            Map.entry("ck_caixa_valores", "Os valores do caixa não podem ser negativos"),
            Map.entry("ck_caixa_fechamento", "Fechamento de caixa incompleto"),
            Map.entry("ux_caixa_aberto_por_terminal", "Já existe um caixa aberto neste terminal"),
            Map.entry("ck_cm_valor", "O valor deve ser maior que zero"),
            Map.entry("ck_venda_valores", "Os valores da venda não podem ser negativos"),
            Map.entry("ck_venda_total", "O total da venda não confere com o valor bruto e os descontos"),
            Map.entry("ck_venda_cancelamento", "Informe o motivo do cancelamento"),
            Map.entry("ck_vi_valores", "Quantidade ou valores do item inválidos"),
            Map.entry("ck_vi_liquido", "O valor do item não confere com os descontos"),
            Map.entry("ck_vi_cancelado", "Item cancelado não recebe rateio de desconto"),
            Map.entry("ck_vp_valor", "O valor do pagamento deve ser maior que zero"),
            // Inventário
            Map.entry("ck_inventario_descricao", "Informe a descrição do inventário"),
            Map.entry("uq_ii_produto", "Este produto já está neste inventário"),
            Map.entry("ck_ii_quantidade", "A quantidade contada não pode ser negativa"),
            // Compras
            Map.entry("ck_em_valor_nota", "O valor total da nota não pode ser negativo"),
            Map.entry("ux_em_documento", "Esta nota já foi lançada para este fornecedor"),
            Map.entry("ck_emi_valores", "A quantidade deve ser maior que zero e o custo não pode ser negativo"),
            // Estoque
            Map.entry("ck_me_sinal", "A quantidade do movimento não corresponde ao tipo (entrada ou saída)"),
            Map.entry("ck_me_origem", "Informe o motivo do ajuste"),
            Map.entry("ux_me_venda_item", "Este item de venda já movimentou o estoque"),
            Map.entry("ux_me_inventario_item", "Este item de inventário já foi aplicado ao estoque"),
            Map.entry("ux_me_entrada_item", "Este item de entrada já foi lançado no estoque"),
            Map.entry("ux_me_devolucao_item", "Este item de devolução já movimentou o estoque"),
            Map.entry("ck_dev_motivo", "Informe o motivo da devolução"),
            Map.entry("ck_dev_valor", "O valor da devolução não pode ser negativo"),
            Map.entry("ck_dev_caixa", "O reembolso em dinheiro exige o caixa aberto"),
            Map.entry("ck_dev_cliente", "O vale-troca exige o cliente"),
            Map.entry("ck_di_quantidade", "A quantidade devolvida deve ser maior que zero"),
            Map.entry("ck_di_valores", "O valor e o custo devolvidos não podem ser negativos"));

    private MensagensBanco() {
    }

    public static Optional<String> mensagem(String constraint) {
        return constraint == null ? Optional.empty() : Optional.ofNullable(MENSAGENS.get(constraint));
    }
}
