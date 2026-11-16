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
            Map.entry("ck_produto_conteudo",
                    "Conteúdo líquido inválido: informe a quantidade (maior que zero) e a unidade (g, kg, ml ou L)"),
            Map.entry("ck_ei_preco", "O preço da etiqueta não pode ser negativo"),
            Map.entry("ck_ei_origem", "Origem da etiqueta inválida"),
            Map.entry("ck_ei_atacado", "Faixa de atacado da etiqueta inválida"),
            Map.entry("ck_pcb_quantidade", "A quantidade da embalagem deve ser maior que zero"),
            Map.entry("ck_pcb_preco_embalagem", "O preço da embalagem deve ser maior que zero"),
            Map.entry("ck_pcb_principal_unitario",
                    "O código de barras principal é o da unidade: uma embalagem com mais de 1 unidade não pode ser a "
                            + "principal"),
            Map.entry("uq_ppq_quantidade", "Já existe uma faixa de preço com esta quantidade para o produto"),
            Map.entry("ck_ppq_quantidade", "A quantidade da faixa de preço deve ser maior que 1"),
            Map.entry("ck_ppq_preco", "O preço da faixa deve ser maior que zero"),
            Map.entry("ck_produto_validade_balanca", "Os dias de validade na balança devem estar entre 1 e 990"),
            Map.entry("ck_cb_itens", "A carga de balança precisa ter ao menos um produto"),
            Map.entry("ck_cbi_preco", "O preço do item da carga de balança deve ser maior que zero"),
            Map.entry("ck_cbi_plu", "O PLU do item da carga de balança deve ser maior que zero"),
            Map.entry("ck_promocao_preco","O preço promocional deve ser maior que zero"),
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
            Map.entry("uq_pf_produto_fornecedor", "Este fornecedor já está vinculado ao produto"),
            Map.entry("uq_pf_codigo", "Este código do fornecedor já está vinculado a outro produto"),
            Map.entry("ux_pf_principal", "O produto só pode ter um fornecedor principal"),
            Map.entry("ck_pf_codigo", "Informe o código no fornecedor ou deixe o campo vazio"),
            Map.entry("ck_pf_unidade", "Informe a unidade de compra ou deixe o campo vazio"),
            Map.entry("ck_pf_fator", "O fator deve ser 1 ou mais"),
            Map.entry("ck_pf_custo", "O último custo não pode ser negativo"),
            Map.entry("ck_pf_prazo", "O prazo de entrega deve estar entre 0 e 365 dias"),
            Map.entry("uq_pci_produto", "Este produto já está no pedido. Altere a quantidade da linha"),
            Map.entry("ck_pci_quantidade", "A quantidade deve ser maior que zero"),
            Map.entry("ck_pci_custo", "O custo previsto não pode ser negativo"),
            Map.entry("ck_pc_status", "Situação do pedido inválida"),
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
            Map.entry("ck_di_valores", "O valor e o custo devolvidos não podem ser negativos"),
            Map.entry("ck_dev_abatido", "O valor abatido da devolução não confere com a forma de reembolso"),
            Map.entry("ck_cliente_limite_credito", "O limite de crédito não pode ser negativo"),
            Map.entry("ck_tr_valor", "O valor da parcela deve ser maior que zero"),
            Map.entry("ck_tr_baixas", "O pago e o abatido não podem passar do valor da parcela"),
            Map.entry("ck_tr_situacao", "A situação do título não confere com o que foi pago e abatido"),
            Map.entry("ck_tr_cancelado", "O cancelamento do título exige o motivo"),
            // Financeiro
            Map.entry("ux_conta_nome", "Já existe uma conta com este nome"),
            Map.entry("ux_conta_padrao", "Só uma conta pode ser a padrão do caixa"),
            Map.entry("ux_cd_nome", "Já existe uma categoria de despesa com este nome"),
            Map.entry("ux_cm2_saldo_inicial", "Esta conta já tem saldo inicial. Para corrigir, lance um ajuste"),
            Map.entry("ck_fp_taxa", "A taxa deve estar entre 0 e 20%"),
            Map.entry("ck_fp_dias", "Os dias para receber devem estar entre 0 e 60"),
            Map.entry("ck_fp_conta_destino", "Dinheiro, crédito do cliente e crediário não têm conta de destino"),
            Map.entry("ck_tp_pago", "O pago não pode passar do valor da conta"),
            Map.entry("uq_emil_lote", "Este lote (código e validade) já está na divisão do item"),
            Map.entry("ck_lt_quantidade", "A quantidade da transferência deve ser maior que zero"),
            // Fiscal (F19)
            Map.entry("ux_perfil_tributario_nome", "Já existe um perfil tributário com este nome"),
            Map.entry("ck_pt_nome", "Informe o nome do perfil tributário"),
            Map.entry("ck_pt_icms", "Informe o CST ou o CSOSN do ICMS (só um dos dois)"),
            Map.entry("ck_pt_aliquotas", "As alíquotas devem estar entre 0 e 100%"),
            Map.entry("ck_pt_reducao", "A redução da base do ICMS deve ser maior que 0 e menor que 100%"),
            Map.entry("ck_produto_origem", "A origem da mercadoria vai de 0 a 8"),
            Map.entry("fk_produto_perfil_tributario", "Este perfil tributário está em uso e não pode ser excluído"),
            // NFC-e (F23)
            Map.entry("ck_nfce_numeracao_numero", "A numeração da NFC-e desta série chegou ao fim: escolha outra série "
                    + "em Configurações › NFC-e"),
            Map.entry("uq_nfce_numero", "Este número de NFC-e já foi usado nesta série"),
            Map.entry("uq_nfce_chave", "Esta chave de acesso já pertence a outra NFC-e"),
            Map.entry("ux_nfce_venda_vigente", "Esta venda já tem uma NFC-e"),
            Map.entry("ck_nfce_ambiente", "O sistema só emite NFC-e em homologação"),
            Map.entry("ck_venda_consumidor_documento", "O CPF ou CNPJ na nota tem 11 ou 14 dígitos"),
            Map.entry("ck_nfce_evento_justificativa", "A justificativa tem de 15 a 255 caracteres"),
            Map.entry("ck_nfce_inut_justificativa", "A justificativa tem de 15 a 255 caracteres"));

    private MensagensBanco() {
    }

    public static Optional<String> mensagem(String constraint) {
        return constraint == null ? Optional.empty() : Optional.ofNullable(MENSAGENS.get(constraint));
    }
}
