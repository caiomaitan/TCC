package br.com.varesync.report;

import br.com.varesync.model.StatusTitulo;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * R21 — Títulos a receber (F14.4): por situação, cliente e vencimento, com o que está em aberto em cada faixa de atraso:
 * a vencer, 1–30, 31–60, 61–90 e mais de 90 dias. A linha de totais é a posição da carteira.
 */
public final class RelTitulosReceber implements Relatorio<RelTitulosReceber.Linha> {

    public record Linha(String cliente, long vendaId, String parcela, LocalDate emissao, LocalDate vencimento,
                        StatusTitulo status, BigDecimal valor, BigDecimal pago, BigDecimal abatido, BigDecimal emAberto,
                        Long diasAtraso) {

        public String situacao() {
            return switch (status) {
                case ABERTO -> diasAtraso != null ? "Vencido" : "A vencer";
                case QUITADO -> "Quitado";
                case CANCELADO -> "Cancelado";
            };
        }

        /** O em aberto, se o atraso estiver na faixa de {@code de} a {@code ate} dias (sem atraso, a faixa 0). */
        BigDecimal naFaixa(long de, long ate) {
            long dias = diasAtraso == null ? 0 : diasAtraso;
            return status == StatusTitulo.ABERTO && dias >= de && dias <= ate ? emAberto : Dinheiro.ZERO;
        }
    }

    @Override
    public String codigo() {
        return "R21";
    }

    @Override
    public String nome() {
        return "Títulos a receber";
    }

    @Override
    public String descricao() {
        return "Parcelas do crediário por situação, cliente e vencimento, com o valor em aberto em cada faixa de atraso.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.SITUACAO_TITULO, TipoFiltro.CLIENTE, TipoFiltro.VENCIMENTO);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros) throws SQLException {
        FiltroCrediario f = filtros.crediario();
        String sql = """
                SELECT c.nome AS cliente, t.venda_id, t.parcela, t.total_parcelas, t.emissao, t.vencimento, t.status,
                       t.valor, t.valor_pago, t.valor_abatido
                  FROM titulo_receber t
                  JOIN cliente c ON c.id = t.cliente_id
                 WHERE (? = 'TODOS'
                        OR (? = 'EM_ABERTO' AND t.status = 'ABERTO')
                        OR (? = 'VENCIDOS' AND t.status = 'ABERTO' AND t.vencimento < ?)
                        OR (? = 'QUITADOS' AND t.status = 'QUITADO')
                        OR (? = 'CANCELADOS' AND t.status = 'CANCELADO'))
                   AND (CAST(? AS DATE) IS NULL OR t.vencimento >= ?)
                   AND (CAST(? AS DATE) IS NULL OR t.vencimento <= ?)
                   AND (CAST(? AS TEXT) IS NULL OR position(? IN c.nome_busca) > 0 OR position(? IN c.cpf_cnpj) > 0)
                 ORDER BY t.vencimento, c.nome_busca, t.venda_id, t.parcela
                """;
        String situacao = f.situacaoOuPadrao().name();
        String cliente = Textos.vazio(f.cliente()) ? null : Textos.normalizarBusca(f.cliente());
        String documento = cliente == null ? null : br.com.varesync.util.CpfCnpj.normalizar(f.cliente());
        List<Linha> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, situacao);
            ps.setString(2, situacao);
            ps.setString(3, situacao);
            ps.setObject(4, parametros.hoje());
            ps.setString(5, situacao);
            ps.setString(6, situacao);
            ps.setObject(7, f.vencimentoDe(), Types.DATE);
            ps.setObject(8, f.vencimentoDe(), Types.DATE);
            ps.setObject(9, f.vencimentoAte(), Types.DATE);
            ps.setObject(10, f.vencimentoAte(), Types.DATE);
            ps.setString(11, cliente);
            ps.setString(12, cliente);
            ps.setString(13, documento == null || documento.isEmpty() ? "#" : documento); // "#" nunca está num documento
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    StatusTitulo status = StatusTitulo.valueOf(rs.getString("status"));
                    LocalDate vencimento = rs.getObject("vencimento", LocalDate.class);
                    BigDecimal valor = rs.getBigDecimal("valor");
                    BigDecimal pago = rs.getBigDecimal("valor_pago");
                    BigDecimal abatido = rs.getBigDecimal("valor_abatido");
                    boolean aberto = status == StatusTitulo.ABERTO;
                    long dias = ChronoUnit.DAYS.between(vencimento, parametros.hoje());
                    linhas.add(new Linha(rs.getString("cliente"), rs.getLong("venda_id"),
                            rs.getInt("parcela") + "/" + rs.getInt("total_parcelas"),
                            rs.getObject("emissao", LocalDate.class), vencimento, status, valor, pago, abatido,
                            aberto ? valor.subtract(pago).subtract(abatido) : Dinheiro.ZERO,
                            aberto && dias > 0 ? Long.valueOf(dias) : null));
                }
            }
        }
        return linhas;
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        return List.of(
                Coluna.<Linha>texto("Cliente", Linha::cliente).comLargura(1.7),
                Coluna.<Linha>codigo("Venda", Linha::vendaId).comLargura(0.8),
                Coluna.<Linha>texto("Parcela", Linha::parcela).comLargura(0.85),
                Coluna.<Linha>de("Vencimento", TipoColuna.DATA, Linha::vencimento).comLargura(1.2),
                Coluna.<Linha>texto("Situação", Linha::situacao).comLargura(0.95),
                Coluna.<Linha>de("Atraso", TipoColuna.CODIGO, Linha::diasAtraso).comLargura(0.8),
                faixa("Em aberto", Linha::emAberto),
                faixa("A vencer", l -> l.naFaixa(0, 0)),
                faixa("1–30", l -> l.naFaixa(1, 30)),
                faixa("31–60", l -> l.naFaixa(31, 60)),
                faixa("61–90", l -> l.naFaixa(61, 90)),
                faixa("Mais de 90", l -> l.naFaixa(91, Long.MAX_VALUE)));
    }

    /** Coluna em reais, com largura para o total em negrito: são 12 colunas na largura da tela. */
    private static Coluna<Linha> faixa(String titulo, Function<Linha, BigDecimal> valor) {
        return Coluna.moeda(titulo, valor).comLargura(1.15);
    }
}
