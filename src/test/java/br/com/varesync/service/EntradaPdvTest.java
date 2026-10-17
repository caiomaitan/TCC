package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Leitura da entrada do PDV (6.6, 9.1): código, quantidade*código e texto. */
class EntradaPdvTest {

    @Test
    void codigoDeBarrasSozinho() {
        EntradaPdv e = EntradaPdv.interpretar(" 7891000315507 ");
        assertThat(e.quantidade()).isNull();
        assertThat(e.codigo()).isEqualTo("7891000315507");
        assertThat(e.busca()).isFalse();
    }

    @Test
    void quantidadeVezesCodigo() {
        EntradaPdv tres = EntradaPdv.interpretar("3*7891000315507");
        assertThat(tres.quantidade()).isEqualByComparingTo("3");
        assertThat(tres.codigo()).isEqualTo("7891000315507");

        EntradaPdv fracionada = EntradaPdv.interpretar("0,345*123");
        assertThat(fracionada.quantidade()).isEqualByComparingTo("0.345");
        assertThat(fracionada.codigo()).isEqualTo("123");

        assertThat(EntradaPdv.interpretar("2 * 123").quantidade()).isEqualByComparingTo("2");
    }

    @Test
    void textoAbreABusca() {
        EntradaPdv e = EntradaPdv.interpretar("arroz  tipo 1");
        assertThat(e.busca()).isTrue();
        assertThat(e.texto()).isEqualTo("arroz tipo 1");
        assertThat(e.codigo()).isNull();

        EntradaPdv comQuantidade = EntradaPdv.interpretar("2*café");
        assertThat(comQuantidade.quantidade()).isEqualByComparingTo("2");
        assertThat(comQuantidade.texto()).isEqualTo("café");
    }

    @Test
    void erros() {
        assertThatThrownBy(() -> EntradaPdv.interpretar("  ")).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> EntradaPdv.interpretar("*123")).hasMessageContaining("antes do *");
        assertThatThrownBy(() -> EntradaPdv.interpretar("3*")).hasMessageContaining("depois do *");
        assertThatThrownBy(() -> EntradaPdv.interpretar("x*123")).hasMessage("Quantidade inválida (\"x\").");
        assertThatThrownBy(() -> EntradaPdv.interpretar("0*123")).hasMessage("A quantidade deve ser maior que zero.");
        assertThatThrownBy(() -> EntradaPdv.interpretar("-1*123")).hasMessage("A quantidade deve ser maior que zero.");
    }
}
