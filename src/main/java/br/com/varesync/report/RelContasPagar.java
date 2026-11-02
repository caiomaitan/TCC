package br.com.varesync.report;

import br.com.varesync.model.Permissao;
import br.com.varesync.model.StatusTituloPagar;
import br.com.varesync.util.Dinheiro;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * R23 — Contas a pagar (F15.7): por situação (em aberto, vencidas, pagas, canceladas ou todas), categoria de despesa,
 * fornecedor e vencimento, com o valor, o pago e o que está em aberto. Exige também a permissão FINANCEIRO.
 */
public final class RelContasPagar implements Relatorio<RelContasPagar.Linha> {

    private static final DateTimeFormatter MES = DateTimeFormatter.ofPattern("MM/uuuu");

    public record Linha(long id, LocalDate vencimento, String descricao, String categoria, String fornecedor,
                        String documento, LocalDate competencia, StatusTituloPagar status, BigDecimal valor,
                        BigDecimal pago, BigDecimal emAberto, Long diasAtraso) {

        public String situacao() {
            return switch (status) {
                case ABERTO -> diasAtraso != null ? "Vencida" : "A vencer";
                case PAGO -> "Paga";
                case CANCELADO -> "Cancelada";
            };
        }

        public String competenciaTexto() {
            return MES.format(competencia);
        }
    }

    @Override
    public String codigo() {
        return "R23";
    }

    @Override
    public String nome() {
        return "Contas a pagar";
    }

    @Override
    public String descricao() {
        return "Contas a pagar por situação, categoria, fornecedor e vencimento, com o valor, o pago e o que está em "
                + "aberto (\"Quitados\" = pagas).";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.SITUACAO_TITULO, TipoFiltro.CATEGORIA_DESPESA, TipoFiltro.FORNECEDOR,
                TipoFiltro.VENCIMENTO);
    }

    @Override
    public Set<Permissao> permissoesExtras() {
        return Set.of(Permissao.FINANCEIRO);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros) throws SQLException {
        FiltroCrediario f = filtros.crediario();
        String sql = """
                SELECT t.id, t.vencimento, t.descricao, c.nome AS categoria, f.razao_social AS fornecedor, t.documento,
                       t.competencia, t.status, t.valor, t.valor_pago
                  FROM titulo_pagar t
                  JOIN categoria_despesa c ON c.id = t.categoria_id
                  LEFT JOIN fornecedor f ON f.id = t.fornecedor_id
                 WHERE (? = 'TODOS'
                        OR (? = 'EM_ABERTO' AND t.status = 'ABERTO')
                        OR (? = 'VENCIDOS' AND t.status = 'ABERTO' AND t.vencimento < ?)
                        OR (? = 'QUITADOS' AND t.status = 'PAGO')
                        OR (? = 'CANCELADOS' AND t.status = 'CANCELADO'))
                   AND (CAST(? AS BIGINT) IS NULL OR t.categoria_id = ?)
                   AND (CAST(? AS BIGINT) IS NULL OR t.fornecedor_id = ?)
                   AND (CAST(? AS DATE) IS NULL OR t.vencimento >= ?)
                   AND (CAST(? AS DATE) IS NULL OR t.vencimento <= ?)
                 ORDER BY t.vencimento, t.id
                """;
        String situacao = f.situacaoOuPadrao().name();
        List<Linha> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, situacao);
            ps.setString(2, situacao);
            ps.setString(3, situacao);
            ps.setObject(4, parametros.hoje());
            ps.setString(5, situacao);
            ps.setString(6, situacao);
            Consultas.opcional(ps, 7, filtros.financeiro().categoriaDespesaId());
            Consultas.opcional(ps, 9, filtros.fornecedorId());
            ps.setObject(11, f.vencimentoDe(), Types.DATE);
            ps.setObject(12, f.vencimentoDe(), Types.DATE);
            ps.setObject(13, f.vencimentoAte(), Types.DATE);
            ps.setObject(14, f.vencimentoAte(), Types.DATE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    StatusTituloPagar status = StatusTituloPagar.valueOf(rs.getString("status"));
                    LocalDate vencimento = rs.getObject("vencimento", LocalDate.class);
                    BigDecimal valor = rs.getBigDecimal("valor");
                    BigDecimal pago = rs.getBigDecimal("valor_pago");
                    boolean aberto = status == StatusTituloPagar.ABERTO;
                    long dias = ChronoUnit.DAYS.between(vencimento, parametros.hoje());
                    linhas.add(new Linha(rs.getLong("id"), vencimento, rs.getString("descricao"),
                            rs.getString("categoria"), rs.getString("fornecedor") == null ? ""
                            : rs.getString("fornecedor"), rs.getString("documento") == null ? ""
                            : rs.getString("documento"), rs.getObject("competencia", LocalDate.class), status, valor,
                            pago, aberto ? valor.subtract(pago) : Dinheiro.ZERO,
                            aberto && dias > 0 ? Long.valueOf(dias) : null));
                }
            }
        }
        return linhas;
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        return List.of(
                Coluna.<Linha>de("Vencimento", TipoColuna.DATA, Linha::vencimento).comLargura(1.1),
                Coluna.<Linha>texto("Descrição", Linha::descricao).comLargura(3.0),
                Coluna.<Linha>texto("Categoria", Linha::categoria).comLargura(1.6),
                Coluna.<Linha>texto("Fornecedor", Linha::fornecedor).comLargura(1.8),
                Coluna.<Linha>texto("Competência", Linha::competenciaTexto).comLargura(1.0),
                Coluna.<Linha>texto("Situação", Linha::situacao).comLargura(0.9),
                Coluna.<Linha>de("Atraso", TipoColuna.CODIGO, Linha::diasAtraso).comLargura(0.7),
                Coluna.moeda("Valor", Linha::valor).comLargura(1.15),
                Coluna.moeda("Pago", Linha::pago).comLargura(1.15),
                Coluna.moeda("Em aberto", Linha::emAberto).comLargura(1.15));
    }
}
