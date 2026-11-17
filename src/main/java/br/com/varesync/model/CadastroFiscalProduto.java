package br.com.varesync.model;

/**
 * O cadastro fiscal do produto como está no banco, para a conferência do PDV com a NFC-e ligada (F23.6).
 *
 * @param perfil nulo = sem perfil (com o perfil inativo, {@code perfil.ativo()} é falso)
 */
public record CadastroFiscalProduto(String produto, String ncm, String cest, Integer origem, PerfilTributario perfil) {
}
