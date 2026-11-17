package br.com.varesync.service.nfce;

import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SEFAZ falsa dos testes (F23.4): confere a assinatura e o XSD de cada nota que recebe e responde conforme o roteiro
 * do teste (autoriza, rejeita com um cStat, denega, não responde, fica paralisada, volta). Guarda a sua "base": as
 * notas autorizadas (para a consulta pela chave), os eventos e as inutilizações. Nunca vai à rede.
 */
public final class SefazFalsa implements Sefaz {

    /** O que a próxima autorização faz. */
    public enum Acao {
        AUTORIZA,
        /** não responde, e a nota não chegou (a consulta dirá 217) */
        SEM_RESPOSTA,
        /** não responde, mas a nota chegou e foi autorizada (a consulta dirá 100) */
        SEM_RESPOSTA_MAS_AUTORIZOU,
        /** responde duplicidade (204); a nota já estava autorizada */
        DUPLICIDADE
    }

    public record Envio(String chave, String tpEmis) {
    }

    private final Clock clock;
    private final ValidacaoXsd validacao;
    private final PublicKey chavePublica;
    private final Deque<Object> roteiro = new ConcurrentLinkedDeque<>();
    private final Deque<Object> roteiroEventos = new ConcurrentLinkedDeque<>();
    private final java.util.Set<String> canceladas = ConcurrentHashMap.newKeySet();
    private final AssinaturaNfce assinatura;
    private final Map<String, String> autorizadas = new ConcurrentHashMap<>();
    private final List<Envio> envios = Collections.synchronizedList(new ArrayList<>());
    private final List<String> eventos = Collections.synchronizedList(new ArrayList<>());
    private final List<String> inutilizacoes = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong protocolos = new AtomicLong(135_260_000_000_001L);
    private volatile boolean fora;
    private volatile String statusServico = "107";
    private volatile long demoraMs;

    public SefazFalsa(Clock clock, Path pastaSchemas) {
        this.clock = clock;
        this.validacao = new ValidacaoXsd(pastaSchemas);
        this.chavePublica = XmlNfceTeste.certificadoDeTeste().getPublicKey();
        this.assinatura = new AssinaturaNfce(validacao.pasta(), clock.getZone());
    }

    // ----- Roteiro -----

    /** As próximas autorizações: {@link Acao} ou um cStat de rejeição/denegação (ex.: "539", "302"). */
    public SefazFalsa depois(Object... acoes) {
        roteiro.addAll(List.of(acoes));
        return this;
    }

    /** Sem comunicação em tudo (cai a internet). */
    public void cair() {
        fora = true;
    }

    /** Volta a responder (status 107). */
    public void voltar() {
        fora = false;
        statusServico = "107";
    }

    /** Responde, mas o serviço está paralisado (108): conta como sem comunicação. */
    public void paralisar() {
        statusServico = "108";
    }

    /** Segura cada autorização por um tempo (para os testes de concorrência). */
    public void demorar(long ms) {
        demoraMs = ms;
    }

    public List<Envio> envios() {
        return List.copyOf(envios);
    }

    public List<String> eventos() {
        return List.copyOf(eventos);
    }

    public List<String> inutilizacoes() {
        return List.copyOf(inutilizacoes);
    }

    public boolean autorizada(String chave) {
        return autorizadas.containsKey(chave);
    }

    private void exigirComunicacao() {
        if (fora) {
            throw new SemComunicacaoException("SEFAZ falsa fora do ar");
        }
        if (!"107".equals(statusServico)) {
            throw new SemComunicacaoException("SEFAZ falsa paralisada");
        }
    }

    private String dataXml() {
        return agora().format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx"));
    }

    private OffsetDateTime agora() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    // ----- Serviços -----

    @Override
    public RespostaSefaz status(ContextoSefaz contexto) {
        if (fora) {
            throw new SemComunicacaoException("SEFAZ falsa fora do ar");
        }
        if (RespostaSefaz.PARALISADO.contains(statusServico)) {
            throw new SemComunicacaoException("SEFAZ falsa paralisada");
        }
        return new RespostaSefaz(statusServico, "Servico em Operacao", null, agora(), null);
    }

    @Override
    public RespostaSefaz autorizar(ContextoSefaz contexto, String nfeAssinada, String idLote) {
        exigirComunicacao();
        // Confere o que recebe: o XSD do lote e a assinatura com o certificado de teste; só homologação.
        List<String> erros = validacao.erros(AssinaturaNfce.loteCom(nfeAssinada, idLote), ValidacaoXsd.XSD_LOTE);
        if (!erros.isEmpty()) {
            throw new AssertionError("A SEFAZ falsa recebeu um XML fora do XSD: " + erros);
        }
        if (!XmlNfceTeste.assinaturaConfere(nfeAssinada, chavePublica)) {
            throw new AssertionError("A SEFAZ falsa recebeu uma nota com a assinatura que não confere");
        }
        if (!"2".equals(XmlNfceTeste.texto(nfeAssinada, "tpAmb"))) {
            throw new AssertionError("A SEFAZ falsa recebeu uma nota de produção");
        }
        String chave = XmlNfceTeste.documento(nfeAssinada).getElementsByTagNameNS("*", "infNFe").item(0)
                .getAttributes().getNamedItem("Id").getNodeValue().substring(3);
        envios.add(new Envio(chave, XmlNfceTeste.texto(nfeAssinada, "tpEmis")));
        if (demoraMs > 0) {
            try {
                Thread.sleep(demoraMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Object acao = roteiro.isEmpty() ? Acao.AUTORIZA : roteiro.poll();
        if (acao == Acao.SEM_RESPOSTA) {
            throw new SemComunicacaoException("SEFAZ falsa: tempo esgotado");
        }
        if (acao == Acao.SEM_RESPOSTA_MAS_AUTORIZOU) {
            autorizar(chave);
            throw new SemComunicacaoException("SEFAZ falsa: tempo esgotado (mas autorizou)");
        }
        if (acao == Acao.DUPLICIDADE) {
            autorizar(chave);
            return new RespostaSefaz("204", "Rejeicao: Duplicidade de NF-e", null, agora(), null);
        }
        if (acao instanceof String cstat) {
            String motivo = RespostaSefaz.DENEGADA.contains(cstat) ? "Uso Denegado: Irregularidade fiscal"
                    : "Rejeicao: Codigo de teste " + cstat;
            return new RespostaSefaz(cstat, motivo, null, agora(), null);
        }
        String protocolo = autorizar(chave);
        return new RespostaSefaz("100", "Autorizado o uso da NF-e", protocolo, agora(), protNFe(chave, protocolo));
    }

    private String autorizar(String chave) {
        return autorizadas.computeIfAbsent(chave, c -> String.valueOf(protocolos.getAndIncrement()));
    }

    private String protNFe(String chave, String protocolo) {
        return "<protNFe versao=\"4.00\"><infProt><tpAmb>2</tpAmb><verAplic>SEFAZ-FALSA</verAplic><chNFe>" + chave
                + "</chNFe><dhRecbto>" + dataXml() + "</dhRecbto><nProt>" + protocolo
                + "</nProt><digVal>AAAA</digVal><cStat>100</cStat><xMotivo>Autorizado o uso da NF-e</xMotivo>"
                + "</infProt></protNFe>";
    }

    @Override
    public RespostaSefaz consultar(ContextoSefaz contexto, String chave) {
        exigirComunicacao();
        String protocolo = autorizadas.get(chave);
        if (protocolo == null) {
            return new RespostaSefaz("217", "Rejeicao: NF-e nao consta na base de dados da SEFAZ", null, agora(),
                    null);
        }
        return new RespostaSefaz("100", "Autorizado o uso da NF-e", protocolo, agora(), protNFe(chave, protocolo));
    }

    /**
     * Evento 110111: monta e assina o pedido como a {@link SefazHomologacao} ({@link PedidosNfce}), confere o XSD e a
     * assinatura e responde conforme o roteiro dos eventos (registra, recusa com um cStat, não responde, 573 quando já
     * foi registrado). Registrado, devolve o procEventoNFe.
     */
    @Override
    public RespostaSefaz cancelar(ContextoSefaz contexto, String chave, String protocolo, String justificativa,
                                  OffsetDateTime quando) {
        exigirComunicacao();
        String lote = PedidosNfce.cancelamento(assinatura.configuracao(contexto.uf(), contexto.certificado()),
                contexto.cnpj(), chave, protocolo, justificativa, quando);
        conferirPedido(lote, "envEventoCancNFe_v1.00.xsd", "infEvento");
        if (!protocolo.equals(autorizadas.get(chave))) {
            return retEvento(chave, "110111", "494", "Rejeicao: Chave de Acesso inexistente", lote);
        }
        Object acao = roteiroEventos.isEmpty() ? null : roteiroEventos.poll();
        if (acao == Acao.SEM_RESPOSTA) {
            throw new SemComunicacaoException("SEFAZ falsa: tempo esgotado no evento");
        }
        if (acao == Acao.SEM_RESPOSTA_MAS_AUTORIZOU) {
            canceladas.add(chave);
            eventos.add("110111 " + chave);
            throw new SemComunicacaoException("SEFAZ falsa: tempo esgotado no evento (mas registrou)");
        }
        if (acao instanceof String cstat) {
            return retEvento(chave, "110111", cstat, "Rejeicao: Codigo de teste " + cstat, lote);
        }
        if (!canceladas.add(chave)) {
            return retEvento(chave, "110111", "573", "Rejeicao: Duplicidade de evento", lote);
        }
        eventos.add("110111 " + chave);
        return retEvento(chave, "110111", "135", "Evento registrado e vinculado a NF-e", lote);
    }

    @Override
    public RespostaSefaz cancelarPorSubstituicao(ContextoSefaz contexto, String chave, String protocolo,
                                                 String chaveSubstituta, String justificativa,
                                                 OffsetDateTime quando) {
        exigirComunicacao();
        String lote = PedidosNfce.cancelamentoPorSubstituicao(assinatura.configuracao(contexto.uf(),
                contexto.certificado()), contexto.cnpj(), chave, protocolo, chaveSubstituta, justificativa, quando);
        conferirPedido(lote, "envEventoCancSubst_v1.00.xsd", "infEvento");
        if (!protocolo.equals(autorizadas.get(chave)) || !autorizadas.containsKey(chaveSubstituta)) {
            return retEvento(chave, "110112", "494", "Rejeicao: Chave de Acesso inexistente", lote);
        }
        eventos.add("110112 " + chave + " por " + chaveSubstituta);
        canceladas.add(chave);
        return retEvento(chave, "110112", "135", "Evento registrado e vinculado a NF-e", lote);
    }

    @Override
    public RespostaSefaz inutilizar(ContextoSefaz contexto, int serie, int numeroInicial, int numeroFinal,
                                   String justificativa, OffsetDateTime quando) {
        exigirComunicacao();
        var config = assinatura.configuracao(contexto.uf(), contexto.certificado());
        String assinado = PedidosNfce.inutilizacaoAssinada(config, PedidosNfce.inutilizacao(config, contexto.cnpj(),
                serie, numeroInicial, numeroFinal, justificativa, quando));
        conferirPedido(assinado, "inutNFe_v4.00.xsd", "infInut");
        inutilizacoes.add(serie + ":" + numeroInicial + "-" + numeroFinal);
        String protocolo = String.valueOf(protocolos.getAndIncrement());
        String retorno = "<retInutNFe xmlns=\"http://www.portalfiscal.inf.br/nfe\" versao=\"4.00\"><infInut>"
                + "<tpAmb>2</tpAmb><verAplic>SEFAZ-FALSA</verAplic><cStat>102</cStat>"
                + "<xMotivo>Inutilizacao de numero homologado</xMotivo><cUF>" + XmlNfceTeste.texto(assinado, "cUF")
                + "</cUF><ano>" + XmlNfceTeste.texto(assinado, "ano") + "</ano><CNPJ>" + contexto.cnpj()
                + "</CNPJ><mod>65</mod><serie>" + serie + "</serie><nNFIni>" + numeroInicial + "</nNFIni><nNFFin>"
                + numeroFinal + "</nNFFin><dhRecbto>" + dataXml() + "</dhRecbto><nProt>" + protocolo
                + "</nProt></infInut></retInutNFe>";
        return new RespostaSefaz("102", "Inutilizacao de numero homologado", protocolo, agora(),
                PedidosNfce.procInutilizacao(assinado, retorno));
    }

    /** O próximo evento 110111: {@link Acao#SEM_RESPOSTA}, {@link Acao#SEM_RESPOSTA_MAS_AUTORIZOU} ou um cStat. */
    public SefazFalsa depoisNoEvento(Object... acoes) {
        roteiroEventos.addAll(List.of(acoes));
        return this;
    }

    private void conferirPedido(String xml, String xsd, String elementoAssinado) {
        List<String> erros = validacao.erros(xml, xsd);
        if (!erros.isEmpty()) {
            throw new AssertionError("A SEFAZ falsa recebeu um pedido fora do XSD " + xsd + ": " + erros);
        }
        if (!XmlNfceTeste.assinaturaConfere(xml, chavePublica, elementoAssinado)) {
            throw new AssertionError("A SEFAZ falsa recebeu um pedido com a assinatura que não confere");
        }
        if (!"2".equals(XmlNfceTeste.texto(xml, "tpAmb"))) {
            throw new AssertionError("A SEFAZ falsa recebeu um pedido de produção");
        }
    }

    /** A resposta de um evento; registrado (135 ou 573), com o procEventoNFe. */
    private RespostaSefaz retEvento(String chave, String tipo, String cstat, String motivo, String lote) {
        boolean registrado = "135".equals(cstat) || "573".equals(cstat);
        String protocolo = "135".equals(cstat) ? String.valueOf(protocolos.getAndIncrement()) : null;
        String retorno = "<retEnvEvento xmlns=\"http://www.portalfiscal.inf.br/nfe\" versao=\"1.00\"><idLote>1</idLote>"
                + "<tpAmb>2</tpAmb><verAplic>SEFAZ-FALSA</verAplic><cOrgao>" + chave.substring(0, 2) + "</cOrgao>"
                + "<cStat>128</cStat><xMotivo>Lote de Evento Processado</xMotivo><retEvento versao=\"1.00\">"
                + "<infEvento><tpAmb>2</tpAmb><verAplic>SEFAZ-FALSA</verAplic><cOrgao>" + chave.substring(0, 2)
                + "</cOrgao><cStat>" + cstat + "</cStat><xMotivo>" + motivo + "</xMotivo><chNFe>" + chave
                + "</chNFe><tpEvento>" + tipo + "</tpEvento><nSeqEvento>1</nSeqEvento><dhRegEvento>" + dataXml()
                + "</dhRegEvento>" + (protocolo == null ? "" : "<nProt>" + protocolo + "</nProt>")
                + "</infEvento></retEvento></retEnvEvento>";
        return new RespostaSefaz(cstat, motivo, protocolo, agora(),
                registrado ? PedidosNfce.procEvento(lote, retorno) : retorno);
    }
}
