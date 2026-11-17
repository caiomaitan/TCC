package br.com.varesync.model;

/**
 * Uma linha do comprovante: texto à esquerda e valor à direita. A mesma lista vira o texto da tela e o PDF.
 */
public record LinhaComprovante(Estilo estilo, String esquerda, String direita) {

    public enum Estilo {
        NORMAL,
        NEGRITO,
        CENTRO,
        CENTRO_NEGRITO,
        SEPARADOR,
        /** Texto em toda a largura, sem valor à direita (descrição do item, motivo). */
        TEXTO,
        /** QR Code do DANFE NFC-e (F23.7): o conteúdo vai em {@code esquerda}; no PDF e no papel, a imagem. */
        QRCODE
    }

    public static LinhaComprovante normal(String esquerda, String direita) {
        return new LinhaComprovante(Estilo.NORMAL, esquerda, direita);
    }

    public static LinhaComprovante negrito(String esquerda, String direita) {
        return new LinhaComprovante(Estilo.NEGRITO, esquerda, direita);
    }

    public static LinhaComprovante centro(String texto) {
        return new LinhaComprovante(Estilo.CENTRO, texto, "");
    }

    public static LinhaComprovante centroNegrito(String texto) {
        return new LinhaComprovante(Estilo.CENTRO_NEGRITO, texto, "");
    }

    public static LinhaComprovante texto(String texto) {
        return new LinhaComprovante(Estilo.TEXTO, texto, "");
    }

    public static LinhaComprovante qrCode(String conteudo) {
        return new LinhaComprovante(Estilo.QRCODE, conteudo, "");
    }

    public static LinhaComprovante separador() {
        return new LinhaComprovante(Estilo.SEPARADOR, "", "");
    }
}
