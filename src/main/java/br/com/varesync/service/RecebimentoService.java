package br.com.varesync.service;

import br.com.varesync.dao.ClienteDAO;
import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.FormaPagamentoDAO;
import br.com.varesync.dao.RecebimentoDAO;
import br.com.varesync.dao.TituloReceberDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.CaixaSessao;
import br.com.varesync.model.ClienteCrediario;
import br.com.varesync.model.ClienteVenda;
import br.com.varesync.model.Comprovante;
import br.com.varesync.model.ComprovanteRecebimento;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.PlanoRecebimento;
import br.com.varesync.model.RecebimentoSessao;
import br.com.varesync.model.TituloEmAberto;
import br.com.varesync.model.TituloReceber;
import br.com.varesync.util.CalculoEncargos;
import br.com.varesync.util.CpfCnpj;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Recebimento do crediário (F14.3): títulos em aberto com os encargos do dia, valor aplicado do vencimento mais antigo
 * para o mais novo, desconto nos encargos, pagamento como no PDV (dinheiro com troco) e estorno na mesma sessão de
 * caixa. Exige RECEBIMENTO ou a liberação de quem tem, com o caixa aberto no terminal. Travas na ordem: caixa → cliente
 * → títulos (na ordem do id), a mesma da venda e da devolução.
 */
public class RecebimentoService {

    public static final String ENTIDADE_RECEBIMENTO = "recebimento";
    static final int TAMANHO_MAXIMO_MOTIVO = 200;

    private final Transacao transacao;
    private final RecebimentoDAO dao;
    private final TituloReceberDAO tituloDAO;
    private final ClienteDAO clienteDAO;
    private final FormaPagamentoDAO formaDAO;
    private final ConfiguracaoDAO configuracaoDAO;
    private final CaixaService caixaService;
    private final AuditoriaService auditoria;
    private final Clock clock;

    /** Recebimento gravado: número, total, troco e comprovante. */
    public record RecebimentoRegistrado(long id, BigDecimal total, BigDecimal troco, ComprovanteRecebimento comprovante) {
    }

    public RecebimentoService(Transacao transacao, RecebimentoDAO dao, TituloReceberDAO tituloDAO, ClienteDAO clienteDAO,
                              FormaPagamentoDAO formaDAO, ConfiguracaoDAO configuracaoDAO, CaixaService caixaService,
                              AuditoriaService auditoria, Clock clock) {
        this.transacao = transacao;
        this.dao = dao;
        this.tituloDAO = tituloDAO;
        this.clienteDAO = clienteDAO;
        this.formaDAO = formaDAO;
        this.configuracaoDAO = configuracaoDAO;
        this.caixaService = caixaService;
        this.auditoria = auditoria;
        this.clock = clock;
    }

    /**
     * Clientes para receber: CPF/CNPJ completo → o dono dele; senão, a busca do cadastro. Inclui os inativos: inativar o
     * cliente não apaga o que ele deve.
     */
    public List<ClienteVenda> pesquisarClientes(Sessao sessao, String texto) {
        exigirConsulta(sessao);
        return transacao.executar(con -> {
            Optional<String> documento = ClienteService.documentoCompleto(texto);
            if (documento.isPresent()) {
                return clienteDAO.buscarPorDocumento(con, documento.get()).stream().toList();
            }
            return clienteDAO.pesquisarParaVenda(con, ClienteService.busca(texto, true));
        });
    }

    /** Formas do recebimento: as ativas, menos crediário e crédito do cliente. */
    public List<FormaPagamento> formas(Sessao sessao) {
        exigirConsulta(sessao);
        return transacao.executar(con -> formaDAO.listarAtivas(con).stream()
                .filter(f -> !f.crediario() && !f.creditoCliente()).toList());
    }

    /** Títulos em aberto do cliente, do vencimento mais antigo para o mais novo, com os encargos de hoje. */
    public List<TituloEmAberto> titulos(Sessao sessao, long clienteId) {
        exigirConsulta(sessao);
        return transacao.executar(con -> comEncargos(con, tituloDAO.emAbertoDoClienteLista(con, clienteId, false)));
    }

    /**
     * Como o recebimento se distribui, sem gravar.
     *
     * @param tituloIds títulos escolhidos; vazio = todos os em aberto
     * @param valor     valor a receber; nulo = quitar os títulos
     * @param desconto  desconto nos encargos; nulo = sem desconto
     */
    public PlanoRecebimento simular(Sessao sessao, long clienteId, List<Long> tituloIds, BigDecimal valor,
                                    BigDecimal desconto) {
        exigirConsulta(sessao);
        BigDecimal quantia = valor == null ? null : CaixaService.valor(valor, "o valor a receber", false);
        BigDecimal descontoFinal = descontoLido(desconto);
        return transacao.executar(con -> planejar(comEncargos(con, tituloDAO.emAbertoDoClienteLista(con, clienteId,
                false)), tituloIds, quantia, descontoFinal));
    }

    /** Pagamento vazio para o valor do plano: formas como no PDV, só o dinheiro dá troco. */
    public Recebimento pagamento(PlanoRecebimento plano) {
        return new Recebimento(plano.total());
    }

    /**
     * Grava o recebimento, numa transação: confere os títulos travados, recalcula os encargos do dia, baixa o principal,
     * grava as tabelas de apoio e a auditoria.
     *
     * @param liberacao         de quem tem RECEBIMENTO; nula se o próprio usuário tem
     * @param liberacaoDesconto de quem tem LIMITE_CREDITO; nula sem desconto ou se o próprio usuário tem
     */
    public RecebimentoRegistrado registrar(Sessao sessao, long clienteId, List<Long> tituloIds, BigDecimal valor,
                                           BigDecimal desconto, Recebimento pagamento, Liberacao liberacao,
                                           Liberacao liberacaoDesconto) {
        Liberacao liberouRecebimento = sessao.exigirOuLiberado(Permissao.RECEBIMENTO, liberacao);
        BigDecimal descontoFinal = descontoLido(desconto);
        Liberacao liberouDesconto = descontoFinal.signum() > 0
                ? sessao.exigirOuLiberado(Permissao.LIMITE_CREDITO, liberacaoDesconto) : null;
        BigDecimal quantia = valor == null ? null : CaixaService.valor(valor, "o valor a receber", false);
        for (Recebimento.Parcela p : pagamento.parcelas()) {
            if (p.forma().crediario() || p.forma().creditoCliente()) {
                throw new NegocioException("O crediário não pode ser pago em " + p.forma().descricao() + ".");
            }
        }
        OffsetDateTime agora = OffsetDateTime.now(clock);
        return transacao.executar(con -> {
            CaixaSessao caixa = caixaService.travarAberta(con, sessao);
            clienteDAO.travarParaCrediario(con, clienteId)
                    .orElseThrow(() -> new NegocioException("Cliente não encontrado."));
            PlanoRecebimento plano = planejar(comEncargos(con, tituloDAO.emAbertoDoClienteLista(con, clienteId, true)),
                    tituloIds, quantia, descontoFinal);
            if (pagamento.total().compareTo(plano.total()) != 0) {
                throw new NegocioException("O valor a receber mudou para " + Formatos.moeda(plano.total())
                        + ". Confira e informe o pagamento de novo.");
            }
            if (!pagamento.quitado()) {
                throw new NegocioException("Falta receber " + Formatos.moeda(pagamento.falta()) + ".");
            }
            Long liberadoPor = liberouRecebimento != null ? Long.valueOf(liberouRecebimento.usuarioId())
                    : liberouDesconto != null ? Long.valueOf(liberouDesconto.usuarioId()) : null;
            long id = dao.inserir(con, new RecebimentoDAO.Novo(clienteId, caixa.id(), sessao.usuarioId(), liberadoPor,
                    agora, plano.principal(), plano.multa(), plano.juros(), plano.desconto(), plano.total(),
                    pagamento.troco()));
            List<PlanoRecebimento.Linha> naOrdemDasTravas = new ArrayList<>(plano.linhas());
            naOrdemDasTravas.sort(Comparator.comparingLong(l -> l.titulo().tituloId()));
            for (PlanoRecebimento.Linha l : naOrdemDasTravas) {
                dao.inserirTitulo(con, id, l.titulo().tituloId(), l.principal(), l.multa(), l.juros(), l.desconto());
                tituloDAO.baixar(con, l.titulo().tituloId(), l.principal());
            }
            for (Recebimento.Parcela p : pagamento.valoresAbatidos()) {
                dao.inserirPagamento(con, id, p.forma().id(), p.valor());
            }
            auditoria.registrar(con, sessao, AcaoAuditoria.RECEBIMENTO_REGISTRADO, ENTIDADE_RECEBIMENTO, id,
                    "Recebimento nº " + id + " do cliente nº " + clienteId + " no caixa nº " + caixa.id() + ": "
                            + plano.linhas().size() + " título(s), principal " + Formatos.moeda(plano.principal())
                            + ", multa " + Formatos.moeda(plano.multa()) + ", juros " + Formatos.moeda(plano.juros())
                            + (plano.desconto().signum() > 0 ? ", desconto " + Formatos.moeda(plano.desconto()) : "")
                            + ", total " + Formatos.moeda(plano.total())
                            + (liberouRecebimento == null ? "" : " (" + liberouRecebimento.descricao() + ")")
                            + (liberouDesconto == null ? "" : " (desconto: " + liberouDesconto.descricao() + ")"));
            return new RecebimentoRegistrado(id, plano.total(), pagamento.troco(),
                    montarComprovante(con, id, configuracaoDAO.carregar(con)));
        });
    }

    /**
     * Estorno (F14.3): só na mesma sessão de caixa, ainda aberta, com CANCELAR_VENDA ou a liberação de quem tem. Volta o
     * que foi pago dos títulos e deixa de contar no dinheiro esperado; o dinheiro devolvido não pode passar do que
     * deveria estar na gaveta.
     */
    public void estornar(Sessao sessao, long recebimentoId, String motivo, Liberacao liberacao) {
        Liberacao liberou = sessao.exigirOuLiberado(Permissao.CANCELAR_VENDA, liberacao);
        String motivoFinal = Textos.normalizarEspacos(motivo);
        if (motivoFinal.isEmpty()) {
            throw new NegocioException("Informe o motivo do estorno.");
        }
        if (motivoFinal.length() > TAMANHO_MAXIMO_MOTIVO) {
            throw new NegocioException("O motivo pode ter no máximo " + TAMANHO_MAXIMO_MOTIVO + " caracteres.");
        }
        OffsetDateTime agora = OffsetDateTime.now(clock);
        long autorizou = liberou == null ? sessao.usuarioId() : liberou.usuarioId();
        transacao.executar(con -> {
            CaixaSessao caixa = caixaService.travarAberta(con, sessao);
            RecebimentoDAO.Cabecalho r = dao.buscar(con, recebimentoId, false)
                    .orElseThrow(() -> new NegocioException("Recebimento nº " + recebimentoId + " não encontrado."));
            ClienteCrediario cliente = clienteDAO.travarParaCrediario(con, r.clienteId()).orElseThrow();
            r = dao.buscar(con, recebimentoId, true).orElseThrow();
            if (r.estornado()) {
                throw new NegocioException("O recebimento nº " + recebimentoId + " já foi estornado.");
            }
            if (r.caixaSessaoId() != caixa.id()) {
                throw new NegocioException("O recebimento nº " + recebimentoId + " é de outra sessão de caixa: só pode "
                        + "ser estornado na sessão em que foi feito.");
            }
            if (r.anonimizado()) {
                throw new NegocioException("O cliente nº " + cliente.id() + " foi anonimizado (LGPD): o recebimento "
                        + "não pode mais ser estornado.");
            }
            if (dao.dinheiro(con, recebimentoId).compareTo(caixaService.dinheiroEsperado(con, caixa.id())) > 0) {
                // Sem mostrar o esperado: o fechamento é às cegas.
                throw new NegocioException("O dinheiro deste recebimento é maior que o que deveria estar na gaveta. "
                        + "Faça um suprimento antes de estornar.");
            }
            List<RecebimentoDAO.Parte> partes = dao.partes(con, recebimentoId);
            Set<Long> vendas = new HashSet<>();
            partes.forEach(p -> vendas.add(p.vendaId()));
            for (long vendaId : vendas.stream().sorted().toList()) {
                tituloDAO.daVenda(con, vendaId, true); // mesma trava da devolução e do cancelamento
            }
            for (RecebimentoDAO.Parte p : partes) {
                tituloDAO.baixar(con, p.tituloId(), p.principal().negate());
            }
            dao.estornar(con, recebimentoId, autorizou, agora);
            auditoria.registrar(con, sessao, AcaoAuditoria.RECEBIMENTO_ESTORNADO, ENTIDADE_RECEBIMENTO, recebimentoId,
                    "Recebimento nº " + recebimentoId + " estornado: principal " + Formatos.moeda(r.principal())
                            + ", total " + Formatos.moeda(r.total()) + ". Motivo: " + motivoFinal
                            + (liberou == null ? "" : " (" + liberou.descricao() + ")"));
            return null;
        });
    }

    /** Recebimentos da sessão de caixa aberta neste terminal; vazio sem caixa aberto. */
    public List<RecebimentoSessao> daSessaoAberta(Sessao sessao) {
        exigirConsulta(sessao);
        return transacao.executar(con -> {
            Optional<CaixaSessao> caixa = caixaService.caixaAberto(con, sessao);
            return caixa.isEmpty() ? List.<RecebimentoSessao>of() : dao.daSessao(con, caixa.get().id());
        });
    }

    /** Reimpressão do comprovante. */
    public ComprovanteRecebimento comprovante(Sessao sessao, long recebimentoId) {
        exigirConsulta(sessao);
        return transacao.executar(con -> montarComprovante(con, recebimentoId, configuracaoDAO.carregar(con)));
    }

    // ----- Apoio -----

    private static void exigirConsulta(Sessao sessao) {
        sessao.exigirUma(Permissao.RECEBIMENTO, Permissao.PDV);
    }

    private static BigDecimal descontoLido(BigDecimal desconto) {
        return desconto == null ? Dinheiro.ZERO : CaixaService.valor(desconto, "o desconto nos encargos", true);
    }

    /** Encargos de hoje, do vencimento mais antigo para o mais novo. */
    private List<TituloEmAberto> comEncargos(Connection con, List<TituloReceber> titulos) throws SQLException {
        Configuracoes.Crediario c = configuracaoDAO.carregar(con).crediario();
        LocalDate hoje = LocalDate.now(clock);
        return titulos.stream()
                .sorted(Comparator.comparing(TituloReceber::vencimento).thenComparingLong(TituloReceber::vendaId)
                        .thenComparingInt(TituloReceber::parcela))
                .map(t -> new TituloEmAberto(t.id(), t.vendaId(), t.parcela(), t.totalParcelas(), t.vencimento(),
                        t.valor(), t.valorPago(), t.valorAbatido(), CalculoEncargos.calcular(t.emAberto(),
                        t.vencimento(), hoje, c.multaPct(), c.jurosMesPct(), c.carenciaDias())))
                .toList();
    }

    /** Escolhe os títulos e aplica o valor ou quita com o desconto (F14.3). */
    private static PlanoRecebimento planejar(List<TituloEmAberto> abertos, List<Long> tituloIds, BigDecimal valor,
                                             BigDecimal desconto) {
        List<TituloEmAberto> escolhidos = abertos;
        if (tituloIds != null && !tituloIds.isEmpty()) {
            Set<Long> ids = new HashSet<>(tituloIds);
            escolhidos = abertos.stream().filter(t -> ids.contains(t.tituloId())).toList();
            if (escolhidos.size() != ids.size()) {
                throw new NegocioException("Um dos títulos escolhidos não está mais em aberto. Confira a lista.");
            }
        }
        if (escolhidos.isEmpty()) {
            throw new NegocioException("O cliente não tem títulos em aberto.");
        }
        List<CalculoEncargos.Encargos> encargos = escolhidos.stream().map(TituloEmAberto::encargos).toList();
        BigDecimal paraQuitar = encargos.stream().map(CalculoEncargos.Encargos::paraQuitar)
                .reduce(Dinheiro.ZERO, BigDecimal::add);
        BigDecimal totalEncargos = encargos.stream().map(CalculoEncargos.Encargos::encargos)
                .reduce(Dinheiro.ZERO, BigDecimal::add);
        if (desconto.compareTo(totalEncargos) > 0) {
            throw new NegocioException("O desconto (" + Formatos.moeda(desconto) + ") passa dos encargos dos títulos ("
                    + Formatos.moeda(totalEncargos) + ").");
        }
        if (valor != null && desconto.signum() > 0 && valor.compareTo(paraQuitar.subtract(desconto)) != 0) {
            throw new NegocioException("O desconto nos encargos vale só para quitar os títulos: selecione os títulos "
                    + "que serão quitados, sem digitar outro valor.");
        }
        if (valor != null && desconto.signum() == 0 && valor.compareTo(paraQuitar) > 0) {
            throw new NegocioException("O valor (" + Formatos.moeda(valor) + ") passa do que falta para quitar os "
                    + "títulos (" + Formatos.moeda(paraQuitar) + ").");
        }
        List<CalculoEncargos.Aplicacao> aplicacoes = valor == null || desconto.signum() > 0
                ? CalculoEncargos.quitar(encargos, desconto) : CalculoEncargos.aplicarValor(valor, encargos);
        List<PlanoRecebimento.Linha> linhas = new ArrayList<>();
        for (CalculoEncargos.Aplicacao a : aplicacoes) {
            linhas.add(new PlanoRecebimento.Linha(escolhidos.get(a.indice()), a.principal(), a.multa(), a.juros(),
                    a.desconto()));
        }
        return new PlanoRecebimento(linhas);
    }

    private ComprovanteRecebimento montarComprovante(Connection con, long id, Configuracoes config)
            throws SQLException {
        RecebimentoDAO.Cabecalho c = dao.buscar(con, id, false)
                .orElseThrow(() -> new NegocioException("Recebimento nº " + id + " não encontrado."));
        Map<Long, List<Comprovante.ParcelaCrediario>> posicoes = new HashMap<>();
        List<ComprovanteRecebimento.Titulo> titulos = new ArrayList<>();
        for (RecebimentoDAO.Parte p : dao.partes(con, id)) {
            List<Comprovante.ParcelaCrediario> posicao = posicoes.get(p.vendaId());
            if (posicao == null) {
                posicao = tituloDAO.posicaoEm(con, p.vendaId(), c.dataHora(), id, null);
                posicoes.put(p.vendaId(), posicao);
            }
            BigDecimal depois = posicao.stream().filter(x -> x.parcela() == p.parcela())
                    .map(Comprovante.ParcelaCrediario::valor).findFirst().orElse(Dinheiro.ZERO);
            titulos.add(new ComprovanteRecebimento.Titulo(p.vendaId(), p.parcela(), p.totalParcelas(), p.vencimento(),
                    p.principal(), p.multa(), p.juros(), p.desconto(), depois));
        }
        titulos.sort(Comparator.comparing(ComprovanteRecebimento.Titulo::vencimento)
                .thenComparingLong(ComprovanteRecebimento.Titulo::vendaId)
                .thenComparingInt(ComprovanteRecebimento.Titulo::parcela));
        Configuracoes.Loja l = config.loja();
        return new ComprovanteRecebimento(new Comprovante.Loja(l.nome(), l.documento(), l.endereco(), l.telefone()),
                c.id(), c.dataHora(), c.usuario(), c.liberadoPor(), new Comprovante.Cliente(c.clienteNome(),
                c.clienteDocumento() == null ? "" : CpfCnpj.ocultar(c.clienteDocumento())), titulos, c.principal(),
                c.multa(), c.juros(), c.desconto(), c.total(), dao.pagamentos(con, id), c.troco(), c.estornadoEm(),
                config.rodapeComprovante());
    }
}
