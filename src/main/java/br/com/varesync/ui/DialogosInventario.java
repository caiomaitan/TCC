package br.com.varesync.ui;

import br.com.varesync.model.Categoria;
import br.com.varesync.model.InclusaoInventario;
import br.com.varesync.service.NegocioException;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;

/**
 * Diálogos do inventário (6.7): novo inventário e inclusão por categoria. A gravação é feita pela ação recebida;
 * {@code NegocioException} mantém o diálogo aberto com a mensagem. Enter confirma, Esc cancela.
 */
public final class DialogosInventario {

    private DialogosInventario() {
    }

    /** @return número do inventário criado, ou vazio se cancelado */
    public static Optional<Long> novo(Function<String, Long> criar) {
        DialogoFormulario formulario = new DialogoFormulario("Novo inventário",
                "Dê um nome que ajude a encontrar o inventário depois (ex.: \"Mercearia — outubro\"). "
                        + "Os produtos são incluídos em seguida.", "Criar");
        TextField descricao = new TextField();
        descricao.setPromptText("Ex.: Bebidas — outubro");
        formulario.adicionarCampo("Descrição *", descricao);
        return formulario.mostrar(() -> criar.apply(descricao.getText()), null);
    }

    /** @return o resultado da inclusão dos produtos ativos da categoria escolhida, ou vazio se cancelado */
    public static Optional<InclusaoInventario> incluirCategoria(List<Categoria> categorias,
                                                                Function<Categoria, InclusaoInventario> incluir) {
        DialogoFormulario formulario = new DialogoFormulario("Incluir produtos por categoria",
                "Entram os produtos ativos da categoria que ainda não estão neste nem em outro inventário aberto.",
                "Incluir");
        ComboBox<Categoria> categoria = new ComboBox<>();
        categoria.getItems().setAll(categorias);
        categoria.setMaxWidth(Double.MAX_VALUE);
        categoria.setPromptText("Escolha a categoria");
        formulario.adicionarCampo("Categoria *", categoria);
        return formulario.mostrar(() -> {
            if (categoria.getValue() == null) {
                throw new NegocioException("Escolha a categoria.");
            }
            return incluir.apply(categoria.getValue());
        }, null);
    }
}
