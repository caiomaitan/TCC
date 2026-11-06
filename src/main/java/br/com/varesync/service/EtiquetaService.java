package br.com.varesync.service;

import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.EtiquetaDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.Etiqueta;
import br.com.varesync.model.OrigemEtiqueta;
import br.com.varesync.model.Permissao;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Etiquetas de gôndola (F18.1, permissão PRODUTOS): seleção dos produtos (pendentes, preço alterado desde uma data,
 * produtos de uma entrada, categoria ou lista manual) com o preço vigente hoje, e o registro de cada impressão
 * (PDF, impressora térmica ou "Marcar como em dia"), com auditoria na mesma transação.
 *
 * <p>Quem gera o PDF ou manda para a térmica registra depois o que foi impresso ({@link #registrarImpressao}): a
 * etiqueta guarda o preço e a promoção que realmente foram para a gôndola.
 */
public class EtiquetaService {

    public static final String ENTIDADE = "etiqueta";
    /** Etiquetas de um produto numa impressão. */
    public static final int QUANTIDADE_MAXIMA = 999;

    private final Transacao transacao;
    private final EtiquetaDAO dao;
    private final ConfiguracaoDAO configuracaoDAO;
    private final AuditoriaService auditoria;
    private final Clock clock;

    public EtiquetaService(Transacao transacao, EtiquetaDAO dao, ConfiguracaoDAO configuracaoDAO,
                           AuditoriaService auditoria, Clock clock) {
        this.transacao = transacao;
        this.dao = dao;
        this.configuracaoDAO = configuracaoDAO;
        this.auditoria = auditoria;
        this.clock = clock;
    }

    /** Hoje pelo relógio do sistema (a data das etiquetas). */
    public LocalDate hoje() {
        return LocalDate.now(clock);
    }

    /** Produtos ativos com estoque cujo preço ou promoção vigente difere da última etiqueta (ou que nunca tiveram). */
    public List<Etiqueta> pendentes(Sessao sessao) {
        sessao.exigir(Permissao.PRODUTOS);
        return transacao.executar(con -> dao.pendentes(con, hoje()));
    }

    public int contarPendentes(Sessao sessao) {
        sessao.exigir(Permissao.PRODUTOS);
        return transacao.executar(con -> dao.contarPendentes(con, hoje()));
    }

    /** Preço de venda alterado a partir do começo do dia {@code desde} (no fuso do sistema), pelo histórico. */
    public List<Etiqueta> alteradosDesde(Sessao sessao, LocalDate desde) {
        sessao.exigir(Permissao.PRODUTOS);
        if (desde == null) {
            throw new NegocioException("Informe a data a partir da qual o preço foi alterado.");
        }
        if (desde.isAfter(hoje())) {
            throw new NegocioException("A data não pode ser depois de hoje.");
        }
        OffsetDateTime inicio = desde.atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
        return transacao.executar(con -> dao.alteradosDesde(con, hoje(), inicio));
    }

    public List<Etiqueta> daEntrada(Sessao sessao, long entradaId) {
        sessao.exigir(Permissao.PRODUTOS);
        return transacao.executar(con -> {
            if (!dao.entradaExiste(con, entradaId)) {
                throw new NegocioException("A entrada nº " + entradaId + " não foi encontrada.");
            }
            return dao.daEntrada(con, hoje(), entradaId);
        });
    }

    public List<Etiqueta> daCategoria(Sessao sessao, long categoriaId) {
        sessao.exigir(Permissao.PRODUTOS);
        return transacao.executar(con -> dao.daCategoria(con, hoje(), categoriaId));
    }

    /** Lista manual: o produto ativo, com o preço de hoje. */
    public Etiqueta produto(Sessao sessao, long produtoId) {
        sessao.exigir(Permissao.PRODUTOS);
        List<Etiqueta> uma = transacao.executar(con -> dao.produtos(con, hoje(), List.of(produtoId)));
        if (uma.isEmpty()) {
            throw new NegocioException("Produto " + produtoId + " não encontrado ou inativo: produto inativo não "
                    + "tem etiqueta.");
        }
        return uma.get(0);
    }

    /** Recarrega estes produtos com o preço de hoje (os inativos saem), na ordem do nome. */
    public List<Etiqueta> produtos(Sessao sessao, Collection<Long> ids) {
        sessao.exigir(Permissao.PRODUTOS);
        return transacao.executar(con -> dao.produtos(con, hoje(), ids));
    }

    /** Colunas, linhas, modelo e impressora térmica (Configurações › Etiquetas). */
    public Configuracoes.Etiquetas configuracao(Sessao sessao) {
        sessao.exigir(Permissao.PRODUTOS);
        return transacao.executar(con -> configuracaoDAO.carregar(con).etiquetas());
    }

    /**
     * Registra o que foi impresso: uma linha por produto, com o preço e a promoção da etiqueta.
     *
     * @param origem PDF ou TERMICA
     */
    public void registrarImpressao(Sessao sessao, List<Etiqueta> impressas, OrigemEtiqueta origem) {
        sessao.exigir(Permissao.PRODUTOS);
        if (origem == OrigemEtiqueta.EM_DIA) {
            throw new IllegalArgumentException("Use marcarEmDia");
        }
        conferir(impressas);
        int total = impressas.stream().mapToInt(Etiqueta::quantidade).sum();
        transacao.executar(con -> {
            OffsetDateTime agora = OffsetDateTime.now(clock);
            for (Etiqueta e : impressas) {
                dao.registrar(con, e.produtoId(), e.preco(), e.promocaoId(), origem, sessao.usuarioId(), agora);
            }
            auditoria.registrar(con, sessao, AcaoAuditoria.ETIQUETAS_IMPRESSAS, ENTIDADE, null,
                    "Etiquetas impressas (" + origem.rotulo() + "): " + produtos(impressas.size()) + ", "
                            + (total == 1 ? "1 etiqueta" : total + " etiquetas"));
            return null;
        });
    }

    /**
     * "Marcar como em dia" (F18.1): registra a etiqueta com o preço vigente hoje, sem imprimir. Serve para começar a
     * usar a função com as gôndolas já etiquetadas.
     *
     * @return quantos produtos foram marcados (os inativos ficam de fora)
     */
    public int marcarEmDia(Sessao sessao, Collection<Long> produtoIds) {
        sessao.exigir(Permissao.PRODUTOS);
        Set<Long> ids = new LinkedHashSet<>(produtoIds);
        if (ids.isEmpty()) {
            throw new NegocioException("Não há produtos na lista para marcar como em dia.");
        }
        return transacao.executar(con -> {
            List<Etiqueta> atuais = dao.produtos(con, hoje(), ids);
            OffsetDateTime agora = OffsetDateTime.now(clock);
            for (Etiqueta e : atuais) {
                dao.registrar(con, e.produtoId(), e.preco(), e.promocaoId(), OrigemEtiqueta.EM_DIA, sessao.usuarioId(),
                        agora);
            }
            if (!atuais.isEmpty()) {
                auditoria.registrar(con, sessao, AcaoAuditoria.ETIQUETAS_EM_DIA, ENTIDADE, null,
                        "Etiquetas marcadas como em dia, sem imprimir: " + produtos(atuais.size()));
            }
            return atuais.size();
        });
    }

    /** Lista não vazia e quantidade de 1 a {@value #QUANTIDADE_MAXIMA} em cada produto. */
    public static void conferir(List<Etiqueta> etiquetas) {
        if (etiquetas.isEmpty()) {
            throw new NegocioException("Não há produtos na lista de etiquetas.");
        }
        for (Etiqueta e : etiquetas) {
            if (e.quantidade() < 1 || e.quantidade() > QUANTIDADE_MAXIMA) {
                throw new NegocioException("A quantidade de etiquetas de \"" + e.nome() + "\" deve estar entre 1 e "
                        + QUANTIDADE_MAXIMA + ".");
            }
        }
    }

    private static String produtos(int n) {
        return n == 1 ? "1 produto" : n + " produtos";
    }
}
