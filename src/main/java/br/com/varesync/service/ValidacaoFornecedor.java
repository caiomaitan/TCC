package br.com.varesync.service;

import br.com.varesync.dao.FornecedorDAO;
import br.com.varesync.model.DadosFornecedor;
import br.com.varesync.util.CpfCnpj;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Normaliza e valida o cadastro de fornecedor (6.10). Razão social obrigatória; o resto é opcional, mas, se informado,
 * tem de estar certo: CPF/CNPJ com DV (A.2, A.3), telefone com DDD, e-mail e UF. Gravado sem máscara.
 */
final class ValidacaoFornecedor {

    static final int TAMANHO_RAZAO = 120;
    static final int TAMANHO_EMAIL = 120;
    static final int TAMANHO_CONTATO = 60;
    static final int TAMANHO_CIDADE = 60;

    static final Set<String> UFS = Set.of("AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA", "MT", "MS",
            "MG", "PA", "PB", "PR", "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO");

    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final Pattern MASCARA_TELEFONE = Pattern.compile("[\\s().\\-]");

    private ValidacaoFornecedor() {
    }

    static FornecedorDAO.Gravacao validar(DadosFornecedor dados) {
        String razao = Textos.normalizarEspacos(dados.razaoSocial());
        if (razao.isEmpty()) {
            throw new NegocioException("Informe a razão social do fornecedor.");
        }
        limite(razao, TAMANHO_RAZAO, "A razão social");
        String fantasia = opcional(dados.nomeFantasia());
        limite(fantasia, TAMANHO_RAZAO, "O nome fantasia");
        return new FornecedorDAO.Gravacao(razao, fantasia, documento(dados.cpfCnpj()), telefone(dados.telefone()),
                email(dados.email()), limite(opcional(dados.contato()), TAMANHO_CONTATO, "O contato"),
                limite(opcional(dados.cidade()), TAMANHO_CIDADE, "A cidade"), uf(dados.uf()),
                Textos.vazio(dados.observacao()) ? null : dados.observacao().strip());
    }

    /** CPF (11 dígitos) ou CNPJ (14 posições, numérico ou alfanumérico), sem máscara e em maiúsculas. */
    static String documento(String digitado) {
        if (Textos.vazio(digitado)) {
            return null;
        }
        String documento = CpfCnpj.normalizar(digitado);
        CpfCnpj.validar(documento).ifPresent(erro -> {
            throw new NegocioException(erro);
        });
        return documento;
    }

    /** Só dígitos, com DDD: 10 (fixo) ou 11 (celular). */
    static String telefone(String digitado) {
        if (Textos.vazio(digitado)) {
            return null;
        }
        String digitos = MASCARA_TELEFONE.matcher(digitado).replaceAll("");
        if (!Textos.soDigitos(digitos) || (digitos.length() != 10 && digitos.length() != 11)) {
            throw new NegocioException("Telefone inválido: informe o DDD e o número, com 10 ou 11 dígitos "
                    + "(ex.: (16) 3333-4444).");
        }
        return digitos;
    }

    static String email(String digitado) {
        String email = opcional(digitado);
        if (email == null) {
            return null;
        }
        limite(email, TAMANHO_EMAIL, "O e-mail");
        if (!EMAIL.matcher(email).matches()) {
            throw new NegocioException("E-mail inválido (ex.: compras@fornecedor.com.br).");
        }
        return email.toLowerCase(Formatos.PT_BR);
    }

    static String uf(String digitada) {
        String uf = opcional(digitada);
        if (uf == null) {
            return null;
        }
        uf = uf.toUpperCase(Formatos.PT_BR);
        if (!UFS.contains(uf)) {
            throw new NegocioException("UF inválida: use a sigla do estado (ex.: SP).");
        }
        return uf;
    }

    /** Trim e espaços duplos removidos; vazio vira nulo. */
    private static String opcional(String texto) {
        String normalizado = Textos.normalizarEspacos(texto);
        return normalizado.isEmpty() ? null : normalizado;
    }

    private static String limite(String texto, int tamanho, String rotulo) {
        if (texto != null && texto.length() > tamanho) {
            throw new NegocioException(rotulo + " pode ter no máximo " + tamanho + " caracteres.");
        }
        return texto;
    }
}
