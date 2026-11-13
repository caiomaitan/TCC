package br.com.varesync.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class VersaoSistemaTest {

    private static Properties props(String... chaveValor) {
        Properties p = new Properties();
        for (int i = 0; i < chaveValor.length; i += 2) {
            p.setProperty(chaveValor[i], chaveValor[i + 1]);
        }
        return p;
    }

    @Test
    void versaoCommitEDataDoBuild() {
        VersaoSistema v = VersaoSistema.de(
                props("versao", "2.0.0-SNAPSHOT", "build.data", "2026-11-12T13:45:00Z"),
                props("git.commit.id.abbrev", "f9597a9", "git.dirty", "false"));

        assertThat(v.versao()).isEqualTo("2.0.0-SNAPSHOT");
        assertThat(v.versaoNumerica()).isEqualTo("2.0.0");
        assertThat(v.commitTexto()).isEqualTo("f9597a9");
        assertThat(v.dataBuild()).contains(Instant.parse("2026-11-12T13:45:00Z"));
        assertThat(v.textoBarraDeStatus()).isEqualTo("v2.0.0-SNAPSHOT");
        assertThat(v.dicaBarraDeStatus()).isEqualTo("Versão 2.0.0-SNAPSHOT — abre a tela Sobre");
    }

    @Test
    void dataDoBuildNoFusoDoComputador() {
        VersaoSistema v = VersaoSistema.de(props("versao", "2.0.0", "build.data", "2026-11-12T13:45:00Z"),
                new Properties());
        // No fuso do computador (no CI, o da loja: America/Sao_Paulo).
        assertThat(v.dataBuildTexto()).isEqualTo(br.com.varesync.util.Formatos.dataHora(
                java.time.OffsetDateTime.ofInstant(Instant.parse("2026-11-12T13:45:00Z"),
                        java.time.ZoneId.systemDefault())));
        assertThat(v.dataBuildTexto()).matches("\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}");
    }

    @Test
    void commitComAlteracoesNaoCommitadas() {
        VersaoSistema v = VersaoSistema.de(props("versao", "2.0.0"),
                props("git.commit.id.abbrev", "f9597a9", "git.dirty", "true"));
        assertThat(v.commitTexto()).isEqualTo("f9597a9 (com alterações não commitadas)");
    }

    @Test
    void semGitNemRecursosFiltradosApareceDesconhecido() {
        VersaoSistema semNada = VersaoSistema.de(new Properties(), new Properties());
        assertThat(semNada.versao()).isEqualTo(VersaoSistema.DESCONHECIDO);
        assertThat(semNada.commitTexto()).isEqualTo("desconhecido");
        assertThat(semNada.dataBuildTexto()).isEqualTo("desconhecida");

        // Recurso copiado sem o filtro do Maven (rodando pela IDE): o ${...} não vale como versão.
        VersaoSistema semFiltro = VersaoSistema.de(
                props("versao", "${project.version}", "build.data", "${build.data}"),
                props("git.commit.id.abbrev", " "));
        assertThat(semFiltro.versao()).isEqualTo(VersaoSistema.DESCONHECIDO);
        assertThat(semFiltro.commit()).isEqualTo(Optional.empty());
        assertThat(semFiltro.dataBuild()).isEmpty();
    }

    @Test
    void dataEmFormatoEstranhoViraDesconhecida() {
        VersaoSistema v = VersaoSistema.de(props("versao", "2.0.0", "build.data", "12/11/2026"), new Properties());
        assertThat(v.dataBuildTexto()).isEqualTo("desconhecida");
    }

    @Test
    void esteBuildTemAVersaoDoPomECommit() {
        // Os recursos de target/classes vêm do Maven: versao.properties filtrado e git.properties do git-commit-id.
        VersaoSistema v = VersaoSistema.doBuild();
        assertThat(v.versao()).startsWith("2.0.0");
        assertThat(v.versaoNumerica()).isEqualTo("2.0.0");
        assertThat(v.dataBuild()).isPresent();
        if (getClass().getResource("/git.properties") != null) {
            assertThat(v.commit()).hasValueSatisfying(c -> assertThat(c).matches("[0-9a-f]{7,}"));
        }
    }
}
