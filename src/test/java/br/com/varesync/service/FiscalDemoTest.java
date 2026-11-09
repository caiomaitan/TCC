package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.model.DadosPerfil;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.PerfilTributario;
import br.com.varesync.service.fiscal.ContextoFiscal;
import br.com.varesync.service.fiscal.Inconsistencia;
import br.com.varesync.service.fiscal.ItemFiscal;
import br.com.varesync.service.fiscal.RegrasFiscais;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Cadastro fiscal do gerador (F19.5), sem banco: cobre o catálogo inteiro e só os casos de propósito acusam. */
class FiscalDemoTest {

    @Test
    void cadaProdutoDoCatalogoTemOsSeusDadosFiscaisComFormatoValido() {
        Map<String, FiscalDemo.Dados> dados = FiscalDemo.produtos();
        assertThat(dados.keySet()).containsExactlyInAnyOrderElementsOf(
                CatalogoDemo.itens().stream().map(CatalogoDemo.ItemCatalogo::nome).toList());
        Set<String> perfis = FiscalDemo.perfis().stream().map(DadosPerfil::nome).collect(Collectors.toSet());
        assertThat(dados.values()).allSatisfy(d -> {
            ValidacaoFiscal.ncm(d.ncm());
            ValidacaoFiscal.cest(d.cest());
            assertThat(d.perfil() == null || perfis.contains(d.perfil())).isTrue();
        });
        assertThat(FiscalDemo.COM_INCONSISTENCIA).containsAll(FiscalDemo.SO_AVISO);
        assertThat(dados.keySet()).containsAll(FiscalDemo.COM_INCONSISTENCIA);
        assertThat(FiscalDemo.perfis()).allSatisfy(FiscalService::validar);
    }

    /** As regras sobre o catálogo, no Simples com UF SP, como o R30 os vê (sem tabelas importadas). */
    @Test
    void soOsProdutosDePropositoTemInconsistencia() {
        Map<String, PerfilTributario> perfis = new HashMap<>();
        long id = 1;
        for (DadosPerfil d : FiscalDemo.perfis()) {
            var g = FiscalService.validar(d);
            perfis.put(d.nome(), new PerfilTributario(id++, g.nome(), g.cfop(), g.cstIcms(), g.csosn(),
                    g.aliquotaIcms(), g.reducaoBaseIcms(), g.cstPis(), g.aliquotaPis(), g.cstCofins(),
                    g.aliquotaCofins(), g.codigoBeneficio(), g.cstIbsCbs(), g.cclassTrib(), true, 0));
        }
        Map<String, FiscalDemo.Dados> dados = FiscalDemo.produtos();
        Map<String, Set<String>> perfisDoNcm = new HashMap<>();
        dados.values().stream().filter(d -> d.ncm() != null && d.perfil() != null).forEach(d ->
                perfisDoNcm.computeIfAbsent(ValidacaoFiscal.ncm(d.ncm()), k -> new TreeSet<>()).add(d.perfil()));
        ContextoFiscal contexto = ContextoFiscal.da(new Configuracoes.Loja("Demo", "", "", "",
                br.com.varesync.model.RegimeTributario.SIMPLES, "SP", ""));
        Map<String, List<Inconsistencia>> achados = new HashMap<>();
        dados.forEach((nome, d) -> {
            String ncm = ValidacaoFiscal.ncm(d.ncm());
            List<String> outros = ncm == null || d.perfil() == null ? List.of()
                    : perfisDoNcm.get(ncm).stream().filter(p -> !Objects.equals(p, d.perfil())).toList();
            List<Inconsistencia> i = RegrasFiscais.verificar(new ItemFiscal(1, nome, null, ncm,
                    ValidacaoFiscal.cest(d.cest()), d.origem(), d.perfil() == null ? null : perfis.get(d.perfil()),
                    null, null, null, outros), contexto);
            if (!i.isEmpty()) {
                achados.put(nome, i);
            }
        });
        assertThat(achados.keySet()).containsExactlyInAnyOrderElementsOf(FiscalDemo.COM_INCONSISTENCIA);
        assertThat(achados.entrySet().stream().filter(e -> e.getValue().stream().anyMatch(Inconsistencia::erro))
                .map(Map.Entry::getKey).toList()).hasSize(6).doesNotContainAnyElementsOf(FiscalDemo.SO_AVISO);
    }
}
