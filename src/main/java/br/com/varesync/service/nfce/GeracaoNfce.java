package br.com.varesync.service.nfce;

import br.com.swconsultoria.nfe.dom.ConfiguracoesNfe;
import br.com.swconsultoria.nfe.schemas.TNFe;
import br.com.varesync.model.TipoEmissaoNfce;
import java.util.List;

/**
 * A NFC-e pronta para gravar (F23.3), sem banco: monta ({@link MontagemNfce}), põe o QR Code versão 3 e a URL de
 * consulta em infNFeSupl ({@link QrCodeNfce}), assina com o certificado do terminal ({@link AssinaturaNfce}) e valida
 * o lote pelo XSD ({@link ValidacaoXsd}). Quem chama decide: válida, grava o XML; com erros, a NFC-e fica REJEITADA, sem
 * ir à SEFAZ.
 */
public final class GeracaoNfce {

    /**
     * @param xml         só o elemento {@code NFe} assinado (o que fica gravado)
     * @param qrCode      o texto do QR Code
     * @param urlConsulta a URL da consulta pela chave
     * @param erros       os erros do XSD (vazio = válido)
     */
    public record Resultado(String xml, String qrCode, String urlConsulta, List<String> erros) {

        public Resultado {
            erros = List.copyOf(erros);
        }

        public boolean valido() {
            return erros.isEmpty();
        }

        /** O motivo gravado na REJEITADA: os primeiros erros do XSD. */
        public String motivo() {
            return "O XML da NFC-e não passou na validação pelo XSD: " + String.join("; ",
                    erros.subList(0, Math.min(3, erros.size()))) + (erros.size() > 3 ? " (e mais " + (erros.size() - 3)
                    + ")" : "") + ". Confira o cadastro fiscal dos produtos e os dados da loja.";
        }
    }

    private final MontagemNfce montagem;
    private final AssinaturaNfce assinatura;
    private final ValidacaoXsd validacao;

    public GeracaoNfce(MontagemNfce montagem, AssinaturaNfce assinatura, ValidacaoXsd validacao) {
        this.montagem = montagem;
        this.assinatura = assinatura;
        this.validacao = validacao;
    }

    /**
     * @param urlQrCode   da configuração; vazio = o da biblioteca para a UF
     * @param urlConsulta da configuração; vazio = o da biblioteca para a UF
     */
    public Resultado gerar(NotaNfce nota, CertificadoA1 certificado, String urlQrCode, String urlConsulta) {
        ConfiguracoesNfe config = assinatura.configuracao(nota.emitente().uf(), certificado);
        String qrUrl = urlQrCode == null || urlQrCode.isBlank() ? assinatura.urlQrCode(config) : urlQrCode;
        String consulta = urlConsulta == null || urlConsulta.isBlank() ? assinatura.urlConsulta(config) : urlConsulta;
        TNFe nfe = montagem.montar(nota);
        NotaNfce.Identificacao id = nota.identificacao();
        String qrCode = id.tipoEmissao() == TipoEmissaoNfce.CONTINGENCIA_OFFLINE
                ? QrCodeNfce.contingencia(qrUrl, id.chave(), montagem.dataHora(id.emitidaEm()), nota.total(),
                        nota.consumidorDocumento(), certificado)
                : QrCodeNfce.normal(qrUrl, id.chave());
        TNFe.InfNFeSupl supl = new TNFe.InfNFeSupl();
        supl.setQrCode(qrCode);
        supl.setUrlChave(consulta);
        nfe.setInfNFeSupl(supl);
        String lote = assinatura.assinarLote(AssinaturaNfce.lote(nfe, String.valueOf(id.numero())), config);
        List<String> erros = validacao.erros(lote, ValidacaoXsd.XSD_LOTE);
        return new Resultado(AssinaturaNfce.nfeDoLote(lote), qrCode, consulta, erros);
    }
}
