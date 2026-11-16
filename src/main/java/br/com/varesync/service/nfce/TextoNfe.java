package br.com.varesync.service.nfce;

/**
 * Texto para os campos do leiaute (tipo TString: caracteres de espaço a ÿ, sem espaço no começo nem no fim). Os
 * caracteres fora dessa faixa (travessão, aspas curvas, emoji) viram espaço; "&lt;" e "&gt;" também, porque a
 * biblioteca troca as entidades de volta por esses caracteres depois de gerar o XML. Espaços repetidos viram um só, e o
 * texto é cortado no tamanho do campo.
 */
public final class TextoNfe {

    private TextoNfe() {
    }

    public static String limpar(String texto, int tamanho) {
        if (texto == null) {
            return "";
        }
        StringBuilder s = new StringBuilder(texto.length());
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            boolean aceito = c >= ' ' && c <= 'ÿ' && c != '<' && c != '>' && c != 0x7F && (c < 0x80 || c > 0x9F);
            s.append(aceito ? c : ' ');
        }
        String limpo = s.toString().replaceAll(" {2,}", " ").strip();
        return limpo.length() > tamanho ? limpo.substring(0, tamanho).strip() : limpo;
    }
}
