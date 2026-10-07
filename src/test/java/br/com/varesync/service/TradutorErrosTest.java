package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

class TradutorErrosTest {

    /** Erro no formato do protocolo do PostgreSQL: campos "tipo+valor" separados por \0. */
    private static PSQLException erroPostgres(String sqlState, String mensagem, String constraint) {
        StringBuilder campos = new StringBuilder("SERROR\0C").append(sqlState).append("\0M").append(mensagem).append('\0');
        if (constraint != null) {
            campos.append('n').append(constraint).append('\0');
        }
        return new PSQLException(new ServerErrorMessage(campos.toString()));
    }

    @Test
    void negocioPassaSemAlteracao() {
        NegocioException original = new NegocioException("Regra violada");
        assertThat(TradutorErros.traduzir(original)).isSameAs(original);
    }

    @Test
    void unicoUsaMensagemDaConstraint() {
        PSQLException erro = erroPostgres("23505", "duplicate key value", "uq_pcb_codigo");

        RuntimeException traduzida = TradutorErros.traduzir(erro);

        assertThat(traduzida).isInstanceOf(NegocioException.class)
                .hasMessage("Este código de barras já pertence a outro produto")
                .hasCause(erro);
    }

    @Test
    void unicoSemMapeamentoUsaMensagemPadrao() {
        RuntimeException traduzida = TradutorErros.traduzir(erroPostgres("23505", "dup", "uq_desconhecida"));
        assertThat(traduzida).isInstanceOf(NegocioException.class).hasMessage(MensagensBanco.UNICO_PADRAO);
    }

    @Test
    void chaveEstrangeiraInformaRegistroEmUso() {
        RuntimeException traduzida = TradutorErros.traduzir(erroPostgres("23503", "fk", "fk_vi_produto"));
        assertThat(traduzida).isInstanceOf(NegocioException.class)
                .hasMessage("Este registro está em uso e não pode ser excluído");
    }

    @Test
    void checkUsaMensagemDaConstraint() {
        RuntimeException traduzida = TradutorErros.traduzir(erroPostgres("23514", "check", "ck_me_origem"));
        assertThat(traduzida).isInstanceOf(NegocioException.class).hasMessage("Informe o motivo do ajuste");
    }

    @Test
    void checkSemMapeamentoUsaMensagemPadrao() {
        RuntimeException traduzida = TradutorErros.traduzir(erroPostgres("23514", "check", "ck_nova"));
        assertThat(traduzida).isInstanceOf(NegocioException.class).hasMessage(MensagensBanco.CHECK_PADRAO);
    }

    @Test
    void triggerUsaAMensagemDoBanco() {
        String texto = "O estoque só pode ser alterado registrando um movimento de estoque.";
        RuntimeException traduzida = TradutorErros.traduzir(erroPostgres("P0001", texto, null));
        assertThat(traduzida).isInstanceOf(NegocioException.class).hasMessage(texto);
    }

    @Test
    void demaisErrosSaoInesperados() {
        PSQLException erro = erroPostgres("42P01", "relation does not exist", null);

        RuntimeException traduzida = TradutorErros.traduzir(erro);

        assertThat(traduzida).isInstanceOf(ErroInesperadoException.class)
                .hasMessage("Erro inesperado. Os detalhes foram registrados no log.")
                .hasCause(erro);
    }

    @Test
    void sqlExceptionGenericaEhInesperada() {
        assertThat(TradutorErros.traduzir(new SQLException("falha", "08006")))
                .isInstanceOf(ErroInesperadoException.class);
    }

    @Test
    void excecaoNaoSqlEhInesperadaEPreservaACausa() {
        IllegalStateException erro = new IllegalStateException("bug");
        assertThat(TradutorErros.traduzir(erro)).isInstanceOf(ErroInesperadoException.class).hasCause(erro);
    }

    @Test
    void encontraSqlExceptionNaCadeiaDeCausas() {
        PSQLException sql = erroPostgres("23505", "dup", "ux_caixa_aberto_por_terminal");
        RuntimeException embrulhada = new RuntimeException("camada", sql);

        RuntimeException traduzida = TradutorErros.traduzir(embrulhada);

        assertThat(traduzida).isInstanceOf(NegocioException.class)
                .hasMessage("Já existe um caixa aberto neste terminal")
                .hasCause(embrulhada);
    }

    @Test
    void mensagensBancoSemConstraint() {
        assertThat(MensagensBanco.mensagem(null)).isEmpty();
        assertThat(MensagensBanco.mensagem("uq_usuario_login")).isPresent();
    }
}
