package br.com.varesync.service.fiscal;

import br.com.varesync.model.PerfilTributario;
import br.com.varesync.model.RegimeTributario;
import java.math.BigDecimal;
import java.util.List;

/**
 * Montagem dos casos dos testes das regras: um perfil "certo" do Simples (5102, CSOSN 102, PIS e COFINS 49 a 0%) e um
 * produto ativo certo (NCM, CEST vazio, origem 0), mudados só no que cada caso quer.
 */
final class Fiscais {

    static final ContextoFiscal SIMPLES = new ContextoFiscal(RegimeTributario.SIMPLES, "SP");
    static final ContextoFiscal NORMAL = new ContextoFiscal(RegimeTributario.NORMAL, "SP");

    private Fiscais() {
    }

    static Perfil perfil() {
        return new Perfil();
    }

    static Item item() {
        return new Item();
    }

    static final class Perfil {
        String nome = "Simples tributado";
        String cfop = "5102";
        String cst;
        String csosn = "102";
        BigDecimal aliquotaIcms = BigDecimal.ZERO;
        BigDecimal reducao;
        String cstPis = "49";
        BigDecimal aliquotaPis = BigDecimal.ZERO;
        String cstCofins = "49";
        BigDecimal aliquotaCofins = BigDecimal.ZERO;
        String beneficio;
        String cstIbsCbs;
        String cclassTrib;

        Perfil nome(String v) {
            nome = v;
            return this;
        }

        Perfil cfop(String v) {
            cfop = v;
            return this;
        }

        /** Troca para CST do ICMS (regime normal). */
        Perfil cst(String v) {
            cst = v;
            csosn = null;
            return this;
        }

        Perfil csosn(String v) {
            csosn = v;
            cst = null;
            return this;
        }

        Perfil icms(String v) {
            aliquotaIcms = new BigDecimal(v);
            return this;
        }

        Perfil reducao(String v) {
            reducao = v == null ? null : new BigDecimal(v);
            return this;
        }

        Perfil pis(String cstV, String aliquota) {
            cstPis = cstV;
            aliquotaPis = new BigDecimal(aliquota);
            return this;
        }

        Perfil cofins(String cstV, String aliquota) {
            cstCofins = cstV;
            aliquotaCofins = new BigDecimal(aliquota);
            return this;
        }

        Perfil beneficio(String v) {
            beneficio = v;
            return this;
        }

        Perfil ibsCbs(String cstV, String cclass) {
            cstIbsCbs = cstV;
            cclassTrib = cclass;
            return this;
        }

        PerfilTributario montar() {
            return new PerfilTributario(1, nome, cfop, cst, csosn, aliquotaIcms, reducao, cstPis, aliquotaPis,
                    cstCofins, aliquotaCofins, beneficio, cstIbsCbs, cclassTrib, true, 1);
        }
    }

    static final class Item {
        String ncm = "22030000";
        String cest;
        Integer origem = 0;
        PerfilTributario perfil = new Perfil().montar();
        Boolean ncmNaTabela;
        Boolean cestNaTabela;
        Boolean corresponde;
        List<String> outros = List.of();

        Item ncm(String v) {
            ncm = v;
            return this;
        }

        Item cest(String v) {
            cest = v;
            return this;
        }

        Item origem(Integer v) {
            origem = v;
            return this;
        }

        Item perfil(Perfil v) {
            perfil = v == null ? null : v.montar();
            return this;
        }

        Item semPerfil() {
            perfil = null;
            return this;
        }

        Item tabelas(Boolean ncmExiste, Boolean cestExiste, Boolean cestDoNcm) {
            ncmNaTabela = ncmExiste;
            cestNaTabela = cestExiste;
            corresponde = cestDoNcm;
            return this;
        }

        Item outrosPerfis(String... nomes) {
            outros = List.of(nomes);
            return this;
        }

        ItemFiscal montar() {
            return new ItemFiscal(1, "Cerveja pilsen lata 350 ml", "Bebidas", ncm, cest, origem, perfil, ncmNaTabela,
                    cestNaTabela, corresponde, outros);
        }
    }
}
