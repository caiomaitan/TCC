package br.com.varesync.service;

import br.com.varesync.dao.EntradaDAO;
import br.com.varesync.dao.FornecedorDAO;
import br.com.varesync.dao.ProdutoDAO;
import br.com.varesync.model.DadosEntrada;
import br.com.varesync.model.Fornecedor;
import br.com.varesync.model.ParcelaEntrada;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.ProdutoVenda;
import br.com.varesync.util.CpfCnpj;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Importação do XML da NF-e na entrada de mercadoria (6.11, segunda parte), com a permissão COMPRAS. A prévia mostra
 * o emitente (fornecedor já cadastrado pelo CNPJ ou a cadastrar), a nota e os itens vinculados aos produtos pelo
 * código de barras; o usuário escolhe ou cadastra o produto dos itens sem vínculo. {@link #gerarEntrada} grava, numa
 * única transação, o fornecedor (se faltar) e uma entrada {@code ABERTA} com os itens, para conferência e confirmação
 * na tela Compras.
 */
public class ImportacaoNfeService {

    static final int TAMANHO_RAZAO = 120;
    static final int TAMANHO_CIDADE = 60;

    private final Transacao transacao;
    private final FornecedorDAO fornecedorDAO;
    private final EntradaDAO entradaDAO;
    private final ProdutoDAO produtoDAO;
    private final EntradaService entradaService;
    private final Clock clock;

    /** Item da nota e o produto vinculado pelo código de barras (nulo sem vínculo). */
    public record ItemPrevia(LeitorNfe.Item item, ProdutoVenda produto) {
    }

    /**
     * @param fornecedor       fornecedor com o CNPJ/CPF do emitente; nulo = será cadastrado
     * @param entradaExistente entrada já lançada com a mesma nota desse fornecedor (a geração será recusada)
     */
    public record Previa(LeitorNfe.Nota nota, Fornecedor fornecedor, List<ItemPrevia> itens, Long entradaExistente) {

        public long semVinculo() {
            return itens.stream().filter(i -> i.produto() == null).count();
        }
    }

    public ImportacaoNfeService(Transacao transacao, FornecedorDAO fornecedorDAO, EntradaDAO entradaDAO,
                                ProdutoDAO produtoDAO, EntradaService entradaService, Clock clock) {
        this.transacao = transacao;
        this.fornecedorDAO = fornecedorDAO;
        this.entradaDAO = entradaDAO;
        this.produtoDAO = produtoDAO;
        this.entradaService = entradaService;
        this.clock = clock;
    }

    /** Lê o XML e vincula o que der, sem gravar nada. */
    public Previa ler(Sessao sessao, byte[] xml) {
        sessao.exigir(Permissao.COMPRAS);
        LeitorNfe.Nota nota = LeitorNfe.ler(xml);
        String documento = documento(nota.emitente());
        return transacao.executar(con -> {
            Optional<Fornecedor> fornecedor = fornecedor(con, documento);
            Long existente = null;
            if (fornecedor.isPresent() && !Textos.vazio(nota.numero())) {
                existente = entradaDAO.mesmaNota(con, fornecedor.get().id(), nota.numero().strip(), null).orElse(null);
            }
            List<ItemPrevia> itens = new ArrayList<>();
            for (LeitorNfe.Item item : nota.itens()) {
                ProdutoVenda produto = item.codigoBarras() == null ? null
                        : produtoDAO.buscarParaVendaPorCodigo(con, item.codigoBarras()).orElse(null);
                itens.add(new ItemPrevia(item, produto));
            }
            return new Previa(nota, fornecedor.orElse(null), List.copyOf(itens), existente);
        });
    }

    /**
     * Gera a entrada {@code ABERTA}. O mesmo produto em vários itens da nota vira uma linha só (quantidades e valores
     * somados): a entrada aceita um produto por linha (Fase 7). O custo de cada linha é valor ÷ quantidade, com 4
     * casas.
     *
     * @param escolhidos número do item ({@code nItem}) → produto escolhido ou cadastrado; os demais usam o vínculo
     *                   pelo código de barras
     * @return número da entrada gerada
     * @throws NegocioException item sem produto, nota já lançada, quantidade inválida para a unidade do produto…
     */
    public long gerarEntrada(Sessao sessao, byte[] xml, Map<Integer, Long> escolhidos) {
        sessao.exigir(Permissao.COMPRAS);
        LeitorNfe.Nota nota = LeitorNfe.ler(xml);
        String documento = documento(nota.emitente());
        return transacao.executar(con -> {
            record Linha(long produtoId, BigDecimal quantidade, BigDecimal valor, String itens) {
            }
            Map<Long, Linha> linhas = new LinkedHashMap<>();
            for (LeitorNfe.Item item : nota.itens()) {
                Long produtoId = escolhidos.get(item.numero());
                if (produtoId == null && item.codigoBarras() != null) {
                    produtoId = produtoDAO.buscarParaVendaPorCodigo(con, item.codigoBarras())
                            .map(ProdutoVenda::id).orElse(null);
                }
                if (produtoId == null) {
                    throw new NegocioException("O item " + item.numero() + " da nota (\"" + item.descricao()
                            + "\") não tem produto: escolha ou cadastre o produto antes de gerar a entrada.");
                }
                Linha anterior = linhas.get(produtoId);
                linhas.put(produtoId, anterior == null
                        ? new Linha(produtoId, item.quantidade(), item.valorTotal(), String.valueOf(item.numero()))
                        : new Linha(produtoId, anterior.quantidade().add(item.quantidade()),
                        anterior.valor().add(item.valorTotal()), anterior.itens() + ", " + item.numero()));
            }

            Fornecedor fornecedor = fornecedor(con, documento).orElse(null);
            long fornecedorId = fornecedor != null ? fornecedor.id() : cadastrar(con, nota);
            long entradaId = entradaService.criar(con, sessao, new DadosEntrada(fornecedorId,
                    nota.numero() == null ? "" : nota.numero(), nota.emissao(), nota.valorTotal()));
            for (Linha linha : linhas.values()) {
                try {
                    entradaService.adicionarItem(con, entradaId, linha.produtoId(), linha.quantidade(), null,
                            linha.valor());
                } catch (NegocioException e) {
                    throw new NegocioException("Item " + linha.itens() + " da nota: " + e.getMessage(), e);
                }
            }
            // As duplicatas do XML já vêm como parcelas (F15.3), numeradas na ordem da nota.
            entradaService.salvarParcelas(con, entradaId, nota.duplicatas().stream()
                    .map(d -> new ParcelaEntrada(0, d.vencimento(), d.valor())).toList());
            return entradaId;
        });
    }

    /** CNPJ/CPF do emitente sem máscara, validado. */
    private static String documento(LeitorNfe.Emitente emitente) {
        String documento = CpfCnpj.normalizar(emitente.documento());
        CpfCnpj.validar(documento).ifPresent(erro -> {
            throw new NegocioException("O CNPJ/CPF do emitente no XML é inválido: " + erro);
        });
        return documento;
    }

    private Optional<Fornecedor> fornecedor(Connection con, String documento) throws SQLException {
        Optional<FornecedorDAO.Dono> dono = fornecedorDAO.donoDoDocumento(con, documento, null);
        return dono.isEmpty() ? Optional.empty() : fornecedorDAO.buscar(con, dono.get().id());
    }

    /** Cadastra o emitente como fornecedor, com o que o XML traz (telefone e UF só se válidos). */
    private long cadastrar(Connection con, LeitorNfe.Nota nota) throws SQLException {
        LeitorNfe.Emitente e = nota.emitente();
        String razao = corte(Textos.normalizarEspacos(e.razaoSocial()), TAMANHO_RAZAO);
        if (razao.isEmpty()) {
            throw new NegocioException("O XML da NF-e não traz a razão social do emitente.");
        }
        String fantasia = corte(Textos.normalizarEspacos(e.nomeFantasia()), TAMANHO_RAZAO);
        String telefone = Textos.soDigitos(e.telefone()) && (e.telefone().length() == 10 || e.telefone().length() == 11)
                ? e.telefone() : null;
        String uf = ValidacaoFornecedor.UFS.contains(e.uf().toUpperCase()) ? e.uf().toUpperCase() : null;
        String cidade = corte(Textos.normalizarEspacos(e.cidade()), TAMANHO_CIDADE);
        return fornecedorDAO.inserir(con, new FornecedorDAO.Gravacao(razao, fantasia.isEmpty() ? null : fantasia,
                documento(e), telefone, null, null, cidade.isEmpty() ? null : cidade, uf,
                "Cadastrado pela importação do XML da NF-e nº " + nota.numero()), OffsetDateTime.now(clock));
    }

    private static String corte(String texto, int tamanho) {
        return texto.length() > tamanho ? texto.substring(0, tamanho).strip() : texto;
    }
}
