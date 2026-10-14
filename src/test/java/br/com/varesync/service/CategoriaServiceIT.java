package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.Categoria;
import br.com.varesync.model.DadosProduto;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Categorias (6.3): nome único sem diferenciar maiúsculas; em uso → inativar em vez de excluir. */
class CategoriaServiceIT extends IntegracaoBase {

    private AcessoTeste t;
    private CategoriaService categorias;
    private Sessao admin;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        categorias = t.categorias;
        admin = t.supervisor();
    }

    private Categoria buscar(long id) {
        return categorias.listar(admin, true).stream().filter(c -> c.id() == id).findFirst().orElseThrow();
    }

    @Test
    void listaAsCategoriasDaV2EmOrdemAlfabetica() {
        assertThat(categorias.listar(admin, false)).extracting(Categoria::nome).containsExactly(
                "Açougue", "Bebidas", "Frios e laticínios", "Higiene e perfumaria", "Hortifruti", "Limpeza",
                "Mercearia", "Outros", "Padaria");
    }

    @Test
    void criaERenomeiaComNomeUnicoSemDiferenciarMaiusculas() {
        long id = categorias.criar(admin, "  Pet   shop ");
        assertThat(buscar(id).nome()).isEqualTo("Pet shop");
        assertThat(buscar(id).ativa()).isTrue();

        assertThatThrownBy(() -> categorias.criar(admin, "MERCEARIA"))
                .isInstanceOf(NegocioException.class)
                .hasMessage("Já existe uma categoria com o nome \"MERCEARIA\".");
        assertThatThrownBy(() -> categorias.renomear(admin, id, "bebidas"))
                .hasMessage("Já existe uma categoria com o nome \"bebidas\".");
        assertThatThrownBy(() -> categorias.criar(admin, "  ")).hasMessage("Informe o nome da categoria.");

        categorias.renomear(admin, id, "PET SHOP"); // a própria categoria pode mudar só as maiúsculas
        assertThat(buscar(id).nome()).isEqualTo("PET SHOP");
    }

    @Test
    void categoriaEmUsoNaoExcluiEPodeSerInativada() {
        long id = categorias.criar(admin, "Sazonais");
        t.produtos.criar(admin, new DadosProduto("Panetone 500 g", id, "UN", new BigDecimal("29.90"),
                new BigDecimal("18.00"), null, null, List.of(), null));

        assertThatThrownBy(() -> categorias.excluir(admin, id))
                .isInstanceOf(RegistroEmUsoException.class)
                .hasMessageStartingWith("A categoria \"Sazonais\" é usada por 1 produto e não pode ser excluída.");

        categorias.alterarSituacao(admin, id, false);
        assertThat(buscar(id).ativa()).isFalse();
        assertThat(categorias.listar(admin, false)).extracting(Categoria::id).doesNotContain(id);
    }

    @Test
    void categoriaSemUsoEExcluida() {
        long id = categorias.criar(admin, "Temporária");
        categorias.excluir(admin, id);
        assertThat(categorias.listar(admin, true)).extracting(Categoria::id).doesNotContain(id);
    }
}
