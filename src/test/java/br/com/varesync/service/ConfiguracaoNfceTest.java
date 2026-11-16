package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.RegimeTributario;
import br.com.varesync.service.nfce.EmitenteNfce;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Configurações da NFC-e (F23.2, seção 0.4): o endereço do emitente, a seção NFC-e e o que falta para emitir. */
class ConfiguracaoNfceTest {

    private static final Configuracoes.Emitente ENDERECO = new Configuracoes.Emitente("Empresa Teste Ltda",
            "Rua das Flores", "100", "", "Centro", "14010-100", "Ribeirão Preto", "3543402");

    private static Configuracoes completa() {
        return Configuracoes.PADRAO.comLoja(new Configuracoes.Loja("Mercado Bom Preço", "12345678000195", "",
                        "1633334444", RegimeTributario.SIMPLES, "SP", "123456789012"))
                .comEmitente(ConfiguracaoService.emitente(ENDERECO, "SP"));
    }

    @Test
    void cepECodigoDoMunicipioDaUf() {
        Configuracoes.Emitente e = ConfiguracaoService.emitente(ENDERECO, "SP");
        assertThat(e.cep()).isEqualTo("14010100");
        assertThat(e.codigoMunicipio()).isEqualTo("3543402");
        assertThatThrownBy(() -> ConfiguracaoService.emitente(new Configuracoes.Emitente("", "", "", "", "", "1401",
                "", ""), "SP")).isInstanceOf(NegocioException.class).hasMessageContaining("CEP tem 8 dígitos");
        assertThatThrownBy(() -> ConfiguracaoService.emitente(new Configuracoes.Emitente("", "", "", "", "", "", "",
                "3304557"), "SP")).isInstanceOf(NegocioException.class).hasMessageContaining("começam por 35");
        assertThatThrownBy(() -> ConfiguracaoService.emitente(new Configuracoes.Emitente("", "", "", "", "", "", "",
                "3543402"), "")).isInstanceOf(NegocioException.class).hasMessageContaining("Escolha a UF");
        assertThat(ConfiguracaoService.emitente(Configuracoes.Emitente.VAZIO, "")).isEqualTo(
                Configuracoes.Emitente.VAZIO);
    }

    @Test
    void faixasDaSecaoNfce() {
        Configuracoes.Nfce p = Configuracoes.Nfce.PADRAO;
        assertThat(ConfiguracaoService.nfce(p)).usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class).isEqualTo(p);
        assertThatThrownBy(() -> ConfiguracaoService.nfce(new Configuracoes.Nfce(false, 890, 30, 10, 5,
                p.valorIdentificacao(), p.valorMaximo(), true, 30, "", "", null)))
                .hasMessageContaining("série da NFC-e deve estar entre 0 e 889");
        assertThatThrownBy(() -> ConfiguracaoService.nfce(new Configuracoes.Nfce(false, 1, 31, 10, 5,
                p.valorIdentificacao(), p.valorMaximo(), true, 30, "", "", null)))
                .hasMessageContaining("prazo de cancelamento deve estar entre 1 e 30");
        assertThatThrownBy(() -> ConfiguracaoService.nfce(new Configuracoes.Nfce(false, 1, 30, 10, 5,
                new BigDecimal("300000"), p.valorMaximo(), true, 30, "", "", null)))
                .hasMessageContaining("não pode passar do valor que exige NF-e");
        assertThatThrownBy(() -> ConfiguracaoService.nfce(new Configuracoes.Nfce(false, 1, 30, 10, 5,
                p.valorIdentificacao(), p.valorMaximo(), true, 30, "ftp://x", "", null)))
                .hasMessageContaining("http:// ou https://");
    }

    @Test
    void responsavelTecnicoTodoVazioOuTodoPreenchido() {
        assertThat(ConfiguracaoService.respTecnico(new Configuracoes.RespTecnico("", " ", "", "")))
                .isEqualTo(Configuracoes.RespTecnico.VAZIO);
        Configuracoes.RespTecnico ok = ConfiguracaoService.respTecnico(new Configuracoes.RespTecnico(
                "11.222.333/0001-81", "Fulano de Tal", "suporte@exemplo.com.br", "(16) 99999-8888"));
        assertThat(ok).isEqualTo(new Configuracoes.RespTecnico("11222333000181", "Fulano de Tal",
                "suporte@exemplo.com.br", "16999998888"));
        assertThatThrownBy(() -> ConfiguracaoService.respTecnico(new Configuracoes.RespTecnico("11222333000181", "",
                "", ""))).hasMessageContaining("contato");
        assertThatThrownBy(() -> ConfiguracaoService.respTecnico(new Configuracoes.RespTecnico("11222333000180",
                "Fulano", "a@b.com", "16999998888"))).hasMessageContaining("CNPJ do responsável técnico");
    }

    @Test
    void oQueFaltaParaEmitir() {
        assertThat(EmitenteNfce.pendencias(Configuracoes.PADRAO)).hasSize(10);
        assertThat(EmitenteNfce.pendencias(completa())).isEmpty();
        EmitenteNfce e = EmitenteNfce.de(completa());
        assertThat(e.razaoSocial()).isEqualTo("Empresa Teste Ltda");
        assertThat(e.nomeFantasia()).isEqualTo("Mercado Bom Preço");
        assertThat(e.codigoUf()).isEqualTo(35);
        assertThat(e.crt()).isEqualTo("1");
        Configuracoes isento = completa().comLoja(new Configuracoes.Loja("Loja", "12345678000195", "", "",
                RegimeTributario.NORMAL, "SP", "ISENTO"));
        assertThat(EmitenteNfce.pendencias(isento)).containsExactly("a inscrição estadual (não vale ISENTO)");
        Configuracoes cpf = completa().comLoja(new Configuracoes.Loja("Loja", "52998224725", "", "",
                RegimeTributario.SIMPLES, "SP", "123"));
        assertThatThrownBy(() -> EmitenteNfce.de(cpf)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("o CNPJ da loja");
    }

    @Test
    void chavesNovasVoltamDoMapa() {
        Configuracoes c = completa().comNfce(new Configuracoes.Nfce(true, 7, 20, 15, 3, new BigDecimal("5000"),
                new BigDecimal("150000"), false, 45, "https://a.gov.br/qr", "https://a.gov.br/c",
                new Configuracoes.RespTecnico("11222333000181", "Fulano", "a@b.com.br", "16999998888")));
        Map<String, String> mapa = c.paraMapa();
        assertThat(mapa).containsEntry("nfce.valor_identificacao", "5000").containsEntry("empresa.cep", "14010100");
        assertThat(Configuracoes.de(mapa).nfce()).isEqualTo(c.nfce());
        assertThat(Configuracoes.de(mapa).emitente()).isEqualTo(c.emitente());
        assertThat(Configuracoes.de(Map.of()).nfce()).isEqualTo(Configuracoes.Nfce.PADRAO);
    }
}
