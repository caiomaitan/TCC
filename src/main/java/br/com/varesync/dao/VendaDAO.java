package br.com.varesync.dao;

import br.com.varesync.model.Comprovante;
import br.com.varesync.model.StatusVenda;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.model.VendaResumo;
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
 * Vendas ({@code venda}, {@code venda_item}, {@code venda_pagamento}). Venda nunca é apagada: cancelar muda o status.
 * Os totais seguem {@code ck_venda_total} e {@code ck_vi_liquido}; o service calcula com as mesmas escalas.
 */
public class VendaDAO {

    /** Totais gravados em {@code venda}. */
    public record Totais(BigDecimal valorBruto, BigDecimal descontoItens, BigDecimal descontoVenda,
                         BigDecimal valorTotal, BigDecimal troco) {
    }

    /**
     * Valores de uma linha de {@code venda_item}. Item cancelado leva quando e quem cancelou e, se houve, quem liberou
     * (F10.3); nos demais, os três ficam nulos.
     */
    public record Item(int sequencia, long produtoId, BigDecimal quantidade, BigDecimal precoUnitario,
                       BigDecimal valorBruto, BigDecimal desconto, BigDecimal descontoRateado,
                       BigDecimal valorLiquido, BigDecimal custoUnitario, BigDecimal custoTotal, Long promocaoId,
                       boolean cancelado, OffsetDateTime canceladoEm, Long canceladoPor,
                       Long cancelamentoLiberadoPor) {
    }

    /** Venda travada para o cancelamento. */
    public record VendaTravada(long id, long caixaSessaoId, StatusVenda status, BigDecimal valorTotal) {
    }

    /** Item não cancelado, para estornar o estoque. */
    public record ItemVendido(long id, long produtoId, BigDecimal quantidade) {
    }

    /** Cabeçalho do comprovante; nome e CPF/CNPJ do cliente nulos sem identificação (o CPF pode faltar). */
    public record Cabecalho(long id, OffsetDateTime dataHora, String operador, String terminal, StatusVenda status,
                            OffsetDateTime canceladaEm, String motivoCancelamento, Totais totais, String clienteNome,
                            String clienteCpfCnpj) {
    }

    /** Pagamento gravado (valor abatido) com o tipo da forma, para devolver o troco ao dinheiro no comprovante. */
    public record PagamentoGravado(String forma, TipoFormaPagamento tipo, BigDecimal valor) {
    }

    /**
     * @param descontoLiberadoPor quem autorizou o desconto acima do limite (F10.3); nulo dentro do limite
     * @param clienteId           cliente identificado (F12.2); nulo sem identificação
     * @return id (número) da venda
     */
    public long inserir(Connection con, long caixaSessaoId, long usuarioId, OffsetDateTime dataHora, Totais t,
                        Long descontoLiberadoPor, Long clienteId) throws SQLException {
        String sql = """
                INSERT INTO venda (caixa_sessao_id, usuario_id, data_hora, status, valor_bruto, desconto_itens,
                                   desconto_venda, valor_total, troco, desconto_liberado_por, cliente_id)
                VALUES (?, ?, ?, 'FINALIZADA', ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, caixaSessaoId);
            ps.setLong(2, usuarioId);
            ps.setObject(3, dataHora);
            ps.setBigDecimal(4, t.valorBruto());
            ps.setBigDecimal(5, t.descontoItens());
            ps.setBigDecimal(6, t.descontoVenda());
            ps.setBigDecimal(7, t.valorTotal());
            ps.setBigDecimal(8, t.troco());
            Jdbc.definirLong(ps, 9, descontoLiberadoPor);
            Jdbc.definirLong(ps, 10, clienteId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** @return id do item */
    public long inserirItem(Connection con, long vendaId, Item i) throws SQLException {
        String sql = """
                INSERT INTO venda_item (venda_id, sequencia, produto_id, quantidade, preco_unitario, valor_bruto,
                                        desconto, desconto_rateado, valor_liquido, custo_unitario, custo_total,
                                        promocao_id, cancelado, cancelado_em, cancelado_por,
                                        cancelamento_liberado_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, vendaId);
            ps.setInt(2, i.sequencia());
            ps.setLong(3, i.produtoId());
            ps.setBigDecimal(4, i.quantidade());
            ps.setBigDecimal(5, i.precoUnitario());
            ps.setBigDecimal(6, i.valorBruto());
            ps.setBigDecimal(7, i.desconto());
            ps.setBigDecimal(8, i.descontoRateado());
            ps.setBigDecimal(9, i.valorLiquido());
            ps.setBigDecimal(10, i.custoUnitario());
            ps.setBigDecimal(11, i.custoTotal());
            Jdbc.definirLong(ps, 12, i.promocaoId());
            ps.setBoolean(13, i.cancelado());
            ps.setObject(14, i.canceladoEm());
            Jdbc.definirLong(ps, 15, i.canceladoPor());
            Jdbc.definirLong(ps, 16, i.cancelamentoLiberadoPor());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public void inserirPagamento(Connection con, long vendaId, int formaPagamentoId, BigDecimal valor)
            throws SQLException {
        String sql = """
                INSERT INTO venda_pagamento (venda_id, forma_pagamento_id, valor)
                VALUES (?, ?, ?)
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, vendaId);
            ps.setInt(2, formaPagamentoId);
            ps.setBigDecimal(3, valor);
            ps.executeUpdate();
        }
    }

    /** Venda com a linha travada até o fim da transação (dois cancelamentos simultâneos não estornam duas vezes). */
    public Optional<VendaTravada> travar(Connection con, long vendaId) throws SQLException {
        String sql = """
                SELECT id, caixa_sessao_id, status, valor_total
                  FROM venda
                 WHERE id = ?
                   FOR UPDATE
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, vendaId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new VendaTravada(rs.getLong("id"), rs.getLong("caixa_sessao_id"),
                        StatusVenda.valueOf(rs.getString("status")), rs.getBigDecimal("valor_total")));
            }
        }
    }

    /**
     * @param canceladaPor quem autorizou (o próprio operador ou quem liberou)
     * @param operadorId   quem executou o cancelamento (F10.3)
     */
    public void cancelar(Connection con, long vendaId, OffsetDateTime canceladaEm, long canceladaPor, long operadorId,
                         String motivo) throws SQLException {
        String sql = """
                UPDATE venda
                   SET status = 'CANCELADA', cancelada_em = ?, cancelada_por = ?, cancelamento_operador_id = ?,
                       motivo_cancelamento = ?
                 WHERE id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, canceladaEm);
            ps.setLong(2, canceladaPor);
            ps.setLong(3, operadorId);
            ps.setString(4, motivo);
            ps.setLong(5, vendaId);
            ps.executeUpdate();
        }
    }

    /** Itens não cancelados da venda, na ordem da sequência. */
    public List<ItemVendido> itensVendidos(Connection con, long vendaId) throws SQLException {
        String sql = """
                SELECT id, produto_id, quantidade
                  FROM venda_item
                 WHERE venda_id = ? AND NOT cancelado
                 ORDER BY sequencia
                """;
        List<ItemVendido> itens = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, vendaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    itens.add(new ItemVendido(rs.getLong("id"), rs.getLong("produto_id"),
                            rs.getBigDecimal("quantidade")));
                }
            }
        }
        return itens;
    }

    /** Vendas da sessão de caixa, a mais recente primeiro. */
    public List<VendaResumo> listarDaSessao(Connection con, long caixaSessaoId) throws SQLException {
        String sql = """
                SELECT v.id, v.data_hora, u.nome AS operador, v.valor_total, v.status,
                       (SELECT count(*) FROM venda_item vi WHERE vi.venda_id = v.id AND NOT vi.cancelado) AS itens
                  FROM venda v
                  JOIN usuario u ON u.id = v.usuario_id
                 WHERE v.caixa_sessao_id = ?
                 ORDER BY v.id DESC
                """;
        List<VendaResumo> vendas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, caixaSessaoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    vendas.add(new VendaResumo(rs.getLong("id"), rs.getObject("data_hora", OffsetDateTime.class),
                            rs.getString("operador"), rs.getInt("itens"), rs.getBigDecimal("valor_total"),
                            StatusVenda.valueOf(rs.getString("status"))));
                }
            }
        }
        return vendas;
    }

    public Optional<Cabecalho> cabecalho(Connection con, long vendaId) throws SQLException {
        String sql = """
                SELECT v.id, v.data_hora, u.nome AS operador, s.terminal, v.status, v.cancelada_em,
                       v.motivo_cancelamento, v.valor_bruto, v.desconto_itens, v.desconto_venda, v.valor_total,
                       v.troco, c.nome AS cliente_nome, c.cpf_cnpj AS cliente_cpf_cnpj
                  FROM venda v
                  JOIN usuario u      ON u.id = v.usuario_id
                  JOIN caixa_sessao s ON s.id = v.caixa_sessao_id
                  LEFT JOIN cliente c ON c.id = v.cliente_id
                 WHERE v.id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, vendaId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Cabecalho(rs.getLong("id"), rs.getObject("data_hora", OffsetDateTime.class),
                        rs.getString("operador"), rs.getString("terminal"),
                        StatusVenda.valueOf(rs.getString("status")),
                        rs.getObject("cancelada_em", OffsetDateTime.class), rs.getString("motivo_cancelamento"),
                        new Totais(rs.getBigDecimal("valor_bruto"), rs.getBigDecimal("desconto_itens"),
                                rs.getBigDecimal("desconto_venda"), rs.getBigDecimal("valor_total"),
                                rs.getBigDecimal("troco")), rs.getString("cliente_nome"),
                        rs.getString("cliente_cpf_cnpj")));
            }
        }
    }

    /** Itens do comprovante (inclusive os cancelados), na ordem de registro. */
    public List<Comprovante.Item> itensComprovante(Connection con, long vendaId) throws SQLException {
        String sql = """
                SELECT vi.sequencia,
                       coalesce((SELECT b.codigo FROM produto_codigo_barras b
                                  WHERE b.produto_id = p.id AND b.principal), p.id::text) AS codigo,
                       p.nome, vi.quantidade, p.unidade, u.fracionada, vi.preco_unitario, vi.valor_bruto,
                       vi.desconto, vi.cancelado
                  FROM venda_item vi
                  JOIN produto p        ON p.id = vi.produto_id
                  JOIN unidade_medida u ON u.sigla = p.unidade
                 WHERE vi.venda_id = ?
                 ORDER BY vi.sequencia
                """;
        List<Comprovante.Item> itens = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, vendaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    itens.add(new Comprovante.Item(rs.getInt("sequencia"), rs.getString("codigo"),
                            rs.getString("nome"), rs.getBigDecimal("quantidade"), rs.getString("unidade"),
                            rs.getBoolean("fracionada"), rs.getBigDecimal("preco_unitario"),
                            rs.getBigDecimal("valor_bruto"), rs.getBigDecimal("desconto"),
                            rs.getBoolean("cancelado")));
                }
            }
        }
        return itens;
    }

    /** Pagamentos gravados (valor abatido), na ordem de gravação. */
    public List<PagamentoGravado> pagamentos(Connection con, long vendaId) throws SQLException {
        String sql = """
                SELECT fp.descricao, fp.tipo, vp.valor
                  FROM venda_pagamento vp
                  JOIN forma_pagamento fp ON fp.id = vp.forma_pagamento_id
                 WHERE vp.venda_id = ?
                 ORDER BY vp.id
                """;
        List<PagamentoGravado> pagamentos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, vendaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pagamentos.add(new PagamentoGravado(rs.getString("descricao"),
                            TipoFormaPagamento.valueOf(rs.getString("tipo")), rs.getBigDecimal("valor")));
                }
            }
        }
        return pagamentos;
    }
}
