package br.com.varesync.service.nfce;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Os XSD do pacote PL 010g (F23.3): a lista bate com a pasta, e a cópia para a pasta do usuário funciona. */
class ValidacaoXsdTest {

    @TempDir
    Path pasta;

    @Test
    void listaBateComAPastaDosRecursos() throws IOException {
        try (Stream<Path> arquivos = Files.list(Path.of("src/main/resources/nfe/schemas"))) {
            assertThat(arquivos.map(p -> p.getFileName().toString()).sorted().toList())
                    .isEqualTo(ValidacaoXsd.arquivos().stream().sorted().toList());
        }
        assertThat(ValidacaoXsd.arquivos()).contains("enviNFe_v4.00.xsd", "leiauteNFe_v4.00.xsd",
                "DFeTiposBasicos_v1.00.xsd", "xmldsig-core-schema_v1.01.xsd");
    }

    @Test
    void copiaParaAPastaDoUsuarioComOGrupoIbsCbs() throws IOException {
        Path destino = new ValidacaoXsd(pasta).pasta();
        assertThat(destino).isEqualTo(pasta.resolve(ValidacaoXsd.VERSAO_PACOTE));
        assertThat(Files.readString(destino.resolve("leiauteNFe_v4.00.xsd"))).contains("name=\"IBSCBS\"");
        try (Stream<Path> copiados = Files.list(destino)) {
            assertThat(copiados.count()).isEqualTo(ValidacaoXsd.arquivos().size());
        }
    }
}
