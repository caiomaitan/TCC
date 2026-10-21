package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.Produto;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Importação de produtos por planilha CSV (6.11): prévia linha a linha, erro na linha 3 não importa nada, ignorar
 * as linhas com erro, categoria criada, estoque inicial com motivo "Importação" e Windows-1252.
 */
class ImportacaoProdutosIT extends IntegracaoBase {

    private static final String CABECALHO = PlanilhaProdutosTest.CABECALHO;

    private AcessoTeste t;
    private ImportacaoProdutosService importacao;
    private Sessao admin;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        importacao = t.importacao;
        admin = t.supervisor();
    }

    private static byte[] csv(String... linhas) {
        return (CABECALHO + "\r\n" + String.join("\r\n", linhas) + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private long produtos() throws Exception {
        return t.contar("SELECT count(*) FROM produto");
    }

    @Test
    void planilhaValidaImportaTudoComCategoriaNovaEEstoqueInicial() throws Exception {
        byte[] planilha = csv(
                "Café torrado 500 g;7891000315507;UN;Mercearia;12,5000;18,90;5;24",
                "Queijo muçarela;;kg;Frios e laticínios;28,9;R$ 42,90;2;10,500",
                "Sabão em pó 1 kg;7891234567895;;Lavanderia;9,80;14,50;;");
        ImportacaoProdutosService.Previa previa = importacao.previa(admin, planilha);
        assertThat(previa.comErro()).isZero();
        assertThat(previa.validas()).isEqualTo(3);
        assertThat(previa.categoriasNovas()).containsExactly("Lavanderia");
        assertThat(previa.linhas().get(1).unidade()).isEqualTo("KG");
        assertThat(previa.linhas().get(2).unidade()).isEqualTo("UN"); // vazio = UN
        assertThat(produtos()).isZero(); // a prévia não grava

        ImportacaoProdutosService.Resultado r = importacao.importar(admin, planilha, false);
        assertThat(r.importados()).isEqualTo(3);
        assertThat(r.categoriasCriadas()).containsExactly("Lavanderia");
        assertThat(produtos()).isEqualTo(3);

        Produto cafe = t.produtos.buscar(admin, r.primeiroId());
        assertThat(cafe.nome()).isEqualTo("Café torrado 500 g");
        assertThat(cafe.categoriaNome()).isEqualTo("Mercearia");
        assertThat(cafe.precoVenda()).isEqualByComparingTo("18.90");
        assertThat(cafe.custo()).isEqualByComparingTo("12.5");
        assertThat(cafe.estoqueMinimo()).isEqualByComparingTo("5");
        assertThat(cafe.estoqueAtual()).isEqualByComparingTo("24");
        assertThat(cafe.codigoPrincipal()).hasValue("7891000315507");
        assertThat(t.produtos.buscar(admin, r.primeiroId() + 1).estoqueAtual()).isEqualByComparingTo("10.5");

        assertThat(t.contar("SELECT count(*) FROM movimento_estoque WHERE tipo = 'AJUSTE_ENTRADA' AND motivo = '"
                + ImportacaoProdutosService.MOTIVO_ESTOQUE + "'")).isEqualTo(2);
        assertThat(t.contar("SELECT count(*) FROM categoria WHERE nome = 'Lavanderia'")).isEqualTo(1);
        assertThat(t.auditoria(AcaoAuditoria.PRODUTO_CRIADO)).hasSize(3)
                .allMatch(d -> d.contains("pela importação de planilha (linha "));
    }

    @Test
    void erroNaLinha3ApontaLinhaECampoENaoImportaNada() throws Exception {
        byte[] planilha = csv(
                "Café torrado 500 g;7891000315507;UN;Mercearia;12,50;18,90;5;24",
                "Açúcar refinado 1 kg;;UN;Novidades;abc;4,99;;",
                "Arroz 5 kg;;UN;Mercearia;20;27,90;;1,5");
        ImportacaoProdutosService.Previa previa = importacao.previa(admin, planilha);
        assertThat(previa.comErro()).isEqualTo(2);
        ImportacaoProdutosService.Linha linha3 = previa.linhas().get(1);
        assertThat(linha3.numero()).isEqualTo(3);
        assertThat(linha3.erros()).singleElement().satisfies(e -> {
            assertThat(e.campo()).isEqualTo("custo");
            assertThat(e.mensagem()).contains("\"abc\" não é um número válido");
        });
        assertThat(previa.linhas().get(2).erros()).singleElement()
                .satisfies(e -> assertThat(e.campo()).isEqualTo("estoque_inicial"));

        assertThatThrownBy(() -> importacao.importar(admin, planilha, false))
                .isInstanceOf(NegocioException.class)
                .hasMessageContaining("erro em 2 linhas (a primeira: linha 3, custo:")
                .hasMessageContaining("Nada foi importado");
        assertThat(produtos()).isZero();
        assertThat(t.contar("SELECT count(*) FROM categoria WHERE nome = 'Novidades'")).isZero();

        // Ignorando as linhas com erro, só o café entra.
        ImportacaoProdutosService.Resultado r = importacao.importar(admin, planilha, true);
        assertThat(r.importados()).isEqualTo(1);
        assertThat(r.ignorados()).isEqualTo(2);
        assertThat(r.categoriasCriadas()).isEmpty(); // "Novidades" só estava numa linha com erro
        assertThat(produtos()).isEqualTo(1);
    }

    @Test
    void codigoDeBarrasRepetidoInvalidoUnidadeECategoriaInativa() throws Exception {
        long existente = t.produtos.criar(admin, new DadosProduto("Refrigerante", null, "UN", new BigDecimal("5.49"),
                new BigDecimal("3.20"), null, null, List.of(new CodigoBarras("7891000315507", true)), null));
        long limpeza = t.categorias.listar(admin, false).stream().filter(c -> c.nome().equals("Limpeza"))
                .findFirst().orElseThrow().id();
        t.categorias.alterarSituacao(admin, limpeza, false);

        ImportacaoProdutosService.Previa previa = importacao.previa(admin, csv(
                "Refrigerante;7891000315507;UN;;3;5",
                "Suco;7891234567890;UN;;3;5",
                "Água;7,89123E+12;UN;;1;2",
                "Leite;7891234567895;GALAO;;3;5",
                "Biscoito;7891234567895;UN;limpeza;2;3",
                ";;UN;;2;1;;;sobrou"));
        assertThat(previa.linhas()).hasSize(6);
        assertThat(previa.linhas().get(0).erros()).singleElement().asString()
                .contains("já pertence ao produto \"Refrigerante\" (código " + existente + ")");
        assertThat(previa.linhas().get(0).avisos()).contains("já existe produto com este nome");
        assertThat(previa.linhas().get(1).erros()).singleElement().asString().contains("dígito verificador");
        assertThat(previa.linhas().get(2).erros()).singleElement().asString().contains("notação científica");
        assertThat(previa.linhas().get(3).erros()).singleElement().asString()
                .startsWith("unidade: \"GALAO\" não é uma unidade cadastrada");
        assertThat(previa.linhas().get(4).erros()).extracting(ImportacaoProdutosService.Erro::campo)
                .containsExactly("codigo_barras", "categoria"); // repetido da linha 5 e categoria inativa
        assertThat(previa.linhas().get(4).erros().get(0).mensagem()).contains("já está na linha 5");
        assertThat(previa.linhas().get(5).erros()).extracting(ImportacaoProdutosService.Erro::campo)
                .containsExactly("linha", "nome");
        assertThat(previa.linhas().get(5).avisos()).contains("preço abaixo do custo");
        assertThat(previa.validas()).isZero();
        assertThatThrownBy(() -> importacao.importar(admin, csv("Suco;7891234567890;UN;;3;5"), true))
                .hasMessageContaining("Nenhuma linha");
    }

    @Test
    void windows1252ECategoriaSemDiferenciarMaiusculas() throws Exception {
        byte[] planilha = (CABECALHO + "\r\nFeijão carioca 1 kg;;UN;MERCEARIA;6,20;8,99;;\r\n")
                .getBytes(PlanilhaProdutos.WINDOWS_1252);
        ImportacaoProdutosService.Previa previa = importacao.previa(admin, planilha);
        assertThat(previa.codificacao()).isEqualTo("Windows-1252");
        assertThat(previa.linhas().get(0).categoria()).isEqualTo("Mercearia");
        assertThat(previa.linhas().get(0).categoriaNova()).isFalse();
        long id = importacao.importar(admin, planilha, false).primeiroId();
        assertThat(t.produtos.buscar(admin, id).nome()).isEqualTo("Feijão carioca 1 kg");
    }

    @Test
    void modeloEPermissao() {
        assertThat(new String(importacao.modelo(admin), StandardCharsets.UTF_8)).startsWith("﻿" + CABECALHO);
        assertThat(importacao.previa(admin, importacao.modelo(admin)).comErro()).isZero();
        t.usuarios.criar(admin, "caixa1", "Caixa", false, EnumSet.of(Permissao.PDV), "inicial1", "inicial1");
        Sessao caixa = t.acesso.entrar("caixa1", "inicial1");
        assertThatThrownBy(() -> importacao.previa(caixa, importacao.modelo(admin)))
                .isInstanceOf(PermissaoNegadaException.class);
    }
}
