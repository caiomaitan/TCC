package br.com.varesync.model;

/**
 * Regime tributário da empresa (F19.1), na configuração {@code empresa.regime_tributario}. No SIMPLES a situação do
 * ICMS é o CSOSN; no NORMAL, o CST.
 */
public enum RegimeTributario {
    SIMPLES("Simples Nacional"),
    NORMAL("Regime normal");

    private final String rotulo;

    RegimeTributario(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }

    @Override
    public String toString() {
        return rotulo;
    }
}
