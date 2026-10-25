package br.com.varesync.dao;

import br.com.varesync.model.Cliente;
import br.com.varesync.model.ClienteVenda;
import br.com.varesync.model.HistoricoCliente;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Clientes ({@code cliente}, F12). Os horários vêm do Clock (V3: o trigger só usa {@code now()} fora do sistema). As
 * compras saem de {@code vw_venda_item_rentabilidade} (só vendas finalizadas e itens não cancelados).
 */
public class ClienteDAO {

    /** Valores gravados no INSERT/UPDATE, já normalizados e validados pelo service (opcionais nulos). */
    public record Gravacao(String nome, String cpfCnpj, String telefone, String email, LocalDate dataNascimento,
                           String cep, String logradouro, String numero, String complemento, String bairro,
                           String cidade, String uf, String observacao) {
    }

    /** Cliente que já usa um CPF/CNPJ. */
    public record Dono(long id, String nome) {
    }

    /**
     * Termos da busca (F12.1), já normalizados pelo service; vazios não filtram e, se todos estão vazios, vêm todos.
     *
     * @param nome      nome sem acento e minúsculo (contém)
     * @param documento CPF/CNPJ sem máscara (contém)
     * @param telefone  dígitos do telefone (contém)
     */
    public record Busca(String nome, String documento, String telefone, boolean incluirInativos) {
    }

    private static final String COLUNAS = """
            SELECT c.id, c.nome, c.cpf_cnpj, c.telefone, c.email, c.data_nascimento, c.cep, c.logradouro, c.numero,
                   c.complemento, c.bairro, c.cidade, c.uf, c.observacao, c.consentimento_marketing,
                   c.consentimento_em, c.anonimizado, c.ativo, c.criado_em, uc.nome AS criado_por_nome,
                   c.atualizado_em, ua.nome AS atualizado_por_nome
              FROM cliente c
              JOIN usuario uc ON uc.id = c.criado_por
              JOIN usuario ua ON ua.id = c.atualizado_por
            """;

    /**
     * @param consentimentoEm nulo sem consentimento de marketing
     * @return código (id) do cliente
     */
    public long inserir(Connection con, Gravacao g, OffsetDateTime consentimentoEm, long usuarioId,
                        OffsetDateTime agora) throws SQLException {
        String sql = """
                INSERT INTO cliente (nome, cpf_cnpj, telefone, email, data_nascimento, cep, logradouro, numero,
                                     complemento, bairro, cidade, uf, observacao, consentimento_marketing,
                                     consentimento_em, criado_em, criado_por, atualizado_em, atualizado_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int i = definir(ps, g, consentimentoEm);
            ps.setObject(i++, agora);
            ps.setLong(i++, usuarioId);
            ps.setObject(i++, agora);
            ps.setLong(i, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public void alterar(Connection con, long id, Gravacao g, OffsetDateTime consentimentoEm, long usuarioId,
                        OffsetDateTime agora) throws SQLException {
        String sql = """
                UPDATE cliente
                   SET nome = ?, cpf_cnpj = ?, telefone = ?, email = ?, data_nascimento = ?, cep = ?, logradouro = ?,
                       numero = ?, complemento = ?, bairro = ?, cidade = ?, uf = ?, observacao = ?,
                       consentimento_marketing = ?, consentimento_em = ?, atualizado_em = ?, atualizado_por = ?
                 WHERE id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int i = definir(ps, g, consentimentoEm);
            ps.setObject(i++, agora);
            ps.setLong(i++, usuarioId);
            ps.setLong(i, id);
            ps.executeUpdate();
        }
    }

    public void alterarSituacao(Connection con, long id, boolean ativo, long usuarioId, OffsetDateTime agora)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE cliente SET ativo = ?, atualizado_em = ?, atualizado_por = ? WHERE id = ?")) {
            ps.setBoolean(1, ativo);
            ps.setObject(2, agora);
            ps.setLong(3, usuarioId);
            ps.setLong(4, id);
            ps.executeUpdate();
        }
    }

    /** Apaga os dados pessoais (F12.6): nome trocado, o resto nulo, sem consentimento e inativo. */
    public void anonimizar(Connection con, long id, String nome, long usuarioId, OffsetDateTime agora)
            throws SQLException {
        String sql = """
                UPDATE cliente
                   SET nome = ?, cpf_cnpj = NULL, telefone = NULL, email = NULL, data_nascimento = NULL, cep = NULL,
                       logradouro = NULL, numero = NULL, complemento = NULL, bairro = NULL, cidade = NULL, uf = NULL,
                       observacao = NULL, consentimento_marketing = FALSE, consentimento_em = NULL,
                       anonimizado = TRUE, ativo = FALSE, atualizado_em = ?, atualizado_por = ?
                 WHERE id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, nome);
            ps.setObject(2, agora);
            ps.setLong(3, usuarioId);
            ps.setLong(4, id);
            ps.executeUpdate();
        }
    }

    public void excluir(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM cliente WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public Optional<Cliente> buscar(Connection con, long id) throws SQLException {
        return buscar(con, id, false);
    }

    /** Busca e trava a linha até o fim da transação. */
    public Optional<Cliente> buscarParaAtualizar(Connection con, long id) throws SQLException {
        return buscar(con, id, true);
    }

    private Optional<Cliente> buscar(Connection con, long id, boolean travar) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(COLUNAS + " WHERE c.id = ?"
                + (travar ? " FOR UPDATE OF c" : ""))) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(ler(rs)) : Optional.empty();
            }
        }
    }

    /**
     * Busca (F12.1): nome contém o termo (sem acento), CPF/CNPJ contém o documento ou telefone contém os dígitos;
     * ordenada pelo nome. Sem {@code incluirInativos}, só os ativos.
     */
    public List<Cliente> pesquisar(Connection con, Busca b) throws SQLException {
        String sql = COLUNAS + """
                 WHERE (? OR c.ativo)
                   AND ((CAST(? AS TEXT) IS NULL AND CAST(? AS TEXT) IS NULL AND CAST(? AS TEXT) IS NULL)
                        OR c.nome_busca LIKE ? ESCAPE '\\'
                        OR c.cpf_cnpj LIKE ? ESCAPE '\\'
                        OR c.telefone LIKE ? ESCAPE '\\')
                 ORDER BY c.nome_busca, c.id
                """;
        List<Cliente> clientes = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setBoolean(1, b.incluirInativos());
            String[] termos = {b.nome(), b.documento(), b.telefone()};
            for (int i = 0; i < termos.length; i++) {
                ps.setString(2 + i, termos[i]);
                if (termos[i] == null) {
                    ps.setNull(5 + i, Types.VARCHAR);
                } else {
                    ps.setString(5 + i, "%" + Jdbc.escaparLike(termos[i]) + "%");
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    clientes.add(ler(rs));
                }
            }
        }
        return clientes;
    }

    /** Cliente pelo CPF/CNPJ exato (sem máscara), em qualquer situação. */
    public Optional<ClienteVenda> buscarPorDocumento(Connection con, String cpfCnpj) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT id, nome, cpf_cnpj, ativo FROM cliente WHERE cpf_cnpj = ?")) {
            ps.setString(1, cpfCnpj);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(lerVenda(rs)) : Optional.empty();
            }
        }
    }

    /** O cliente como o PDV o vê, em qualquer situação. */
    public Optional<ClienteVenda> buscarParaVenda(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT id, nome, cpf_cnpj, ativo FROM cliente WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(lerVenda(rs)) : Optional.empty();
            }
        }
    }

    /** Outro cliente com o mesmo CPF/CNPJ ({@code ignorarId} = o próprio, na alteração). */
    public Optional<Dono> donoDoDocumento(Connection con, String cpfCnpj, Long ignorarId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT id, nome FROM cliente
                 WHERE cpf_cnpj = ? AND (CAST(? AS BIGINT) IS NULL OR id <> ?)
                """)) {
            ps.setString(1, cpfCnpj);
            Jdbc.definirLong(ps, 2, ignorarId);
            Jdbc.definirLong(ps, 3, ignorarId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Dono(rs.getLong(1), rs.getString(2))) : Optional.empty();
            }
        }
    }

    /** Quantas vendas (em qualquer situação) apontam para o cliente: com alguma, ele não pode ser excluído. */
    public int contarVendas(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT count(*) FROM venda WHERE cliente_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Compras do cliente, da mais recente para a mais antiga. */
    public List<HistoricoCliente.Compra> compras(Connection con, long id) throws SQLException {
        String sql = """
                SELECT r.venda_id, r.data_hora, count(*) AS itens, sum(r.receita) AS valor
                  FROM vw_venda_item_rentabilidade r
                 WHERE r.cliente_id = ?
                 GROUP BY r.venda_id, r.data_hora
                 ORDER BY r.data_hora DESC, r.venda_id DESC
                """;
        List<HistoricoCliente.Compra> compras = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    compras.add(new HistoricoCliente.Compra(rs.getLong("venda_id"),
                            rs.getObject("data_hora", OffsetDateTime.class), rs.getInt("itens"),
                            rs.getBigDecimal("valor")));
                }
            }
        }
        return compras;
    }

    /**
     * Produtos comprados pelo cliente: em quantas compras cada um aparece, a quantidade e o valor; do que aparece em
     * mais compras para o que aparece em menos (empate: maior valor, depois o nome).
     *
     * @param limite nulo = todos
     */
    public List<HistoricoCliente.ProdutoComprado> produtos(Connection con, long id, Integer limite)
            throws SQLException {
        String sql = """
                SELECT r.produto_id, r.produto_nome, r.unidade, u.fracionada, count(DISTINCT r.venda_id) AS compras,
                       sum(r.quantidade) AS quantidade, sum(r.receita) AS valor
                  FROM vw_venda_item_rentabilidade r
                  JOIN unidade_medida u ON u.sigla = r.unidade
                 WHERE r.cliente_id = ?
                 GROUP BY r.produto_id, r.produto_nome, r.unidade, u.fracionada
                 ORDER BY compras DESC, valor DESC, lower(r.produto_nome)
                 LIMIT ?
                """;
        List<HistoricoCliente.ProdutoComprado> produtos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, id);
            if (limite == null) {
                ps.setNull(2, Types.INTEGER);
            } else {
                ps.setInt(2, limite);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    produtos.add(new HistoricoCliente.ProdutoComprado(rs.getLong("produto_id"),
                            rs.getString("produto_nome"), rs.getString("unidade"), rs.getBoolean("fracionada"),
                            rs.getInt("compras"), rs.getBigDecimal("quantidade"), rs.getBigDecimal("valor")));
                }
            }
        }
        return produtos;
    }

    private static int definir(PreparedStatement ps, Gravacao g, OffsetDateTime consentimentoEm)
            throws SQLException {
        int i = 1;
        ps.setString(i++, g.nome());
        ps.setString(i++, g.cpfCnpj());
        ps.setString(i++, g.telefone());
        ps.setString(i++, g.email());
        ps.setObject(i++, g.dataNascimento());
        ps.setString(i++, g.cep());
        ps.setString(i++, g.logradouro());
        ps.setString(i++, g.numero());
        ps.setString(i++, g.complemento());
        ps.setString(i++, g.bairro());
        ps.setString(i++, g.cidade());
        ps.setString(i++, g.uf());
        ps.setString(i++, g.observacao());
        ps.setBoolean(i++, consentimentoEm != null);
        ps.setObject(i++, consentimentoEm);
        return i;
    }

    private static Cliente ler(ResultSet rs) throws SQLException {
        return new Cliente(rs.getLong("id"), rs.getString("nome"), rs.getString("cpf_cnpj"), rs.getString("telefone"),
                rs.getString("email"), rs.getObject("data_nascimento", LocalDate.class), rs.getString("cep"),
                rs.getString("logradouro"), rs.getString("numero"), rs.getString("complemento"),
                rs.getString("bairro"), rs.getString("cidade"), rs.getString("uf"), rs.getString("observacao"),
                rs.getBoolean("consentimento_marketing"), rs.getObject("consentimento_em", OffsetDateTime.class),
                rs.getBoolean("anonimizado"), rs.getBoolean("ativo"), rs.getObject("criado_em", OffsetDateTime.class),
                rs.getString("criado_por_nome"), rs.getObject("atualizado_em", OffsetDateTime.class),
                rs.getString("atualizado_por_nome"));
    }

    private static ClienteVenda lerVenda(ResultSet rs) throws SQLException {
        return new ClienteVenda(rs.getLong("id"), rs.getString("nome"), rs.getString("cpf_cnpj"),
                rs.getBoolean("ativo"));
    }
}
