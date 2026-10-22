package br.com.varesync.model;

import java.time.OffsetDateTime;

/**
 * Linha da consulta de auditoria (6.13).
 *
 * @param usuario    "Nome (login)", ou nulo quando não há usuário (login recusado com login inexistente)
 * @param entidadeId número do registro afetado (venda, produto…), se houver
 */
public record RegistroAuditoria(long id, OffsetDateTime criadoEm, String usuario, AcaoAuditoria acao,
                                String entidade, Long entidadeId, String descricao, String terminal) {
}
