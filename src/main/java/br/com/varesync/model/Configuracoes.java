package br.com.varesync.model;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Acesso tipado aos parâmetros da tabela {@code configuracao} (5.2). As chaves e os valores padrão são os da V2 (e da
 * V4: {@code seguranca.inatividade_minutos}, F10.2); uma
 * chave que falta ou tem valor inválido (alterada fora do sistema) volta ao padrão em {@link #de(Map)}.
 *
 * <p>Imutável: os services leem um retrato a cada operação ({@code ConfiguracaoDAO.carregar}), e a tela de
 * Configurações grava um novo retrato inteiro ({@code ConfiguracaoService.salvar}).
 */
public record Configuracoes(Loja loja, ParametrosVenda venda, BigDecimal margemMinimaPct, int diasSemGiro,
                            Balanca balanca, Backup backup, String rodapeComprovante,
                            int inatividadeMinutos) {

    public static final String EMPRESA_NOME = "empresa.nome";
    public static final String EMPRESA_DOCUMENTO = "empresa.documento";
    public static final String EMPRESA_ENDERECO = "empresa.endereco";
    public static final String EMPRESA_TELEFONE = "empresa.telefone";
    public static final String VENDA_ESTOQUE_NEGATIVO = "venda.permitir_estoque_negativo";
    public static final String VENDA_DESCONTO_MAXIMO = "venda.desconto_maximo_operador_pct";
    public static final String VENDA_CANCELAR_ITEM = "venda.cancelar_item_exige_supervisor";
    public static final String MARGEM_MINIMA = "relatorio.margem_minima_pct";
    public static final String DIAS_SEM_GIRO = "relatorio.dias_sem_giro";
    public static final String BALANCA_ATIVO = "balanca.ativo";
    public static final String BALANCA_PREFIXO = "balanca.prefixo";
    public static final String BALANCA_DIGITOS = "balanca.digitos_codigo";
    public static final String BALANCA_TIPO_VALOR = "balanca.tipo_valor";
    public static final String BACKUP_PASTA = "backup.pasta";
    public static final String BACKUP_PG_DUMP = "backup.pg_dump";
    public static final String BACKUP_MANTER = "backup.manter_ultimos";
    public static final String COMPROVANTE_RODAPE = "comprovante.rodape";
    public static final String SEGURANCA_INATIVIDADE = "seguranca.inatividade_minutos";

    public static final int DIAS_SEM_GIRO_MAXIMO = 3650;
    /** Bloqueio por inatividade (F10.2): de 0 (desligado) a 8 horas. */
    public static final int INATIVIDADE_MAXIMA = 480;
    public static final int DIGITOS_PLU_MINIMO = 4;
    public static final int DIGITOS_PLU_MAXIMO = 6;
    private static final BigDecimal CEM = BigDecimal.valueOf(100);

    /** Dados da loja (comprovante, relatórios, título). Documento e telefone gravados sem máscara. */
    public record Loja(String nome, String documento, String endereco, String telefone) {
    }

    /**
     * Etiqueta de balança (6.9): EAN-13 que começa com {@code prefixo}, seguido de {@code digitosCodigo} dígitos de
     * PLU e do valor ({@code tipoValor}) até o 12º dígito.
     */
    public record Balanca(boolean ativo, int prefixo, int digitosCodigo, TipoValorBalanca tipoValor) {

        /** Maior PLU que cabe na etiqueta: 9999, 99999 ou 999999. */
        public int pluMaximo() {
            return (int) Math.pow(10, digitosCodigo) - 1;
        }
    }

    /** Backup (6.12). Pasta e {@code pg_dump} vazios = padrão. */
    public record Backup(String pasta, String pgDump, int manterUltimos) {
    }

    /** Valores da V2. */
    public static final Configuracoes PADRAO = new Configuracoes(new Loja("Minha Loja", "", "", ""),
            ParametrosVenda.PADRAO, BigDecimal.valueOf(15), 30,
            new Balanca(false, 2, 5, TipoValorBalanca.PRECO), new Backup("", "", 15),
            "Obrigado pela preferência!", 15);

    public Configuracoes comLoja(Loja outra) {
        return new Configuracoes(outra, venda, margemMinimaPct, diasSemGiro, balanca, backup, rodapeComprovante,
                inatividadeMinutos);
    }

    public Configuracoes comVenda(ParametrosVenda outra) {
        return new Configuracoes(loja, outra, margemMinimaPct, diasSemGiro, balanca, backup, rodapeComprovante,
                inatividadeMinutos);
    }

    public Configuracoes comRelatorios(BigDecimal margem, int dias) {
        return new Configuracoes(loja, venda, margem, dias, balanca, backup, rodapeComprovante,
                inatividadeMinutos);
    }

    public Configuracoes comBalanca(Balanca outra) {
        return new Configuracoes(loja, venda, margemMinimaPct, diasSemGiro, outra, backup, rodapeComprovante,
                inatividadeMinutos);
    }

    public Configuracoes comRodape(String outro) {
        return new Configuracoes(loja, venda, margemMinimaPct, diasSemGiro, balanca, backup, outro, inatividadeMinutos);
    }

    /** Lê as linhas da tabela; chave ausente ou valor inválido usa o padrão da V2. */
    public static Configuracoes de(Map<String, String> valores) {
        Configuracoes p = PADRAO;
        Loja loja = new Loja(texto(valores, EMPRESA_NOME, p.loja.nome()), texto(valores, EMPRESA_DOCUMENTO, ""),
                texto(valores, EMPRESA_ENDERECO, ""), texto(valores, EMPRESA_TELEFONE, ""));
        ParametrosVenda venda = new ParametrosVenda(
                logico(valores, VENDA_ESTOQUE_NEGATIVO, p.venda.permitirEstoqueNegativo()),
                percentual(valores, VENDA_DESCONTO_MAXIMO, true).orElse(p.venda.descontoMaximoPct()),
                logico(valores, VENDA_CANCELAR_ITEM, p.venda.cancelarItemExigeSupervisor()));
        BigDecimal margem = percentual(valores, MARGEM_MINIMA, false).orElse(p.margemMinimaPct);
        int dias = inteiro(valores, DIAS_SEM_GIRO, 1, DIAS_SEM_GIRO_MAXIMO).orElse(p.diasSemGiro);
        Balanca balanca = new Balanca(logico(valores, BALANCA_ATIVO, p.balanca.ativo()),
                inteiro(valores, BALANCA_PREFIXO, 0, 9).filter(v -> valores.get(BALANCA_PREFIXO).strip().length() == 1)
                        .orElse(p.balanca.prefixo()),
                inteiro(valores, BALANCA_DIGITOS, DIGITOS_PLU_MINIMO, DIGITOS_PLU_MAXIMO)
                        .orElse(p.balanca.digitosCodigo()),
                tipoValor(valores.get(BALANCA_TIPO_VALOR)).orElse(p.balanca.tipoValor()));
        Backup backup = new Backup(texto(valores, BACKUP_PASTA, ""), texto(valores, BACKUP_PG_DUMP, ""),
                inteiro(valores, BACKUP_MANTER, 1, 999).orElse(p.backup.manterUltimos()));
        return new Configuracoes(loja, venda, margem, dias, balanca, backup,
                texto(valores, COMPROVANTE_RODAPE, p.rodapeComprovante),
                inteiro(valores, SEGURANCA_INATIVIDADE, 0, INATIVIDADE_MAXIMA).orElse(p.inatividadeMinutos));
    }

    public Configuracoes comInatividade(int minutos) {
        return new Configuracoes(loja, venda, margemMinimaPct, diasSemGiro, balanca, backup, rodapeComprovante,
                minutos);
    }

    /** Chave → valor como fica na tabela (números com ponto decimal, lógicos {@code true}/{@code false}). */
    public Map<String, String> paraMapa() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(EMPRESA_NOME, loja.nome());
        m.put(EMPRESA_DOCUMENTO, loja.documento());
        m.put(EMPRESA_ENDERECO, loja.endereco());
        m.put(EMPRESA_TELEFONE, loja.telefone());
        m.put(VENDA_ESTOQUE_NEGATIVO, String.valueOf(venda.permitirEstoqueNegativo()));
        m.put(VENDA_DESCONTO_MAXIMO, numero(venda.descontoMaximoPct()));
        m.put(VENDA_CANCELAR_ITEM, String.valueOf(venda.cancelarItemExigeSupervisor()));
        m.put(MARGEM_MINIMA, numero(margemMinimaPct));
        m.put(DIAS_SEM_GIRO, String.valueOf(diasSemGiro));
        m.put(BALANCA_ATIVO, String.valueOf(balanca.ativo()));
        m.put(BALANCA_PREFIXO, String.valueOf(balanca.prefixo()));
        m.put(BALANCA_DIGITOS, String.valueOf(balanca.digitosCodigo()));
        m.put(BALANCA_TIPO_VALOR, balanca.tipoValor().name());
        m.put(BACKUP_PASTA, backup.pasta());
        m.put(BACKUP_PG_DUMP, backup.pgDump());
        m.put(BACKUP_MANTER, String.valueOf(backup.manterUltimos()));
        m.put(COMPROVANTE_RODAPE, rodapeComprovante);
        m.put(SEGURANCA_INATIVIDADE, String.valueOf(inatividadeMinutos));
        return m;
    }

    private static String numero(BigDecimal valor) {
        BigDecimal sem = valor.stripTrailingZeros();
        return (sem.scale() < 0 ? sem.setScale(0) : sem).toPlainString();
    }

    private static String texto(Map<String, String> valores, String chave, String padrao) {
        String valor = valores.get(chave);
        return valor == null ? padrao : valor.strip();
    }

    private static boolean logico(Map<String, String> valores, String chave, boolean padrao) {
        String valor = valores.get(chave);
        if (valor == null) {
            return padrao;
        }
        String v = valor.strip();
        return v.equalsIgnoreCase("true") || (!v.equalsIgnoreCase("false") && padrao);
    }

    /** Percentual de 0 a 100 (com 100 só onde {@code aceitaCem}), com ponto ou vírgula decimal. */
    private static Optional<BigDecimal> percentual(Map<String, String> valores, String chave, boolean aceitaCem) {
        String valor = valores.get(chave);
        if (valor == null) {
            return Optional.empty();
        }
        try {
            BigDecimal v = new BigDecimal(valor.strip().replace(',', '.'));
            boolean dentro = v.signum() >= 0 && (aceitaCem ? v.compareTo(CEM) <= 0 : v.compareTo(CEM) < 0);
            return dentro ? Optional.of(v) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<Integer> inteiro(Map<String, String> valores, String chave, int minimo, int maximo) {
        String valor = valores.get(chave);
        if (valor == null) {
            return Optional.empty();
        }
        try {
            int v = Integer.parseInt(valor.strip());
            return v >= minimo && v <= maximo ? Optional.of(v) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<TipoValorBalanca> tipoValor(String valor) {
        if (valor == null) {
            return Optional.empty();
        }
        for (TipoValorBalanca t : TipoValorBalanca.values()) {
            if (t.name().equalsIgnoreCase(valor.strip())) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
