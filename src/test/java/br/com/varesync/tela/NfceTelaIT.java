package br.com.varesync.tela;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.config.AppConfig;
import br.com.varesync.controller.ItemMenu;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.NfceGravada;
import br.com.varesync.model.StatusNfce;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.service.DadosTela;
import br.com.varesync.service.nfce.CertificadoTeste;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

/**
 * NFC-e em homologação, parte A (F23.2 e F23.3), pela tela: o endereço do emitente em Configurações › Loja, o
 * certificado A1 deste terminal em Configurações › NFC-e (o de teste, gerado pelo keytool), uma venda no PDV cujo XML é
 * montado, assinado e validado com o certificado gravado pela tela (nada é transmitido) e o Diagnóstico com a
 * {@code vw_nfce_inconsistente}. A senha e o caminho do certificado não aparecem no log nem na auditoria.
 */
class NfceTelaIT extends TelaBase {

    private static final String CERVEJA = "7891000315507";

    @Test
    void emitenteCertificadoXmlEDiagnostico() throws Exception {
        dados.criarSupervisor();
        dados.configurar("empresa.documento", CertificadoTeste.CNPJ);
        dados.configurar("empresa.uf", "SP");
        dados.configurar("empresa.ie", "123456789012");
        dados.perfilSimples("Simples tributado", "5102", "102");
        long cerveja = dados.produto("Cerveja lata 350 ml", "UN", "6.00", "4.00", "24", CERVEJA);
        dados.dadosFiscais(cerveja, "22030000", 0, "Simples tributado");
        dados.abrirCaixa("100.00");
        Path pfx = CertificadoTeste.arquivo();

        LogCapturado log = LogCapturado.iniciar();
        Scene principal = abrirEEntrar(DadosTela.LOGIN_SUPERVISOR, DadosTela.SENHA_SUPERVISOR);
        Parent r = principal.getRoot();
        abrirTela(principal, ItemMenu.CONFIGURACOES);

        // ----- Loja: o bloco "Endereço para a NFC-e" -----
        assertThat(selecionarAba(r, "Loja")).isTrue();
        assertThat(fx(() -> ((Label) r.lookup("#situacaoEmitente")).getText())).contains("ainda falta",
                "a razão social", "o código IBGE do município");
        capturar(principal, "loja_emitente_incompleto");
        noFx(() -> {
            texto(r, "#campoRazaoSocial", "Empresa Teste Ltda");
            texto(r, "#campoLogradouro", "Rua das Flores");
            texto(r, "#campoNumero", "100");
            texto(r, "#campoBairro", "Centro");
            texto(r, "#campoCep", "14010-100");
            texto(r, "#campoMunicipio", "Ribeirão Preto");
            texto(r, "#campoCodigoMunicipio", "3543402");
            botao(r, "Salvar alterações").fire();
        });
        esperar(() -> "Empresa Teste Ltda".equals(valorOuNulo(
                "SELECT valor FROM configuracao WHERE chave = 'empresa.razao_social'")), "emitente gravado");
        assertThat(valor("SELECT valor FROM configuracao WHERE chave = 'empresa.cep'")).isEqualTo("14010100");
        assertThat(fx(() -> ((Label) r.lookup("#situacaoEmitente")).getText()))
                .isEqualTo("Os dados do emitente estão completos para a NFC-e.");
        capturar(principal, "loja_emitente_completo");

        // ----- NFC-e: o certificado deste terminal -----
        assertThat(selecionarAba(r, "NFC-e")).isTrue();
        assertThat(fx(() -> ((Label) r.lookup("#situacaoCertificado")).getText())).contains("ainda não tem certificado");
        noFx(() -> {
            texto(r, "#campoCertificado", pfx.toString());
            texto(r, "#campoSenhaCertificado", CertificadoTeste.SENHA);
            botao(r, "Salvar certificado").fire();
        });
        esperar(() -> fxr(() -> ((Label) r.lookup("#situacaoCertificado")).getText()).startsWith("Titular:"),
                "certificado salvo");
        String situacao = fx(() -> ((Label) r.lookup("#situacaoCertificado")).getText());
        assertThat(situacao).contains("Titular: EMPRESA TESTE LTDA", "CNPJ: 12.345.678/0001-95", "válido até",
                "a raiz do CNPJ é a da loja").doesNotContain(CertificadoTeste.SENHA, pfx.toString());
        assertThat(fx(() -> ((TextField) r.lookup("#campoSenhaCertificado")).getText())).as("senha limpa").isEmpty();
        capturar(principal, "nfce_certificado_do_terminal");
        String config = Files.readString(AppConfig.arquivoPadrao(), StandardCharsets.UTF_8);
        assertThat(config).as("o certificado fica no config.properties do terminal").contains("nfce.certificado=");
        assertThat(contar("SELECT count(*) FROM configuracao WHERE valor LIKE '%.pfx%' OR valor LIKE '%"
                + CertificadoTeste.SENHA + "%'")).as("nada do certificado no banco").isZero();
        assertThat(contar("SELECT count(*) FROM auditoria WHERE acao = 'NFCE_CERTIFICADO_ALTERADO'")).isEqualTo(1);

        // ----- PDV: uma venda; o XML montado, assinado e validado (nada é transmitido) -----
        Scene pdv = abrirPdv(principal);
        Parent p = pdv.getRoot();
        digitar(p, "#entrada", "2*" + CERVEJA);
        tecla(p, KeyCode.F10);
        Scene pagamento = esperarCena(s -> s.getRoot().lookup("#botaoFinalizar") != null, "pagamento");
        Parent g = pagamento.getRoot();
        noFx(() -> {
            @SuppressWarnings("unchecked")
            ComboBox<FormaPagamento> forma = (ComboBox<FormaPagamento>) g.lookup("#forma");
            forma.setValue(forma.getItems().stream().filter(f -> f.tipo() == TipoFormaPagamento.DINHEIRO)
                    .findFirst().orElseThrow());
            TextField valor = (TextField) g.lookup("#valor");
            valor.setText("20,00");
            valor.fireEvent(new ActionEvent());
        });
        Thread.sleep(300);
        Platform.runLater(() -> ((Button) g.lookup("#botaoFinalizar")).fire());
        DialogPane finalizada = esperarDialogo("finalizada");
        long venda = Long.parseLong(finalizada.getHeaderText().replaceAll("\\D", ""));
        Window janelaFinalizada = finalizada.getScene().getWindow();
        Platform.runLater(() -> {
            for (ButtonType b : finalizada.getButtonTypes()) {
                if (b.getText().equals("Próxima venda")) {
                    ((Button) finalizada.lookupButton(b)).fire();
                }
            }
        });
        esperar(() -> !janelaFinalizada.isShowing(), "próxima venda");
        Thread.sleep(300);
        Window janelaPdv = pdv.getWindow();
        tecla(p, KeyCode.ESCAPE);
        esperar(() -> !janelaPdv.isShowing(), "sair do PDV");

        NfceGravada nfce = dados.nfceDaVenda(venda);
        assertThat(nfce.status()).as(nfce.motivo()).isEqualTo(StatusNfce.PENDENTE);
        assertThat(nfce.chave()).hasSize(44).startsWith("35").contains(CertificadoTeste.CNPJ + "65001");
        assertThat(nfce.xml()).contains("<Signature", "<tpAmb>2</tpAmb>", "<CRT>1</CRT>",
                "<xNome>Empresa Teste Ltda</xNome>", "<vTroco>8.00</vTroco>").doesNotContain("<dest>");
        assertThat(nfce.qrcode()).endsWith("?p=" + nfce.chave() + "|3|2");

        // ----- Diagnóstico: a vw_nfce_inconsistente vazia -----
        abrirTela(principal, ItemMenu.PAINEL);
        abrirTela(principal, ItemMenu.CONFIGURACOES);
        assertThat(selecionarAba(r, "Diagnóstico")).isTrue();
        noFx(() -> botao(r, "Verificar agora").fire());
        Thread.sleep(300);
        // O sucesso vira notificação discreta (F21.5): confere a lista vazia e o fim da verificação.
        esperar(() -> fxr(() -> !r.lookup("#botaoVerificar").isDisabled()), "diagnóstico das NFC-e");
        assertThat(fx(() -> ((javafx.scene.control.ListView<?>) r.lookup("#listaNfce")).getItems())).isEmpty();
        assertThat(fx(() -> ((Label) r.lookup("#resultadoNfce")).getStyleClass())).doesNotContain("mensagem-erro");
        capturar(principal, "diagnostico_nfce");

        // ----- Sigilo: nem a senha nem o caminho do certificado no log ou na auditoria -----
        assertThat(log.textos()).as("log do sistema e da biblioteca")
                .contains("Login de admin") // a captura pegou o log da sessão
                .doesNotContain(CertificadoTeste.SENHA, pfx.toString(), pfx.getFileName().toString());
        assertThat(contar("SELECT count(*) FROM auditoria WHERE descricao LIKE '%" + CertificadoTeste.SENHA
                + "%' OR descricao LIKE '%.pfx%'")).isZero();
    }

    private static void texto(Parent r, String id, String valor) {
        ((TextField) r.lookup(id)).setText(valor);
    }

    private static String valorOuNulo(String sql) {
        try {
            return valor(sql);
        } catch (java.sql.SQLException e) {
            return null;
        }
    }
}
