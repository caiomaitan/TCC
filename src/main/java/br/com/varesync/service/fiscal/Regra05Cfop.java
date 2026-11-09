package br.com.varesync.service.fiscal;

import br.com.varesync.model.PerfilTributario;
import java.util.ArrayList;
import java.util.List;

/**
 * Regra 5: CFOP que não é de saída interna (não começa com 5; a NFC-e só aceita operação interna); CFOP 5405 sem
 * situação de ST (CST 60 ou CSOSN 500); CFOP 5101 ou 5102 com CST 60 ou CSOSN 500.
 */
public final class Regra05Cfop implements RegraFiscal {

    @Override
    public int numero() {
        return 5;
    }

    @Override
    public String titulo() {
        return "CFOP";
    }

    @Override
    public List<Inconsistencia> verificar(ItemFiscal item, ContextoFiscal contexto) {
        PerfilTributario p = item.perfil();
        if (p == null) {
            return List.of();
        }
        String cfop = p.cfop() == null ? "" : p.cfop();
        List<Inconsistencia> achados = new ArrayList<>();
        if (!cfop.startsWith("5")) {
            achados.add(erro("CFOP " + cfop + " não é de saída interna: na venda ao consumidor ele começa com 5 "
                    + "(a NFC-e só aceita operação interna)."));
        }
        boolean st = RegraFiscal.comSt(p);
        if (cfop.equals("5405") && !st) {
            achados.add(erro("CFOP 5405 (mercadoria com ST) com " + p.situacaoIcms()
                    + ", que não indica ST: use o CST 60 ou o CSOSN 500."));
        }
        if ((cfop.equals("5101") || cfop.equals("5102")) && st) {
            achados.add(erro("CFOP " + cfop + " com " + p.situacaoIcms()
                    + " (mercadoria com ST): na revenda com ST, o CFOP é 5405."));
        }
        return achados;
    }
}
