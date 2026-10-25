package br.com.varesync.service;

import br.com.varesync.dao.ClienteDAO;
import br.com.varesync.model.DadosCliente;
import br.com.varesync.util.Textos;
import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * Normaliza e valida o cadastro de cliente (F12.1). Nome obrigatório; o resto é opcional, mas, se informado, tem de
 * estar certo: CPF/CNPJ com DV (como no fornecedor), telefone com DDD, e-mail, nascimento, CEP e UF. Gravado sem
 * máscara.
 */
final class ValidacaoCliente {

    static final int TAMANHO_NOME = 120;
    static final int TAMANHO_LOGRADOURO = 120;
    static final int TAMANHO_NUMERO = 10;
    static final int TAMANHO_COMPLEMENTO = 60;
    static final int TAMANHO_BAIRRO = 60;
    static final int TAMANHO_CIDADE = 60;
    /** Nascimento antes disso é erro de digitação. */
    static final LocalDate NASCIMENTO_MINIMO = LocalDate.of(1900, 1, 1);

    private static final Pattern MASCARA_CEP = Pattern.compile("[\\s.\\-]");

    private ValidacaoCliente() {
    }

    /** @param hoje para recusar nascimento no futuro */
    static ClienteDAO.Gravacao validar(DadosCliente dados, LocalDate hoje) {
        return new ClienteDAO.Gravacao(nome(dados.nome()), ValidacaoFornecedor.documento(dados.cpfCnpj()),
                ValidacaoFornecedor.telefone(dados.telefone()), ValidacaoFornecedor.email(dados.email()),
                nascimento(dados.dataNascimento(), hoje), cep(dados.cep()),
                limite(opcional(dados.logradouro()), TAMANHO_LOGRADOURO, "O logradouro"),
                limite(opcional(dados.numero()), TAMANHO_NUMERO, "O número"),
                limite(opcional(dados.complemento()), TAMANHO_COMPLEMENTO, "O complemento"),
                limite(opcional(dados.bairro()), TAMANHO_BAIRRO, "O bairro"),
                limite(opcional(dados.cidade()), TAMANHO_CIDADE, "A cidade"), ValidacaoFornecedor.uf(dados.uf()),
                Textos.vazio(dados.observacao()) ? null : dados.observacao().strip());
    }

    static String nome(String digitado) {
        String nome = Textos.normalizarEspacos(digitado);
        if (nome.isEmpty()) {
            throw new NegocioException("Informe o nome do cliente.");
        }
        return limite(nome, TAMANHO_NOME, "O nome");
    }

    static LocalDate nascimento(LocalDate data, LocalDate hoje) {
        if (data == null) {
            return null;
        }
        if (data.isAfter(hoje)) {
            throw new NegocioException("A data de nascimento não pode ser depois de hoje.");
        }
        if (data.isBefore(NASCIMENTO_MINIMO)) {
            throw new NegocioException("Data de nascimento inválida: confira o ano.");
        }
        return data;
    }

    /** Só dígitos, 8 ("14000-000" → "14000000"). */
    static String cep(String digitado) {
        if (Textos.vazio(digitado)) {
            return null;
        }
        String digitos = MASCARA_CEP.matcher(digitado).replaceAll("");
        if (!Textos.soDigitos(digitos) || digitos.length() != 8) {
            throw new NegocioException("CEP inválido: informe os 8 dígitos (ex.: 14000-000).");
        }
        return digitos;
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
