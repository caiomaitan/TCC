package br.com.varesync.tela;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.config.AppConfig;
import br.com.varesync.config.AppContext;
import br.com.varesync.controller.ItemMenu;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.NotaFiscal;
import br.com.varesync.model.StatusNfce;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.report.ImpressaoComprovante;
import br.com.varesync.report.Relatorio;
import br.com.varesync.service.DadosTela;
import br.com.varesync.service.nfce.CertificadoTeste;
import br.com.varesync.service.nfce.SefazFalsa;
import br.com.varesync.ui.DialogosNfce;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.stage.Window;
import javax.print.PrintService;
import net.sf.jasperreports.engine.JasperPrint;
import org.junit.jupiter.api.Test;

/**
 * NFC-e em homologação, parte D (F23.8 a F23.10), pela tela, com a SEFAZ falsa e uma impressora que não imprime (nada
 * vai à rede nem ao papel): o cancelamento da venda com NFC-e pelo F9 (aos 29 minutos cancela; aos 31, recusa; em
 * contingência, recusa), o aviso de contingência do PDV sumindo assim que o "Transmitir agora" sai do modo, a aba Notas
 * fiscais (lista, corrigir e reenviar, ver DANFE, salvar XML, inutilizar números, ZIP do mês), os alertas do painel com o
 * clique na aba filtrada e o R32 batendo com a aba.
 */
class NfceParteDTelaIT extends TelaBase {

    private static final String CERVEJA = "7891000315507";
    private static final String MOTIVO = "Cliente desistiu da compra no caixa";

    @Test
    void cancelamentoNotasFiscaisEPainel() throws Exception {
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
        long cerveja = dados.produto("Cerveja lata 350 ml", "UN", "6.00", "4.00", "48", CERVEJA);
        dados.dadosFiscais(cerveja, "22030000", 0, "Simples tributado");
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
        dados.configurar("nfce.ativa", "true");

        // ----- PDV: três vendas (autorizada, rejeitada, autorizada) -----
        Scene pdv = abrirPdv(principal);
        Parent p = pdv.getRoot();
        long vendaA = vender(p, "autorizada");
        sefaz.depois("539");
        long vendaB = vender(p, "rejeitada");
        long vendaC = vender(p, "autorizada");

        // ----- F23.8 pelo F9: a de 29 minutos cancela; a de 31, recusa -----
        executar("UPDATE nfce SET autorizada_em = now() - interval '29 minutes' WHERE venda_id = " + vendaA);
        executar("UPDATE nfce SET autorizada_em = now() - interval '31 minutes' WHERE venda_id = " + vendaC);
        tecla(p, KeyCode.F9);
        Scene f9 = esperarCena(s -> s.getRoot().lookup("#tabelaVendas") != null, "vendas da sessão");
        Parent v = f9.getRoot();
        DialogPane motivo = pedirCancelamento(v, vendaA);
        escreverMotivo(motivo, MOTIVO);
        confirmarDialogo(motivo);
        // F21.5: o sucesso vai para a notificação discreta da janela (o rótulo fica vazio)
        esperar(() -> fxr(() -> String.valueOf(br.com.varesync.ui.Notificacao.atual(f9.getWindow()))
                .contains("cancelada, com a NFC-e")), "cancelamento com a NFC-e");
        assertThat(valor("SELECT status FROM nfce WHERE venda_id = " + vendaA)).isEqualTo("CANCELADA");
        assertThat(valor("SELECT status FROM venda WHERE id = " + vendaA)).isEqualTo("CANCELADA");
        assertThat(sefaz.eventos()).hasSize(1);
        capturar(f9, "f9_cancelada_aos_29_minutos");

        DialogPane recusa = pedirCancelamento(v, vendaC);
        escreverMotivo(recusa, MOTIVO);
        Platform.runLater(() -> ((Button) recusa.lookupButton(recusa.getButtonTypes().get(0))).fire());
        esperar(() -> fxr(() -> textoDosRotulos(recusa).contains("mais de 30 minutos")), "recusa dos 31 minutos");
        assertThat(fx(() -> textoDosRotulos(recusa))).contains("A NFC-e foi autorizada há mais de 30 minutos e não "
                + "pode mais ser cancelada. Registre uma devolução.");
        capturar(recusa.getScene(), "f9_recusa_aos_31_minutos");
        cancelarDialogo(recusa);
        assertThat(valor("SELECT status FROM venda WHERE id = " + vendaC)).isEqualTo("FINALIZADA");
        fecharJanela(f9);

        // ----- Contingência: o aviso fixo; a venda não cancela -----
        sefaz.depois(SefazFalsa.Acao.SEM_RESPOSTA);
        long vendaD = vender(p, "contingência");
        assertThat(fx(() -> ((Label) p.lookup("#avisoContingencia")).getText())).startsWith("NFC-e em contingência");
        tecla(p, KeyCode.F9);
        Scene f9b = esperarCena(s -> s.getRoot().lookup("#tabelaVendas") != null && s.getWindow().isShowing(),
                "vendas da sessão");
        DialogPane contingencia = pedirCancelamento(f9b.getRoot(), vendaD);
        escreverMotivo(contingencia, MOTIVO);
        Platform.runLater(() -> ((Button) contingencia.lookupButton(contingencia.getButtonTypes().get(0))).fire());
        esperar(() -> fxr(() -> textoDosRotulos(contingencia).contains("sem resposta da SEFAZ")),
                "recusa da contingência");
        capturar(contingencia.getScene(), "f9_recusa_em_contingencia");
        cancelarDialogo(contingencia);
        fecharJanela(f9b);
        capturar(pdv, "pdv_aviso_contingencia_antes");

        // ----- F23.9 Aba Notas fiscais -----
        abrirTela(principal, ItemMenu.FISCAL);
        assertThat(selecionarAba(r, "Notas fiscais")).isTrue();
        Thread.sleep(500);
        TableView<NotaFiscal> tabela = tabela(r);
        assertThat(fx(() -> tabela.getItems().size())).isEqualTo(5); // A, B, C, a original de D e a de contingência
        capturar(principal, "notas_fiscais_lista");

        // Corrigir e reenviar a rejeitada (no mesmo número).
        int numeroB = Integer.parseInt(valor("SELECT numero FROM nfce WHERE venda_id = " + vendaB));
        selecionar(tabela, vendaB);
        noFx(() -> ((Button) r.lookup("#botaoReenviar")).fire());
        esperarMensagem(r, "corrigida e autorizada");
        assertThat(valor("SELECT status || ';' || numero FROM nfce WHERE venda_id = " + vendaB))
                .isEqualTo("AUTORIZADA;" + numeroB);
        capturar(principal, "notas_fiscais_reenviada");

        // Transmitir agora: a de contingência é autorizada, e o aviso do PDV some sem a tela do PDV ser atualizada.
        noFx(() -> ((Button) r.lookup("#botaoTransmitir")).fire());
        esperarMensagem(r, "autorizada");
        esperar(() -> fxr(() -> ((Label) p.lookup("#avisoContingencia")).getText().isEmpty()), "o aviso sumir");
        assertThat(fx(() -> p.lookup("#avisoContingencia").isVisible())).isFalse();
        capturar(pdv, "pdv_aviso_contingencia_sumiu");
        capturar(principal, "notas_fiscais_transmitir_agora");
        assertThat(valor("SELECT status FROM nfce WHERE venda_id = " + vendaD + " AND tipo_emissao = 9"))
                .isEqualTo("AUTORIZADA");

        // Ver DANFE da reenviada.
        selecionar(tabela, vendaB);
        Platform.runLater(() -> ((Button) r.lookup("#botaoDanfe")).fire());
        Scene danfe = esperarCena(s -> s.getRoot().lookup("#qrCode") != null && s.getWindow().isShowing(), "DANFE");
        Thread.sleep(400);
        capturar(danfe, "notas_fiscais_ver_danfe");
        fecharJanela(danfe);

        // Salvar XML, Inutilizar números e Exportar XML do mês, sem os diálogos do sistema (pelo controller).
        Object controller = controllerDaAba(fx(() -> (Button) r.lookup("#botaoInutilizar")));
        long nfceB = Long.parseLong(valor("SELECT id FROM nfce WHERE venda_id = " + vendaB));
        Path xml = Path.of("target", "telas", getClass().getSimpleName(), "nfce-reenviada.xml");
        Files.createDirectories(xml.getParent());
        noFx(() -> chamar(controller, "salvarXml", new Class<?>[] {long.class, Path.class}, nfceB, xml));
        assertThat(Files.readString(xml, StandardCharsets.UTF_8)).contains("<nfeProc", "<protNFe");

        noFx(() -> chamar(controller, "inutilizar", new Class<?>[] {DialogosNfce.Faixa.class},
                new DialogosNfce.Faixa(1, 20, 22, "Numeros pulados na troca do sistema de caixa")));
        assertThat(esperarMensagem(r, "inutilizados")).startsWith("Números 20 a 22 da série 1 "
                + "inutilizados (protocolo ");
        assertThat(sefaz.inutilizacoes()).contains("1:20-22");
        capturar(principal, "notas_fiscais_inutilizacao");

        Path zip = Path.of("target", "telas", getClass().getSimpleName(), "nfce-mes.zip");
        Files.deleteIfExists(zip);
        noFx(() -> chamar(controller, "exportarMes", new Class<?>[] {YearMonth.class, Path.class},
                YearMonth.now(), zip));
        esperarMensagem(r, "ZIP gravado");
        List<String> nomes = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                nomes.add(e.getName());
            }
        }
        // As autorizadas (B, C e a de contingência), a cancelada (A), os eventos (110111 de A e a inutilização da
        // original de D, que não constava) e a inutilização da faixa.
        assertThat(nomes).filteredOn(n -> n.startsWith("NFC-e/")).hasSize(4);
        assertThat(nomes).filteredOn(n -> n.startsWith("Eventos/")).hasSize(1);
        assertThat(nomes).filteredOn(n -> n.startsWith("Inutilizacoes/")).hasSize(2);
        capturar(principal, "notas_fiscais_zip_do_mes");

        // ----- F23.10 Painel: os alertas e o clique na aba filtrada -----
        sefaz.depois("539");
        vender(p, "rejeitada");
        sefaz.depois(SefazFalsa.Acao.SEM_RESPOSTA);
        vender(p, "contingência");
        abrirTela(principal, ItemMenu.PAINEL);
        noFx(() -> ((ScrollPane) r.lookup(".painel-rolagem")).setVvalue(1));
        Thread.sleep(400);
        Hyperlink aTransmitir = link(r, "NFC-e a transmitir");
        Hyperlink rejeitadas = link(r, "rejeitada a corrigir");
        assertThat(fx(aTransmitir::getText)).startsWith("2 NFC-e a transmitir (em contingência ou sem resposta)");
        assertThat(fx(rejeitadas::getText)).startsWith("1 NFC-e rejeitada a corrigir");
        capturar(principal, "painel_alertas_nfce");
        noFx(rejeitadas::fire);
        esperar(() -> fxr(() -> r.lookup("#botaoReenviar") != null && r.lookup("#botaoReenviar").isVisible()),
                "aba Notas fiscais");
        Thread.sleep(600);
        TableView<NotaFiscal> filtrada = tabela(r);
        assertThat(fx(() -> filtrada.getItems().stream().map(NotaFiscal::status).toList()))
                .containsExactly(StatusNfce.REJEITADA);
        assertThat(fx(() -> ((DatePicker) r.lookup("#campoDe")).getValue())).isNull();
        capturar(principal, "painel_clique_rejeitadas");

        // ----- R32: as mesmas notas da aba no mesmo período -----
        noFx(() -> {
            @SuppressWarnings("unchecked")
            ComboBox<Object> situacao = (ComboBox<Object>) r.lookup("#campoSituacao");
            situacao.getSelectionModel().selectFirst();
            ((DatePicker) r.lookup("#campoDe")).setValue(LocalDate.now());
            ((DatePicker) r.lookup("#campoAte")).setValue(LocalDate.now());
        });
        Thread.sleep(500);
        int naAba = fx(() -> tabela(r).getItems().size());
        abrirTela(principal, ItemMenu.RELATORIOS);
        gerar(r, "R32");
        assertThat(fx(() -> linhas(r))).isEqualTo(naAba).isEqualTo((int) contar("SELECT count(*) FROM nfce"));
        capturar(principal, "r32_nfce_emitidas");

        // ----- Nada do certificado nem do XML no log; nenhuma inconsistência -----
        assertThat(contar("SELECT count(*) FROM vw_nfce_inconsistente")).isZero();
        assertThat(log.textos()).doesNotContain("<infNFe", "<NFe", "<Signature", "<infEvento",
                CertificadoTeste.SENHA, CertificadoTeste.arquivo().toString());
        Window janelaPdv = pdv.getWindow();
        tecla(p, KeyCode.ESCAPE);
        esperar(() -> !janelaPdv.isShowing(), "sair do PDV");
    }

    // ----- Apoio -----

    /** Uma venda de 1 cerveja em dinheiro; espera a situação da NFC-e, fecha o aviso e devolve o número da venda. */
    private long vender(Parent p, String situacao) throws Exception {
        long antes = contar("SELECT coalesce(max(id), 0) FROM venda");
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
            TextField campo = (TextField) g.lookup("#valor");
            campo.setText("10,00");
            campo.fireEvent(new ActionEvent());
        });
        Thread.sleep(300);
        Platform.runLater(() -> ((Button) g.lookup("#botaoFinalizar")).fire());
        Scene cena = esperarCena(s -> s.getRoot() instanceof DialogPane d && d.getHeaderText() != null
                && d.getHeaderText().contains("finalizada") && d.lookup("#situacaoNfce") instanceof Label l
                && l.getText().contains(situacao), "venda com a NFC-e " + situacao);
        Thread.sleep(300);
        DialogPane d = (DialogPane) cena.getRoot();
        Window w = cena.getWindow();
        Platform.runLater(() -> {
            for (ButtonType b : d.getButtonTypes()) {
                if (b.getText().equals("Próxima venda")) {
                    ((Button) d.lookupButton(b)).fire();
                }
            }
        });
        esperar(() -> !w.isShowing(), "próxima venda");
        Thread.sleep(300);
        long venda = contar("SELECT max(id) FROM venda");
        assertThat(venda).isGreaterThan(antes);
        return venda;
    }

    /** Seleciona a venda no F9 e pede o cancelamento: o diálogo do motivo. */
    private static DialogPane pedirCancelamento(Parent v, long vendaId) throws Exception {
        noFx(() -> {
            @SuppressWarnings("unchecked")
            TableView<br.com.varesync.model.VendaResumo> t =
                    (TableView<br.com.varesync.model.VendaResumo>) v.lookup("#tabelaVendas");
            t.getItems().stream().filter(x -> x.id() == vendaId).findFirst()
                    .ifPresent(x -> t.getSelectionModel().select(x));
        });
        Platform.runLater(() -> ((Button) v.lookup("#botaoCancelar")).fire());
        DialogPane d = esperarDialogo("Cancelar a venda nº " + vendaId);
        Thread.sleep(300);
        return d;
    }

    private static void escreverMotivo(DialogPane d, String texto) throws Exception {
        noFx(() -> {
            ComboBox<?> combo = (ComboBox<?>) d.lookup(".combo-box");
            combo.getEditor().setText(texto);
        });
    }

    private static void cancelarDialogo(DialogPane d) throws Exception {
        Window w = d.getScene().getWindow();
        Platform.runLater(() -> {
            for (ButtonType b : d.getButtonTypes()) {
                if (b.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE) {
                    ((Button) d.lookupButton(b)).fire();
                }
            }
        });
        esperar(() -> !w.isShowing(), "cancelar o diálogo");
        Thread.sleep(300);
    }

    private static String textoDosRotulos(DialogPane d) {
        StringBuilder s = new StringBuilder();
        for (Node n : d.lookupAll(".label")) {
            s.append(((Label) n).getText()).append('\n');
        }
        return s.toString();
    }

    @SuppressWarnings("unchecked")
    private static TableView<NotaFiscal> tabela(Parent r) throws Exception {
        return fx(() -> (TableView<NotaFiscal>) r.lookupAll(".table-view").stream()
                .filter(n -> ((TableView<?>) n).getColumns().stream().anyMatch(c -> "Protocolo".equals(c.getText())))
                .findFirst().orElseThrow());
    }

    private static void selecionar(TableView<NotaFiscal> tabela, long vendaId) throws Exception {
        noFx(() -> tabela.getItems().stream().filter(n -> n.vendaId() == vendaId && n.status() != StatusNfce.SEM_RESPOSTA)
                .findFirst().ifPresent(n -> tabela.getSelectionModel().select(n)));
        Thread.sleep(200);
    }

    /**
     * Espera o aviso da aba: o erro e a informação ficam no rótulo; o sucesso vai para a notificação discreta da janela
     * (F21.5). Devolve o texto achado.
     */
    private static String esperarMensagem(Parent r, String trecho) throws Exception {
        String[] achado = new String[1];
        esperar(() -> fxr(() -> {
            Label m = mensagemDaAba(r);
            String rotulo = m == null || m.getText() == null ? "" : m.getText();
            String notificacao = String.valueOf(br.com.varesync.ui.Notificacao.atual(r.getScene().getWindow()));
            String texto = rotulo.contains(trecho) ? rotulo : notificacao.contains(trecho) ? notificacao : null;
            achado[0] = texto;
            return texto != null && !r.lookup("#botaoTransmitir").isDisabled();
        }), "mensagem \"" + trecho + "\"");
        Thread.sleep(300);
        return achado[0];
    }

    /** O rótulo de mensagem da aba Notas fiscais (o #mensagem que fica depois dos botões dela). */
    private static Label mensagemDaAba(Parent r) {
        Node botao = r.lookup("#botaoTransmitir");
        return botao == null ? null : (Label) botao.getParent().getParent().lookup("#mensagem");
    }

    private static Hyperlink link(Parent r, String trecho) throws Exception {
        return fx(() -> r.lookupAll(".hyperlink").stream().map(Hyperlink.class::cast)
                .filter(h -> h.getText().contains(trecho)).findFirst()
                .orElseThrow(() -> new AssertionError("alerta \"" + trecho + "\" não encontrado")));
    }

    private void gerar(Parent r, String codigo) throws Exception {
        noFx(() -> {
            @SuppressWarnings("unchecked")
            ComboBox<Relatorio<?>> escolha = (ComboBox<Relatorio<?>>) r.lookup("#escolhaRelatorio");
            escolha.getItems().stream().filter(x -> x.codigo().equals(codigo)).findFirst()
                    .ifPresent(x -> escolha.getSelectionModel().select(x));
        });
        Thread.sleep(300);
        noFx(() -> {
            ((DatePicker) r.lookup("#dataInicio")).setValue(LocalDate.now());
            ((DatePicker) r.lookup("#dataFim")).setValue(LocalDate.now());
            ((Button) r.lookup("#botaoGerar")).fire();
        });
        esperar(() -> fxr(() -> !r.lookup("#botaoGerar").isDisabled() && linhas(r) > 0), codigo + " gerado");
        Thread.sleep(400);
    }

    private static int linhas(Parent r) {
        return r.lookupAll(".table-view").stream().mapToInt(n -> ((TableView<?>) n).getItems().size()).max()
                .orElse(0);
    }

    private static void fecharJanela(Scene cena) throws Exception {
        Window w = cena.getWindow();
        Platform.runLater(w::hide);
        esperar(() -> !w.isShowing(), "fechar");
        Thread.sleep(300);
    }

    private static void executar(String sql) throws Exception {
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    private static void chamar(Object alvo, String nome, Class<?>[] tipos, Object... args) {
        try {
            Method m = alvo.getClass().getDeclaredMethod(nome, tipos);
            m.setAccessible(true);
            m.invoke(alvo, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** O controller da aba Notas fiscais, achado no tratador do botão (o FXML liga o onAction a um método dele). */
    private static Object controllerDaAba(Button botao) throws Exception {
        Object achado = procurar(fx(botao::getOnAction), 0);
        if (achado == null) {
            throw new IllegalStateException("controller da aba Notas fiscais não encontrado");
        }
        return achado;
    }

    private static Object procurar(Object objeto, int nivel) throws IllegalAccessException {
        if (objeto == null || nivel > 3) {
            return null;
        }
        if (objeto.getClass().getName().equals("br.com.varesync.controller.NotasFiscaisController")) {
            return objeto;
        }
        for (Class<?> c = objeto.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().isPrimitive() || java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                } catch (RuntimeException inacessivel) {
                    continue;
                }
                Object achado = procurar(f.get(objeto), nivel + 1);
                if (achado != null) {
                    return achado;
                }
            }
        }
        return null;
    }
}
