package br.com.varesync.service.nfce;

import br.com.swconsultoria.nfe.schemas.ObjectFactory;
import br.com.swconsultoria.nfe.schemas.TCIBSNFe;
import br.com.swconsultoria.nfe.schemas.TEnderEmi;
import br.com.swconsultoria.nfe.schemas.TIBSCBSMonoTot;
import br.com.swconsultoria.nfe.schemas.TInfRespTec;
import br.com.swconsultoria.nfe.schemas.TNFe;
import br.com.swconsultoria.nfe.schemas.TNFe.InfNFe;
import br.com.swconsultoria.nfe.schemas.TNFe.InfNFe.Det;
import br.com.swconsultoria.nfe.schemas.TNFe.InfNFe.Det.Imposto;
import br.com.swconsultoria.nfe.schemas.TRed;
import br.com.swconsultoria.nfe.schemas.TTribNFe;
import br.com.swconsultoria.nfe.schemas.TUfEmi;
import br.com.varesync.model.RegimeTributario;
import br.com.varesync.model.TipoEmissaoNfce;
import br.com.varesync.model.TributoItem;
import br.com.varesync.service.NegocioException;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Gtin;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * Montagem da NFC-e (F23.3), sem banco: a partir da venda, dos tributos gravados na F22 e do emitente, preenche as
 * classes do leiaute da biblioteca Java_NFe ({@link TNFe}, PL 010g, com o grupo IBSCBS). Assinatura, QR Code e
 * validação ficam fora: {@link AssinaturaNfce}, {@link QrCodeNfce} e {@link ValidacaoXsd}.
 *
 * <p>Recusa com {@link NegocioException} o que a NFC-e desta versão não aceita: item sem os tributos gravados, situação
 * do ICMS fora das regras 14 do R30, CST do PIS ou da COFINS sem grupo e, no regime normal, o IBS e a CBS sem CST ou
 * fora de 000, 200 e 410 (regra 17).
 */
public final class MontagemNfce {

    /** Exigência da SEFAZ para o 1º item em homologação; o DANFE mostra a descrição de verdade. */
    public static final String DESCRICAO_HOMOLOGACAO = "NOTA FISCAL EMITIDA EM AMBIENTE DE HOMOLOGACAO - SEM VALOR FISCAL";
    public static final String SEM_GTIN = "SEM GTIN";
    public static final String AMBIENTE_HOMOLOGACAO = "2";
    public static final String VERSAO_LEIAUTE = "4.00";

    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final Set<String> TPAG_CARTAO = Set.of("03", "04", "17");
    private static final Set<String> PIS_COFINS_TRIBUTADO = Set.of("01", "02");
    private static final Set<String> PIS_COFINS_NAO_TRIBUTADO = Set.of("04", "05", "06", "07", "08", "09");
    private static final BigDecimal CEM = BigDecimal.valueOf(100);

    private final ZoneId fuso;
    private final ObjectFactory fabrica = new ObjectFactory();

    public MontagemNfce(ZoneId fuso) {
        this.fuso = fuso;
    }

    /** dhEmi e dhCont: AAAA-MM-DDThh:mm:ss com o fuso (ex.: 2026-11-14T10:30:00-03:00). */
    public String dataHora(OffsetDateTime instante) {
        return instante.atZoneSameInstant(fuso).truncatedTo(ChronoUnit.SECONDS).format(DATA_HORA);
    }

    public TNFe montar(NotaNfce nota) {
        if (nota.itens().isEmpty()) {
            throw new NegocioException("A venda não tem itens para a NFC-e.");
        }
        NotaNfce.Identificacao id = nota.identificacao();
        EmitenteNfce emitente = nota.emitente();
        InfNFe inf = new InfNFe();
        inf.setVersao(VERSAO_LEIAUTE);
        inf.setId("NFe" + id.chave());
        inf.setIde(ide(nota));
        inf.setEmit(emit(emitente));
        if (nota.consumidorDocumento() != null && !nota.consumidorDocumento().isBlank()) {
            inf.setDest(dest(nota.consumidorDocumento()));
        }
        Totais totais = new Totais();
        for (int i = 0; i < nota.itens().size(); i++) {
            inf.getDet().add(det(i + 1, nota.itens().get(i), emitente.regime(), totais));
        }
        if (totais.valorProdutos.subtract(totais.desconto).compareTo(nota.total()) != 0) {
            throw new IllegalStateException("Os itens não somam o total da venda");
        }
        inf.setTotal(total(totais, nota.total(), emitente.regime()));
        InfNFe.Transp transp = new InfNFe.Transp();
        transp.setModFrete("9");
        inf.setTransp(transp);
        inf.setPag(pag(nota));
        if (nota.respTecnico() != null && nota.respTecnico().informado()) {
            TInfRespTec resp = new TInfRespTec();
            resp.setCNPJ(nota.respTecnico().cnpj());
            resp.setXContato(TextoNfe.limpar(nota.respTecnico().contato(), 60));
            resp.setEmail(nota.respTecnico().email());
            resp.setFone(nota.respTecnico().fone());
            inf.setInfRespTec(resp);
        }
        TNFe nfe = new TNFe();
        nfe.setInfNFe(inf);
        return nfe;
    }

    // ----- Identificação, emitente e destinatário -----

    private InfNFe.Ide ide(NotaNfce nota) {
        NotaNfce.Identificacao id = nota.identificacao();
        EmitenteNfce emitente = nota.emitente();
        InfNFe.Ide ide = new InfNFe.Ide();
        ide.setCUF(String.valueOf(emitente.codigoUf()));
        ide.setCNF(id.codigoNumerico());
        ide.setNatOp("VENDA");
        ide.setMod(ChaveAcesso.MODELO_NFCE);
        ide.setSerie(String.valueOf(id.serie()));
        ide.setNNF(String.valueOf(id.numero()));
        ide.setDhEmi(dataHora(id.emitidaEm()));
        ide.setTpNF("1");
        ide.setIdDest("1");
        ide.setCMunFG(emitente.codigoMunicipio());
        ide.setTpImp("4");
        ide.setTpEmis(String.valueOf(id.tipoEmissao().codigo()));
        ide.setCDV(id.chave().substring(43));
        ide.setTpAmb(AMBIENTE_HOMOLOGACAO);
        ide.setFinNFe("1");
        ide.setIndFinal("1");
        ide.setIndPres("1");
        ide.setIndIntermed("0");
        ide.setProcEmi("0");
        ide.setVerProc(TextoNfe.limpar(nota.versaoSistema(), 20));
        if (id.tipoEmissao() == TipoEmissaoNfce.CONTINGENCIA_OFFLINE) {
            ide.setDhCont(dataHora(id.contingenciaEm()));
            ide.setXJust(TextoNfe.limpar(id.justificativa(), 256));
        }
        return ide;
    }

    private static InfNFe.Emit emit(EmitenteNfce e) {
        InfNFe.Emit emit = new InfNFe.Emit();
        emit.setCNPJ(e.cnpj());
        emit.setXNome(TextoNfe.limpar(e.razaoSocial(), 60));
        String fantasia = TextoNfe.limpar(e.nomeFantasia(), 60);
        if (!fantasia.isEmpty()) {
            emit.setXFant(fantasia);
        }
        TEnderEmi ender = new TEnderEmi();
        ender.setXLgr(TextoNfe.limpar(e.logradouro(), 60));
        ender.setNro(TextoNfe.limpar(e.numero(), 60));
        String complemento = TextoNfe.limpar(e.complemento(), 60);
        if (!complemento.isEmpty()) {
            ender.setXCpl(complemento);
        }
        ender.setXBairro(TextoNfe.limpar(e.bairro(), 60));
        ender.setCMun(e.codigoMunicipio());
        ender.setXMun(TextoNfe.limpar(e.municipio(), 60));
        ender.setUF(TUfEmi.valueOf(e.uf()));
        ender.setCEP(e.cep());
        ender.setCPais("1058");
        ender.setXPais("BRASIL");
        if (!e.telefone().isEmpty()) {
            ender.setFone(e.telefone());
        }
        emit.setEnderEmit(ender);
        emit.setIE(e.ie());
        emit.setCRT(e.crt());
        return emit;
    }

    /** Só o CPF ou o CNPJ, sem nome (F23.3); indIEDest 9 = não contribuinte. */
    private static InfNFe.Dest dest(String documento) {
        InfNFe.Dest dest = new InfNFe.Dest();
        if (documento.length() == 11) {
            dest.setCPF(documento);
        } else {
            dest.setCNPJ(documento);
        }
        dest.setIndIEDest("9");
        return dest;
    }

    // ----- Itens -----

    private Det det(int numeroItem, NotaNfce.Item item, RegimeTributario regime, Totais totais) {
        TributoItem t = item.tributo();
        if (t == null) {
            throw new NegocioException("O item " + item.sequencia() + " (" + item.descricao() + ") foi vendido sem os "
                    + "dados fiscais do produto (NCM, origem e perfil tributário): a NFC-e não pode ser emitida.");
        }
        Det det = new Det();
        det.setNItem(String.valueOf(numeroItem));
        det.setProd(prod(numeroItem, item, t));
        Imposto imposto = new Imposto();
        BigDecimal aproximados = t.tributosAproximados();
        if (aproximados != null) {
            imposto.getContent().add(fabrica.createTNFeInfNFeDetImpostoVTotTrib(d2(aproximados)));
            totais.tributosAproximados = totais.tributosAproximados.add(aproximados);
        }
        imposto.getContent().add(fabrica.createTNFeInfNFeDetImpostoICMS(icms(item, t, regime, totais)));
        imposto.getContent().add(fabrica.createTNFeInfNFeDetImpostoPIS(pis(item, t, totais)));
        imposto.getContent().add(fabrica.createTNFeInfNFeDetImpostoCOFINS(cofins(item, t, totais)));
        if (regime == RegimeTributario.NORMAL) {
            imposto.getContent().add(fabrica.createTNFeInfNFeDetImpostoIBSCBS(ibsCbs(item, t, totais)));
        }
        det.setImposto(imposto);
        totais.valorProdutos = totais.valorProdutos.add(item.valorBruto());
        totais.desconto = totais.desconto.add(item.desconto());
        return det;
    }

    private static Det.Prod prod(int numeroItem, NotaNfce.Item item, TributoItem t) {
        Det.Prod prod = new Det.Prod();
        prod.setCProd(String.valueOf(item.codigo()));
        String gtin = gtin(item.gtin());
        prod.setCEAN(gtin);
        prod.setXProd(numeroItem == 1 ? DESCRICAO_HOMOLOGACAO : TextoNfe.limpar(item.descricao(), 120));
        prod.setNCM(t.ncm());
        if (t.cest() != null) {
            prod.setCEST(t.cest());
        }
        if (t.codigoBeneficio() != null) {
            prod.setCBenef(t.codigoBeneficio());
        }
        prod.setCFOP(t.cfop());
        String unidade = TextoNfe.limpar(item.unidade(), 6);
        String quantidade = item.quantidade().setScale(4, RoundingMode.HALF_UP).toPlainString();
        String unitario = valorUnitario(item.valorBruto(), item.quantidade()).toPlainString();
        prod.setUCom(unidade);
        prod.setQCom(quantidade);
        prod.setVUnCom(unitario);
        prod.setVProd(d2(item.valorBruto()));
        prod.setCEANTrib(gtin);
        prod.setUTrib(unidade);
        prod.setQTrib(quantidade);
        prod.setVUnTrib(unitario);
        if (item.desconto().signum() > 0) {
            prod.setVDesc(d2(item.desconto()));
        }
        prod.setIndTot("1");
        return prod;
    }

    /**
     * vUnCom = valor bruto ÷ quantidade, com 10 casas: com o preço da embalagem (F20) ou a etiqueta de balança, o
     * preço de 2 casas × a quantidade não bate com o valor do item, e a SEFAZ recusa.
     */
    public static BigDecimal valorUnitario(BigDecimal valorBruto, BigDecimal quantidade) {
        return valorBruto.divide(quantidade, 10, RoundingMode.HALF_UP);
    }

    /** O código de barras principal, se for um GTIN válido que não começa por 2 (uso interno, como a balança). */
    static String gtin(String codigo) {
        if (codigo == null || codigo.isBlank() || codigo.startsWith("2") || !Gtin.valido(codigo)) {
            return SEM_GTIN;
        }
        return codigo;
    }

    // ----- ICMS -----

    private static Imposto.ICMS icms(NotaNfce.Item item, TributoItem t, RegimeTributario regime, Totais totais) {
        Imposto.ICMS icms = new Imposto.ICMS();
        String origem = String.valueOf(t.origem());
        if (t.cstIcms() != null) {
            if (regime != RegimeTributario.NORMAL) {
                throw situacaoRecusada(item, "o CST " + t.cstIcms() + " é do regime normal, e a loja é do Simples");
            }
            switch (t.cstIcms()) {
                case "00" -> {
                    Imposto.ICMS.ICMS00 g = new Imposto.ICMS.ICMS00();
                    g.setOrig(origem);
                    g.setCST("00");
                    g.setModBC("3");
                    g.setVBC(d2(t.baseIcms()));
                    g.setPICMS(d2(t.aliquotaIcms()));
                    g.setVICMS(d2(t.valorIcms()));
                    if (t.aliquotaFcp().signum() > 0) {
                        g.setPFCP(d2(t.aliquotaFcp()));
                        g.setVFCP(d2(t.valorFcp()));
                    }
                    icms.setICMS00(g);
                    totais.somarIcms(t);
                }
                case "20" -> {
                    Imposto.ICMS.ICMS20 g = new Imposto.ICMS.ICMS20();
                    g.setOrig(origem);
                    g.setCST("20");
                    g.setModBC("3");
                    g.setPRedBC(d2(t.reducaoBaseIcms()));
                    g.setVBC(d2(t.baseIcms()));
                    g.setPICMS(d2(t.aliquotaIcms()));
                    g.setVICMS(d2(t.valorIcms()));
                    if (t.aliquotaFcp().signum() > 0) {
                        g.setVBCFCP(d2(t.baseIcms()));
                        g.setPFCP(d2(t.aliquotaFcp()));
                        g.setVFCP(d2(t.valorFcp()));
                    }
                    icms.setICMS20(g);
                    totais.somarIcms(t);
                }
                case "40", "41" -> {
                    Imposto.ICMS.ICMS40 g = new Imposto.ICMS.ICMS40();
                    g.setOrig(origem);
                    g.setCST(t.cstIcms());
                    icms.setICMS40(g);
                }
                case "60" -> {
                    Imposto.ICMS.ICMS60 g = new Imposto.ICMS.ICMS60();
                    g.setOrig(origem);
                    g.setCST("60");
                    if (temStRetido(t)) {
                        g.setVBCSTRet(d2(t.baseStRetido()));
                        g.setPST(d2(t.aliquotaSt()));
                        g.setVICMSSubstituto(d2(t.icmsSubstituto()));
                        g.setVICMSSTRet(d2(t.icmsStRetido()));
                    }
                    icms.setICMS60(g);
                }
                default -> throw situacaoRecusada(item, "o CST " + t.cstIcms() + " do ICMS não é aceito nesta versão "
                        + "(só 00, 20, 40, 41 e 60)");
            }
            return icms;
        }
        if (regime == RegimeTributario.NORMAL) {
            throw situacaoRecusada(item, "o CSOSN " + t.csosn() + " é do Simples, e a loja é do regime normal");
        }
        switch (t.csosn()) {
            case "102", "103", "300", "400" -> {
                Imposto.ICMS.ICMSSN102 g = new Imposto.ICMS.ICMSSN102();
                g.setOrig(origem);
                g.setCSOSN(t.csosn());
                icms.setICMSSN102(g);
            }
            case "500" -> {
                Imposto.ICMS.ICMSSN500 g = new Imposto.ICMS.ICMSSN500();
                g.setOrig(origem);
                g.setCSOSN("500");
                if (temStRetido(t)) {
                    g.setVBCSTRet(d2(t.baseStRetido()));
                    g.setPST(d2(t.aliquotaSt()));
                    g.setVICMSSubstituto(d2(t.icmsSubstituto()));
                    g.setVICMSSTRet(d2(t.icmsStRetido()));
                }
                icms.setICMSSN500(g);
            }
            default -> throw situacaoRecusada(item, "o CSOSN " + t.csosn() + " não é aceito nesta versão (só 102, "
                    + "103, 300, 400 e 500)");
        }
        return icms;
    }

    /** Os quatro campos do ST retido saem juntos (são um grupo opcional no leiaute) ou nenhum (F22.4). */
    private static boolean temStRetido(TributoItem t) {
        return t.baseStRetido() != null && t.aliquotaSt() != null && t.icmsSubstituto() != null
                && t.icmsStRetido() != null;
    }

    private static NegocioException situacaoRecusada(NotaNfce.Item item, String motivo) {
        return new NegocioException("O item " + item.sequencia() + " (" + item.descricao() + ") não pode ir na NFC-e: "
                + motivo + ".");
    }

    // ----- PIS e COFINS -----

    private static Imposto.PIS pis(NotaNfce.Item item, TributoItem t, Totais totais) {
        Imposto.PIS pis = new Imposto.PIS();
        String cst = t.cstPis();
        if (PIS_COFINS_TRIBUTADO.contains(cst)) {
            Imposto.PIS.PISAliq g = new Imposto.PIS.PISAliq();
            g.setCST(cst);
            g.setVBC(d2(t.basePis()));
            g.setPPIS(d2(t.aliquotaPis()));
            g.setVPIS(d2(t.valorPis()));
            pis.setPISAliq(g);
        } else if (PIS_COFINS_NAO_TRIBUTADO.contains(cst)) {
            Imposto.PIS.PISNT g = new Imposto.PIS.PISNT();
            g.setCST(cst);
            pis.setPISNT(g);
        } else if (outrasOperacoes(cst)) {
            Imposto.PIS.PISOutr g = new Imposto.PIS.PISOutr();
            g.setCST(cst);
            g.setVBC(d2(t.basePis()));
            g.setPPIS(d2(t.aliquotaPis()));
            g.setVPIS(d2(t.valorPis()));
            pis.setPISOutr(g);
        } else {
            throw situacaoRecusada(item, "o CST " + cst + " do PIS não é aceito nesta versão");
        }
        totais.pis = totais.pis.add(t.valorPis());
        return pis;
    }

    private static Imposto.COFINS cofins(NotaNfce.Item item, TributoItem t, Totais totais) {
        Imposto.COFINS cofins = new Imposto.COFINS();
        String cst = t.cstCofins();
        if (PIS_COFINS_TRIBUTADO.contains(cst)) {
            Imposto.COFINS.COFINSAliq g = new Imposto.COFINS.COFINSAliq();
            g.setCST(cst);
            g.setVBC(d2(t.baseCofins()));
            g.setPCOFINS(d2(t.aliquotaCofins()));
            g.setVCOFINS(d2(t.valorCofins()));
            cofins.setCOFINSAliq(g);
        } else if (PIS_COFINS_NAO_TRIBUTADO.contains(cst)) {
            Imposto.COFINS.COFINSNT g = new Imposto.COFINS.COFINSNT();
            g.setCST(cst);
            cofins.setCOFINSNT(g);
        } else if (outrasOperacoes(cst)) {
            Imposto.COFINS.COFINSOutr g = new Imposto.COFINS.COFINSOutr();
            g.setCST(cst);
            g.setVBC(d2(t.baseCofins()));
            g.setPCOFINS(d2(t.aliquotaCofins()));
            g.setVCOFINS(d2(t.valorCofins()));
            cofins.setCOFINSOutr(g);
        } else {
            throw situacaoRecusada(item, "o CST " + cst + " da COFINS não é aceito nesta versão");
        }
        totais.cofins = totais.cofins.add(t.valorCofins());
        return cofins;
    }

    /** CST 49 a 99 (outras operações; o 49 e o 99 são os do Simples), no grupo PISOutr e COFINSOutr. */
    private static boolean outrasOperacoes(String cst) {
        try {
            int n = Integer.parseInt(cst);
            return n >= 49 && n <= 99;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ----- IBS e CBS (só no regime normal) -----

    private static TTribNFe ibsCbs(NotaNfce.Item item, TributoItem t, Totais totais) {
        String cst = t.cstIbsCbs();
        if (cst == null || t.cclassTrib() == null) {
            throw situacaoRecusada(item, "no regime normal, o perfil precisa do CST do IBS e da CBS e do cClassTrib "
                    + "(sem esse grupo, a homologação recusa a nota)");
        }
        if (!Set.of("000", "200", "410").contains(cst)) {
            throw situacaoRecusada(item, "o CST " + cst + " do IBS e da CBS não é aceito nesta versão (só 000, 200 e "
                    + "410)");
        }
        TTribNFe trib = new TTribNFe();
        trib.setCST(cst);
        trib.setCClassTrib(t.cclassTrib());
        if (!"410".equals(cst)) {
            if (t.baseIbsCbs() == null) {
                throw situacaoRecusada(item, "o IBS e a CBS não foram calculados na venda");
            }
            BigDecimal reducao = "200".equals(cst) ? t.reducaoAliquotaIbsCbs() : null;
            TCIBSNFe g = new TCIBSNFe();
            g.setVBC(d2(t.baseIbsCbs()));
            TCIBSNFe.GIBSUF uf = new TCIBSNFe.GIBSUF();
            uf.setPIBSUF(d4(t.aliquotaIbsUf()));
            uf.setGRed(reducao(t.aliquotaIbsUf(), reducao));
            uf.setVIBSUF(d2(t.valorIbsUf()));
            g.setGIBSUF(uf);
            TCIBSNFe.GIBSMun mun = new TCIBSNFe.GIBSMun();
            mun.setPIBSMun(d4(t.aliquotaIbsMun()));
            mun.setGRed(reducao(t.aliquotaIbsMun(), reducao));
            mun.setVIBSMun(d2(t.valorIbsMun()));
            g.setGIBSMun(mun);
            g.setVIBS(d2(t.valorIbsUf().add(t.valorIbsMun())));
            TCIBSNFe.GCBS cbs = new TCIBSNFe.GCBS();
            cbs.setPCBS(d4(t.aliquotaCbs()));
            cbs.setGRed(reducao(t.aliquotaCbs(), reducao));
            cbs.setVCBS(d2(t.valorCbs()));
            g.setGCBS(cbs);
            trib.setGIBSCBS(g);
            totais.somarIbsCbs(t);
        }
        totais.temIbsCbs = true;
        return trib;
    }

    /** gRed: o percentual de redução e a alíquota efetiva (4 casas), só no CST 200 com redução. */
    private static TRed reducao(BigDecimal aliquota, BigDecimal reducao) {
        if (reducao == null) {
            return null;
        }
        TRed red = new TRed();
        red.setPRedAliq(d4(reducao));
        red.setPAliqEfet(d4(aliquota.multiply(CEM.subtract(reducao)).divide(CEM, 4, RoundingMode.HALF_UP)));
        return red;
    }

    // ----- Totais e pagamentos -----

    private static final class Totais {
        BigDecimal valorProdutos = BigDecimal.ZERO;
        BigDecimal desconto = BigDecimal.ZERO;
        BigDecimal baseIcms = BigDecimal.ZERO;
        BigDecimal icms = BigDecimal.ZERO;
        BigDecimal fcp = BigDecimal.ZERO;
        BigDecimal pis = BigDecimal.ZERO;
        BigDecimal cofins = BigDecimal.ZERO;
        BigDecimal tributosAproximados = BigDecimal.ZERO;
        BigDecimal baseIbsCbs = BigDecimal.ZERO;
        BigDecimal ibsUf = BigDecimal.ZERO;
        BigDecimal ibsMun = BigDecimal.ZERO;
        BigDecimal cbs = BigDecimal.ZERO;
        boolean temIbsCbs;

        void somarIcms(TributoItem t) {
            baseIcms = baseIcms.add(t.baseIcms());
            icms = icms.add(t.valorIcms());
            fcp = fcp.add(t.valorFcp());
        }

        void somarIbsCbs(TributoItem t) {
            baseIbsCbs = baseIbsCbs.add(t.baseIbsCbs());
            ibsUf = ibsUf.add(t.valorIbsUf());
            ibsMun = ibsMun.add(t.valorIbsMun());
            cbs = cbs.add(t.valorCbs());
        }
    }

    /** A soma dos itens; vNF = o total da venda (= valor dos produtos − descontos). */
    private static InfNFe.Total total(Totais t, BigDecimal totalVenda, RegimeTributario regime) {
        InfNFe.Total.ICMSTot tot = new InfNFe.Total.ICMSTot();
        tot.setVBC(d2(t.baseIcms));
        tot.setVICMS(d2(t.icms));
        tot.setVICMSDeson(d2(BigDecimal.ZERO));
        tot.setVFCP(d2(t.fcp));
        tot.setVBCST(d2(BigDecimal.ZERO));
        tot.setVST(d2(BigDecimal.ZERO));
        tot.setVFCPST(d2(BigDecimal.ZERO));
        tot.setVFCPSTRet(d2(BigDecimal.ZERO));
        tot.setVProd(d2(t.valorProdutos));
        tot.setVFrete(d2(BigDecimal.ZERO));
        tot.setVSeg(d2(BigDecimal.ZERO));
        tot.setVDesc(d2(t.desconto));
        tot.setVII(d2(BigDecimal.ZERO));
        tot.setVIPI(d2(BigDecimal.ZERO));
        tot.setVIPIDevol(d2(BigDecimal.ZERO));
        tot.setVPIS(d2(t.pis));
        tot.setVCOFINS(d2(t.cofins));
        tot.setVOutro(d2(BigDecimal.ZERO));
        tot.setVNF(d2(totalVenda));
        if (t.tributosAproximados.signum() > 0) {
            tot.setVTotTrib(d2(t.tributosAproximados));
        }
        InfNFe.Total total = new InfNFe.Total();
        total.setICMSTot(tot);
        if (regime == RegimeTributario.NORMAL && t.temIbsCbs) {
            TIBSCBSMonoTot ibs = new TIBSCBSMonoTot();
            ibs.setVBCIBSCBS(d2(t.baseIbsCbs));
            TIBSCBSMonoTot.GIBS gibs = new TIBSCBSMonoTot.GIBS();
            TIBSCBSMonoTot.GIBS.GIBSUF uf = new TIBSCBSMonoTot.GIBS.GIBSUF();
            uf.setVDif(d2(BigDecimal.ZERO));
            uf.setVDevTrib(d2(BigDecimal.ZERO));
            uf.setVIBSUF(d2(t.ibsUf));
            gibs.setGIBSUF(uf);
            TIBSCBSMonoTot.GIBS.GIBSMun mun = new TIBSCBSMonoTot.GIBS.GIBSMun();
            mun.setVDif(d2(BigDecimal.ZERO));
            mun.setVDevTrib(d2(BigDecimal.ZERO));
            mun.setVIBSMun(d2(t.ibsMun));
            gibs.setGIBSMun(mun);
            gibs.setVIBS(d2(t.ibsUf.add(t.ibsMun)));
            gibs.setVCredPres(d2(BigDecimal.ZERO));
            gibs.setVCredPresCondSus(d2(BigDecimal.ZERO));
            ibs.setGIBS(gibs);
            TIBSCBSMonoTot.GCBS gcbs = new TIBSCBSMonoTot.GCBS();
            gcbs.setVDif(d2(BigDecimal.ZERO));
            gcbs.setVDevTrib(d2(BigDecimal.ZERO));
            gcbs.setVCBS(d2(t.cbs));
            gcbs.setVCredPres(d2(BigDecimal.ZERO));
            gcbs.setVCredPresCondSus(d2(BigDecimal.ZERO));
            ibs.setGCBS(gcbs);
            total.setIBSCBSTot(ibs);
        }
        return total;
    }

    /**
     * Um detPag por pagamento, com o tPag da forma; no 99, a descrição em xPag; no 03, 04 e 17, o grupo card com
     * tpIntegra 2 (sem integração). No dinheiro, o valor entregue (o abatido + o troco, no primeiro pagamento em
     * dinheiro) e o troco em vTroco. Venda de total zero: tPag 90 (sem pagamento).
     */
    private static InfNFe.Pag pag(NotaNfce nota) {
        InfNFe.Pag pag = new InfNFe.Pag();
        BigDecimal troco = nota.troco() == null ? BigDecimal.ZERO : nota.troco();
        boolean trocoLancado = troco.signum() == 0;
        for (NotaNfce.Pagamento p : nota.pagamentos()) {
            InfNFe.Pag.DetPag det = new InfNFe.Pag.DetPag();
            det.setTPag(p.tpag());
            if ("99".equals(p.tpag())) {
                det.setXPag(TextoNfe.limpar(p.descricao(), 60));
            }
            BigDecimal valor = p.valor();
            if (!trocoLancado && "01".equals(p.tpag())) {
                valor = valor.add(troco);
                trocoLancado = true;
            }
            det.setVPag(d2(valor));
            if (TPAG_CARTAO.contains(p.tpag())) {
                InfNFe.Pag.DetPag.Card card = new InfNFe.Pag.DetPag.Card();
                card.setTpIntegra("2");
                det.setCard(card);
            }
            pag.getDetPag().add(det);
        }
        if (pag.getDetPag().isEmpty()) {
            InfNFe.Pag.DetPag det = new InfNFe.Pag.DetPag();
            det.setTPag("90");
            det.setVPag(d2(BigDecimal.ZERO));
            pag.getDetPag().add(det);
        }
        if (!trocoLancado) {
            throw new IllegalStateException("Troco sem pagamento em dinheiro");
        }
        if (troco.signum() > 0) {
            pag.setVTroco(d2(troco));
        }
        return pag;
    }

    private static String d2(BigDecimal valor) {
        return valor.setScale(Dinheiro.ESCALA_DINHEIRO, RoundingMode.HALF_UP).toPlainString();
    }

    private static String d4(BigDecimal valor) {
        return valor.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }
}
