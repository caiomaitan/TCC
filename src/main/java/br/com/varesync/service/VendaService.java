package br.com.varesync.service;

import br.com.varesync.dao.ClienteDAO;
import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.CreditoClienteDAO;
import br.com.varesync.dao.EstoqueDAO;
import br.com.varesync.dao.FormaPagamentoDAO;
import br.com.varesync.dao.ProdutoDAO;
import br.com.varesync.dao.TituloReceberDAO;
import br.com.varesync.dao.VendaDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.CaixaSessao;
import br.com.varesync.model.ClienteCrediario;
import br.com.varesync.model.ClienteVenda;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.Comprovante;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.Embalagem;
import br.com.varesync.model.FaixaPreco;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.OrigemMovimento;
import br.com.varesync.model.ParametrosVenda;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.PlanoCrediario;
import br.com.varesync.model.ProdutoResumo;
import br.com.varesync.model.ProdutoVenda;
import br.com.varesync.model.SituacaoProduto;
import br.com.varesync.model.StatusTitulo;
import br.com.varesync.model.StatusVenda;
import br.com.varesync.model.TituloReceber;
import br.com.varesync.model.TipoCreditoCliente;
import br.com.varesync.model.TipoDesconto;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.model.TipoMovimento;
import br.com.varesync.model.TipoValorBalanca;
import br.com.varesync.model.VendaResumo;
import br.com.varesync.util.CalculoCrediario;
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
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * PDV (6.6): leitura do produto, carrinho com regra de estoque, descontos com limite e liberação, cancelamento de
 * item, abandono, finalização numa única transação, vendas da sessão e cancelamento de venda finalizada.
 *
 * <p>O carrinho fica em memória ({@link Carrinho}); a venda só vai para o banco em {@link #finalizar}. Toda venda e
 * todo cancelamento travam a sessão de caixa aberta do terminal ({@link CaixaService#travarAberta}).
 */
public class VendaService {

    public static final String ENTIDADE_VENDA = "venda";
    private static final String ENTIDADE_CLIENTE = "cliente";
    private static final BigDecimal CEM = BigDecimal.valueOf(100);

    private final Transacao transacao;
    private final ProdutoDAO produtoDAO;
    private final VendaDAO vendaDAO;
    private final FormaPagamentoDAO formaPagamentoDAO;
    private final ConfiguracaoDAO configuracaoDAO;
    private final EstoqueDAO estoqueDAO;
    private final EstoqueService estoqueService;
    private final CaixaService caixaService;
    private final PrecoService precoService;
    private final AuditoriaService auditoria;
    private final ClienteDAO clienteDAO;
    private final CreditoClienteDAO creditoDAO;
    private final CreditoService creditoService;
    private final TituloReceberDAO tituloDAO;
    private final RecebivelService recebivelService;
    private final TributosVendaService tributos;
    private final Clock clock;

    /** Item incluído e, se houver, o aviso não bloqueante (estoque ficará negativo). */
    public record Inclusao(ItemCarrinho item, String aviso) {
    }

    /** Resultado da finalização: número da venda, troco, cashback gerado (F12.5; zero sem ele) e o comprovante. */
    public record VendaFinalizada(long vendaId, BigDecimal total, BigDecimal troco, BigDecimal cashback,
                                  Comprovante comprovante) {
    }

    /**
     * O que o pagamento mostra para o crediário (F14.2).
     *
     * @param primeiroVencimento sugestão para a 1ª parcela (hoje + {@code crediario.dias_primeira})
     * @param limiteDisponivel   limite − o que está em aberto; nulo sem cliente identificado
     */
    public record OpcoesCrediario(int maxParcelas, LocalDate primeiroVencimento, BigDecimal limiteDisponivel) {
    }

    public VendaService(Transacao transacao, ProdutoDAO produtoDAO, VendaDAO vendaDAO,
                        FormaPagamentoDAO formaPagamentoDAO, ConfiguracaoDAO configuracaoDAO, EstoqueDAO estoqueDAO,
                        EstoqueService estoqueService, CaixaService caixaService, PrecoService precoService,
                        AuditoriaService auditoria, ClienteDAO clienteDAO, CreditoClienteDAO creditoDAO,
                        CreditoService creditoService, TituloReceberDAO tituloDAO, RecebivelService recebivelService,
                        TributosVendaService tributos, Clock clock) {
        this.tributos = tributos;
        this.recebivelService = recebivelService;
        this.tituloDAO = tituloDAO;
        this.transacao = transacao;
        this.produtoDAO = produtoDAO;
        this.vendaDAO = vendaDAO;
        this.formaPagamentoDAO = formaPagamentoDAO;
        this.configuracaoDAO = configuracaoDAO;
        this.estoqueDAO = estoqueDAO;
        this.estoqueService = estoqueService;
        this.caixaService = caixaService;
        this.precoService = precoService;
        this.auditoria = auditoria;
        this.clienteDAO = clienteDAO;
        this.creditoDAO = creditoDAO;
        this.creditoService = creditoService;
        this.clock = clock;
    }

    public ParametrosVenda parametros(Sessao sessao) {
        sessao.exigir(Permissao.PDV);
        return transacao.executar(this::parametros);
    }

    // ----- Leitura de produto -----

    /**
     * Produto lido no PDV e, se veio de etiqueta de balança (6.9), a etiqueta; se veio de um código de embalagem
     * (caixa, fardo, F20.2), a embalagem. As duas são nulas nos demais casos.
     */
    public record LeituraProduto(ProdutoVenda produto, EtiquetaBalanca etiqueta, Embalagem embalagem) {

        public LeituraProduto(ProdutoVenda produto, EtiquetaBalanca etiqueta) {
            this(produto, etiqueta, null);
        }
    }

    /**
     * Consulta de preço (F7): o produto e o preço que vale hoje (promoção ou preço de venda) e, com a F20.3, as faixas
     * de preço por quantidade, as embalagens com preço próprio e a embalagem do código lido (nula no da unidade).
     */
    public record ConsultaPreco(ProdutoVenda produto, PrecoService.Preco preco, List<FaixaPreco> faixas,
                                List<CodigoBarras> embalagens, Embalagem lida) {

        public ConsultaPreco(ProdutoVenda produto, PrecoService.Preco preco) {
            this(produto, preco, List.of(), List.of(), null);
        }

        /** O texto do F7, uma informação por linha. */
        public String texto() {
            ProdutoVenda p = produto;
            String unidade = p.unidade();
            StringBuilder s = new StringBuilder(p.nome()).append('\n');
            s.append(preco.promocional()
                    ? Formatos.moeda(preco.valor()) + " / " + unidade + " (promoção; preço normal "
                    + Formatos.moeda(p.precoVenda()) + ")"
                    : Formatos.moeda(p.precoVenda()) + " / " + unidade);
            if (lida != null && lida.ehEmbalagem()) {
                s.append("\nCódigo lido: embalagem com ").append(qtd(lida.quantidade())).append(' ')
                        .append(unidade.toLowerCase());
            }
            for (FaixaPreco f : faixas) {
                s.append("\nA partir de ").append(qtd(f.quantidadeMinima())).append(' ').append(unidade.toLowerCase())
                        .append(": ").append(Formatos.moeda(f.preco())).append(" / ").append(unidade);
                if (f.preco().compareTo(preco.valor()) >= 0) {
                    s.append(" (não vale: a promoção é menor)");
                }
            }
            for (CodigoBarras c : embalagens) {
                BigDecimal unidades = Dinheiro.multiplicar(c.quantidade(), preco.valor());
                s.append("\nEmbalagem com ").append(qtd(c.quantidade())).append(' ').append(unidade.toLowerCase())
                        .append(" (").append(c.codigo()).append("): ");
                if (unidades.compareTo(c.precoEmbalagem()) < 0) {
                    s.append(Formatos.moeda(unidades)).append(" pelas unidades (preço da embalagem ")
                            .append(Formatos.moeda(c.precoEmbalagem())).append(")");
                } else {
                    s.append(Formatos.moeda(c.precoEmbalagem()));
                }
            }
            s.append("\nEstoque: ").append(Formatos.quantidade(p.saldo(), p.fracionada())).append(' ').append(unidade);
            if (!p.ativo()) {
                s.append("\nProduto inativo: não pode ser vendido.");
            }
            return s.toString();
        }

        private static String qtd(BigDecimal q) {
            return q.stripTrailingZeros().toPlainString().replace('.', ',');
        }
    }

    /**
     * Código lido ou digitado (6.6): código de barras exato → código interno → etiqueta de balança (se ativa, 6.9)
     * → "Produto não encontrado". Produto inativo ou sem preço é recusado.
     */
    public LeituraProduto lerProduto(Sessao sessao, String codigo) {
        LeituraProduto leitura = buscarPorCodigo(sessao, codigo);
        validarVendavel(leitura.produto());
        return leitura;
    }

    /** Produto escolhido na busca (F2). */
    public ProdutoVenda produtoParaVenda(Sessao sessao, long produtoId) {
        sessao.exigir(Permissao.PDV);
        ProdutoVenda produto = transacao.executar(con -> produtoDAO.buscarParaVenda(con, produtoId))
                .orElseThrow(() -> new NegocioException("Produto não encontrado."));
        validarVendavel(produto);
        return produto;
    }

    /** Consulta de preço (F7): mostra o produto, mesmo inativo, e o preço de hoje (com promoção), sem vender. */
    public ConsultaPreco consultarPreco(Sessao sessao, String codigo) {
        LeituraProduto leitura = buscarPorCodigo(sessao, codigo);
        ProdutoVenda produto = leitura.produto();
        return transacao.executar(con -> new ConsultaPreco(produto,
                precoService.precoVigente(con, produto, LocalDate.now(clock)), precoService.faixas(con, produto.id()),
                produtoDAO.listarCodigos(con, produto.id()).stream().filter(c -> c.precoEmbalagem() != null).toList(),
                leitura.embalagem()));
    }

    /** Busca por nome (F2): só produtos ativos; só dígitos procura código interno ou código de barras exato. */
    public List<ProdutoResumo> pesquisarProdutos(Sessao sessao, String texto) {
        sessao.exigir(Permissao.PDV);
        String termo = Textos.normalizarEspacos(texto);
        ProdutoDAO.Filtro filtro = Textos.soDigitos(termo)
                ? new ProdutoDAO.Filtro(null, termo, null, SituacaoProduto.ATIVOS)
                : new ProdutoDAO.Filtro(Textos.normalizarBusca(termo), null, null, SituacaoProduto.ATIVOS);
        return transacao.executar(con -> produtoDAO.pesquisar(con, filtro));
    }

    // ----- Carrinho -----

    /**
     * Inclui o item (6.6): quantidade &gt; 0 com até 3 casas (inteira se a unidade não é fracionada), preço vigente
     * ({@link PrecoService}) e regra de estoque: com {@code venda.permitir_estoque_negativo = false} recusa se
     * (saldo − quantidade já no carrinho − nova quantidade) &lt; 0; com {@code true}, só avisa.
     */
    public Inclusao adicionarItem(Sessao sessao, Carrinho carrinho, ProdutoVenda produto, BigDecimal quantidade) {
        sessao.exigir(Permissao.PDV);
        if (quantidade == null || quantidade.signum() <= 0) {
            throw new NegocioException("A quantidade deve ser maior que zero.");
        }
        BigDecimal qtd = ValidacaoProduto.quantidade(quantidade, "A quantidade", produto.fracionada());
        return incluir(sessao, carrinho, produto, qtd, null, null);
    }

    /**
     * Inclui o item lido por um código de embalagem (caixa, fardo, F20.2): quantidade = quantidade da embalagem ×
     * multiplicador ({@code 2*código} = 2 embalagens), com o multiplicador inteiro. Com preço de embalagem, o valor do
     * item é o menor entre o preço da embalagem × nº de embalagens e a quantidade × o preço unitário vigente (com a
     * promoção), e o item fica fora das faixas por quantidade; sem ele, é um item comum, que participa das faixas. O
     * estoque baixa a quantidade em unidades. Mesma regra de estoque de {@link #adicionarItem}.
     *
     * @param multiplicador nº de embalagens; nulo = 1
     */
    public Inclusao adicionarEmbalagem(Sessao sessao, Carrinho carrinho, LeituraProduto leitura,
                                       BigDecimal multiplicador) {
        sessao.exigir(Permissao.PDV);
        Embalagem embalagem = leitura.embalagem();
        if (embalagem == null) {
            throw new IllegalArgumentException("Leitura sem embalagem");
        }
        BigDecimal embalagens = multiplicador == null ? BigDecimal.ONE : multiplicador;
        if (embalagens.signum() <= 0) {
            throw new NegocioException("A quantidade deve ser maior que zero.");
        }
        if (embalagens.stripTrailingZeros().scale() > 0) {
            throw new NegocioException("O código lido é de uma embalagem com "
                    + quantidade(embalagem.quantidade(), leitura.produto()) + ": informe um número inteiro de "
                    + "embalagens antes do * (ex.: 2*código = 2 embalagens).");
        }
        BigDecimal qtd = ValidacaoProduto.quantidade(embalagem.quantidade().multiply(embalagens), "A quantidade",
                leitura.produto().fracionada());
        return incluir(sessao, carrinho, leitura.produto(), qtd, null, embalagem.temPreco()
                ? embalagem.preco().multiply(embalagens) : null);
    }

    /**
     * Inclui o item lido pela etiqueta de balança (6.9): a quantidade (e, em {@code PRECO}, o valor do item, sem
     * recálculo) vem da etiqueta, com o preço vigente do produto. Mesma regra de estoque de {@link #adicionarItem}.
     */
    public Inclusao adicionarEtiqueta(Sessao sessao, Carrinho carrinho, LeituraProduto leitura) {
        sessao.exigir(Permissao.PDV);
        if (leitura.etiqueta() == null) {
            throw new IllegalArgumentException("Leitura sem etiqueta de balança");
        }
        if (!leitura.produto().fracionada()) {
            throw new NegocioException("O produto \"" + leitura.produto().nome() + "\" não é vendido por peso ("
                    + leitura.produto().unidade() + "): confira o PLU no cadastro.");
        }
        return incluir(sessao, carrinho, leitura.produto(), null, leitura.etiqueta(), null);
    }

    /**
     * @param quantidade       nula quando vem da etiqueta (calculada aqui com o preço vigente)
     * @param precoEmbalagens  preço da embalagem × nº de embalagens (F20.2); nulo sem embalagem com preço próprio
     */
    private Inclusao incluir(Sessao sessao, Carrinho carrinho, ProdutoVenda produto, BigDecimal quantidade,
                             EtiquetaBalanca etiqueta, BigDecimal precoEmbalagens) {
        record Leitura(ProdutoVenda produto, PrecoService.Preco preco, BigDecimal quantidade, BigDecimal valorFixo,
                       List<FaixaPreco> faixas, String aviso) {
        }
        Leitura leitura = transacao.executar(con -> {
            ProdutoVenda atual = produtoDAO.buscarParaVenda(con, produto.id())
                    .orElseThrow(() -> new NegocioException("Produto não encontrado."))
                    .comCodigo(produto.codigo());
            validarVendavel(atual);
            PrecoService.Preco preco = precoService.precoVigente(con, atual, LocalDate.now(clock));
            BigDecimal qtd = quantidade;
            BigDecimal valorFixo = null;
            if (etiqueta != null) {
                EtiquetaBalanca.Item item = etiqueta.item(preco.valor());
                qtd = item.quantidade();
                valorFixo = etiqueta.tipo() == TipoValorBalanca.PRECO ? item.valorBruto() : null;
            }
            if (precoEmbalagens != null) {
                // A caixa nunca sai mais cara que as unidades soltas (F20.2); empatando, vale o preço da embalagem.
                BigDecimal unidades = Dinheiro.multiplicar(qtd, preco.valor());
                BigDecimal embalagens = Dinheiro.dinheiro(precoEmbalagens);
                if (unidades.compareTo(embalagens) < 0) {
                    valorFixo = unidades;
                } else {
                    valorFixo = embalagens;
                    preco = new PrecoService.Preco(valorFixo.divide(qtd, Dinheiro.ESCALA_DINHEIRO,
                            Dinheiro.ARREDONDAMENTO), null);
                }
            }
            ParametrosVenda parametros = parametros(con);
            BigDecimal noCarrinho = carrinho.quantidadeDoProduto(atual.id());
            String aviso = null;
            if (atual.saldo().subtract(noCarrinho).subtract(qtd).signum() < 0) {
                if (!parametros.permitirEstoqueNegativo()) {
                    throw new NegocioException("Estoque insuficiente de \"" + atual.nome() + "\": saldo "
                            + quantidade(atual.saldo(), atual) + (noCarrinho.signum() > 0
                            ? ", já há " + quantidade(noCarrinho, atual) + " nesta venda" : "") + ".");
                }
                aviso = "Atenção: o estoque de \"" + atual.nome() + "\" ficará negativo (saldo "
                        + quantidade(atual.saldo(), atual) + ").";
            }
            return new Leitura(atual, preco, qtd, valorFixo, precoService.faixas(con, atual.id()), aviso);
        });
        // Entram nas faixas por quantidade os itens comuns: nem balança nem embalagem com preço próprio (F20.2).
        boolean participaFaixa = etiqueta == null && precoEmbalagens == null;
        ItemCarrinho item = carrinho.adicionar(leitura.produto(), leitura.quantidade(), leitura.preco().valor(),
                leitura.preco().promocaoId(), leitura.valorFixo(), participaFaixa);
        carrinho.definirFaixas(leitura.produto().id(), leitura.faixas());
        List<String> avisos = new ArrayList<>();
        if (leitura.aviso() != null) {
            avisos.add(leitura.aviso());
        }
        avisos.addAll(carrinho.aplicarFaixas(leitura.produto().id()));
        return new Inclusao(item, avisos.isEmpty() ? null : String.join(" ", avisos));
    }

    /**
     * Desconto no item (F4), em R$ ou %, até o valor do item; zero remove. Acima do limite do operador exige
     * DESCONTO_ACIMA_LIMITE ou a liberação ({@link LimiteDescontoException} sem ela).
     */
    public void aplicarDescontoItem(Sessao sessao, Carrinho carrinho, int sequencia, TipoDesconto tipo,
                                    BigDecimal informado, Liberacao liberacao) {
        sessao.exigir(Permissao.PDV);
        ItemCarrinho item = carrinho.itemAtivo(sequencia);
        BigDecimal valor = valorDesconto(tipo, informado, item.valorBruto());
        BigDecimal anterior = item.desconto();
        carrinho.definirDescontoItem(sequencia, valor);
        try {
            conferirLimite(sessao, carrinho, liberacao);
        } catch (RuntimeException e) {
            carrinho.definirDescontoItem(sequencia, anterior);
            throw e;
        }
    }

    /** Desconto na venda (F5), em R$ ou %, até o total; zero remove. Mesmo limite do desconto no item. */
    public void aplicarDescontoVenda(Sessao sessao, Carrinho carrinho, TipoDesconto tipo, BigDecimal informado,
                                     Liberacao liberacao) {
        sessao.exigir(Permissao.PDV);
        BigDecimal valor = valorDesconto(tipo, informado, carrinho.baseDescontoVenda());
        BigDecimal anterior = carrinho.descontoVenda();
        carrinho.definirDescontoVenda(valor);
        try {
            conferirLimite(sessao, carrinho, liberacao);
        } catch (RuntimeException e) {
            carrinho.definirDescontoVenda(anterior);
            throw e;
        }
    }

    /** O desconto atual passa do limite e ainda não há permissão nem liberação que o cubra? (antes do F10) */
    public boolean descontoPrecisaLiberacao(Sessao sessao, Carrinho carrinho) {
        Sessao atual = sessao.exigir(Permissao.PDV);
        return precisaLiberacao(atual, carrinho, transacao.executar(this::parametros));
    }

    /** Registra a liberação do desconto atual, pedida antes do pagamento. */
    public void liberarDesconto(Sessao sessao, Carrinho carrinho, Liberacao liberacao) {
        sessao.exigir(Permissao.PDV);
        sessao.exigirOuLiberado(Permissao.DESCONTO_ACIMA_LIMITE, liberacao);
        carrinho.registrarLiberacaoDesconto(liberacao);
    }

    /** Cancelar item pede liberação? ({@code venda.cancelar_item_exige_supervisor} e o usuário sem CANCELAR_VENDA) */
    public boolean cancelarItemPrecisaLiberacao(Sessao sessao) {
        Sessao atual = sessao.exigir(Permissao.PDV);
        return transacao.executar(this::parametros).cancelarItemExigeSupervisor()
                && !atual.pode(Permissao.CANCELAR_VENDA);
    }

    /**
     * Cancela o item durante a venda (F6): fica riscado e sai do total; auditoria {@code ITEM_CANCELADO}. Com
     * {@code venda.cancelar_item_exige_supervisor = true}, exige CANCELAR_VENDA ou a liberação. O item guarda quando,
     * quem cancelou e quem liberou, gravados em {@code venda_item} na finalização (F10.3).
     *
     * @return aviso para a tela, ou nulo (o desconto da venda é retirado se passar do novo total)
     */
    public String cancelarItem(Sessao sessao, Carrinho carrinho, int sequencia, Liberacao liberacao) {
        sessao.exigir(Permissao.PDV);
        ItemCarrinho item = carrinho.itemAtivo(sequencia);
        OffsetDateTime agora = OffsetDateTime.now(clock);
        Optional<Liberacao> liberou = transacao.executar(con -> {
            Liberacao valeu = parametros(con).cancelarItemExigeSupervisor()
                    ? sessao.exigirOuLiberado(Permissao.CANCELAR_VENDA, liberacao) : null;
            auditoria.registrar(con, sessao, AcaoAuditoria.ITEM_CANCELADO, ENTIDADE_VENDA, null,
                    "Item " + item.sequencia() + " cancelado durante a venda: " + descrever(item)
                            + (valeu == null ? "" : " (" + valeu.descricao() + ")"));
            return Optional.ofNullable(valeu);
        });
        boolean descontoRetirado = carrinho.cancelar(sequencia, new ItemCarrinho.Cancelamento(agora,
                sessao.usuarioId(), liberou.map(Liberacao::usuarioId).orElse(null)));
        // F20.2: com menos unidades, os itens do produto podem voltar ao preço de uma faixa menor (ou ao vigente).
        List<String> avisos = new ArrayList<>();
        if (descontoRetirado) {
            avisos.add(Carrinho.DESCONTO_VENDA_RETIRADO);
        }
        avisos.addAll(carrinho.aplicarFaixas(item.produto().id()));
        return avisos.isEmpty() ? null : String.join(" ", avisos);
    }

    /** Abandona a venda em andamento (Esc): auditoria {@code VENDA_ABANDONADA} com os itens e o total; nada vai para {@code venda}. */
    public void abandonar(Sessao sessao, Carrinho carrinho) {
        sessao.exigir(Permissao.PDV);
        registrarAbandono(sessao, carrinho, "");
    }

    /**
     * Abandona a venda em andamento quando a sessão termina e o usuário não pode continuar (F10.1 e F10.2): acesso
     * encerrado pelo supervisor ou "Trocar usuário" na tela bloqueada. Não confere a permissão (o usuário pode ter
     * perdido o acesso): só registra {@code VENDA_ABANDONADA} com o motivo e esvazia o carrinho.
     */
    public void abandonarNoEncerramento(Sessao sessao, Carrinho carrinho, String motivo) {
        registrarAbandono(sessao, carrinho, " (" + motivo + ")");
    }

    private void registrarAbandono(Sessao sessao, Carrinho carrinho, String complemento) {
        if (carrinho.vazio()) {
            return;
        }
        String itens = carrinho.itens().stream()
                .map(i -> (i.cancelado() ? "(cancelado) " : "") + descrever(i))
                .collect(Collectors.joining("; "));
        transacao.executar(con -> {
            auditoria.registrar(con, sessao, AcaoAuditoria.VENDA_ABANDONADA, ENTIDADE_VENDA, null,
                    "Venda abandonada com " + carrinho.quantidadeItensAtivos() + " item(ns), total "
                            + Formatos.moeda(carrinho.total())
                            + (carrinho.descontos().signum() > 0
                            ? " (descontos de " + Formatos.moeda(carrinho.descontos()) + ")" : "")
                            + ". Itens: " + itens + complemento);
            return null;
        });
        carrinho.limpar();
    }

    // ----- Cliente (F11, F12.2) -----

    /** Identifica o cliente da venda (troca o anterior, se houver). Cliente inativo ou anonimizado é recusado. */
    public ClienteVenda identificarCliente(Sessao sessao, Carrinho carrinho, long clienteId) {
        sessao.exigir(Permissao.PDV);
        ClienteVenda cliente = transacao.executar(con -> clienteAtivo(con, clienteId));
        carrinho.definirCliente(cliente);
        return cliente;
    }

    /** Tira a identificação do cliente da venda em andamento. */
    public void removerCliente(Sessao sessao, Carrinho carrinho) {
        sessao.exigir(Permissao.PDV);
        carrinho.definirCliente(null);
    }

    private ClienteVenda clienteAtivo(Connection con, long clienteId) throws SQLException {
        ClienteVenda cliente = clienteDAO.buscarParaVenda(con, clienteId)
                .orElseThrow(() -> new NegocioException("Cliente não encontrado."));
        if (!cliente.ativo()) {
            throw new NegocioException("O cliente \"" + cliente.nome() + "\" está inativo e não pode ser identificado "
                    + "na venda. Remova ou troque o cliente (F11).");
        }
        return cliente;
    }

    /**
     * Pagamento da venda em andamento (F10): com cliente identificado, o saldo de crédito lido agora (F12.4) pode ser
     * usado; sem cliente, o crédito do cliente é recusado.
     */
    public Recebimento recebimento(Sessao sessao, Carrinho carrinho) {
        sessao.exigir(Permissao.PDV);
        if (carrinho.cliente().isEmpty()) {
            return new Recebimento(carrinho.total());
        }
        long clienteId = carrinho.cliente().get().id();
        BigDecimal saldo = transacao.executar(con -> creditoDAO.saldo(con, clienteId)).orElse(Dinheiro.ZERO);
        return new Recebimento(carrinho.total(), saldo);
    }

    /** Parcelas possíveis, vencimento sugerido e limite disponível do cliente da venda em andamento (F14.2). */
    public OpcoesCrediario opcoesCrediario(Sessao sessao, Carrinho carrinho) {
        sessao.exigir(Permissao.PDV);
        return transacao.executar(con -> {
            Configuracoes.Crediario config = configuracaoDAO.carregar(con).crediario();
            BigDecimal disponivel = null;
            if (carrinho.cliente().isPresent()) {
                long clienteId = carrinho.cliente().get().id();
                disponivel = clienteDAO.limiteCredito(con, clienteId).orElse(Dinheiro.ZERO)
                        .subtract(tituloDAO.emAbertoDoCliente(con, clienteId));
            }
            return new OpcoesCrediario(config.maxParcelas(), LocalDate.now(clock).plusDays(config.diasPrimeira()),
                    disponivel);
        });
    }

    public List<FormaPagamento> formasAtivas(Sessao sessao) {
        sessao.exigir(Permissao.PDV);
        return transacao.executar(formaPagamentoDAO::listarAtivas);
    }

    // ----- Finalização -----

    /**
     * Finaliza a venda numa única transação (6.6): reconfere caixa aberto, produtos ativos, regra de estoque, limite
     * de desconto e formas de pagamento; grava {@code venda} → {@code venda_item} (com cancelados, na ordem) →
     * {@code venda_pagamento} → um movimento {@code VENDA} por item não cancelado → com cliente, {@code USO_EM_VENDA}
     * do crédito usado (saldo travado e conferido) e o {@code CASHBACK} (F12.4 e F12.5). Em falha, nada é gravado e
     * o carrinho continua como estava.
     */
    public VendaFinalizada finalizar(Sessao sessao, Carrinho carrinho, Recebimento recebimento) {
        Sessao conferida = sessao.exigir(Permissao.PDV);
        if (!carrinho.temItensAtivos()) {
            throw new NegocioException("A venda não tem itens.");
        }
        BigDecimal total = carrinho.total();
        if (recebimento.total().compareTo(total) != 0) {
            throw new NegocioException("O total da venda mudou. Refaça o pagamento.");
        }
        if (!recebimento.quitado()) {
            throw new NegocioException("Falta receber " + Formatos.moeda(recebimento.falta()) + ".");
        }
        OffsetDateTime agora = OffsetDateTime.now(clock);
        return transacao.executar(con -> {
            CaixaSessao caixa = caixaService.travarAberta(con, sessao);
            ParametrosVenda parametros = parametros(con);

            boolean acimaDoLimite = !carrinho.dentroDoLimite(parametros.descontoMaximoPct());
            Liberacao liberacaoDesconto = null;
            if (acimaDoLimite && !conferida.pode(Permissao.DESCONTO_ACIMA_LIMITE)) {
                liberacaoDesconto = carrinho.liberacaoDescontoValida()
                        .orElseThrow(() -> new LimiteDescontoException(mensagemLimite(carrinho, parametros)));
                conferida.exigirOuLiberado(Permissao.DESCONTO_ACIMA_LIMITE, liberacaoDesconto); // quem liberou ainda pode
            }
            // Quem autorizou o desconto acima do limite (F10.3): quem liberou, ou o próprio operador com a permissão.
            Long descontoLiberadoPor = !acimaDoLimite ? null
                    : liberacaoDesconto == null ? sessao.usuarioId() : liberacaoDesconto.usuarioId();

            Set<Integer> formasAtivas = formaPagamentoDAO.listarAtivas(con).stream().map(FormaPagamento::id)
                    .collect(Collectors.toSet());
            List<Recebimento.Parcela> pagamentos = recebimento.valoresAbatidos();
            for (Recebimento.Parcela p : pagamentos) {
                if (!formasAtivas.contains(p.forma().id())) {
                    throw new NegocioException("A forma de pagamento \"" + p.forma().descricao()
                            + "\" foi desativada. Escolha outra.");
                }
            }

            Map<Long, ProdutoVenda> produtos = new HashMap<>();
            for (ItemCarrinho item : carrinho.itens()) {
                long id = item.produto().id();
                if (!produtos.containsKey(id)) {
                    produtos.put(id, produtoDAO.buscarParaVenda(con, id)
                            .orElseThrow(() -> new NegocioException("O produto \"" + item.produto().nome()
                                    + "\" não existe mais.")));
                }
            }
            Set<Long> vendidos = new TreeSet<>(); // ordem do id: trava os saldos sempre na mesma ordem
            carrinho.itens().stream().filter(i -> !i.cancelado()).forEach(i -> vendidos.add(i.produto().id()));
            for (long id : vendidos) {
                ProdutoVenda atual = produtos.get(id);
                if (!atual.ativo()) {
                    throw new NegocioException("O produto \"" + atual.nome() + "\" foi inativado e não pode ser "
                            + "vendido. Cancele o item (F6).");
                }
                if (!parametros.permitirEstoqueNegativo()) {
                    BigDecimal saldo = estoqueDAO.travarSaldo(con, id).orElse(BigDecimal.ZERO);
                    BigDecimal naVenda = carrinho.quantidadeDoProduto(id);
                    if (saldo.compareTo(naVenda) < 0) {
                        throw new NegocioException("Estoque insuficiente de \"" + atual.nome() + "\": saldo "
                                + quantidade(saldo, atual) + ", na venda " + quantidade(naVenda, atual) + ".");
                    }
                }
            }

            // O cliente pode ter sido inativado ou anonimizado em outro computador depois da identificação.
            Long clienteId = null;
            if (carrinho.cliente().isPresent()) {
                clienteId = clienteAtivo(con, carrinho.cliente().get().id()).id();
            }
            // Crediário (F14.2): cliente travado antes do saldo de crédito, na mesma ordem da anonimização.
            BigDecimal crediario = recebimento.crediarioUsado();
            List<CalculoCrediario.Parcela> parcelas = List.of();
            if (crediario.signum() > 0) {
                if (clienteId == null) {
                    throw new NegocioException("Para vender no crediário, identifique o cliente (F11).");
                }
                parcelas = parcelas(recebimento.planoCrediario(), crediario, con);
                conferirCrediario(con, conferida, clienteId, crediario, recebimento.liberacaoCrediario());
            }
            // Crédito do cliente (F12.4): o saldo é conferido com a linha travada (pode ter mudado noutro caixa).
            BigDecimal creditoUsado = recebimento.creditoUsado();
            if (creditoUsado.signum() > 0) {
                if (clienteId == null) {
                    throw new NegocioException("Para pagar com o crédito do cliente, identifique o cliente (F11).");
                }
                BigDecimal saldo = creditoDAO.travarSaldo(con, clienteId).orElse(Dinheiro.ZERO);
                if (saldo.compareTo(creditoUsado) < 0) {
                    throw new NegocioException("Saldo de crédito insuficiente: o cliente tem "
                            + Formatos.moeda(saldo.max(Dinheiro.ZERO)) + " e o pagamento usa "
                            + Formatos.moeda(creditoUsado) + ". Refaça o pagamento.");
                }
            }

            Map<Integer, BigDecimal> rateio = carrinho.rateio();
            BigDecimal troco = recebimento.troco();
            long vendaId = vendaDAO.inserir(con, caixa.id(), sessao.usuarioId(), agora, new VendaDAO.Totais(
                    carrinho.valorBruto(), carrinho.descontoItens(), carrinho.descontoVenda(), total, troco),
                    descontoLiberadoPor, clienteId);

            record Vendido(ItemCarrinho item, long itemId) {
            }
            List<Vendido> movimentar = new ArrayList<>();
            for (ItemCarrinho item : carrinho.itens()) {
                BigDecimal custo = produtos.get(item.produto().id()).custo();
                BigDecimal rateado = item.cancelado() ? Dinheiro.ZERO : rateio.get(item.sequencia());
                BigDecimal liquido = item.valorBruto().subtract(item.desconto()).subtract(rateado);
                ItemCarrinho.Cancelamento c = item.cancelamento();
                long itemId = vendaDAO.inserirItem(con, vendaId, new VendaDAO.Item(item.sequencia(),
                        item.produto().id(), item.quantidade(), item.precoUnitario(), item.valorBruto(),
                        item.desconto(), rateado, liquido, custo, Dinheiro.multiplicar(item.quantidade(), custo),
                        item.promocaoId(), item.cancelado(), c == null ? null : c.em(),
                        c == null ? null : c.porUsuarioId(), c == null ? null : c.liberadoPorUsuarioId(),
                        item.faixa() == null ? null : item.faixa().id(),
                        item.faixa() == null ? null : item.faixa().quantidadeMinima()));
                if (!item.cancelado()) {
                    movimentar.add(new Vendido(item, itemId));
                }
            }
            tributos.registrar(con, vendaId, LocalDate.now(clock)); // F22.2: só registra, a venda não muda
            Map<Long, Recebimento.Parcela> pagamentosGravados = new LinkedHashMap<>();
            for (Recebimento.Parcela p : pagamentos) {
                pagamentosGravados.put(vendaDAO.inserirPagamento(con, vendaId, p.forma().id(), p.valor()), p);
            }
            movimentar.sort(Comparator.comparingLong((Vendido v) -> v.item().produto().id())
                    .thenComparingInt(v -> v.item().sequencia()));
            for (Vendido v : movimentar) {
                estoqueService.movimentar(con, sessao, v.item().produto().id(), TipoMovimento.VENDA,
                        v.item().quantidade().negate(), OrigemMovimento.vendaItem(v.itemId()), null, null);
            }
            BigDecimal cashback = Dinheiro.ZERO;
            if (clienteId != null) {
                if (creditoUsado.signum() > 0) {
                    creditoService.movimentar(con, sessao, clienteId, TipoCreditoCliente.USO_EM_VENDA,
                            creditoUsado.negate(), vendaId, null);
                }
                // Cashback (F12.5 e F14.2) sobre o que não foi pago com crédito nem ficou no crediário.
                cashback = configuracaoDAO.carregar(con).fidelidade()
                        .cashback(total.subtract(creditoUsado).subtract(crediario));
                if (cashback.signum() > 0) {
                    creditoService.movimentar(con, sessao, clienteId, TipoCreditoCliente.CASHBACK, cashback,
                            vendaId, null);
                }
                LocalDate emissao = LocalDate.now(clock);
                for (CalculoCrediario.Parcela p : parcelas) {
                    tituloDAO.inserir(con, clienteId, vendaId, p.numero(), parcelas.size(), emissao, p.vencimento(),
                            p.valor(), agora, sessao.usuarioId());
                }
            }
            // Recebíveis (F15.2) por último: o saldo da conta é a última trava da venda.
            for (Map.Entry<Long, Recebimento.Parcela> p : pagamentosGravados.entrySet()) {
                recebivelService.gerarDaVenda(con, sessao, p.getKey(), vendaId, p.getValue().forma().id(),
                        p.getValue().valor(), agora);
            }

            if (acimaDoLimite) {
                auditoria.registrar(con, sessao, AcaoAuditoria.DESCONTO_LIBERADO, ENTIDADE_VENDA, vendaId,
                        "Desconto de " + Formatos.moeda(carrinho.descontos()) + " na venda nº " + vendaId + " ("
                                + Formatos.percentual(carrinho.percentualDesconto()) + " do valor de "
                                + Formatos.moeda(carrinho.valorBruto()) + "), acima do limite de "
                                + Formatos.percentual(parametros.descontoMaximoPct()) + " do operador ("
                                + (liberacaoDesconto == null ? "permissão de " + sessao.usuario().descricao()
                                : liberacaoDesconto.descricao()) + ")");
            }
            return new VendaFinalizada(vendaId, total, troco, cashback, montarComprovante(con, vendaId));
        });
    }

    /** Parcelas do plano (F14.2): de 1 a {@code crediario.max_parcelas}; a 1ª vence de hoje em diante. */
    private List<CalculoCrediario.Parcela> parcelas(PlanoCrediario plano, BigDecimal valor, Connection con)
            throws SQLException {
        if (plano == null || plano.primeiroVencimento() == null) {
            throw new NegocioException("Informe as parcelas e o vencimento da 1ª parcela do crediário.");
        }
        int maximo = configuracaoDAO.carregar(con).crediario().maxParcelas();
        if (plano.parcelas() < 1 || plano.parcelas() > maximo) {
            throw new NegocioException("O crediário pode ter de 1 a " + maximo + " parcelas.");
        }
        if (plano.primeiroVencimento().isBefore(LocalDate.now(clock))) {
            throw new NegocioException("O vencimento da 1ª parcela não pode ser anterior a hoje.");
        }
        BigDecimal centavos = Dinheiro.dinheiro(new BigDecimal("0.01")).multiply(BigDecimal.valueOf(plano.parcelas()));
        if (valor.compareTo(centavos) < 0) {
            throw new NegocioException("O valor no crediário (" + Formatos.moeda(valor) + ") não dá para "
                    + plano.parcelas() + " parcelas.");
        }
        return CalculoCrediario.parcelar(valor, plano.parcelas(), plano.primeiroVencimento());
    }

    /**
     * Regras do cliente no crediário (F14.2), com a linha dele travada: ativo e não bloqueado; passar do limite ou ter
     * título vencido há mais de {@code crediario.bloqueio_atraso_dias} exige LIMITE_CREDITO ou a liberação
     * ({@link CrediarioLiberacaoException} sem ela), com auditoria depois que a venda tem número.
     */
    private void conferirCrediario(Connection con, Sessao sessao, long clienteId, BigDecimal valor,
                                   Liberacao liberacao) throws SQLException {
        ClienteCrediario cliente = clienteDAO.travarParaCrediario(con, clienteId)
                .orElseThrow(() -> new NegocioException("Cliente não encontrado."));
        if (!cliente.ativo()) {
            throw new NegocioException("O cliente \"" + cliente.nome() + "\" está inativo e não pode comprar no "
                    + "crediário.");
        }
        if (cliente.bloqueado()) {
            throw new NegocioException("O crediário do cliente \"" + cliente.nome() + "\" está bloqueado. Escolha "
                    + "outra forma de pagamento.");
        }
        BigDecimal disponivel = cliente.limiteCredito().subtract(tituloDAO.emAbertoDoCliente(con, clienteId));
        int atrasoMaximo = configuracaoDAO.carregar(con).crediario().bloqueioAtrasoDias();
        List<String> motivos = new ArrayList<>();
        if (valor.compareTo(disponivel) > 0) {
            motivos.add("o valor no crediário (" + Formatos.moeda(valor) + ") passa do limite disponível ("
                    + Formatos.moeda(disponivel.max(Dinheiro.ZERO)) + ")");
        }
        if (tituloDAO.temVencidoAntesDe(con, clienteId, LocalDate.now(clock).minusDays(atrasoMaximo))) {
            motivos.add("o cliente tem título vencido há mais de " + atrasoMaximo + " dias");
        }
        if (motivos.isEmpty()) {
            return;
        }
        String motivo = String.join(" e ", motivos);
        if (!sessao.pode(Permissao.LIMITE_CREDITO) && liberacao == null) {
            throw new CrediarioLiberacaoException("Crediário de \"" + cliente.nome() + "\": " + motivo + ". É preciso "
                    + "a liberação de quem tem a permissão \"" + Permissao.LIMITE_CREDITO.rotulo() + "\".");
        }
        Liberacao liberou = sessao.exigirOuLiberado(Permissao.LIMITE_CREDITO, liberacao);
        auditoria.registrar(con, sessao, AcaoAuditoria.CREDIARIO_LIBERADO, ENTIDADE_CLIENTE, clienteId,
                "Crediário do cliente nº " + clienteId + ": " + motivo + " ("
                        + (liberou == null ? "permissão de " + sessao.usuario().descricao() : liberou.descricao())
                        + ")");
    }

    // ----- Vendas da sessão (F9) -----

    /** Vendas da sessão de caixa aberta neste terminal (vazio sem caixa aberto), a mais recente primeiro. */
    public List<VendaResumo> vendasDaSessao(Sessao sessao) {
        sessao.exigir(Permissao.PDV);
        return transacao.executar(con -> {
            Optional<CaixaSessao> caixa = caixaService.caixaAberto(con, sessao);
            return caixa.isEmpty() ? List.<VendaResumo>of() : vendaDAO.listarDaSessao(con, caixa.get().id());
        });
    }

    /**
     * Cancela uma venda finalizada da sessão aberta (6.6): CANCELAR_VENDA ou liberação; motivo obrigatório. Numa
     * transação: status {@code CANCELADA} com quem autorizou, quem executou (F10.3), quando e o motivo;
     * {@code CANCELAMENTO_VENDA} para cada item não cancelado; {@code ESTORNO_USO} e {@code ESTORNO_CASHBACK} do
     * crédito do cliente (os estornos podem deixar o saldo negativo); auditoria com o operador e quem liberou. Venda com
     * devolução (F13) não pode mais ser cancelada.
     */
    public void cancelar(Sessao sessao, long vendaId, String motivo, Liberacao liberacao) {
        sessao.exigir(Permissao.PDV);
        Liberacao liberou = sessao.exigirOuLiberado(Permissao.CANCELAR_VENDA, liberacao);
        String motivoFinal = Textos.normalizarEspacos(motivo);
        if (motivoFinal.isEmpty()) {
            throw new NegocioException("Informe o motivo do cancelamento.");
        }
        if (motivoFinal.length() > CaixaService.TAMANHO_MAXIMO_MOTIVO) {
            throw new NegocioException("O motivo pode ter no máximo " + CaixaService.TAMANHO_MAXIMO_MOTIVO
                    + " caracteres.");
        }
        OffsetDateTime agora = OffsetDateTime.now(clock);
        transacao.executar(con -> {
            CaixaSessao caixa = caixaService.travarAberta(con, sessao);
            VendaDAO.VendaTravada venda = vendaDAO.travar(con, vendaId)
                    .orElseThrow(() -> new NegocioException("Venda nº " + vendaId + " não encontrada."));
            if (venda.caixaSessaoId() != caixa.id()) {
                throw new NegocioException("Só é possível cancelar vendas do caixa aberto neste terminal (a venda nº "
                        + vendaId + " é do caixa nº " + venda.caixaSessaoId() + ").");
            }
            if (venda.status() == StatusVenda.CANCELADA) {
                throw new NegocioException("A venda nº " + vendaId + " já está cancelada.");
            }
            if (vendaDAO.temDevolucao(con, vendaId)) {
                throw new NegocioException("A venda nº " + vendaId + " tem devolução e não pode mais ser cancelada. "
                        + "Para desfazê-la por inteiro, devolva os itens que faltam (Devolver itens).");
            }
            // Crediário (F14.2): só sem pagamento em nenhuma parcela; os títulos são cancelados junto.
            List<TituloReceber> titulos = tituloDAO.daVenda(con, vendaId, true);
            if (titulos.stream().anyMatch(t -> t.valorPago().signum() > 0)) {
                throw new NegocioException("A venda nº " + vendaId + " tem parcela do crediário com pagamento e não "
                        + "pode ser cancelada.");
            }
            tituloDAO.cancelarDaVenda(con, vendaId, motivoFinal);
            long autorizou = liberou == null ? sessao.usuarioId() : liberou.usuarioId();
            vendaDAO.cancelar(con, vendaId, agora, autorizou, sessao.usuarioId(), motivoFinal);
            List<VendaDAO.ItemVendido> itens = new ArrayList<>(vendaDAO.itensVendidos(con, vendaId));
            itens.sort(Comparator.comparingLong(VendaDAO.ItemVendido::produtoId));
            for (VendaDAO.ItemVendido item : itens) {
                estoqueService.movimentar(con, sessao, item.produtoId(), TipoMovimento.CANCELAMENTO_VENDA,
                        item.quantidade(), OrigemMovimento.vendaItem(item.id()), null, null);
            }
            // Crédito do cliente (F12.4 e F12.5): devolve o crédito usado e retira o cashback gerado.
            List<String> estornos = new ArrayList<>();
            for (CreditoClienteDAO.DaVenda m : creditoDAO.daVenda(con, vendaId)) {
                TipoCreditoCliente estorno = switch (m.tipo()) {
                    case USO_EM_VENDA -> TipoCreditoCliente.ESTORNO_USO;
                    case CASHBACK -> TipoCreditoCliente.ESTORNO_CASHBACK;
                    default -> null;
                };
                if (estorno != null) {
                    creditoService.movimentar(con, sessao, m.clienteId(), estorno, m.valor().negate(), vendaId, null);
                    estornos.add(estorno.rotulo().toLowerCase() + " de " + Formatos.moeda(m.valor().abs()));
                }
            }
            recebivelService.cancelarDaVenda(con, sessao, vendaId, agora);
            auditoria.registrar(con, sessao, AcaoAuditoria.VENDA_CANCELADA, ENTIDADE_VENDA, vendaId,
                    "Venda nº " + vendaId + " de " + Formatos.moeda(venda.valorTotal()) + " cancelada no caixa nº "
                            + caixa.id() + ". Motivo: " + motivoFinal
                            + (liberou == null ? "" : " (" + liberou.descricao() + ")")
                            + (estornos.isEmpty() ? "" : ". Crédito do cliente: " + String.join(" e ", estornos))
                            + (titulos.isEmpty() ? "" : ". " + titulos.size() + " título(s) do crediário cancelado(s)"));
            return null;
        });
    }

    /** Comprovante não fiscal de uma venda (reimpressão pelo F9). */
    public Comprovante comprovante(Sessao sessao, long vendaId) {
        sessao.exigir(Permissao.PDV);
        return transacao.executar(con -> montarComprovante(con, vendaId));
    }

    /**
     * Segunda via do comprovante de qualquer venda, pela busca global (F21.3): para quem tem PDV, RELATORIOS ou
     * DEVOLUCAO. Só mostra: não muda nada nem grava auditoria.
     */
    public Comprovante segundaVia(Sessao sessao, long vendaId) {
        sessao.exigirAlguma(Permissao.PDV, Permissao.RELATORIOS, Permissao.DEVOLUCAO);
        return transacao.executar(con -> {
            if (vendaDAO.cabecalho(con, vendaId).isEmpty()) {
                throw new NegocioException("Venda nº " + vendaId + " não encontrada.");
            }
            return montarComprovante(con, vendaId);
        });
    }

    // ----- Apoio -----

    /** Código de barras exato → código interno → etiqueta de balança (com a balança ativa) → não encontrado. */
    private LeituraProduto buscarPorCodigo(Sessao sessao, String digitado) {
        sessao.exigir(Permissao.PDV);
        String codigo = Textos.normalizarEspacos(digitado);
        if (!Textos.soDigitos(codigo)) {
            throw new NegocioException("Leia o código de barras ou digite o código do produto.");
        }
        return transacao.executar(con -> {
            Optional<ProdutoVenda> porCodigoBarras = produtoDAO.buscarParaVendaPorCodigo(con, codigo);
            if (porCodigoBarras.isPresent()) {
                Embalagem embalagem = produtoDAO.embalagemDoCodigo(con, codigo)
                        .filter(Embalagem::ehEmbalagem).orElse(null);
                return Optional.of(new LeituraProduto(porCodigoBarras.get(), null, embalagem));
            }
            Optional<ProdutoVenda> porCodigo = produtoDAO.buscarParaVenda(con, ProdutoDAO.idOuNenhum(codigo));
            if (porCodigo.isPresent()) {
                return Optional.of(new LeituraProduto(porCodigo.get(), null));
            }
            Configuracoes.Balanca balanca = configuracaoDAO.carregar(con).balanca();
            if (!balanca.ativo()) {
                return Optional.<LeituraProduto>empty();
            }
            Optional<EtiquetaBalanca> etiqueta = EtiquetaBalanca.reconhecer(codigo, balanca);
            if (etiqueta.isEmpty()) {
                return Optional.<LeituraProduto>empty();
            }
            ProdutoVenda produto = produtoDAO.buscarParaVendaPorPlu(con, etiqueta.get().plu(), codigo)
                    .orElseThrow(() -> new NegocioException("Produto de balança não encontrado (PLU "
                            + etiqueta.get().plu() + ")."));
            return Optional.of(new LeituraProduto(produto, etiqueta.get()));
        }).orElseThrow(() -> new NegocioException("Produto não encontrado (código " + codigo + ")."));
    }

    private static void validarVendavel(ProdutoVenda produto) {
        if (!produto.ativo()) {
            throw new NegocioException("O produto \"" + produto.nome() + "\" está inativo e não pode ser vendido.");
        }
        if (produto.precoVenda().signum() <= 0) {
            throw new NegocioException("O produto \"" + produto.nome() + "\" está sem preço de venda e não pode ser "
                    + "vendido.");
        }
    }

    private void conferirLimite(Sessao sessao, Carrinho carrinho, Liberacao liberacao) {
        ParametrosVenda parametros = transacao.executar(this::parametros);
        if (!precisaLiberacao(sessao.conferida(), carrinho, parametros)) {
            return;
        }
        if (liberacao == null) {
            throw new LimiteDescontoException(mensagemLimite(carrinho, parametros));
        }
        sessao.exigirOuLiberado(Permissao.DESCONTO_ACIMA_LIMITE, liberacao);
        carrinho.registrarLiberacaoDesconto(liberacao);
    }

    private static boolean precisaLiberacao(Sessao sessao, Carrinho carrinho, ParametrosVenda parametros) {
        return !carrinho.dentroDoLimite(parametros.descontoMaximoPct())
                && !sessao.pode(Permissao.DESCONTO_ACIMA_LIMITE)
                && carrinho.liberacaoDescontoValida().isEmpty();
    }

    private static String mensagemLimite(Carrinho carrinho, ParametrosVenda parametros) {
        return "O desconto total da venda (" + Formatos.percentual(carrinho.percentualDesconto())
                + " do valor) passa do limite de " + Formatos.percentual(parametros.descontoMaximoPct())
                + " do operador. É preciso a liberação de quem tem a permissão \""
                + Permissao.DESCONTO_ACIMA_LIMITE.rotulo() + "\".";
    }

    /** Desconto informado em reais (até 2 casas) ou percentual (0 a 100, até 2 casas), convertido em reais. */
    static BigDecimal valorDesconto(TipoDesconto tipo, BigDecimal informado, BigDecimal base) {
        if (tipo == null) {
            throw new NegocioException("Escolha o tipo de desconto: R$ ou %.");
        }
        if (tipo == TipoDesconto.VALOR) {
            return CaixaService.valor(informado, "o desconto", true);
        }
        if (informado == null) {
            throw new NegocioException("Informe o desconto.");
        }
        if (informado.signum() < 0 || informado.compareTo(CEM) > 0) {
            throw new NegocioException("O percentual de desconto deve estar entre 0 e 100.");
        }
        if (ValidacaoProduto.casas(informado) > Dinheiro.ESCALA_PERCENTUAL) {
            throw new NegocioException("O percentual pode ter no máximo " + Dinheiro.ESCALA_PERCENTUAL
                    + " casas decimais.");
        }
        return tipo.emReais(informado, base);
    }

    ParametrosVenda parametros(Connection con) throws SQLException {
        return configuracaoDAO.carregar(con).venda();
    }

    private Comprovante montarComprovante(Connection con, long vendaId) throws SQLException {
        VendaDAO.Cabecalho c = vendaDAO.cabecalho(con, vendaId)
                .orElseThrow(() -> new NegocioException("Venda nº " + vendaId + " não encontrada."));
        List<VendaDAO.PagamentoGravado> gravados = vendaDAO.pagamentos(con, vendaId);
        int ultimoDinheiro = -1;
        for (int i = 0; i < gravados.size(); i++) {
            if (gravados.get(i).tipo() == TipoFormaPagamento.DINHEIRO) {
                ultimoDinheiro = i;
            }
        }
        List<Comprovante.Pagamento> pagamentos = new ArrayList<>();
        for (int i = 0; i < gravados.size(); i++) {
            VendaDAO.PagamentoGravado g = gravados.get(i);
            // No comprovante, o dinheiro aparece como foi entregue: valor abatido + troco.
            BigDecimal valor = i == ultimoDinheiro ? g.valor().add(c.totais().troco()) : g.valor();
            pagamentos.add(new Comprovante.Pagamento(g.forma(), valor));
        }
        Configuracoes config = configuracaoDAO.carregar(con);
        Comprovante.Loja loja = new Comprovante.Loja(config.loja().nome(), config.loja().documento(),
                config.loja().endereco(), config.loja().telefone());
        // Cashback e saldo (F12.5): com a fidelidade ligada ou quando a venda mexeu no crédito do cliente.
        Comprovante.Credito credito = null;
        if (c.clienteId() != null) {
            List<CreditoClienteDAO.DaVenda> movimentos = creditoDAO.daVenda(con, vendaId);
            if (config.fidelidade().ativo() || !movimentos.isEmpty()) {
                BigDecimal cashback = movimentos.stream().filter(m -> m.tipo() == TipoCreditoCliente.CASHBACK)
                        .map(CreditoClienteDAO.DaVenda::valor).findFirst().orElse(Dinheiro.ZERO);
                credito = new Comprovante.Credito(cashback,
                        creditoDAO.saldo(con, c.clienteId()).orElse(Dinheiro.ZERO));
            }
        }
        return new Comprovante(loja, c.id(), c.dataHora(), c.operador(), c.terminal(), c.status(), c.canceladaEm(),
                c.motivoCancelamento(), vendaDAO.itensComprovante(con, vendaId), c.totais().valorBruto(),
                c.totais().descontoItens(), c.totais().descontoVenda(), c.totais().valorTotal(), pagamentos,
                c.totais().troco(), config.rodapeComprovante(), c.clienteNome() == null ? null
                : new Comprovante.Cliente(c.clienteNome(), CpfCnpj.ocultar(c.clienteCpfCnpj())), credito,
                tituloDAO.daVenda(con, vendaId, false).stream().filter(t -> t.status() != StatusTitulo.CANCELADO)
                        .map(t -> new Comprovante.ParcelaCrediario(t.parcela(), t.totalParcelas(), t.vencimento(),
                                t.valor())).toList());
    }

    private static String descrever(ItemCarrinho item) {
        return quantidade(item.quantidade(), item.produto()) + " x " + item.produto().nome() + " ("
                + Formatos.moeda(item.valorBruto()) + ")";
    }

    private static String quantidade(BigDecimal valor, ProdutoVenda produto) {
        return Formatos.quantidade(valor, produto.fracionada()) + " " + produto.unidade();
    }
}
