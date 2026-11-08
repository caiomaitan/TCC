package br.com.varesync.service;

import br.com.varesync.dao.EntradaDAO;
import br.com.varesync.dao.FornecedorDAO;
import br.com.varesync.dao.ProdutoDAO;
import br.com.varesync.dao.ProdutoFornecedorDAO;
import br.com.varesync.model.DadosEntrada;
import br.com.varesync.model.Fornecedor;
import br.com.varesync.model.LoteEntrada;
import br.com.varesync.model.ParcelaEntrada;
import br.com.varesync.model.ParteLote;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.ProdutoFornecedor;
import br.com.varesync.model.ProdutoVenda;
import br.com.varesync.util.CpfCnpj;
import br.com.varesync.util.Dinheiro;
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
 * código de barras ou, sem ele, pelo código do produto no fornecedor ({@code cProd}, F16.1); o usuário escolhe ou
 * cadastra o produto dos itens sem vínculo. {@link #gerarEntrada} grava, numa única transação, o fornecedor (se
 * faltar), os vínculos feitos à mão e uma entrada {@code ABERTA} com os itens, para conferência e confirmação na tela
 * Compras.
 *
 * <p>Quantidade e custo de cada item: pelo código de barras, como a Fase 8 (a unidade tributável usa {@code qTrib},
 * sem fator); pelo código do fornecedor ou escolhido à mão, {@code qCom} × fator e {@code vUnCom} ÷ fator (4 casas).
 */
public class ImportacaoNfeService {

    static final int TAMANHO_RAZAO = 120;
    static final int TAMANHO_CIDADE = 60;

    private final Transacao transacao;
    private final FornecedorDAO fornecedorDAO;
    private final EntradaDAO entradaDAO;
    private final ProdutoDAO produtoDAO;
    private final ProdutoFornecedorDAO vinculoDAO;
    private final EntradaService entradaService;
    private final ProdutoFornecedorService vinculos;
    private final Clock clock;

    /**
     * Item da nota e o produto vinculado (nulo sem vínculo).
     *
     * @param fator não nulo quando o vínculo é pelo código do fornecedor: a quantidade é {@code qCom} × fator
     */
    public record ItemPrevia(LeitorNfe.Item item, ProdutoVenda produto, BigDecimal fator) {

        public boolean peloFornecedor() {
            return fator != null;
        }
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
                                ProdutoDAO produtoDAO, ProdutoFornecedorDAO vinculoDAO, EntradaService entradaService,
                                ProdutoFornecedorService vinculos, Clock clock) {
        this.transacao = transacao;
        this.fornecedorDAO = fornecedorDAO;
        this.entradaDAO = entradaDAO;
        this.produtoDAO = produtoDAO;
        this.vinculoDAO = vinculoDAO;
        this.entradaService = entradaService;
        this.vinculos = vinculos;
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
                itens.add(vincular(con, fornecedor.map(Fornecedor::id).orElse(null), item));
            }
            return new Previa(nota, fornecedor.orElse(null), List.copyOf(itens), existente);
        });
    }

    /** Sem fatores: o produto escolhido à mão recebe fator 1. */
    public long gerarEntrada(Sessao sessao, byte[] xml, Map<Integer, Long> escolhidos) {
        return gerarEntrada(sessao, xml, escolhidos, Map.of());
    }

    /**
     * Gera a entrada {@code ABERTA}. O mesmo produto em vários itens da nota vira uma linha só (quantidades e valores
     * somados, e o custo = valor ÷ quantidade): a entrada aceita um produto por linha (Fase 7).
     *
     * @param escolhidos número do item ({@code nItem}) → produto escolhido ou cadastrado à mão; os demais usam o
     *                   vínculo pelo código de barras ou pelo código do fornecedor
     * @param fatores    número do item → fator do produto escolhido à mão (sem ele, 1); fica gravado no vínculo
     * @return número da entrada gerada
     * @throws NegocioException item sem produto, nota já lançada, quantidade inválida para a unidade do produto…
     */
    public long gerarEntrada(Sessao sessao, byte[] xml, Map<Integer, Long> escolhidos,
                             Map<Integer, BigDecimal> fatores) {
        sessao.exigir(Permissao.COMPRAS);
        LeitorNfe.Nota nota = LeitorNfe.ler(xml);
        String documento = documento(nota.emitente());
        return transacao.executar(con -> {
            record Linha(long produtoId, BigDecimal quantidade, BigDecimal valor, BigDecimal custo, String itens,
                         List<LoteEntrada> lotes) {
            }
            record Manual(long produtoId, LeitorNfe.Item item, BigDecimal fator) {
            }
            Fornecedor fornecedor = fornecedor(con, documento).orElse(null);
            Map<Long, Linha> linhas = new LinkedHashMap<>();
            List<Manual> manuais = new ArrayList<>();
            for (LeitorNfe.Item item : nota.itens()) {
                Long produtoId = escolhidos.get(item.numero());
                BigDecimal fator = null;
                if (produtoId != null) {
                    fator = fatorEscolhido(fatores.get(item.numero()), item);
                    manuais.add(new Manual(produtoId, item, fator));
                } else {
                    ItemPrevia vinculo = vincular(con, fornecedor == null ? null : fornecedor.id(), item);
                    if (vinculo.produto() != null) {
                        produtoId = vinculo.produto().id();
                        fator = vinculo.fator();
                    }
                }
                if (produtoId == null) {
                    throw new NegocioException("O item " + item.numero() + " da nota (\"" + item.descricao()
                            + "\") não tem produto: escolha ou cadastre o produto antes de gerar a entrada.");
                }
                BigDecimal quantidade = fator == null ? item.quantidade()
                        : item.comercial().quantidade().multiply(fator);
                BigDecimal custo = fator == null || item.comercial().valorUnitario() == null ? null
                        : item.comercial().valorUnitario().divide(fator, Dinheiro.ESCALA_CUSTO, Dinheiro.ARREDONDAMENTO);
                List<LoteEntrada> lotes = lotesDoRastro(item, quantidade);
                Linha anterior = linhas.get(produtoId);
                linhas.put(produtoId, anterior == null
                        ? new Linha(produtoId, quantidade, item.valorTotal(), custo, String.valueOf(item.numero()),
                        lotes)
                        : new Linha(produtoId, anterior.quantidade().add(quantidade),
                        anterior.valor().add(item.valorTotal()), null, anterior.itens() + ", " + item.numero(),
                        juntar(anterior.lotes(), lotes)));
            }

            long fornecedorId = fornecedor != null ? fornecedor.id() : cadastrar(con, nota);
            long entradaId = entradaService.criar(con, sessao, new DadosEntrada(fornecedorId,
                    nota.numero() == null ? "" : nota.numero(), nota.emissao(), nota.valorTotal()));
            for (Linha linha : linhas.values()) {
                try {
                    long itemId;
                    if (linha.custo() != null) {
                        itemId = entradaService.adicionarItemDaNota(con, entradaId, linha.produtoId(),
                                linha.quantidade(), linha.custo(), linha.valor());
                    } else {
                        itemId = entradaService.adicionarItem(con, entradaId, linha.produtoId(), linha.quantidade(),
                                null, linha.valor());
                    }
                    // F17.2: o rastro da nota preenche a divisão em lotes do produto com controle de validade; se a
                    // soma não bater com a quantidade do item, a confirmação espera a divisão ser acertada.
                    if (!linha.lotes().isEmpty()
                            && produtoDAO.controlaValidade(con, linha.produtoId()).orElse(false)) {
                        entradaService.salvarLotesDoItem(con, entradaDAO.buscarItem(con, entradaId, itemId)
                                .orElseThrow(), linha.lotes());
                    }
                } catch (NegocioException e) {
                    throw new NegocioException("Item " + linha.itens() + " da nota: " + e.getMessage(), e);
                }
            }
            // F16.1: o vínculo feito à mão fica gravado para as próximas notas do fornecedor.
            for (Manual m : manuais) {
                vinculos.vincularPelaNota(con, sessao, m.produtoId(), fornecedorId, m.item().comercial().codigoProduto(),
                        m.item().comercial().unidade(), m.fator());
            }
            // As duplicatas do XML já vêm como parcelas (F15.3), numeradas na ordem da nota.
            entradaService.salvarParcelas(con, entradaId, nota.duplicatas().stream()
                    .map(d -> new ParcelaEntrada(0, d.vencimento(), d.valor())).toList());
            return entradaId;
        });
    }

    /**
     * Lotes do grupo {@code rastro} na unidade do produto (F17.2): cada {@code qLote} pela mesma razão da quantidade do
     * item (quantidade do item ÷ {@code qCom}; pelo vínculo, o fator), com 3 casas. Rastro sem validade é ignorado (a
     * divisão fica para conferir).
     */
    static List<LoteEntrada> lotesDoRastro(LeitorNfe.Item item, BigDecimal quantidadeDoItem) {
        BigDecimal qCom = item.comercial().quantidade();
        List<LoteEntrada> lotes = new ArrayList<>();
        for (LeitorNfe.Rastro r : item.rastros()) {
            if (r.validade() == null || r.quantidade() == null || r.quantidade().signum() <= 0) {
                continue;
            }
            BigDecimal q = qCom == null || qCom.signum() == 0 ? r.quantidade()
                    : r.quantidade().multiply(quantidadeDoItem).divide(qCom, Dinheiro.ESCALA_QUANTIDADE,
                    Dinheiro.ARREDONDAMENTO);
            lotes = juntar(lotes, List.of(new LoteEntrada(ParteLote.normalizarCodigo(r.lote()), r.validade(),
                    q.setScale(Dinheiro.ESCALA_QUANTIDADE, Dinheiro.ARREDONDAMENTO))));
        }
        return lotes;
    }

    /** Junta as divisões, somando o mesmo lote (código e validade). */
    private static List<LoteEntrada> juntar(List<LoteEntrada> a, List<LoteEntrada> b) {
        Map<String, LoteEntrada> juntos = new LinkedHashMap<>();
        for (LoteEntrada l : a) {
            juntos.put(l.codigo() + "|" + l.validade(), l);
        }
        for (LoteEntrada l : b) {
            juntos.merge(l.codigo() + "|" + l.validade(), l, (x, y) -> new LoteEntrada(x.codigo(), x.validade(),
                    x.quantidade().add(y.quantidade())));
        }
        return new ArrayList<>(juntos.values());
    }

    /**
     * Fator que a conferência propõe para o produto escolhido à mão: na unidade tributável, quanto 1 unidade comercial
     * tem dela (ex.: 2 CX = 48 UN → 24), se der um número com até 3 casas; senão, 1.
     */
    public static BigDecimal fatorSugerido(LeitorNfe.Item item) {
        BigDecimal qCom = item.comercial().quantidade();
        if (item.unidadeTributavel() && qCom.signum() > 0) {
            try {
                BigDecimal fator = item.quantidade().divide(qCom).stripTrailingZeros();
                if (fator.scale() <= Dinheiro.ESCALA_QUANTIDADE && fator.compareTo(BigDecimal.ONE) >= 0) {
                    return fator.setScale(Dinheiro.ESCALA_QUANTIDADE);
                }
            } catch (ArithmeticException e) {
                // dízima: fica o fator 1
            }
        }
        return BigDecimal.ONE.setScale(Dinheiro.ESCALA_QUANTIDADE);
    }

    private static BigDecimal fatorEscolhido(BigDecimal fator, LeitorNfe.Item item) {
        if (fator == null) {
            return BigDecimal.ONE;
        }
        try {
            // O fator só precisa ser inteiro quando o produto é de unidade inteira; a quantidade é conferida depois.
            return ProdutoFornecedorService.fator(fator, true);
        } catch (NegocioException e) {
            throw new NegocioException("Item " + item.numero() + " da nota: " + e.getMessage(), e);
        }
    }

    /**
     * Produto de cada item da nota (nº do item → produto), como na geração da entrada: o escolhido à mão na
     * conferência ou o vínculo pelo código de barras ou pelo código do fornecedor. Item sem produto fica de fora.
     * Usado pelo preenchimento dos dados fiscais (F19.3).
     */
    Map<Integer, Long> produtosDaNota(Connection con, LeitorNfe.Nota nota, Map<Integer, Long> escolhidos)
            throws SQLException {
        Long fornecedorId = fornecedor(con, documento(nota.emitente())).map(Fornecedor::id).orElse(null);
        Map<Integer, Long> produtos = new LinkedHashMap<>();
        for (LeitorNfe.Item item : nota.itens()) {
            Long produtoId = escolhidos == null ? null : escolhidos.get(item.numero());
            if (produtoId == null) {
                ItemPrevia vinculo = vincular(con, fornecedorId, item);
                produtoId = vinculo.produto() == null ? null : vinculo.produto().id();
            }
            if (produtoId != null) {
                produtos.put(item.numero(), produtoId);
            }
        }
        return produtos;
    }

    /** Pelo código de barras; sem ele, pelo par (fornecedor, {@code cProd}). */
    private ItemPrevia vincular(Connection con, Long fornecedorId, LeitorNfe.Item item) throws SQLException {
        if (item.codigoBarras() != null) {
            Optional<ProdutoVenda> produto = produtoDAO.buscarParaVendaPorCodigo(con, item.codigoBarras());
            if (produto.isPresent()) {
                return new ItemPrevia(item, produto.get(), null);
            }
        }
        String cProd = item.comercial().codigoProduto();
        if (fornecedorId != null && !Textos.vazio(cProd)) {
            Optional<ProdutoFornecedor> vinculo = vinculoDAO.porCodigo(con, fornecedorId, cProd.strip());
            if (vinculo.isPresent()) {
                Optional<ProdutoVenda> produto = produtoDAO.buscarParaVenda(con, vinculo.get().produtoId());
                if (produto.isPresent()) {
                    return new ItemPrevia(item, produto.get(), vinculo.get().fator());
                }
            }
        }
        return new ItemPrevia(item, null, null);
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
