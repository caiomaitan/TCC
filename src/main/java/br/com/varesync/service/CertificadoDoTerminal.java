package br.com.varesync.service;

/**
 * Onde fica o certificado A1 deste terminal (F23.2): o {@code ~/.varesync/config.properties}, fora do banco e do
 * repositório. O {@code AppContext} liga esta interface ao {@code AppConfig}; os testes usam uma em memória.
 */
public interface CertificadoDoTerminal {

    /** Caminho do arquivo .pfx ou .p12; vazio = sem certificado. */
    String caminho();

    /** Senha que vale (a da variável {@code VARESYNC_CERT_SENHA}, se existir, ou a do arquivo). */
    String senha();

    /** Se a senha vem da variável de ambiente (a tela não pede a senha). */
    boolean senhaNaVariavel();

    /** Grava o novo caminho e a senha (caminho vazio tira o certificado). */
    void gravar(String caminho, String senha);
}
