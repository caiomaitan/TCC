package br.com.varesync.service;

import br.com.varesync.model.CodigoBarras;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Gtin;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validações do cadastro de produto (6.3), com mensagens prontas para a tela.
 * As mesmas regras dos CHECK de {@code produto} e {@code produto_codigo_barras}, mais as que o banco não conhece
 * (casas decimais, unidade inteira, dígito verificador).
 */
final class ValidacaoProduto {

    static final int TAMANHO_MINIMO_NOME = 2;
    static final int TAMANHO_MAXIMO_NOME = 120;
    static final int PLU_MAXIMO = 999_999;

    /** NUMERIC(12,2) e NUMERIC(14,4) aceitam até 10 dígitos inteiros; NUMERIC(14,3), até 11. */
    private static final BigDecimal LIMITE_VALOR = new BigDecimal("10000000000");
    private static final BigDecimal LIMITE_QUANTIDADE = new BigDecimal("100000000000");

    private ValidacaoProduto() {
    }

    /** @return o nome sem espaços nas pontas nem duplicados */
    static String nome(String digitado) {
        String nome = Textos.normalizarEspacos(digitado);
        if (nome.isEmpty()) {
            throw new NegocioException("Informe o nome do produto.");
        }
        if (nome.length() < TAMANHO_MINIMO_NOME) {
            throw new NegocioException("O nome do produto deve ter ao menos " + TAMANHO_MINIMO_NOME + " caracteres.");
        }
        if (nome.length() > TAMANHO_MAXIMO_NOME) {
            throw new NegocioException("O nome do produto pode ter no máximo " + TAMANHO_MAXIMO_NOME + " caracteres.");
        }
        return nome;
    }

    /** Preço de venda: obrigatório, ≥ 0, até 2 casas. Preço 0 é aceito (o produto só não é vendido). */
    static BigDecimal preco(BigDecimal preco) {
        return valor(preco, "o preço de venda", Dinheiro.ESCALA_DINHEIRO);
    }

    /** Custo: obrigatório, ≥ 0, até 4 casas. */
    static BigDecimal custo(BigDecimal custo) {
        return valor(custo, "o custo", Dinheiro.ESCALA_CUSTO);
    }

    /** Valor ≥ 0 com até {@code escala} casas; {@code rotulo} em minúsculas ("o custo unitário"). */
    static BigDecimal valor(BigDecimal valor, String rotulo, int escala) {
        if (valor == null) {
            throw new NegocioException("Informe " + rotulo + ".");
        }
        String nome = maiuscula(rotulo);
        if (valor.signum() < 0) {
            throw new NegocioException(nome + " não pode ser negativo.");
        }
        if (casas(valor) > escala) {
            throw new NegocioException(nome + " pode ter no máximo " + escala + " casas decimais.");
        }
        if (valor.compareTo(LIMITE_VALOR) >= 0) {
            throw new NegocioException(nome + " está acima do limite permitido.");
        }
        return valor.setScale(escala, Dinheiro.ARREDONDAMENTO);
    }

    /**
     * Quantidade ≥ 0, até 3 casas e inteira se a unidade não é fracionada (regra do sistema todo, 6.4).
     *
     * @param rotulo "O estoque mínimo", "O estoque inicial"…
     */
    static BigDecimal quantidade(BigDecimal quantidade, String rotulo, boolean fracionada) {
        if (quantidade.signum() < 0) {
            throw new NegocioException(rotulo + " não pode ser negativo.");
        }
        if (casas(quantidade) > Dinheiro.ESCALA_QUANTIDADE) {
            throw new NegocioException(rotulo + " pode ter no máximo " + Dinheiro.ESCALA_QUANTIDADE
                    + " casas decimais.");
        }
        if (!fracionada && casas(quantidade) > 0) {
            String digitada = quantidade.stripTrailingZeros().toPlainString().replace('.', ',');
            throw new NegocioException(rotulo + " deve ser um número inteiro: a unidade do produto não aceita "
                    + "quantidade fracionada (" + digitada + ").");
        }
        if (quantidade.compareTo(LIMITE_QUANTIDADE) >= 0) {
            throw new NegocioException(rotulo + " está acima do limite permitido.");
        }
        return quantidade.setScale(Dinheiro.ESCALA_QUANTIDADE, Dinheiro.ARREDONDAMENTO);
    }

    /**
     * PLU: só com balança ativa e unidade fracionada (6.3), de 1 a 999999.
     *
     * @param jaGravado o produto já tinha este PLU: continua valendo mesmo com a balança desligada
     */
    static void plu(Integer plu, boolean fracionada, boolean balancaAtiva, boolean jaGravado) {
        if (plu == null) {
            return;
        }
        if (!balancaAtiva && !jaGravado) {
            throw new NegocioException("O PLU só é usado com a leitura de etiquetas de balança ativa (Configurações).");
        }
        if (!fracionada) {
            throw new NegocioException("O PLU só vale para produto vendido por peso ou volume (unidade fracionada).");
        }
        if (plu < 1 || plu > PLU_MAXIMO) {
            throw new NegocioException("O PLU deve estar entre 1 e " + PLU_MAXIMO + ".");
        }
    }

    /**
     * Códigos de barras: só dígitos, GTIN-8/12/13/14 com DV válido, sem repetição e no máximo um principal.
     * Sem principal marcado, o primeiro vira o principal.
     *
     * @return a lista normalizada, com o principal primeiro
     */
    static List<CodigoBarras> codigos(List<CodigoBarras> informados) {
        List<CodigoBarras> normalizados = new ArrayList<>();
        Set<String> vistos = new HashSet<>();
        long principais = 0;
        for (CodigoBarras informado : informados) {
            String codigo = informado.codigo() == null ? "" : informado.codigo().strip();
            Gtin.validar(codigo).ifPresent(erro -> {
                throw new NegocioException(erro);
            });
            if (!vistos.add(codigo)) {
                throw new NegocioException("O código de barras " + codigo + " foi informado mais de uma vez.");
            }
            if (informado.principal()) {
                principais++;
            }
            normalizados.add(new CodigoBarras(codigo, informado.principal()));
        }
        if (principais > 1) {
            throw new NegocioException("Marque só um código de barras como principal.");
        }
        if (principais == 0 && !normalizados.isEmpty()) {
            normalizados.set(0, new CodigoBarras(normalizados.get(0).codigo(), true));
        }
        List<CodigoBarras> ordenados = new ArrayList<>(normalizados.stream().filter(CodigoBarras::principal).toList());
        normalizados.stream().filter(c -> !c.principal()).forEach(ordenados::add);
        return ordenados;
    }

    /** Casas decimais significativas: 1,50 → 1; 10 → 0. */
    static int casas(BigDecimal valor) {
        return Math.max(0, valor.stripTrailingZeros().scale());
    }

    private static String maiuscula(String texto) {
        return Character.toUpperCase(texto.charAt(0)) + texto.substring(1);
    }
}
