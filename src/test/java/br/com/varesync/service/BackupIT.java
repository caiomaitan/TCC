package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.varesync.BancoTeste;
import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.AlertasPainel;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.Permissao;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Backup (6.12) com o pg_dump de verdade sobre o banco de teste (o teste é pulado se não houver pg_dump nesta
 * máquina), retenção dos últimos N, automático no máximo um por dia, auditoria, permissão e o alerta do painel (7.5).
 */
class BackupIT extends IntegracaoBase {

    @TempDir
    Path pasta;

    private AcessoTeste t;
    private Sessao admin;
    private BackupService.Conexao conexao;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        admin = t.supervisor();
        BancoTeste.Credenciais c = BancoTeste.credenciais();
        conexao = BackupService.Conexao.deUrl(c.url(), c.usuario(), c.senha());
    }

    private BackupService real() {
        return t.backup(ExecutorProcesso.sistema(), conexao, pasta, BackupService.instalacoesPadrao(),
                System.getenv("PATH"));
    }

    private void manter(int quantos) {
        Configuracoes c = t.configuracoes.carregar(admin);
        t.configuracoes.salvar(admin, new Configuracoes(c.loja(), c.venda(), c.margemMinimaPct(), c.diasSemGiro(),
                c.balanca(), new Configuracoes.Backup("", "", quantos), c.rodapeComprovante(),
                c.seguranca()));
    }

    @Test
    void backupRealGeraOArquivoNoFormatoDoPgDumpEMantemSoOsUltimosN() throws Exception {
        BackupService backup = real();
        assumeTrue(backup.localizarPgDump("").isPresent(), "pg_dump não instalado nesta máquina");
        manter(2);
        Files.writeString(pasta.resolve("varesync_20200101_0800.backup"), "antigo");
        Files.writeString(pasta.resolve("varesync_20200102_0800.backup"), "antigo");

        BackupService.Resultado r = backup.fazer(admin);
        assertThat(r.arquivo().getFileName().toString()).matches("varesync_\\d{8}_\\d{4}\\.backup");
        assertThat(r.arquivo().getFileName().toString()).isEqualTo("varesync_20261006_"
                + java.time.format.DateTimeFormatter.ofPattern("HHmm").format(
                java.time.OffsetDateTime.now(t.relogio).atZoneSameInstant(t.relogio.getZone())) + ".backup");
        assertThat(r.tamanho()).isGreaterThan(1000);
        try (InputStream in = Files.newInputStream(r.arquivo())) {
            assertThat(new String(in.readNBytes(5))).isEqualTo("PGDMP"); // formato custom (-Fc)
        }
        assertThat(r.removidos()).containsExactly("varesync_20200101_0800.backup");
        assertThat(Files.list(pasta).map(p -> p.getFileName().toString()).toList())
                .containsExactlyInAnyOrder(r.arquivo().getFileName().toString(), "varesync_20200102_0800.backup");
        assertThat(t.auditoria(AcaoAuditoria.BACKUP_REALIZADO)).singleElement().asString()
                .startsWith("Backup manual gravado em " + r.arquivo())
                .contains("removidos os antigos: varesync_20200101_0800.backup");

        // Um minuto depois: novo arquivo; ficam só os 2 mais recentes.
        t.relogio.avancar(Duration.ofMinutes(1));
        BackupService.Resultado segundo = backup.fazer(admin);
        assertThat(segundo.removidos()).containsExactly("varesync_20200102_0800.backup");
        assertThat(BackupService.arquivos(pasta)).extracting(BackupService.ArquivoBackup::nome)
                .containsExactly(segundo.arquivo().getFileName().toString(), r.arquivo().getFileName().toString());
        assertThat(Files.list(pasta).filter(p -> p.toString().endsWith(".tmp")).count()).isZero();
    }

    @Test
    void automaticoNoMaximoUmPorDiaParaQualquerUsuario() throws Exception {
        BackupService backup = real();
        assumeTrue(backup.localizarPgDump("").isPresent(), "pg_dump não instalado nesta máquina");
        t.usuarios.criar(admin, "caixa1", "Operador", false, EnumSet.of(Permissao.PDV), "inicial1", "inicial1");
        Sessao operador = t.acesso.entrar("caixa1", "inicial1");

        assertThat(backup.automaticoPendente()).isTrue();
        assertThat(backup.fazerAutomatico(operador)).isPresent();
        assertThat(backup.automaticoPendente()).isFalse();
        assertThat(backup.fazerAutomatico(admin)).isEmpty(); // já houve backup hoje
        assertThat(t.auditoria(AcaoAuditoria.BACKUP_REALIZADO)).singleElement().asString()
                .startsWith("Backup automático (ao sair do sistema) gravado em");

        t.relogio.avancar(Duration.ofDays(1));
        assertThat(backup.automaticoPendente()).isTrue();
        assertThatThrownBy(() -> backup.fazer(operador)).isInstanceOf(PermissaoNegadaException.class);
    }

    @Test
    void semPgDumpVersaoAntigaOuFalhaDoProgramaDaoMensagemClaraESemArquivo() throws Exception {
        BackupService semPrograma = t.backup(ExecutorProcesso.sistema(), conexao, pasta, List.of(), "");
        assertThatThrownBy(() -> semPrograma.fazer(admin)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("Não encontrei o pg_dump");

        Path falso = Files.writeString(pasta.resolve("pg_dump.exe"), "x");
        List<List<String>> chamadas = new ArrayList<>();
        BackupService antigo = t.backup((comando, ambiente, limite) -> {
            chamadas.add(comando);
            return new ExecutorProcesso.Resultado(0, "pg_dump (PostgreSQL) 9.6.24");
        }, conexao, pasta, List.of(), pasta.toString());
        assertThatThrownBy(() -> antigo.fazer(admin)).hasMessageContaining("versão 9")
                .hasMessageContaining("mesma versão do servidor ou mais novo");
        assertThat(chamadas).singleElement().isEqualTo(List.of(falso.toString(), "--version"));

        BackupService comErro = t.backup((comando, ambiente, limite) -> {
            if (comando.contains("--version")) {
                return new ExecutorProcesso.Resultado(0, "pg_dump (PostgreSQL) 99.0");
            }
            String arquivo = comando.stream().filter(a -> a.startsWith("--file=")).findFirst().orElseThrow()
                    .substring("--file=".length());
            Files.writeString(Path.of(arquivo), "parcial");
            return new ExecutorProcesso.Resultado(1, "pg_dump: error: connection failed");
        }, conexao, pasta, List.of(), pasta.toString());
        assertThatThrownBy(() -> comErro.fazer(admin)).hasMessageContaining("o pg_dump informou um erro");
        assertThat(BackupService.arquivos(pasta)).isEmpty();
        assertThat(Files.list(pasta).filter(p -> p.toString().endsWith(".tmp")).count()).isZero();
        assertThat(t.auditoria(AcaoAuditoria.BACKUP_REALIZADO)).isEmpty();

        BackupService indisponivel = t.backup(ExecutorProcesso.sistema(), null, pasta, List.of(), "");
        assertThatThrownBy(() -> indisponivel.fazer(admin)).hasMessageContaining("não está disponível");
    }

    /**
     * pg_dump falso que grava um arquivo e pg_restore falso que o lê ({@code leBackup}) ou o recusa.
     */
    private static ExecutorProcesso falso(boolean leBackup) {
        return (comando, ambiente, limite) -> {
            if (comando.contains("--version")) {
                return new ExecutorProcesso.Resultado(0, "pg_dump (PostgreSQL) 99.1");
            }
            if (comando.contains("--list")) {
                assertThat(Path.of(comando.get(0)).getFileName().toString()).isEqualTo("pg_restore.exe");
                assertThat(comando.get(2)).endsWith(".backup.tmp");
                return leBackup ? new ExecutorProcesso.Resultado(0, ";\n; Archive created at 2026-10-24\n")
                        : new ExecutorProcesso.Resultado(1,
                        "pg_restore: error: input file does not appear to be a valid archive");
            }
            String arquivo = comando.stream().filter(a -> a.startsWith("--file=")).findFirst().orElseThrow()
                    .substring("--file=".length());
            Files.writeString(Path.of(arquivo), "PGDMP de teste");
            return new ExecutorProcesso.Resultado(0, "");
        };
    }

    /** Pasta com pg_dump.exe e, se pedido, pg_restore.exe (arquivos falsos); devolve a pasta para o PATH. */
    private String programasFalsos(boolean comPgRestore) throws IOException {
        Path bin = Files.createDirectories(pasta.resolve("bin"));
        Files.writeString(bin.resolve("pg_dump.exe"), "x");
        if (comPgRestore) {
            Files.writeString(bin.resolve("pg_restore.exe"), "x");
        }
        return bin.toString();
    }

    /** Aceite F10 (backup): um backup que o {@code pg_restore --list} não lê é descartado e avisado. */
    @Test
    void backupQueOPgRestoreNaoLeEDescartadoEAvisado() throws Exception {
        BackupService ilegivel = t.backup(falso(false), conexao, pasta, List.of(), programasFalsos(true));
        assertThatThrownBy(() -> ilegivel.fazer(admin)).isInstanceOf(NegocioException.class)
                .hasMessage("O backup não foi concluído: o arquivo gerado não passou na conferência "
                        + "(pg_restore --list) e foi descartado. Os detalhes foram registrados no log.");
        assertThat(BackupService.arquivos(pasta)).isEmpty();
        assertThat(Files.list(pasta).filter(p -> p.toString().endsWith(".tmp")).count()).isZero();
        assertThat(t.auditoria(AcaoAuditoria.BACKUP_REALIZADO)).isEmpty();
        assertThat(t.painel.carregar(admin).alertas().backupAtrasado().ultimo())
                .as("o painel continua sem backup").isNull();
        t.usuarios.criar(admin, "caixa1", "Operador", false, EnumSet.of(Permissao.PDV), "inicial1", "inicial1");
        Sessao operador = t.acesso.entrar("caixa1", "inicial1");
        assertThatThrownBy(() -> ilegivel.fazerAutomatico(operador)).hasMessageContaining("não passou na conferência");
        assertThat(ilegivel.automaticoPendente()).as("o automático tenta de novo na próxima saída").isTrue();

        Files.delete(pasta.resolve("bin/pg_restore.exe"));
        assertThatThrownBy(() -> ilegivel.fazer(admin))
                .hasMessageStartingWith("O backup não foi concluído: não encontrei o pg_restore na pasta do pg_dump");
        assertThat(BackupService.arquivos(pasta)).isEmpty();

        BackupService bom = t.backup(falso(true), conexao, pasta, List.of(), programasFalsos(true));
        assertThat(bom.fazer(admin).arquivo().getFileName().toString()).matches("varesync_\\d{8}_\\d{4}\\.backup");
        assertThat(t.auditoria(AcaoAuditoria.BACKUP_REALIZADO)).hasSize(1);
    }

    @Test
    void pgRestoreDaMesmaPastaDoPgDump() {
        assertThat(BackupService.pgRestoreAoLado(Path.of("C:\\PG\\16\\bin\\pg_dump.exe")))
                .isEqualTo(Path.of("C:\\PG\\16\\bin\\pg_restore.exe"));
        assertThat(BackupService.pgRestoreAoLado(Path.of("/usr/bin/pg_dump"))).isEqualTo(Path.of("/usr/bin/pg_restore"));
    }

    @Test
    void situacaoParaAAbaBackup() throws IOException {
        Files.writeString(pasta.resolve("varesync_20261001_2200.backup"), "x");
        BackupService.Situacao s = t.backup(ExecutorProcesso.sistema(), conexao, pasta, List.of(), "")
                .situacao(admin);
        assertThat(s.pasta()).isEqualTo(pasta);
        assertThat(s.pgDump()).isEmpty();
        assertThat(s.ultimo()).isEmpty();
        assertThat(s.arquivos()).extracting(BackupService.ArquivoBackup::nome)
                .containsExactly("varesync_20261001_2200.backup");
    }

    @Test
    void alertaDoPainelSoParaOSupervisorComBackupHaMaisDeSeteDias() throws Exception {
        AlertasPainel.BackupAtrasado nunca = t.painel.carregar(admin).alertas().backupAtrasado();
        assertThat(nunca).isNotNull();
        assertThat(nunca.ultimo()).isNull();

        BackupService backup = t.backup(falso(true), conexao, pasta, List.of(), programasFalsos(true));
        backup.fazer(admin);
        assertThat(t.painel.carregar(admin).alertas().backupAtrasado()).isNull();

        t.relogio.avancar(Duration.ofDays(7));
        assertThat(t.painel.carregar(admin).alertas().backupAtrasado()).isNull(); // 7 dias: ainda não
        t.relogio.avancar(Duration.ofDays(1));
        AlertasPainel.BackupAtrasado atrasado = t.painel.carregar(admin).alertas().backupAtrasado();
        assertThat(atrasado.dias()).isEqualTo(8);
        assertThat(atrasado.ultimo()).isNotNull();

        t.usuarios.criar(admin, "gerente", "Gerente", false, EnumSet.allOf(Permissao.class), "inicial1", "inicial1");
        Sessao gerente = t.acesso.entrar("gerente", "inicial1");
        assertThat(t.painel.carregar(gerente).alertas().backupAtrasado()).isNull(); // só supervisor
    }
}
