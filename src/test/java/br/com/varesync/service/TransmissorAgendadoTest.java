package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.RelogioTeste;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.Usuario;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** O transmissor em segundo plano (F23.5): só com a NFC-e ligada, alguém logado e o intervalo cumprido. */
class TransmissorAgendadoTest {

    private static Configuracoes.Nfce nfce(boolean ativa, int minutos) {
        Configuracoes.Nfce p = Configuracoes.Nfce.PADRAO;
        return new Configuracoes.Nfce(ativa, p.serie(), p.prazoCancelamentoMinutos(), p.timeoutSegundos(), minutos,
                p.valorIdentificacao(), p.valorMaximo(), p.viaEstabelecimento(), p.alertaCertificadoDias(),
                p.urlQrCode(), p.urlConsulta(), p.respTecnico());
    }

    @Test
    void rodaComANfceLigadaAlguemLogadoEOIntervaloCumprido() {
        RelogioTeste relogio = new RelogioTeste(Instant.parse("2026-11-16T13:00:00Z"));
        AtomicReference<Configuracoes.Nfce> config = new AtomicReference<>(nfce(false, 5));
        List<Sessao> rodadas = new ArrayList<>();
        Sessao sessao = new Sessao(new Usuario(1, "admin", "Admin", true, true, false, 0, null, null,
                EnumSet.noneOf(Permissao.class)), "CAIXA-01");
        AtomicReference<Sessao> logado = new AtomicReference<>(sessao);
        try (TransmissorAgendado agendado = new TransmissorAgendado(config::get, rodadas::add, relogio)) {
            agendado.iniciar(logado::get);

            assertThat(agendado.executarSeDevido()).as("NFC-e desligada").isFalse();
            config.set(nfce(true, 5));
            assertThat(agendado.executarSeDevido()).as("primeira rodada").isTrue();
            relogio.avancar(Duration.ofMinutes(4));
            assertThat(agendado.executarSeDevido()).as("antes dos 5 minutos").isFalse();
            relogio.avancar(Duration.ofMinutes(1));
            assertThat(agendado.executarSeDevido()).isTrue();
            relogio.avancar(Duration.ofMinutes(10));
            logado.set(null);
            assertThat(agendado.executarSeDevido()).as("ninguém logado").isFalse();
            logado.set(sessao);
            agendado.parar();
            assertThat(agendado.executarSeDevido()).as("desligado no log off").isFalse();
        }
        assertThat(rodadas).containsExactly(sessao, sessao);
    }
}
