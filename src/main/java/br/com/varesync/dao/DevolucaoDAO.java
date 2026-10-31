package br.com.varesync.dao;

import br.com.varesync.model.ComprovanteDevolucao;
import br.com.varesync.model.DevolucaoResumo;
import br.com.varesync.model.FormaReembolso;
import br.com.varesync.model.ItemDevolucao;
import br.com.varesync.model.StatusVenda;
import br.com.varesync.model.VendaDevolvivel;
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
 * Devoluções ({@code devolucao}, {@code devolucao_item}, V8). Imutáveis: o banco recusa UPDATE e DELETE, e o trigger
 * {@code trg_devolucao_item_confere} recusa devolver mais do que foi vendido.
 */
public class DevolucaoDAO {

    /** Até quantas vendas a busca da tela Devoluções mostra. */
    public static final int LIMITE_BUSCA = 500;

    /** Busca de vendas: número exato, ou período {@code [inicio, fim)} e, opcional, o nome ou CPF/CNPJ do cliente. */
    public record Filtro(Long vendaId, OffsetDateTime inicio, OffsetDateTime fimExclusivo, String clienteNome,
                         String clienteDocumento) {
    }

    /** Cabeçalho da venda para a devolução; cliente nulo sem identificação. */
    public record Venda(long id, OffsetDateTime dataHora, String operador, StatusVenda status, BigDecimal valorTotal,
                        Long clienteId, String clienteNome, String clienteCpfCnpj, boolean clienteAnonimizado) {
    }

    /** Valores de uma linha de {@code devolucao}. */
    public record Nova(long vendaId, long usuarioId, OffsetDateTime dataHora, String motivo, FormaReembolso forma,
                       BigDecimal valorTotal, Long clienteId, Long liberadoPor, String observacao,
                       Long caixaSessaoId, BigDecimal valorAbatido) {
    }

    /** Cabeçalho do comprovante da devolução. */
    public record Cabecalho(long id, OffsetDateTime dataHora, long vendaId, OffsetDateTime vendaDataHora,
                            String usuario, String liberadoPor, FormaReembolso forma, BigDecimal valorTotal,
                            String motivo, String observacao, Long clienteId, String clienteNome,
                            String clienteCpfCnpj, BigDecimal valorAbatido) {
    }

    /** Vendas finalizadas da busca, da mais recente para a mais antiga, com o valor já devolvido. */
    public List<VendaDevolvivel> pesquisar(Connection con, Filtro f) throws SQLException {
        String sql = """
                SELECT v.id, v.data_hora, u.nome AS operador, c.nome AS cliente, v.valor_total,
                       coalesce((SELECT sum(d.valor_total) FROM devolucao d WHERE d.venda_id = v.id), 0) AS devolvido
                  FROM venda v
                  JOIN usuario u      ON u.id = v.usuario_id
                  LEFT JOIN cliente c ON c.id = v.cliente_id
                 WHERE v.status = 'FINALIZADA'
                   AND (CAST(? AS BIGINT) IS NULL OR v.id = ?)
                   AND (CAST(? AS TIMESTAMPTZ) IS NULL OR v.data_hora >= ?)
                   AND (CAST(? AS TIMESTAMPTZ) IS NULL OR v.data_hora < ?)
                   AND (CAST(? AS VARCHAR) IS NULL OR c.nome_busca LIKE ? ESCAPE '\\')
                   AND (CAST(? AS VARCHAR) IS NULL OR c.cpf_cnpj LIKE ? ESCAPE '\\')
                 ORDER BY v.data_hora DESC, v.id DESC
                 LIMIT ?
                """;
        List<VendaDevolvivel> vendas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Jdbc.definirLong(ps, 1, f.vendaId());
            Jdbc.definirLong(ps, 2, f.vendaId());
            ps.setObject(3, f.inicio());
            ps.setObject(4, f.inicio());
            ps.setObject(5, f.fimExclusivo());
            ps.setObject(6, f.fimExclusivo());
            String nome = f.clienteNome() == null ? null : "%" + Jdbc.escaparLike(f.clienteNome()) + "%";
            ps.setString(7, nome);
            ps.setString(8, nome);
            String documento = f.clienteDocumento() == null ? null
                    : "%" + Jdbc.escaparLike(f.clienteDocumento()) + "%";
            ps.setString(9, documento);
            ps.setString(10, documento);
            ps.setInt(11, LIMITE_BUSCA);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    vendas.add(new VendaDevolvivel(rs.getLong("id"), rs.getObject("data_hora", OffsetDateTime.class),
                            rs.getString("operador"), rs.getString("cliente"), rs.getBigDecimal("valor_total"),
                            rs.getBigDecimal("devolvido")));
                }
            }
        }
        return vendas;
    }

    /**
     * @param travar trava a linha da venda até o fim da transação: duas devoluções (ou uma devolução e um
     *               cancelamento) da mesma venda não correm em paralelo
     */
    public Optional<Venda> venda(Connection con, long vendaId, boolean travar) throws SQLException {
        String sql = """
                SELECT v.id, v.data_hora, u.nome AS operador, v.status, v.valor_total, v.cliente_id,
                       c.nome AS cliente_nome, c.cpf_cnpj AS cliente_cpf_cnpj,
                       coalesce(c.anonimizado, FALSE) AS anonimizado
                  FROM venda v
                  JOIN usuario u      ON u.id = v.usuario_id
                  LEFT JOIN cliente c ON c.id = v.cliente_id
                 WHERE v.id = ?
                """ + (travar ? " FOR UPDATE OF v" : "");
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, vendaId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Venda(rs.getLong("id"), rs.getObject("data_hora", OffsetDateTime.class),
                        rs.getString("operador"), StatusVenda.valueOf(rs.getString("status")),
                        rs.getBigDecimal("valor_total"), Jdbc.lerLong(rs, "cliente_id"),
                        rs.getString("cliente_nome"), rs.getString("cliente_cpf_cnpj"), rs.getBoolean("anonimizado")));
            }
        }
    }

    /** Itens da venda (inclusive os cancelados), na ordem da sequência, com o que já foi devolvido de cada um. */
    public List<ItemDevolucao> itens(Connection con, long vendaId) throws SQLException {
        String sql = """
                SELECT vi.id, vi.sequencia, vi.produto_id,
                       coalesce((SELECT b.codigo FROM produto_codigo_barras b
                                  WHERE b.produto_id = p.id AND b.principal), p.id::text) AS codigo,
                       p.nome, p.unidade, um.fracionada, vi.cancelado, vi.quantidade, vi.valor_liquido,
                       vi.custo_unitario, vi.custo_total,
                       coalesce(d.quantidade, 0) AS devolvida, coalesce(d.valor, 0) AS valor_devolvido,
                       coalesce(d.custo, 0) AS custo_devolvido
                  FROM venda_item vi
                  JOIN produto p         ON p.id = vi.produto_id
                  JOIN unidade_medida um ON um.sigla = p.unidade
                  LEFT JOIN (SELECT venda_item_id, sum(quantidade) AS quantidade, sum(valor) AS valor,
                                    sum(custo_total) AS custo
                               FROM devolucao_item
                              GROUP BY venda_item_id) d ON d.venda_item_id = vi.id
                 WHERE vi.venda_id = ?
                 ORDER BY vi.sequencia
                """;
        List<ItemDevolucao> itens = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, vendaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    itens.add(new ItemDevolucao(rs.getLong("id"), rs.getInt("sequencia"), rs.getLong("produto_id"),
                            rs.getString("codigo"), rs.getString("nome"), rs.getString("unidade"),
                            rs.getBoolean("fracionada"), rs.getBoolean("cancelado"), rs.getBigDecimal("quantidade"),
                            rs.getBigDecimal("devolvida"), rs.getBigDecimal("valor_liquido"),
                            rs.getBigDecimal("valor_devolvido"), rs.getBigDecimal("custo_unitario"),
                            rs.getBigDecimal("custo_total"), rs.getBigDecimal("custo_devolvido")));
                }
            }
        }
        return itens;
    }

    /** @return id (número) da devolução */
    public long inserir(Connection con, Nova d) throws SQLException {
        String sql = """
                INSERT INTO devolucao (venda_id, usuario_id, data_hora, motivo, forma_reembolso, valor_total,
                                       cliente_id, liberado_por, observacao, caixa_sessao_id, valor_abatido)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, d.vendaId());
            ps.setLong(2, d.usuarioId());
            ps.setObject(3, d.dataHora());
            ps.setString(4, d.motivo());
            ps.setString(5, d.forma().name());
            ps.setBigDecimal(6, d.valorTotal());
            Jdbc.definirLong(ps, 7, d.clienteId());
            Jdbc.definirLong(ps, 8, d.liberadoPor());
            ps.setString(9, d.observacao());
            Jdbc.definirLong(ps, 10, d.caixaSessaoId());
            ps.setBigDecimal(11, d.valorAbatido());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** @return id do item da devolução */
    public long inserirItem(Connection con, long devolucaoId, long vendaItemId, BigDecimal quantidade, BigDecimal valor,
                            BigDecimal custoTotal, boolean voltaEstoque) throws SQLException {
        String sql = """
                INSERT INTO devolucao_item (devolucao_id, venda_item_id, quantidade, valor, custo_total, volta_estoque)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, devolucaoId);
            ps.setLong(2, vendaItemId);
            ps.setBigDecimal(3, quantidade);
            ps.setBigDecimal(4, valor);
            ps.setBigDecimal(5, custoTotal);
            ps.setBoolean(6, voltaEstoque);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Devoluções da venda, da mais antiga para a mais recente. */
    public List<DevolucaoResumo> daVenda(Connection con, long vendaId) throws SQLException {
        String sql = """
                SELECT d.id, d.data_hora, d.venda_id, u.nome AS usuario, d.forma_reembolso, d.valor_total, d.motivo,
                       (SELECT count(*) FROM devolucao_item di WHERE di.devolucao_id = d.id) AS itens
                  FROM devolucao d
                  JOIN usuario u ON u.id = d.usuario_id
                 WHERE d.venda_id = ?
                 ORDER BY d.data_hora, d.id
                """;
        List<DevolucaoResumo> devolucoes = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, vendaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    devolucoes.add(new DevolucaoResumo(rs.getLong("id"),
                            rs.getObject("data_hora", OffsetDateTime.class), rs.getLong("venda_id"),
                            rs.getString("usuario"), FormaReembolso.valueOf(rs.getString("forma_reembolso")),
                            rs.getBigDecimal("valor_total"), rs.getInt("itens"), rs.getString("motivo")));
                }
            }
        }
        return devolucoes;
    }

    public Optional<Cabecalho> cabecalho(Connection con, long devolucaoId) throws SQLException {
        String sql = """
                SELECT d.id, d.data_hora, d.venda_id, v.data_hora AS venda_data_hora, u.nome AS usuario,
                       l.nome AS liberado_por, d.forma_reembolso, d.valor_total, d.motivo, d.observacao,
                       d.cliente_id, c.nome AS cliente_nome, c.cpf_cnpj AS cliente_cpf_cnpj, d.valor_abatido
                  FROM devolucao d
                  JOIN venda v        ON v.id = d.venda_id
                  JOIN usuario u      ON u.id = d.usuario_id
                  LEFT JOIN usuario l ON l.id = d.liberado_por
                  LEFT JOIN cliente c ON c.id = d.cliente_id
                 WHERE d.id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, devolucaoId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Cabecalho(rs.getLong("id"), rs.getObject("data_hora", OffsetDateTime.class),
                        rs.getLong("venda_id"), rs.getObject("venda_data_hora", OffsetDateTime.class),
                        rs.getString("usuario"), rs.getString("liberado_por"),
                        FormaReembolso.valueOf(rs.getString("forma_reembolso")), rs.getBigDecimal("valor_total"),
                        rs.getString("motivo"), rs.getString("observacao"), Jdbc.lerLong(rs, "cliente_id"),
                        rs.getString("cliente_nome"), rs.getString("cliente_cpf_cnpj"),
                        rs.getBigDecimal("valor_abatido")));
            }
        }
    }

    /** Itens do comprovante da devolução, na ordem da sequência da venda. */
    public List<ComprovanteDevolucao.Item> itensComprovante(Connection con, long devolucaoId) throws SQLException {
        String sql = """
                SELECT vi.sequencia,
                       coalesce((SELECT b.codigo FROM produto_codigo_barras b
                                  WHERE b.produto_id = p.id AND b.principal), p.id::text) AS codigo,
                       p.nome, di.quantidade, p.unidade, um.fracionada, di.valor, NOT di.volta_estoque AS avariado
                  FROM devolucao_item di
                  JOIN venda_item vi     ON vi.id = di.venda_item_id
                  JOIN produto p         ON p.id = vi.produto_id
                  JOIN unidade_medida um ON um.sigla = p.unidade
                 WHERE di.devolucao_id = ?
                 ORDER BY vi.sequencia
                """;
        List<ComprovanteDevolucao.Item> itens = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, devolucaoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    itens.add(new ComprovanteDevolucao.Item(rs.getInt("sequencia"), rs.getString("codigo"),
                            rs.getString("nome"), rs.getBigDecimal("quantidade"), rs.getString("unidade"),
                            rs.getBoolean("fracionada"), rs.getBigDecimal("valor"), rs.getBoolean("avariado")));
                }
            }
        }
        return itens;
    }
}
