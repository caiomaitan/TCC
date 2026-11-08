package br.com.varesync.service;

import br.com.varesync.dao.CargaBalancaDAO;
import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.ItemCargaBalanca;
import br.com.varesync.model.ItemCargaBalanca.Situacao;
import br.com.varesync.model.Permissao;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Carga de balança (F18.2, permissão PRODUTOS): o arquivo de itens da balança Toledo ({@code ITENSMGV.TXT}, formato
 * em {@link LayoutMgv}) com os pesáveis ativos vendidos em KG que têm PLU, pelo preço por kg vigente hoje (com
 * promoção), gravado em {@code balanca.pasta_carga}; e o registro de cada carga, para saber o que está pendente.
 *
 * <p>Pendente: pesável que nunca foi carregado, ou cujo PLU, preço vigente, descrição ou dias de validade diferem do
 * último item carregado. A carga leva sempre todos os pesáveis (a balança recebe a lista inteira).
 */
public class CargaBalancaService {

    public static final String ENTIDADE = "carga_balanca";

    private final Transacao transacao;
    private final CargaBalancaDAO dao;
    private final ConfiguracaoDAO configuracaoDAO;
    private final AuditoriaService auditoria;
    private final Clock clock;

    public CargaBalancaService(Transacao transacao, CargaBalancaDAO dao, ConfiguracaoDAO configuracaoDAO,
                               AuditoriaService auditoria, Clock clock) {
        this.transacao = transacao;
        this.dao = dao;
        this.configuracaoDAO = configuracaoDAO;
        this.auditoria = auditoria;
        this.clock = clock;
    }

    /** Resultado da carga gerada. */
    public record Carga(long id, Path arquivo, int itens, int pendentes) {
    }

    public LocalDate hoje() {
        return LocalDate.now(clock);
    }

    /** Todos os pesáveis que vão para a balança, na ordem do PLU, com a situação de cada um. */
    public List<ItemCargaBalanca> itens(Sessao sessao) {
        sessao.exigir(Permissao.PRODUTOS);
        return transacao.executar(con -> itens(con));
    }

    public List<ItemCargaBalanca> pendentes(Sessao sessao) {
        return itens(sessao).stream().filter(ItemCargaBalanca::pendente).toList();
    }

    public int contarPendentes(Sessao sessao) {
        return pendentes(sessao).size();
    }

    /** Na transação de quem chama (painel); quem chama confere a permissão. */
    int contarPendentes(Connection con) throws SQLException {
        return (int) itens(con).stream().filter(ItemCargaBalanca::pendente).count();
    }

    /** A pasta configurada em Configurações › Balança (vazia = não configurada). */
    public String pasta(Sessao sessao) {
        sessao.exigir(Permissao.PRODUTOS);
        return transacao.executar(con -> configuracaoDAO.carregar(con).balanca().pastaCarga());
    }

    /** Quando foi a última carga; nula se nenhuma. */
    public OffsetDateTime ultimaCarga(Sessao sessao) {
        sessao.exigir(Permissao.PRODUTOS);
        return transacao.executar(dao::ultimaCarga);
    }

    /**
     * Grava o {@code ITENSMGV.TXT} na pasta configurada (substituindo o anterior) e registra a carga, com auditoria.
     * Se a gravação do arquivo falha, nada é registrado.
     */
    public Carga gerar(Sessao sessao) {
        sessao.exigir(Permissao.PRODUTOS);
        return transacao.executar(con -> {
            Path pasta = pasta(configuracaoDAO.carregar(con).balanca().pastaCarga());
            List<ItemCargaBalanca> itens = itensParaCarga(con);
            StringBuilder conteudo = new StringBuilder();
            for (ItemCargaBalanca item : itens) {
                conteudo.append(LayoutMgv.linha(item.nome(), item.plu(), item.preco(), item.validadeDias()))
                        .append(LayoutMgv.FIM_DE_LINHA);
            }
            Path arquivo = pasta.resolve(LayoutMgv.ARQUIVO);
            int pendentes = (int) itens.stream().filter(ItemCargaBalanca::pendente).count();
            long id = registrar(con, sessao, itens, arquivo.toString(), pendentes);
            gravar(pasta, arquivo, conteudo.toString());
            return new Carga(id, arquivo, itens.size(), pendentes);
        });
    }

    /**
     * Registra a carga sem gravar arquivo (gerador de dados: o painel começa com a balança em dia).
     *
     * @return quantos produtos foram registrados (0 = nenhum pesável, nada registrado)
     */
    public int registrarSemArquivo(Sessao sessao) {
        sessao.exigir(Permissao.PRODUTOS);
        return transacao.executar(con -> {
            List<ItemCargaBalanca> itens = itens(con);
            if (itens.isEmpty()) {
                return 0;
            }
            for (ItemCargaBalanca item : itens) {
                LayoutMgv.linha(item.nome(), item.plu(), item.preco(), item.validadeDias());
            }
            int pendentes = (int) itens.stream().filter(ItemCargaBalanca::pendente).count();
            registrar(con, sessao, itens, null, pendentes);
            return itens.size();
        });
    }

    private List<ItemCargaBalanca> itensParaCarga(Connection con) throws SQLException {
        List<ItemCargaBalanca> itens = itens(con);
        if (itens.isEmpty()) {
            throw new NegocioException("Não há produtos para a balança: a carga leva os produtos ativos vendidos em "
                    + "KG que têm PLU e preço.");
        }
        return itens;
    }

    private long registrar(Connection con, Sessao sessao, List<ItemCargaBalanca> itens, String arquivo, int pendentes)
            throws SQLException {
        List<CargaBalancaDAO.Item> gravados = new ArrayList<>(itens.size());
        for (ItemCargaBalanca item : itens) {
            gravados.add(new CargaBalancaDAO.Item(item.produtoId(), item.plu(), item.preco(), item.descricao(),
                    item.validadeDias()));
        }
        long id = dao.registrar(con, arquivo, gravados, sessao.usuarioId(), OffsetDateTime.now(clock));
        String onde = arquivo == null ? ", sem arquivo (gerador de dados)" : ", em " + arquivo;
        auditoria.registrar(con, sessao, AcaoAuditoria.CARGA_BALANCA_GERADA, ENTIDADE, id,
                "Carga de balança gerada: " + produtos(itens.size()) + " (" + pendentes
                        + (pendentes == 1 ? " pendente" : " pendentes") + ")" + onde);
        return id;
    }

    private List<ItemCargaBalanca> itens(Connection con) throws SQLException {
        List<ItemCargaBalanca> itens = new ArrayList<>();
        for (CargaBalancaDAO.Linha l : dao.pesaveis(con, hoje())) {
            String descricao = LayoutMgv.descricao(l.nome()).juntas();
            itens.add(new ItemCargaBalanca(l.produtoId(), l.nome(), l.plu(), l.preco(), l.emPromocao(),
                    l.validadeDias(), descricao, l.ultimaCargaEm(), situacao(l, descricao)));
        }
        return itens;
    }

    static Situacao situacao(CargaBalancaDAO.Linha l, String descricao) {
        if (l.ultimaCargaEm() == null) {
            return Situacao.NUNCA_CARREGADO;
        }
        if (!Objects.equals(l.ultimoPlu(), l.plu())) {
            return Situacao.PLU_ALTERADO;
        }
        if (l.ultimoPreco().compareTo(l.preco()) != 0) {
            return Situacao.PRECO_ALTERADO;
        }
        if (!descricao.equals(l.ultimaDescricao())) {
            return Situacao.DESCRICAO_ALTERADA;
        }
        if (!Objects.equals(l.ultimaValidade(), l.validadeDias())) {
            return Situacao.VALIDADE_ALTERADA;
        }
        return Situacao.EM_DIA;
    }

    private static Path pasta(String configurada) {
        if (configurada == null || configurada.isBlank()) {
            throw new NegocioException("A pasta da carga de balança não está configurada. Configure em "
                    + "Configurações › Balança (a pasta de onde o MGV importa os itens).");
        }
        Path pasta;
        try {
            pasta = Path.of(configurada);
        } catch (InvalidPathException e) {
            throw new NegocioException("A pasta da carga de balança não é um caminho válido: " + configurada + ".");
        }
        if (!Files.isDirectory(pasta)) {
            throw new NegocioException("A pasta da carga de balança não existe: " + configurada + ". Confira em "
                    + "Configurações › Balança.");
        }
        return pasta;
    }

    /** Grava num temporário da mesma pasta e troca pelo arquivo final, para o MGV nunca ler um arquivo pela metade. */
    private static void gravar(Path pasta, Path arquivo, String conteudo) {
        Path temporario = null;
        try {
            temporario = Files.createTempFile(pasta, "ITENSMGV", ".tmp");
            Files.writeString(temporario, conteudo, StandardCharsets.US_ASCII);
            try {
                Files.move(temporario, arquivo, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporario, arquivo, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            if (temporario != null) {
                try {
                    Files.deleteIfExists(temporario);
                } catch (IOException ignorada) {
                    // o temporário fica para trás; a carga não é registrada
                }
            }
            throw new NegocioException("Não foi possível gravar o arquivo da balança em " + arquivo + ": "
                    + e.getMessage() + ". A carga não foi registrada.");
        }
    }

    private static String produtos(int n) {
        return n == 1 ? "1 produto" : n + " produtos";
    }
}
