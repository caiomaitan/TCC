package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.config.Servicos;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** Semente fixa (6.14): duas gerações no mesmo dia, em bancos zerados, produzem exatamente os mesmos dados. */
class GeradorDemoReproducaoIT extends IntegracaoBase {

    private static final Clock HOJE = Clock.fixed(Instant.parse("2026-10-17T15:00:00Z"), ZoneId.systemDefault());
    private static final String RESUMO = """
            SELECT (SELECT count(*) FROM venda) || '|' ||
                   (SELECT sum(valor_total) FROM venda WHERE status = 'FINALIZADA') || '|' ||
                   (SELECT count(*) FROM venda WHERE status = 'CANCELADA') || '|' ||
                   (SELECT count(*) FROM venda_item WHERE cancelado) || '|' ||
                   (SELECT sum(custo_total) FROM venda_item) || '|' ||
                   (SELECT sum(quantidade) FROM produto_estoque) || '|' ||
                   (SELECT sum(valor_contado) FROM caixa_sessao) || '|' ||
                   (SELECT count(*) FROM movimento_estoque) || '|' ||
                   (SELECT string_agg(to_char(data_hora, 'YYYY-MM-DD HH24:MI:SS'), ',' ORDER BY id) FROM venda)
            """;

    private String gerarEResumir() throws Exception {
        AcessoTeste t = new AcessoTeste(dataSource);
        t.limpar();
        Sessao admin = t.supervisor();
        Servicos.geradorDemo(dataSource, HOJE, AcessoTeste.TERMINAL, new Senhas(AcessoTeste.CUSTO_BCRYPT_TESTE))
                .gerar(admin, (feito, total, mensagem) -> { });
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(RESUMO)) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    void mesmaSementeMesmosDados() throws Exception {
        String primeira = gerarEResumir();
        String segunda = gerarEResumir();
        assertThat(segunda).isEqualTo(primeira);
    }
}
