package br.com.varesync.model;

import java.time.LocalDate;

/** Como a parte no crediário é parcelada (F14.2): número de parcelas e vencimento da primeira. */
public record PlanoCrediario(int parcelas, LocalDate primeiroVencimento) {
}
