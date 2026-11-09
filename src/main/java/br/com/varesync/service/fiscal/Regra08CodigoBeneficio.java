package br.com.varesync.service.fiscal;

import br.com.varesync.model.PerfilTributario;
import br.com.varesync.service.ValidacaoFiscal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Regra 8: código de benefício (cBenef) com o CST 00 (a SEFAZ recusa); fora do formato ou de outra UF que não a da
 * loja (só com a UF da loja informada; "SEM CBENEF" não tem UF); CST 20, 40 ou 41 sem código (aviso: várias UFs
 * exigem).
 */
public final class Regra08CodigoBeneficio implements RegraFiscal {

    private static final Set<String> PEDEM_CODIGO = Set.of("20", "40", "41");

    @Override
    public int numero() {
        return 8;
    }

    @Override
    public String titulo() {
        return "Código de benefício";
    }

    @Override
    public List<Inconsistencia> verificar(ItemFiscal item, ContextoFiscal contexto) {
        PerfilTributario p = item.perfil();
        if (p == null) {
            return List.of();
        }
        String codigo = p.codigoBeneficio() == null || p.codigoBeneficio().isBlank() ? null : p.codigoBeneficio();
        List<Inconsistencia> achados = new ArrayList<>();
        if (codigo != null && "00".equals(p.cstIcms())) {
            achados.add(erro("Código de benefício " + codigo + " com o CST 00: a SEFAZ recusa a combinação."));
        }
        if (codigo != null && !codigo.equals(ValidacaoFiscal.SEM_CBENEF)) {
            if (!ValidacaoFiscal.formatoBeneficio(codigo)) {
                achados.add(erro("Código de benefício \"" + codigo + "\" fora do formato: 8 caracteres começando "
                        + "pela UF (ex.: SP010360), ou \"" + ValidacaoFiscal.SEM_CBENEF + "\"."));
            } else if (!contexto.uf().isEmpty() && !codigo.startsWith(contexto.uf())) {
                achados.add(erro("Código de benefício " + codigo + " é da UF " + codigo.substring(0, 2)
                        + ", e a loja é de " + contexto.uf() + "."));
            }
        }
        if (codigo == null && p.cstIcms() != null && PEDEM_CODIGO.contains(p.cstIcms())) {
            achados.add(aviso("CST " + p.cstIcms() + " sem código de benefício: várias UFs o exigem (ou \""
                    + ValidacaoFiscal.SEM_CBENEF + "\")."));
        }
        return achados;
    }
}
