package br.com.varesync.ui;

import javafx.scene.control.PasswordField;

/** Diálogos de senha: alterar a própria (pede a atual) e definir senha provisória (supervisor). */
public final class DialogoSenha {

    /** Grava a senha; lança {@code NegocioException} se ela for recusada. */
    @FunctionalInterface
    public interface AlterarPropria {
        void executar(String atual, String nova, String confirmacao);
    }

    /** Grava a senha provisória; lança {@code NegocioException} se ela for recusada. */
    @FunctionalInterface
    public interface DefinirProvisoria {
        void executar(String nova, String confirmacao);
    }

    private DialogoSenha() {
    }

    /**
     * @param regra a política de senha em vigor (F10.6), mostrada no alto do diálogo
     * @return true se a senha foi alterada
     */
    public static boolean alterarPropria(String regra, AlterarPropria acao) {
        DialogoFormulario formulario = new DialogoFormulario("Alterar minha senha", regra, "Alterar");
        PasswordField atual = new PasswordField();
        PasswordField nova = new PasswordField();
        PasswordField confirmacao = new PasswordField();
        formulario.adicionarCampo("Senha atual *", atual);
        formulario.adicionarCampo("Nova senha *", nova);
        formulario.adicionarCampo("Confirmação *", confirmacao);
        return formulario.mostrar(() -> {
            acao.executar(atual.getText(), nova.getText(), confirmacao.getText());
            return Boolean.TRUE;
        }, null).isPresent();
    }

    /**
     * @param regra a política de senha em vigor (F10.6), mostrada no alto do diálogo
     * @return true se a senha provisória foi gravada
     */
    public static boolean definirProvisoria(String usuario, String regra, DefinirProvisoria acao) {
        DialogoFormulario formulario = new DialogoFormulario("Redefinir senha de " + usuario,
                "Informe uma senha provisória e passe-a ao usuário. Ele terá de trocá-la ao entrar. " + regra,
                "Redefinir");
        PasswordField nova = new PasswordField();
        PasswordField confirmacao = new PasswordField();
        formulario.adicionarCampo("Senha provisória *", nova);
        formulario.adicionarCampo("Confirmação *", confirmacao);
        return formulario.mostrar(() -> {
            acao.executar(nova.getText(), confirmacao.getText());
            return Boolean.TRUE;
        }, null).isPresent();
    }
}
