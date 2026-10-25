package br.com.varesync.service;

import br.com.varesync.dao.AuditoriaDAO;
import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Configuracoes;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backup do banco (6.12) com {@code pg_dump -Fc}: manual (Configurações, só supervisor) e automático ao sair do
 * sistema, no máximo um por dia (o último backup é o último {@code BACKUP_REALIZADO} da auditoria). O caminho do
 * {@code pg_dump} é o configurado ou, se vazio, o do PATH ou o de {@code C:\Program Files\PostgreSQL\*\bin} (o de
 * versão mais alta). A senha do banco vai na variável {@code PGPASSWORD} do processo, nunca na linha de comando. O
 * arquivo {@code varesync_AAAAMMDD_HHmm.backup} é gravado primeiro como {@code .tmp} e só ganha o nome final se o
 * {@code pg_dump} terminar bem e o {@code pg_restore --list} conseguir ler o arquivo (F10.9); depois ficam só os
 * últimos {@code backup.manter_ultimos}.
 */
public class BackupService {

    public static final String ENTIDADE_BACKUP = "backup";
    /** O painel avisa o supervisor quando o último backup tem mais de 7 dias (7.5). */
    public static final int DIAS_ALERTA = 7;
    static final Pattern NOME_ARQUIVO = Pattern.compile("varesync_\\d{8}_\\d{4}\\.backup");
    private static final DateTimeFormatter FORMATO_NOME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");
    private static final Pattern VERSAO = Pattern.compile("\\)\\s*(\\d+)");
    private static final List<String> EXECUTAVEIS = List.of("pg_dump.exe", "pg_dump");
    private static final Duration LIMITE_BACKUP = Duration.ofHours(1);
    private static final Duration LIMITE_VERSAO = Duration.ofSeconds(30);
    private static final Duration LIMITE_CONFERENCIA = Duration.ofMinutes(10);
    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    /** Banco a copiar, lido da configuração local (3.3). {@link #toString()} não mostra a senha. */
    public record Conexao(String host, int porta, String banco, String usuario, String senha) {

        /** "jdbc:postgresql://host[:porta]/banco[?parâmetros]" ou a forma curta "jdbc:postgresql:banco" (localhost). */
        public static Conexao deUrl(String url, String usuario, String senha) {
            Matcher curta = Pattern.compile("^jdbc:postgresql:([^/?][^?]*)").matcher(url.strip());
            if (curta.find()) {
                return new Conexao("localhost", 5432, curta.group(1), usuario, senha == null ? "" : senha);
            }
            Matcher m = Pattern.compile("^jdbc:postgresql://([^:/?]+)(?::(\\d+))?/([^?]+)").matcher(url.strip());
            if (!m.find()) {
                throw new IllegalArgumentException("URL do banco não reconhecida: " + url);
            }
            return new Conexao(m.group(1), m.group(2) == null ? 5432 : Integer.parseInt(m.group(2)), m.group(3),
                    usuario, senha == null ? "" : senha);
        }

        @Override
        public String toString() {
            return usuario + "@" + host + ":" + porta + "/" + banco;
        }
    }

    public record ArquivoBackup(String nome, long tamanho, OffsetDateTime modificadoEm) {
    }

    /**
     * Situação para a aba Backup.
     *
     * @param pgDump o {@code pg_dump} que será usado, se encontrado
     */
    public record Situacao(Path pasta, Optional<Path> pgDump, Optional<OffsetDateTime> ultimo,
                           List<ArquivoBackup> arquivos) {
    }

    /** @param removidos backups antigos apagados para ficar só com os últimos N */
    public record Resultado(Path arquivo, long tamanho, List<String> removidos, boolean automatico) {
    }

    private final Transacao transacao;
    private final ConfiguracaoDAO configuracaoDAO;
    private final AuditoriaDAO auditoriaDAO;
    private final AuditoriaService auditoria;
    private final ExecutorProcesso executor;
    private final Conexao conexao;
    private final Path pastaPadrao;
    private final List<Path> instalacoesPostgres;
    private final String path;
    private final Clock clock;

    /**
     * @param pastaPadrao         pasta usada com {@code backup.pasta} vazio
     * @param instalacoesPostgres onde procurar instalações do PostgreSQL ({@code C:\Program Files\PostgreSQL})
     * @param path                a variável PATH do sistema
     */
    public BackupService(Transacao transacao, ConfiguracaoDAO configuracaoDAO, AuditoriaDAO auditoriaDAO,
                         AuditoriaService auditoria, ExecutorProcesso executor, Conexao conexao, Path pastaPadrao,
                         List<Path> instalacoesPostgres, String path, Clock clock) {
        this.transacao = transacao;
        this.configuracaoDAO = configuracaoDAO;
        this.auditoriaDAO = auditoriaDAO;
        this.auditoria = auditoria;
        this.executor = executor;
        this.conexao = conexao;
        this.pastaPadrao = pastaPadrao;
        this.instalacoesPostgres = List.copyOf(instalacoesPostgres);
        this.path = path == null ? "" : path;
        this.clock = clock;
    }

    /** Onde o Windows instala o PostgreSQL. */
    public static List<Path> instalacoesPadrao() {
        return List.of(Path.of("C:\\Program Files\\PostgreSQL"), Path.of("C:\\Program Files (x86)\\PostgreSQL"));
    }

    // ----- Consulta -----

    public Situacao situacao(Sessao sessao) {
        sessao.exigirSupervisor();
        record Lido(Configuracoes config, Optional<OffsetDateTime> ultimo) {
        }
        Lido lido = transacao.executar(con -> new Lido(configuracaoDAO.carregar(con),
                auditoriaDAO.ultimo(con, AcaoAuditoria.BACKUP_REALIZADO)));
        Path pasta = pasta(lido.config());
        return new Situacao(pasta, localizarPgDump(lido.config().backup().pgDump()), lido.ultimo(), arquivos(pasta));
    }

    /** O automático ainda não rodou hoje? (nenhum backup, manual ou automático, com data de hoje) */
    public boolean automaticoPendente() {
        Optional<OffsetDateTime> ultimo = transacao.executar(
                con -> auditoriaDAO.ultimo(con, AcaoAuditoria.BACKUP_REALIZADO));
        LocalDate hoje = LocalDate.now(clock);
        return ultimo.map(u -> u.atZoneSameInstant(clock.getZone()).toLocalDate().isBefore(hoje)).orElse(true);
    }

    // ----- Backup -----

    /** Backup manual (Configurações › Backup), só para o supervisor. */
    public Resultado fazer(Sessao sessao) {
        sessao.exigirSupervisor();
        return executar(sessao, false);
    }

    /**
     * Backup automático ao sair do sistema, para qualquer usuário, se ainda não houve backup hoje.
     *
     * @return vazio quando já havia backup hoje
     */
    public Optional<Resultado> fazerAutomatico(Sessao sessao) {
        return automaticoPendente() ? Optional.of(executar(sessao, true)) : Optional.empty();
    }

    private Resultado executar(Sessao sessao, boolean automatico) {
        if (conexao == null) {
            throw new NegocioException("O backup não está disponível: o endereço do banco na configuração local não "
                    + "está no formato jdbc:postgresql://servidor:porta/banco.");
        }
        record Lido(Configuracoes config, int versaoServidor) {
        }
        Lido lido = transacao.executar(con -> new Lido(configuracaoDAO.carregar(con), versaoServidor(con)));
        Configuracoes.Backup config = lido.config().backup();
        Path pasta = pasta(lido.config());
        try {
            Files.createDirectories(pasta);
        } catch (IOException e) {
            log.error("Falha ao criar a pasta de backup {}", pasta, e);
            throw new NegocioException("Não foi possível criar a pasta de backup " + pasta + ". Confira o caminho em "
                    + "Configurações › Backup.");
        }
        Path pgDump = localizarPgDump(config.pgDump()).orElseThrow(() -> new NegocioException(config.pgDump()
                .isBlank() ? "Não encontrei o pg_dump (programa de backup do PostgreSQL). Informe o caminho dele em "
                + "Configurações › Backup (ex.: C:\\Program Files\\PostgreSQL\\16\\bin\\pg_dump.exe)."
                : "O pg_dump informado em Configurações › Backup não existe: " + config.pgDump() + "."));
        int versao = versaoPgDump(pgDump);
        if (versao < lido.versaoServidor()) {
            throw new NegocioException("O pg_dump encontrado é da versão " + versao + ", mas o servidor do banco é "
                    + "da versão " + lido.versaoServidor() + ". Use o pg_dump da mesma versão do servidor ou mais "
                    + "novo (" + pgDump + ").");
        }

        String nome = "varesync_" + FORMATO_NOME.format(OffsetDateTime.now(clock).atZoneSameInstant(clock.getZone()))
                + ".backup";
        Path destino = pasta.resolve(nome);
        Path temporario = pasta.resolve(nome + ".tmp");
        ExecutorProcesso.Resultado r;
        try {
            r = executor.executar(comando(pgDump, temporario), ambiente(), LIMITE_BACKUP);
        } catch (IOException e) {
            apagar(temporario);
            log.error("Falha ao executar o pg_dump {}", pgDump, e);
            throw new NegocioException("O backup não foi concluído: não foi possível executar o pg_dump. Os "
                    + "detalhes foram registrados no log.");
        }
        long tamanho = tamanho(temporario);
        if (r.codigo() != 0 || tamanho <= 0) {
            apagar(temporario);
            log.error("pg_dump terminou com código {} ({} bytes): {}", r.codigo(), tamanho, r.saida());
            throw new NegocioException("O backup não foi concluído: o pg_dump informou um erro. Os detalhes foram "
                    + "registrados no log.");
        }
        conferir(pgDump, temporario);
        mover(temporario, destino);
        List<String> removidos = manterUltimos(pasta, config.manterUltimos());
        transacao.executar(con -> {
            auditoria.registrar(con, sessao, AcaoAuditoria.BACKUP_REALIZADO, ENTIDADE_BACKUP, null,
                    "Backup " + (automatico ? "automático (ao sair do sistema)" : "manual") + " gravado em "
                            + destino + " (" + tamanhoLegivel(tamanho) + ")"
                            + (removidos.isEmpty() ? "" : "; removidos os antigos: " + String.join(", ", removidos)));
            return null;
        });
        log.info("Backup {} gravado em {} ({} bytes)", automatico ? "automático" : "manual", destino, tamanho);
        return new Resultado(destino, tamanho, removidos, automatico);
    }

    /**
     * Confere o arquivo recém-gravado (F10.9) com o {@code pg_restore --list} da mesma pasta do {@code pg_dump}, que
     * só lê o índice do arquivo (não conecta no banco). Se o {@code pg_restore} não existir, não rodar ou não ler o
     * arquivo, o arquivo é apagado como um backup que falhou: nada de {@code BACKUP_REALIZADO}, e quem fez vê o aviso.
     */
    void conferir(Path pgDump, Path arquivo) {
        Path pgRestore = pgRestoreAoLado(pgDump);
        if (!Files.isRegularFile(pgRestore)) {
            apagar(arquivo);
            log.error("pg_restore não encontrado em {} para conferir o backup", pgRestore);
            throw new NegocioException("O backup não foi concluído: não encontrei o pg_restore na pasta do pg_dump ("
                    + pgRestore + "), que confere o arquivo gerado. O arquivo foi descartado.");
        }
        ExecutorProcesso.Resultado r;
        try {
            r = executor.executar(List.of(pgRestore.toString(), "--list", arquivo.toString()), Map.of(),
                    LIMITE_CONFERENCIA);
        } catch (IOException e) {
            apagar(arquivo);
            log.error("Falha ao executar o pg_restore {} para conferir o backup", pgRestore, e);
            throw new NegocioException("O backup não foi concluído: não foi possível conferir o arquivo com o "
                    + "pg_restore, e ele foi descartado. Os detalhes foram registrados no log.");
        }
        if (r.codigo() != 0 || r.saida() == null || r.saida().isBlank()) {
            apagar(arquivo);
            log.error("pg_restore --list não leu o backup (código {}): {}", r.codigo(), r.saida());
            throw new NegocioException("O backup não foi concluído: o arquivo gerado não passou na conferência "
                    + "(pg_restore --list) e foi descartado. Os detalhes foram registrados no log.");
        }
    }

    /** {@code .../bin/pg_dump.exe} → {@code .../bin/pg_restore.exe} (o mesmo para o nome sem {@code .exe}). */
    static Path pgRestoreAoLado(Path pgDump) {
        String nome = pgDump.getFileName().toString().replace("pg_dump", "pg_restore");
        return pgDump.getParent() == null ? Path.of(nome) : pgDump.getParent().resolve(nome);
    }

    /** O comando, sem a senha (ela vai em {@code PGPASSWORD}); {@code --no-password} evita o pedido de senha. */
    List<String> comando(Path pgDump, Path arquivo) {
        return List.of(pgDump.toString(), "--format=custom", "--no-password", "--host=" + conexao.host(),
                "--port=" + conexao.porta(), "--username=" + conexao.usuario(), "--file=" + arquivo,
                "--dbname=" + conexao.banco());
    }

    Map<String, String> ambiente() {
        Map<String, String> ambiente = new LinkedHashMap<>();
        if (!conexao.senha().isEmpty()) {
            ambiente.put("PGPASSWORD", conexao.senha());
        }
        return ambiente;
    }

    // ----- pg_dump -----

    /** Configurado (arquivo, ou pasta que o contém) → PATH → instalações do PostgreSQL, da versão mais alta. */
    public Optional<Path> localizarPgDump(String configurado) {
        if (configurado != null && !configurado.isBlank()) {
            Path informado = Path.of(configurado.strip());
            if (Files.isRegularFile(informado)) {
                return Optional.of(informado);
            }
            return Files.isDirectory(informado) ? executavelEm(informado) : Optional.empty();
        }
        for (String pasta : path.split(File.pathSeparator)) {
            if (!pasta.isBlank()) {
                Optional<Path> achado = executavelEm(Path.of(pasta.strip()));
                if (achado.isPresent()) {
                    return achado;
                }
            }
        }
        for (Path raiz : instalacoesPostgres) {
            if (!Files.isDirectory(raiz)) {
                continue;
            }
            try (Stream<Path> versoes = Files.list(raiz)) {
                Optional<Path> achado = versoes.filter(Files::isDirectory)
                        .sorted(Comparator.comparing(BackupService::numeroVersao).reversed())
                        .map(v -> executavelEm(v.resolve("bin")))
                        .flatMap(Optional::stream).findFirst();
                if (achado.isPresent()) {
                    return achado;
                }
            } catch (IOException e) {
                log.warn("Falha ao procurar o pg_dump em {}", raiz, e);
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> executavelEm(Path pasta) {
        return EXECUTAVEIS.stream().map(pasta::resolve).filter(Files::isRegularFile).findFirst();
    }

    /** "16" → 16; "9.6" → 9,6 (para ordenar as pastas de versão); nome sem número vale −1. */
    static double numeroVersao(Path pasta) {
        try {
            return Double.parseDouble(pasta.getFileName().toString());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Versão principal do {@code pg_dump} ("pg_dump (PostgreSQL) 16.4" → 16). */
    int versaoPgDump(Path pgDump) {
        try {
            ExecutorProcesso.Resultado r = executor.executar(List.of(pgDump.toString(), "--version"), Map.of(),
                    LIMITE_VERSAO);
            return versao(r.saida()).orElseThrow(() -> new IOException("Versão não reconhecida: " + r.saida()));
        } catch (IOException e) {
            log.error("Falha ao conferir a versão do pg_dump {}", pgDump, e);
            throw new NegocioException("Não foi possível executar o pg_dump (" + pgDump + "). Confira o caminho em "
                    + "Configurações › Backup.");
        }
    }

    static Optional<Integer> versao(String saida) {
        Matcher m = VERSAO.matcher(saida == null ? "" : saida);
        return m.find() ? Optional.of(Integer.parseInt(m.group(1))) : Optional.empty();
    }

    /** Versão principal do servidor ({@code server_version_num} 160004 → 16). */
    private static int versaoServidor(Connection con) throws SQLException {
        try (Statement st = con.createStatement();
             var rs = st.executeQuery("SELECT current_setting('server_version_num')::int")) {
            rs.next();
            return rs.getInt(1) / 10000;
        }
    }

    // ----- Arquivos -----

    Path pasta(Configuracoes config) {
        String pasta = config.backup().pasta();
        return pasta == null || pasta.isBlank() ? pastaPadrao : Path.of(pasta.strip());
    }

    /** Backups da pasta (pelo padrão do nome), do mais recente para o mais antigo. */
    static List<ArquivoBackup> arquivos(Path pasta) {
        List<ArquivoBackup> arquivos = new ArrayList<>();
        for (Path p : backups(pasta)) {
            try {
                arquivos.add(new ArquivoBackup(p.getFileName().toString(), Files.size(p),
                        Files.getLastModifiedTime(p).toInstant().atOffset(ZoneOffset.UTC)));
            } catch (IOException e) {
                log.warn("Falha ao ler {}", p, e);
            }
        }
        return arquivos;
    }

    /** Apaga os backups além dos {@code manter} mais recentes (o nome ordena pela data). @return os apagados */
    static List<String> manterUltimos(Path pasta, int manter) {
        List<Path> backups = backups(pasta);
        List<String> removidos = new ArrayList<>();
        for (Path antigo : backups.subList(Math.min(Math.max(manter, 1), backups.size()), backups.size())) {
            try {
                Files.delete(antigo);
                removidos.add(antigo.getFileName().toString());
            } catch (IOException e) {
                log.warn("Não foi possível apagar o backup antigo {}", antigo, e);
            }
        }
        return removidos;
    }

    private static List<Path> backups(Path pasta) {
        if (!Files.isDirectory(pasta)) {
            return List.of();
        }
        try (Stream<Path> arquivos = Files.list(pasta)) {
            return arquivos.filter(p -> NOME_ARQUIVO.matcher(p.getFileName().toString()).matches())
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException e) {
            log.warn("Falha ao listar {}", pasta, e);
            return List.of();
        }
    }

    private static void mover(Path origem, Path destino) {
        try {
            try {
                Files.move(origem, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(origem, destino, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Falha ao renomear {} para {}", origem, destino, e);
            throw new NegocioException("O backup foi gerado, mas não foi possível gravá-lo com o nome final em "
                    + destino.getParent() + ". Os detalhes foram registrados no log.");
        }
    }

    private static long tamanho(Path arquivo) {
        try {
            return Files.isRegularFile(arquivo) ? Files.size(arquivo) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private static void apagar(Path arquivo) {
        try {
            Files.deleteIfExists(arquivo);
        } catch (IOException e) {
            log.warn("Não foi possível apagar {}", arquivo, e);
        }
    }

    /** "850 KB", "12,3 MB". */
    public static String tamanhoLegivel(long bytes) {
        if (bytes < 1024 * 1024) {
            return Math.max(1, Math.round(bytes / 1024.0)) + " KB";
        }
        return String.format(br.com.varesync.util.Formatos.PT_BR, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
