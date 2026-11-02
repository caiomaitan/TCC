package br.com.varesync.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Acesso tipado às configurações (5.2): padrão da V2, V4 e V5, valores inválidos e ida e volta pela tabela. Sem banco. */
class ConfiguracoesTest {

    /** As linhas que a V2 insere, mais a da V4 (bloqueio por inatividade, F10.2) e as da V5 (senha, F10.6). */
    static Map<String, String> v2() {
        Map<String, String> m = new HashMap<>();
        m.put("empresa.nome", "Minha Loja");
        m.put("empresa.documento", "");
        m.put("empresa.endereco", "");
        m.put("empresa.telefone", "");
        m.put("venda.permitir_estoque_negativo", "true");
        m.put("venda.desconto_maximo_operador_pct", "5");
        m.put("venda.cancelar_item_exige_supervisor", "false");
        m.put("relatorio.margem_minima_pct", "15");
        m.put("relatorio.dias_sem_giro", "30");
        m.put("balanca.ativo", "false");
        m.put("balanca.prefixo", "2");
        m.put("balanca.digitos_codigo", "5");
        m.put("balanca.tipo_valor", "PRECO");
        m.put("backup.pasta", "");
        m.put("backup.pg_dump", "");
        m.put("backup.manter_ultimos", "15");
        m.put("comprovante.rodape", "Obrigado pela preferência!");
        m.put("seguranca.inatividade_minutos", "15");
        m.put("seguranca.senha_tamanho_minimo", "6");
        m.put("seguranca.senha_letra_e_numero", "false");
        m.put("fidelidade.ativo", "false");
        m.put("fidelidade.cashback_pct", "1");
        m.put("fidelidade.compra_minima", "0");
        m.put("devolucao.prazo_dias", "30");
        m.put("crediario.max_parcelas", "3");
        m.put("crediario.dias_primeira", "30");
        m.put("crediario.juros_mes_pct", "2");
        m.put("crediario.multa_pct", "2");
        m.put("crediario.carencia_dias", "0");
        m.put("crediario.bloqueio_atraso_dias", "30");
        m.put("financeiro.integrar_caixa", "false");
        return m;
    }

    /** V7 (F12.5): fidelidade desligada, 1% e sem compra mínima; percentual acima de 100 ou negativo volta ao padrão. */
    @Test
    void fidelidadeDaV7() {
        Map<String, String> m = v2();
        m.put("fidelidade.ativo", "true");
        m.put("fidelidade.cashback_pct", "2,5");
        m.put("fidelidade.compra_minima", "20.00");
        Configuracoes c = Configuracoes.de(m);
        assertThat(c.fidelidade().ativo()).isTrue();
        assertThat(c.fidelidade().cashbackPct()).isEqualByComparingTo("2.5");
        assertThat(c.fidelidade().compraMinima()).isEqualByComparingTo("20");
        assertThat(c.paraMapa()).containsEntry("fidelidade.cashback_pct", "2.5")
                .containsEntry("fidelidade.compra_minima", "20");
        m.put("fidelidade.cashback_pct", "101");
        m.put("fidelidade.compra_minima", "-1");
        assertThat(Configuracoes.de(m).fidelidade()).isEqualTo(new Configuracoes.Fidelidade(true, BigDecimal.ONE,
                BigDecimal.ZERO));
    }

    /** V8 (F13.2): prazo de devolução de 30 dias; fora de 0 a 365 volta ao padrão. */
    @Test
    void prazoDeDevolucaoDaV8() {
        Map<String, String> m = v2();
        m.put("devolucao.prazo_dias", "0");
        assertThat(Configuracoes.de(m).devolucaoPrazoDias()).isZero();
        m.put("devolucao.prazo_dias", "366");
        assertThat(Configuracoes.de(m).devolucaoPrazoDias()).isEqualTo(30);
        assertThat(Configuracoes.PADRAO.comDevolucaoPrazo(7).paraMapa()).containsEntry("devolucao.prazo_dias", "7");
    }

    /** V12 (F15.1): a integração do caixa vem desligada e é lida e gravada como as demais. */
    @Test
    void integracaoDoCaixaDaV12() {
        assertThat(Configuracoes.PADRAO.integrarCaixa()).isFalse();
        Map<String, String> m = v2();
        m.put("financeiro.integrar_caixa", "true");
        assertThat(Configuracoes.de(m).integrarCaixa()).isTrue();
        assertThat(Configuracoes.PADRAO.comIntegrarCaixa(true).paraMapa())
                .containsEntry("financeiro.integrar_caixa", "true");
    }

    /** V10 (F14): lê as chaves do crediário; parcelas fora de 1 a 60 voltam ao padrão. */
    @Test
    void crediarioDaV10() {
        Map<String, String> m = v2();
        m.put("crediario.max_parcelas", "6");
        m.put("crediario.juros_mes_pct", "1,5");
        Configuracoes c = Configuracoes.de(m);
        assertThat(c.crediario().maxParcelas()).isEqualTo(6);
        assertThat(c.crediario().jurosMesPct()).isEqualByComparingTo("1.5");
        assertThat(c.paraMapa()).containsEntry("crediario.max_parcelas", "6")
                .containsEntry("crediario.juros_mes_pct", "1.5");
        m.put("crediario.max_parcelas", "0");
        assertThat(Configuracoes.de(m).crediario().maxParcelas()).isEqualTo(3);
    }

    @Test
    void valoresDaV2SaoOPadrao() {
        assertThat(Configuracoes.de(v2())).isEqualTo(Configuracoes.PADRAO);
        assertThat(Configuracoes.PADRAO.paraMapa()).isEqualTo(v2());
        assertThat(Configuracoes.de(Map.of())).isEqualTo(Configuracoes.PADRAO); // tabela vazia
    }

    @Test
    void valorInvalidoVoltaAoPadrao() {
        Map<String, String> m = v2();
        m.put("venda.permitir_estoque_negativo", "talvez");
        m.put("venda.desconto_maximo_operador_pct", "abc");
        m.put("relatorio.margem_minima_pct", "100");
        m.put("relatorio.dias_sem_giro", "0");
        m.put("balanca.prefixo", "22");
        m.put("balanca.digitos_codigo", "7");
        m.put("balanca.tipo_valor", "VOLUME");
        m.put("seguranca.inatividade_minutos", "481");
        m.put("seguranca.senha_tamanho_minimo", "5");
        m.put("seguranca.senha_letra_e_numero", "sim");
        assertThat(Configuracoes.de(m)).isEqualTo(Configuracoes.PADRAO);
        m.put("seguranca.inatividade_minutos", "-1");
        m.put("seguranca.senha_tamanho_minimo", "51");
        assertThat(Configuracoes.de(m)).isEqualTo(Configuracoes.PADRAO);
    }

    @Test
    void lerEGravarSaoInversos() {
        Map<String, String> m = v2();
        m.put("empresa.nome", "  Mercado Bom Preço ");
        m.put("venda.permitir_estoque_negativo", "FALSE");
        m.put("venda.desconto_maximo_operador_pct", "7,5");
        m.put("venda.cancelar_item_exige_supervisor", "true");
        m.put("relatorio.margem_minima_pct", "12.25");
        m.put("relatorio.dias_sem_giro", "45");
        m.put("balanca.ativo", "true");
        m.put("balanca.digitos_codigo", "4");
        m.put("balanca.tipo_valor", "peso");
        m.put("seguranca.inatividade_minutos", "0");
        m.put("seguranca.senha_tamanho_minimo", " 8 ");
        m.put("seguranca.senha_letra_e_numero", "TRUE");
        Configuracoes c = Configuracoes.de(m);
        assertThat(c.inatividadeMinutos()).isZero(); // 0 desliga o bloqueio
        assertThat(c.politicaSenha()).isEqualTo(new PoliticaSenha(8, true));
        assertThat(c.loja().nome()).isEqualTo("Mercado Bom Preço");
        assertThat(c.venda().permitirEstoqueNegativo()).isFalse();
        assertThat(c.venda().descontoMaximoPct()).isEqualByComparingTo("7.5");
        assertThat(c.venda().cancelarItemExigeSupervisor()).isTrue();
        assertThat(c.margemMinimaPct()).isEqualByComparingTo("12.25");
        assertThat(c.diasSemGiro()).isEqualTo(45);
        assertThat(c.balanca()).isEqualTo(new Configuracoes.Balanca(true, 2, 4, TipoValorBalanca.PESO));
        assertThat(c.balanca().pluMaximo()).isEqualTo(9999);

        Map<String, String> gravado = c.paraMapa();
        assertThat(gravado).containsEntry("venda.desconto_maximo_operador_pct", "7.5")
                .containsEntry("relatorio.margem_minima_pct", "12.25")
                .containsEntry("balanca.tipo_valor", "PESO")
                .containsEntry("venda.permitir_estoque_negativo", "false")
                .containsEntry("seguranca.senha_tamanho_minimo", "8")
                .containsEntry("seguranca.senha_letra_e_numero", "true");
        assertThat(Configuracoes.de(gravado).paraMapa()).isEqualTo(gravado);
    }

    @Test
    void numeroInteiroSemCasasNaTabela() {
        Configuracoes c = Configuracoes.PADRAO.comRelatorios(new BigDecimal("20.00"), 30);
        assertThat(c.paraMapa()).containsEntry("relatorio.margem_minima_pct", "20");
    }
}
