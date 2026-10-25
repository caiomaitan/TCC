package br.com.varesync.report;

import br.com.varesync.util.CpfCnpj;
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
 * R18 — Clientes (F12.7): por cliente identificado nas vendas do período, o número de compras, o valor, o ticket
 * médio e a última compra do período. Parte de {@code vw_venda_item_rentabilidade} (só vendas finalizadas e itens
 * não cancelados), como o R01; vendas sem cliente ficam de fora. O CPF aparece mascarado, como no comprovante.
 */
public final class RelClientes implements Relatorio<RelClientes.Linha> {

    public record Linha(long clienteId, String cliente, String cpfCnpj, long compras, BigDecimal valor,
                        OffsetDateTime ultimaCompra) {

        public BigDecimal ticketMedio() {
            return Dinheiro.dividir(valor, BigDecimal.valueOf(compras), Dinheiro.ESCALA_DINHEIRO).orElse(null);
        }

        public String documentoOculto() {
            return cpfCnpj == null ? "" : CpfCnpj.ocultar(cpfCnpj);
        }
    }

    @Override
    public String codigo() {
        return "R18";
    }

    @Override
    public String nome() {
        return "Clientes";
    }

    @Override
    public String descricao() {
        return "Clientes identificados nas vendas do período: número de compras, valor, ticket médio e última "
                + "compra.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.PERIODO);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros)
            throws SQLException {
        String sql = """
                SELECT c.id, c.nome, c.cpf_cnpj, count(DISTINCT r.venda_id) AS compras, sum(r.receita) AS valor,
                       max(r.data_hora) AS ultima
                  FROM vw_venda_item_rentabilidade r
                  JOIN cliente c ON c.id = r.cliente_id
                 WHERE r.data_hora >= ? AND r.data_hora < ?
                 GROUP BY c.id, c.nome, c.cpf_cnpj
                 ORDER BY valor DESC, c.nome_busca, c.id
                """;
        List<Linha> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Consultas.periodo(ps, 1, filtros, parametros);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    linhas.add(new Linha(rs.getLong("id"), rs.getString("nome"), rs.getString("cpf_cnpj"),
                            rs.getLong("compras"), rs.getBigDecimal("valor"),
                            rs.getObject("ultima", OffsetDateTime.class)));
                }
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
                Coluna.inteiro("Compras", Linha::compras),
                Coluna.moeda("Valor", Linha::valor),
                Coluna.<Linha>de("Ticket médio", TipoColuna.MOEDA, Linha::ticketMedio).comTotal(linhas ->
                        Dinheiro.dividir(Coluna.soma(linhas, Linha::valor),
                                BigDecimal.valueOf(linhas.stream().mapToLong(Linha::compras).sum()),
                                Dinheiro.ESCALA_DINHEIRO).orElse(null)),
                Coluna.<Linha>de("Última compra", TipoColuna.DATA_HORA, Linha::ultimaCompra));
    }
}
