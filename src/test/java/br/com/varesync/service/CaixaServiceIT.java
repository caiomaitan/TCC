package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.dao.CaixaDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.CaixaMovimento;
import br.com.varesync.model.CaixaSessao;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.RelatorioFechamento;
import br.com.varesync.model.StatusCaixa;
import br.com.varesync.model.TipoCaixaMovimento;
import br.com.varesync.model.VendasPorForma;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Caixa (6.5, primeira parte da Fase 4): abertura com fundo de troco, sangria e suprimento com liberação, fechamento
 * às cegas e relatório R11. O PDV ainda não existe: as vendas da sessão são gravadas por SQL, como o
 * {@code VendaService} vai gravá-las (venda + pagamentos), para conferir o dinheiro esperado (A.10).
 */
class CaixaServiceIT extends IntegracaoBase {

    private AcessoTeste t;
    private CaixaService caixa;
    private Sessao admin;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        caixa = t.caixa;
        admin = t.supervisor();
    }

    private static BigDecimal reais(String valor) {
        return new BigDecimal(valor);
    }

    private Sessao operador(String login, Permissao... permissoes) {
        EnumSet<Permissao> conjunto = EnumSet.noneOf(Permissao.class);
        conjunto.addAll(List.of(permissoes));
        t.usuarios.criar(admin, login, "Operador " + login, false, conjunto, "inicial1", "inicial1");
        return t.acesso.entrar(login, "inicial1");
    }

    private long sangria(Sessao sessao, String valor, String motivo) {
        return caixa.registrarMovimento(sessao, TipoCaixaMovimento.SANGRIA, reais(valor), motivo, null);
    }

    /**
     * Grava uma venda finalizada na sessão, como o PDV fará: venda com os totais e os pagamentos já líquidos de
     * troco ({@code venda_pagamento.valor}), por tipo de forma de pagamento.
     */
    private long venda(long caixaId, Sessao operador, String bruto, String descontoItens, String descontoVenda,
                       String troco, Map<String, String> pagamentos) throws Exception {
        BigDecimal total = reais(bruto).subtract(reais(descontoItens)).subtract(reais(descontoVenda));
        try (Connection con = dataSource.getConnection()) {
            long id;
            try (PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO venda (caixa_sessao_id, usuario_id, data_hora, valor_bruto, desconto_itens,
                                       desconto_venda, valor_total, troco)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                    """)) {
                ps.setLong(1, caixaId);
                ps.setLong(2, operador.usuarioId());
                ps.setObject(3, OffsetDateTime.now(t.relogio));
                ps.setBigDecimal(4, reais(bruto));
                ps.setBigDecimal(5, reais(descontoItens));
                ps.setBigDecimal(6, reais(descontoVenda));
                ps.setBigDecimal(7, total);
                ps.setBigDecimal(8, reais(troco));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    id = rs.getLong(1);
                }
            }
            for (Map.Entry<String, String> pagamento : pagamentos.entrySet()) {
                try (PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO venda_pagamento (venda_id, forma_pagamento_id, valor)
                        VALUES (?, (SELECT id FROM forma_pagamento WHERE tipo = ? ORDER BY id LIMIT 1), ?)
                        """)) {
                    ps.setLong(1, id);
                    ps.setString(2, pagamento.getKey());
                    ps.setBigDecimal(3, reais(pagamento.getValue()));
                    ps.executeUpdate();
                }
            }
            return id;
        }
    }

    /** Venda de 16,00 de A.10: 10,00 no PIX e o restante em dinheiro (entregou 10,00, troco 4,00). */
    private long vendaA10(long caixaId) throws Exception {
        Map<String, String> pagamentos = new LinkedHashMap<>();
        pagamentos.put("PIX", "10.00");
        pagamentos.put("DINHEIRO", "6.00");
        return venda(caixaId, admin, "16.00", "0", "0", "4.00", pagamentos);
    }

    private void cancelarPorSql(long vendaId) throws Exception {
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement("""
                     UPDATE venda SET status = 'CANCELADA', cancelada_em = ?, cancelada_por = ?,
                                      cancelamento_operador_id = ?, motivo_cancelamento = 'Cliente desistiu'
                      WHERE id = ?
                     """)) {
            ps.setObject(1, OffsetDateTime.now(t.relogio));
            ps.setLong(2, admin.usuarioId());
            ps.setLong(3, admin.usuarioId());
            ps.setLong(4, vendaId);
            ps.executeUpdate();
        }
    }

    // ----- Abertura -----

    @Test
    void abrirCaixaComCemReaisGravaASessaoDoTerminalEAuditoria() throws Exception {
        Instant abertura = t.relogio.instant();
        CaixaSessao aberto = caixa.abrir(admin, reais("100"));

        assertThat(aberto.aberta()).isTrue();
        assertThat(aberto.status()).isEqualTo(StatusCaixa.ABERTO);
        assertThat(aberto.terminal()).isEqualTo(AcessoTeste.TERMINAL);
        assertThat(aberto.valorAbertura()).isEqualByComparingTo("100.00");
        assertThat(aberto.abertoPorNome()).isEqualTo("Ana Admin");
        assertThat(aberto.abertoEm().toInstant()).isEqualTo(abertura);
        assertThat(aberto.valorEsperado()).isNull();
        assertThat(caixa.caixaAberto(admin)).map(CaixaSessao::id).contains(aberto.id());
        assertThat(t.auditoria(AcaoAuditoria.CAIXA_ABERTO)).containsExactly("Caixa nº " + aberto.id()
                + " aberto no terminal CAIXA-TESTE com fundo de troco de R$ 100,00");
    }

    @Test
    void fundoDeTrocoZeroEAceitoENegativoNao() {
        assertThatThrownBy(() -> caixa.abrir(admin, reais("-0.01")))
                .isInstanceOf(NegocioException.class).hasMessage("O fundo de troco não pode ser negativo.");
        assertThatThrownBy(() -> caixa.abrir(admin, null)).hasMessage("Informe o fundo de troco.");
        assertThatThrownBy(() -> caixa.abrir(admin, reais("10.005")))
                .hasMessage("O fundo de troco pode ter no máximo 2 casas decimais.");
        assertThat(caixa.caixaAberto(admin)).isEmpty();

        assertThat(caixa.abrir(admin, BigDecimal.ZERO).valorAbertura()).isEqualByComparingTo("0");
    }

    @Test
    void segundoCaixaNoMesmoTerminalRecusadoEOutroTerminalAbre() throws Exception {
        CaixaSessao primeiro = caixa.abrir(admin, reais("100"));

        assertThatThrownBy(() -> caixa.abrir(admin, reais("50")))
                .isInstanceOf(NegocioException.class)
                .hasMessageStartingWith("O caixa deste terminal já está aberto (nº " + primeiro.id()
                        + ", aberto por Ana Admin em ");
        // Dois logins abrindo ao mesmo tempo: o índice ux_caixa_aberto_por_terminal é a última barreira.
        assertThatThrownBy(() -> new Transacao(dataSource).executar(con -> new CaixaDAO().abrir(con,
                AcessoTeste.TERMINAL, admin.usuarioId(), reais("1"), OffsetDateTime.now(t.relogio), null)))
                .isInstanceOf(NegocioException.class)
                .hasMessageContaining("Já existe um caixa aberto neste terminal");

        Sessao outroComputador = new Sessao(admin.usuario(), "CAIXA-02");
        CaixaSessao segundo = caixa.abrir(outroComputador, reais("80"));
        assertThat(segundo.id()).isNotEqualTo(primeiro.id());
        assertThat(caixa.caixaAberto(outroComputador)).map(CaixaSessao::id).contains(segundo.id());
        assertThat(caixa.caixaAberto(admin)).map(CaixaSessao::id).contains(primeiro.id());
        assertThat(t.contar("SELECT count(*) FROM caixa_sessao WHERE status = 'ABERTO'")).isEqualTo(2);
    }

    @Test
    void quemEntraNoTerminalContinuaNaSessaoAberta() {
        CaixaSessao aberto = caixa.abrir(admin, reais("100"));
        Sessao carla = operador("carla", Permissao.PDV, Permissao.SANGRIA_SUPRIMENTO);

        assertThat(caixa.caixaAberto(carla)).map(CaixaSessao::id).contains(aberto.id());
        caixa.registrarMovimento(carla, TipoCaixaMovimento.SUPRIMENTO, reais("20"), "Reforço de troco", null);
        RelatorioFechamento fechamento = caixa.fechar(carla, reais("120"));

        assertThat(fechamento.sessao().id()).isEqualTo(aberto.id());
        assertThat(fechamento.sessao().abertoPorNome()).isEqualTo("Ana Admin");
        assertThat(fechamento.sessao().fechadoPorNome()).isEqualTo("Operador carla");
        assertThat(fechamento.movimentos()).extracting(CaixaMovimento::usuarioNome).containsExactly("Operador carla");
        assertThat(fechamento.situacao()).isEqualTo("Sem diferença");
    }

    // ----- Sangria e suprimento -----

    @Test
    void sangriaESuprimentoComValorEMotivoESangriaNaoPassaDoEsperado() throws Exception {
        CaixaSessao aberto = caixa.abrir(admin, reais("100"));
        caixa.registrarMovimento(admin, TipoCaixaMovimento.SUPRIMENTO, reais("20"), "  Reforço   de troco ", null);

        assertThatThrownBy(() -> sangria(admin, "120.01", "Recolhimento ao cofre"))
                .isInstanceOf(NegocioException.class)
                .hasMessage("O valor da sangria é maior que o dinheiro que deveria estar na gaveta. Confira o valor.");
        assertThatThrownBy(() -> sangria(admin, "0", "Recolhimento")).hasMessage("O valor deve ser maior que zero.");
        assertThatThrownBy(() -> sangria(admin, "-5", "Recolhimento")).hasMessage("O valor não pode ser negativo.");
        assertThatThrownBy(() -> sangria(admin, "10", "   ")).hasMessage("Informe o motivo da sangria.");
        assertThatThrownBy(() -> caixa.registrarMovimento(admin, TipoCaixaMovimento.SUPRIMENTO, reais("10"), null,
                null)).hasMessage("Informe o motivo do suprimento.");
        assertThatThrownBy(() -> sangria(admin, "10", "x".repeat(201)))
                .hasMessage("O motivo pode ter no máximo 200 caracteres.");
        assertThatThrownBy(() -> caixa.registrarMovimento(admin, null, reais("10"), "Teste", null))
                .hasMessage("Escolha o tipo: sangria ou suprimento.");

        sangria(admin, "120", "Recolhimento ao cofre"); // exatamente o esperado: pode

        List<CaixaMovimento> movimentos = caixa.movimentosDoCaixaAberto(admin);
        assertThat(movimentos).extracting(CaixaMovimento::tipo)
                .containsExactly(TipoCaixaMovimento.SUPRIMENTO, TipoCaixaMovimento.SANGRIA);
        assertThat(movimentos).extracting(m -> m.valor().toPlainString()).containsExactly("20.00", "120.00");
        assertThat(movimentos).extracting(CaixaMovimento::motivo)
                .containsExactly("Reforço de troco", "Recolhimento ao cofre");
        assertThat(t.auditoria(AcaoAuditoria.SUPRIMENTO)).containsExactly("Suprimento de R$ 20,00 no caixa nº "
                + aberto.id() + ". Motivo: Reforço de troco");
        assertThat(t.auditoria(AcaoAuditoria.SANGRIA)).containsExactly("Sangria de R$ 120,00 no caixa nº "
                + aberto.id() + ". Motivo: Recolhimento ao cofre");
    }

    @Test
    void semPermissaoDeSangriaPrecisaDeLiberacaoQueValeSoParaEla() throws Exception {
        CaixaSessao aberto = caixa.abrir(admin, reais("100"));
        Sessao caixa1 = operador("caixa1", Permissao.PDV);

        assertThatThrownBy(() -> sangria(caixa1, "30", "Recolhimento ao cofre"))
                .isInstanceOf(PermissaoNegadaException.class)
                .hasMessage("Você não tem permissão para usar a rotina Sangria e suprimento. Procure o supervisor.");
        Liberacao outraPermissao = t.acesso.liberar("admin", "segredo1", Permissao.DESCONTO_ACIMA_LIMITE);
        assertThatThrownBy(() -> caixa.registrarMovimento(caixa1, TipoCaixaMovimento.SANGRIA, reais("30"),
                "Recolhimento ao cofre", outraPermissao))
                .isInstanceOf(PermissaoNegadaException.class)
                .hasMessage("A liberação recebida não vale para Sangria e suprimento.");

        Liberacao liberacao = t.acesso.liberar("admin", "segredo1", Permissao.SANGRIA_SUPRIMENTO);
        assertThat(liberacao.permissao()).isEqualTo(Permissao.SANGRIA_SUPRIMENTO);
        caixa.registrarMovimento(caixa1, TipoCaixaMovimento.SANGRIA, reais("30"), "Recolhimento ao cofre",
                liberacao);

        assertThat(caixa.movimentosDoCaixaAberto(admin)).extracting(CaixaMovimento::usuarioNome)
                .containsExactly("Operador caixa1");
        assertThat(t.contar("SELECT count(*) FROM auditoria a JOIN usuario u ON u.id = a.usuario_id "
                + "WHERE a.acao = 'SANGRIA' AND u.login = 'caixa1'")).isEqualTo(1);
        assertThat(t.auditoria(AcaoAuditoria.SANGRIA)).containsExactly("Sangria de R$ 30,00 no caixa nº "
                + aberto.id() + ". Motivo: Recolhimento ao cofre (liberado por Ana Admin (admin))");
    }

    @Test
    void semPermissaoPdvNaoOperaOCaixaMasVeASituacao() {
        caixa.abrir(admin, reais("100"));
        Sessao gerente = operador("gerente", Permissao.SANGRIA_SUPRIMENTO, Permissao.RELATORIOS);
        String semPdv = "Você não tem permissão para usar a rotina PDV. Procure o supervisor.";

        assertThat(caixa.caixaAberto(gerente)).isPresent();
        assertThatThrownBy(() -> sangria(gerente, "10", "Recolhimento"))
                .isInstanceOf(PermissaoNegadaException.class).hasMessage(semPdv);
        assertThatThrownBy(() -> caixa.fechar(gerente, reais("100"))).hasMessage(semPdv);
        assertThatThrownBy(() -> caixa.movimentosDoCaixaAberto(gerente)).hasMessage(semPdv);
        assertThatThrownBy(() -> caixa.ultimoFechamento(gerente)).hasMessage(semPdv);
        Sessao outroComputador = new Sessao(gerente.usuario(), "CAIXA-02");
        assertThatThrownBy(() -> caixa.abrir(outroComputador, reais("10"))).hasMessage(semPdv);
    }

    @Test
    void semCaixaAbertoNaoHaSangriaFechamentoNemVenda() throws Exception {
        String semCaixa = "Não há caixa aberto neste terminal. Abra o caixa para continuar.";

        assertThatThrownBy(() -> sangria(admin, "10", "Recolhimento")).hasMessage(semCaixa);
        assertThatThrownBy(() -> caixa.fechar(admin, reais("0"))).hasMessage(semCaixa);
        assertThat(caixa.movimentosDoCaixaAberto(admin)).isEmpty();
        // O PDV chama travarAberta dentro da transação da venda.
        assertThatThrownBy(() -> new Transacao(dataSource).executar(con -> caixa.travarAberta(con, admin)))
                .isInstanceOf(NegocioException.class).hasMessage(semCaixa);

        CaixaSessao aberto = caixa.abrir(admin, reais("100"));
        long travada = new Transacao(dataSource).executar(con -> caixa.travarAberta(con, admin).id());
        assertThat(travada).isEqualTo(aberto.id());
        caixa.fechar(admin, reais("100"));
        assertThatThrownBy(() -> new Transacao(dataSource).executar(con -> caixa.travarAberta(con, admin)))
                .hasMessage(semCaixa);
        assertThat(t.contar("SELECT count(*) FROM caixa_movimento")).isZero();
    }

    // ----- Fechamento às cegas e R11 -----

    @Test
    void fechamentoAsCegasDoApendiceA10ComRelatorio() throws Exception {
        Instant abertura = t.relogio.instant();
        CaixaSessao aberto = caixa.abrir(admin, reais("100"));
        vendaA10(aberto.id());
        t.relogio.avancar(Duration.ofHours(2));
        Instant horaSangria = t.relogio.instant();
        // Com a venda, o esperado é 106,00: a sangria não pode passar disso.
        assertThatThrownBy(() -> sangria(admin, "106.01", "Recolhimento ao cofre"))
                .hasMessageStartingWith("O valor da sangria é maior");
        sangria(admin, "50", "Recolhimento ao cofre");
        t.relogio.avancar(Duration.ofHours(6));
        Instant horaFechamento = t.relogio.instant();

        RelatorioFechamento r = caixa.fechar(admin, reais("55"));

        CaixaSessao fechado = r.sessao();
        assertThat(fechado.status()).isEqualTo(StatusCaixa.FECHADO);
        assertThat(fechado.valorEsperado()).isEqualByComparingTo("56.00"); // 100 + 6 − 50 (A.10)
        assertThat(fechado.valorContado()).isEqualByComparingTo("55.00");
        assertThat(fechado.diferenca()).isEqualByComparingTo("-1.00"); // coluna gerada pelo banco
        assertThat(r.diferenca()).isEqualByComparingTo("-1.00");
        assertThat(r.situacao()).isEqualTo("Falta de R$ 1,00");
        assertThat(fechado.fechadoPorNome()).isEqualTo("Ana Admin");
        assertThat(fechado.abertoEm().toInstant()).isEqualTo(abertura);
        assertThat(fechado.fechadoEm().toInstant()).isEqualTo(horaFechamento);
        assertThat(r.movimentos()).singleElement().satisfies(m -> {
            assertThat(m.criadoEm().toInstant()).isEqualTo(horaSangria);
            assertThat(m.valor()).isEqualByComparingTo("50");
        });

        assertThat(r.resumo().valorAbertura()).isEqualByComparingTo("100");
        assertThat(r.resumo().vendasDinheiro()).isEqualByComparingTo("6");
        assertThat(r.resumo().suprimentos()).isEqualByComparingTo("0");
        assertThat(r.resumo().sangrias()).isEqualByComparingTo("50");
        assertThat(r.resumo().quantidadeVendas()).isEqualTo(1);
        assertThat(r.resumo().totalVendido()).isEqualByComparingTo("16");
        assertThat(r.resumo().quantidadeCanceladas()).isZero();
        assertThat(r.descontos()).isEqualByComparingTo("0");
        assertThat(r.vendasPorForma()).extracting(VendasPorForma::forma).containsExactly("Dinheiro", "PIX");
        assertThat(r.vendasPorForma()).extracting(f -> f.valor().toPlainString()).containsExactly("6.00", "10.00");
        assertThat(r.vendasPorForma()).extracting(VendasPorForma::quantidadeVendas).containsExactly(1L, 1L);

        assertThat(caixa.caixaAberto(admin)).isEmpty();
        assertThat(t.auditoria(AcaoAuditoria.CAIXA_FECHADO)).containsExactly("Caixa nº " + aberto.id()
                + " fechado: esperado R$ 56,00, contado R$ 55,00 (falta de R$ 1,00)");
        assertThatThrownBy(() -> caixa.fechar(admin, reais("55")))
                .hasMessage("Não há caixa aberto neste terminal. Abra o caixa para continuar.");
    }

    @Test
    void vendaCanceladaSaiDoEsperadoEDescontosEFormasEntramNoRelatorio() throws Exception {
        CaixaSessao aberto = caixa.abrir(admin, reais("100"));
        long cancelada = vendaA10(aberto.id());
        sangria(admin, "50", "Recolhimento ao cofre");
        cancelarPorSql(cancelada);
        venda(aberto.id(), admin, "20.00", "1.50", "0.50", "0", Map.of("CARTAO_CREDITO", "18.00"));

        RelatorioFechamento r = caixa.fechar(admin, reais("50"));

        assertThat(r.sessao().valorEsperado()).isEqualByComparingTo("50.00"); // A.10, venda cancelada
        assertThat(r.situacao()).isEqualTo("Sem diferença");
        assertThat(r.resumo().quantidadeVendas()).isEqualTo(1);
        assertThat(r.resumo().totalVendido()).isEqualByComparingTo("18.00");
        assertThat(r.resumo().quantidadeCanceladas()).isEqualTo(1);
        assertThat(r.resumo().vendasDinheiro()).isEqualByComparingTo("0");
        assertThat(r.descontos()).isEqualByComparingTo("2.00");
        assertThat(r.vendasPorForma()).singleElement().satisfies(f -> {
            assertThat(f.forma()).isEqualTo("Cartão de crédito");
            assertThat(f.valor()).isEqualByComparingTo("18.00");
        });
    }

    @Test
    void sobraNoFechamentoEUltimoFechamentoDoTerminal() {
        assertThat(caixa.ultimoFechamento(admin)).isEmpty();
        CaixaSessao primeiro = caixa.abrir(admin, reais("100"));
        assertThatThrownBy(() -> caixa.relatorioFechamento(admin, primeiro.id()))
                .hasMessage("O relatório de fechamento só fica disponível depois que o caixa é fechado.");
        assertThatThrownBy(() -> caixa.fechar(admin, reais("-1")))
                .hasMessage("O dinheiro contado não pode ser negativo.");

        RelatorioFechamento fechamento = caixa.fechar(admin, reais("102.5"));
        assertThat(fechamento.situacao()).isEqualTo("Sobra de R$ 2,50");

        CaixaSessao segundo = caixa.abrir(admin, reais("30"));
        assertThat(segundo.id()).isNotEqualTo(primeiro.id());
        assertThat(caixa.ultimoFechamento(admin)).map(r -> r.sessao().id()).contains(primeiro.id());
        assertThat(caixa.relatorioFechamento(admin, primeiro.id()).sessao().valorContado())
                .isEqualByComparingTo("102.50");
        assertThat(caixa.ultimoFechamento(new Sessao(admin.usuario(), "CAIXA-02"))).isEmpty();
    }

    @Test
    void enumsDoCaixaIguaisAosChecksDoBanco() throws Exception {
        assertThat(nomes(StatusCaixa.values())).isEqualTo(valoresDoCheck("ck_caixa_status"));
        assertThat(nomes(TipoCaixaMovimento.values())).isEqualTo(valoresDoCheck("ck_cm_tipo"));
    }

    private static Set<String> nomes(Enum<?>[] valores) {
        return Arrays.stream(valores).map(Enum::name).collect(Collectors.toSet());
    }

    private static Set<String> valoresDoCheck(String constraint) throws Exception {
        String definicao;
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?")) {
            ps.setString(1, constraint);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as(constraint).isTrue();
                definicao = rs.getString(1);
            }
        }
        Set<String> valores = new HashSet<>();
        Matcher m = Pattern.compile("'(\\w+)'").matcher(definicao);
        while (m.find()) {
            valores.add(m.group(1));
        }
        return valores;
    }
}
