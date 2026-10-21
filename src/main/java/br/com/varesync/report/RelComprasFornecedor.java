package br.com.varesync.report;

import br.com.varesync.util.CpfCnpj;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * R17 — Compras por fornecedor (7.3): só entradas {@code CONFIRMADA}, pela data da confirmação (quando a mercadoria
 * entrou no estoque); por fornecedor, o número de entradas, de itens (linhas das notas) e o valor (soma dos itens).
 */
public final class RelComprasFornecedor implements Relatorio<RelComprasFornecedor.Linha> {

    public record Linha(long fornecedorId, String fornecedor, String cpfCnpj, long entradas, long itens,
                        BigDecimal valor, OffsetDateTime ultimaEntrada) {

        public String documentoFormatado() {
            return cpfCnpj == null ? "" : CpfCnpj.formatar(cpfCnpj);
        }
    }

    @Override
    public String codigo() {
        return "R17";
    }

    @Override
    public String nome() {
        return "Compras por fornecedor";
    }

    @Override
    public String descricao() {
        return "Entradas de mercadoria confirmadas no período, por fornecedor: número de entradas, itens e valor "
                + "das notas.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.PERIODO, TipoFiltro.FORNECEDOR);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros)
            throws SQLException {
        String sql = """
                SELECT f.id, f.razao_social, f.cpf_cnpj, count(DISTINCT e.id) AS entradas, count(i.id) AS itens,
                       coalesce(sum(i.valor_total), 0) AS valor, max(e.confirmado_em) AS ultima
                  FROM entrada_mercadoria e
                  JOIN fornecedor f ON f.id = e.fornecedor_id
                  LEFT JOIN entrada_mercadoria_item i ON i.entrada_id = e.id
                 WHERE e.status = 'CONFIRMADA' AND e.confirmado_em >= ? AND e.confirmado_em < ?
                   AND (CAST(? AS BIGINT) IS NULL OR f.id = ?)
                 GROUP BY f.id, f.razao_social, f.cpf_cnpj
                 ORDER BY valor DESC, lower(f.razao_social)
                """;
        List<Linha> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Consultas.periodo(ps, 1, filtros, parametros);
            Consultas.opcional(ps, 3, filtros.fornecedorId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    linhas.add(new Linha(rs.getLong("id"), rs.getString("razao_social"), rs.getString("cpf_cnpj"),
                            rs.getLong("entradas"), rs.getLong("itens"), rs.getBigDecimal("valor"),
                            rs.getObject("ultima", OffsetDateTime.class)));
                }
            }
        }
        return linhas;
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        return List.of(
                Coluna.codigo("Código", Linha::fornecedorId),
                Coluna.texto("Fornecedor", Linha::fornecedor),
                Coluna.<Linha>texto("CPF/CNPJ", Linha::documentoFormatado).comLargura(1.8),
                Coluna.inteiro("Entradas", Linha::entradas),
                Coluna.inteiro("Itens", Linha::itens),
                Coluna.moeda("Valor", Linha::valor),
                Coluna.<Linha>de("Última entrada", TipoColuna.DATA_HORA, Linha::ultimaEntrada));
    }
}
