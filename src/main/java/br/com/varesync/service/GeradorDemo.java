package br.com.varesync.service;

import br.com.varesync.dao.CaixaDAO;
import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.DemoDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.CaixaSessao;
import br.com.varesync.model.Categoria;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.DadosCliente;
import br.com.varesync.model.DadosDevolucao;
import br.com.varesync.model.DadosEntrada;
import br.com.varesync.model.DadosFornecedor;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.FormaReembolso;
import br.com.varesync.model.ItemContagem;
import br.com.varesync.model.ItemDevolucao;
import br.com.varesync.model.MotivoPerda;
import br.com.varesync.model.PlanoCrediario;
import br.com.varesync.model.PlanoRecebimento;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.Produto;
import br.com.varesync.model.ProdutoVenda;
import br.com.varesync.model.TipoAjuste;
import br.com.varesync.model.TipoCaixaMovimento;
import br.com.varesync.model.TipoConta;
import br.com.varesync.model.TipoDesconto;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.model.TituloEmAberto;
import br.com.varesync.model.VendaDevolucao;
import br.com.varesync.service.CatalogoDemo.FornecedorDemo;
import br.com.varesync.service.CatalogoDemo.ItemCatalogo;
import br.com.varesync.util.CalculoCrediario;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.RelogioAjustavel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gerador de dados de demonstração (6.14): só para o supervisor e só num banco sem vendas. Com a semente fixa, o
 * resultado é sempre o mesmo (a não ser pelas datas, que terminam ontem).
 *
 * <p>Tudo passa pelos próprios services (regras, auditoria e triggers valendo), montados sobre um
 * {@link RelogioAjustavel} que o gerador posiciona em cada instante do passado: cadastros na véspera do primeiro
 * dia, depois {@value #DIAS} dias de operação com um caixa por dia.
 *
 * <p>Primeiro o gerador planeja tudo em memória (vendas, itens, perdas e, desde a fase 7, as compras); a demanda
 * planejada define o estoque inicial de cada produto e a quantidade de cada entrada semanal dos fornecedores (a
 * demanda dos 7 dias seguintes, com o custo variando um pouco para cima), para que o saldo não fique negativo.
 * Depois executa o plano. As devoluções (F13: cerca de 0,5% dos itens) são sorteadas depois de cada venda, com uma
 * semente própria, e feitas de 1 a 5 dias depois dela, com o caixa do dia aberto. Como cada service grava na própria
 * transação, uma falha no meio deixa o que já foi gravado; nesse caso, o banco deve ser recriado (README).
 */
public class GeradorDemo {

    public static final long SEMENTE = 20_261_017L;
    public static final int DIAS = 60;
    static final BigDecimal FUNDO_DE_TROCO = new BigDecimal("150.00");
    private static final BigDecimal CEM = BigDecimal.valueOf(100);
    private static final int[] PESO_HORA = {3, 4, 5, 9, 10, 8, 4, 4, 5, 9, 10, 7}; // 8h às 19h
    private static final int PRIMEIRA_HORA = 8;
    private static final int DIA_REAJUSTE_1 = 20;
    private static final int DIA_REAJUSTE_2 = 45;
    private static final int DIA_INVENTARIO = 40;
    private static final List<String> CATEGORIAS_INVENTARIO = List.of("Limpeza", "Higiene e perfumaria");

    /** Usuário criado, com a senha provisória (trocada no primeiro login). */
    public record UsuarioDemo(String login, String nome, String perfil, String senha) {
        @Override
        public String toString() {
            return "UsuarioDemo[" + login + ", " + perfil + "]"; // sem a senha (vai para o log)
        }
    }

    public record Resultado(List<UsuarioDemo> usuarios, int produtos, int vendas, int vendasCanceladas,
                            int itensCancelados, LocalDate inicio, LocalDate fim, long inventarioId,
                            int fornecedores, int entradas, int clientes, int vendasComCliente, int usosDeCredito,
                            int devolucoes, int clientesComLimite, int vendasCrediario, int recebimentos,
                            int titulosVencidos) {
    }

    /** Crediário (F14.4): 30 clientes com limite; deles, parte das vendas identificadas vai a prazo. */
    static final int CLIENTES_COM_LIMITE = 30;
    static final int PCT_VENDA_A_PRAZO = 30;
    /** Pagamento da parcela: em dia, com atraso; o que sobra fica em aberto (alguns títulos vencidos). */
    static final int PCT_PAGA_EM_DIA = 65;
    static final int PCT_PAGA_COM_ATRASO = 25;
    static final int DIAS_ATE_PRIMEIRA_DEMO = 15;

    /** Recebimento sorteado para um dia: a parcela da venda e, em dinheiro ou PIX. */
    private record RecebimentoPlano(long clienteId, long vendaId, int parcela, boolean emDinheiro) {
    }

    /** Itens devolvidos, por mil itens vendidos (F13.4: cerca de 0,5%). */
    static final int POR_MIL_ITENS_DEVOLVIDOS = 5;
    /** A devolução acontece de 1 a este número de dias depois da venda (dentro do prazo de 30 dias). */
    static final int DIAS_ATE_DEVOLVER = 5;
    private static final List<String> MOTIVOS_DEVOLUCAO = List.of("Produto com defeito", "Cliente desistiu",
            "Produto errado", "Embalagem violada", "Comprou a mais");

    /**
     * Devolução sorteada depois de uma venda: os itens (posição na venda), quais voltam avariados, a forma pedida e o
     * motivo. Dinheiro acima do esperado do caixa vira {@code OUTRO}; vale-troca só em venda com cliente.
     */
    private record DevolucaoPlano(long vendaId, LocalTime hora, List<Integer> itens, List<Boolean> avariados,
                                  FormaReembolso forma, String motivo) {
    }

    /** Parte das vendas com cliente identificado (F12.8: cerca de 40%) e, delas, as que tentam usar o crédito. */
    static final int PCT_VENDAS_COM_CLIENTE = 40;
    static final int PCT_USA_CREDITO = 25;
    /** Só usa o crédito a partir deste saldo (alguns centavos de cashback não valem a parcela). */
    static final BigDecimal CREDITO_MINIMO_PARA_USAR = new BigDecimal("1.00");

    /** Andamento, para a barra de progresso da tela. */
    @FunctionalInterface
    public interface Progresso {
        void informar(long feito, long total, String mensagem);
    }

    private enum Forma { DINHEIRO, PIX, DEBITO, CREDITO, VALE, PIX_E_DINHEIRO, CREDITO_CLIENTE, CREDIARIO }

    /** {@code crediario.bloqueio_atraso_dias} padrão da V10: o gerador não muda essa configuração. */
    private static final int ATRASO_QUE_PEDE_LIBERACAO = 30;

    private record UsuarioPlano(String login, String nome, boolean gerente) {
    }

    private static final List<UsuarioPlano> USUARIOS = List.of(
            new UsuarioPlano("maria", "Maria Oliveira", false),
            new UsuarioPlano("jose", "José Santos", false),
            new UsuarioPlano("carlos", "Carlos Pereira", true));

    private record ItemPlano(int produto, BigDecimal quantidade, boolean cancelar) {
    }

    private record VendaPlano(LocalTime hora, List<ItemPlano> itens, Forma forma, int descontoVendaPct,
                              boolean descontoItem, boolean cancelar) {
    }

    private record PerdaPlano(int produto, BigDecimal quantidade, MotivoPerda motivo) {
    }

    private record DiaPlano(int numero, LocalDate data, int operadorManha, int operadorTarde,
                            List<VendaPlano> vendas, List<PerdaPlano> perdas, List<LocalTime> sangrias) {
    }

    /** Linha da nota: alguns itens são lançados pelo valor total da linha, em vez do custo unitário. */
    private record ItemCompra(int produto, BigDecimal quantidade, BigDecimal custo, boolean porValorTotal) {

        BigDecimal valorTotal() {
            return Dinheiro.multiplicar(quantidade, custo);
        }
    }

    /** Entrada semanal de um fornecedor, no dia {@code dia} (1 = primeiro dia), {@code semana} 0, 1, 2… */
    private record CompraPlano(int fornecedor, int dia, int semana, List<ItemCompra> itens) {
    }

    private final RelogioAjustavel relogio;
    private final Clock relogioReal;
    private final Transacao transacao;
    private final AcessoService acesso;
    private final UsuarioService usuarios;
    private final CategoriaService categorias;
    private final ProdutoService produtos;
    private final EstoqueService estoque;
    private final CaixaService caixa;
    private final VendaService vendas;
    private final InventarioService inventarios;
    private final FornecedorService fornecedores;
    private final EntradaService compras;
    private final ClienteService clientes;
    private final DevolucaoService devolucoes;
    private final RecebimentoService recebimentos;
    private final DemoDAO demoDAO;
    private final CaixaDAO caixaDAO;
    private final ConfiguracaoDAO configuracaoDAO;
    private final AuditoriaService auditoria;

    /** Os services recebidos devem ter sido montados sobre {@code relogio}. */
    public GeradorDemo(RelogioAjustavel relogio, Clock relogioReal, Transacao transacao, AcessoService acesso,
                       UsuarioService usuarios, CategoriaService categorias, ProdutoService produtos,
                       EstoqueService estoque, CaixaService caixa, VendaService vendas,
                       InventarioService inventarios, FornecedorService fornecedores, EntradaService compras,
                       ClienteService clientes, DevolucaoService devolucoes, RecebimentoService recebimentos,
                       DemoDAO demoDAO, CaixaDAO caixaDAO, ConfiguracaoDAO configuracaoDAO,
                       AuditoriaService auditoria) {
        this.recebimentos = recebimentos;
        this.relogio = relogio;
        this.relogioReal = relogioReal;
        this.transacao = transacao;
        this.acesso = acesso;
        this.usuarios = usuarios;
        this.categorias = categorias;
        this.produtos = produtos;
        this.estoque = estoque;
        this.caixa = caixa;
        this.vendas = vendas;
        this.inventarios = inventarios;
        this.fornecedores = fornecedores;
        this.compras = compras;
        this.clientes = clientes;
        this.devolucoes = devolucoes;
        this.demoDAO = demoDAO;
        this.caixaDAO = caixaDAO;
        this.configuracaoDAO = configuracaoDAO;
        this.auditoria = auditoria;
    }

    /**
     * Confere se o banco aceita os dados de demonstração, sem gravar nada.
     *
     * @throws NegocioException com o motivo (já há vendas, caixa aberto, login ou código de barras em uso)
     */
    public void conferir(Sessao sessao) {
        sessao.exigirSupervisor();
        List<ItemCatalogo> catalogo = CatalogoDemo.itens();
        transacao.executar(con -> {
            long vendasGravadas = demoDAO.contarVendas(con);
            if (vendasGravadas > 0) {
                throw new NegocioException("Este banco já tem " + vendasGravadas + " venda(s). Os dados de "
                        + "demonstração só podem ser gerados num banco sem vendas: crie um banco separado "
                        + "(ex.: varesync_demo, veja o README) e conecte o sistema a ele.");
            }
            List<String> logins = demoDAO.loginsExistentes(con, USUARIOS.stream().map(UsuarioPlano::login).toList());
            if (!logins.isEmpty()) {
                throw new NegocioException("Os logins " + String.join(", ", logins) + " já existem. Os dados de "
                        + "demonstração criam os usuários maria, jose e carlos: use um banco separado.");
            }
            List<String> codigos = demoDAO.codigosExistentes(con, catalogo.stream()
                    .map(ItemCatalogo::codigoBarras).filter(c -> c != null).toList());
            List<String> plus = demoDAO.plusExistentes(con, catalogo.stream()
                    .map(ItemCatalogo::plu).filter(p -> p != null).toList());
            if (!codigos.isEmpty() || !plus.isEmpty()) {
                throw new NegocioException("Já há produtos com os códigos de barras ou PLUs dos dados de "
                        + "demonstração. Use um banco separado.");
            }
            if (!demoDAO.documentosExistentes(con, CatalogoDemo.fornecedores().stream()
                    .map(FornecedorDemo::cnpj).toList()).isEmpty()) {
                throw new NegocioException("Já há fornecedores com os CNPJs dos dados de demonstração. Use um banco "
                        + "separado.");
            }
            if (!demoDAO.documentosDeClientesExistentes(con, CatalogoDemo.clientes().stream()
                    .map(CatalogoDemo.ClienteDemo::cpf).toList()).isEmpty()) {
                throw new NegocioException("Já há clientes com os CPFs dos dados de demonstração. Use um banco "
                        + "separado.");
            }
            return null;
        });
        caixa.caixaAberto(sessao).ifPresent(c -> {
            throw new NegocioException("O caixa nº " + c.id() + " deste terminal está aberto. Feche o caixa antes "
                    + "de gerar os dados de demonstração.");
        });
    }

    /** Gera os dados (6.14). Demora: a tela chama em {@code Task}. */
    public Resultado gerar(Sessao supervisor, Progresso progresso) {
        conferir(supervisor);
        Random plano = new Random(SEMENTE);
        Random execucao = new Random(SEMENTE + 1);
        List<ItemCatalogo> catalogo = CatalogoDemo.itens();
        ZoneId zona = relogioReal.getZone();
        LocalDate hoje = LocalDate.now(relogioReal);
        LocalDate inicio = hoje.minusDays(DIAS);
        List<DiaPlano> dias = planejar(plano, catalogo, inicio);
        BigDecimal[][] demandaDia = demandaPorDia(dias, catalogo.size());
        List<FornecedorDemo> fornecedoresDemo = CatalogoDemo.fornecedores();
        List<CompraPlano> comprasPlano = planejarCompras(new Random(SEMENTE + 3), catalogo, fornecedoresDemo,
                demandaDia, inicio);
        Map<Integer, List<CompraPlano>> comprasPorDia = comprasPlano.stream()
                .collect(Collectors.groupingBy(CompraPlano::dia, LinkedHashMap::new, Collectors.toList()));
        List<CatalogoDemo.ClienteDemo> clientesDemo = CatalogoDemo.clientes();
        long totalPassos = catalogo.size() + dias.stream().mapToLong(d -> d.vendas().size()).sum()
                + comprasPlano.size() + clientesDemo.size();
        long[] feito = {0};

        // Véspera do primeiro dia: balança, usuários e produtos.
        LocalDate vespera = inicio.minusDays(1);
        posicionar(vespera, LocalTime.of(8, 0), zona);
        ativarBalanca(supervisor);
        List<UsuarioDemo> criados = criarUsuarios(supervisor, new Random(SEMENTE + 2));
        Map<String, UsuarioDemo> porLogin = criados.stream()
                .collect(Collectors.toMap(UsuarioDemo::login, u -> u, (a, b) -> a, LinkedHashMap::new));
        List<Sessao> operadores = new ArrayList<>();
        for (UsuarioPlano u : USUARIOS) {
            operadores.add(acesso.entrar(u.login(), porLogin.get(u.login()).senha()));
        }
        Sessao gerente = operadores.get(2);
        String senhaGerente = porLogin.get("carlos").senha();
        Liberacao liberaCancelamento = acesso.liberar("carlos", senhaGerente, Permissao.CANCELAR_VENDA);
        Liberacao liberaDesconto = acesso.liberar("carlos", senhaGerente, Permissao.DESCONTO_ACIMA_LIMITE);

        Map<String, Long> categoriaIds = prepararCategorias(supervisor);
        posicionar(vespera, LocalTime.of(8, 20), zona);
        List<Long> fornecedorIds = new ArrayList<>();
        for (FornecedorDemo f : fornecedoresDemo) {
            fornecedorIds.add(fornecedores.criar(supervisor, new DadosFornecedor(f.razaoSocial(), f.nomeFantasia(),
                    f.cnpj(), f.telefone(), f.email(), f.contato(), f.cidade(), f.uf(),
                    "Entrega toda " + diaDaSemana(f.diaEntrega()) + " de manhã.")));
        }
        Map<Integer, BigDecimal> inicial = estoquesIniciais(catalogo, dias, demandaDia, comprasPlano);
        List<Long> produtoIds = new ArrayList<>();
        for (int i = 0; i < catalogo.size(); i++) {
            ItemCatalogo item = catalogo.get(i);
            posicionar(vespera, LocalTime.of(8, 30).plusSeconds(20L * i), zona);
            produtoIds.add(produtos.criar(supervisor, dados(item, categoriaIds, inicial.get(i))));
            progresso.informar(++feito[0], totalPassos, "Cadastrando produtos (" + (i + 1) + " de "
                    + catalogo.size() + ")");
        }
        // Clientes e fidelidade (F12.8): 150 clientes na véspera e cashback de 1% ligado.
        ligarFidelidade(supervisor);
        List<Long> clienteIds = new ArrayList<>();
        for (int i = 0; i < clientesDemo.size(); i++) {
            CatalogoDemo.ClienteDemo c = clientesDemo.get(i);
            posicionar(vespera, LocalTime.of(10, 0).plusSeconds(10L * i), zona);
            clienteIds.add(clientes.criar(supervisor, new DadosCliente(c.nome(), c.cpf(), c.telefone(), null, null,
                    null, null, null, null, null, "Ribeirão Preto", "SP", null, c.consentimento())));
            progresso.informar(++feito[0], totalPassos, "Cadastrando clientes (" + (i + 1) + " de "
                    + clientesDemo.size() + ")");
        }
        Random fidelidade = new Random(SEMENTE + 4);
        // Devoluções (F13): semente própria, para não mudar o resto dos dados de demonstração.
        Random devolucao = new Random(SEMENTE + 5);
        Map<Integer, List<DevolucaoPlano>> devolucoesPorDia = new LinkedHashMap<>();
        int devolucoesFeitas = 0;
        // Crediário (F14.4): semente própria, pela mesma razão; os clientes com limite são os primeiros do catálogo.
        Random prazo = new Random(SEMENTE + 6);
        posicionar(vespera, LocalTime.of(11, 0), zona);
        List<Long> comLimite = clienteIds.subList(0, Math.min(CLIENTES_COM_LIMITE, clienteIds.size()));
        for (long id : comLimite) {
            clientes.alterarCrediario(supervisor, id, BigDecimal.valueOf(1000L + prazo.nextInt(5) * 500L), false);
        }
        Map<Integer, List<RecebimentoPlano>> recebimentosPorDia = new LinkedHashMap<>();
        int vendasCrediario = 0;
        int recebimentosFeitos = 0;

        Map<Long, ProdutoVenda> paraVenda = new LinkedHashMap<>();
        for (int i = 0; i < produtoIds.size(); i++) {
            if (catalogo.get(i).popularidade() > 0) {
                paraVenda.put(produtoIds.get(i), vendas.produtoParaVenda(supervisor, produtoIds.get(i)));
            }
        }
        Map<Forma, List<FormaPagamento>> formas = formas(supervisor);

        int vendasFeitas = 0;
        int vendasComCliente = 0;
        int usosDeCredito = 0;
        int canceladas = 0;
        int itensCancelados = 0;
        long inventarioId = 0;
        int entradas = 0;
        for (DiaPlano dia : dias) {
            for (CompraPlano compra : comprasPorDia.getOrDefault(dia.numero(), List.of())) {
                comprar(gerente, compra, fornecedoresDemo.get(compra.fornecedor()),
                        fornecedorIds.get(compra.fornecedor()), produtoIds, dia.data(), zona);
                entradas++;
                progresso.informar(++feito[0], totalPassos, "Dia " + dia.numero() + " de " + DIAS + " ("
                        + Formatos.data(dia.data()) + "): entrada de mercadoria de "
                        + fornecedoresDemo.get(compra.fornecedor()).nomeFantasia());
            }
            if (dia.numero() == DIA_REAJUSTE_1 || dia.numero() == DIA_REAJUSTE_2) {
                posicionar(dia.data(), LocalTime.of(7, 20), zona);
                reajustarPrecos(gerente, catalogo, produtoIds, execucao, dia.numero() == DIA_REAJUSTE_1 ? 0 : 1);
            }
            if (dia.numero() == DIA_INVENTARIO) {
                posicionar(dia.data(), LocalTime.of(7, 0), zona);
                inventarioId = inventario(gerente, categoriaIds, execucao, dia.data(), zona);
            }
            for (int p = 0; p < dia.perdas().size(); p++) {
                PerdaPlano perda = dia.perdas().get(p);
                posicionar(dia.data(), LocalTime.of(7, 30).plusMinutes(p), zona);
                estoque.ajustar(gerente, produtoIds.get(perda.produto()), TipoAjuste.PERDA, perda.quantidade(),
                        perda.motivo().rotulo(), perda.motivo() == MotivoPerda.AVARIA ? "embalagem danificada"
                                : null);
            }

            Sessao manha = operadores.get(dia.operadorManha());
            Sessao tarde = operadores.get(dia.operadorTarde());
            posicionar(dia.data(), LocalTime.of(7, 45), zona);
            CaixaSessao aberto = caixa.abrir(manha, FUNDO_DE_TROCO);
            List<LocalTime> sangrias = new ArrayList<>(dia.sangrias());
            List<DevolucaoPlano> devolverHoje = new ArrayList<>(devolucoesPorDia.getOrDefault(dia.numero(),
                    List.of()));
            devolverHoje.sort((a, b) -> a.hora().compareTo(b.hora()));
            int recebimentoDoDia = 0;
            for (RecebimentoPlano devido : recebimentosPorDia.getOrDefault(dia.numero(), List.of())) {
                posicionar(dia.data(), LocalTime.of(8, 0).plusMinutes(recebimentoDoDia++), zona);
                recebimentosFeitos += receber(gerente, devido, formas) ? 1 : 0;
            }
            for (int v = 0; v < dia.vendas().size(); v++) {
                VendaPlano venda = dia.vendas().get(v);
                while (!sangrias.isEmpty() && !sangrias.get(0).isAfter(venda.hora())) {
                    posicionar(dia.data(), sangrias.remove(0), zona);
                    sangria(gerente, aberto.id());
                }
                while (!devolverHoje.isEmpty() && !devolverHoje.get(0).hora().isAfter(venda.hora())) {
                    DevolucaoPlano d = devolverHoje.remove(0);
                    posicionar(dia.data(), d.hora(), zona);
                    devolver(gerente, d, aberto.id());
                    devolucoesFeitas++;
                }
                Sessao operador = venda.hora().getHour() < 14 ? manha : tarde;
                Long clienteId = fidelidade.nextInt(100) < PCT_VENDAS_COM_CLIENTE
                        ? clienteIds.get(fidelidade.nextInt(clienteIds.size())) : null;
                boolean usarCredito = clienteId != null && fidelidade.nextInt(100) < PCT_USA_CREDITO;
                // O sorteio do prazo vem sempre, com ou sem cliente: os demais não mudam quando as regras mudarem.
                int sorteioPrazo = prazo.nextInt(100);
                int parcelas = 1 + prazo.nextInt(3);
                boolean aPrazo = clienteId != null && comLimite.contains(clienteId) && !venda.cancelar()
                        && sorteioPrazo < PCT_VENDA_A_PRAZO;
                Venda feita = vender(operador, venda, produtoIds, paraVenda, formas, execucao, dia.data(), zona,
                        liberaCancelamento, liberaDesconto, clienteId, usarCredito, aPrazo ? parcelas : 0);
                if (feita.parcelas() > 0) {
                    vendasCrediario++;
                    planejarRecebimentos(prazo, recebimentosPorDia, dia.numero(), clienteId, feita.vendaId(),
                            feita.parcelas());
                }
                vendasFeitas++;
                vendasComCliente += clienteId == null ? 0 : 1;
                usosDeCredito += feita.usouCredito() ? 1 : 0;
                itensCancelados += feita.itensCancelados();
                if (venda.cancelar()) {
                    posicionar(dia.data(), venda.hora().plusMinutes(3), zona);
                    vendas.cancelar(operador, feita.vendaId(), execucao.nextBoolean()
                            ? "Cliente desistiu da compra" : "Erro na forma de pagamento", liberaCancelamento);
                    canceladas++;
                } else {
                    int diaDevolucao = dia.numero() + 1 + devolucao.nextInt(DIAS_ATE_DEVOLVER);
                    DevolucaoPlano d = planejarDevolucao(devolucao, venda, feita.vendaId(), clienteId != null);
                    if (d != null && diaDevolucao <= DIAS) {
                        devolucoesPorDia.computeIfAbsent(diaDevolucao, k -> new ArrayList<>()).add(d);
                    }
                }
                progresso.informar(++feito[0], totalPassos, "Dia " + dia.numero() + " de " + DIAS + " ("
                        + Formatos.data(dia.data()) + "): venda " + (v + 1) + " de "
                        + dia.vendas().size());
            }
            for (DevolucaoPlano d : devolverHoje) {
                posicionar(dia.data(), d.hora(), zona);
                devolver(gerente, d, aberto.id());
                devolucoesFeitas++;
            }
            posicionar(dia.data(), LocalTime.of(20, 10), zona);
            caixa.fechar(tarde, contado(aberto.id(), execucao));
        }

        relogio.ajustar(relogioReal.instant());
        int titulosVencidos = comLimite.stream().mapToInt(id -> (int) recebimentos.titulos(supervisor, id).stream()
                .filter(t -> t.diasAtraso() > 0).count()).sum();
        Resultado resultado = new Resultado(criados, catalogo.size(), vendasFeitas, canceladas, itensCancelados,
                inicio, hoje.minusDays(1), inventarioId, fornecedorIds.size(), entradas, clienteIds.size(),
                vendasComCliente, usosDeCredito, devolucoesFeitas, comLimite.size(), vendasCrediario,
                recebimentosFeitos, titulosVencidos);
        transacao.executar(con -> {
            auditoria.registrar(con, supervisor, AcaoAuditoria.DADOS_DEMO_GERADOS, null, null,
                    "Dados de demonstração gerados (semente " + SEMENTE + "): " + resultado.produtos()
                            + " produtos, usuários " + criados.stream().map(UsuarioDemo::login)
                            .collect(Collectors.joining(", ")) + ", " + resultado.vendas() + " vendas ("
                            + resultado.vendasCanceladas() + " canceladas, " + resultado.vendasComCliente()
                            + " com cliente, " + resultado.vendasCrediario() + " a prazo), " + resultado.recebimentos()
                            + " recebimentos, " + resultado.devolucoes() + " devoluções, " + resultado.clientes()
                            + " clientes, " + resultado.fornecedores()
                            + " fornecedores e " + resultado.entradas() + " entradas de mercadoria de "
                            + Formatos.data(inicio) + " a " + Formatos.data(resultado.fim()));
            return null;
        });
        return resultado;
    }

    // ----- Plano -----

    private static List<DiaPlano> planejar(Random r, List<ItemCatalogo> catalogo, LocalDate inicio) {
        int[] acumulado = new int[catalogo.size()];
        int soma = 0;
        for (int i = 0; i < catalogo.size(); i++) {
            soma += catalogo.get(i).popularidade();
            acumulado[i] = soma;
        }
        List<DiaPlano> dias = new ArrayList<>();
        for (int d = 0; d < DIAS; d++) {
            LocalDate data = inicio.plusDays(d);
            int quantidade = vendasNoDia(r, data.getDayOfWeek());
            List<VendaPlano> vendasDia = new ArrayList<>();
            for (int v = 0; v < quantidade; v++) {
                vendasDia.add(planejarVenda(r, catalogo, acumulado, soma));
            }
            vendasDia.sort((a, b) -> a.hora().compareTo(b.hora()));
            List<PerdaPlano> perdas = new ArrayList<>();
            if (r.nextInt(100) < 25) {
                int produto = sortear(r, acumulado, soma);
                ItemCatalogo item = catalogo.get(produto);
                BigDecimal qtd = item.pesavel() ? BigDecimal.valueOf(300 + r.nextInt(1200), 3)
                        : BigDecimal.valueOf(1 + r.nextInt(3));
                perdas.add(new PerdaPlano(produto, qtd, r.nextInt(3) == 0 ? MotivoPerda.AVARIA
                        : MotivoPerda.VENCIMENTO));
            }
            List<LocalTime> sangrias = r.nextBoolean() ? List.of(LocalTime.of(15, 0))
                    : List.of(LocalTime.of(12, 30), LocalTime.of(17, 30));
            int manha = d % 2;
            int tarde = data.getDayOfWeek() == DayOfWeek.SUNDAY ? 2 : 1 - manha;
            dias.add(new DiaPlano(d + 1, data, manha, tarde, vendasDia, perdas, sangrias));
        }
        return dias;
    }

    /** 20 a 60 vendas, mais no fim de semana. */
    private static int vendasNoDia(Random r, DayOfWeek dia) {
        return switch (dia) {
            case MONDAY -> 22 + r.nextInt(15);
            case TUESDAY -> 20 + r.nextInt(15);
            case WEDNESDAY -> 24 + r.nextInt(15);
            case THURSDAY -> 26 + r.nextInt(15);
            case FRIDAY -> 34 + r.nextInt(17);
            case SATURDAY -> 42 + r.nextInt(19);
            case SUNDAY -> 20 + r.nextInt(11);
        };
    }

    private static VendaPlano planejarVenda(Random r, List<ItemCatalogo> catalogo, int[] acumulado, int soma) {
        int hora = PRIMEIRA_HORA + sortear(r, PESO_HORA);
        LocalTime instante = LocalTime.of(hora, r.nextInt(60), r.nextInt(60));
        int quantidadeItens = Math.min(15, 1 + (int) Math.floor(-Math.log(1 - r.nextDouble()) * 3.2));
        List<ItemPlano> itens = new ArrayList<>();
        for (int i = 0; i < quantidadeItens; i++) {
            int produto = sortear(r, acumulado, soma);
            boolean cancelar = i > 0 && r.nextInt(100) < 2;
            itens.add(new ItemPlano(produto, quantidade(r, catalogo.get(produto)), cancelar));
        }
        int f = r.nextInt(100);
        Forma forma = f < 30 ? Forma.DINHEIRO : f < 60 ? Forma.PIX : f < 80 ? Forma.DEBITO : f < 92 ? Forma.CREDITO
                : f < 95 ? Forma.VALE : Forma.PIX_E_DINHEIRO;
        int d = r.nextInt(100);
        int descontoVenda = d < 3 ? 5 : d < 6 ? 3 : 0;
        boolean descontoItem = descontoVenda == 0 && d < 8;
        boolean cancelar = r.nextInt(100) == 0;
        return new VendaPlano(instante, itens, forma, descontoVenda, descontoItem, cancelar);
    }

    private static BigDecimal quantidade(Random r, ItemCatalogo item) {
        if (item.pesavel()) {
            return item.nome().startsWith("Pão") ? BigDecimal.valueOf(200 + r.nextInt(601), 3)
                    : BigDecimal.valueOf(150 + r.nextInt(1351), 3);
        }
        int q = r.nextInt(100);
        return BigDecimal.valueOf(q < 70 ? 1 : q < 90 ? 2 : 3 + r.nextInt(4));
    }

    private static int sortear(Random r, int[] acumulado, int soma) {
        int alvo = r.nextInt(soma);
        for (int i = 0; i < acumulado.length; i++) {
            if (alvo < acumulado[i]) {
                return i;
            }
        }
        throw new IllegalStateException("sorteio fora da faixa");
    }

    private static int sortear(Random r, int[] pesos) {
        int soma = 0;
        for (int p : pesos) {
            soma += p;
        }
        int alvo = r.nextInt(soma);
        for (int i = 0; i < pesos.length; i++) {
            alvo -= pesos[i];
            if (alvo < 0) {
                return i;
            }
        }
        throw new IllegalStateException("sorteio fora da faixa");
    }

    /** Quantidade planejada para sair de cada produto em cada dia (itens não cancelados e perdas): [dia][produto]. */
    private static BigDecimal[][] demandaPorDia(List<DiaPlano> dias, int produtos) {
        BigDecimal[][] demanda = new BigDecimal[dias.size()][produtos];
        for (int d = 0; d < dias.size(); d++) {
            Arrays.fill(demanda[d], BigDecimal.ZERO);
            for (VendaPlano venda : dias.get(d).vendas()) {
                for (ItemPlano item : venda.itens()) {
                    if (!item.cancelar()) {
                        demanda[d][item.produto()] = demanda[d][item.produto()].add(item.quantidade());
                    }
                }
            }
            for (PerdaPlano perda : dias.get(d).perdas()) {
                demanda[d][perda.produto()] = demanda[d][perda.produto()].add(perda.quantidade());
            }
        }
        return demanda;
    }

    private static BigDecimal somar(BigDecimal[][] demanda, int produto, int deDia, int ateDiaExclusivo) {
        BigDecimal soma = BigDecimal.ZERO;
        for (int d = deDia; d < Math.min(ateDiaExclusivo, demanda.length); d++) {
            soma = soma.add(demanda[d][produto]);
        }
        return soma;
    }

    /** Produtos repostos pelo fornecedor: os das suas categorias que vendem, menos os de "estoque baixo". */
    private static boolean reposto(ItemCatalogo item, FornecedorDemo fornecedor) {
        return fornecedor.categorias().contains(item.categoria()) && item.popularidade() > 0 && !item.estoqueBaixo();
    }

    /** Índice (0 = primeiro dia) da primeira entrega do fornecedor. */
    private static int primeiraEntrega(FornecedorDemo fornecedor, LocalDate inicio) {
        int d = 0;
        while (inicio.plusDays(d).getDayOfWeek() != fornecedor.diaEntrega()) {
            d++;
        }
        return d;
    }

    /**
     * Uma entrada por semana de cada fornecedor, no dia da entrega: a demanda planejada dos 7 dias seguintes,
     * arredondada para cima em unidades inteiras. O custo sobe 0,25% por semana mais 0% a 2,5% de variação sobre o
     * custo do catálogo (nunca abaixo dele: os produtos de preço abaixo do custo continuam assim); 1 em cada 5 linhas é
     * lançada pelo valor total.
     */
    private static List<CompraPlano> planejarCompras(Random r, List<ItemCatalogo> catalogo,
                                                     List<FornecedorDemo> fornecedores, BigDecimal[][] demanda,
                                                     LocalDate inicio) {
        List<CompraPlano> compras = new ArrayList<>();
        for (int f = 0; f < fornecedores.size(); f++) {
            FornecedorDemo fornecedor = fornecedores.get(f);
            int semana = 0;
            for (int d = primeiraEntrega(fornecedor, inicio); d < DIAS; d += 7, semana++) {
                List<ItemCompra> itens = new ArrayList<>();
                for (int p = 0; p < catalogo.size(); p++) {
                    ItemCatalogo item = catalogo.get(p);
                    if (!reposto(item, fornecedor)) {
                        continue;
                    }
                    BigDecimal quantidade = somar(demanda, p, d, d + 7).setScale(0, RoundingMode.CEILING);
                    if (quantidade.signum() == 0) {
                        continue;
                    }
                    BigDecimal fator = BigDecimal.ONE.add(BigDecimal.valueOf(25L * semana + r.nextInt(251), 4));
                    BigDecimal custo = item.custo().multiply(fator).setScale(2, RoundingMode.HALF_UP);
                    itens.add(new ItemCompra(p, quantidade, custo, r.nextInt(5) == 0));
                }
                if (!itens.isEmpty()) {
                    compras.add(new CompraPlano(f, d + 1, semana, itens));
                }
            }
        }
        compras.sort((a, b) -> a.dia() != b.dia() ? Integer.compare(a.dia(), b.dia())
                : Integer.compare(a.fornecedor(), b.fornecedor()));
        return compras;
    }

    /**
     * Estoque inicial de cada produto: os repostos cobrem a demanda até a primeira entrega, com folga; os demais, a
     * demanda dos {@value #DIAS} dias ({@link #estoqueInicial}).
     */
    private static Map<Integer, BigDecimal> estoquesIniciais(List<ItemCatalogo> catalogo, List<DiaPlano> dias,
                                                             BigDecimal[][] demanda, List<CompraPlano> compras) {
        Map<Integer, BigDecimal> inicial = new LinkedHashMap<>();
        LocalDate inicio = dias.get(0).data();
        List<FornecedorDemo> fornecedores = CatalogoDemo.fornecedores();
        for (int p = 0; p < catalogo.size(); p++) {
            ItemCatalogo item = catalogo.get(p);
            BigDecimal total = somar(demanda, p, 0, DIAS);
            FornecedorDemo fornecedor = null;
            for (FornecedorDemo f : fornecedores) {
                if (reposto(item, f)) {
                    fornecedor = f;
                }
            }
            if (fornecedor == null || compras.isEmpty()) {
                inicial.put(p, estoqueInicial(item, total));
            } else {
                BigDecimal antes = somar(demanda, p, 0, primeiraEntrega(fornecedor, inicio));
                inicial.put(p, estoqueInicialReposto(item, antes, total));
            }
        }
        return inicial;
    }

    /**
     * Produto reposto toda semana: a demanda até a primeira entrega + 30% da média semanal + mínimo + 6. Como cada
     * entrada traz a demanda da semana, o saldo nunca fica negativo e termina acima do mínimo.
     */
    static BigDecimal estoqueInicialReposto(ItemCatalogo item, BigDecimal demandaAntes, BigDecimal demandaTotal) {
        BigDecimal mediaSemanal = demandaTotal.multiply(BigDecimal.valueOf(7))
                .divide(BigDecimal.valueOf(DIAS), Dinheiro.ESCALA_QUANTIDADE, RoundingMode.HALF_UP);
        return demandaAntes.add(mediaSemanal.multiply(new BigDecimal("0.3"))).add(item.minimo())
                .add(BigDecimal.valueOf(6)).setScale(0, RoundingMode.CEILING);
    }

    /**
     * Cobre a demanda com folga e termina acima do mínimo; os de "estoque baixo" terminam abaixo do mínimo; os sem
     * venda ficam com um pouco de estoque parado.
     */
    static BigDecimal estoqueInicial(ItemCatalogo item, BigDecimal demanda) {
        if (item.popularidade() == 0) {
            return BigDecimal.valueOf(item.pesavel() ? 3 : 8);
        }
        if (item.estoqueBaixo()) {
            return demanda.add(BigDecimal.valueOf(4)).setScale(0, RoundingMode.CEILING);
        }
        BigDecimal folga = demanda.multiply(new BigDecimal("1.15")).add(item.minimo()).add(BigDecimal.valueOf(6));
        return folga.setScale(0, RoundingMode.CEILING);
    }

    // ----- Execução -----

    private void posicionar(LocalDate data, LocalTime hora, ZoneId zona) {
        Instant instante = data.atTime(hora).atZone(zona).toInstant();
        relogio.ajustar(instante);
    }

    /**
     * Entrada semanal pelo {@link EntradaService} (6.10): nota com a emissão na véspera e o valor total igual à soma
     * dos itens, itens às 6h40 e confirmação às 6h50 (antes da abertura do caixa, do inventário e dos reajustes).
     */
    private void comprar(Sessao gerente, CompraPlano compra, FornecedorDemo fornecedor, long fornecedorId,
                         List<Long> produtoIds, LocalDate data, ZoneId zona) {
        posicionar(data, LocalTime.of(6, 40), zona);
        BigDecimal total = compra.itens().stream().map(ItemCompra::valorTotal).reduce(BigDecimal.ZERO,
                BigDecimal::add);
        long id = compras.criar(gerente, new DadosEntrada(fornecedorId,
                String.valueOf(fornecedor.primeiraNota() + compra.semana()), data.minusDays(1), total));
        LocalTime hora = LocalTime.of(6, 40, 10);
        for (ItemCompra item : compra.itens()) {
            posicionar(data, hora, zona);
            hora = hora.plusSeconds(5);
            compras.adicionarItem(gerente, id, produtoIds.get(item.produto()), item.quantidade(),
                    item.porValorTotal() ? null : item.custo(), item.porValorTotal() ? item.valorTotal() : null);
        }
        posicionar(data, LocalTime.of(6, 50), zona);
        compras.confirmar(gerente, id);
    }

    private static String diaDaSemana(DayOfWeek dia) {
        return switch (dia) {
            case MONDAY -> "segunda-feira";
            case TUESDAY -> "terça-feira";
            case WEDNESDAY -> "quarta-feira";
            case THURSDAY -> "quinta-feira";
            case FRIDAY -> "sexta-feira";
            case SATURDAY -> "sábado";
            case SUNDAY -> "domingo";
        };
    }

    /** Os pesáveis têm PLU, que só é aceito com a balança ativa (6.3). */
    private void ativarBalanca(Sessao supervisor) {
        transacao.executar(con -> {
            if (!configuracaoDAO.carregar(con).balanca().ativo()) {
                configuracaoDAO.gravar(con, Configuracoes.BALANCA_ATIVO, "true");
                auditoria.registrar(con, supervisor, AcaoAuditoria.CONFIGURACAO_ALTERADA, "configuracao", null,
                        "Balança ativada pelo gerador de dados de demonstração (produtos pesáveis com PLU)");
            }
            return null;
        });
    }

    private List<UsuarioDemo> criarUsuarios(Sessao supervisor, Random r) {
        List<UsuarioDemo> criados = new ArrayList<>();
        for (UsuarioPlano u : USUARIOS) {
            String senha = "demo" + (1000 + r.nextInt(9000));
            Set<Permissao> permissoes = u.gerente()
                    ? EnumSet.allOf(Permissao.class)
                    : EnumSet.of(Permissao.PDV);
            usuarios.criar(supervisor, u.login(), u.nome(), false, permissoes, senha, senha);
            criados.add(new UsuarioDemo(u.login(), u.nome(), u.gerente() ? "Gerente" : "Operador de caixa", senha));
        }
        return criados;
    }

    private Map<String, Long> prepararCategorias(Sessao supervisor) {
        Map<String, Long> ids = new LinkedHashMap<>();
        for (Categoria c : categorias.listar(supervisor, true)) {
            ids.put(c.nome().toLowerCase(), c.id());
            if (!c.ativa()) {
                categorias.alterarSituacao(supervisor, c.id(), true);
            }
        }
        for (ItemCatalogo item : CatalogoDemo.itens()) {
            ids.computeIfAbsent(item.categoria().toLowerCase(), n -> categorias.criar(supervisor, item.categoria()));
        }
        return ids;
    }

    private static DadosProduto dados(ItemCatalogo item, Map<String, Long> categoriaIds, BigDecimal inicial) {
        List<CodigoBarras> codigos = item.codigoBarras() == null ? List.of()
                : List.of(new CodigoBarras(item.codigoBarras(), true));
        return new DadosProduto(item.nome(), categoriaIds.get(item.categoria().toLowerCase()), item.unidade(),
                item.preco(), item.custo(), item.minimo(), item.plu(), codigos, inicial);
    }

    private Map<Forma, List<FormaPagamento>> formas(Sessao sessao) {
        Map<TipoFormaPagamento, FormaPagamento> porTipo = new EnumMap<>(TipoFormaPagamento.class);
        for (FormaPagamento f : vendas.formasAtivas(sessao)) {
            porTipo.putIfAbsent(f.tipo(), f);
        }
        FormaPagamento dinheiro = porTipo.get(TipoFormaPagamento.DINHEIRO);
        if (dinheiro == null) {
            throw new NegocioException("A forma de pagamento Dinheiro está desativada. Ative-a antes de gerar os "
                    + "dados de demonstração.");
        }
        Map<Forma, List<FormaPagamento>> formas = new EnumMap<>(Forma.class);
        formas.put(Forma.DINHEIRO, List.of(dinheiro));
        formas.put(Forma.PIX, List.of(porTipo.getOrDefault(TipoFormaPagamento.PIX, dinheiro)));
        formas.put(Forma.DEBITO, List.of(porTipo.getOrDefault(TipoFormaPagamento.CARTAO_DEBITO, dinheiro)));
        formas.put(Forma.CREDITO, List.of(porTipo.getOrDefault(TipoFormaPagamento.CARTAO_CREDITO, dinheiro)));
        formas.put(Forma.VALE, List.of(porTipo.getOrDefault(TipoFormaPagamento.OUTROS, dinheiro)));
        formas.put(Forma.PIX_E_DINHEIRO, List.of(porTipo.getOrDefault(TipoFormaPagamento.PIX, dinheiro), dinheiro));
        // Desativada, as vendas não usam o crédito do cliente (o cashback continua).
        FormaPagamento credito = porTipo.get(TipoFormaPagamento.CREDITO_CLIENTE);
        formas.put(Forma.CREDITO_CLIENTE, credito == null ? List.of() : List.of(credito));
        FormaPagamento crediario = porTipo.get(TipoFormaPagamento.CREDIARIO);
        formas.put(Forma.CREDIARIO, crediario == null ? List.of() : List.of(crediario));
        return formas;
    }

    /** Fidelidade (F12.8): ligada com 1% de cashback e sem compra mínima. */
    private void ligarFidelidade(Sessao supervisor) {
        transacao.executar(con -> {
            if (!configuracaoDAO.carregar(con).fidelidade().ativo()) {
                configuracaoDAO.gravar(con, Configuracoes.FIDELIDADE_ATIVO, "true");
                configuracaoDAO.gravar(con, Configuracoes.FIDELIDADE_CASHBACK, "1");
                configuracaoDAO.gravar(con, Configuracoes.FIDELIDADE_COMPRA_MINIMA, "0");
                auditoria.registrar(con, supervisor, AcaoAuditoria.CONFIGURACAO_ALTERADA, "configuracao", null,
                        "Fidelidade ligada pelo gerador de dados de demonstração (cashback de 1%, sem compra mínima)");
            }
            return null;
        });
    }

    private record Venda(long vendaId, int itensCancelados, boolean usouCredito, int parcelas) {
    }

    /**
     * Para cada parcela, sorteia se é paga em dia (no vencimento), com atraso (de 3 a 20 dias) ou fica em aberto. Dia
     * depois dos 60 da demonstração não acontece: a parcela fica em aberto, a vencer ou vencida.
     */
    private static void planejarRecebimentos(Random r, Map<Integer, List<RecebimentoPlano>> porDia, int diaVenda,
                                             long clienteId, long vendaId, int parcelas) {
        for (int p = 1; p <= parcelas; p++) {
            int vencimento = diaVenda + DIAS_ATE_PRIMEIRA_DEMO + (p - 1) * CalculoCrediario.INTERVALO_DIAS;
            int sorteio = r.nextInt(100);
            int atraso = 3 + r.nextInt(18);
            boolean emDinheiro = r.nextBoolean();
            if (sorteio >= PCT_PAGA_EM_DIA + PCT_PAGA_COM_ATRASO) {
                continue;
            }
            int dia = sorteio < PCT_PAGA_EM_DIA ? vencimento : vencimento + atraso;
            if (dia <= DIAS) {
                porDia.computeIfAbsent(dia, k -> new ArrayList<>())
                        .add(new RecebimentoPlano(clienteId, vendaId, p, emDinheiro));
            }
        }
    }

    /** Quita a parcela, se ela ainda estiver em aberto (uma devolução pode já ter abatido tudo). */
    private boolean receber(Sessao gerente, RecebimentoPlano plano, Map<Forma, List<FormaPagamento>> formas) {
        Optional<TituloEmAberto> titulo = recebimentos.titulos(gerente, plano.clienteId()).stream()
                .filter(t -> t.vendaId() == plano.vendaId() && t.parcela() == plano.parcela()).findFirst();
        if (titulo.isEmpty()) {
            return false;
        }
        List<Long> ids = List.of(titulo.get().tituloId());
        PlanoRecebimento previsto = recebimentos.simular(gerente, plano.clienteId(), ids, null, null);
        Recebimento pagamento = recebimentos.pagamento(previsto);
        pagamento.adicionar(formas.get(plano.emDinheiro() ? Forma.DINHEIRO : Forma.PIX).get(0), previsto.total());
        recebimentos.registrar(gerente, plano.clienteId(), ids, null, null, pagamento, null, null);
        return true;
    }

    private Venda vender(Sessao operador, VendaPlano plano, List<Long> produtoIds, Map<Long, ProdutoVenda> paraVenda,
                         Map<Forma, List<FormaPagamento>> formas, Random r, LocalDate data, ZoneId zona,
                         Liberacao liberaCancelamento, Liberacao liberaDesconto, Long clienteId,
                         boolean usarCredito, int parcelas) {
        Carrinho carrinho = new Carrinho();
        int cancelados = 0;
        LocalTime hora = plano.hora();
        for (ItemPlano item : plano.itens()) {
            posicionar(data, hora, zona);
            hora = hora.plusSeconds(4);
            ProdutoVenda produto = paraVenda.get(produtoIds.get(item.produto()));
            ItemCarrinho incluido = vendas.adicionarItem(operador, carrinho, produto, item.quantidade()).item();
            if (item.cancelar()) {
                vendas.cancelarItem(operador, carrinho, incluido.sequencia(), liberaCancelamento);
                cancelados++;
            }
        }
        posicionar(data, hora, zona);
        if (plano.descontoItem()) {
            ItemCarrinho primeiro = carrinho.itens().get(0);
            vendas.aplicarDescontoItem(operador, carrinho, primeiro.sequencia(), TipoDesconto.PERCENTUAL,
                    BigDecimal.valueOf(5), liberaDesconto);
        } else if (plano.descontoVendaPct() > 0) {
            vendas.aplicarDescontoVenda(operador, carrinho, TipoDesconto.PERCENTUAL,
                    BigDecimal.valueOf(plano.descontoVendaPct()), liberaDesconto);
        }
        if (clienteId != null) {
            vendas.identificarCliente(operador, carrinho, clienteId);
        }
        Recebimento recebimento = vendas.recebimento(operador, carrinho);
        // Uso do crédito (F12.8): parte da venda paga com o saldo do cliente, o resto pela forma planejada.
        boolean usouCredito = false;
        FormaPagamento credito = formas.get(Forma.CREDITO_CLIENTE).stream().findFirst().orElse(null);
        BigDecimal disponivel = recebimento.creditoDisponivel().orElse(Dinheiro.ZERO);
        if (usarCredito && credito != null && carrinho.total().signum() > 0
                && disponivel.compareTo(CREDITO_MINIMO_PARA_USAR) >= 0) {
            recebimento.adicionar(credito, disponivel.min(carrinho.total()));
            usouCredito = true;
        }
        List<FormaPagamento> usar = formas.get(plano.forma());
        // Crediário (F14.4): só com limite e sem atraso que pediria liberação; venda muito pequena fica à vista.
        int aPrazo = 0;
        if (parcelas > 0 && !formas.get(Forma.CREDIARIO).isEmpty() && !recebimento.quitado()) {
            VendaService.OpcoesCrediario opcoes = vendas.opcoesCrediario(operador, carrinho);
            boolean cabe = opcoes.limiteDisponivel() != null
                    && opcoes.limiteDisponivel().compareTo(recebimento.falta()) >= 0
                    && recebimento.falta().compareTo(BigDecimal.valueOf(parcelas)) >= 0
                    && recebimentos.titulos(operador, clienteId).stream()
                    .noneMatch(t -> t.diasAtraso() > ATRASO_QUE_PEDE_LIBERACAO);
            if (cabe) {
                // Os mesmos sorteios da venda à vista: os dados seguintes não mudam por causa do crediário.
                if (usar.get(usar.size() - 1).dinheiro()) {
                    notas(recebimento.falta(), r);
                }
                recebimento.adicionar(formas.get(Forma.CREDIARIO).get(0), recebimento.falta());
                recebimento.definirPlanoCrediario(new PlanoCrediario(parcelas, data.plusDays(DIAS_ATE_PRIMEIRA_DEMO)));
                aPrazo = parcelas;
            }
        }
        if (!recebimento.quitado()) {
            if (usar.size() == 2 && recebimento.falta().compareTo(BigDecimal.valueOf(4)) > 0) {
                BigDecimal parte = recebimento.falta().divide(BigDecimal.valueOf(2), 0, RoundingMode.DOWN)
                        .setScale(2);
                recebimento.adicionar(usar.get(0), parte);
                recebimento.adicionar(usar.get(1), notas(recebimento.falta(), r));
            } else if (usar.get(usar.size() - 1).dinheiro()) {
                recebimento.adicionar(usar.get(usar.size() - 1), notas(recebimento.falta(), r));
            } else {
                recebimento.adicionar(usar.get(0), recebimento.falta());
            }
        }
        long vendaId = vendas.finalizar(operador, carrinho, recebimento).vendaId();
        return new Venda(vendaId, cancelados, usouCredito, aPrazo);
    }

    /** O que o cliente entrega em dinheiro: o valor exato ou a nota de 5, 10, 20, 50 ou 100 seguinte. */
    static BigDecimal notas(BigDecimal falta, Random r) {
        int[] notas = {0, 5, 10, 20, 50, 100};
        int escolha = notas[r.nextInt(notas.length)];
        if (escolha == 0) {
            return falta;
        }
        BigDecimal nota = BigDecimal.valueOf(escolha);
        BigDecimal valor = falta.divide(nota, 0, RoundingMode.CEILING).multiply(nota);
        return valor.setScale(Dinheiro.ESCALA_DINHEIRO);
    }

    /**
     * Sorteia a devolução de uma venda finalizada: cada item não cancelado tem {@value #POR_MIL_ITENS_DEVOLVIDOS} em
     * mil de ser devolvido, e um em quatro volta avariado. Vale-troca em metade das vendas com cliente; nas demais,
     * dinheiro (60%) ou outro. Nulo se nenhum item foi sorteado.
     */
    private static DevolucaoPlano planejarDevolucao(Random r, VendaPlano venda, long vendaId, boolean comCliente) {
        List<Integer> itens = new ArrayList<>();
        List<Boolean> avariados = new ArrayList<>();
        for (int i = 0; i < venda.itens().size(); i++) {
            if (!venda.itens().get(i).cancelar() && r.nextInt(1000) < POR_MIL_ITENS_DEVOLVIDOS) {
                itens.add(i);
                avariados.add(r.nextInt(4) == 0);
            }
        }
        if (itens.isEmpty()) {
            return null;
        }
        FormaReembolso forma = comCliente && r.nextBoolean() ? FormaReembolso.CREDITO_CLIENTE
                : r.nextInt(10) < 6 ? FormaReembolso.DINHEIRO : FormaReembolso.OUTRO;
        LocalTime hora = LocalTime.of(9 + r.nextInt(10), r.nextInt(60));
        return new DevolucaoPlano(vendaId, hora, itens, avariados, forma,
                MOTIVOS_DEVOLUCAO.get(r.nextInt(MOTIVOS_DEVOLUCAO.size())));
    }

    /**
     * Devolução pelo {@link DevolucaoService} (regras valendo), registrada pelo gerente: 1 unidade do item de unidade
     * inteira vendido em mais de uma, ou o item inteiro. Reembolso em dinheiro acima do esperado do caixa vira outro.
     */
    private void devolver(Sessao gerente, DevolucaoPlano plano, long caixaId) {
        VendaDevolucao venda = devolucoes.venda(gerente, plano.vendaId());
        List<DadosDevolucao.Item> itens = new ArrayList<>();
        BigDecimal total = Dinheiro.ZERO;
        for (int k = 0; k < plano.itens().size(); k++) {
            ItemDevolucao item = venda.itens().get(plano.itens().get(k));
            BigDecimal q = !item.fracionada() && item.quantidade().compareTo(BigDecimal.ONE) > 0 ? BigDecimal.ONE
                    : item.disponivel();
            itens.add(new DadosDevolucao.Item(item.vendaItemId(), q, plano.avariados().get(k)));
            total = total.add(item.valor(q));
        }
        FormaReembolso forma = plano.forma();
        if (forma == FormaReembolso.DINHEIRO) {
            BigDecimal esperado = transacao.executar(con -> caixaDAO.resumo(con, caixaId).dinheiroEsperado());
            if (total.compareTo(esperado) > 0) {
                forma = FormaReembolso.OUTRO;
            }
        }
        Long conta = forma == FormaReembolso.OUTRO ? devolucoes.contasParaReembolso(gerente).stream()
                .filter(c -> c.tipo() == TipoConta.BANCO).findFirst().orElseThrow().id() : null;
        devolucoes.registrar(gerente, new DadosDevolucao(plano.vendaId(), itens, plano.motivo(), forma, null, null,
                conta), null);
    }

    /** Sangria (6.5) deixando o fundo de troco na gaveta, em múltiplos de R$ 50,00. */
    private void sangria(Sessao gerente, long caixaId) {
        BigDecimal esperado = transacao.executar(con -> caixaDAO.resumo(con, caixaId).dinheiroEsperado());
        BigDecimal retirar = esperado.subtract(FUNDO_DE_TROCO).divide(BigDecimal.valueOf(50), 0, RoundingMode.DOWN)
                .multiply(BigDecimal.valueOf(50));
        if (retirar.compareTo(BigDecimal.valueOf(100)) >= 0) {
            caixa.registrarMovimento(gerente, TipoCaixaMovimento.SANGRIA, retirar.setScale(2),
                    "Recolhimento para o cofre", null);
        }
    }

    /** Dinheiro contado no fechamento: quase sempre igual ao esperado; às vezes alguns centavos ou reais a mais ou a menos. */
    private BigDecimal contado(long caixaId, Random r) {
        BigDecimal esperado = transacao.executar(con -> caixaDAO.resumo(con, caixaId).dinheiroEsperado());
        int sorteio = r.nextInt(100);
        if (sorteio < 75) {
            return esperado;
        }
        BigDecimal diferenca = BigDecimal.valueOf(sorteio < 90 ? -(5 + r.nextInt(496)) : 5 + r.nextInt(296), 2);
        BigDecimal contado = esperado.add(diferenca);
        return contado.signum() < 0 ? esperado : contado;
    }

    /** Reajuste de 3% a 8% em 6 produtos vendidos (preço terminado em 9 centavos). */
    private void reajustarPrecos(Sessao gerente, List<ItemCatalogo> catalogo, List<Long> produtoIds, Random r,
                                 int rodada) {
        List<Integer> candidatos = new ArrayList<>();
        for (int i = 0; i < catalogo.size(); i++) {
            ItemCatalogo item = catalogo.get(i);
            if (item.popularidade() >= 3 && item.preco().compareTo(item.custo()) > 0 && i % 2 == rodada) {
                candidatos.add(i);
            }
        }
        for (int n = 0; n < 6 && !candidatos.isEmpty(); n++) {
            int indice = candidatos.remove(r.nextInt(candidatos.size()));
            Produto atual = produtos.buscar(gerente, produtoIds.get(indice));
            BigDecimal aumento = BigDecimal.ONE.add(BigDecimal.valueOf(3 + r.nextInt(6)).divide(CEM));
            BigDecimal novo = atual.precoVenda().multiply(aumento).setScale(1, RoundingMode.DOWN)
                    .add(new BigDecimal("0.09"));
            if (novo.compareTo(atual.precoVenda()) <= 0) {
                novo = atual.precoVenda().add(new BigDecimal("0.10"));
            }
            produtos.alterar(gerente, atual.id(), new DadosProduto(atual.nome(), atual.categoriaId(),
                    atual.unidade(), novo, atual.custo(), atual.estoqueMinimo(), atual.plu(), atual.codigosBarras(),
                    null));
        }
    }

    /** Inventário de limpeza e higiene com algumas divergências e um item não contado, aplicado em seguida. */
    private long inventario(Sessao gerente, Map<String, Long> categoriaIds, Random r, LocalDate data, ZoneId zona) {
        long id = inventarios.criar(gerente, "Inventário de limpeza e higiene");
        for (String categoria : CATEGORIAS_INVENTARIO) {
            inventarios.incluirCategoria(gerente, id, categoriaIds.get(categoria.toLowerCase()));
        }
        List<ItemContagem> itens = inventarios.itensContagem(gerente, id);
        LocalTime hora = LocalTime.of(7, 5);
        for (int i = 0; i < itens.size(); i++) {
            ItemContagem item = itens.get(i);
            if (i == itens.size() - 1) {
                break; // um item fica sem contar (aviso na aplicação)
            }
            posicionar(data, hora, zona);
            hora = hora.plusSeconds(30);
            BigDecimal saldo = estoque.saldo(gerente, item.produtoId()).saldo();
            int sorteio = r.nextInt(100);
            int diferenca = sorteio < 65 ? 0 : sorteio < 80 ? -1 : sorteio < 88 ? -2 : 1;
            BigDecimal contado = saldo.add(BigDecimal.valueOf(diferenca)).max(BigDecimal.ZERO);
            inventarios.registrarContagem(gerente, id, item.itemId(), contado);
        }
        posicionar(data, LocalTime.of(7, 40), zona);
        inventarios.aplicar(gerente, id);
        return id;
    }
}
