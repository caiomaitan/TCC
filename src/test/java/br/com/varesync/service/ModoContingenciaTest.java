package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** O aviso da contingência (F23.9): quem observa o modo é avisado na entrada e na saída, e para de ser avisado. */
class ModoContingenciaTest {

    private static final OffsetDateTime INICIO = OffsetDateTime.of(2026, 11, 16, 9, 40, 0, 0, ZoneOffset.ofHours(-3));

    @Test
    void avisaNaEntradaENaSaidaUmaVezSo() {
        ModoContingencia modo = new ModoContingencia();
        List<Optional<OffsetDateTime>> avisos = new ArrayList<>();
        Runnable parar = modo.observar(avisos::add);

        assertThat(modo.entrar(INICIO)).isTrue();
        assertThat(modo.entrar(INICIO.plusMinutes(5))).isFalse(); // já estava: não avisa de novo
        assertThat(modo.sair()).isTrue();
        assertThat(modo.sair()).isFalse();
        assertThat(avisos).containsExactly(Optional.of(INICIO), Optional.empty());

        parar.run();
        modo.entrar(INICIO);
        assertThat(avisos).hasSize(2);
    }

    @Test
    void falhaDeUmObservadorNaoImpedeOsOutrosNemOModo() {
        ModoContingencia modo = new ModoContingencia();
        List<Optional<OffsetDateTime>> avisos = new ArrayList<>();
        modo.observar(e -> {
            throw new IllegalStateException("tela fechada");
        });
        modo.observar(avisos::add);

        assertThat(modo.entrar(INICIO)).isTrue();
        assertThat(modo.ativo()).isTrue();
        assertThat(avisos).containsExactly(Optional.of(INICIO));
    }
}
