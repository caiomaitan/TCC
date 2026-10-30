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
 * R18 — Clientes (F12.7 e F13.3): por cliente identificado nas vendas do período, o número de compras, o valor
 * comprado, as devoluções (na data delas), o valor líquido, o ticket médio e a última compra do período. Parte de
 * {@code vw_resultado_item}, como o R01; vendas sem cliente ficam de fora. O nº de compras, o ticket médio e a última
 * compra contam só as vendas. O CPF aparece mascarado, como no comprovante.
 */
public final class RelClientes implements Relatorio<RelClientes.Linha> {

    /**
     * @param comprado     receita das compras do período
     * @param devolvido    devoluções do período das compras do cliente (positivo)
     * @param ultimaCompra nula se no período só houve devolução
     */
    public record Linha(long clienteId, String cliente, String cpfCnpj, long compras, BigDecimal comprado,
                        BigDecimal devolvido, OffsetDateTime ultimaCompra) {

        /** Valor líquido = comprado − devolvido. */
        public BigDecimal valor() {
            return comprado.subtract(devolvido);
        }

        public BigDecimal ticketMedio() {
            return Dinheiro.dividir(comprado, BigDecimal.valueOf(compras), Dinheiro.ESCALA_DINHEIRO).orElse(null);
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
        return "Clientes identificados nas vendas do período: número de compras, valor comprado, devoluções, valor "
                + "líquido, ticket médio e última compra.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.PERIODO);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros)
            throws SQLException {
        String sql = """
                SELECT c.id, c.nome, c.cpf_cnpj,
                       count(DISTINCT r.venda_id) FILTER (WHERE r.tipo = 'VENDA') AS compras,
                       coalesce(sum(r.receita) FILTER (WHERE r.tipo = 'VENDA'), 0) AS comprado,
                       coalesce(-sum(r.receita) FILTER (WHERE r.tipo = 'DEVOLUCAO'), 0) AS devolvido,
                       max(r.data_hora) FILTER (WHERE r.tipo = 'VENDA') AS ultima
                  FROM vw_resultado_item r
                  JOIN cliente c ON c.id = r.cliente_id
                 WHERE r.data_hora >= ? AND r.data_hora < ?
                 GROUP BY c.id, c.nome, c.cpf_cnpj
                 ORDER BY sum(r.receita) DESC, c.nome_busca, c.id
                """;
        List<Linha> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Consultas.periodo(ps, 1, filtros, parametros);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    linhas.add(new Linha(rs.getLong("id"), rs.getString("nome"), rs.getString("cpf_cnpj"),
                            rs.getLong("compras"), rs.getBigDecimal("comprado"), rs.getBigDecimal("devolvido"),
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
                Coluna.<Linha>inteiro("Compras", Linha::compras).comLargura(0.8),
                Coluna.moeda("Comprado", Linha::comprado),
                Coluna.moeda("Devoluções", Linha::devolvido),
                Coluna.moeda("Valor líquido", Linha::valor),
                Coluna.<Linha>de("Ticket médio", TipoColuna.MOEDA, Linha::ticketMedio).comTotal(linhas ->
                        Dinheiro.dividir(Coluna.soma(linhas, Linha::comprado),
                                BigDecimal.valueOf(linhas.stream().mapToLong(Linha::compras).sum()),
                                Dinheiro.ESCALA_DINHEIRO).orElse(null)),
                Coluna.<Linha>de("Última compra", TipoColuna.DATA_HORA, Linha::ultimaCompra));
    }
}
