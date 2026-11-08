package br.com.varesync.model;

/**
 * Dados fiscais do produto como a tela envia (F19.2): NCM e CEST com ou sem pontos (vazio = não informado), origem
 * (nula = não informada) e perfil (nulo = sem perfil).
 */
public record DadosFiscais(String ncm, String cest, Integer origem, Long perfilId) {
}
