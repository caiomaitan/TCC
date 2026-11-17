package br.com.varesync.tela;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.config.AppConfig;
import br.com.varesync.config.AppContext;
import br.com.varesync.controller.ItemMenu;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.report.ImpressaoComprovante;
import br.com.varesync.report.LeituraQrCode;
import br.com.varesync.service.DadosTela;
import br.com.varesync.service.nfce.CertificadoTeste;
import br.com.varesync.service.nfce.SefazFalsa;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.stage.Window;
import javax.print.PrintService;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperPrintManager;
import org.junit.jupiter.api.Test;

/**
 * NFC-e em homologação, parte C (F23.6 e F23.7), pela tela, com a SEFAZ falsa e uma impressora que não imprime (nada vai
 * à rede nem ao papel): "NFC-e ligada" e "Testar comunicação" em Configurações; no PDV, o produto sem dados fiscais
 * recusado, o "CPF/CNPJ na nota" (inválido recusado), a venda autorizada com o DANFE impresso e mostrado (o QR Code da
 * página lido de volta), a venda em contingência com o DANFE e a via do estabelecimento, o aviso fixo da contingência e o
 * "Reimprimir" do F9.
 */
class NfceParteCTelaIT extends TelaBase {

    private static final String CERVEJA = "7891000315507";
    private static final String BALA = "7891234567895";

    @Test
    void pdvComANfceLigada() throws Exception {
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
        dados.produto("Bala de goma", "UN", "1.00", "0.40", "50", BALA); // sem dados fiscais
        dados.abrirCaixa("100.00");

        SefazFalsa sefaz = new SefazFalsa(Clock.systemDefaultZone(), AppConfig.pastaPadrao().resolve("schemas"));
        List<JasperPrint> impressos = new CopyOnWriteArrayList<>();
        PrintService impressora = (PrintService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PrintService.class}, (p, m, a) -> "getName".equals(m.getName())
                        ? "Impressora de teste" : null);
        AppContext.Ajustes ajustes = new AppContext.Ajustes(sefaz, pdf -> new ImpressaoComprovante(pdf,
                () -> impressora, (impressao, destino) -> impressos.add(impressao)));

        LogCapturado log = LogCapturado.iniciar();
        Scene principal = abrirEEntrar(DadosTela.LOGIN_SUPERVISOR, DadosTela.SENHA_SUPERVISOR, ajustes);
        Parent r = principal.getRoot();
        dados.gravarCertificadoDeTeste();

        // ----- Configurações › NFC-e: ligar e testar a comunicação -----
        abrirTela(principal, ItemMenu.CONFIGURACOES);
        assertThat(selecionarAba(r, "NFC-e")).isTrue();
        noFx(() -> {
            CheckBox ligada = (CheckBox) r.lookup("#campoNfceAtiva");
            ligada.setSelected(true);
            ligada.fireEvent(new ActionEvent());
        });
        assertThat(fx(() -> ((Label) r.lookup("#mensagemNfceAtiva")).getText())).startsWith("NFC-e ligada");
        assertThat(valor("SELECT valor FROM configuracao WHERE chave = 'nfce.ativa'")).isEqualTo("true");
        noFx(() -> ((Button) r.lookup("#botaoTestarNfce")).fire());
        esperar(() -> fxr(() -> ((Label) r.lookup("#mensagemNfceAtiva")).getText().contains("cStat")),
                "testar comunicação");
        assertThat(fx(() -> ((Label) r.lookup("#mensagemNfceAtiva")).getText())).contains("cStat 107",
                "em operação");
        capturar(principal, "nfce_ligada_e_testar_comunicacao");

        // ----- PDV: o produto sem dados fiscais é recusado -----
        Scene pdv = abrirPdv(principal);
        Parent p = pdv.getRoot();
        digitar(p, "#entrada", BALA);
        assertThat(fx(() -> ((Label) p.lookup("#mensagem")).getText())).contains("Bala de goma",
                "não pode ser vendido com a NFC-e ligada", "o NCM");
        capturar(pdv, "pdv_produto_sem_dados_fiscais");

        // ----- Venda autorizada: CPF inválido recusado; com o CPF certo, o DANFE impresso -----
        digitar(p, "#entrada", CERVEJA);
        tecla(p, KeyCode.F10);
        Scene pagamento = esperarCena(s -> s.getRoot().lookup("#botaoFinalizar") != null
                && s.getWindow().isShowing(), "pagamento");
        Parent g = pagamento.getRoot();
        pagarEmDinheiro(g, "10,00");
        assertThat(fx(() -> g.lookup("#linhaDocumento").isVisible())).isTrue();
        noFx(() -> ((TextField) g.lookup("#campoDocumento")).setText("529.982.247-24"));
        noFx(() -> ((Button) g.lookup("#botaoFinalizar")).fire());
        assertThat(fx(() -> ((Label) g.lookup("#mensagem")).getText())).contains("CPF/CNPJ na nota",
                "dígito verificador");
        capturar(pagamento, "pagamento_cpf_invalido");
        noFx(() -> ((TextField) g.lookup("#campoDocumento")).setText("529.982.247-25"));
        Platform.runLater(() -> ((Button) g.lookup("#botaoFinalizar")).fire());

        DialogPane autorizada = esperarSituacao("autorizada");
        capturar(autorizada.getScene(), "venda_autorizada");
        assertThat(impressos).hasSize(1);
        String qrAutorizada = valor("SELECT qrcode FROM nfce WHERE status = 'AUTORIZADA'");
        assertThat(LeituraQrCode.lerDaPagina(impressos.get(0))).isEqualTo(qrAutorizada);
        gravarImagem(pagina(impressos.get(0)), "danfe_autorizado_impresso");
        Scene danfe = responderEEsperarDanfe(autorizada, "Ver DANFE");
        String texto = fx(() -> ((TextArea) danfe.getRoot().lookup("#texto")).getText());
        assertThat(texto).contains("Documento Auxiliar da Nota Fiscal", "Protocolo de autorização",
                "EMITIDA EM AMBIENTE DE HOMOLOGAÇÃO", "CONSUMIDOR - CPF: 529.982.247-25")
                .doesNotContain("NÃO É DOCUMENTO FISCAL");
        assertThat(fx(() -> ((ImageView) danfe.getRoot().lookup("#qrCode")).getImage())).isNotNull();
        capturar(danfe, "danfe_autorizado_na_tela");
        fecharJanela(danfe);

        // ----- Venda em contingência: o DANFE e a via do estabelecimento, sem protocolo; o aviso fixo -----
        sefaz.depois(SefazFalsa.Acao.SEM_RESPOSTA);
        digitar(p, "#entrada", CERVEJA);
        tecla(p, KeyCode.F10);
        Scene pagamento2 = esperarCena(s -> s.getRoot().lookup("#botaoFinalizar") != null
                && s.getWindow().isShowing(), "pagamento");
        pagarEmDinheiro(pagamento2.getRoot(), "10,00");
        Platform.runLater(() -> ((Button) pagamento2.getRoot().lookup("#botaoFinalizar")).fire());
        DialogPane contingencia = esperarSituacao("contingência");
        capturar(contingencia.getScene(), "venda_em_contingencia");
        assertThat(impressos).hasSize(3);
        String qrContingencia = valor("SELECT qrcode FROM nfce WHERE status = 'CONTINGENCIA'");
        assertThat(LeituraQrCode.lerDaPagina(impressos.get(1))).isEqualTo(qrContingencia);
        assertThat(LeituraQrCode.lerDaPagina(impressos.get(2))).isEqualTo(qrContingencia);
        gravarImagem(pagina(impressos.get(1)), "danfe_contingencia_impresso");
        gravarImagem(pagina(impressos.get(2)), "danfe_contingencia_via_estabelecimento");
        responder(contingencia, "Próxima venda");
        Thread.sleep(400);
        String aviso = fx(() -> ((Label) p.lookup("#avisoContingencia")).getText());
        assertThat(aviso).matches("NFC-e em contingência desde \\d\\d:\\d\\d: as notas serão transmitidas quando a "
                + "SEFAZ voltar");
        assertThat(valor("SELECT motivo FROM nfce WHERE status = 'SEM_RESPOSTA'"))
                .isEqualTo("Sem resposta da SEFAZ: SEFAZ falsa: tempo esgotado");
        capturar(pdv, "pdv_aviso_contingencia");

        // ----- F9 › Reimprimir: o DANFE de contingência -----
        tecla(p, KeyCode.F9);
        Scene vendasSessao = esperarCena(s -> s.getRoot().lookup("#tabelaVendas") != null, "vendas da sessão");
        noFx(() -> {
            ((TableView<?>) vendasSessao.getRoot().lookup("#tabelaVendas")).getSelectionModel().select(0);
        });
        Platform.runLater(() -> ((Button) vendasSessao.getRoot().lookup("#botaoComprovante")).fire());
        Scene reimpresso = esperarCena(s -> s.getRoot().lookup("#qrCode") != null && s.getWindow().isShowing()
                && s.getRoot().lookup("#qrCode").isVisible(), "DANFE reimpresso");
        String textoReimpresso = fx(() -> ((TextArea) reimpresso.getRoot().lookup("#texto")).getText());
        assertThat(textoReimpresso).contains("EMITIDA EM CONTINGÊNCIA", "Pendente de autorização")
                .doesNotContain("Protocolo de autorização");
        capturar(reimpresso, "danfe_contingencia_reimpresso");
        fecharJanela(reimpresso);
        fecharJanela(vendasSessao);

        // ----- Nada do certificado nem do XML no log -----
        assertThat(contar("SELECT count(*) FROM vw_nfce_inconsistente")).isZero();
        assertThat(log.textos()).doesNotContain("<infNFe", "<NFe", "<Signature", CertificadoTeste.SENHA,
                CertificadoTeste.arquivo().toString());
    }

    private static void pagarEmDinheiro(Parent g, String valor) throws Exception {
        noFx(() -> {
            @SuppressWarnings("unchecked")
            ComboBox<FormaPagamento> forma = (ComboBox<FormaPagamento>) g.lookup("#forma");
            forma.setValue(forma.getItems().stream().filter(f -> f.tipo() == TipoFormaPagamento.DINHEIRO)
                    .findFirst().orElseThrow());
            TextField campo = (TextField) g.lookup("#valor");
            campo.setText(valor);
            campo.fireEvent(new ActionEvent());
        });
        Thread.sleep(300);
    }

    /** O diálogo "Venda nº N finalizada" com a situação da NFC-e (depois da emissão na Task). */
    private static DialogPane esperarSituacao(String trecho) throws Exception {
        Scene cena = esperarCena(s -> s.getRoot() instanceof DialogPane d && d.getHeaderText() != null
                && d.getHeaderText().contains("finalizada") && d.lookup("#situacaoNfce") instanceof Label l
                && l.getText().contains(trecho), "venda finalizada com a NFC-e " + trecho);
        Thread.sleep(300);
        return (DialogPane) cena.getRoot();
    }

    private static void responder(DialogPane d, String botao) throws Exception {
        Window w = d.getScene().getWindow();
        Platform.runLater(() -> {
            for (ButtonType b : d.getButtonTypes()) {
                if (b.getText().equals(botao)) {
                    ((Button) d.lookupButton(b)).fire();
                }
            }
        });
        esperar(() -> !w.isShowing(), botao);
    }

    private static Scene responderEEsperarDanfe(DialogPane d, String botao) throws Exception {
        responder(d, botao);
        Scene danfe = esperarCena(s -> s.getRoot().lookup("#qrCode") != null && s.getWindow().isShowing(), "DANFE");
        Thread.sleep(400);
        return danfe;
    }

    private static void fecharJanela(Scene cena) throws Exception {
        Window w = cena.getWindow();
        Platform.runLater(w::hide);
        esperar(() -> !w.isShowing(), "fechar");
        Thread.sleep(300);
    }

    private static BufferedImage pagina(JasperPrint impressao) throws Exception {
        return (BufferedImage) JasperPrintManager.printPageToImage(impressao, 0, 3f);
    }
}
