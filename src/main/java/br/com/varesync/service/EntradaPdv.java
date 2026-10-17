package br.com.varesync.service;

import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;

/**
 * O que foi digitado ou lido no campo do PDV (6.6): {@code código}, {@code quantidade*código} (ex.: {@code 3*7891000315507},
 * {@code 0,345*123}) ou texto, que abre a busca (F2) já filtrada. A etiqueta de balança é da Fase 8.
 *
 * @param quantidade informada antes do {@code *}; nula se não houve
 * @param codigo     só dígitos (código de barras ou código interno); nulo quando é busca por texto
 * @param texto      termo da busca por nome; nulo quando é código
 */
public record EntradaPdv(BigDecimal quantidade, String codigo, String texto) {

    /** @throws NegocioException quantidade inválida ou faltando uma das partes do {@code *} */
    public static EntradaPdv interpretar(String digitado) {
        String entrada = Textos.normalizarEspacos(digitado);
        if (entrada.isEmpty()) {
            throw new NegocioException("Leia o código de barras ou digite o código ou o nome do produto.");
        }
        BigDecimal quantidade = null;
        int asterisco = entrada.indexOf('*');
        if (asterisco >= 0) {
            String parteQuantidade = entrada.substring(0, asterisco).strip();
            entrada = entrada.substring(asterisco + 1).strip();
            if (parteQuantidade.isEmpty()) {
                throw new NegocioException("Informe a quantidade antes do * (ex.: 3*7891000315507).");
            }
            if (entrada.isEmpty()) {
                throw new NegocioException("Informe o código do produto depois do * (ex.: 3*7891000315507).");
            }
            try {
                quantidade = Dinheiro.ler(parteQuantidade);
            } catch (IllegalArgumentException e) {
                throw new NegocioException("Quantidade inválida (\"" + parteQuantidade + "\").");
            }
            if (quantidade.signum() <= 0) {
                throw new NegocioException("A quantidade deve ser maior que zero.");
            }
        }
        return Textos.soDigitos(entrada) ? new EntradaPdv(quantidade, entrada, null)
                : new EntradaPdv(quantidade, null, entrada);
    }

    public boolean busca() {
        return texto != null;
    }
}
