package br.com.varesync.config;

import br.com.varesync.service.CertificadoDoTerminal;
import java.nio.file.Path;

/**
 * O certificado A1 do terminal no {@code ~/.varesync/config.properties} (F23.2). Relê o arquivo a cada uso, para valer
 * a troca feita na tela sem reiniciar o sistema; a senha da variável {@code VARESYNC_CERT_SENHA} vale no lugar da do
 * arquivo.
 */
public final class CertificadoNoArquivo implements CertificadoDoTerminal {

    private final Path arquivo;

    public CertificadoNoArquivo(Path arquivo) {
        this.arquivo = arquivo;
    }

    @Override
    public String caminho() {
        return AppConfig.carregar(arquivo).certificado();
    }

    @Override
    public String senha() {
        return AppConfig.carregar(arquivo).senhaCertificado();
    }

    @Override
    public boolean senhaNaVariavel() {
        return AppConfig.senhaCertificadoNaVariavel();
    }

    @Override
    public synchronized void gravar(String caminho, String senha) {
        AppConfig.carregar(arquivo).comCertificado(caminho, senha).salvar();
    }
}
