package br.com.varesync.service.nfce;

import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.RegimeTributario;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.ValidacaoFiscal;
import br.com.varesync.util.CodigoUf;
import br.com.varesync.util.CpfCnpj;
import br.com.varesync.util.Textos;
import java.util.ArrayList;
import java.util.List;

/**
 * O emitente da NFC-e (F23.2), conferido: CNPJ válido (numérico; o alfanumérico fica fora desta versão), IE com dígitos
 * (não vale ISENTO), UF, endereço completo e código do município da UF. O nome da loja vira o nome fantasia; o CRT sai do
 * regime (SIMPLES = 1, NORMAL = 3).
 */
public record EmitenteNfce(String cnpj, String razaoSocial, String nomeFantasia, String logradouro, String numero,
                           String complemento, String bairro, String codigoMunicipio, String municipio, String uf,
                           int codigoUf, String cep, String telefone, String ie, RegimeTributario regime) {

    /** CRT: 1 Simples Nacional, 3 regime normal. */
    public String crt() {
        return regime == RegimeTributario.NORMAL ? "3" : "1";
    }

    /** O que falta para emitir a NFC-e, na ordem da tela; vazio = completo. */
    public static List<String> pendencias(Configuracoes c) {
        Configuracoes.Loja loja = c.loja();
        Configuracoes.Emitente e = c.emitente();
        List<String> falta = new ArrayList<>();
        String documento = loja.documento() == null ? "" : loja.documento();
        if (documento.length() != 14 || !Textos.soDigitos(documento) || !CpfCnpj.cnpjValido(documento)) {
            falta.add("o CNPJ da loja (o CPF e o CNPJ com letras não servem para a NFC-e)");
        }
        if (loja.ie().isEmpty() || ValidacaoFiscal.ISENTO.equals(loja.ie())) {
            falta.add("a inscrição estadual (não vale ISENTO)");
        }
        if (CodigoUf.de(loja.uf()).isEmpty()) {
            falta.add("a UF");
        }
        if (e.razaoSocial().length() < 2) {
            falta.add("a razão social");
        }
        if (e.logradouro().length() < 2) {
            falta.add("o logradouro");
        }
        if (e.numero().isEmpty()) {
            falta.add("o número do endereço");
        }
        if (e.bairro().length() < 2) {
            falta.add("o bairro");
        }
        if (e.cep().length() != 8) {
            falta.add("o CEP");
        }
        if (e.municipio().length() < 2) {
            falta.add("o município");
        }
        boolean municipioDaUf = e.codigoMunicipio().length() == 7 && CodigoUf.de(loja.uf())
                .map(uf -> e.codigoMunicipio().startsWith(String.valueOf(uf))).orElse(false);
        if (!municipioDaUf) {
            falta.add("o código IBGE do município (da UF da loja)");
        }
        return falta;
    }

    /** O emitente conferido, ou a recusa com tudo o que falta. */
    public static EmitenteNfce de(Configuracoes c) {
        List<String> falta = pendencias(c);
        if (!falta.isEmpty()) {
            throw new NegocioException("Para emitir a NFC-e, complete em Configurações › Loja: " + String.join(", ",
                    falta) + ".");
        }
        Configuracoes.Loja loja = c.loja();
        Configuracoes.Emitente e = c.emitente();
        String telefone = Textos.apenasDigitos(loja.telefone());
        return new EmitenteNfce(loja.documento(), e.razaoSocial(), loja.nome(), e.logradouro(), e.numero(),
                e.complemento(), e.bairro(), e.codigoMunicipio(), e.municipio(), loja.uf(),
                CodigoUf.de(loja.uf()).orElseThrow(), e.cep(),
                telefone.length() >= 6 && telefone.length() <= 14 ? telefone : "", loja.ie(), loja.regime());
    }
}
