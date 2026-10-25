package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.dao.ClienteDAO;
import br.com.varesync.model.DadosCliente;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Cadastro de cliente (F12.1): normalização sem máscara e validações, sem banco. */
class ValidacaoClienteTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 10, 24);

    private static DadosCliente dados(String nome, String cpf, String telefone, LocalDate nascimento, String cep) {
        return new DadosCliente(nome, cpf, telefone, null, nascimento, cep, null, null, null, null, null, null, null,
                false);
    }

    @Test
    void gravaSemMascaraEComEspacosNormalizados() {
        ClienteDAO.Gravacao g = ValidacaoCliente.validar(new DadosCliente("  Maria   da Silva ", "529.982.247-25",
                "(16) 99999-8888", "Maria@Exemplo.com.br", LocalDate.of(1985, 3, 25), "14010-000",
                " Rua das  Flores ", "120", "", "Centro", "Ribeirão Preto", "sp", "  ", true), HOJE);
        assertThat(g.nome()).isEqualTo("Maria da Silva");
        assertThat(g.cpfCnpj()).isEqualTo("52998224725");
        assertThat(g.telefone()).isEqualTo("16999998888");
        assertThat(g.email()).isEqualTo("maria@exemplo.com.br");
        assertThat(g.cep()).isEqualTo("14010000");
        assertThat(g.logradouro()).isEqualTo("Rua das Flores");
        assertThat(g.complemento()).isNull();
        assertThat(g.uf()).isEqualTo("SP");
        assertThat(g.observacao()).isNull();
    }

    @Test
    void recusaOQueEstaErrado() {
        assertThatThrownBy(() -> ValidacaoCliente.validar(dados(" ", null, null, null, null), HOJE))
                .hasMessage("Informe o nome do cliente.");
        assertThatThrownBy(() -> ValidacaoCliente.validar(dados("Maria", "529.982.247-24", null, null, null), HOJE))
                .hasMessage("CPF 529.982.247-24 inválido: o dígito verificador não confere.");
        assertThatThrownBy(() -> ValidacaoCliente.validar(dados("Maria", null, "9999-8888", null, null), HOJE))
                .hasMessageStartingWith("Telefone inválido");
        assertThatThrownBy(() -> ValidacaoCliente.validar(dados("Maria", null, null, HOJE.plusDays(1), null), HOJE))
                .hasMessage("A data de nascimento não pode ser depois de hoje.");
        assertThatThrownBy(() -> ValidacaoCliente.validar(dados("Maria", null, null, LocalDate.of(1899, 12, 31),
                null), HOJE)).hasMessage("Data de nascimento inválida: confira o ano.");
        assertThatThrownBy(() -> ValidacaoCliente.validar(dados("Maria", null, null, null, "1401-000"), HOJE))
                .hasMessageStartingWith("CEP inválido");
        assertThatThrownBy(() -> ValidacaoCliente.validar(dados("x".repeat(121), null, null, null, null), HOJE))
                .hasMessage("O nome pode ter no máximo 120 caracteres.");
    }

    @Test
    void cnpjAlfanumericoTambemValeParaCliente() {
        assertThat(ValidacaoCliente.validar(dados("Padaria", "12.abc.345/01de-35", null, null, null), HOJE).cpfCnpj())
                .isEqualTo("12ABC34501DE35");
    }

    @Test
    void buscaSeparaNomeDocumentoETelefone() {
        assertThat(ClienteService.busca("  ", true)).isEqualTo(new ClienteDAO.Busca(null, null, null, true));
        assertThat(ClienteService.busca("José", false)).isEqualTo(new ClienteDAO.Busca("jose", null, null, false));
        assertThat(ClienteService.busca("529.982", false))
                .isEqualTo(new ClienteDAO.Busca("529.982", "529982", "529982", false));
        assertThat(ClienteService.busca("(16) 99999", false))
                .isEqualTo(new ClienteDAO.Busca("(16) 99999", null, "1699999", false));
        assertThat(ClienteService.busca("12.abc", false)).isEqualTo(new ClienteDAO.Busca("12.abc", "12ABC", null,
                false));
    }

    @Test
    void documentoCompletoSoComDvValido() {
        assertThat(ClienteService.documentoCompleto("529.982.247-25")).contains("52998224725");
        assertThat(ClienteService.documentoCompleto("529.982.247-24")).isEmpty();
        assertThat(ClienteService.documentoCompleto("529982")).isEmpty();
        assertThat(ClienteService.documentoCompleto("Maria")).isEmpty();
    }
}
