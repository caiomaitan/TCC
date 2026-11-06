package br.com.varesync.service;

import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.EstoqueDAO;
import br.com.varesync.dao.LoteDAO;
import br.com.varesync.dao.MovimentoEstoqueDAO;
import br.com.varesync.dao.ProdutoDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.InconsistenciaEstoque;
import br.com.varesync.model.Lote;
import br.com.varesync.model.MotivoPerda;
import br.com.varesync.model.MovimentoLote;
import br.com.varesync.model.Kardex;
import br.com.varesync.model.OrigemMovimento;
import br.com.varesync.model.ParteLote;
import br.com.varesync.model.TipoLoteMovimento;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.ResultadoAjuste;
import br.com.varesync.model.SaldoEstoque;
import br.com.varesync.model.TipoAjuste;
import br.com.varesync.model.TipoMovimento;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Estoque (6.4, RF09). {@link #movimentar} é o único caminho para movimentar estoque: insere em
 * {@code movimento_estoque} e o trigger atualiza o saldo; roda na transação de quem chama, que confere a própria
 * permissão (ex.: estoque inicial no cadastro de produto exige PRODUTOS).
 * Os demais métodos são a rotina Estoque: consulta com kardex, ajustes manuais e alertas (permissão ESTOQUE),
 * e o diagnóstico (supervisor).
 */
public class EstoqueService {

    static final int TAMANHO_MAXIMO_MOTIVO = 200;
    /** Período padrão do kardex: os últimos 30 dias, contando hoje. */
    static final int DIAS_PERIODO_PADRAO = 30;
    static final int TAMANHO_MAXIMO_CODIGO_LOTE = 30;

    private final Transacao transacao;
    private final ProdutoDAO produtoDAO;
    private final EstoqueDAO estoqueDAO;
    private final MovimentoEstoqueDAO movimentoDAO;
    private final LoteDAO loteDAO;
    private final ConfiguracaoDAO configuracaoDAO;
    private final AuditoriaService auditoria;
    private final Clock clock;

    public EstoqueService(Transacao transacao, ProdutoDAO produtoDAO, EstoqueDAO estoqueDAO,
                          MovimentoEstoqueDAO movimentoDAO, LoteDAO loteDAO, ConfiguracaoDAO configuracaoDAO,
                          AuditoriaService auditoria, Clock clock) {
        this.configuracaoDAO = configuracaoDAO;
        this.transacao = transacao;
        this.produtoDAO = produtoDAO;
        this.estoqueDAO = estoqueDAO;
        this.movimentoDAO = movimentoDAO;
        this.loteDAO = loteDAO;
        this.auditoria = auditoria;
        this.clock = clock;
    }

    /**
     * Registra um movimento. Valida antes do banco para dar mensagem clara; o banco é a última barreira.
     *
     * @param quantidadeComSinal positiva entra, negativa sai; o sinal tem de combinar com o tipo
     * @param motivo             obrigatório em ajuste e perda
     * @param custoOpcional      nulo = custo atual do produto (o trigger preenche)
     * @return id do movimento
     */
    public long movimentar(Connection con, Sessao sessao, long produtoId, TipoMovimento tipo,
                           BigDecimal quantidadeComSinal, OrigemMovimento origem, String motivo,
                           BigDecimal custoOpcional) throws SQLException {
        return movimentar(con, sessao, produtoId, tipo, quantidadeComSinal, origem, motivo, custoOpcional, null);
    }

    /**
     * Registra um movimento e, se o produto controla validade (F17.2), distribui a quantidade nos lotes: pela lista
     * informada (lotes existentes ou novos, somando a quantidade do movimento) ou, sem ela, pela regra do tipo do
     * movimento (saídas por FEFO com o "SEM LOTE" primeiro; cancelamento e devolução para os lotes da venda; ajuste de
     * entrada e sobra de inventário no "SEM LOTE"). Em produto sem controle, a lista é ignorada.
     *
     * <p>O controle e os saldos dos lotes são lidos depois do INSERT do movimento, que trava a linha do produto em
     * {@code produto_estoque}: duas vendas simultâneas não consomem o mesmo lote.
     *
     * @param lotes nula ou vazia = regra do tipo; quantidades positivas (o sinal vem do movimento)
     */
    public long movimentar(Connection con, Sessao sessao, long produtoId, TipoMovimento tipo,
                           BigDecimal quantidadeComSinal, OrigemMovimento origem, String motivo,
                           BigDecimal custoOpcional, List<ParteLote> lotes) throws SQLException {
        if (!origem.compativelCom(tipo)) {
            throw new IllegalArgumentException("Origem " + origem + " não combina com o movimento " + tipo);
        }
        if (!tipo.aceitaQuantidade(quantidadeComSinal.signum())) {
            throw new NegocioException(quantidadeComSinal.signum() == 0
                    ? "A quantidade do movimento não pode ser zero."
                    : "A quantidade do movimento não corresponde ao tipo (entrada ou saída).");
        }
        String motivoNormalizado = Textos.vazio(motivo) ? null : Textos.normalizarEspacos(motivo);
        if (tipo.exigeMotivo() && motivoNormalizado == null) {
            throw new NegocioException("Informe o motivo do ajuste.");
        }
        if (motivoNormalizado != null && motivoNormalizado.length() > TAMANHO_MAXIMO_MOTIVO) {
            throw new NegocioException("O motivo pode ter no máximo " + TAMANHO_MAXIMO_MOTIVO + " caracteres.");
        }
        boolean fracionada = produtoDAO.unidadeFracionada(con, produtoId)
                .orElseThrow(() -> new NegocioException("Produto não encontrado."));
        ValidacaoProduto.quantidade(quantidadeComSinal.abs(), "A quantidade", fracionada);

        OffsetDateTime agora = OffsetDateTime.now(clock);
        long id = movimentoDAO.inserir(con, produtoId, tipo, quantidadeComSinal, custoOpcional,
                origem.vendaItemId(), origem.inventarioItemId(), origem.entradaItemId(), origem.devolucaoItemId(),
                motivoNormalizado,
                sessao.usuarioId(), agora);
        if (produtoDAO.controlaValidade(con, produtoId).orElse(false)) {
            distribuir(con, sessao, produtoId, id, tipo, quantidadeComSinal, origem, lotes, fracionada, agora);
        }
        return id;
    }

    // ----- Lotes (F17.2) -----

    private void distribuir(Connection con, Sessao sessao, long produtoId, long movimentoId, TipoMovimento tipo,
                            BigDecimal comSinal, OrigemMovimento origem, List<ParteLote> lotes, boolean fracionada,
                            OffsetDateTime agora) throws SQLException {
        BigDecimal quantidade = comSinal.abs();
        boolean saida = comSinal.signum() < 0;
        List<RegraLotes.Parte> partes;
        if (lotes != null && !lotes.isEmpty()) {
            partes = escolhidos(con, sessao, produtoId, lotes, quantidade, saida, fracionada, agora);
        } else {
            partes = switch (tipo) {
                // Na venda, o vencido fica por último (F17.2); nas outras saídas, FEFO puro.
                case VENDA -> RegraLotes.saida(loteDAO.listar(con, produtoId), quantidade, LocalDate.now(clock));
                case AJUSTE_SAIDA, PERDA -> RegraLotes.saida(loteDAO.listar(con, produtoId), quantidade);
                case INVENTARIO -> saida ? RegraLotes.saida(loteDAO.listar(con, produtoId), quantidade)
                        : List.of(new RegraLotes.Parte(semLoteId(con, produtoId).orElse(null), quantidade));
                case AJUSTE_ENTRADA ->
                        List.of(new RegraLotes.Parte(semLoteId(con, produtoId).orElse(null), quantidade));
                case CANCELAMENTO_VENDA -> RegraLotes.cancelamento(loteDAO.daVenda(con, origem.vendaItemId()),
                        semLoteId(con, produtoId).orElse(null), quantidade);
                case DEVOLUCAO_VENDA -> {
                    long vendaItemId = loteDAO.vendaItemDaDevolucao(con, origem.devolucaoItemId())
                            .orElseThrow(() -> new NegocioException("Item da devolução não encontrado."));
                    yield RegraLotes.devolucao(loteDAO.daVenda(con, vendaItemId),
                            loteDAO.devolvidoDaVenda(con, vendaItemId), semLoteId(con, produtoId).orElse(null),
                            quantidade);
                }
                case ENTRADA_COMPRA -> throw new NegocioException("O produto " + nome(con, produtoId)
                        + " controla validade: divida o item da entrada em lotes (validade e quantidade).");
            };
        }
        for (RegraLotes.Parte p : partes) {
            long loteId = p.loteId() != null ? p.loteId() : criarSemLote(con, sessao, produtoId, agora);
            loteDAO.inserirMovimento(con, loteId, TipoLoteMovimento.MOVIMENTO, movimentoId, null,
                    saida ? p.quantidade().negate() : p.quantidade(), sessao.usuarioId(), agora);
        }
    }

    /**
     * Lotes informados por quem chama: pertencem ao produto, somam a quantidade do movimento e, na saída, cabem no saldo
     * de cada lote (o "SEM LOTE" pode ficar negativo). Lote novo exige validade, exceto o "SEM LOTE".
     */
    private List<RegraLotes.Parte> escolhidos(Connection con, Sessao sessao, long produtoId, List<ParteLote> lotes,
                                              BigDecimal quantidade, boolean saida, boolean fracionada,
                                              OffsetDateTime agora) throws SQLException {
        java.util.Map<Long, BigDecimal> porLote = new java.util.LinkedHashMap<>();
        BigDecimal soma = BigDecimal.ZERO;
        for (ParteLote parte : lotes) {
            if (parte.quantidade() == null || parte.quantidade().signum() <= 0) {
                throw new NegocioException("A quantidade de cada lote deve ser maior que zero.");
            }
            ValidacaoProduto.quantidade(parte.quantidade(), "A quantidade do lote", fracionada);
            long loteId = resolverLote(con, sessao, produtoId, parte, !saida, agora).id();
            porLote.merge(loteId, parte.quantidade(), BigDecimal::add);
            soma = soma.add(parte.quantidade());
        }
        if (soma.compareTo(quantidade) != 0) {
            throw new NegocioException("A soma dos lotes (" + Formatos.quantidade(soma, fracionada)
                    + ") é diferente da quantidade do movimento (" + Formatos.quantidade(quantidade, fracionada) + ").");
        }
        List<RegraLotes.Parte> partes = new ArrayList<>();
        for (var e : porLote.entrySet()) {
            if (saida) {
                Lote lote = loteDAO.buscar(con, e.getKey()).orElseThrow();
                if (!lote.semLote() && lote.saldo().compareTo(e.getValue()) < 0) {
                    throw new NegocioException("O lote " + lote.descricao() + " tem só "
                            + Formatos.quantidade(lote.saldo(), fracionada) + ": não dá para tirar "
                            + Formatos.quantidade(e.getValue(), fracionada) + ".");
                }
            }
            partes.add(new RegraLotes.Parte(e.getKey(), e.getValue()));
        }
        return partes;
    }

    /**
     * Lote existente (do produto) ou pelo código e validade; com {@code podeCriar}, cria o que não existe (o "SEM LOTE"
     * é sempre criado quando preciso).
     */
    private Lote resolverLote(Connection con, Sessao sessao, long produtoId, ParteLote parte, boolean podeCriar,
                              OffsetDateTime agora) throws SQLException {
        if (parte.loteId() != null) {
            Lote lote = loteDAO.buscar(con, parte.loteId())
                    .orElseThrow(() -> new NegocioException("Lote não encontrado."));
            if (lote.produtoId() != produtoId) {
                throw new NegocioException("O lote " + lote.descricao() + " não é deste produto.");
            }
            return lote;
        }
        String codigo = ParteLote.normalizarCodigo(parte.codigo());
        if (codigo.length() > TAMANHO_MAXIMO_CODIGO_LOTE) {
            throw new NegocioException("O código do lote pode ter no máximo " + TAMANHO_MAXIMO_CODIGO_LOTE
                    + " caracteres.");
        }
        if (parte.validade() == null && !codigo.isEmpty()) {
            throw new NegocioException("Informe a validade do lote " + codigo + ".");
        }
        java.util.Optional<Long> id = loteDAO.buscarId(con, produtoId, codigo, parte.validade());
        if (id.isEmpty()) {
            if (!podeCriar && parte.validade() != null) {
                throw new NegocioException("O lote " + Lote.descrever(codigo, parte.validade())
                        + " não existe neste produto.");
            }
            id = java.util.Optional.of(loteDAO.inserir(con, produtoId, codigo, parte.validade(), sessao.usuarioId(),
                    agora));
        }
        return loteDAO.buscar(con, id.get()).orElseThrow();
    }

    private java.util.Optional<Long> semLoteId(Connection con, long produtoId) throws SQLException {
        return loteDAO.buscarId(con, produtoId, "", null);
    }

    private long criarSemLote(Connection con, Sessao sessao, long produtoId, OffsetDateTime agora)
            throws SQLException {
        java.util.Optional<Long> existente = semLoteId(con, produtoId);
        return existente.isPresent() ? existente.get()
                : loteDAO.inserir(con, produtoId, "", null, sessao.usuarioId(), agora);
    }

    private String nome(Connection con, long produtoId) throws SQLException {
        return estoqueDAO.buscar(con, produtoId).map(SaldoEstoque::descricao).orElse("nº " + produtoId);
    }

    /**
     * Partes de um movimento nos lotes (quantidades positivas), na ordem de gravação; vazio se o produto não controla
     * validade. Serve para repetir os mesmos lotes em outro movimento (a perda do item devolvido avariado).
     */
    public List<ParteLote> lotesDoMovimento(Connection con, long movimentoId) throws SQLException {
        return loteDAO.doMovimento(con, movimentoId).stream()
                .map(l -> ParteLote.existente(l.loteId(), l.quantidade().abs()))
                .toList();
    }

    /** Lotes do produto com o saldo: o "SEM LOTE" primeiro e os demais por validade. */
    public List<Lote> lotes(Sessao sessao, long produtoId) {
        sessao.exigir(Permissao.ESTOQUE);
        return transacao.executar(con -> loteDAO.listar(con, produtoId));
    }

    /**
     * Aba Lotes do Estoque (F17.3): se o produto controla validade, os lotes com saldo (o "SEM LOTE" primeiro) e o
     * prazo do alerta ({@code validade.alerta_dias}), para destacar os que vencem logo.
     */
    public LotesDoProduto lotesDoProduto(Sessao sessao, long produtoId) {
        sessao.exigir(Permissao.ESTOQUE);
        return transacao.executar(con -> {
            boolean controla = produtoDAO.controlaValidade(con, produtoId)
                    .orElseThrow(() -> new NegocioException("Produto não encontrado."));
            return new LotesDoProduto(controla, configuracaoDAO.carregar(con).validadeAlertaDias(),
                    LocalDate.now(clock), loteDAO.listar(con, produtoId));
        });
    }

    /**
     * Lotes de um produto, com o que a tela precisa para destacá-los.
     *
     * @param alertaDias o lote que vence de hoje até hoje + {@code alertaDias} está "vencendo"
     */
    public record LotesDoProduto(boolean controla, int alertaDias, LocalDate hoje, List<Lote> lotes) {

        public LotesDoProduto {
            lotes = List.copyOf(lotes);
        }

        /** Dias para vencer (negativo = vencido); nulo no "SEM LOTE". */
        public Long diasParaVencer(Lote lote) {
            return lote.validade() == null ? null
                    : java.time.temporal.ChronoUnit.DAYS.between(hoje, lote.validade());
        }

        /** "Vencido", "Vence hoje", "Vence em 3 dias", "SEM LOTE negativo", "OK" ou vazio (zerado). */
        public String situacao(Lote lote) {
            if (lote.semLote()) {
                return lote.saldo().signum() < 0 ? "Negativo" : "Validade desconhecida";
            }
            long dias = diasParaVencer(lote);
            if (lote.saldo().signum() <= 0) {
                return "Zerado";
            }
            if (dias < 0) {
                return "Vencido";
            }
            if (dias <= alertaDias) {
                return dias == 0 ? "Vence hoje" : dias == 1 ? "Vence amanhã" : "Vence em " + dias + " dias";
            }
            return "OK";
        }

        /** Vencido com saldo ou "SEM LOTE" negativo: em vermelho. */
        public boolean critico(Lote lote) {
            return lote.semLote() ? lote.saldo().signum() < 0
                    : lote.saldo().signum() > 0 && diasParaVencer(lote) < 0;
        }

        /** Vencendo em até {@code alertaDias} (com saldo): em amarelo. */
        public boolean vencendo(Lote lote) {
            if (lote.semLote() || lote.saldo().signum() <= 0) {
                return false;
            }
            long dias = diasParaVencer(lote);
            return dias >= 0 && dias <= alertaDias;
        }
    }

    /** Razão do lote (F17.3): data, tipo, documento ou motivo e quantidade, do mais antigo para o mais recente. */
    public List<MovimentoLote> razaoDoLote(Sessao sessao, long produtoId, long loteId) {
        sessao.exigir(Permissao.ESTOQUE);
        return transacao.executar(con -> {
            Lote lote = loteDAO.buscar(con, loteId).orElseThrow(() -> new NegocioException("Lote não encontrado."));
            if (lote.produtoId() != produtoId) {
                throw new NegocioException("O lote " + lote.descricao() + " não é deste produto.");
            }
            return loteDAO.razao(con, loteId);
        });
    }

    /**
     * Liga ou desliga o controle de validade na transação de quem chama (cadastro do produto, que confere a permissão e
     * grava a auditoria). Trava o saldo do produto antes. Ao ligar, a diferença entre o saldo do produto e a soma dos
     * lotes vira SALDO_INICIAL: positiva, no "SEM LOTE"; negativa, sai dos lotes como uma saída. Ao desligar, os lotes
     * ficam parados.
     *
     * @return descrição para a auditoria, ou vazio se o controle já estava assim
     */
    java.util.Optional<String> definirControle(Connection con, Sessao sessao, long produtoId, boolean ligar)
            throws SQLException {
        BigDecimal saldo = travarSaldo(con, produtoId);
        boolean atual = produtoDAO.controlaValidade(con, produtoId)
                .orElseThrow(() -> new NegocioException("Produto não encontrado."));
        if (atual == ligar) {
            return java.util.Optional.empty();
        }
        OffsetDateTime agora = OffsetDateTime.now(clock);
        produtoDAO.definirControleValidade(con, produtoId, ligar, sessao.usuarioId(), agora);
        SaldoEstoque produto = buscar(con, produtoId);
        if (!ligar) {
            return java.util.Optional.of("Controle de validade desligado no produto " + produto.descricao()
                    + ": os lotes ficam parados até o controle voltar.");
        }
        List<Lote> lotes = loteDAO.listar(con, produtoId);
        BigDecimal soma = lotes.stream().map(Lote::saldo).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal diferenca = saldo.subtract(soma);
        List<RegraLotes.Parte> partes = diferenca.signum() > 0
                ? List.of(new RegraLotes.Parte(semLoteId(con, produtoId).orElse(null), diferenca))
                : diferenca.signum() < 0 ? RegraLotes.saida(lotes, diferenca.negate()) : List.of();
        for (RegraLotes.Parte p : partes) {
            long loteId = p.loteId() != null ? p.loteId() : criarSemLote(con, sessao, produtoId, agora);
            loteDAO.inserirMovimento(con, loteId, TipoLoteMovimento.SALDO_INICIAL, null, null,
                    diferenca.signum() > 0 ? p.quantidade() : p.quantidade().negate(), sessao.usuarioId(), agora);
        }
        String saldoInicial = diferenca.signum() == 0 ? "os lotes já somavam o saldo do produto"
                : "saldo inicial de " + quantidade(diferenca, produto) + (diferenca.signum() > 0
                ? " no " + Lote.SEM_LOTE : " (saída dos lotes)");
        return java.util.Optional.of("Controle de validade ligado no produto " + produto.descricao() + ": "
                + saldoInicial + ".");
    }

    /**
     * Transferência entre lotes do mesmo produto (ESTOQUE, com motivo): não gera movimento de estoque. A origem precisa
     * ter saldo, inclusive o "SEM LOTE"; o destino pode ser um lote novo (código e validade).
     *
     * @param destino lote de destino e a quantidade transferida
     */
    public void transferirEntreLotes(Sessao sessao, long produtoId, long origemLoteId, ParteLote destino,
                                     String motivo) {
        sessao.exigir(Permissao.ESTOQUE);
        String motivoFinal = Textos.normalizarEspacos(motivo);
        if (motivoFinal.isEmpty()) {
            throw new NegocioException("Informe o motivo da transferência.");
        }
        if (motivoFinal.length() > TAMANHO_MAXIMO_MOTIVO) {
            throw new NegocioException("O motivo pode ter no máximo " + TAMANHO_MAXIMO_MOTIVO + " caracteres.");
        }
        if (destino == null || destino.quantidade() == null || destino.quantidade().signum() <= 0) {
            throw new NegocioException("Informe a quantidade a transferir, maior que zero.");
        }
        transacao.executar(con -> {
            travarSaldo(con, produtoId);
            SaldoEstoque produto = buscar(con, produtoId);
            if (!produtoDAO.controlaValidade(con, produtoId).orElse(false)) {
                throw new NegocioException("O produto " + produto.descricao() + " não controla validade e lotes.");
            }
            BigDecimal quantidade = ValidacaoProduto.quantidade(destino.quantidade(), "A quantidade",
                    produto.unidadeFracionada());
            OffsetDateTime agora = OffsetDateTime.now(clock);
            Lote origem = resolverLote(con, sessao, produtoId, ParteLote.existente(origemLoteId, quantidade), false,
                    agora);
            if (origem.saldo().compareTo(quantidade) < 0) {
                throw new NegocioException("O lote " + origem.descricao() + " tem só " + quantidade(
                        origem.saldo().max(BigDecimal.ZERO), produto) + ": não dá para transferir "
                        + quantidade(quantidade, produto) + ".");
            }
            Lote alvo = resolverLote(con, sessao, produtoId, destino, true, agora);
            if (alvo.id() == origem.id()) {
                throw new NegocioException("Escolha um lote de destino diferente do de origem.");
            }
            long transferencia = loteDAO.inserirTransferencia(con, produtoId, origem.id(), alvo.id(), quantidade,
                    motivoFinal, sessao.usuarioId(), agora);
            loteDAO.inserirMovimento(con, origem.id(), TipoLoteMovimento.TRANSFERENCIA, null, transferencia,
                    quantidade.negate(), sessao.usuarioId(), agora);
            loteDAO.inserirMovimento(con, alvo.id(), TipoLoteMovimento.TRANSFERENCIA, null, transferencia,
                    quantidade, sessao.usuarioId(), agora);
            auditoria.registrar(con, sessao, AcaoAuditoria.LOTE_TRANSFERIDO, AuditoriaService.ENTIDADE_PRODUTO,
                    produtoId, "Produto " + produto.descricao() + ": " + quantidade(quantidade, produto)
                            + " do lote " + origem.descricao() + " para o lote " + alvo.descricao()
                            + ". Motivo: " + motivoFinal);
            return null;
        });
    }

    /** Linhas de {@code vw_lote_inconsistente}. Esperado: nenhuma. Só supervisor (6.2). */
    public List<String> diagnosticoLotes(Sessao sessao) {
        sessao.exigirSupervisor();
        return transacao.executar(loteDAO::inconsistencias);
    }

    // ----- Consulta (6.4, Fig. 17 revisada) -----

    /**
     * Busca por nome (sem acento nem maiúsculas) ou, se só dígitos, por código interno ou código de barras exato.
     *
     * @param soAbaixoDoMinimo só produtos com saldo abaixo do mínimo
     * @param incluirInativos  também os produtos inativos
     */
    public List<SaldoEstoque> pesquisar(Sessao sessao, String texto, boolean soAbaixoDoMinimo,
                                        boolean incluirInativos) {
        return pesquisar(sessao, texto, soAbaixoDoMinimo, incluirInativos, false);
    }

    /**
     * @param soSemLoteNegativo só produtos com o controle de validade ligado e o "SEM LOTE" negativo (F17.3)
     */
    public List<SaldoEstoque> pesquisar(Sessao sessao, String texto, boolean soAbaixoDoMinimo,
                                        boolean incluirInativos, boolean soSemLoteNegativo) {
        sessao.exigir(Permissao.ESTOQUE);
        String termo = Textos.normalizarEspacos(texto);
        EstoqueDAO.Filtro filtro = Textos.soDigitos(termo)
                ? new EstoqueDAO.Filtro(null, termo, soAbaixoDoMinimo, incluirInativos, soSemLoteNegativo)
                : new EstoqueDAO.Filtro(Textos.normalizarBusca(termo), null, soAbaixoDoMinimo, incluirInativos,
                soSemLoteNegativo);
        return transacao.executar(con -> estoqueDAO.pesquisar(con, filtro));
    }

    /** Cabeçalho da consulta: saldo, mínimo, custo médio e valor em estoque. */
    public SaldoEstoque saldo(Sessao sessao, long produtoId) {
        sessao.exigir(Permissao.ESTOQUE);
        return transacao.executar(con -> buscar(con, produtoId));
    }

    /** Últimos {@value #DIAS_PERIODO_PADRAO} dias, contando hoje. */
    public Periodo periodoPadrao() {
        LocalDate hoje = LocalDate.now(clock);
        return new Periodo(hoje.minusDays(DIAS_PERIODO_PADRAO - 1), hoje);
    }

    /**
     * Kardex do produto no período (as duas datas incluídas), do mais antigo para o mais recente, com o saldo
     * anterior ao início (F10.4).
     */
    public Kardex kardex(Sessao sessao, long produtoId, Periodo periodo) {
        sessao.exigir(Permissao.ESTOQUE);
        if (periodo == null || periodo.inicio() == null || periodo.fim() == null) {
            throw new NegocioException("Informe a data inicial e a data final do período.");
        }
        if (periodo.inicio().isAfter(periodo.fim())) {
            throw new NegocioException("A data inicial não pode ser depois da data final.");
        }
        OffsetDateTime inicio = periodo.inicioEm(clock.getZone());
        return transacao.executar(con -> new Kardex(inicio, movimentoDAO.saldoAntes(con, produtoId, inicio),
                movimentoDAO.listar(con, produtoId, inicio, periodo.fimExclusivoEm(clock.getZone()))));
    }

    // ----- Ajustes manuais (6.4) -----

    /**
     * Entrada, saída ou perda. A quantidade é positiva e o sinal vem do tipo; o motivo é obrigatório (na perda,
     * um de {@link MotivoPerda}). Com observação, grava "Motivo: observação".
     */
    public ResultadoAjuste ajustar(Sessao sessao, long produtoId, TipoAjuste tipo, BigDecimal quantidade,
                                   String motivo, String observacao) {
        return ajustar(sessao, produtoId, tipo, quantidade, motivo, observacao, null);
    }

    /**
     * Ajuste com o lote escolhido (F17.2), para produto com controle de validade. Na entrada, o lote existente ou um
     * novo (código e validade); sem escolha, vai para o "SEM LOTE". Na saída e na perda, sai do lote escolhido, sem
     * passar do saldo dele; sem escolha, FEFO. A perda por vencimento exige o lote. Em produto sem controle, o lote é
     * ignorado.
     *
     * @param lote só o lote importa (a quantidade dele é ignorada); nulo = regra do tipo
     */
    public ResultadoAjuste ajustar(Sessao sessao, long produtoId, TipoAjuste tipo, BigDecimal quantidade,
                                   String motivo, String observacao, ParteLote lote) {
        sessao.exigir(Permissao.ESTOQUE);
        if (tipo == null) {
            throw new NegocioException("Escolha o tipo do ajuste: entrada, saída ou perda.");
        }
        if (quantidade == null) {
            throw new NegocioException("Informe a quantidade.");
        }
        if (quantidade.signum() <= 0) {
            throw new NegocioException("A quantidade deve ser maior que zero.");
        }
        String motivoFinal = comporMotivo(tipo, motivo, observacao);
        BigDecimal comSinal = tipo == TipoAjuste.ENTRADA ? quantidade : quantidade.negate();
        return transacao.executar(con -> {
            SaldoEstoque produto = buscar(con, produtoId);
            BigDecimal anterior = travarSaldo(con, produtoId);
            boolean controla = produtoDAO.controlaValidade(con, produtoId).orElse(false);
            List<ParteLote> lotes = null;
            String noLote = "";
            if (controla) {
                if (lote == null && tipo == TipoAjuste.PERDA && motivoFinal.startsWith(MotivoPerda.VENCIMENTO.rotulo())) {
                    throw new NegocioException("Na perda por vencimento, escolha o lote que venceu.");
                }
                if (lote != null) {
                    ParteLote escolha = lote;
                    if (comSinal.signum() < 0) {
                        Lote escolhido = resolverLote(con, sessao, produtoId, lote, false, OffsetDateTime.now(clock));
                        if (escolhido.saldo().compareTo(quantidade) < 0) {
                            throw new NegocioException("O lote " + escolhido.descricao() + " tem só "
                                    + quantidade(escolhido.saldo().max(BigDecimal.ZERO), produto) + ".");
                        }
                        escolha = ParteLote.existente(escolhido.id(), quantidade);
                    }
                    lotes = List.of(escolha.comQuantidade(quantidade));
                    noLote = escolha.loteId() != null
                            ? " (lote " + loteDAO.buscar(con, escolha.loteId()).map(Lote::descricao).orElse("") + ")"
                            : " (lote " + Lote.descrever(ParteLote.normalizarCodigo(escolha.codigo()),
                            escolha.validade()) + ")";
                }
            }
            long id = movimentar(con, sessao, produtoId, tipo.tipoMovimento(), comSinal, OrigemMovimento.NENHUMA,
                    motivoFinal, null, lotes);
            ResultadoAjuste resultado = new ResultadoAjuste(id, tipo.tipoMovimento(), comSinal, anterior,
                    anterior.add(comSinal));
            auditoria.registrar(con, sessao, AcaoAuditoria.ESTOQUE_AJUSTADO, AuditoriaService.ENTIDADE_PRODUTO,
                    produtoId, "Produto " + produto.descricao() + ": " + descrever(resultado, produto) + noLote
                            + ". Motivo: " + motivoFinal);
            return resultado;
        });
    }

    /**
     * "Ajustar saldo para…": calcula a diferença para o saldo atual (lido com a linha travada) e gera uma entrada
     * ou uma saída de ajuste.
     *
     * @throws NegocioException se o saldo já for o informado
     */
    public ResultadoAjuste ajustarSaldoPara(Sessao sessao, long produtoId, BigDecimal novoSaldo, String motivo) {
        sessao.exigir(Permissao.ESTOQUE);
        if (novoSaldo == null) {
            throw new NegocioException("Informe o novo saldo.");
        }
        String motivoFinal = comporMotivo(TipoAjuste.ENTRADA, motivo, null);
        return transacao.executar(con -> {
            SaldoEstoque produto = buscar(con, produtoId);
            BigDecimal alvo = ValidacaoProduto.quantidade(novoSaldo, "O novo saldo", produto.unidadeFracionada());
            BigDecimal atual = travarSaldo(con, produtoId);
            BigDecimal diferenca = alvo.subtract(atual);
            if (diferenca.signum() == 0) {
                throw new NegocioException("O saldo já é " + quantidade(atual, produto) + ": não há o que ajustar.");
            }
            TipoMovimento tipo = diferenca.signum() > 0 ? TipoMovimento.AJUSTE_ENTRADA : TipoMovimento.AJUSTE_SAIDA;
            ResultadoAjuste resultado = registrar(con, sessao, produtoId, tipo, diferenca, motivoFinal);
            auditoria.registrar(con, sessao, AcaoAuditoria.ESTOQUE_AJUSTADO, AuditoriaService.ENTIDADE_PRODUTO,
                    produtoId, "Produto " + produto.descricao() + ": saldo ajustado para "
                            + quantidade(alvo, produto) + " — " + descrever(resultado, produto)
                            + ". Motivo: " + motivoFinal);
            return resultado;
        });
    }

    // ----- Alertas e diagnóstico -----

    /** Produtos ativos com saldo abaixo do mínimo (alerta do painel). */
    public int contarAbaixoDoMinimo(Sessao sessao) {
        sessao.exigir(Permissao.ESTOQUE);
        return transacao.executar(estoqueDAO::contarAbaixoDoMinimo);
    }

    /** Produtos cujo saldo difere da soma dos movimentos. Esperado: nenhum. Só supervisor (6.2). */
    public List<InconsistenciaEstoque> diagnostico(Sessao sessao) {
        sessao.exigirSupervisor();
        return transacao.executar(estoqueDAO::inconsistencias);
    }

    /**
     * Motivo gravado no movimento: "Motivo" ou "Motivo: observação". Na perda o motivo vem da lista e "Outros"
     * exige a observação.
     */
    static String comporMotivo(TipoAjuste tipo, String motivo, String observacao) {
        String texto = Textos.normalizarEspacos(motivo);
        String obs = Textos.normalizarEspacos(observacao);
        if (texto.isEmpty()) {
            throw new NegocioException("Informe o motivo do ajuste.");
        }
        if (tipo.motivoDaLista()) {
            MotivoPerda perda = MotivoPerda.doRotulo(texto).orElseThrow(() -> new NegocioException(
                    "Escolha o motivo da perda: " + Arrays.stream(MotivoPerda.values()).map(MotivoPerda::rotulo)
                            .collect(Collectors.joining(", ")) + "."));
            if (perda == MotivoPerda.OUTROS && obs.isEmpty()) {
                throw new NegocioException("Com o motivo \"Outros\", descreva a perda na observação.");
            }
            texto = perda.rotulo();
        }
        String completo = obs.isEmpty() ? texto : texto + ": " + obs;
        if (completo.length() > TAMANHO_MAXIMO_MOTIVO) {
            throw new NegocioException("O motivo e a observação podem ter juntos no máximo " + TAMANHO_MAXIMO_MOTIVO
                    + " caracteres.");
        }
        return completo;
    }

    private SaldoEstoque buscar(Connection con, long produtoId) throws SQLException {
        return estoqueDAO.buscar(con, produtoId).orElseThrow(() -> new NegocioException("Produto não encontrado."));
    }

    BigDecimal travarSaldo(Connection con, long produtoId) throws SQLException {
        return estoqueDAO.travarSaldo(con, produtoId)
                .orElseThrow(() -> new NegocioException("Produto não encontrado."));
    }

    /** Trava o saldo, registra o movimento e devolve o saldo antes e depois. */
    private ResultadoAjuste registrar(Connection con, Sessao sessao, long produtoId, TipoMovimento tipo,
                                      BigDecimal comSinal, String motivo) throws SQLException {
        BigDecimal anterior = travarSaldo(con, produtoId);
        long id = movimentar(con, sessao, produtoId, tipo, comSinal, OrigemMovimento.NENHUMA, motivo, null);
        return new ResultadoAjuste(id, tipo, comSinal, anterior, anterior.add(comSinal));
    }

    /** "saída de 3 UN, saldo de 10 UN para 7 UN". */
    private static String descrever(ResultadoAjuste r, SaldoEstoque produto) {
        String acao = switch (r.tipo()) {
            case AJUSTE_ENTRADA -> "entrada";
            case AJUSTE_SAIDA -> "saída";
            case PERDA -> "perda";
            default -> r.tipo().descricao().toLowerCase(Formatos.PT_BR);
        };
        return acao + " de " + quantidade(r.quantidade().abs(), produto) + ", saldo de "
                + quantidade(r.saldoAnterior(), produto) + " para " + quantidade(r.saldoPosterior(), produto);
    }

    private static String quantidade(BigDecimal valor, SaldoEstoque produto) {
        return Formatos.quantidade(valor, produto.unidadeFracionada()) + " " + produto.unidade();
    }
}
