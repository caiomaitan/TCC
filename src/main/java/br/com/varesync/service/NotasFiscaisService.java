package br.com.varesync.service;

import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.NfceDAO;
import br.com.varesync.dao.NumeracaoNfceDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.AlertasNfce;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.DanfeNfce;
import br.com.varesync.model.NfceGravada;
import br.com.varesync.model.NotaFiscal;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.StatusNfce;
import br.com.varesync.model.StatusVenda;
import br.com.varesync.model.TipoEmissaoNfce;
import br.com.varesync.service.nfce.CertificadoA1;
import br.com.varesync.service.nfce.ChaveAcesso;
import br.com.varesync.service.nfce.RespostaSefaz;
import br.com.varesync.service.nfce.Sefaz;
import br.com.varesync.service.nfce.SemComunicacaoException;
import br.com.varesync.util.CodigoUf;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A aba Notas fiscais da janela Cadastro fiscal (F23.9, permissão FISCAL) e os alertas da NFC-e no painel (F23.10):
 * a lista com filtro por período, situação e número; Ver DANFE; Salvar XML; Transmitir agora; Corrigir e reenviar a
 * rejeitada, no mesmo número; Inutilizar números sem NFC-e; Exportar XML do mês, num ZIP para o contador.
 */
public class NotasFiscaisService {

    private static final Logger LOG = LoggerFactory.getLogger(NotasFiscaisService.class);
    /** "A transmitir" no filtro e no alerta do painel: em contingência ou sem resposta. */
    public static final Set<StatusNfce> A_TRANSMITIR = EnumSet.of(StatusNfce.CONTINGENCIA, StatusNfce.SEM_RESPOSTA);
    private static final Set<StatusNfce> COM_DANFE = EnumSet.of(StatusNfce.AUTORIZADA, StatusNfce.CONTINGENCIA,
            StatusNfce.CANCELADA);

    /**
     * Filtro da aba.
     *
     * @param de         emitida a partir do dia (nulo = sem limite)
     * @param ate        emitida até o dia, inclusive (nulo = sem limite)
     * @param situacoes  vazio = todas
     * @param numero     nulo = todos
     */
    public record Filtro(LocalDate de, LocalDate ate, Set<StatusNfce> situacoes, Integer numero) {
        public Filtro {
            situacoes = situacoes == null ? Set.of() : Set.copyOf(situacoes);
        }
    }

    /** O XML de uma NFC-e para o "Salvar XML": o nome sugerido e o conteúdo. */
    public record XmlNota(String nomeArquivo, String conteudo) {
    }

    /** O que o "Exportar XML do mês" colocou no ZIP. */
    public record ExportacaoXml(int notas, int eventos, int inutilizacoes) {
        public int total() {
            return notas + eventos + inutilizacoes;
        }
    }

    /** A faixa inutilizada, com o protocolo da SEFAZ. */
    public record Inutilizacao(int serie, int inicial, int fim, String protocolo) {
    }

    private final Transacao transacao;
    private final NfceDAO nfceDAO;
    private final NumeracaoNfceDAO numeracaoDAO;
    private final ConfiguracaoDAO configuracaoDAO;
    private final TributosVendaService tributos;
    private final EmissaoNfceService emissao;
    private final TransmissorNfce transmissor;
    private final NfceTerminalService nfceTerminal;
    private final CertificadoService certificados;
    private final Sefaz sefaz;
    private final AuditoriaService auditoria;
    private final Clock clock;
    private final ZoneId fuso;

    public NotasFiscaisService(Transacao transacao, NfceDAO nfceDAO, NumeracaoNfceDAO numeracaoDAO,
                               ConfiguracaoDAO configuracaoDAO, TributosVendaService tributos,
                               EmissaoNfceService emissao, TransmissorNfce transmissor,
                               NfceTerminalService nfceTerminal, CertificadoService certificados, Sefaz sefaz,
                               AuditoriaService auditoria, Clock clock, ZoneId fuso) {
        this.transacao = transacao;
        this.nfceDAO = nfceDAO;
        this.numeracaoDAO = numeracaoDAO;
        this.configuracaoDAO = configuracaoDAO;
        this.tributos = tributos;
        this.emissao = emissao;
        this.transmissor = transmissor;
        this.nfceTerminal = nfceTerminal;
        this.certificados = certificados;
        this.sefaz = sefaz;
        this.auditoria = auditoria;
        this.clock = clock;
        this.fuso = fuso;
    }

    // ----- Lista, DANFE e XML -----

    public List<NotaFiscal> listar(Sessao sessao, Filtro filtro) {
        sessao.exigir(Permissao.FISCAL);
        if (filtro.de() != null && filtro.ate() != null && filtro.ate().isBefore(filtro.de())) {
            throw new NegocioException("A data final não pode ser anterior à inicial.");
        }
        OffsetDateTime inicio = filtro.de() == null ? null : inicioDoDia(filtro.de());
        OffsetDateTime fim = filtro.ate() == null ? null : inicioDoDia(filtro.ate().plusDays(1));
        return transacao.executar(con -> nfceDAO.listar(con, inicio, fim, filtro.situacoes(), filtro.numero()));
    }

    /** O DANFE da NFC-e montada (autorizada, em contingência ou cancelada), para imprimir ou salvar em PDF. */
    public DanfeNfce danfe(Sessao sessao, long nfceId) {
        sessao.exigir(Permissao.FISCAL);
        return transacao.executar(con -> {
            NfceGravada n = buscar(con, nfceId);
            if (!COM_DANFE.contains(n.status()) || n.xml() == null || n.qrcode() == null) {
                throw new NegocioException("A NFC-e nº " + n.numero() + " está " + n.status().rotulo().toLowerCase()
                        + " e não tem DANFE: só a autorizada, a de contingência e a cancelada têm.");
            }
            return nfceTerminal.danfe(con, n, configuracaoDAO.carregar(con));
        });
    }

    /** O XML gravado: o nfeProc (autorizada, cancelada, substituída) ou o assinado. */
    public XmlNota xml(Sessao sessao, long nfceId) {
        sessao.exigir(Permissao.FISCAL);
        NfceGravada n = transacao.executar(con -> buscar(con, nfceId));
        if (n.xml() == null) {
            throw new NegocioException("A NFC-e nº " + n.numero() + " ainda não tem XML"
                    + (n.motivo() == null ? "." : ": " + n.motivo()));
        }
        boolean proc = n.xml().contains("<nfeProc");
        return new XmlNota(n.chave() + (proc ? "-procNFe.xml" : "-nfe.xml"), n.xml());
    }

    // ----- Transmitir agora -----

    /** Roda o transmissor na hora (F23.5), com a sessão de quem pediu; o resumo em linguagem simples. */
    public String transmitirAgora(Sessao sessao) {
        sessao.exigir(Permissao.FISCAL);
        TransmissorNfce.Resumo r = transmissor.transmitir(sessao);
        List<String> partes = new ArrayList<>();
        contar(partes, r.autorizadas(), "autorizada", "autorizadas");
        contar(partes, r.rejeitadas(), "rejeitada ou denegada", "rejeitadas ou denegadas");
        contar(partes, r.substituidas(), "cancelada por substituição", "canceladas por substituição");
        contar(partes, r.inutilizadas(), "com o número inutilizado", "com o número inutilizado");
        contar(partes, r.emContingencia(), "ainda em contingência", "ainda em contingência");
        String feito = partes.isEmpty() ? "Nenhuma NFC-e precisava ser transmitida." : "NFC-e: "
                + String.join(", ", partes) + ".";
        return r.mensagem() == null ? feito : (partes.isEmpty() ? "" : feito + " ") + r.mensagem();
    }

    private static void contar(List<String> partes, int n, String um, String varios) {
        if (n > 0) {
            partes.add(n + " " + (n == 1 ? um : varios));
        }
    }

    // ----- Corrigir e reenviar -----

    /**
     * Corrigir e reenviar a REJEITADA (F23.9), no mesmo número, com a trava da transmissão:
     * <ul>
     *   <li>emissão normal: a nota não existe na SEFAZ, então a data de emissão passa a ser agora (a chave é refeita,
     *   com o mesmo cNF) e os tributos são recalculados com o cadastro atual;</li>
     *   <li>contingência: a lei manda manter número, série, data de emissão e valores (Ajuste SINIEF 19/16); só os
     *   códigos (NCM, CEST, CFOP, CST e CSOSN) são relidos, e, se a correção mudar algum valor de imposto, o reenvio é
     *   recusado, com a explicação.</li>
     * </ul>
     * Depois, a nota é montada e enviada (a de contingência também). Auditoria {@code NFCE_REENVIADA}.
     */
    public EmissaoNfceService.Resultado reenviar(Sessao sessao, long nfceId) {
        sessao.exigir(Permissao.FISCAL);
        NfceGravada atual = transacao.executar(con -> buscar(con, nfceId));
        if (atual.status() != StatusNfce.REJEITADA) {
            throw new NegocioException("A NFC-e nº " + atual.numero() + " está " + atual.status().rotulo()
                    .toLowerCase() + ": só a rejeitada é corrigida e reenviada.");
        }
        CertificadoA1 certificado = certificados.doTerminal();
        return emissao.comTrava(nfceId, n -> {
            if (n.status() != StatusNfce.REJEITADA) {
                return new EmissaoNfceService.Resultado(n, false, "A NFC-e nº " + n.numero() + " está "
                        + n.status().rotulo().toLowerCase() + ": só a rejeitada é corrigida e reenviada.");
            }
            transacao.executar(con -> {
                reabrir(con, sessao, n);
                return null;
            });
            return emissao.processarTravada(sessao, emissao.buscar(nfceId), certificado, true, false);
        });
    }

    private void reabrir(Connection con, Sessao sessao, NfceGravada n) throws SQLException {
        NfceDAO.VendaNfce venda = nfceDAO.venda(con, n.vendaId());
        if (venda.status() != StatusVenda.FINALIZADA) {
            throw new NegocioException("A venda nº " + n.vendaId() + " foi cancelada: a NFC-e nº " + n.numero()
                    + " não é reenviada, e o transmissor inutiliza o número.");
        }
        OffsetDateTime agora = OffsetDateTime.now(clock);
        String anterior = NotaFiscal.mensagem(n.cstat(), n.motivo());
        String descricao;
        if (n.tipoEmissao() == TipoEmissaoNfce.NORMAL) {
            Configuracoes config = configuracaoDAO.carregar(con);
            int codigoUf = CodigoUf.de(config.loja().uf()).orElseThrow(() -> new NegocioException(
                    "Para a NFC-e, informe a UF da loja em Configurações › Loja."));
            String chave = ChaveAcesso.montar(codigoUf, YearMonth.from(agora.atZoneSameInstant(fuso)),
                    config.loja().documento(), n.serie(), n.numero(), TipoEmissaoNfce.NORMAL.codigo(),
                    n.codigoNumerico());
            int itens = tributos.recalcular(con, n.vendaId(), LocalDate.now(clock));
            nfceDAO.reabrir(con, n.id(), StatusNfce.PENDENTE, chave, agora, agora);
            descricao = "emissão normal, com a data de emissão de agora e os tributos recalculados (" + itens
                    + (itens == 1 ? " item" : " itens") + ")";
        } else {
            LocalDate dataVenda = venda.dataHora().atZoneSameInstant(fuso).toLocalDate();
            List<String> mudancas = tributos.valoresQueMudariam(con, n.vendaId(), dataVenda);
            if (!mudancas.isEmpty()) {
                throw new NegocioException("A NFC-e nº " + n.numero() + " é de contingência: a lei manda manter "
                        + "número, série, data de emissão e valores, e a correção mudaria o valor dos impostos ("
                        + String.join("; ", mudancas) + "). Corrija só os códigos (NCM, CEST, CFOP, CST e CSOSN), "
                        + "sem mudar as alíquotas, e tente de novo.");
            }
            tributos.recalcular(con, n.vendaId(), dataVenda);
            nfceDAO.reabrir(con, n.id(), StatusNfce.CONTINGENCIA, n.chave(), n.emitidaEm(), agora);
            descricao = "contingência, com o número, a data de emissão e os valores mantidos e os códigos relidos";
        }
        auditoria.registrar(con, sessao, AcaoAuditoria.NFCE_REENVIADA, NfceService.ENTIDADE, n.id(),
                "NFC-e nº " + n.numero() + " (série " + n.serie() + ") da venda nº " + n.vendaId()
                        + " corrigida e reenviada: " + descricao + ". Rejeição anterior: "
                        + (anterior == null ? "sem motivo" : anterior));
    }

    // ----- Inutilizar números -----

    /**
     * Inutiliza uma faixa de números da série sem NFC-e (F23.9), com a justificativa de 15 a 255 caracteres. O pedido
     * vai até o dia 10 do mês seguinte ao da numeração (o mês da primeira NFC-e emitida depois da faixa; sem ela, o
     * mês atual). Primeiro a numeração da série passa da faixa (a venda não reserva mais esses números), depois o
     * pedido vai à SEFAZ, fora da transação, e só homologado é gravado (procInutNFe), com a auditoria.
     */
    public Inutilizacao inutilizar(Sessao sessao, int serie, int inicial, int fim, String justificativa) {
        sessao.exigir(Permissao.FISCAL);
        if (serie < 0 || serie > 889) {
            throw new NegocioException("A série vai de 0 a 889.");
        }
        if (inicial < 1 || fim < inicial || fim > 999_999_998) {
            throw new NegocioException("Informe a faixa: o número inicial (a partir de 1) e o final, igual ou maior.");
        }
        String texto = Textos.normalizarEspacos(justificativa);
        if (texto.length() < CancelamentoNfceService.JUSTIFICATIVA_MINIMA
                || texto.length() > CancelamentoNfceService.JUSTIFICATIVA_MAXIMA) {
            throw new NegocioException("A justificativa precisa ter de 15 a 255 caracteres (exigência da SEFAZ).");
        }
        CertificadoA1 certificado = certificados.doTerminal();
        OffsetDateTime agora = OffsetDateTime.now(clock);
        transacao.executar(con -> {
            // A trava da série vem antes das conferências: uma venda ao mesmo tempo não pega um número da faixa.
            numeracaoDAO.pularAte(con, serie, fim);
            if (nfceDAO.contarNaFaixa(con, serie, inicial, fim) > 0) {
                throw new NegocioException("Há NFC-e com números dessa faixa: só números sem NFC-e são "
                        + "inutilizados aqui. A rejeitada de venda cancelada é inutilizada pelo transmissor.");
            }
            if (nfceDAO.faixaJaInutilizada(con, serie, inicial, fim)) {
                throw new NegocioException("Algum número dessa faixa da série " + serie + " já foi inutilizado.");
            }
            OffsetDateTime salto = nfceDAO.primeiraEmitidaDepois(con, serie, fim).orElse(agora);
            LocalDate limite = YearMonth.from(salto.atZoneSameInstant(fuso)).plusMonths(1).atDay(10);
            if (LocalDate.now(clock).isAfter(limite)) {
                throw new NegocioException("O prazo para inutilizar esses números acabou em " + Formatos.data(limite)
                        + " (o dia 10 do mês seguinte ao da numeração): a SEFAZ não aceita mais o pedido. Procure o "
                        + "contador.");
            }
            return null;
        });
        RespostaSefaz r;
        try {
            r = sefaz.inutilizar(emissao.contexto(certificado), serie, inicial, fim, texto, agora);
        } catch (SemComunicacaoException e) {
            throw new NegocioException("A SEFAZ não respondeu (" + e.causa() + "): os números não foram "
                    + "inutilizados. Tente de novo em alguns minutos.");
        }
        LOG.info("Inutilização da série {}, {} a {}: cStat {} - {}", serie, inicial, fim, r.cstat(), r.motivo());
        if (!r.inutilizada()) {
            throw new NegocioException("A SEFAZ recusou a inutilização (cStat " + r.descricao() + ").");
        }
        OffsetDateTime gravada = OffsetDateTime.now(clock);
        transacao.executar(con -> {
            nfceDAO.inserirInutilizacao(con, serie, inicial, fim, agora.atZoneSameInstant(fuso).getYear() % 100, texto,
                    r.cstat(), r.motivo(), r.protocolo(), r.xml(), gravada, sessao.usuarioId());
            auditoria.registrar(con, sessao, AcaoAuditoria.NFCE_INUTILIZADA, NfceService.ENTIDADE, null,
                    (inicial == fim ? "Número " + inicial : "Números " + inicial + " a " + fim) + " da série " + serie
                            + " da NFC-e inutilizados (protocolo " + r.protocolo() + "). Justificativa: " + texto);
            return null;
        });
        return new Inutilizacao(serie, inicial, fim, r.protocolo());
    }

    // ----- Exportar XML do mês -----

    /**
     * Um ZIP com os XML do mês para o contador: as notas autorizadas, canceladas e substituídas emitidas no mês (o
     * nfeProc), os eventos registrados no mês (o procEventoNFe) e as inutilizações do mês (o procInutNFe).
     */
    public ExportacaoXml exportarMes(Sessao sessao, YearMonth mes, Path destino) {
        sessao.exigir(Permissao.FISCAL);
        List<NfceDAO.XmlGuardado> xmls = transacao.executar(con -> nfceDAO.xmlsDoPeriodo(con,
                inicioDoDia(mes.atDay(1)), inicioDoDia(mes.plusMonths(1).atDay(1))));
        if (xmls.isEmpty()) {
            throw new NegocioException("Não há XML de NFC-e em " + String.format("%02d/%d", mes.getMonthValue(),
                    mes.getYear()) + ".");
        }
        try (OutputStream saida = Files.newOutputStream(destino);
             ZipOutputStream zip = new ZipOutputStream(saida, StandardCharsets.UTF_8)) {
            for (NfceDAO.XmlGuardado x : xmls) {
                zip.putNextEntry(new ZipEntry(x.nome()));
                zip.write(x.xml().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            LOG.error("Falha ao gravar o ZIP dos XML", e);
            throw new NegocioException("Não foi possível gravar o arquivo ZIP. Escolha outra pasta.");
        }
        int notas = (int) xmls.stream().filter(x -> x.nome().startsWith("NFC-e/")).count();
        int eventos = (int) xmls.stream().filter(x -> x.nome().startsWith("Eventos/")).count();
        return new ExportacaoXml(notas, eventos, xmls.size() - notas - eventos);
    }

    // ----- Painel (F23.10) -----

    /**
     * Os alertas da NFC-e para o painel, só para quem tem FISCAL (sem ela, nenhum). O do certificado deste terminal
     * aparece com a NFC-e ligada, a partir de {@code nfce.alerta_certificado_dias} antes do vencimento.
     */
    public AlertasNfce alertas(Sessao sessao) {
        if (!sessao.pode(Permissao.FISCAL)) {
            return AlertasNfce.NENHUM;
        }
        OffsetDateTime hoje = inicioDoDia(LocalDate.now(clock));
        NfceDAO.Pendentes p = transacao.executar(con -> nfceDAO.pendentes(con, hoje));
        Configuracoes.Nfce config = transacao.executar(configuracaoDAO::carregar).nfce();
        Long dias = null;
        if (config.ativa()) {
            OptionalLong validade = certificados.diasParaVencer();
            if (validade.isPresent() && validade.getAsLong() <= config.alertaCertificadoDias()) {
                dias = validade.getAsLong();
            }
        }
        return new AlertasNfce(p.aTransmitir(), p.aTransmitirAntigas() > 0, p.rejeitadas(), dias);
    }

    private OffsetDateTime inicioDoDia(LocalDate dia) {
        return dia.atStartOfDay(fuso).toOffsetDateTime();
    }

    private NfceGravada buscar(Connection con, long id) throws SQLException {
        return nfceDAO.buscar(con, id, false).orElseThrow(() -> new NegocioException("NFC-e não encontrada."));
    }

    /** Para os testes: a primeira NFC-e da venda numa situação. */
    Optional<NfceGravada> daVenda(long vendaId, StatusNfce status) {
        return transacao.executar(con -> nfceDAO.daVenda(con, vendaId).stream()
                .filter(n -> n.status() == status).findFirst());
    }
}
