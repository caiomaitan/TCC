package br.com.varesync.report;

import br.com.varesync.dao.NfceDAO;
import br.com.varesync.model.NotaFiscal;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.StatusNfce;
import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R32 — NFC-e emitidas (F23.10): as NFC-e com a emissão (dhEmi) no período, com número, série, data, valor, situação,
 * chave e protocolo, as mesmas linhas da aba Notas fiscais com o mesmo período. A linha de totais traz a quantidade e o
 * valor por situação. Pede também a permissão FISCAL, como o R30 e o R31.
 */
public final class RelNfceEmitidas implements Relatorio<NotaFiscal> {

    @Override
    public String codigo() {
        return "R32";
    }

    @Override
    public String nome() {
        return "NFC-e emitidas";
    }

    @Override
    public String descricao() {
        return "NFC-e emitidas no período, com a situação, a chave e o protocolo, e os totais por situação.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.PERIODO);
    }

    @Override
    public Set<Permissao> permissoesExtras() {
        return Set.of(Permissao.FISCAL);
    }

    @Override
    public List<NotaFiscal> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros)
            throws SQLException {
        String sql = "SELECT " + NfceDAO.COLUNAS_NOTA + """
                  FROM nfce n JOIN venda v ON v.id = n.venda_id
                 WHERE n.emitida_em >= ? AND n.emitida_em < ?
                 ORDER BY n.emitida_em, n.serie, n.numero
                """;
        List<NotaFiscal> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Consultas.periodo(ps, 1, filtros, parametros);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    linhas.add(NfceDAO.lerNota(rs));
                }
            }
        }
        return linhas;
    }

    /** "Autorizada: 3 (R$ 30,00) · Cancelada: 1 (R$ 5,49)", na ordem das situações. */
    static String totaisPorSituacao(List<NotaFiscal> linhas) {
        Map<StatusNfce, long[]> quantidades = new EnumMap<>(StatusNfce.class);
        Map<StatusNfce, BigDecimal> valores = new EnumMap<>(StatusNfce.class);
        for (NotaFiscal n : linhas) {
            quantidades.computeIfAbsent(n.status(), s -> new long[1])[0]++;
            valores.merge(n.status(), n.valor(), BigDecimal::add);
        }
        List<String> partes = new ArrayList<>();
        for (StatusNfce s : StatusNfce.values()) {
            if (quantidades.containsKey(s)) {
                partes.add(s.rotulo() + ": " + quantidades.get(s)[0] + " (" + Formatos.moeda(valores.get(s)) + ")");
            }
        }
        return String.join(" · ", partes);
    }

    @Override
    public List<Coluna<NotaFiscal>> colunas(Filtros filtros) {
        return List.of(
                Coluna.<NotaFiscal>codigo("Número", NotaFiscal::numero).comLargura(0.9),
                Coluna.<NotaFiscal>codigo("Série", NotaFiscal::serie).comLargura(0.6),
                Coluna.<NotaFiscal>de("Data/hora", TipoColuna.DATA_HORA, NotaFiscal::emitidaEm),
                Coluna.moeda("Valor", NotaFiscal::valor),
                Coluna.<NotaFiscal>texto("Situação", n -> n.status().rotulo()
                        + (n.contingencia() ? " (contingência)" : ""))
                        .comLargura(2.0).comTotal(RelNfceEmitidas::totaisPorSituacao),
                Coluna.<NotaFiscal>texto("Chave", NotaFiscal::chave).comLargura(4.2),
                Coluna.<NotaFiscal>texto("Protocolo", n -> n.protocolo() == null ? "" : n.protocolo())
                        .comLargura(1.6));
    }
}
