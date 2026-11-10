package br.com.varesync.ui;

import br.com.varesync.model.Preferencias;
import br.com.varesync.model.TamanhoFonte;
import br.com.varesync.model.Tema;
import java.util.Optional;
import java.util.function.UnaryOperator;
import javafx.scene.control.ComboBox;

/** "Minhas preferências" (F21.1): tema e tamanho da fonte do próprio usuário. Enter grava, Esc cancela. */
public final class DialogoPreferencias {

    private DialogoPreferencias() {
    }

    /**
     * @param atuais as preferências em uso
     * @param salvar grava e devolve as preferências gravadas; {@code NegocioException} mantém o diálogo aberto
     * @return as preferências gravadas, ou vazio se a pessoa cancelou
     */
    public static Optional<Preferencias> mostrar(Preferencias atuais, UnaryOperator<Preferencias> salvar) {
        DialogoFormulario formulario = new DialogoFormulario("Minhas preferências",
                "Valem só para você, neste e nos outros computadores, a partir de agora. A tela de entrada continua "
                        + "no padrão.", "Salvar");
        ComboBox<Tema> tema = new ComboBox<>();
        tema.getItems().setAll(Tema.values());
        tema.setValue(atuais.tema());
        tema.setMaxWidth(Double.MAX_VALUE);
        ComboBox<TamanhoFonte> fonte = new ComboBox<>();
        fonte.getItems().setAll(TamanhoFonte.values());
        fonte.setValue(atuais.fonte());
        fonte.setMaxWidth(Double.MAX_VALUE);
        formulario.adicionarCampo("Tema", tema);
        formulario.adicionarCampo("Tamanho da fonte", fonte);
        return formulario.mostrar(() -> salvar.apply(new Preferencias(tema.getValue(), fonte.getValue())), null);
    }
}
