package br.com.varesync.model;

import java.util.List;

/**
 * O que aconteceu ao incluir produtos num inventário (6.7): quantos entraram, quantos já estavam nele e quais ficaram
 * de fora por estarem em outro inventário aberto ("Café 500 g (inventário nº 3)").
 */
public record InclusaoInventario(int incluidos, int jaNoInventario, List<String> emOutroInventario) {

    /** Quantos nomes de produtos de fora aparecem na mensagem; os demais viram "e mais N". */
    static final int NOMES_NA_MENSAGEM = 5;

    public InclusaoInventario {
        emOutroInventario = List.copyOf(emOutroInventario);
    }

    /** "3 produtos incluídos. 1 já estava neste inventário. 1 não foi incluído porque está em outro …". */
    public String mensagem() {
        StringBuilder texto = new StringBuilder();
        texto.append(incluidos == 0 ? "Nenhum produto incluído."
                : incluidos == 1 ? "1 produto incluído." : incluidos + " produtos incluídos.");
        if (jaNoInventario > 0) {
            texto.append(jaNoInventario == 1 ? " 1 já estava neste inventário."
                    : " " + jaNoInventario + " já estavam neste inventário.");
        }
        int fora = emOutroInventario.size();
        if (fora > 0) {
            texto.append(fora == 1 ? " 1 não foi incluído porque está em outro inventário aberto: "
                    : " " + fora + " não foram incluídos porque estão em outro inventário aberto: ");
            texto.append(String.join(", ", emOutroInventario.subList(0, Math.min(fora, NOMES_NA_MENSAGEM))));
            if (fora > NOMES_NA_MENSAGEM) {
                texto.append(" e mais ").append(fora - NOMES_NA_MENSAGEM);
            }
            texto.append('.');
        }
        return texto.toString();
    }
}
