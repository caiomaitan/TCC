package br.com.varesync.service;

import br.com.varesync.dao.EstoqueDAO;
import br.com.varesync.dao.MovimentoEstoqueDAO;
import br.com.varesync.dao.ProdutoDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.InconsistenciaEstoque;
import br.com.varesync.model.MotivoPerda;
import br.com.varesync.model.MovimentoKardex;
import br.com.varesync.model.OrigemMovimento;
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

    private final Transacao transacao;
    private final ProdutoDAO produtoDAO;
    private final EstoqueDAO estoqueDAO;
    private final MovimentoEstoqueDAO movimentoDAO;
    private final AuditoriaService auditoria;
    private final Clock clock;

    public EstoqueService(Transacao transacao, ProdutoDAO produtoDAO, EstoqueDAO estoqueDAO,
                          MovimentoEstoqueDAO movimentoDAO, AuditoriaService auditoria, Clock clock) {
        this.transacao = transacao;
        this.produtoDAO = produtoDAO;
        this.estoqueDAO = estoqueDAO;
        this.movimentoDAO = movimentoDAO;
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

        return movimentoDAO.inserir(con, produtoId, tipo, quantidadeComSinal, custoOpcional,
                origem.vendaItemId(), origem.inventarioItemId(), origem.entradaItemId(), motivoNormalizado,
                sessao.usuarioId(), OffsetDateTime.now(clock));
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
        sessao.exigir(Permissao.ESTOQUE);
        String termo = Textos.normalizarEspacos(texto);
        EstoqueDAO.Filtro filtro = Textos.soDigitos(termo)
                ? new EstoqueDAO.Filtro(null, termo, soAbaixoDoMinimo, incluirInativos)
                : new EstoqueDAO.Filtro(Textos.normalizarBusca(termo), null, soAbaixoDoMinimo, incluirInativos);
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

    /** Kardex do produto no período (as duas datas incluídas), do mais antigo para o mais recente. */
    public List<MovimentoKardex> kardex(Sessao sessao, long produtoId, Periodo periodo) {
        sessao.exigir(Permissao.ESTOQUE);
        if (periodo == null || periodo.inicio() == null || periodo.fim() == null) {
            throw new NegocioException("Informe a data inicial e a data final do período.");
        }
        if (periodo.inicio().isAfter(periodo.fim())) {
            throw new NegocioException("A data inicial não pode ser depois da data final.");
        }
        return transacao.executar(con -> movimentoDAO.listar(con, produtoId, periodo.inicioEm(clock.getZone()),
                periodo.fimExclusivoEm(clock.getZone())));
    }

    // ----- Ajustes manuais (6.4) -----

    /**
     * Entrada, saída ou perda. A quantidade é positiva e o sinal vem do tipo; o motivo é obrigatório (na perda,
     * um de {@link MotivoPerda}). Com observação, grava "Motivo: observação".
     */
    public ResultadoAjuste ajustar(Sessao sessao, long produtoId, TipoAjuste tipo, BigDecimal quantidade,
                                   String motivo, String observacao) {
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
            ResultadoAjuste resultado = registrar(con, sessao, produtoId, tipo.tipoMovimento(), comSinal,
                    motivoFinal);
            auditoria.registrar(con, sessao, AcaoAuditoria.ESTOQUE_AJUSTADO, AuditoriaService.ENTIDADE_PRODUTO,
                    produtoId, "Produto " + produto.descricao() + ": " + descrever(resultado, produto)
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

    private BigDecimal travarSaldo(Connection con, long produtoId) throws SQLException {
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
