package br.com.varesync.dao;

import br.com.varesync.model.StatusVenda;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Consultas da busca global (Ctrl+K, F21.3), cada uma com limite de linhas: a busca roda enquanto se digita e só
 * mostra os primeiros de cada grupo.
 */
public class BuscaGlobalDAO {

    /**
     * Produto achado. {@code codigoLido} e {@code quantidadeEmbalagem} vêm preenchidos quando ele foi achado pelo
     * código de barras (a quantidade é a da embalagem: 1 no código da unidade, 12 na caixa com 12).
     */
    public record ProdutoAchado(long id, String nome, String unidade, boolean fracionada, boolean ativo,
                                BigDecimal preco, Integer plu, String codigoLido, BigDecimal quantidadeEmbalagem) {
    }

    public record ClienteAchado(long id, String nome, String cpfCnpj, String cidade, String uf, boolean ativo) {
    }

    public record VendaAchada(long id, OffsetDateTime dataHora, StatusVenda status, BigDecimal total,
                              String clienteNome) {
    }

    /**
     * Produtos pelo nome sem acento ({@code nomeBusca}, parte dele) ou, com {@code digitos}, pelo código interno, pelo
     * código de barras exato (o da unidade ou o de uma embalagem) e pelo PLU. Primeiro os achados pelo código, depois
     * os ativos, pelo nome.
     *
     * @param nomeBusca termo já normalizado (minúsculas, sem acento); nulo = não procura no nome
     * @param digitos   só dígitos; nulo = não procura pelos códigos
     */
    public List<ProdutoAchado> produtos(Connection con, String nomeBusca, String digitos, int limite)
            throws SQLException {
        String sql = """
                SELECT p.id, p.nome, p.unidade, u.fracionada, p.ativo, p.preco_venda, p.plu,
                       b.codigo AS codigo_lido, b.quantidade AS quantidade_embalagem
                  FROM produto p
                  JOIN unidade_medida u ON u.sigla = p.unidade
                  LEFT JOIN produto_codigo_barras b ON b.produto_id = p.id AND b.codigo = CAST(? AS TEXT)
                 WHERE (CAST(? AS TEXT) IS NOT NULL AND p.nome_busca LIKE ? ESCAPE '\\')
                    OR b.codigo IS NOT NULL
                    OR p.id = ?
                    OR p.plu = ?
                 ORDER BY (b.codigo IS NOT NULL OR p.id = ? OR p.plu = ?) DESC, p.ativo DESC, p.nome_busca, p.id
                 LIMIT ?
                """;
        long id = digitos == null ? -1 : ProdutoDAO.idOuNenhum(digitos);
        int plu = digitos == null || digitos.length() > 6 ? -1 : Integer.parseInt(digitos);
        List<ProdutoAchado> achados = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, digitos);
            ps.setString(2, nomeBusca);
            if (nomeBusca == null) {
                ps.setNull(3, Types.VARCHAR);
            } else {
                ps.setString(3, "%" + Jdbc.escaparLike(nomeBusca) + "%");
            }
            ps.setLong(4, id);
            ps.setInt(5, plu);
            ps.setLong(6, id);
            ps.setInt(7, plu);
            ps.setInt(8, limite);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int lido = rs.getInt("plu");
                    achados.add(new ProdutoAchado(rs.getLong("id"), rs.getString("nome"), rs.getString("unidade"),
                            rs.getBoolean("fracionada"), rs.getBoolean("ativo"), rs.getBigDecimal("preco_venda"),
                            rs.wasNull() ? null : lido, rs.getString("codigo_lido"),
                            rs.getBigDecimal("quantidade_embalagem")));
                }
            }
        }
        return achados;
    }

    /**
     * Clientes pelo nome sem acento (parte dele) ou pelo CPF/CNPJ (parte dele, sem máscara). Os anonimizados (F12.6)
     * ficam de fora: não têm mais dados pessoais para achar. Primeiro os ativos, pelo nome.
     *
     * @param documento CPF/CNPJ sem máscara (ou parte); nulo = não procura pelo documento
     */
    public List<ClienteAchado> clientes(Connection con, String nomeBusca, String documento, int limite)
            throws SQLException {
        String sql = """
                SELECT c.id, c.nome, c.cpf_cnpj, c.cidade, c.uf, c.ativo
                  FROM cliente c
                 WHERE NOT c.anonimizado
                   AND (c.nome_busca LIKE ? ESCAPE '\\'
                        OR (CAST(? AS TEXT) IS NOT NULL AND c.cpf_cnpj LIKE ? ESCAPE '\\'))
                 ORDER BY c.ativo DESC, c.nome_busca, c.id
                 LIMIT ?
                """;
        List<ClienteAchado> achados = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, "%" + Jdbc.escaparLike(nomeBusca) + "%");
            ps.setString(2, documento);
            if (documento == null) {
                ps.setNull(3, Types.VARCHAR);
            } else {
                ps.setString(3, "%" + Jdbc.escaparLike(documento) + "%");
            }
            ps.setInt(4, limite);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    achados.add(new ClienteAchado(rs.getLong("id"), rs.getString("nome"), rs.getString("cpf_cnpj"),
                            rs.getString("cidade"), rs.getString("uf"), rs.getBoolean("ativo")));
                }
            }
        }
        return achados;
    }

    /** Venda pelo número (finalizada ou cancelada). */
    public Optional<VendaAchada> venda(Connection con, long numero) throws SQLException {
        String sql = """
                SELECT v.id, v.data_hora, v.status, v.valor_total, c.nome AS cliente_nome
                  FROM venda v
                  LEFT JOIN cliente c ON c.id = v.cliente_id AND NOT c.anonimizado
                 WHERE v.id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, numero);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new VendaAchada(rs.getLong("id"), rs.getObject("data_hora", OffsetDateTime.class),
                        StatusVenda.valueOf(rs.getString("status")), rs.getBigDecimal("valor_total"),
                        rs.getString("cliente_nome")));
            }
        }
    }
}
