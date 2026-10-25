package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.dao.EstoqueDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.InconsistenciaEstoque;
import br.com.varesync.model.Kardex;
import br.com.varesync.model.MovimentoKardex;
import br.com.varesync.model.OrigemMovimento;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.ResultadoAjuste;
import br.com.varesync.model.SaldoEstoque;
import br.com.varesync.model.TipoAjuste;
import br.com.varesync.model.TipoMovimento;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Estoque (6.4, Fase 3): {@link EstoqueService#movimentar} como único caminho, consulta com kardex, ajustes manuais,
 * alertas de mínimo e diagnóstico. Ao fim de cada teste, {@code IntegracaoBase} confere que
 * {@code vw_estoque_inconsistente} está vazia.
 */
class EstoqueServiceIT extends IntegracaoBase {

    private static final String SEM_PERMISSAO = "Você não tem permissão para usar a rotina Estoque. Procure o supervisor.";

    private AcessoTeste t;
    private EstoqueService estoque;
    private Sessao admin;
    private long arroz;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        estoque = t.estoque;
        admin = t.supervisor();
        arroz = criar("Arroz 5 kg", "UN", "0", null, List.of());
    }

    private long criar(String nome, String unidade, String minimo, String inicial, List<CodigoBarras> codigos) {
        return t.produtos.criar(admin, new DadosProduto(nome, null, unidade, new BigDecimal("27.90"),
                new BigDecimal("21.50"), new BigDecimal(minimo), null, codigos,
                inicial == null ? null : new BigDecimal(inicial)));
    }

    private long movimentar(TipoMovimento tipo, String quantidade, String motivo) throws Exception {
        try (Connection con = dataSource.getConnection()) {
            return estoque.movimentar(con, admin, arroz, tipo, new BigDecimal(quantidade), OrigemMovimento.NENHUMA,
                    motivo, null);
        }
    }

    private ResultadoAjuste ajustar(long produto, TipoAjuste tipo, String quantidade, String motivo, String obs) {
        return estoque.ajustar(admin, produto, tipo, new BigDecimal(quantidade), motivo, obs);
    }

    private List<MovimentoKardex> kardex(long produto) {
        return estoque.kardex(admin, produto, estoque.periodoPadrao()).movimentos();
    }

    private List<String> origens(long produto, Periodo periodo) {
        return estoque.kardex(admin, produto, periodo).movimentos().stream().map(MovimentoKardex::origem).toList();
    }

    private Sessao operador(String login, Permissao... permissoes) {
        EnumSet<Permissao> conjunto = EnumSet.noneOf(Permissao.class);
        conjunto.addAll(List.of(permissoes));
        t.usuarios.criar(admin, login, "Operador " + login, false, conjunto, "inicial1", "inicial1");
        return t.acesso.entrar(login, "inicial1");
    }

    // ----- Critérios de aceite da Fase 3 -----

    @Test
    void kardexMostraOSaldoAposCadaMovimento() throws Exception {
        long feijao = criar("Feijão carioca 1 kg", "UN", "0", "10", List.of());
        ajustar(feijao, TipoAjuste.SAIDA, "3", "Acerto de contagem", null);
        ajustar(feijao, TipoAjuste.PERDA, "2", "Avaria", "embalagem rasgada");
        ajustar(feijao, TipoAjuste.ENTRADA, "5", "Devolução de cliente", "");

        List<MovimentoKardex> linhas = kardex(feijao);

        assertThat(linhas).extracting(MovimentoKardex::tipo).containsExactly(TipoMovimento.AJUSTE_ENTRADA,
                TipoMovimento.AJUSTE_SAIDA, TipoMovimento.PERDA, TipoMovimento.AJUSTE_ENTRADA);
        assertThat(linhas).extracting(m -> m.quantidade().stripTrailingZeros().toPlainString())
                .containsExactly("10", "-3", "-2", "5");
        assertThat(linhas).extracting(m -> m.saldoPosterior().stripTrailingZeros().toPlainString())
                .containsExactly("10", "7", "5", "10");
        assertThat(linhas).extracting(m -> m.saldoAnterior().stripTrailingZeros().toPlainString())
                .containsExactly("0", "10", "7", "5");
        assertThat(linhas).extracting(MovimentoKardex::origem).containsExactly("Estoque inicial",
                "Acerto de contagem", "Avaria: embalagem rasgada", "Devolução de cliente");
        assertThat(linhas).extracting(MovimentoKardex::usuarioNome).containsOnly("Ana Admin");
        assertThat(linhas).allSatisfy(m -> assertThat(m.custoUnitario()).isEqualByComparingTo("21.50"));

        SaldoEstoque saldo = estoque.saldo(admin, feijao);
        assertThat(saldo.saldo()).isEqualByComparingTo("10");
        assertThat(saldo.custo()).isEqualByComparingTo("21.5");
        assertThat(saldo.valorEmEstoque()).isEqualByComparingTo("215.00");
        assertThat(saldo.ultimaMovimentacao().toInstant()).isEqualTo(linhas.get(3).criadoEm().toInstant());
    }

    @Test
    void ajusteSemMotivoERecusado() throws Exception {
        assertThatThrownBy(() -> ajustar(arroz, TipoAjuste.ENTRADA, "5", "   ", "obs sem motivo"))
                .isInstanceOf(NegocioException.class).hasMessage("Informe o motivo do ajuste.");
        assertThatThrownBy(() -> ajustar(arroz, TipoAjuste.SAIDA, "1", null, null))
                .hasMessage("Informe o motivo do ajuste.");
        assertThatThrownBy(() -> ajustar(arroz, TipoAjuste.PERDA, "1", "", null))
                .hasMessage("Informe o motivo do ajuste.");
        assertThatThrownBy(() -> estoque.ajustarSaldoPara(admin, arroz, new BigDecimal("7"), " "))
                .hasMessage("Informe o motivo do ajuste.");

        assertThat(t.contar("SELECT count(*) FROM movimento_estoque")).isZero();
        assertThat(t.auditoria(AcaoAuditoria.ESTOQUE_AJUSTADO)).isEmpty();
    }

    @Test
    void unidadeUnNaoAceitaUmEMeioEKgAceita() {
        assertThatThrownBy(() -> ajustar(arroz, TipoAjuste.ENTRADA, "1.5", "Acerto de contagem", null))
                .isInstanceOf(NegocioException.class)
                .hasMessage("A quantidade deve ser um número inteiro: a unidade do produto não aceita quantidade "
                        + "fracionada (1,5).");
        assertThatThrownBy(() -> estoque.ajustarSaldoPara(admin, arroz, new BigDecimal("1.5"), "Acerto"))
                .hasMessageStartingWith("O novo saldo deve ser um número inteiro");
        assertThat(estoque.saldo(admin, arroz).saldo()).isEqualByComparingTo("0");

        long queijo = criar("Queijo muçarela", "KG", "0", null, List.of());
        ajustar(queijo, TipoAjuste.ENTRADA, "0.345", "Acerto de contagem", null);
        assertThatThrownBy(() -> ajustar(queijo, TipoAjuste.ENTRADA, "0.0005", "Acerto de contagem", null))
                .hasMessage("A quantidade pode ter no máximo 3 casas decimais.");
        assertThat(estoque.saldo(admin, queijo).saldo()).isEqualByComparingTo("0.345");
    }

    @Test
    void ajustarSaldoPara7ComSaldo10GeraSaidaDe3() throws Exception {
        long cafe = criar("Café 500 g", "UN", "0", "10", List.of());

        ResultadoAjuste r = estoque.ajustarSaldoPara(admin, cafe, new BigDecimal("7"), " Acerto  de contagem ");

        assertThat(r.tipo()).isEqualTo(TipoMovimento.AJUSTE_SAIDA);
        assertThat(r.quantidade()).isEqualByComparingTo("-3");
        assertThat(r.saldoAnterior()).isEqualByComparingTo("10");
        assertThat(r.saldoPosterior()).isEqualByComparingTo("7");
        assertThat(estoque.saldo(admin, cafe).saldo()).isEqualByComparingTo("7");
        MovimentoKardex ultimo = kardex(cafe).get(1);
        assertThat(ultimo.id()).isEqualTo(r.movimentoId());
        assertThat(ultimo.tipo()).isEqualTo(TipoMovimento.AJUSTE_SAIDA);
        assertThat(ultimo.quantidade()).isEqualByComparingTo("-3");
        assertThat(ultimo.saldoPosterior()).isEqualByComparingTo("7");
        assertThat(ultimo.origem()).isEqualTo("Acerto de contagem");
        assertThat(t.auditoria(AcaoAuditoria.ESTOQUE_AJUSTADO)).containsExactly("Produto Café 500 g (código " + cafe
                + "): saldo ajustado para 7 UN — saída de 3 UN, saldo de 10 UN para 7 UN. Motivo: Acerto de contagem");

        assertThatThrownBy(() -> estoque.ajustarSaldoPara(admin, cafe, new BigDecimal("7"), "Acerto"))
                .hasMessage("O saldo já é 7 UN: não há o que ajustar.");
        assertThatThrownBy(() -> estoque.ajustarSaldoPara(admin, cafe, new BigDecimal("-1"), "Acerto"))
                .hasMessage("O novo saldo não pode ser negativo.");
        assertThatThrownBy(() -> estoque.ajustarSaldoPara(admin, cafe, null, "Acerto"))
                .hasMessage("Informe o novo saldo.");

        ResultadoAjuste entrada = estoque.ajustarSaldoPara(admin, cafe, new BigDecimal("12"), "Conferência");
        assertThat(entrada.tipo()).isEqualTo(TipoMovimento.AJUSTE_ENTRADA);
        assertThat(entrada.quantidade()).isEqualByComparingTo("5");
        assertThat(estoque.saldo(admin, cafe).saldo()).isEqualByComparingTo("12");
    }

    @Test
    void diagnosticoVazioEDetectaSaldoDiferenteDosMovimentos() throws Exception {
        long feijao = criar("Feijão carioca 1 kg", "UN", "0", "10", List.of());
        assertThat(estoque.diagnostico(admin)).isEmpty();

        // Simula uma alteração feita fora do sistema, desfeita no fim (rollback).
        try (Connection con = dataSource.getConnection()) {
            con.setAutoCommit(false);
            try (PreparedStatement liberar = con.prepareStatement(
                         "SELECT set_config('varesync.via_movimento', 'on', true)");
                 PreparedStatement alterar = con.prepareStatement(
                         "UPDATE produto_estoque SET quantidade = quantidade + 1 WHERE produto_id = ?")) {
                liberar.execute();
                alterar.setLong(1, feijao);
                alterar.executeUpdate();
                List<InconsistenciaEstoque> linhas = new EstoqueDAO().inconsistencias(con);
                assertThat(linhas).hasSize(1);
                InconsistenciaEstoque linha = linhas.get(0);
                assertThat(linha.produtoId()).isEqualTo(feijao);
                assertThat(linha.nome()).isEqualTo("Feijão carioca 1 kg");
                assertThat(linha.saldoRegistrado()).isEqualByComparingTo("11");
                assertThat(linha.saldoPelosMovimentos()).isEqualByComparingTo("10");
                assertThat(linha.diferenca()).isEqualByComparingTo("1");
            } finally {
                con.rollback();
            }
        }
        assertThat(estoque.diagnostico(admin)).isEmpty();
    }

    // ----- Ajustes -----

    @Test
    void perdaGravaOMotivoDaListaComAObservacaoEAuditoria() throws Exception {
        long leite = criar("Leite integral 1 L", "UN", "0", "10", List.of());

        ResultadoAjuste r = ajustar(leite, TipoAjuste.PERDA, "2", "vencimento", " lote  12 ");

        assertThat(r.tipo()).isEqualTo(TipoMovimento.PERDA);
        assertThat(r.quantidade()).isEqualByComparingTo("-2");
        assertThat(kardex(leite).get(1).origem()).isEqualTo("Vencimento: lote 12");
        assertThat(estoque.saldo(admin, leite).saldo()).isEqualByComparingTo("8");
        assertThat(t.auditoria(AcaoAuditoria.ESTOQUE_AJUSTADO)).containsExactly("Produto Leite integral 1 L (código "
                + leite + "): perda de 2 UN, saldo de 10 UN para 8 UN. Motivo: Vencimento: lote 12");

        assertThatThrownBy(() -> ajustar(leite, TipoAjuste.PERDA, "1", "Quebrou", null))
                .hasMessage("Escolha o motivo da perda: Vencimento, Avaria, Furto/desvio, Consumo interno, Outros.");
        assertThatThrownBy(() -> ajustar(leite, TipoAjuste.PERDA, "1", "Outros", " "))
                .hasMessage("Com o motivo \"Outros\", descreva a perda na observação.");
        assertThatThrownBy(() -> ajustar(leite, TipoAjuste.SAIDA, "0", "Acerto", null))
                .hasMessage("A quantidade deve ser maior que zero.");
        assertThatThrownBy(() -> ajustar(leite, TipoAjuste.ENTRADA, "-4", "Acerto", null))
                .hasMessage("A quantidade deve ser maior que zero.");
        assertThatThrownBy(() -> estoque.ajustar(admin, leite, TipoAjuste.ENTRADA, null, "Acerto", null))
                .hasMessage("Informe a quantidade.");
        assertThatThrownBy(() -> estoque.ajustar(admin, leite, null, BigDecimal.ONE, "Acerto", null))
                .hasMessage("Escolha o tipo do ajuste: entrada, saída ou perda.");
        assertThatThrownBy(() -> ajustar(999_999, TipoAjuste.ENTRADA, "1", "Acerto", null))
                .hasMessage("Produto não encontrado.");
        assertThat(estoque.saldo(admin, leite).saldo()).isEqualByComparingTo("8");
    }

    @Test
    void saidaMaiorQueOSaldoDeixaNegativoEAjustarParaZeroCorrige() {
        long sabao = criar("Sabão em pó 1 kg", "UN", "0", "2", List.of());

        ajustar(sabao, TipoAjuste.SAIDA, "5", "Acerto de contagem", null);
        SaldoEstoque negativo = estoque.saldo(admin, sabao);
        assertThat(negativo.saldo()).isEqualByComparingTo("-3");
        assertThat(negativo.abaixoDoMinimo()).isTrue();

        ResultadoAjuste r = estoque.ajustarSaldoPara(admin, sabao, BigDecimal.ZERO, "Acerto de contagem");
        assertThat(r.tipo()).isEqualTo(TipoMovimento.AJUSTE_ENTRADA);
        assertThat(r.quantidade()).isEqualByComparingTo("3");
        assertThat(estoque.saldo(admin, sabao).saldo()).isEqualByComparingTo("0");
    }

    // ----- Consulta, período e alertas -----

    @Test
    void kardexFiltraPeloPeriodoComAsDuasDatasIncluidas() {
        ZonedDateTime agora = ZonedDateTime.now(t.relogio);
        LocalDate dia1 = agora.toLocalDate();
        ajustar(arroz, TipoAjuste.ENTRADA, "1", "dia 1", null);
        t.relogio.avancar(Duration.between(agora, dia1.plusDays(1).atStartOfDay(agora.getZone())).minusSeconds(1));
        ajustar(arroz, TipoAjuste.ENTRADA, "1", "fim do dia 1", null);
        t.relogio.avancar(Duration.ofSeconds(1));
        ajustar(arroz, TipoAjuste.ENTRADA, "1", "início do dia 2", null);
        t.relogio.avancar(Duration.ofDays(1));
        ajustar(arroz, TipoAjuste.ENTRADA, "1", "dia 3", null);

        assertThat(origens(arroz, new Periodo(dia1, dia1))).containsExactly("dia 1", "fim do dia 1");
        assertThat(origens(arroz, new Periodo(dia1.plusDays(1), dia1.plusDays(1)))).containsExactly("início do dia 2");
        assertThat(origens(arroz, new Periodo(dia1.plusDays(1), dia1.plusDays(2))))
                .containsExactly("início do dia 2", "dia 3");
        assertThat(origens(arroz, new Periodo(dia1.minusDays(10), dia1.minusDays(1)))).isEmpty();

        Periodo padrao = estoque.periodoPadrao();
        assertThat(padrao.fim()).isEqualTo(dia1.plusDays(2));
        assertThat(padrao.inicio()).isEqualTo(dia1.plusDays(2).minusDays(29));
        assertThat(kardex(arroz)).hasSize(4);

        assertThatThrownBy(() -> estoque.kardex(admin, arroz, new Periodo(dia1, dia1.minusDays(1))))
                .hasMessage("A data inicial não pode ser depois da data final.");
        assertThatThrownBy(() -> estoque.kardex(admin, arroz, new Periodo(null, dia1)))
                .hasMessage("Informe a data inicial e a data final do período.");
    }

    /** Aceite F10 (kardex): com movimentos antes do período, a primeira linha mostra o saldo anterior certo. */
    @Test
    void kardexComecaPeloSaldoAnteriorAoPeriodo() {
        ZonedDateTime agora = ZonedDateTime.now(t.relogio);
        LocalDate dia1 = agora.toLocalDate();
        ajustar(arroz, TipoAjuste.ENTRADA, "10", "dia 1", null);
        ajustar(arroz, TipoAjuste.SAIDA, "3", "dia 1, saída", null);
        t.relogio.avancar(Duration.ofDays(1));
        ajustar(arroz, TipoAjuste.PERDA, "2", "Avaria", null);
        t.relogio.avancar(Duration.ofDays(1));
        ajustar(arroz, TipoAjuste.ENTRADA, "4", "dia 3", null);

        Kardex dia2 = estoque.kardex(admin, arroz, new Periodo(dia1.plusDays(1), dia1.plusDays(1)));
        assertThat(dia2.saldoAnterior()).isEqualByComparingTo("7");
        assertThat(dia2.inicio()).isEqualTo(dia1.plusDays(1).atStartOfDay(agora.getZone()).toOffsetDateTime());
        List<MovimentoKardex> linhas = dia2.linhas();
        assertThat(linhas).extracting(MovimentoKardex::descricaoTipo)
                .containsExactly("Saldo anterior", TipoMovimento.PERDA.descricao());
        assertThat(linhas.get(0).linhaSaldoAnterior()).isTrue();
        assertThat(linhas.get(0).quantidade()).isNull();
        assertThat(linhas.get(0).saldoPosterior()).isEqualByComparingTo("7");
        assertThat(linhas.get(1).saldoPosterior()).isEqualByComparingTo("5");

        // Sem movimento antes do início: saldo anterior 0. Depois de tudo: o saldo atual, sem movimentos.
        assertThat(estoque.kardex(admin, arroz, new Periodo(dia1, dia1.plusDays(2))).saldoAnterior()).isZero();
        Kardex depois = estoque.kardex(admin, arroz, new Periodo(dia1.plusDays(3), dia1.plusDays(3)));
        assertThat(depois.saldoAnterior()).isEqualByComparingTo("9");
        assertThat(depois.movimentos()).isEmpty();
        assertThat(depois.linhas()).singleElement().satisfies(l -> assertThat(l.linhaSaldoAnterior()).isTrue());
    }

    @Test
    void percentSublinhadoEBarraNaBuscaValemComoTexto() {
        long cinquenta = criar("Desconto 50% caixa", "UN", "0", null, List.of());
        criar("Desconto 500 caixa", "UN", "0", null, List.of());
        long sublinhado = criar("Tampa a_b", "UN", "0", null, List.of());
        criar("Tampa axb", "UN", "0", null, List.of());
        long barra = criar("Cabo 1\\2 polegada", "UN", "0", null, List.of());
        criar("Cabo 12 polegada", "UN", "0", null, List.of());

        assertThat(estoque.pesquisar(admin, "50%", false, false)).extracting(SaldoEstoque::produtoId)
                .containsExactly(cinquenta);
        assertThat(estoque.pesquisar(admin, "a_b", false, false)).extracting(SaldoEstoque::produtoId)
                .containsExactly(sublinhado);
        assertThat(estoque.pesquisar(admin, "1\\2", false, false)).extracting(SaldoEstoque::produtoId)
                .containsExactly(barra);
    }

    @Test
    void pesquisaPorNomeCodigoECodigoDeBarrasEFiltraAbaixoDoMinimo() {
        long acucar = criar("Açúcar refinado 1 kg", "UN", "5", "3",
                List.of(new CodigoBarras("7891234567895", true)));
        long feijao = criar("Feijão preto 1 kg", "UN", "5", null, List.of());
        t.produtos.alterarSituacao(admin, feijao, false);
        ajustar(arroz, TipoAjuste.ENTRADA, "10", "Acerto de contagem", null);

        assertThat(estoque.pesquisar(admin, "ACUCAR", false, false)).extracting(SaldoEstoque::produtoId)
                .containsExactly(acucar);
        assertThat(estoque.pesquisar(admin, "7891234567895", false, false)).extracting(SaldoEstoque::produtoId)
                .containsExactly(acucar);
        assertThat(estoque.pesquisar(admin, String.valueOf(arroz), false, false)).extracting(SaldoEstoque::produtoId)
                .containsExactly(arroz);
        assertThat(estoque.pesquisar(admin, "", false, false)).extracting(SaldoEstoque::nome)
                .containsExactly("Açúcar refinado 1 kg", "Arroz 5 kg");
        assertThat(estoque.pesquisar(admin, "", true, false)).extracting(SaldoEstoque::produtoId)
                .containsExactly(acucar);
        assertThat(estoque.pesquisar(admin, "", true, true)).extracting(SaldoEstoque::produtoId)
                .containsExactly(acucar, feijao);
        assertThat(estoque.contarAbaixoDoMinimo(admin)).isEqualTo(1);

        SaldoEstoque linha = estoque.pesquisar(admin, "acucar", false, false).get(0);
        assertThat(linha.saldo()).isEqualByComparingTo("3");
        assertThat(linha.estoqueMinimo()).isEqualByComparingTo("5");
        assertThat(linha.valorEmEstoque()).isEqualByComparingTo("64.50");
        assertThat(linha.abaixoDoMinimo()).isTrue();
        assertThat(linha.unidade()).isEqualTo("UN");
        assertThat(linha.unidadeFracionada()).isFalse();

        ajustar(acucar, TipoAjuste.ENTRADA, "2", "Bonificação de fornecedor", null);
        assertThat(estoque.contarAbaixoDoMinimo(admin)).isZero();
    }

    // ----- Permissões -----

    @Test
    void semPermissaoEstoqueOServiceRecusaEDiagnosticoSoSupervisor() {
        Sessao cadastro = operador("cadastro", Permissao.PRODUTOS);
        Periodo periodo = estoque.periodoPadrao();

        assertThatThrownBy(() -> estoque.pesquisar(cadastro, "", false, false))
                .isInstanceOf(PermissaoNegadaException.class).hasMessage(SEM_PERMISSAO);
        assertThatThrownBy(() -> estoque.saldo(cadastro, arroz)).isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> estoque.kardex(cadastro, arroz, periodo)).isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> estoque.ajustar(cadastro, arroz, TipoAjuste.ENTRADA, BigDecimal.ONE, "Acerto", null))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> estoque.ajustarSaldoPara(cadastro, arroz, BigDecimal.ONE, "Acerto"))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> estoque.contarAbaixoDoMinimo(cadastro)).isInstanceOf(PermissaoNegadaException.class);

        Sessao estoquista = operador("estoque", Permissao.ESTOQUE);
        estoque.ajustar(estoquista, arroz, TipoAjuste.ENTRADA, BigDecimal.ONE, "Acerto de contagem", null);
        assertThat(kardex(arroz)).extracting(MovimentoKardex::usuarioNome).containsExactly("Operador estoque");
        assertThatThrownBy(() -> estoque.diagnostico(estoquista))
                .isInstanceOf(PermissaoNegadaException.class)
                .hasMessage("Somente o supervisor pode realizar esta ação.");
    }

    // ----- movimentar (Fase 2) -----

    @Test
    void validaSinalMotivoEQuantidadeInteira() {
        assertThatThrownBy(() -> movimentar(TipoMovimento.AJUSTE_SAIDA, "3", "Avaria"))
                .isInstanceOf(NegocioException.class)
                .hasMessage("A quantidade do movimento não corresponde ao tipo (entrada ou saída).");
        assertThatThrownBy(() -> movimentar(TipoMovimento.AJUSTE_ENTRADA, "0", "Acerto"))
                .hasMessage("A quantidade do movimento não pode ser zero.");
        assertThatThrownBy(() -> movimentar(TipoMovimento.AJUSTE_SAIDA, "-3", "   "))
                .hasMessage("Informe o motivo do ajuste.");
        assertThatThrownBy(() -> movimentar(TipoMovimento.AJUSTE_ENTRADA, "1.5", "Acerto"))
                .hasMessageStartingWith("A quantidade deve ser um número inteiro");
        assertThatThrownBy(() -> movimentar(TipoMovimento.VENDA, "-1", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void movimentoAtualizaOSaldoPeloTrigger() throws Exception {
        movimentar(TipoMovimento.AJUSTE_ENTRADA, "10", " Estoque   inicial ");
        movimentar(TipoMovimento.PERDA, "-2", "Avaria");

        assertThat(t.produtos.buscar(admin, arroz).estoqueAtual()).isEqualByComparingTo("8");
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT motivo, saldo_posterior FROM movimento_estoque WHERE produto_id = ? ORDER BY id")) {
            ps.setLong(1, arroz);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("Estoque inicial");
                assertThat(rs.getBigDecimal(2)).isEqualByComparingTo("10");
                rs.next();
                assertThat(rs.getBigDecimal(2)).isEqualByComparingTo("8");
            }
        }
    }

    @Test
    void enumTipoMovimentoIgualAoCheckDoBanco() throws Exception {
        String definicao;
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_me_tipo'");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            definicao = rs.getString(1);
        }
        Set<String> doBanco = new HashSet<>();
        Matcher m = Pattern.compile("'(\\w+)'").matcher(definicao);
        while (m.find()) {
            doBanco.add(m.group(1));
        }
        Set<String> doJava = Arrays.stream(TipoMovimento.values()).map(Enum::name).collect(Collectors.toSet());
        assertThat(doJava).isEqualTo(doBanco);
    }
}
