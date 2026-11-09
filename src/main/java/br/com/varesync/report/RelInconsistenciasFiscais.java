package br.com.varesync.report;

import br.com.varesync.model.Permissao;
import br.com.varesync.model.PerfilTributario;
import br.com.varesync.service.ValidacaoFiscal;
import br.com.varesync.service.fiscal.ContextoFiscal;
import br.com.varesync.service.fiscal.Gravidade;
import br.com.varesync.service.fiscal.Inconsistencia;
import br.com.varesync.service.fiscal.ItemFiscal;
import br.com.varesync.service.fiscal.RegraFiscal;
import br.com.varesync.service.fiscal.RegrasFiscais;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * R30 — Inconsistências fiscais (F19.4): as 13 regras de {@link RegrasFiscais} em cada produto ativo, com o perfil
 * tributário dele e a empresa (regime e UF). A existência do NCM e do CEST nas tabelas importadas é conferida no banco;
 * o resto, no Java. Uma linha por inconsistência, na ordem do produto e da regra. Pede também a permissão FISCAL. O
 * painel (F19.5) usa {@link #produtosComErro} com a mesma consulta.
 */
public final class RelInconsistenciasFiscais implements Relatorio<RelInconsistenciasFiscais.Linha> {

    /** @param ncm como está gravado (sem pontos); nulo = vazio */
    public record Linha(long produtoId, String produto, String categoria, String ncm, String perfil, int regra,
                        String regraRotulo, Gravidade gravidade, String mensagem) {

        public boolean erro() {
            return gravidade == Gravidade.ERRO;
        }
    }

    @Override
    public String codigo() {
        return "R30";
    }

    @Override
    public String nome() {
        return "Inconsistências fiscais";
    }

    @Override
    public String descricao() {
        return "Cadastro fiscal incompleto ou combinações erradas no perfil (produtos ativos).";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.REGRA_FISCAL);
    }

    @Override
    public Set<Permissao> permissoesExtras() {
        return Set.of(Permissao.FISCAL);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros) throws SQLException {
        List<Linha> linhas = inconsistencias(con, ContextoFiscal.da(parametros.loja()));
        return filtros.regraFiscal() == null ? linhas
                : linhas.stream().filter(l -> l.regra() == filtros.regraFiscal()).toList();
    }

    /** Todas as inconsistências dos produtos ativos. */
    public static List<Linha> inconsistencias(Connection con, ContextoFiscal contexto) throws SQLException {
        List<Linha> linhas = new ArrayList<>();
        for (ItemFiscal item : itens(con)) {
            for (Inconsistencia i : RegrasFiscais.verificar(item, contexto)) {
                linhas.add(new Linha(item.produtoId(), item.produto(), item.categoria(), item.ncm(),
                        item.perfil() == null ? null : item.perfil().nome(), i.regra(),
                        RegrasFiscais.regra(i.regra()).map(RegraFiscal::rotulo).orElse(String.valueOf(i.regra())),
                        i.gravidade(), i.mensagem()));
            }
        }
        return linhas;
    }

    /** Alerta do painel (F19.5): quantos produtos ativos têm ao menos um erro (os avisos ficam só no R30). */
    public static int produtosComErro(Connection con, ContextoFiscal contexto) throws SQLException {
        return (int) inconsistencias(con, contexto).stream().filter(Linha::erro).map(Linha::produtoId).distinct()
                .count();
    }

    /**
     * Os produtos ativos com o perfil e o que o banco confere nas tabelas importadas. Os outros perfis do mesmo NCM
     * (regra 13) são calculados aqui, entre os produtos ativos com perfil.
     */
    static List<ItemFiscal> itens(Connection con) throws SQLException {
        String sql = """
                WITH tabelas AS (SELECT EXISTS (SELECT 1 FROM ncm) AS com_ncm, EXISTS (SELECT 1 FROM cest) AS com_cest)
                SELECT p.id, p.nome, c.nome AS categoria, p.ncm, p.cest, p.origem,
                       t.id AS perfil_id, t.nome AS perfil_nome, t.cfop, t.cst_icms, t.csosn, t.aliquota_icms,
                       t.reducao_base_icms, t.cst_pis, t.aliquota_pis, t.cst_cofins, t.aliquota_cofins,
                       t.codigo_beneficio, t.cst_ibs_cbs, t.cclass_trib, t.ativo AS perfil_ativo,
                       CASE WHEN NOT tb.com_ncm OR p.ncm IS NULL OR p.ncm !~ '^[0-9]{8}$' THEN NULL
                            ELSE EXISTS (SELECT 1 FROM ncm n WHERE n.codigo = p.ncm) END AS ncm_na_tabela,
                       CASE WHEN NOT tb.com_cest OR p.cest IS NULL OR p.cest !~ '^[0-9]{7}$' THEN NULL
                            ELSE EXISTS (SELECT 1 FROM cest x WHERE x.cest = p.cest) END AS cest_na_tabela,
                       CASE WHEN NOT tb.com_cest OR p.cest IS NULL OR p.cest !~ '^[0-9]{7}$'
                                 OR p.ncm IS NULL OR p.ncm !~ '^[0-9]{8}$' THEN NULL
                            ELSE EXISTS (SELECT 1 FROM cest x WHERE x.cest = p.cest AND p.ncm LIKE x.ncm || '%')
                       END AS cest_do_ncm
                  FROM produto p
                 CROSS JOIN tabelas tb
                  LEFT JOIN categoria c         ON c.id = p.categoria_id
                  LEFT JOIN perfil_tributario t ON t.id = p.perfil_tributario_id
                 WHERE p.ativo
                 ORDER BY p.nome_busca, p.id
                """;
        record Lido(long id, String nome, String categoria, String ncm, String cest, Integer origem,
                    PerfilTributario perfil, Boolean ncmNaTabela, Boolean cestNaTabela, Boolean cestDoNcm) {
        }
        List<Lido> lidos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long perfilId = rs.getLong("perfil_id");
                PerfilTributario perfil = rs.wasNull() ? null : new PerfilTributario(perfilId,
                        rs.getString("perfil_nome"), rs.getString("cfop"), rs.getString("cst_icms"),
                        rs.getString("csosn"), rs.getBigDecimal("aliquota_icms"), rs.getBigDecimal("reducao_base_icms"),
                        rs.getString("cst_pis"), rs.getBigDecimal("aliquota_pis"), rs.getString("cst_cofins"),
                        rs.getBigDecimal("aliquota_cofins"), rs.getString("codigo_beneficio"),
                        rs.getString("cst_ibs_cbs"), rs.getString("cclass_trib"), rs.getBoolean("perfil_ativo"), 0);
                int origem = rs.getInt("origem");
                Integer origemOuNula = rs.wasNull() ? null : origem;
                lidos.add(new Lido(rs.getLong("id"), rs.getString("nome"), rs.getString("categoria"),
                        rs.getString("ncm"), rs.getString("cest"), origemOuNula, perfil,
                        (Boolean) rs.getObject("ncm_na_tabela"), (Boolean) rs.getObject("cest_na_tabela"),
                        (Boolean) rs.getObject("cest_do_ncm")));
            }
        }
        // NCM → perfis (id → nome) dos produtos ativos com perfil, para a regra 13.
        Map<String, Map<Long, String>> perfisDoNcm = new HashMap<>();
        for (Lido l : lidos) {
            if (l.ncm() != null && l.perfil() != null) {
                perfisDoNcm.computeIfAbsent(l.ncm(), k -> new TreeMap<>()).put(l.perfil().id(), l.perfil().nome());
            }
        }
        List<ItemFiscal> itens = new ArrayList<>();
        for (Lido l : lidos) {
            List<String> outros = l.ncm() == null || l.perfil() == null ? List.of()
                    : perfisDoNcm.get(l.ncm()).entrySet().stream()
                    .filter(e -> !Objects.equals(e.getKey(), l.perfil().id())).map(Map.Entry::getValue).sorted()
                    .toList();
            itens.add(new ItemFiscal(l.id(), l.nome(), l.categoria(), l.ncm(), l.cest(), l.origem(), l.perfil(),
                    l.ncmNaTabela(), l.cestNaTabela(), l.cestDoNcm(), outros));
        }
        return itens;
    }

    /** "5 inconsistências (3 erros, 2 avisos)". */
    static String totalInconsistencias(List<Linha> linhas) {
        long erros = linhas.stream().filter(Linha::erro).count();
        long avisos = linhas.size() - erros;
        return (linhas.size() == 1 ? "1 inconsistência" : linhas.size() + " inconsistências") + " ("
                + (erros == 1 ? "1 erro" : erros + " erros") + ", " + (avisos == 1 ? "1 aviso" : avisos + " avisos")
                + ")";
    }

    /** "3 produtos". */
    static String totalProdutos(List<Linha> linhas) {
        long produtos = linhas.stream().map(Linha::produtoId).distinct().count();
        return produtos == 1 ? "1 produto" : produtos + " produtos";
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        return List.of(
                Coluna.<Linha>codigo("Código", Linha::produtoId).comLargura(0.95),
                Coluna.<Linha>texto("Produto", Linha::produto).comLargura(2.4)
                        .comTotal(RelInconsistenciasFiscais::totalProdutos),
                Coluna.<Linha>texto("Categoria", l -> l.categoria() == null ? "" : l.categoria()).comLargura(1.1),
                Coluna.<Linha>texto("NCM", l -> l.ncm() == null ? "—" : ValidacaoFiscal.formatarNcm(l.ncm()))
                        .comLargura(1.25),
                Coluna.<Linha>texto("Perfil", l -> l.perfil() == null ? "—" : l.perfil()).comLargura(1.7),
                Coluna.<Linha>texto("Regra", Linha::regraRotulo).comLargura(2.3),
                Coluna.<Linha>texto("Gravidade", l -> l.gravidade().rotulo()).comLargura(1.25),
                Coluna.<Linha>texto("Inconsistência", Linha::mensagem).comLargura(5.5)
                        .comTotal(RelInconsistenciasFiscais::totalInconsistencias));
    }
}
