package br.com.varesync.service;

import br.com.varesync.dao.CategoriaDAO;
import br.com.varesync.dao.ProdutoDAO;
import br.com.varesync.dao.UnidadeMedidaDAO;
import br.com.varesync.model.Categoria;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.UnidadeMedida;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Gtin;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Importação de produtos por planilha CSV (6.11), com a permissão PRODUTOS: prévia com a validação linha a linha
 * (erros apontam a linha e o campo) e importação numa única transação. Com erro em qualquer linha nada é importado,
 * a não ser que o usuário escolha ignorar as linhas com erro. Categoria inexistente é criada; estoque inicial gera
 * {@code AJUSTE_ENTRADA} com motivo "Importação". Cada produto passa pelas mesmas regras e auditoria do cadastro.
 */
public class ImportacaoProdutosService {

    public static final String MOTIVO_ESTOQUE = "Importação";

    private final Transacao transacao;
    private final ProdutoService produtoService;
    private final ProdutoDAO produtoDAO;
    private final CategoriaDAO categoriaDAO;
    private final UnidadeMedidaDAO unidadeDAO;

    /** Problema num campo; {@code campo} é o nome da coluna do modelo. */
    public record Erro(String campo, String mensagem) {

        @Override
        public String toString() {
            return campo + ": " + mensagem;
        }
    }

    /**
     * Linha validada. Os valores ficam nulos quando o campo tem erro ou está vazio.
     *
     * @param categoriaNova a categoria não existe e será criada
     * @param avisos        situações que não impedem a importação (preço abaixo do custo, nome repetido)
     */
    public record Linha(int numero, String nome, String codigoBarras, String unidade, String categoria,
                        boolean categoriaNova, BigDecimal custo, BigDecimal preco, BigDecimal estoqueMinimo,
                        BigDecimal estoqueInicial, List<Erro> erros, List<String> avisos) {

        public boolean valida() {
            return erros.isEmpty();
        }
    }

    /** Resultado da validação, para a pré-visualização. */
    public record Previa(List<Linha> linhas, String codificacao) {

        public long comErro() {
            return linhas.stream().filter(l -> !l.valida()).count();
        }

        public long validas() {
            return linhas.size() - comErro();
        }

        /** Categorias que serão criadas pelas linhas válidas, sem repetir. */
        public List<String> categoriasNovas() {
            Map<String, String> nomes = new LinkedHashMap<>();
            linhas.stream().filter(l -> l.valida() && l.categoriaNova())
                    .forEach(l -> nomes.putIfAbsent(l.categoria().toLowerCase(Locale.ROOT), l.categoria()));
            return List.copyOf(nomes.values());
        }
    }

    public record Resultado(int importados, int ignorados, List<String> categoriasCriadas, long primeiroId,
                            long ultimoId) {
    }

    public ImportacaoProdutosService(Transacao transacao, ProdutoService produtoService, ProdutoDAO produtoDAO,
                                     CategoriaDAO categoriaDAO, UnidadeMedidaDAO unidadeDAO) {
        this.transacao = transacao;
        this.produtoService = produtoService;
        this.produtoDAO = produtoDAO;
        this.categoriaDAO = categoriaDAO;
        this.unidadeDAO = unidadeDAO;
    }

    /** Modelo {@code .csv} para baixar (cabeçalho e dois exemplos). */
    public byte[] modelo(Sessao sessao) {
        sessao.exigir(Permissao.PRODUTOS);
        return PlanilhaProdutos.modelo();
    }

    /** Lê e valida a planilha, sem gravar nada. */
    public Previa previa(Sessao sessao, byte[] conteudo) {
        sessao.exigir(Permissao.PRODUTOS);
        PlanilhaProdutos.Leitura leitura = PlanilhaProdutos.ler(conteudo);
        return transacao.executar(con -> validar(con, leitura));
    }

    /**
     * Importa numa única transação, validando de novo (o banco pode ter mudado desde a prévia).
     *
     * @param ignorarLinhasComErro false: qualquer erro impede tudo; true: importa só as linhas válidas
     * @throws NegocioException planilha com erro (e sem ignorar), nenhuma linha válida ou falha numa linha
     */
    public Resultado importar(Sessao sessao, byte[] conteudo, boolean ignorarLinhasComErro) {
        sessao.exigir(Permissao.PRODUTOS);
        PlanilhaProdutos.Leitura leitura = PlanilhaProdutos.ler(conteudo);
        return transacao.executar(con -> {
            Previa previa = validar(con, leitura);
            long comErro = previa.comErro();
            if (comErro > 0 && !ignorarLinhasComErro) {
                Linha primeira = previa.linhas().stream().filter(l -> !l.valida()).findFirst().orElseThrow();
                throw new NegocioException("A planilha tem erro em " + (comErro == 1 ? "1 linha" : comErro + " linhas")
                        + " (a primeira: linha " + primeira.numero() + ", " + primeira.erros().get(0)
                        + "). Nada foi importado. Corrija a planilha ou escolha ignorar as linhas com erro.");
            }
            if (previa.validas() == 0) {
                throw new NegocioException("Nenhuma linha da planilha está sem erro: nada foi importado.");
            }
            Map<String, Long> categorias = new HashMap<>();
            for (String nome : previa.categoriasNovas()) {
                categorias.put(nome.toLowerCase(Locale.ROOT), categoriaDAO.inserir(con, nome));
            }
            long primeiro = 0;
            long ultimo = 0;
            int importados = 0;
            for (Linha l : previa.linhas()) {
                if (!l.valida()) {
                    continue;
                }
                Long categoriaId = l.categoria() == null ? null : categorias.computeIfAbsent(
                        l.categoria().toLowerCase(Locale.ROOT), n -> idCategoria(con, l.categoria()));
                DadosProduto dados = new DadosProduto(l.nome(), categoriaId, l.unidade(), l.preco(), l.custo(),
                        l.estoqueMinimo(), null, l.codigoBarras() == null ? List.of()
                        : List.of(new CodigoBarras(l.codigoBarras(), true)), l.estoqueInicial());
                long id;
                try {
                    id = produtoService.criar(con, sessao, dados, MOTIVO_ESTOQUE,
                            " pela importação de planilha (linha " + l.numero() + ")");
                } catch (NegocioException e) {
                    throw new NegocioException("Linha " + l.numero() + ": " + e.getMessage()
                            + " Nada foi importado.", e);
                }
                primeiro = primeiro == 0 ? id : primeiro;
                ultimo = id;
                importados++;
            }
            return new Resultado(importados, (int) comErro, previa.categoriasNovas(), primeiro, ultimo);
        });
    }

    private Long idCategoria(Connection con, String nome) {
        try {
            return categoriaDAO.buscarPorNome(con, nome, null).map(Categoria::id).orElseThrow();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ----- Validação -----

    private Previa validar(Connection con, PlanilhaProdutos.Leitura leitura) throws SQLException {
        Map<String, UnidadeMedida> unidades = new HashMap<>();
        for (UnidadeMedida u : unidadeDAO.listar(con)) {
            unidades.put(u.sigla().toUpperCase(Locale.ROOT), u);
        }
        Map<String, Integer> codigosNaPlanilha = new HashMap<>();
        Map<String, Integer> nomesNaPlanilha = new HashMap<>();
        Map<String, Optional<Categoria>> categorias = new HashMap<>();
        List<Linha> linhas = new ArrayList<>();
        for (PlanilhaProdutos.Linha p : leitura.linhas()) {
            List<Erro> erros = new ArrayList<>();
            List<String> avisos = new ArrayList<>();
            if (p.colunasDemais()) {
                erros.add(new Erro("linha", "tem mais colunas que o modelo (" + PlanilhaProdutos.COLUNAS.size()
                        + "). Confira se algum campo tem ponto e vírgula."));
            }

            String nome = campo(erros, "nome", () -> ValidacaoProduto.nome(p.campo("nome")));
            if (nome != null) {
                String busca = Textos.normalizarBusca(nome);
                Integer outra = nomesNaPlanilha.putIfAbsent(busca, p.numero());
                if (outra != null) {
                    avisos.add("nome igual ao da linha " + outra);
                } else if (!produtoDAO.mesmoNome(con, busca, null).isEmpty()) {
                    avisos.add("já existe produto com este nome");
                }
            }

            String codigo = codigoBarras(con, erros, p.campo("codigo_barras"), p.numero(), codigosNaPlanilha);

            String siglaDigitada = p.campo("unidade").isEmpty() ? "UN" : p.campo("unidade").toUpperCase(Locale.ROOT);
            UnidadeMedida unidade = unidades.get(siglaDigitada);
            if (unidade == null) {
                erros.add(new Erro("unidade", "\"" + p.campo("unidade") + "\" não é uma unidade cadastrada (use "
                        + String.join(", ", unidades.keySet().stream().sorted().toList()) + ")."));
            }

            String categoria = null;
            boolean categoriaNova = false;
            if (!p.campo("categoria").isEmpty()) {
                categoria = campo(erros, "categoria", () -> CategoriaService.nome(p.campo("categoria")));
                if (categoria != null) {
                    String chave = categoria.toLowerCase(Locale.ROOT);
                    if (!categorias.containsKey(chave)) {
                        categorias.put(chave, categoriaDAO.buscarPorNome(con, categoria, null));
                    }
                    Optional<Categoria> existente = categorias.get(chave);
                    if (existente.isEmpty()) {
                        categoriaNova = true;
                    } else if (!existente.get().ativa()) {
                        erros.add(new Erro("categoria", "a categoria \"" + existente.get().nome() + "\" está inativa. "
                                + "Reative-a em Produtos › Categorias ou use outra."));
                    } else {
                        categoria = existente.get().nome();
                    }
                }
            }

            BigDecimal custo = numero(erros, "custo", p.campo("custo"), true, ValidacaoProduto::custo);
            BigDecimal preco = numero(erros, "preco_venda", p.campo("preco_venda"), true, ValidacaoProduto::preco);
            boolean fracionada = unidade != null && unidade.fracionada();
            BigDecimal minimo = numero(erros, "estoque_minimo", p.campo("estoque_minimo"), false,
                    v -> ValidacaoProduto.quantidade(v, "O estoque mínimo", fracionada));
            BigDecimal inicial = numero(erros, "estoque_inicial", p.campo("estoque_inicial"), false,
                    v -> ValidacaoProduto.quantidade(v, "O estoque inicial", fracionada));
            if (unidade == null) {
                minimo = null;
                inicial = null;
            }
            if (custo != null && preco != null) {
                if (preco.signum() == 0) {
                    avisos.add("preço zero: o produto não pode ser vendido no PDV");
                } else if (preco.compareTo(custo) < 0) {
                    avisos.add("preço abaixo do custo");
                }
            }
            linhas.add(new Linha(p.numero(), nome, codigo, unidade == null ? siglaDigitada : unidade.sigla(),
                    categoria, categoriaNova, custo, preco, minimo, inicial, List.copyOf(erros),
                    List.copyOf(avisos)));
        }
        return new Previa(List.copyOf(linhas), leitura.codificacao());
    }

    private String codigoBarras(Connection con, List<Erro> erros, String digitado, int linha,
                                Map<String, Integer> naPlanilha) throws SQLException {
        String codigo = digitado.replace(" ", "");
        if (codigo.isEmpty()) {
            return null;
        }
        if (codigo.toUpperCase(Locale.ROOT).contains("E+")) {
            erros.add(new Erro("codigo_barras", "\"" + digitado + "\" está em notação científica: no Excel, formate "
                    + "a coluna como texto e digite o código de novo."));
            return null;
        }
        Optional<String> invalido = Gtin.validar(codigo);
        if (invalido.isPresent()) {
            erros.add(new Erro("codigo_barras", invalido.get()));
            return null;
        }
        Integer outra = naPlanilha.putIfAbsent(codigo, linha);
        if (outra != null) {
            erros.add(new Erro("codigo_barras", "o código " + codigo + " já está na linha " + outra + "."));
            return null;
        }
        Optional<ProdutoDAO.Dono> dono = produtoDAO.donoDoCodigo(con, codigo, null);
        if (dono.isPresent()) {
            erros.add(new Erro("codigo_barras", "o código " + codigo + " já pertence ao produto "
                    + ProdutoService.descrever(dono.get().id(), dono.get().nome()) + "."));
            return null;
        }
        return codigo;
    }

    private static <T> T campo(List<Erro> erros, String coluna, java.util.function.Supplier<T> validacao) {
        try {
            return validacao.get();
        } catch (NegocioException e) {
            erros.add(new Erro(coluna, e.getMessage()));
            return null;
        }
    }

    /** Número com vírgula ou ponto decimal (aceita "R$"); vazio = erro se obrigatório, senão nulo. */
    private static BigDecimal numero(List<Erro> erros, String coluna, String digitado, boolean obrigatorio,
                                     Function<BigDecimal, BigDecimal> validacao) {
        String texto = digitado.replace("R$", "").replace(" ", "");
        if (texto.isEmpty()) {
            if (obrigatorio) {
                erros.add(new Erro(coluna, "obrigatório."));
            }
            return null;
        }
        BigDecimal valor;
        try {
            valor = Dinheiro.ler(texto);
        } catch (IllegalArgumentException e) {
            erros.add(new Erro(coluna, "\"" + digitado + "\" não é um número válido (use vírgula para os decimais, "
                    + "ex.: 12,50)."));
            return null;
        }
        return campo(erros, coluna, () -> validacao.apply(valor));
    }
}
