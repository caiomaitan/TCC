package br.com.varesync.service;

import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.InfoCertificado;
import br.com.varesync.service.nfce.CertificadoA1;
import br.com.varesync.util.CpfCnpj;
import br.com.varesync.util.Formatos;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Certificado A1 deste terminal (F23.2), só para o supervisor: mostra o titular, o CNPJ, o vencimento e se a raiz do
 * CNPJ bate com a da loja; troca e retira, com a auditoria {@code NFCE_CERTIFICADO_ALTERADO} (sem o caminho e sem a
 * senha). A emissão da NFC-e pega o certificado por {@link #doTerminal()}, sem exigir permissão (tarefa do sistema).
 */
public class CertificadoService {

    public static final String ENTIDADE_CERTIFICADO = "certificado";

    private final Transacao transacao;
    private final ConfiguracaoDAO configuracaoDAO;
    private final AuditoriaService auditoria;
    private final CertificadoDoTerminal terminal;
    private final Clock clock;

    public CertificadoService(Transacao transacao, ConfiguracaoDAO configuracaoDAO, AuditoriaService auditoria,
                              CertificadoDoTerminal terminal, Clock clock) {
        this.transacao = transacao;
        this.configuracaoDAO = configuracaoDAO;
        this.auditoria = auditoria;
        this.terminal = terminal;
        this.clock = clock;
    }

    /** Se este terminal tem um certificado escolhido. */
    public boolean configurado(Sessao sessao) {
        sessao.exigirSupervisor();
        return !terminal.caminho().isEmpty();
    }

    public boolean senhaNaVariavel() {
        return terminal.senhaNaVariavel();
    }

    /** O certificado atual deste terminal, lido de novo do arquivo; vazio = sem certificado. */
    public Optional<InfoCertificado> atual(Sessao sessao) {
        sessao.exigirSupervisor();
        if (terminal.caminho().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(info(ler(terminal.caminho(), terminal.senha())));
    }

    /** Confere um arquivo e a senha sem gravar (para a tela mostrar antes de salvar). */
    public InfoCertificado conferir(Sessao sessao, String caminho, String senha) {
        sessao.exigirSupervisor();
        return info(ler(caminho, senhaInformada(senha)));
    }

    /** Lê, confere e grava o certificado deste terminal, com a auditoria. */
    public InfoCertificado salvar(Sessao sessao, String caminho, String senha) {
        sessao.exigirSupervisor();
        String senhaQueVale = senhaInformada(senha);
        CertificadoA1 certificado = ler(caminho, senhaQueVale);
        InfoCertificado info = info(certificado);
        transacao.executar(con -> {
            auditoria.registrar(con, sessao, AcaoAuditoria.NFCE_CERTIFICADO_ALTERADO, ENTIDADE_CERTIFICADO, null,
                    "Certificado do terminal " + sessao.terminal() + " trocado: " + descrever(info));
            return null;
        });
        terminal.gravar(Path.of(caminho.strip()).toAbsolutePath().toString(),
                terminal.senhaNaVariavel() ? "" : senhaQueVale);
        return info;
    }

    /** Tira o certificado deste terminal. */
    public void remover(Sessao sessao) {
        sessao.exigirSupervisor();
        if (terminal.caminho().isEmpty()) {
            return;
        }
        transacao.executar(con -> {
            auditoria.registrar(con, sessao, AcaoAuditoria.NFCE_CERTIFICADO_ALTERADO, ENTIDADE_CERTIFICADO, null,
                    "Certificado do terminal " + sessao.terminal() + " retirado");
            return null;
        });
        terminal.gravar("", "");
    }

    /**
     * O certificado deste terminal para assinar a NFC-e, ou a recusa em linguagem simples (sem certificado, senha
     * errada, arquivo que sumiu, vencido).
     */
    public CertificadoA1 doTerminal() {
        if (terminal.caminho().isEmpty()) {
            throw new NegocioException("Este terminal não tem o certificado digital da NFC-e. O supervisor escolhe o "
                    + "certificado em Configurações › NFC-e.");
        }
        CertificadoA1 certificado = ler(terminal.caminho(), terminal.senha());
        if (certificado.vencido(clock)) {
            throw new NegocioException("O certificado digital deste terminal está vencido. O supervisor troca o "
                    + "certificado em Configurações › NFC-e.");
        }
        return certificado;
    }

    /**
     * Para o alerta do painel (F23.10), sem exigir permissão e sem mostrar nada do certificado: os dias até o
     * vencimento do certificado deste terminal (negativo = vencido), ou vazio sem certificado ou sem conseguir lê-lo
     * (a recusa aparece na venda e em Configurações › NFC-e).
     */
    public java.util.OptionalLong diasParaVencer() {
        if (terminal.caminho().isEmpty()) {
            return java.util.OptionalLong.empty();
        }
        try {
            CertificadoA1 certificado = ler(terminal.caminho(), terminal.senha());
            return java.util.OptionalLong.of(certificado.vencido(clock) ? Math.min(-1, certificado.diasParaVencer(clock))
                    : certificado.diasParaVencer(clock));
        } catch (NegocioException e) {
            return java.util.OptionalLong.empty();
        }
    }

    private String senhaInformada(String senha) {
        if (terminal.senhaNaVariavel()) {
            return terminal.senha();
        }
        if (senha == null || senha.isEmpty()) {
            throw new NegocioException("Informe a senha do certificado.");
        }
        return senha;
    }

    private static CertificadoA1 ler(String caminho, String senha) {
        if (caminho == null || caminho.isBlank()) {
            throw new NegocioException("Escolha o arquivo do certificado A1 (.pfx ou .p12).");
        }
        Path arquivo;
        try {
            arquivo = Path.of(caminho.strip());
        } catch (InvalidPathException e) {
            throw new NegocioException("O caminho do arquivo do certificado não é válido.");
        }
        return CertificadoA1.ler(arquivo, senha);
    }

    private InfoCertificado info(CertificadoA1 c) {
        Configuracoes config = transacao.executar(configuracaoDAO::carregar);
        String cnpjLoja = config.loja().documento();
        return new InfoCertificado(c.titular(), c.cnpj(),
                OffsetDateTime.ofInstant(c.validoAte(), ZoneId.systemDefault()), c.diasParaVencer(clock),
                c.vencido(clock), c.raizConfere(cnpjLoja), c.problema(clock, cnpjLoja));
    }

    private static String descrever(InfoCertificado i) {
        return "titular " + i.titular() + ", CNPJ " + (i.cnpj().isEmpty() ? "não informado" : CpfCnpj.formatar(i.cnpj()))
                + ", válido até " + Formatos.dataHora(i.validoAte());
    }
}
