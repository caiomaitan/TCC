package br.com.varesync.tela;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.config.AppConfig;
import br.com.varesync.controller.ItemMenu;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.RegistroAuditoria;
import br.com.varesync.model.StatusNfce;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.service.DadosTela;
import br.com.varesync.service.EmissaoNfceService;
import br.com.varesync.service.TransmissorNfce;
import br.com.varesync.service.nfce.CertificadoTeste;
import br.com.varesync.service.nfce.SefazFalsa;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListView;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

/**
 * NFC-e em homologação, parte B (F23.4 e F23.5), pela tela: duas vendas no PDV; a SEFAZ (falsa) não responde à
 * primeira NFC-e, que fica sem resposta, e a venda recebe outra em contingência; a segunda venda já sai em contingência;
 * a SEFAZ volta, e o transmissor autoriza as de contingência e inutiliza o número sem resposta. A auditoria (entrada e
 * saída da contingência e a inutilização, "automático") e o Diagnóstico são conferidos em Configurações; o log, sem o
 * XML nem o certificado.
 *
 * <p>A emissão é da parte C (o PDV ainda não emite): ela roda pelo {@code DadosTela.emissaoNfce}, montada como no
 * sistema, com o certificado do {@code config.properties} temporário, mas com a SEFAZ falsa (nada vai à rede).
 */
class NfceParteBTelaIT extends TelaBase {

    private static final String CERVEJA = "7891000315507";

    @Test
    void contingenciaETransmissor() throws Exception {
        dados.criarSupervisor();
        dados.configurar("empresa.documento", CertificadoTeste.CNPJ);
        dados.configurar("empresa.uf", "SP");
        dados.configurar("empresa.ie", "123456789012");
        dados.configurar("empresa.razao_social", "Empresa Teste Ltda");
        dados.configurar("empresa.logradouro", "Rua das Flores");
        dados.configurar("empresa.numero", "100");
        dados.configurar("empresa.bairro", "Centro");
        dados.configurar("empresa.cep", "14010100");
        dados.configurar("empresa.municipio", "Ribeirão Preto");
        dados.configurar("empresa.codigo_municipio", "3543402");
        dados.perfilSimples("Simples tributado", "5102", "102");
        long cerveja = dados.produto("Cerveja lata 350 ml", "UN", "6.00", "4.00", "24", CERVEJA);
        dados.dadosFiscais(cerveja, "22030000", 0, "Simples tributado");
        dados.abrirCaixa("100.00");

        LogCapturado log = LogCapturado.iniciar();
        Scene principal = abrirEEntrar(DadosTela.LOGIN_SUPERVISOR, DadosTela.SENHA_SUPERVISOR);
        Parent r = principal.getRoot();
        dados.gravarCertificadoDeTeste();
        SefazFalsa sefaz = new SefazFalsa(Clock.systemDefaultZone(), AppConfig.pastaPadrao().resolve("schemas"));
        DadosTela.EmissaoTela nfce = dados.emissaoNfce(sefaz);

        // ----- Duas vendas no PDV; a SEFAZ não responde à primeira NFC-e -----
        Scene pdv = abrirPdv(principal);
        long venda1 = vender(pdv);
        long venda2 = vender(pdv);
        Window janelaPdv = pdv.getWindow();
        tecla(pdv.getRoot(), KeyCode.ESCAPE);
        esperar(() -> !janelaPdv.isShowing(), "sair do PDV");

        sefaz.depois(SefazFalsa.Acao.SEM_RESPOSTA);
        EmissaoNfceService.Resultado primeira = nfce.emitirVenda(venda1);
        assertThat(primeira.semComunicacao()).isTrue();
        assertThat(primeira.nfce().status()).isEqualTo(StatusNfce.CONTINGENCIA);
        EmissaoNfceService.Resultado segunda = nfce.emitirVenda(venda2);
        assertThat(segunda.nfce().status()).as("já sai em contingência").isEqualTo(StatusNfce.CONTINGENCIA);
        assertThat(sefaz.envios()).as("a segunda nem tentou").hasSize(1);
        assertThat(contar("SELECT count(*) FROM nfce WHERE status = 'SEM_RESPOSTA'")).isEqualTo(1);

        // ----- A SEFAZ volta: o transmissor -----
        TransmissorNfce.Resumo resumo = nfce.transmitir();
        assertThat(resumo.semComunicacao()).as(resumo.mensagem()).isFalse();
        assertThat(resumo.autorizadas()).isEqualTo(2);
        assertThat(resumo.inutilizadas()).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM nfce WHERE status = 'AUTORIZADA'")).isEqualTo(2);
        assertThat(contar("SELECT count(*) FROM nfce WHERE status = 'INUTILIZADA'")).isEqualTo(1);

        // ----- Configurações › Auditoria: a contingência e a inutilização, automáticas -----
        abrirTela(principal, ItemMenu.CONFIGURACOES);
        assertThat(selecionarAba(r, "Auditoria")).isTrue();
        noFx(() -> {
            @SuppressWarnings("unchecked")
            ComboBox<?> acao = (ComboBox<?>) r.lookup("#auditoriaAcao");
            acao.getSelectionModel().select(0); // todas
            ((Button) r.lookup("#botaoConsultarAuditoria")).fire();
        });
        esperar(() -> fxr(() -> registros(r).stream().anyMatch(a -> a.acao() == AcaoAuditoria.NFCE_INUTILIZADA)),
                "auditoria da NFC-e");
        List<RegistroAuditoria> registros = fx(() -> registros(r));
        Set<AcaoAuditoria> acoes = registros.stream().map(RegistroAuditoria::acao).collect(Collectors.toSet());
        assertThat(acoes).contains(AcaoAuditoria.NFCE_CONTINGENCIA_INICIADA, AcaoAuditoria.NFCE_CONTINGENCIA_ENCERRADA,
                AcaoAuditoria.NFCE_INUTILIZADA);
        assertThat(registros.stream().filter(a -> a.acao() == AcaoAuditoria.NFCE_CONTINGENCIA_ENCERRADA)
                .map(RegistroAuditoria::descricao).findFirst().orElseThrow()).contains("(automático)");
        capturar(principal, "auditoria_contingencia");

        // ----- Diagnóstico: a vw_nfce_inconsistente vazia -----
        assertThat(selecionarAba(r, "Diagnóstico")).isTrue();
        noFx(() -> botao(r, "Verificar agora").fire());
        Thread.sleep(300);
        esperar(() -> fxr(() -> !r.lookup("#botaoVerificar").isDisabled()), "diagnóstico");
        assertThat(fx(() -> ((ListView<?>) r.lookup("#listaNfce")).getItems())).isEmpty();
        capturar(principal, "diagnostico");

        // ----- Log: o cStat e o motivo, nunca o XML nem o certificado -----
        String texto = log.textos();
        assertThat(texto).contains("cStat 100").doesNotContain("<infNFe", "<NFe", "<Signature", CertificadoTeste.SENHA,
                CertificadoTeste.arquivo().toString());
    }

    @SuppressWarnings("unchecked")
    private static List<RegistroAuditoria> registros(Parent r) {
        return List.copyOf(((TableView<RegistroAuditoria>) r.lookup("#tabelaAuditoria")).getItems());
    }

    /** Uma cerveja paga com R$ 10,00 em dinheiro; devolve o número da venda. */
    private long vender(Scene pdv) throws Exception {
        Parent p = pdv.getRoot();
        digitar(p, "#entrada", CERVEJA);
        tecla(p, KeyCode.F10);
        Scene pagamento = esperarCena(s -> s.getRoot().lookup("#botaoFinalizar") != null
                && s.getWindow().isShowing(), "pagamento");
        Parent g = pagamento.getRoot();
        noFx(() -> {
            @SuppressWarnings("unchecked")
            ComboBox<FormaPagamento> forma = (ComboBox<FormaPagamento>) g.lookup("#forma");
            forma.setValue(forma.getItems().stream().filter(f -> f.tipo() == TipoFormaPagamento.DINHEIRO)
                    .findFirst().orElseThrow());
            TextField valor = (TextField) g.lookup("#valor");
            valor.setText("10,00");
            valor.fireEvent(new ActionEvent());
        });
        Thread.sleep(300);
        Platform.runLater(() -> ((Button) g.lookup("#botaoFinalizar")).fire());
        DialogPane finalizada = esperarDialogo("finalizada");
        long venda = Long.parseLong(finalizada.getHeaderText().replaceAll("\\D", ""));
        Window janela = finalizada.getScene().getWindow();
        Platform.runLater(() -> {
            for (ButtonType b : finalizada.getButtonTypes()) {
                if (b.getText().equals("Próxima venda")) {
                    ((Button) finalizada.lookupButton(b)).fire();
                }
            }
        });
        esperar(() -> !janela.isShowing(), "próxima venda");
        Thread.sleep(400);
        return venda;
    }
}
