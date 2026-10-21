package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Leitura da planilha CSV de produtos (6.11): codificação, cabeçalho, aspas e números de linha. Sem banco. */
class PlanilhaProdutosTest {

    static final String CABECALHO =
            "nome;codigo_barras;unidade;categoria;custo;preco_venda;estoque_minimo;estoque_inicial";

    @Test
    void utf8ComBomEOModeloDeBaixar() {
        PlanilhaProdutos.Leitura leitura = PlanilhaProdutos.ler(PlanilhaProdutos.modelo());
        assertThat(leitura.codificacao()).isEqualTo("UTF-8");
        assertThat(leitura.linhas()).hasSize(2);
        PlanilhaProdutos.Linha cafe = leitura.linhas().get(0);
        assertThat(cafe.numero()).isEqualTo(2);
        assertThat(cafe.campo("nome")).isEqualTo("Café torrado 500 g");
        assertThat(cafe.campo("custo")).isEqualTo("12,5000");
        assertThat(leitura.linhas().get(1).campo("categoria")).isEqualTo("Frios e laticínios");
    }

    @Test
    void windows1252DoExcel() {
        byte[] conteudo = (CABECALHO + "\r\nAçúcar refinado;;UN;Mercearia;3,10;4,99;;\r\n")
                .getBytes(PlanilhaProdutos.WINDOWS_1252);
        PlanilhaProdutos.Leitura leitura = PlanilhaProdutos.ler(conteudo);
        assertThat(leitura.codificacao()).isEqualTo("Windows-1252");
        assertThat(leitura.linhas().get(0).campo("nome")).isEqualTo("Açúcar refinado");
    }

    @Test
    void aspasLinhasVaziasCamposFaltandoENumeroDaLinha() {
        String texto = "\"Nome\";Codigo_Barras;unidade;categoria;custo;preco_venda;estoque_minimo;estoque_inicial;\n"
                + "\n"
                + "\"Biscoito \"\"recheado\"\"; 140 g\";;UN;;1,20;2,50\n"
                + ";;;;;;;\n"
                + "\"Pão\nfrancês\";;KG;Padaria;5;12,90;;;extra\n"
                + "Leite;;UN;;3;5";
        List<PlanilhaProdutos.Linha> linhas = PlanilhaProdutos.ler(texto.getBytes(StandardCharsets.UTF_8)).linhas();
        assertThat(linhas).hasSize(3);
        assertThat(linhas.get(0).numero()).isEqualTo(3);
        assertThat(linhas.get(0).campo("nome")).isEqualTo("Biscoito \"recheado\"; 140 g");
        assertThat(linhas.get(0).campo("estoque_inicial")).isEmpty();
        assertThat(linhas.get(0).colunasDemais()).isFalse();
        assertThat(linhas.get(1).numero()).isEqualTo(5);
        assertThat(linhas.get(1).campo("nome")).isEqualTo("Pão\nfrancês");
        assertThat(linhas.get(1).colunasDemais()).isTrue();
        assertThat(linhas.get(2).numero()).isEqualTo(7); // a quebra dentro das aspas conta
        assertThat(linhas.get(2).campo("preco_venda")).isEqualTo("5");
    }

    @Test
    void cabecalhoDiferenteArquivoVazioESemProdutos() {
        assertThatThrownBy(() -> PlanilhaProdutos.ler("nome;preco\nCafé;5".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(NegocioException.class).hasMessageContaining("cabeçalho");
        assertThatThrownBy(() -> PlanilhaProdutos.ler(new byte[0])).hasMessage("O arquivo está vazio.");
        assertThatThrownBy(() -> PlanilhaProdutos.ler((CABECALHO + "\n\n").getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("nenhum produto");
    }
}
