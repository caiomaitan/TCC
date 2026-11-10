package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.dao.PreferenciaDAO;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.Preferencias;
import br.com.varesync.model.TamanhoFonte;
import br.com.varesync.model.Tema;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Preferências por usuário (F21.1): padrão, gravação por usuário, sessão conferida e CHECKs iguais aos enums. */
class PreferenciaServiceIT extends IntegracaoBase {

    private AcessoTeste t;
    private PreferenciaService preferencias;
    private Sessao admin;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        admin = t.supervisor();
        preferencias = new PreferenciaService(new Transacao(dataSource), new PreferenciaDAO(), t.relogio);
    }

    private Sessao operador(String login) {
        t.usuarios.criar(admin, login, "Operador " + login, false, EnumSet.of(Permissao.PDV), "inicial1", "inicial1");
        Sessao provisoria = t.acesso.entrar(login, "inicial1");
        return t.acesso.trocarSenha(provisoria, "inicial1", "definitiva1", "definitiva1");
    }

    @Test
    void semPreferenciaGravadaValeOPadraoClaroE100() {
        assertThat(preferencias.buscar(admin)).isEqualTo(Preferencias.PADRAO)
                .isEqualTo(new Preferencias(Tema.CLARO, TamanhoFonte.NORMAL));
    }

    @Test
    void temaEscuroE130FicamSalvosPorUsuarioEValemNoProximoLogin() throws Exception {
        Sessao maria = operador("maria");
        Preferencias escuro130 = new Preferencias(Tema.ESCURO, TamanhoFonte.MAIOR);

        preferencias.salvar(maria, escuro130);

        Sessao deNovo = t.acesso.entrar("maria", "definitiva1"); // outro login, outro computador
        assertThat(preferencias.buscar(deNovo)).isEqualTo(escuro130);
        assertThat(preferencias.buscar(admin)).as("as do supervisor não mudam").isEqualTo(Preferencias.PADRAO);
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT tema, fonte, atualizado_em FROM usuario_preferencia WHERE usuario_id = ?")) {
            ps.setLong(1, maria.usuarioId());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("tema")).isEqualTo("ESCURO");
                assertThat(rs.getInt("fonte")).isEqualTo(130);
                assertThat(rs.getObject("atualizado_em", OffsetDateTime.class).toInstant())
                        .as("horário do Clock (V3)").isEqualTo(t.relogio.instant());
            }
        }
    }

    @Test
    void gravarDeNovoSubstituiEAtualizaOHorario() throws Exception {
        preferencias.salvar(admin, new Preferencias(Tema.ESCURO, TamanhoFonte.GRANDE));
        t.relogio.avancar(Duration.ofMinutes(5));
        preferencias.salvar(admin, new Preferencias(Tema.CLARO, TamanhoFonte.MAIOR));

        assertThat(preferencias.buscar(admin)).isEqualTo(new Preferencias(Tema.CLARO, TamanhoFonte.MAIOR));
        assertThat(t.contar("SELECT count(*) FROM usuario_preferencia")).isEqualTo(1);
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT atualizado_em FROM usuario_preferencia")) {
            rs.next();
            assertThat(rs.getObject(1, OffsetDateTime.class).toInstant()).isEqualTo(t.relogio.instant());
        }
    }

    @Test
    void usuarioInativadoNaoGravaEVaiParaOLogin() {
        Sessao maria = operador("maria");
        t.usuarios.alterarSituacao(admin, maria.usuarioId(), false);

        assertThatThrownBy(() -> preferencias.salvar(maria, new Preferencias(Tema.ESCURO, TamanhoFonte.MAIOR)))
                .isInstanceOf(SessaoEncerradaException.class);
        assertThat(preferencias.buscar(maria)).isEqualTo(Preferencias.PADRAO);
    }

    @Test
    void enumsIguaisAosChecksDoBanco() throws Exception {
        assertThat(valoresDoCheck("ck_up_tema"))
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(Tema.values()).map(Enum::name).toList());
        assertThat(valoresDoCheck("ck_up_fonte")).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(TamanhoFonte.values()).map(f -> String.valueOf(f.percentual())).toList());
    }

    @Test
    void bancoRecusaTemaOuFonteForaDaLista() throws Exception {
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO usuario_preferencia (usuario_id, tema, fonte) "
                    + "VALUES (" + admin.usuarioId() + ", 'AZUL', 100)"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_up_tema");
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO usuario_preferencia (usuario_id, tema, fonte) "
                    + "VALUES (" + admin.usuarioId() + ", 'CLARO', 120)"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_up_fonte");
        }
    }

    /** Valores entre aspas (ou números) do CHECK, como no {@code VendaServiceIT}. */
    private List<String> valoresDoCheck(String constraint) throws Exception {
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?")) {
            ps.setString(1, constraint);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as(constraint).isTrue();
                String definicao = rs.getString(1);
                String lista = definicao.substring(definicao.indexOf("ARRAY[") + 6, definicao.indexOf(']'));
                return Arrays.stream(lista.split(","))
                        .map(v -> v.trim().replaceAll("::\\w+( \\w+)?(\\(\\d+\\))?", "").replace("'", "").trim())
                        .toList();
            }
        }
    }
}
