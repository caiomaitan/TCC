package br.com.varesync.model;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.IntegracaoBase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** O enum {@link Permissao} tem exatamente os códigos da tabela {@code permissao} (V2). */
class PermissaoIT extends IntegracaoBase {

    @Test
    void enumIgualATabelaDePermissoes() throws Exception {
        Set<String> doBanco = new HashSet<>();
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT codigo FROM permissao");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                doBanco.add(rs.getString(1));
            }
        }
        Set<String> doJava = Arrays.stream(Permissao.values()).map(Enum::name).collect(Collectors.toSet());
        assertThat(doJava).isEqualTo(doBanco);
    }
}
