package br.com.varesync.model;

import java.math.BigDecimal;

/** Situação do cliente para vender no crediário (F14.2). */
public record ClienteCrediario(long id, String nome, boolean ativo, BigDecimal limiteCredito, boolean bloqueado) {
}
