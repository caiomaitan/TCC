package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.OrigemMovimento;
import br.com.varesync.model.TipoMovimento;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link EstoqueService#movimentar}: o único caminho para movimentar estoque. Na Fase 2 só o estoque inicial o usa;
 * aqui ficam as validações que valem para todos os módulos.
 */
class EstoqueServiceIT extends IntegracaoBase {

    private AcessoTeste t;
    private Sessao admin;
    private long arroz;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        admin = t.supervisor();
        arroz = t.produtos.criar(admin, new DadosProduto("Arroz 5 kg", null, "UN", new BigDecimal("27.90"),
                new BigDecimal("21.50"), null, null, List.of(), null));
    }

    private long movimentar(TipoMovimento tipo, String quantidade, String motivo) throws Exception {
        try (Connection con = dataSource.getConnection()) {
            return t.estoque.movimentar(con, admin, arroz, tipo, new BigDecimal(quantidade), OrigemMovimento.NENHUMA,
                    motivo, null);
        }
    }

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
