package br.com.varesync.service.fiscal;

import br.com.varesync.model.PerfilTributario;
import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Regra 11, para o PIS e a COFINS, cada um com a sua mensagem: CST 04, 06, 07, 08 ou 09 com alíquota maior que zero;
 * CST 01 ou 02 com alíquota zero; no Simples Nacional, alíquota maior que zero (não repetida quando o CST já acusou a
 * alíquota). E CST do PIS diferente do CST da COFINS (aviso).
 */
public final class Regra11PisCofins implements RegraFiscal {

    /** 04 monofásico, 06 alíquota zero, 07 isenta, 08 sem incidência, 09 suspensão. */
    static final Set<String> SEM_ALIQUOTA = Set.of("04", "06", "07", "08", "09");
    static final Set<String> TRIBUTADOS = Set.of("01", "02");

    @Override
    public int numero() {
        return 11;
    }

    @Override
    public String titulo() {
        return "PIS e COFINS";
    }

    @Override
    public List<Inconsistencia> verificar(ItemFiscal item, ContextoFiscal contexto) {
        PerfilTributario p = item.perfil();
        if (p == null) {
            return List.of();
        }
        List<Inconsistencia> achados = new ArrayList<>();
        conferir(achados, "PIS", p.cstPis(), p.aliquotaPis(), contexto);
        conferir(achados, "COFINS", p.cstCofins(), p.aliquotaCofins(), contexto);
        if (!Objects.equals(p.cstPis(), p.cstCofins())) {
            achados.add(aviso("CST do PIS (" + p.cstPis() + ") diferente do CST da COFINS (" + p.cstCofins()
                    + "): em geral os dois são iguais."));
        }
        return achados;
    }

    private void conferir(List<Inconsistencia> achados, String imposto, String cst, BigDecimal aliquota,
                          ContextoFiscal contexto) {
        boolean positiva = RegraFiscal.positivo(aliquota);
        String artigo = imposto.equals("PIS") ? "do " : "da ";
        if (SEM_ALIQUOTA.contains(cst) && positiva) {
            achados.add(erro("CST " + cst + " " + artigo + imposto + " não tem alíquota, mas ela é "
                    + Formatos.percentual(aliquota) + "."));
            return;
        }
        if (TRIBUTADOS.contains(cst) && !positiva) {
            achados.add(erro("CST " + cst + " " + artigo + imposto + " é tributado, mas a alíquota é zero."));
        }
        if (contexto.simples() && positiva) {
            achados.add(erro("Alíquota " + artigo + imposto + " de " + Formatos.percentual(aliquota)
                    + " no Simples Nacional: o " + imposto + " vem no DAS, a alíquota no documento é zero."));
        }
    }
}
