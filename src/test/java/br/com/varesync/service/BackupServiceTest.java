package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Partes do backup (6.12) que não precisam de banco nem do pg_dump: conexão, comando, localização, retenção. */
class BackupServiceTest {

    @TempDir
    Path pasta;

    private static final BackupService.Conexao CONEXAO = new BackupService.Conexao("localhost", 5432, "varesync",
            "varesync", "s3gr3d0");

    private BackupService servico(List<Path> instalacoes, String path) {
        return new BackupService(null, null, null, null, null, CONEXAO, pasta, instalacoes, path,
                Clock.systemDefaultZone());
    }

    @Test
    void conexaoPelaUrlDaConfiguracao() {
        assertThat(BackupService.Conexao.deUrl("jdbc:postgresql://localhost:5432/varesync", "u", "s"))
                .isEqualTo(new BackupService.Conexao("localhost", 5432, "varesync", "u", "s"));
        assertThat(BackupService.Conexao.deUrl("jdbc:postgresql://servidor.loja/varesync?ssl=false", "u", null))
                .isEqualTo(new BackupService.Conexao("servidor.loja", 5432, "varesync", "u", ""));
        assertThat(BackupService.Conexao.deUrl("jdbc:postgresql://192.168.0.10:5433/loja", "u", "s").porta())
                .isEqualTo(5433);
        assertThat(BackupService.Conexao.deUrl("jdbc:postgresql:varesync", "u", "s").host()).isEqualTo("localhost");
        assertThatThrownBy(() -> BackupService.Conexao.deUrl("jdbc:mysql://x/y", "u", "s"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(CONEXAO.toString()).doesNotContain("s3gr3d0");
    }

    @Test
    void senhaVaiNoAmbienteENuncaNaLinhaDeComando() {
        BackupService s = servico(List.of(), "");
        List<String> comando = s.comando(Path.of("pg_dump.exe"), pasta.resolve("x.tmp"));
        assertThat(comando).contains("--format=custom", "--no-password", "--host=localhost", "--port=5432",
                "--username=varesync", "--dbname=varesync");
        assertThat(String.join(" ", comando)).doesNotContain("s3gr3d0");
        assertThat(s.ambiente()).containsEntry("PGPASSWORD", "s3gr3d0");
    }

    @Test
    void versaoDoPgDump() {
        assertThat(BackupService.versao("pg_dump (PostgreSQL) 16.4")).hasValue(16);
        assertThat(BackupService.versao("pg_dump (PostgreSQL) 17.0 (Debian 17.0-1)")).hasValue(17);
        assertThat(BackupService.versao("erro")).isEmpty();
    }

    @Test
    void localizaConfiguradoNoPathOuNaInstalacaoDeVersaoMaisAlta() throws IOException {
        Path instalacoes = Files.createDirectories(pasta.resolve("PostgreSQL"));
        Path antigo = arquivo(instalacoes.resolve("9.6/bin/pg_dump.exe"));
        Path dezesseis = arquivo(instalacoes.resolve("16/bin/pg_dump.exe"));
        Files.createDirectories(instalacoes.resolve("17/bin")); // pasta sem o programa: ignorada
        assertThat(antigo).exists();

        BackupService s = servico(List.of(instalacoes), "");
        assertThat(s.localizarPgDump("")).hasValue(dezesseis);

        Path noPath = arquivo(pasta.resolve("ferramentas/pg_dump.exe"));
        assertThat(servico(List.of(instalacoes), pasta.resolve("vazia") + File.pathSeparator + noPath.getParent())
                .localizarPgDump(null)).hasValue(noPath);

        Path configurado = arquivo(pasta.resolve("outro/pg_dump.exe"));
        assertThat(s.localizarPgDump(configurado.toString())).hasValue(configurado);
        assertThat(s.localizarPgDump(configurado.getParent().toString())).hasValue(configurado); // a pasta bin
        assertThat(s.localizarPgDump(pasta.resolve("nao-existe.exe").toString())).isEmpty();
        assertThat(servico(List.of(pasta.resolve("sem-postgres")), "").localizarPgDump("")).isEmpty();
    }

    @Test
    void mantemSoOsUltimosNPeloNomeEIgnoraOutrosArquivos() throws IOException {
        for (String nome : List.of("varesync_20261001_2200.backup", "varesync_20261002_2200.backup",
                "varesync_20261003_2200.backup", "varesync_20261003_2215.backup", "anotacoes.txt",
                "varesync_20261004_2200.backup.tmp", "copia_varesync_20260101_0000.backup")) {
            arquivo(pasta.resolve(nome));
        }
        List<String> removidos = BackupService.manterUltimos(pasta, 2);
        assertThat(removidos).containsExactly("varesync_20261002_2200.backup", "varesync_20261001_2200.backup");
        assertThat(BackupService.arquivos(pasta)).extracting(BackupService.ArquivoBackup::nome)
                .containsExactly("varesync_20261003_2215.backup", "varesync_20261003_2200.backup");
        assertThat(pasta.resolve("anotacoes.txt")).exists();
        assertThat(pasta.resolve("varesync_20261004_2200.backup.tmp")).exists();
        assertThat(BackupService.manterUltimos(pasta.resolve("nao-existe"), 2)).isEmpty();
    }

    @Test
    void tamanhoLegivel() {
        assertThat(BackupService.tamanhoLegivel(300)).isEqualTo("1 KB");
        assertThat(BackupService.tamanhoLegivel(850 * 1024)).isEqualTo("850 KB");
        assertThat(BackupService.tamanhoLegivel(12_900_000)).isEqualTo("12,3 MB");
    }

    private static Path arquivo(Path caminho) throws IOException {
        Files.createDirectories(caminho.getParent());
        return Files.writeString(caminho, "x");
    }
}
