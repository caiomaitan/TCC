package br.com.varesync.model;

import java.util.List;
import java.util.Optional;

/**
 * Meio de pagamento da NFC-e ({@code tPag}, F22.1): os códigos da tabela oficial (Informes Técnicos 2024.002 e
 * 2026.001) que servem para a NFC-e, os mesmos do CHECK {@code ck_fp_tpag}. Ficam de fora o 90 (sem pagamento),
 * que a NFC-e não usa.
 */
public record MeioPagamentoNfce(String codigo, String descricao) {

    public static final List<MeioPagamentoNfce> TODOS = List.of(
            new MeioPagamentoNfce("01", "Dinheiro"),
            new MeioPagamentoNfce("02", "Cheque"),
            new MeioPagamentoNfce("03", "Cartão de crédito"),
            new MeioPagamentoNfce("04", "Cartão de débito"),
            new MeioPagamentoNfce("05", "Cartão da loja, crediário digital e outros crediários"),
            new MeioPagamentoNfce("10", "Vale-alimentação"),
            new MeioPagamentoNfce("11", "Vale-refeição"),
            new MeioPagamentoNfce("12", "Vale-presente"),
            new MeioPagamentoNfce("13", "Vale-combustível"),
            new MeioPagamentoNfce("14", "Duplicata mercantil"),
            new MeioPagamentoNfce("15", "Boleto bancário"),
            new MeioPagamentoNfce("16", "Depósito bancário"),
            new MeioPagamentoNfce("17", "PIX dinâmico"),
            new MeioPagamentoNfce("18", "Transferência bancária, carteira digital"),
            new MeioPagamentoNfce("19", "Programa de fidelidade, cashback, crédito virtual"),
            new MeioPagamentoNfce("20", "PIX estático"),
            new MeioPagamentoNfce("21", "Crédito em loja"),
            new MeioPagamentoNfce("22", "Pagamento eletrônico não informado (falha de hardware do emissor)"),
            new MeioPagamentoNfce("23", "PIX automático"),
            new MeioPagamentoNfce("24", "TEF (book transfer)"),
            new MeioPagamentoNfce("99", "Outros"));

    public static Optional<MeioPagamentoNfce> doCodigo(String codigo) {
        return TODOS.stream().filter(m -> m.codigo.equals(codigo)).findFirst();
    }

    /** "20 — PIX estático"; código fora da tabela aparece sozinho. */
    public static String rotulo(String codigo) {
        return doCodigo(codigo).map(MeioPagamentoNfce::toString).orElse(codigo == null ? "" : codigo);
    }

    /**
     * O tPag que a V23 (e o trigger {@code fn_forma_pagamento_tpag}) dá a cada tipo. O PIX vem como estático (20):
     * quem usa o PIX gerado pela maquininha troca para 17.
     */
    public static String padrao(TipoFormaPagamento tipo) {
        return switch (tipo) {
            case DINHEIRO -> "01";
            case CARTAO_CREDITO -> "03";
            case CARTAO_DEBITO -> "04";
            case CREDIARIO -> "05";
            case PIX -> "20";
            case CREDITO_CLIENTE -> "21";
            case OUTROS -> "99";
        };
    }

    @Override
    public String toString() {
        return codigo + " — " + descricao;
    }
}
