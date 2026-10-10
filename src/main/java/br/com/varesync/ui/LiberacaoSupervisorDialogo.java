package br.com.varesync.ui;

import br.com.varesync.service.Liberacao;
import java.util.Optional;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

/**
 * Liberação de supervisor (6.2): quando a ação exige uma permissão que o usuário não tem,
 * pede login e senha de quem tem. A liberação vale só para aquela ação.
 *
 * <pre>{@code
 * Optional<Liberacao> l = LiberacaoSupervisorDialogo.solicitar("Cancelar a venda nº 123",
 *         (login, senha) -> acessoService.liberar(login, senha, Permissao.CANCELAR_VENDA));
 * }</pre>
 */
public final class LiberacaoSupervisorDialogo {

    /** Confere as credenciais; lança {@code NegocioException} com o motivo da recusa. */
    @FunctionalInterface
    public interface Validador {
        Liberacao validar(String login, String senha);
    }

    private LiberacaoSupervisorDialogo() {
    }

    /** @return a liberação concedida, ou vazio se o usuário cancelou */
    public static Optional<Liberacao> solicitar(String acao, Validador validador) {
        DialogoFormulario formulario = new DialogoFormulario("Liberação de supervisor",
                acao + " precisa da autorização de quem tem permissão. Informe o usuário e a senha dessa pessoa.",
                "Liberar");
        TextField login = new TextField();
        PasswordField senha = new PasswordField();
        formulario.adicionarCampo("Usuário", login);
        formulario.adicionarCampo("Senha", senha);
        return formulario.mostrar(() -> validador.validar(login.getText(), senha.getText()), () -> {
            senha.clear();
            senha.requestFocus();
        });
    }
}
