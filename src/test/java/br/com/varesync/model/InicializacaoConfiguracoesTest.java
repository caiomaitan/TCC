package br.com.varesync.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.net.URL;
import java.net.URLClassLoader;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regressão da 2.0.1: a ordem em que a JVM inicializa {@link Configuracoes} e os records aninhados não pode deixar
 * nulo nenhum componente do {@link Configuracoes#PADRAO} nem nenhuma constante {@code PADRAO}/{@code VAZIO}. Antes da
 * correção, tocar primeiro em {@code Configuracoes$Emitente} disparava a inicialização da classe externa no meio da
 * do record (pelo {@code vazioSeNulo} privado), e o {@code PADRAO} ficava com o emitente nulo até o fim da JVM. Cada
 * caso usa um ClassLoader novo, isolado do dos testes, para que a classe indicada seja mesmo a primeira inicializada.
 */
class InicializacaoConfiguracoesTest {

    private static final String PACOTE = "br.com.varesync.model.";

    @ParameterizedTest
    @ValueSource(strings = {"Configuracoes", "Configuracoes$Emitente", "Configuracoes$RespTecnico",
            "Configuracoes$Nfce", "Configuracoes$Tributos", "Configuracoes$Fidelidade", "Configuracoes$Crediario",
            "Configuracoes$Compras", "Configuracoes$Etiquetas", "Configuracoes$Assistente", "Configuracoes$Loja",
            "Configuracoes$Balanca", "ParametrosVenda", "PoliticaSenha"})
    void padraoCompletoQualquerQueSejaAPrimeiraClasse(String primeira) throws Exception {
        URL classes = Configuracoes.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader isolado = new URLClassLoader(new URL[] {classes}, ClassLoader.getPlatformClassLoader())) {
            Class.forName(PACOTE + primeira, true, isolado);
            Class<?> configuracoes = Class.forName(PACOTE + "Configuracoes", true, isolado);
            assertThat(configuracoes.getClassLoader()).as("carregada no ClassLoader isolado").isSameAs(isolado);

            Object padrao = configuracoes.getField("PADRAO").get(null);
            assertThat(padrao).isNotNull();
            conferirComponentes("Configuracoes.PADRAO", padrao);
            for (Class<?> aninhada : configuracoes.getDeclaredClasses()) {
                conferirConstantes(aninhada);
            }
        }
    }

    /** Todos os componentes do record (e dos records dentro dele) não nulos. */
    private static void conferirComponentes(String caminho, Object record) throws Exception {
        for (RecordComponent c : record.getClass().getRecordComponents()) {
            Object valor = c.getAccessor().invoke(record);
            assertThat(valor).as(caminho + "." + c.getName()).isNotNull();
            if (valor.getClass().isRecord()) {
                conferirComponentes(caminho + "." + c.getName(), valor);
            }
        }
    }

    private static void conferirConstantes(Class<?> classe) throws Exception {
        for (Field f : classe.getDeclaredFields()) {
            int m = f.getModifiers();
            if (Modifier.isStatic(m) && Modifier.isPublic(m) && f.getType() == classe) {
                Object valor = f.get(null);
                String nome = classe.getSimpleName() + "." + f.getName();
                assertThat(valor).as(nome).isNotNull();
                conferirComponentes(nome, valor);
            }
        }
    }
}
