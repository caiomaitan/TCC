package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.TipoAjuste;
import br.com.varesync.model.UnidadeCadastro;
import br.com.varesync.model.UnidadeMedida;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unidades de medida (F10.5): sigla única de 1 a 6 letras maiúsculas ou dígitos, descrição e fracionada; sigla,
 * fracionada e exclusão só sem uso.
 */
class UnidadeServiceIT extends IntegracaoBase {

    private AcessoTeste t;
    private UnidadeService unidades;
    private Sessao admin;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        unidades = t.unidades;
        admin = t.supervisor();
    }

    private UnidadeCadastro buscar(String sigla) {
        return unidades.listar(admin).stream().filter(u -> u.unidade().sigla().equals(sigla)).findFirst()
                .orElseThrow();
    }

    private long produto(String nome, String unidade) {
        return t.produtos.criar(admin, new DadosProduto(nome, null, unidade, new BigDecimal("9.90"),
                new BigDecimal("5.00"), BigDecimal.ZERO, null, List.of(), null));
    }

    @Test
    void listaAsUnidadesDaV2ComUnPrimeiroEOUsoDeCada() {
        produto("Arroz 5 kg", "UN");
        produto("Queijo muçarela", "KG");
        produto("Presunto", "KG");

        List<UnidadeCadastro> lista = unidades.listar(admin);
        assertThat(lista).extracting(u -> u.unidade().sigla())
                .containsExactly("UN", "CX", "DZ", "FD", "KG", "L", "M", "PCT");
        assertThat(buscar("KG").produtos()).isEqualTo(2);
        assertThat(buscar("UN").produtos()).isEqualTo(1);
        assertThat(buscar("CX").emUso()).isFalse();
    }

    @Test
    void criaComSiglaEmMaiusculasUnicaEValida() {
        UnidadeMedida pc = unidades.criar(admin, "  pc ", "  Peça  avulsa ", false);
        assertThat(pc).isEqualTo(new UnidadeMedida("PC", "Peça avulsa", false));
        assertThat(buscar("PC").unidade().fracionada()).isFalse();
        UnidadeMedida g = unidades.criar(admin, "G100", "Cem gramas", true);
        assertThat(buscar("G100").unidade()).isEqualTo(g);

        assertThatThrownBy(() -> unidades.criar(admin, "kg", "Outro quilo", true))
                .isInstanceOf(NegocioException.class).hasMessage("Já existe a unidade KG (Quilograma).");
        assertThat(unidades.criar(admin, "LITROS", "Seis letras", true).sigla()).isEqualTo("LITROS");
        for (String invalida : List.of("ABCDEFG", "K G", "MÇ", "M-2", "m²")) {
            assertThatThrownBy(() -> unidades.criar(admin, invalida, "Teste", false))
                    .as(invalida).hasMessage(UnidadeService.SIGLA_INVALIDA);
        }
        assertThatThrownBy(() -> unidades.criar(admin, " ", "Teste", false)).hasMessage("Informe a sigla da unidade.");
        assertThatThrownBy(() -> unidades.criar(admin, "XY", " ", false))
                .hasMessage("Informe a descrição da unidade.");
        assertThatThrownBy(() -> unidades.criar(admin, "XY", "x".repeat(41), false))
                .hasMessage("A descrição da unidade pode ter no máximo 40 caracteres.");
    }

    @Test
    void semUsoTudoMudaEPodeSerExcluida() {
        unidades.criar(admin, "PC", "Peça", false);
        UnidadeMedida alterada = unidades.alterar(admin, "PC", "pca", "Peça avulsa", true);
        assertThat(alterada).isEqualTo(new UnidadeMedida("PCA", "Peça avulsa", true));
        assertThat(unidades.listar(admin)).extracting(u -> u.unidade().sigla()).doesNotContain("PC").contains("PCA");

        assertThatThrownBy(() -> unidades.alterar(admin, "PCA", "CX", "Peça", true))
                .hasMessage("Já existe a unidade CX (Caixa).");

        unidades.excluir(admin, "PCA");
        assertThat(unidades.listar(admin)).extracting(u -> u.unidade().sigla()).doesNotContain("PCA");
        assertThatThrownBy(() -> unidades.excluir(admin, "PCA")).hasMessage("Unidade não encontrada.");
    }

    /** Aceite F10 (unidade em uso): não muda "fracionada" nem pode ser excluída; a descrição muda. */
    @Test
    void unidadeEmUsoNaoMudaSiglaNemFracionadaENaoEExcluida() {
        produto("Ovos brancos", "DZ");

        assertThatThrownBy(() -> unidades.alterar(admin, "DZ", "DZ", "Dúzia", true))
                .isInstanceOf(NegocioException.class)
                .hasMessage("A unidade DZ é usada por 1 produto: a sigla e \"fracionada\" não podem mudar. "
                        + "A descrição pode.");
        assertThatThrownBy(() -> unidades.alterar(admin, "DZ", "DUZ", "Dúzia", false))
                .hasMessageContaining("a sigla e \"fracionada\" não podem mudar");
        assertThatThrownBy(() -> unidades.excluir(admin, "DZ"))
                .hasMessage("A unidade DZ é usada por 1 produto e não pode ser excluída.");

        unidades.alterar(admin, "DZ", "dz", "Dúzia (12 unidades)", false);
        assertThat(buscar("DZ").unidade()).isEqualTo(new UnidadeMedida("DZ", "Dúzia (12 unidades)", false));
        assertThat(buscar("DZ").produtos()).isEqualTo(1);
    }

    @Test
    void unNaoMudaDeSiglaNemEExcluidaMesmoSemUso() {
        assertThatThrownBy(() -> unidades.alterar(admin, "UN", "UND", "Unidade", false))
                .hasMessage("A sigla UN não pode mudar: é a unidade padrão do cadastro de produtos.");
        assertThatThrownBy(() -> unidades.excluir(admin, "UN"))
                .hasMessage("A unidade UN não pode ser excluída: é a unidade padrão do cadastro de produtos.");
        unidades.alterar(admin, "UN", "UN", "Unidade (peça)", false);
        assertThat(buscar("UN").unidade().descricao()).isEqualTo("Unidade (peça)");
    }

    @Test
    void unidadeNovaValeNoCadastroDeProdutoComARegraDeFracionada() {
        unidades.criar(admin, "PC", "Peça", false);
        unidades.criar(admin, "MT2", "Metro quadrado", true);
        long piso = produto("Piso cerâmico", "MT2");
        long parafuso = produto("Parafuso 6 mm", "PC");
        t.estoque.ajustar(admin, piso, TipoAjuste.ENTRADA, new BigDecimal("12.5"), "Compra avulsa", null);
        assertThatThrownBy(() -> t.estoque.ajustar(admin, parafuso, TipoAjuste.ENTRADA, new BigDecimal("1.5"),
                "Compra avulsa", null)).isInstanceOf(NegocioException.class);
        assertThat(buscar("MT2").produtos()).isEqualTo(1);
    }

    @Test
    void semPermissaoProdutosOServiceRecusa() {
        t.usuarios.criar(admin, "caixa", "Caixa", false, EnumSet.of(Permissao.PDV), "inicial1", "inicial1");
        Sessao caixa = t.acesso.entrar("caixa", "inicial1");
        assertThatThrownBy(() -> unidades.listar(caixa)).isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> unidades.criar(caixa, "PC", "Peça", false))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> unidades.excluir(caixa, "CX")).isInstanceOf(PermissaoNegadaException.class);
    }
}
