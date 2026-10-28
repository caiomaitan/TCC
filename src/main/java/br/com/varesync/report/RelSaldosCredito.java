package br.com.varesync.report;

import br.com.varesync.util.CpfCnpj;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * R19 — Saldos de crédito (F12.7): clientes com saldo de crédito diferente de zero, do maior para o menor, e o total
 * (o valor que a loja deve aos clientes). Saldo de {@code cliente_credito}, que só o razão altera; a data é a do último
 * movimento. Sem filtros: é a posição de agora. O CPF aparece mascarado, como no R18.
 */
public final class RelSaldosCredito implements Relatorio<RelSaldosCredito.Linha> {

    public record Linha(long clienteId, String cliente, String cpfCnpj, boolean ativo, BigDecimal saldo,
                        OffsetDateTime ultimoMovimento) {

        public String documentoOculto() {
            return cpfCnpj == null ? "" : CpfCnpj.ocultar(cpfCnpj);
        }

        public String situacao() {
            return ativo ? "Ativo" : "Inativo";
        }
    }

    @Override
    public String codigo() {
        return "R19";
    }

    @Override
    public String nome() {
        return "Saldos de crédito";
    }

    @Override
    public String descricao() {
        return "Clientes com saldo de crédito (cashback e ajustes) diferente de zero e o total devido aos clientes.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return Set.of();
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros)
            throws SQLException {
        String sql = """
                SELECT c.id, c.nome, c.cpf_cnpj, c.ativo, cc.saldo,
                       (SELECT max(m.criado_em) FROM cliente_credito_movimento m WHERE m.cliente_id = c.id) AS ultimo
                  FROM cliente_credito cc
                  JOIN cliente c ON c.id = cc.cliente_id
                 WHERE cc.saldo <> 0
                 ORDER BY cc.saldo DESC, c.nome_busca, c.id
                """;
        List<Linha> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                linhas.add(new Linha(rs.getLong("id"), rs.getString("nome"), rs.getString("cpf_cnpj"),
                        rs.getBoolean("ativo"), rs.getBigDecimal("saldo"),
                        rs.getObject("ultimo", OffsetDateTime.class)));
            }
        }
        return linhas;
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        return List.of(
                Coluna.codigo("Código", Linha::clienteId),
                Coluna.<Linha>texto("Cliente", Linha::cliente).comLargura(2.4),
                Coluna.<Linha>texto("CPF/CNPJ", Linha::documentoOculto).comLargura(1.8),
                Coluna.<Linha>texto("Situação", Linha::situacao),
                Coluna.moeda("Saldo", Linha::saldo),
                Coluna.<Linha>de("Último movimento", TipoColuna.DATA_HORA, Linha::ultimoMovimento));
    }
}
