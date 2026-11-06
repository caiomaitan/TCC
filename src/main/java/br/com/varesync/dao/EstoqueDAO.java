package br.com.varesync.dao;

import br.com.varesync.model.InconsistenciaEstoque;
import br.com.varesync.model.SaldoEstoque;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Consultas de saldo a partir de {@code vw_estoque_atual} e o diagnóstico ({@code vw_estoque_inconsistente}).
 * Só leitura: o saldo muda apenas pelo trigger de {@code movimento_estoque}.
 */
public class EstoqueDAO {

    /**
     * Filtros da consulta. {@code termoNome} já normalizado (minúsculo e sem acento); {@code digitos} só dígitos
     * (código interno ou código de barras exato).
     */
    public record Filtro(String termoNome, String digitos, boolean soAbaixoDoMinimo, boolean incluirInativos,
                         boolean soSemLoteNegativo) {

        public Filtro(String termoNome, String digitos, boolean soAbaixoDoMinimo, boolean incluirInativos) {
            this(termoNome, digitos, soAbaixoDoMinimo, incluirInativos, false);
        }
    }

    private static final String COLUNAS = """
            SELECT e.produto_id, e.nome, e.categoria_nome, e.unidade, u.fracionada, e.ativo, e.saldo,
                   e.estoque_minimo, e.custo, e.valor_em_estoque, e.abaixo_do_minimo, e.ultima_movimentacao
              FROM vw_estoque_atual e
              JOIN produto p        ON p.id = e.produto_id
              JOIN unidade_medida u ON u.sigla = e.unidade
            """;

    public List<SaldoEstoque> pesquisar(Connection con, Filtro filtro) throws SQLException {
        StringBuilder sql = new StringBuilder(COLUNAS).append(" WHERE TRUE");
        List<Object> parametros = new ArrayList<>();
        if (filtro.digitos() != null) {
            sql.append("""
                     AND (e.produto_id = ? OR EXISTS (SELECT 1 FROM produto_codigo_barras b
                                                       WHERE b.produto_id = e.produto_id AND b.codigo = ?))
                    """);
            parametros.add(ProdutoDAO.idOuNenhum(filtro.digitos()));
            parametros.add(filtro.digitos());
        } else if (filtro.termoNome() != null && !filtro.termoNome().isEmpty()) {
            sql.append(" AND p.nome_busca LIKE ? ESCAPE '\\'");
            parametros.add("%" + Jdbc.escaparLike(filtro.termoNome()) + "%");
        }
        if (filtro.soAbaixoDoMinimo()) {
            sql.append(" AND e.abaixo_do_minimo");
        }
        if (!filtro.incluirInativos()) {
            sql.append(" AND e.ativo");
        }
        if (filtro.soSemLoteNegativo()) {
            sql.append("""
                     AND p.controla_validade
                     AND EXISTS (SELECT 1 FROM lote l JOIN lote_saldo s ON s.lote_id = l.id
                                  WHERE l.produto_id = e.produto_id AND l.codigo = '' AND l.validade IS NULL
                                    AND s.saldo < 0)
                    """);
        }
        sql.append(" ORDER BY p.nome_busca, e.produto_id");

        List<SaldoEstoque> saldos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
            for (int i = 0; i < parametros.size(); i++) {
                ps.setObject(i + 1, parametros.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    saldos.add(ler(rs));
                }
            }
        }
        return saldos;
    }

    public Optional<SaldoEstoque> buscar(Connection con, long produtoId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(COLUNAS + " WHERE e.produto_id = ?")) {
            ps.setLong(1, produtoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(ler(rs)) : Optional.empty();
            }
        }
    }

    /**
     * Lê o saldo e trava a linha até o fim da transação (outro caixa espera), para que a diferença calculada em
     * "ajustar saldo para" valha no momento do movimento. É só leitura: quem altera o saldo é o trigger.
     */
    public Optional<BigDecimal> travarSaldo(Connection con, long produtoId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT quantidade FROM produto_estoque WHERE produto_id = ? FOR UPDATE")) {
            ps.setLong(1, produtoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getBigDecimal(1)) : Optional.empty();
            }
        }
    }

    /** Produtos ativos com saldo abaixo do mínimo (alerta de reposição). */
    public int contarAbaixoDoMinimo(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT count(*) FROM vw_estoque_atual WHERE ativo AND abaixo_do_minimo");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Linhas de {@code vw_estoque_inconsistente} (esperado: nenhuma). */
    public List<InconsistenciaEstoque> inconsistencias(Connection con) throws SQLException {
        String sql = """
                SELECT i.produto_id, p.nome, i.saldo_registrado, i.saldo_pelos_movimentos
                  FROM vw_estoque_inconsistente i
                  JOIN produto p ON p.id = i.produto_id
                 ORDER BY p.nome_busca, i.produto_id
                """;
        List<InconsistenciaEstoque> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                linhas.add(new InconsistenciaEstoque(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4)));
            }
        }
        return linhas;
    }

    private static SaldoEstoque ler(ResultSet rs) throws SQLException {
        return new SaldoEstoque(
                rs.getLong("produto_id"),
                rs.getString("nome"),
                rs.getString("categoria_nome"),
                rs.getString("unidade"),
                rs.getBoolean("fracionada"),
                rs.getBoolean("ativo"),
                rs.getBigDecimal("saldo"),
                rs.getBigDecimal("estoque_minimo"),
                rs.getBigDecimal("custo"),
                rs.getBigDecimal("valor_em_estoque"),
                rs.getBoolean("abaixo_do_minimo"),
                rs.getObject("ultima_movimentacao", OffsetDateTime.class));
    }
}
