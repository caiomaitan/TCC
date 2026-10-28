package br.com.varesync.report;

import br.com.varesync.dao.Jdbc;
import br.com.varesync.model.MovimentoKardex;
import br.com.varesync.model.TipoMovimento;
import br.com.varesync.service.NegocioException;
import br.com.varesync.util.Dinheiro;
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
 * R04 — Movimentação de estoque (7.3): os movimentos do kardex no período, com produto e tipo opcionais. Totais de
 * entradas e saídas valorizadas a custo (quantidade × custo do movimento); a quantidade só é somada com um produto
 * escolhido, porque unidades diferentes não se somam. Com um produto escolhido, a primeira linha é o "Saldo
 * anterior" ao início do período (F10.4), sem tipo, quantidade nem valores.
 */
public final class RelMovimentacao implements Relatorio<RelMovimentacao.Linha> {

    public record Linha(OffsetDateTime dataHora, long produtoId, String produto, String unidade, boolean fracionada,
                        TipoMovimento tipo, BigDecimal quantidade, BigDecimal saldoApos, BigDecimal custo,
                        String usuario, String origem) {

        /** Linha "Saldo anterior" (F10.4): data = início do período, saldo após = saldo anterior. */
        static Linha saldoAnterior(OffsetDateTime inicio, long produtoId, String produto, String unidade,
                                   boolean fracionada, BigDecimal saldo) {
            return new Linha(inicio, produtoId, produto, unidade, fracionada, null, null, saldo, null, "", "");
        }

        /** "Saldo anterior" ou a descrição do tipo. */
        public String descricaoTipo() {
            return tipo == null ? MovimentoKardex.SALDO_ANTERIOR : tipo.descricao();
        }

        /** Entrada valorizada a custo; nulo nas saídas e no saldo anterior. */
        public BigDecimal entrada() {
            return quantidade != null && quantidade.signum() > 0 ? Dinheiro.multiplicar(quantidade, custo) : null;
        }

        /** Saída valorizada a custo (positiva); nulo nas entradas e no saldo anterior. */
        public BigDecimal saida() {
            return quantidade != null && quantidade.signum() < 0 ? Dinheiro.multiplicar(quantidade.negate(), custo)
                    : null;
        }
    }

    @Override
    public String codigo() {
        return "R04";
    }

    @Override
    public String nome() {
        return "Movimentação de estoque";
    }

    @Override
    public String descricao() {
        return "Movimentos do estoque no período (vendas, cancelamentos, devoluções, ajustes, perdas, inventários), "
                + "com saldo após cada um e o valor a custo das entradas e saídas.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.PERIODO, TipoFiltro.PRODUTO, TipoFiltro.TIPO_MOVIMENTO);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros)
            throws SQLException {
        String sql = """
                SELECT m.criado_em, m.produto_id, p.nome AS produto, p.unidade, um.fracionada, m.tipo, m.quantidade,
                       m.saldo_posterior, coalesce(m.custo_unitario, 0) AS custo, u.nome AS usuario,
                       vi.venda_id, ii.inventario_id, ei.entrada_id, m.motivo, di.devolucao_id
                  FROM movimento_estoque m
                  JOIN produto p                       ON p.id = m.produto_id
                  JOIN unidade_medida um               ON um.sigla = p.unidade
                  JOIN usuario u                       ON u.id = m.usuario_id
                  LEFT JOIN venda_item vi              ON vi.id = m.venda_item_id
                  LEFT JOIN inventario_item ii         ON ii.id = m.inventario_item_id
                  LEFT JOIN entrada_mercadoria_item ei ON ei.id = m.entrada_item_id
                  LEFT JOIN devolucao_item di          ON di.id = m.devolucao_item_id
                 WHERE m.criado_em >= ? AND m.criado_em < ?
                   AND (CAST(? AS BIGINT) IS NULL OR m.produto_id = ?)
                   AND (CAST(? AS VARCHAR) IS NULL OR m.tipo = ?)
                 ORDER BY m.criado_em, m.id
                """;
        List<Linha> linhas = new ArrayList<>();
        if (filtros.produtoId() != null && filtros.periodo() != null) {
            linhas.add(saldoAnterior(con, filtros.produtoId(), filtros.periodo().inicioEm(parametros.zona())));
        }
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Consultas.periodo(ps, 1, filtros, parametros);
            Consultas.opcional(ps, 3, filtros.produtoId());
            Consultas.opcional(ps, 5, filtros.tipo() == null ? null : filtros.tipo().name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MovimentoKardex kardex = new MovimentoKardex(0, null, TipoMovimento.valueOf(rs.getString("tipo")),
                            rs.getBigDecimal("quantidade"), null, rs.getBigDecimal("saldo_posterior"),
                            rs.getBigDecimal("custo"), rs.getString("usuario"), Jdbc.lerLong(rs, "venda_id"),
                            Jdbc.lerLong(rs, "inventario_id"), Jdbc.lerLong(rs, "entrada_id"), rs.getString("motivo"),
                            Jdbc.lerLong(rs, "devolucao_id"));
                    linhas.add(new Linha(rs.getObject("criado_em", OffsetDateTime.class), rs.getLong("produto_id"),
                            rs.getString("produto"), rs.getString("unidade"), rs.getBoolean("fracionada"),
                            kardex.tipo(), kardex.quantidade(), kardex.saldoPosterior(), kardex.custoUnitario(),
                            kardex.usuarioNome(), kardex.origem()));
                }
            }
        }
        return linhas;
    }

    /** O saldo após o último movimento antes do início do período, ou 0 (mesma regra da tela Estoque). */
    private static Linha saldoAnterior(Connection con, long produtoId, OffsetDateTime inicio) throws SQLException {
        String sql = """
                SELECT p.nome, p.unidade, um.fracionada,
                       coalesce((SELECT m.saldo_posterior
                                   FROM movimento_estoque m
                                  WHERE m.produto_id = p.id
                                    AND m.criado_em < ?
                                  ORDER BY m.criado_em DESC, m.id DESC
                                  LIMIT 1), 0) AS saldo
                  FROM produto p
                  JOIN unidade_medida um ON um.sigla = p.unidade
                 WHERE p.id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, inicio);
            ps.setLong(2, produtoId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new NegocioException("Produto não encontrado.");
                }
                return Linha.saldoAnterior(inicio, produtoId, rs.getString("nome"), rs.getString("unidade"),
                        rs.getBoolean("fracionada"), rs.getBigDecimal("saldo"));
            }
        }
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        Coluna<Linha> quantidade = Coluna.quantidade("Quantidade", Linha::quantidade, Linha::fracionada);
        Coluna<Linha> saldo = Coluna.quantidade("Saldo após", Linha::saldoApos, Linha::fracionada);
        if (filtros.produto() != null) {
            quantidade = quantidade.comTotal(linhas -> Coluna.soma(linhas, Linha::quantidade));
        }
        return List.of(
                Coluna.<Linha>de("Data/hora", TipoColuna.DATA_HORA, Linha::dataHora),
                Coluna.texto("Produto", Linha::produto),
                Coluna.<Linha>texto("Tipo", Linha::descricaoTipo).comLargura(1.8),
                quantidade,
                saldo,
                Coluna.<Linha>de("Custo unit.", TipoColuna.CUSTO, Linha::custo),
                Coluna.moeda("Entradas (R$)", Linha::entrada),
                Coluna.moeda("Saídas (R$)", Linha::saida),
                Coluna.<Linha>texto("Usuário", Linha::usuario).comLargura(1.6),
                Coluna.<Linha>texto("Origem / motivo", Linha::origem).comLargura(2.2));
    }
}
