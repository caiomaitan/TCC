package br.com.varesync.model;

import br.com.varesync.util.CpfCnpj;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * DANFE NFC-e (F23.7), bobina de 80 mm, com o leiaute do "Manual de Especificações Técnicas do DANFE NFC-e e QR Code":
 * cabeçalho do emitente; "Documento Auxiliar da Nota Fiscal de Consumidor Eletrônica"; itens; totais e pagamentos;
 * área de mensagem fiscal (homologação); a consulta pela chave; o consumidor; a identificação da NFC-e com o protocolo
 * (em contingência, sem ele); o QR Code; os tributos aproximados (F22.5). Em contingência, "EMITIDA EM CONTINGÊNCIA" e
 * "Pendente de autorização", em destaque, abaixo do cabeçalho e abaixo da identificação; na via do estabelecimento,
 * "Via do Estabelecimento" ao lado da data.
 *
 * <p>É um {@link ComprovanteImpresso}: as mesmas linhas viram a tela, o PDF e a impressão, pelo mesmo caminho do
 * comprovante; a linha {@link LinhaComprovante.Estilo#QRCODE} vira a imagem.
 */
public record DanfeNfce(Emitente emitente, List<Item> itens, BigDecimal valorTotal, BigDecimal descontos,
                        BigDecimal valorPagar, List<Pagamento> pagamentos, BigDecimal troco, String chave,
                        String urlConsulta, String consumidorDocumento, int numero, int serie,
                        OffsetDateTime emitidaEm, String protocolo, OffsetDateTime autorizadaEm, boolean contingencia,
                        boolean viaEstabelecimento, String qrCode, Comprovante.TributosAproximados tributos,
                        long vendaId) implements ComprovanteImpresso {

    public static final String TITULO = "Documento Auxiliar da Nota Fiscal de Consumidor Eletrônica";
    public static final String HOMOLOGACAO = "EMITIDA EM AMBIENTE DE HOMOLOGAÇÃO – SEM VALOR FISCAL";
    public static final String CONTINGENCIA = "EMITIDA EM CONTINGÊNCIA";
    public static final String PENDENTE = "Pendente de autorização";
    public static final String VIA_ESTABELECIMENTO = "Via do Estabelecimento";
    public static final String NAO_IDENTIFICADO = "CONSUMIDOR NÃO IDENTIFICADO";
    private static final int COLUNAS = Comprovante.COLUNAS;
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    /** @param endereco logradouro, número, complemento, bairro, município/UF e CEP, numa linha só */
    public record Emitente(String razaoSocial, String cnpj, String ie, String endereco) {
    }

    /** @param codigo o código interno do produto; {@code valorTotal} = o valor bruto do item */
    public record Item(String codigo, String descricao, BigDecimal quantidade, String unidade,
                       BigDecimal valorUnitario, BigDecimal valorTotal) {
    }

    /** @param valor o valor pago na forma (no dinheiro, o entregue, com o troco) */
    public record Pagamento(String forma, BigDecimal valor) {
    }

    /** A mesma nota, na via do estabelecimento (em contingência, F23.6). */
    public DanfeNfce comoViaDoEstabelecimento() {
        return new DanfeNfce(emitente, itens, valorTotal, descontos, valorPagar, pagamentos, troco, chave, urlConsulta,
                consumidorDocumento, numero, serie, emitidaEm, protocolo, autorizadaEm, contingencia, true, qrCode,
                tributos, vendaId);
    }

    @Override
    public String titulo() {
        return "DANFE NFC-e nº " + numero + " (venda nº " + vendaId + ")"
                + (viaEstabelecimento ? " — " + VIA_ESTABELECIMENTO : "");
    }

    @Override
    public String nomeArquivo() {
        return "danfe-nfce-" + numero + (viaEstabelecimento ? "-via-estabelecimento" : "") + ".pdf";
    }

    @Override
    public Optional<String> qrCodeConteudo() {
        return Optional.of(qrCode);
    }

    @Override
    public List<LinhaComprovante> linhas() {
        List<LinhaComprovante> l = new ArrayList<>();
        // Divisão I: emitente
        centro(l, emitente.razaoSocial(), true);
        l.add(LinhaComprovante.centro("CNPJ: " + CpfCnpj.formatar(emitente.cnpj()) + "  IE: " + emitente.ie()));
        centro(l, emitente.endereco(), false);
        l.add(LinhaComprovante.separador());
        // O título em duas linhas: numa só, não cabe nas 48 colunas da bobina
        l.add(LinhaComprovante.centroNegrito("Documento Auxiliar da Nota Fiscal"));
        l.add(LinhaComprovante.centroNegrito("de Consumidor Eletrônica"));
        if (contingencia) {
            contingencia(l);
        }
        l.add(LinhaComprovante.separador());
        // Divisão II: itens
        l.add(LinhaComprovante.negrito("Código Descrição", ""));
        l.add(LinhaComprovante.negrito("    Qtde. Un x Vl. Unit.", "Vl. Total"));
        for (Item i : itens) {
            l.add(LinhaComprovante.texto(i.codigo() + " " + i.descricao()));
            l.add(LinhaComprovante.normal("    " + quantidade(i.quantidade()) + " " + i.unidade() + " x "
                    + Formatos.moeda(i.valorUnitario()), Formatos.moeda(i.valorTotal())));
        }
        l.add(LinhaComprovante.separador());
        // Divisão III: totais e pagamentos
        l.add(LinhaComprovante.normal("Qtde. total de itens", String.valueOf(itens.size())));
        l.add(LinhaComprovante.normal("Valor total R$", Formatos.numero(valorTotal, 2)));
        if (descontos.signum() > 0) {
            l.add(LinhaComprovante.normal("Descontos R$", "-" + Formatos.numero(descontos, 2)));
        }
        l.add(LinhaComprovante.negrito("Valor a pagar R$", Formatos.numero(valorPagar, 2)));
        l.add(LinhaComprovante.normal("FORMA DE PAGAMENTO", "VALOR PAGO R$"));
        for (Pagamento p : pagamentos) {
            l.add(LinhaComprovante.normal(p.forma(), Formatos.numero(p.valor(), 2)));
        }
        if (troco.signum() > 0) {
            l.add(LinhaComprovante.normal("Troco R$", Formatos.numero(troco, 2)));
        }
        l.add(LinhaComprovante.separador());
        // Área de mensagem fiscal: o texto da homologação em duas linhas (não cabe nas 48 colunas)
        l.add(LinhaComprovante.centroNegrito("EMITIDA EM AMBIENTE DE HOMOLOGAÇÃO –"));
        l.add(LinhaComprovante.centroNegrito("SEM VALOR FISCAL"));
        l.add(LinhaComprovante.separador());
        // Consulta pela chave
        centro(l, "Consulte pela Chave de Acesso em", true);
        centro(l, urlConsulta, false);
        chaveEmGrupos(chave).forEach(g -> l.add(LinhaComprovante.centro(g)));
        l.add(LinhaComprovante.separador());
        // Consumidor
        if (Textos.vazio(consumidorDocumento)) {
            l.add(LinhaComprovante.centroNegrito(NAO_IDENTIFICADO));
        } else {
            l.add(LinhaComprovante.centro("CONSUMIDOR - " + (consumidorDocumento.length() == 11 ? "CPF: " : "CNPJ: ")
                    + CpfCnpj.formatar(consumidorDocumento)));
        }
        l.add(LinhaComprovante.separador());
        // Identificação da NFC-e e protocolo
        l.add(LinhaComprovante.centroNegrito("NFC-e nº " + String.format("%09d", numero) + "  Série "
                + String.format("%03d", serie)));
        l.add(LinhaComprovante.centro(dataHora(emitidaEm) + (viaEstabelecimento ? " - " + VIA_ESTABELECIMENTO : "")));
        if (contingencia) {
            contingencia(l);
        } else {
            l.add(LinhaComprovante.centro("Protocolo de autorização: " + protocolo));
            l.add(LinhaComprovante.centro("Data de autorização: " + dataHora(autorizadaEm)));
        }
        // QR Code
        l.add(LinhaComprovante.qrCode(qrCode));
        // Tributos aproximados (F22.5)
        if (tributos != null) {
            l.add(LinhaComprovante.separador());
            Comprovante.quebrar(tributos.texto(), COLUNAS).forEach(t -> l.add(LinhaComprovante.texto(t)));
        }
        return l;
    }

    private static void contingencia(List<LinhaComprovante> l) {
        l.add(LinhaComprovante.centroNegrito(CONTINGENCIA));
        l.add(LinhaComprovante.centroNegrito(PENDENTE));
    }

    /** A chave em 11 grupos de 4 dígitos, em duas linhas (6 e 5 grupos) para caber em 48 colunas. */
    public static List<String> chaveEmGrupos(String chave) {
        List<String> grupos = new ArrayList<>();
        for (int i = 0; i < chave.length(); i += 4) {
            grupos.add(chave.substring(i, Math.min(chave.length(), i + 4)));
        }
        int meio = Math.min(6, grupos.size());
        List<String> linhas = new ArrayList<>();
        linhas.add(String.join(" ", grupos.subList(0, meio)));
        if (grupos.size() > meio) {
            linhas.add(String.join(" ", grupos.subList(meio, grupos.size())));
        }
        return linhas;
    }

    private static void centro(List<LinhaComprovante> l, String texto, boolean negrito) {
        if (Textos.vazio(texto)) {
            return;
        }
        for (String palavras : Comprovante.quebrar(texto, COLUNAS)) {
            // um endereço sem espaços maior que a linha vai em pedaços (na última "/" que cabe), para não ser cortado
            String resto = palavras;
            while (resto.length() > COLUNAS) {
                int corte = resto.lastIndexOf('/', COLUNAS - 1) + 1;
                if (corte <= 0) {
                    corte = COLUNAS;
                }
                l.add(linha(resto.substring(0, corte), negrito));
                resto = resto.substring(corte);
            }
            l.add(linha(resto, negrito));
        }
    }

    private static LinhaComprovante linha(String texto, boolean negrito) {
        return negrito ? LinhaComprovante.centroNegrito(texto) : LinhaComprovante.centro(texto);
    }

    private static String quantidade(BigDecimal q) {
        return Formatos.quantidade(q, q.stripTrailingZeros().scale() > 0);
    }

    private static String dataHora(OffsetDateTime quando) {
        return quando == null ? "—" : DATA_HORA.format(quando.atZoneSameInstant(ZoneId.systemDefault()));
    }
}
