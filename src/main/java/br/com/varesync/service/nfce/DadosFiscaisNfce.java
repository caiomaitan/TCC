package br.com.varesync.service.nfce;

import br.com.varesync.model.CadastroFiscalProduto;
import br.com.varesync.model.PerfilTributario;
import br.com.varesync.model.RegimeTributario;
import br.com.varesync.service.fiscal.Regra14SituacaoIcmsNfce;
import br.com.varesync.service.fiscal.Regra17CstIbsCbs;
import br.com.varesync.service.fiscal.RegraFiscal;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * O que falta no cadastro fiscal do produto para ele ir na NFC-e (F23.6), conferido no PDV ao registrar o item com a
 * NFC-e ligada: NCM, origem, perfil ativo com a situação do ICMS aceita pela regra 14 do R30, CEST quando há ST e, no
 * regime NORMAL, o CST do IBS e da CBS e o cClassTrib, aceitos pela regra 17. No NORMAL, o perfil sem o CST do IBS e
 * da CBS também é recusado (no R30 é só o aviso da regra 12): sem esse grupo, a homologação recusa a nota de quem é do
 * regime normal desde 01/07/2026. Sem banco.
 */
public final class DadosFiscaisNfce {

    private DadosFiscaisNfce() {
    }

    /** O que falta, em linguagem do lojista; vazio = pode ir na NFC-e. */
    public static List<String> faltas(CadastroFiscalProduto c, RegimeTributario regime) {
        List<String> faltas = new ArrayList<>();
        if (vazio(c.ncm())) {
            faltas.add("o NCM");
        }
        if (c.origem() == null) {
            faltas.add("a origem da mercadoria");
        }
        PerfilTributario p = c.perfil();
        if (p == null) {
            faltas.add("o perfil tributário");
            return faltas;
        }
        if (!p.ativo()) {
            faltas.add("um perfil tributário ativo (o perfil \"" + p.nome() + "\" está inativo)");
            return faltas;
        }
        if (regime == RegimeTributario.SIMPLES) {
            if (vazio(p.csosn())) {
                faltas.add("o CSOSN no perfil (a loja é do Simples Nacional)");
            } else if (!Regra14SituacaoIcmsNfce.CSOSN_ACEITOS.contains(p.csosn())) {
                faltas.add("uma situação do ICMS aceita na NFC-e (o CSOSN " + p.csosn() + " não é aceito: só 102, "
                        + "103, 300, 400 e 500)");
            }
        } else {
            if (vazio(p.cstIcms())) {
                faltas.add("o CST do ICMS no perfil (a loja é do regime normal)");
            } else if (!Regra14SituacaoIcmsNfce.CST_ACEITOS.contains(p.cstIcms())) {
                faltas.add("uma situação do ICMS aceita na NFC-e (o CST " + p.cstIcms() + " não é aceito: só 00, 20, "
                        + "40, 41 e 60)");
            }
        }
        if (RegraFiscal.comSt(p) && vazio(c.cest())) {
            faltas.add("o CEST (o produto tem substituição tributária)");
        }
        if (regime == RegimeTributario.NORMAL) {
            if (vazio(p.cstIbsCbs())) {
                faltas.add("o CST do IBS e da CBS no perfil");
            } else if (!Regra17CstIbsCbs.CST_ACEITOS.contains(p.cstIbsCbs())) {
                faltas.add("um CST do IBS e da CBS aceito na NFC-e (o " + p.cstIbsCbs() + " não é aceito: só 000, 200 "
                        + "e 410)");
            } else if ("200".equals(p.cstIbsCbs()) && !positivo(p.reducaoAliquotaIbsCbs())) {
                faltas.add("a redução de alíquota do IBS e da CBS no perfil (CST 200)");
            }
            if (vazio(p.cclassTrib())) {
                faltas.add("o cClassTrib no perfil");
            }
        }
        return faltas;
    }

    /** A recusa do PDV, ou nulo quando nada falta. */
    public static String recusa(CadastroFiscalProduto c, RegimeTributario regime) {
        List<String> faltas = faltas(c, regime);
        if (faltas.isEmpty()) {
            return null;
        }
        return "O produto \"" + c.produto() + "\" não pode ser vendido com a NFC-e ligada: falta " + juntar(faltas)
                + ". Complete os dados fiscais no cadastro do produto.";
    }

    static String juntar(List<String> partes) {
        if (partes.size() == 1) {
            return partes.get(0);
        }
        return String.join(", ", partes.subList(0, partes.size() - 1)) + " e " + partes.get(partes.size() - 1);
    }

    private static boolean vazio(String s) {
        return s == null || s.isBlank();
    }

    private static boolean positivo(BigDecimal v) {
        return v != null && v.signum() > 0;
    }
}
