package br.com.varesync.service;

import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.NfceDAO;
import br.com.varesync.dao.NumeracaoNfceDAO;
import br.com.varesync.dao.TributoVendaDAO;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.NfceGravada;
import br.com.varesync.model.StatusNfce;
import br.com.varesync.model.StatusVenda;
import br.com.varesync.model.TipoEmissaoNfce;
import br.com.varesync.service.nfce.CertificadoA1;
import br.com.varesync.service.nfce.ChaveAcesso;
import br.com.varesync.service.nfce.EmitenteNfce;
import br.com.varesync.service.nfce.GeracaoNfce;
import br.com.varesync.service.nfce.NotaNfce;
import br.com.varesync.util.CodigoUf;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NFC-e, parte A (F23.1 e F23.3): a reserva do número (na transação de quem chama: a venda, na parte C) e o XML
 * assinado e validado. Nada é transmitido aqui: a comunicação com a SEFAZ é a parte B.
 *
 * <p>A emissão é tarefa do sistema (como o transmissor da F23.5): não exige permissão.
 */
public class NfceService {

    private static final Logger LOG = LoggerFactory.getLogger(NfceService.class);
    /** xJust da contingência off-line (F23.5). */
    public static final String JUSTIFICATIVA_CONTINGENCIA = "SEFAZ indisponivel - sem resposta da autorizacao";

    private final Transacao transacao;
    private final NfceDAO nfceDAO;
    private final NumeracaoNfceDAO numeracaoDAO;
    private final TributoVendaDAO tributoDAO;
    private final ConfiguracaoDAO configuracaoDAO;
    private final GeracaoNfce geracao;
    private final CertificadoService certificados;
    private final String versaoSistema;
    private final RandomGenerator sorteio;
    private final Clock clock;
    private final ZoneId fuso;

    public NfceService(Transacao transacao, NfceDAO nfceDAO, NumeracaoNfceDAO numeracaoDAO,
                       TributoVendaDAO tributoDAO, ConfiguracaoDAO configuracaoDAO, GeracaoNfce geracao,
                       CertificadoService certificados, String versaoSistema, RandomGenerator sorteio, Clock clock,
                       ZoneId fuso) {
        this.transacao = transacao;
        this.nfceDAO = nfceDAO;
        this.numeracaoDAO = numeracaoDAO;
        this.tributoDAO = tributoDAO;
        this.configuracaoDAO = configuracaoDAO;
        this.geracao = geracao;
        this.certificados = certificados;
        this.versaoSistema = versaoSistema;
        this.sorteio = sorteio;
        this.clock = clock;
        this.fuso = fuso;
    }

    /**
     * Reserva o próximo número da série (F23.3), na transação de quem chama: trava a linha da série, devolve o número e
     * soma 1. Na venda (parte C), é a última trava, depois dos recebíveis.
     */
    public int reservarNumero(Connection con, int serie) throws SQLException {
        return numeracaoDAO.reservar(con, serie);
    }

    /**
     * Reserva o número e grava a NFC-e da venda, na transação de quem chama: PENDENTE na emissão normal, CONTINGENCIA
     * na off-line. O cNF é sorteado com 8 dígitos, diferente do número; a chave sai com a UF, o mês da emissão, o CNPJ
     * da loja, a série, o número e o tpEmis.
     *
     * @param emitidaEm      dhEmi
     * @param contingenciaEm dhCont (só na contingência)
     */
    public long reservar(Connection con, long vendaId, TipoEmissaoNfce tipo, OffsetDateTime emitidaEm,
                         OffsetDateTime contingenciaEm) throws SQLException {
        Configuracoes config = configuracaoDAO.carregar(con);
        String cnpj = config.loja().documento();
        int codigoUf = CodigoUf.de(config.loja().uf()).orElseThrow(() -> new NegocioException(
                "Para a NFC-e, informe a UF da loja em Configurações › Loja."));
        if (cnpj == null || !cnpj.matches("[0-9]{14}")) {
            throw new NegocioException("Para a NFC-e, informe o CNPJ da loja em Configurações › Loja.");
        }
        boolean contingencia = tipo == TipoEmissaoNfce.CONTINGENCIA_OFFLINE;
        if (contingencia != (contingenciaEm != null)) {
            throw new IllegalArgumentException("dhCont só na contingência off-line");
        }
        int serie = config.nfce().serie();
        int numero = reservarNumero(con, serie);
        String codigoNumerico = codigoNumerico(numero);
        String chave = ChaveAcesso.montar(codigoUf, YearMonth.from(emitidaEm.atZoneSameInstant(fuso)), cnpj, serie,
                numero, tipo.codigo(), codigoNumerico);
        return nfceDAO.inserir(con, vendaId, serie, numero, codigoNumerico, chave, tipo, emitidaEm, contingenciaEm,
                contingencia ? StatusNfce.CONTINGENCIA : StatusNfce.PENDENTE, OffsetDateTime.now(clock));
    }

    /** cNF aleatório de 8 dígitos e diferente do número da nota (os 8 últimos dígitos dele). */
    String codigoNumerico(int numero) {
        String proibido = String.format("%08d", numero % 100_000_000);
        String sorteado;
        do {
            sorteado = String.format("%08d", sorteio.nextInt(100_000_000));
        } while (sorteado.equals(proibido));
        return sorteado;
    }

    /**
     * Monta, assina e valida o XML da NFC-e PENDENTE ou em CONTINGENCIA e grava o XML e o QR Code (F23.3). Se a
     * montagem recusa (falta dado do emitente ou fiscal do produto) ou o XSD reprova, a NFC-e fica REJEITADA, com a
     * mensagem, sem ir à SEFAZ: é defeito do cadastro ou do sistema. Sem o certificado deste terminal (ou vencido), nada
     * muda e a recusa vai para quem chamou.
     */
    public NfceGravada prepararXml(long nfceId) {
        CertificadoA1 certificado = certificados.doTerminal();
        return transacao.executar(con -> {
            NfceGravada nfce = nfceDAO.buscar(con, nfceId, true)
                    .orElseThrow(() -> new NegocioException("NFC-e não encontrada."));
            if (nfce.status() != StatusNfce.PENDENTE && nfce.status() != StatusNfce.CONTINGENCIA) {
                throw new NegocioException("A NFC-e nº " + nfce.numero() + " está " + nfce.status().rotulo()
                        .toLowerCase() + " e não pode ser montada de novo.");
            }
            Configuracoes config = configuracaoDAO.carregar(con);
            OffsetDateTime agora = OffsetDateTime.now(clock);
            GeracaoNfce.Resultado resultado;
            try {
                NotaNfce nota = nota(con, nfce, config);
                resultado = geracao.gerar(nota, certificado, config.nfce().urlQrCode(), config.nfce().urlConsulta());
            } catch (NegocioException recusa) {
                LOG.info("NFC-e nº {} rejeitada na montagem", nfce.numero());
                nfceDAO.rejeitarLocal(con, nfceId, recusa.getMessage(), agora);
                return nfceDAO.buscar(con, nfceId, false).orElseThrow();
            }
            if (resultado.valido()) {
                nfceDAO.gravarXml(con, nfceId, resultado.xml(), resultado.qrCode(), agora);
            } else {
                LOG.info("NFC-e nº {} rejeitada pelo XSD: {} erro(s)", nfce.numero(), resultado.erros().size());
                nfceDAO.rejeitarLocal(con, nfceId, resultado.motivo(), agora);
            }
            return nfceDAO.buscar(con, nfceId, false).orElseThrow();
        });
    }

    private NotaNfce nota(Connection con, NfceGravada nfce, Configuracoes config) throws SQLException {
        EmitenteNfce emitente = EmitenteNfce.de(config);
        NfceDAO.VendaNfce venda = nfceDAO.venda(con, nfce.vendaId());
        if (venda.status() != StatusVenda.FINALIZADA) {
            throw new NegocioException("A venda nº " + venda.id() + " está cancelada: a NFC-e não é emitida.");
        }
        List<NotaNfce.Item> itens = new ArrayList<>();
        for (NfceDAO.ItemVenda i : venda.itens()) {
            itens.add(new NotaNfce.Item(i.sequencia(), i.produtoId(), i.nome(), i.codigoBarras(), i.unidade(),
                    i.quantidade(), i.valorBruto(), i.desconto(), tributoDAO.buscar(con, i.vendaItemId()).orElse(null)));
        }
        List<NotaNfce.Pagamento> pagamentos = venda.pagamentos().stream()
                .map(p -> new NotaNfce.Pagamento(p.tpag(), p.descricao(), p.valor())).toList();
        NotaNfce.Identificacao id = new NotaNfce.Identificacao(nfce.serie(), nfce.numero(), nfce.codigoNumerico(),
                nfce.tipoEmissao(), nfce.emitidaEm(), nfce.contingenciaEm(),
                nfce.tipoEmissao() == TipoEmissaoNfce.CONTINGENCIA_OFFLINE ? JUSTIFICATIVA_CONTINGENCIA : null,
                nfce.chave());
        return new NotaNfce(id, emitente, venda.consumidorDocumento(), itens, pagamentos, venda.troco(),
                venda.total(), config.nfce().respTecnico(), versaoSistema);
    }

    /** As linhas de {@code vw_nfce_inconsistente} (Diagnóstico de Configurações; o esperado é vazio). */
    public List<String> diagnostico(Sessao sessao) {
        sessao.exigirSupervisor();
        return transacao.executar(nfceDAO::inconsistencias);
    }
}
