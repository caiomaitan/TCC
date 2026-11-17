package br.com.varesync.service;

import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.NfceDAO;
import br.com.varesync.dao.TributoVendaDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.DanfeNfce;
import br.com.varesync.model.NfceGravada;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.StatusNfce;
import br.com.varesync.service.nfce.CertificadoA1;
import br.com.varesync.service.nfce.EmitenteNfce;
import br.com.varesync.service.nfce.RespostaSefaz;
import br.com.varesync.service.nfce.Sefaz;
import br.com.varesync.service.nfce.SemComunicacaoException;
import br.com.varesync.util.CpfCnpj;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A NFC-e deste terminal no PDV e em Configurações (F23.6 e F23.7): a emissão depois da venda, com os DANFE para
 * imprimir; o DANFE da venda para o "Reimprimir" do F9; o modo contingência para o aviso fixo do PDV; "NFC-e ligada"
 * e "Testar comunicação".
 */
public class NfceTerminalService {

    private static final Pattern URL_CHAVE = Pattern.compile("<(?:\\w+:)?urlChave>\\s*([^<]*?)\\s*</(?:\\w+:)?urlChave>");

    /**
     * O que a emissão pelo PDV deu.
     *
     * @param nfce     a NFC-e que vale para a venda (a de contingência, quando foi criada)
     * @param mensagem o que a tela mostra: o motivo da rejeição, a contingência; nulo na autorização
     * @param danfes   o que imprimir: o DANFE (e, em contingência, a via do estabelecimento); vazio na rejeição
     */
    public record Emissao(NfceGravada nfce, String mensagem, List<DanfeNfce> danfes) {

        public boolean autorizada() {
            return nfce.status() == StatusNfce.AUTORIZADA;
        }

        public boolean contingencia() {
            return nfce.status() == StatusNfce.CONTINGENCIA;
        }

        /** REJEITADA ou DENEGADA: a venda continua registrada, sem DANFE. */
        public boolean recusada() {
            return nfce.status() == StatusNfce.REJEITADA || nfce.status() == StatusNfce.DENEGADA;
        }
    }

    /** O resultado do "Testar comunicação": em operação (107) ou o problema em linguagem simples. */
    public record TesteComunicacao(boolean emOperacao, String mensagem) {
    }

    private final Transacao transacao;
    private final NfceDAO nfceDAO;
    private final ConfiguracaoDAO configuracaoDAO;
    private final TributoVendaDAO tributoDAO;
    private final EmissaoNfceService emissao;
    private final CertificadoService certificados;
    private final Sefaz sefaz;
    private final AuditoriaService auditoria;

    public NfceTerminalService(Transacao transacao, NfceDAO nfceDAO, ConfiguracaoDAO configuracaoDAO,
                               TributoVendaDAO tributoDAO, EmissaoNfceService emissao, CertificadoService certificados,
                               Sefaz sefaz, AuditoriaService auditoria) {
        this.transacao = transacao;
        this.nfceDAO = nfceDAO;
        this.configuracaoDAO = configuracaoDAO;
        this.tributoDAO = tributoDAO;
        this.emissao = emissao;
        this.certificados = certificados;
        this.sefaz = sefaz;
        this.auditoria = auditoria;
    }

    // ----- PDV -----

    /**
     * Emite a NFC-e reservada na venda (F23.5), depois do commit, numa {@code Task}. AUTORIZADA ou CONTINGENCIA: os
     * DANFE para imprimir (em contingência, também a via do estabelecimento, com {@code nfce.via_estabelecimento});
     * REJEITADA ou DENEGADA: o motivo, e a orientação para corrigir em Fiscal › Notas fiscais.
     */
    public Emissao emitir(Sessao sessao, long nfceId) {
        sessao.exigir(Permissao.PDV);
        EmissaoNfceService.Resultado r = emissao.emitir(sessao, nfceId);
        NfceGravada nota = r.nfce();
        return transacao.executar(con -> {
            Configuracoes config = configuracaoDAO.carregar(con);
            List<DanfeNfce> danfes = new ArrayList<>();
            String mensagem = r.mensagem();
            switch (nota.status()) {
                case AUTORIZADA -> {
                    danfes.add(danfe(con, nota, config));
                    mensagem = null;
                }
                case CONTINGENCIA -> {
                    DanfeNfce danfe = danfe(con, nota, config);
                    danfes.add(danfe);
                    if (config.nfce().viaEstabelecimento()) {
                        danfes.add(danfe.comoViaDoEstabelecimento());
                    }
                }
                case REJEITADA -> mensagem = "A NFC-e nº " + nota.numero() + " foi rejeitada: " + motivo(nota)
                        + ". A venda nº " + nota.vendaId() + " continua registrada. Corrija o cadastro e reenvie a "
                        + "nota em Fiscal › Notas fiscais.";
                case DENEGADA -> mensagem = "O uso da NFC-e nº " + nota.numero() + " foi denegado pela SEFAZ: "
                        + motivo(nota) + ". A venda nº " + nota.vendaId() + " continua registrada. Veja a situação "
                        + "da loja em Fiscal › Notas fiscais.";
                default -> {
                    // PENDENTE com a trava de outro: o transmissor cuida dela
                }
            }
            return new Emissao(nota, mensagem, List.copyOf(danfes));
        });
    }

    /** O DANFE da NFC-e vigente da venda (autorizada ou em contingência), para o "Reimprimir" do F9. */
    public Optional<DanfeNfce> danfeDaVenda(Sessao sessao, long vendaId) {
        sessao.exigirAlguma(Permissao.PDV, Permissao.FISCAL);
        return transacao.executar(con -> {
            Optional<NfceGravada> vigente = nfceDAO.daVenda(con, vendaId).stream()
                    .filter(n -> n.status() == StatusNfce.AUTORIZADA || n.status() == StatusNfce.CONTINGENCIA)
                    .filter(n -> n.xml() != null && n.qrcode() != null)
                    .max(Comparator.comparingLong(NfceGravada::id));
            if (vigente.isEmpty()) {
                return Optional.<DanfeNfce>empty();
            }
            return Optional.of(danfe(con, vigente.get(), configuracaoDAO.carregar(con)));
        });
    }

    /** Desde quando o terminal está no modo contingência (o aviso fixo do PDV); vazio fora dele. */
    public Optional<OffsetDateTime> contingenciaDesde() {
        return emissao.modo().inicio();
    }

    /**
     * Avisa a cada entrada e saída do modo contingência deste terminal (F23.9: o aviso do PDV some assim que o
     * transmissor sai do modo), na thread de quem mudou o modo.
     *
     * @return o que para de avisar
     */
    public Runnable observarContingencia(java.util.function.Consumer<Optional<OffsetDateTime>> observador) {
        return emissao.modo().observar(observador);
    }

    /** O DANFE de uma NFC-e montada (com o XML e o QR Code), a partir da venda e do emitente. */
    DanfeNfce danfe(Connection con, NfceGravada nota, Configuracoes config) throws SQLException {
        NfceDAO.VendaNfce venda = nfceDAO.venda(con, nota.vendaId());
        List<DanfeNfce.Item> itens = new ArrayList<>();
        BigDecimal valorTotal = Dinheiro.ZERO;
        BigDecimal descontos = Dinheiro.ZERO;
        for (NfceDAO.ItemVenda i : venda.itens()) {
            BigDecimal unitario = i.valorBruto().divide(i.quantidade(), Dinheiro.ESCALA_DINHEIRO,
                    Dinheiro.ARREDONDAMENTO);
            itens.add(new DanfeNfce.Item(String.valueOf(i.produtoId()), i.nome(), i.quantidade(), i.unidade(),
                    unitario, i.valorBruto()));
            valorTotal = valorTotal.add(i.valorBruto());
            descontos = descontos.add(i.desconto());
        }
        int ultimoDinheiro = -1;
        for (int k = 0; k < venda.pagamentos().size(); k++) {
            if ("01".equals(venda.pagamentos().get(k).tpag())) {
                ultimoDinheiro = k;
            }
        }
        List<DanfeNfce.Pagamento> pagamentos = new ArrayList<>();
        for (int k = 0; k < venda.pagamentos().size(); k++) {
            NfceDAO.PagamentoVenda p = venda.pagamentos().get(k);
            // Como no comprovante: o dinheiro aparece como foi entregue (o abatido + o troco).
            pagamentos.add(new DanfeNfce.Pagamento(p.descricao(), k == ultimoDinheiro ? p.valor().add(venda.troco())
                    : p.valor()));
        }
        return new DanfeNfce(emitente(config), itens, valorTotal, descontos, venda.total(), pagamentos, venda.troco(),
                nota.chave(), urlConsulta(nota, config), venda.consumidorDocumento(), nota.numero(), nota.serie(),
                nota.emitidaEm(), nota.protocolo(), nota.autorizadaEm(), nota.status() == StatusNfce.CONTINGENCIA,
                false, nota.qrcode(), tributoDAO.aproximadosDaVenda(con, nota.vendaId()).orElse(null),
                nota.vendaId());
    }

    static DanfeNfce.Emitente emitente(Configuracoes c) {
        Configuracoes.Emitente e = c.emitente();
        StringBuilder endereco = new StringBuilder(e.logradouro()).append(", ").append(e.numero());
        if (!Textos.vazio(e.complemento())) {
            endereco.append(", ").append(e.complemento());
        }
        endereco.append(" - ").append(e.bairro()).append(" - ").append(e.municipio()).append('/')
                .append(c.loja().uf());
        if (e.cep().length() == 8) {
            endereco.append(" - CEP ").append(e.cep(), 0, 5).append('-').append(e.cep().substring(5));
        }
        return new DanfeNfce.Emitente(e.razaoSocial(), c.loja().documento(), c.loja().ie(), endereco.toString());
    }

    /** A urlChave gravada no infNFeSupl (a mesma da nota); sem ela, a da configuração. */
    static String urlConsulta(NfceGravada nota, Configuracoes config) {
        Matcher m = URL_CHAVE.matcher(nota.xml() == null ? "" : nota.xml());
        return m.find() ? m.group(1) : config.nfce().urlConsulta();
    }

    private static String motivo(NfceGravada n) {
        String motivo = Textos.vazio(n.motivo()) ? "sem motivo informado" : n.motivo();
        return n.cstat() == null ? motivo : "cStat " + n.cstat() + " - " + motivo;
    }

    // ----- Configurações › NFC-e -----

    /**
     * "NFC-e ligada" (F23.6): só liga com os dados do emitente completos e com o certificado deste terminal carregado e
     * na validade. Auditoria {@code CONFIGURACAO_ALTERADA}.
     */
    public void ligar(Sessao sessao, boolean ligar) {
        sessao.exigirSupervisor();
        if (ligar) {
            Configuracoes config = transacao.executar(configuracaoDAO::carregar);
            List<String> falta = EmitenteNfce.pendencias(config);
            if (!falta.isEmpty()) {
                throw new NegocioException("Para ligar a NFC-e, complete em Configurações › Loja: "
                        + String.join(", ", falta) + ".");
            }
            try {
                certificados.doTerminal();
            } catch (NegocioException e) {
                throw new NegocioException("Para ligar a NFC-e, este terminal precisa do certificado digital: "
                        + e.getMessage());
            }
        }
        transacao.executar(con -> {
            configuracaoDAO.travar(con);
            if (configuracaoDAO.carregar(con).nfce().ativa() == ligar) {
                return null;
            }
            configuracaoDAO.gravar(con, Configuracoes.NFCE_ATIVA, String.valueOf(ligar));
            auditoria.registrar(con, sessao, AcaoAuditoria.CONFIGURACAO_ALTERADA,
                    ConfiguracaoService.ENTIDADE_CONFIGURACAO, null, "Configurações alteradas: NFC-e "
                            + (ligar ? "ligada" : "desligada") + " (homologação)");
            return null;
        });
    }

    /**
     * "Testar comunicação" (F23.6): o status do serviço com o certificado deste terminal; o cStat e o motivo, ou o
     * problema em linguagem simples (sem certificado, senha errada, certificado vencido, CNPJ diferente, sem
     * internet). Nada do certificado vai para a mensagem além do titular e do CNPJ.
     */
    public TesteComunicacao testarComunicacao(Sessao sessao) {
        sessao.exigirSupervisor();
        CertificadoA1 certificado;
        try {
            certificado = certificados.doTerminal();
        } catch (NegocioException e) {
            return new TesteComunicacao(false, e.getMessage());
        }
        Configuracoes config = transacao.executar(configuracaoDAO::carregar);
        String cnpjLoja = config.loja().documento();
        if (!certificado.raizConfere(cnpjLoja)) {
            return new TesteComunicacao(false, "O CNPJ do certificado (" + (certificado.cnpj().isEmpty()
                    ? "não informado" : CpfCnpj.formatar(certificado.cnpj())) + ") não é o da loja ("
                    + (Textos.vazio(cnpjLoja) ? "não informado" : CpfCnpj.formatar(cnpjLoja))
                    + "): a SEFAZ recusaria as notas. Troque o certificado ou confira o CNPJ em Configurações › Loja.");
        }
        try {
            RespostaSefaz r = sefaz.status(emissao.contexto(certificado));
            return new TesteComunicacao(r.emOperacao(), "SEFAZ de homologação (" + config.loja().uf() + "): "
                    + "cStat " + r.descricao() + (r.emOperacao() ? " — o serviço está em operação." : "."));
        } catch (SemComunicacaoException e) {
            return new TesteComunicacao(false, "Sem comunicação com a SEFAZ: " + e.causa() + ".");
        } catch (NegocioException e) {
            return new TesteComunicacao(false, e.getMessage());
        }
    }

    /** "NFC-e em contingência desde hh:mm: as notas serão transmitidas quando a SEFAZ voltar". */
    public static String avisoContingencia(OffsetDateTime desde) {
        return "NFC-e em contingência desde " + Formatos.hora(desde) + ": as notas serão transmitidas quando a SEFAZ "
                + "voltar";
    }
}
