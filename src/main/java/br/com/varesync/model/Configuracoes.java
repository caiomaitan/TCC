package br.com.varesync.model;

import br.com.varesync.util.Dinheiro;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Acesso tipado aos parâmetros da tabela {@code configuracao} (5.2). As chaves e os valores padrão são os da V2 (e da
 * V4 e da V5: {@code seguranca.*}, F10.2 e F10.6; da V7: {@code fidelidade.*}, F12.5; e da V8:
 * {@code devolucao.prazo_dias}, F13.2; e da V10: {@code crediario.*}, F14; e da V12: {@code financeiro.integrar_caixa}, F15.1;
 * e da V13: {@code compras.*}, F16.2; e da V15: {@code validade.alerta_dias}, F17; e da V16: {@code etiqueta.*}, F18.1; e da V17: {@code balanca.pasta_carga}, F18.2); uma chave que falta ou tem valor inválido (alterada fora do sistema) volta
 * ao padrão em {@link #de(Map)}.
 *
 * <p>Imutável: os services leem um retrato a cada operação ({@code ConfiguracaoDAO.carregar}), e a tela de
 * Configurações grava um novo retrato inteiro ({@code ConfiguracaoService.salvar}).
 */
public record Configuracoes(Loja loja, ParametrosVenda venda, BigDecimal margemMinimaPct, int diasSemGiro,
                            Balanca balanca, Backup backup, String rodapeComprovante, Seguranca seguranca,
                            Fidelidade fidelidade, int devolucaoPrazoDias, Crediario crediario,
                            boolean integrarCaixa, Compras compras, int validadeAlertaDias,
                            Etiquetas etiquetas) {

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
    public static final String BALANCA_PASTA_CARGA = "balanca.pasta_carga";
    public static final String BACKUP_PASTA = "backup.pasta";
    public static final String BACKUP_PG_DUMP = "backup.pg_dump";
    public static final String BACKUP_MANTER = "backup.manter_ultimos";
    public static final String COMPROVANTE_RODAPE = "comprovante.rodape";
    public static final String SEGURANCA_INATIVIDADE = "seguranca.inatividade_minutos";
    public static final String SEGURANCA_SENHA_TAMANHO = "seguranca.senha_tamanho_minimo";
    public static final String SEGURANCA_SENHA_LETRA_E_NUMERO = "seguranca.senha_letra_e_numero";
    public static final String FIDELIDADE_ATIVO = "fidelidade.ativo";
    public static final String FIDELIDADE_CASHBACK = "fidelidade.cashback_pct";
    public static final String FIDELIDADE_COMPRA_MINIMA = "fidelidade.compra_minima";
    public static final String DEVOLUCAO_PRAZO = "devolucao.prazo_dias";
    public static final String CREDIARIO_MAX_PARCELAS = "crediario.max_parcelas";
    public static final String CREDIARIO_DIAS_PRIMEIRA = "crediario.dias_primeira";
    public static final String CREDIARIO_JUROS = "crediario.juros_mes_pct";
    public static final String CREDIARIO_MULTA = "crediario.multa_pct";
    public static final String CREDIARIO_CARENCIA = "crediario.carencia_dias";
    public static final String CREDIARIO_BLOQUEIO = "crediario.bloqueio_atraso_dias";
    public static final String FINANCEIRO_INTEGRAR_CAIXA = "financeiro.integrar_caixa";
    public static final String COMPRAS_DIAS_MEDIA = "compras.dias_media";
    public static final String COMPRAS_COBERTURA = "compras.cobertura_dias";
    public static final String VALIDADE_ALERTA_DIAS = "validade.alerta_dias";
    public static final String ETIQUETA_COLUNAS = "etiqueta.colunas";
    public static final String ETIQUETA_LINHAS = "etiqueta.linhas";
    public static final String ETIQUETA_MODELO_TERMICA = "etiqueta.modelo_termica";
    public static final String ETIQUETA_IMPRESSORA_TERMICA = "etiqueta.impressora_termica";

    public static final int DIAS_SEM_GIRO_MAXIMO = 3650;
    /** Bloqueio por inatividade (F10.2): de 0 (desligado) a 8 horas. */
    public static final int INATIVIDADE_MAXIMA = 480;
    public static final int DIGITOS_PLU_MINIMO = 4;
    public static final int DIGITOS_PLU_MAXIMO = 6;
    /** Prazo de devolução sem liberação (F13.2): de 0 (sempre pede liberação) a um ano; padrão da V8. */
    public static final int DEVOLUCAO_PRAZO_PADRAO = 30;
    public static final int DEVOLUCAO_PRAZO_MAXIMO = 365;
    /** F17: dias antes do vencimento em que o lote entra no alerta. */
    public static final int VALIDADE_ALERTA_PADRAO = 7;
    public static final int VALIDADE_ALERTA_MAXIMO = 365;
    private static final BigDecimal CEM = BigDecimal.valueOf(100);

    /** Dados da loja (comprovante, relatórios, título). Documento e telefone gravados sem máscara. */
    public record Loja(String nome, String documento, String endereco, String telefone) {
    }

    /**
     * Etiqueta de balança (6.9): EAN-13 que começa com {@code prefixo}, seguido de {@code digitosCodigo} dígitos de
     * PLU e do valor ({@code tipoValor}) até o 12º dígito. {@code pastaCarga} (F18.2): pasta onde o ITENSMGV.TXT é
     * gravado; vazia = não configurada.
     */
    public record Balanca(boolean ativo, int prefixo, int digitosCodigo, TipoValorBalanca tipoValor,
                          String pastaCarga) {

        public Balanca {
            pastaCarga = pastaCarga == null ? "" : pastaCarga.strip();
        }

        /** Sem pasta de carga. */
        public Balanca(boolean ativo, int prefixo, int digitosCodigo, TipoValorBalanca tipoValor) {
            this(ativo, prefixo, digitosCodigo, tipoValor, "");
        }

        /** Maior PLU que cabe na etiqueta: 9999, 99999 ou 999999. */
        public int pluMaximo() {
            return (int) Math.pow(10, digitosCodigo) - 1;
        }
    }

    /** Backup (6.12). Pasta e {@code pg_dump} vazios = padrão. */
    public record Backup(String pasta, String pgDump, int manterUltimos) {
    }

    /** Segurança: bloqueio por inatividade (F10.2) e política de senha (F10.6). */
    public record Seguranca(int inatividadeMinutos, PoliticaSenha senha) {
    }

    /**
     * Fidelidade (F12.5): com {@code ativo}, a venda com cliente identificado gera cashback de {@code cashbackPct}% da
     * base (total − crédito usado), se a base for ≥ {@code compraMinima}.
     */
    public record Fidelidade(boolean ativo, BigDecimal cashbackPct, BigDecimal compraMinima) {

        public static final Fidelidade PADRAO = new Fidelidade(false, BigDecimal.ONE, BigDecimal.ZERO);

        /** round(base × % ÷ 100, 2), ou zero se desligada ou se a base ficar abaixo da compra mínima. */
        public BigDecimal cashback(BigDecimal base) {
            if (!ativo || base.signum() <= 0 || base.compareTo(compraMinima) < 0) {
                return Dinheiro.ZERO;
            }
            return base.multiply(cashbackPct).divide(CEM, Dinheiro.ESCALA_DINHEIRO, Dinheiro.ARREDONDAMENTO);
        }
    }

    /** Crediário (F14): parcelas, vencimento da primeira, encargos sobre atraso e atraso que pede liberação. */
    public record Crediario(int maxParcelas, int diasPrimeira, BigDecimal jurosMesPct, BigDecimal multaPct,
                            int carenciaDias, int bloqueioAtrasoDias) {

        public static final int MAX_PARCELAS = 60;
        public static final int DIAS_MAXIMO = 3650;
        public static final Crediario PADRAO = new Crediario(3, 30, BigDecimal.valueOf(2), BigDecimal.valueOf(2), 0, 30);
    }

    /**
     * Sugestão de compra (F16.2): média diária dos últimos {@code diasMedia} dias de venda e cobertura desejada em
     * dias, além do prazo de entrega do fornecedor principal.
     */
    public record Compras(int diasMedia, int coberturaDias) {

        public static final int DIAS_MAXIMO = 365;
        public static final Compras PADRAO = new Compras(30, 15);
    }

    /**
     * Etiquetas de gôndola (F18.1): {@code colunas} × {@code linhas} etiquetas por folha A4; modelo ZPL ou DPL e nome
     * da impressora térmica (vazios = não configurada).
     */
    public record Etiquetas(int colunas, int linhas, String modeloTermica, String impressoraTermica) {

        public static final int COLUNAS_MAXIMO = 6;
        public static final int LINHAS_MAXIMO = 20;
        public static final Etiquetas PADRAO = new Etiquetas(3, 8, "", "");

        public int porFolha() {
            return colunas * linhas;
        }
    }

    /** Valores da V2 (e da V4, V5, V7, V8, V10, V12, V13, V15, V16 e V17). */
    public static final Configuracoes PADRAO = new Configuracoes(new Loja("Minha Loja", "", "", ""),
            ParametrosVenda.PADRAO, BigDecimal.valueOf(15), 30,
            new Balanca(false, 2, 5, TipoValorBalanca.PRECO), new Backup("", "", 15),
            "Obrigado pela preferência!", new Seguranca(15, PoliticaSenha.PADRAO), Fidelidade.PADRAO,
            DEVOLUCAO_PRAZO_PADRAO, Crediario.PADRAO, false, Compras.PADRAO, VALIDADE_ALERTA_PADRAO,
            Etiquetas.PADRAO);

    /** Minutos sem atividade até bloquear a tela (0 = desligado). */
    public int inatividadeMinutos() {
        return seguranca.inatividadeMinutos();
    }

    public PoliticaSenha politicaSenha() {
        return seguranca.senha();
    }

    public Configuracoes comLoja(Loja outra) {
        return new Configuracoes(outra, venda, margemMinimaPct, diasSemGiro, balanca, backup, rodapeComprovante,
                seguranca, fidelidade, devolucaoPrazoDias, crediario, integrarCaixa, compras, validadeAlertaDias,
                etiquetas);
    }

    public Configuracoes comVenda(ParametrosVenda outra) {
        return new Configuracoes(loja, outra, margemMinimaPct, diasSemGiro, balanca, backup, rodapeComprovante,
                seguranca, fidelidade, devolucaoPrazoDias, crediario, integrarCaixa, compras, validadeAlertaDias,
                etiquetas);
    }

    public Configuracoes comRelatorios(BigDecimal margem, int dias) {
        return new Configuracoes(loja, venda, margem, dias, balanca, backup, rodapeComprovante, seguranca,
                fidelidade, devolucaoPrazoDias, crediario, integrarCaixa, compras, validadeAlertaDias,
                etiquetas);
    }

    public Configuracoes comBalanca(Balanca outra) {
        return new Configuracoes(loja, venda, margemMinimaPct, diasSemGiro, outra, backup, rodapeComprovante,
                seguranca, fidelidade, devolucaoPrazoDias, crediario, integrarCaixa, compras, validadeAlertaDias,
                etiquetas);
    }

    public Configuracoes comBackup(Backup outro) {
        return new Configuracoes(loja, venda, margemMinimaPct, diasSemGiro, balanca, outro, rodapeComprovante,
                seguranca, fidelidade, devolucaoPrazoDias, crediario, integrarCaixa, compras, validadeAlertaDias,
                etiquetas);
    }

    public Configuracoes comRodape(String outro) {
        return new Configuracoes(loja, venda, margemMinimaPct, diasSemGiro, balanca, backup, outro, seguranca,
                fidelidade, devolucaoPrazoDias, crediario, integrarCaixa, compras, validadeAlertaDias,
                etiquetas);
    }

    public Configuracoes comSeguranca(Seguranca outra) {
        return new Configuracoes(loja, venda, margemMinimaPct, diasSemGiro, balanca, backup, rodapeComprovante,
                outra, fidelidade, devolucaoPrazoDias, crediario, integrarCaixa, compras, validadeAlertaDias,
                etiquetas);
    }

    public Configuracoes comFidelidade(Fidelidade outra) {
        return new Configuracoes(loja, venda, margemMinimaPct, diasSemGiro, balanca, backup, rodapeComprovante,
                seguranca, outra, devolucaoPrazoDias, crediario, integrarCaixa, compras, validadeAlertaDias,
                etiquetas);
    }

    public Configuracoes comDevolucaoPrazo(int dias) {
        return new Configuracoes(loja, venda, margemMinimaPct, diasSemGiro, balanca, backup, rodapeComprovante,
                seguranca, fidelidade, dias, crediario, integrarCaixa, compras, validadeAlertaDias,
                etiquetas);
    }

    public Configuracoes comCrediario(Crediario outro) {
        return new Configuracoes(loja, venda, margemMinimaPct, diasSemGiro, balanca, backup, rodapeComprovante,
                seguranca, fidelidade, devolucaoPrazoDias, outro, integrarCaixa, compras, validadeAlertaDias,
                etiquetas);
    }

    public Configuracoes comIntegrarCaixa(boolean integrar) {
        return new Configuracoes(loja, venda, margemMinimaPct, diasSemGiro, balanca, backup, rodapeComprovante,
                seguranca, fidelidade, devolucaoPrazoDias, crediario, integrar, compras, validadeAlertaDias, etiquetas);
    }

    public Configuracoes comCompras(Compras outra) {
        return new Configuracoes(loja, venda, margemMinimaPct, diasSemGiro, balanca, backup, rodapeComprovante,
                seguranca, fidelidade, devolucaoPrazoDias, crediario, integrarCaixa, outra, validadeAlertaDias, etiquetas);
    }

    public Configuracoes comValidadeAlertaDias(int dias) {
        return new Configuracoes(loja, venda, margemMinimaPct, diasSemGiro, balanca, backup, rodapeComprovante,
                seguranca, fidelidade, devolucaoPrazoDias, crediario, integrarCaixa, compras, dias, etiquetas);
    }

    public Configuracoes comEtiquetas(Etiquetas outras) {
        return new Configuracoes(loja, venda, margemMinimaPct, diasSemGiro, balanca, backup, rodapeComprovante,
                seguranca, fidelidade, devolucaoPrazoDias, crediario, integrarCaixa, compras, validadeAlertaDias,
                outras);
    }

    public Configuracoes comInatividade(int minutos) {
        return comSeguranca(new Seguranca(minutos, seguranca.senha()));
    }

    public Configuracoes comPoliticaSenha(PoliticaSenha outra) {
        return comSeguranca(new Seguranca(seguranca.inatividadeMinutos(), outra));
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
                tipoValor(valores.get(BALANCA_TIPO_VALOR)).orElse(p.balanca.tipoValor()),
                texto(valores, BALANCA_PASTA_CARGA, ""));
        Backup backup = new Backup(texto(valores, BACKUP_PASTA, ""), texto(valores, BACKUP_PG_DUMP, ""),
                inteiro(valores, BACKUP_MANTER, 1, 999).orElse(p.backup.manterUltimos()));
        Seguranca seguranca = new Seguranca(
                inteiro(valores, SEGURANCA_INATIVIDADE, 0, INATIVIDADE_MAXIMA).orElse(p.inatividadeMinutos()),
                new PoliticaSenha(inteiro(valores, SEGURANCA_SENHA_TAMANHO, PoliticaSenha.TAMANHO_MINIMO,
                        PoliticaSenha.TAMANHO_MAXIMO).orElse(p.politicaSenha().tamanhoMinimo()),
                        logico(valores, SEGURANCA_SENHA_LETRA_E_NUMERO, p.politicaSenha().letraENumero())));
        Fidelidade fidelidade = new Fidelidade(logico(valores, FIDELIDADE_ATIVO, p.fidelidade.ativo()),
                percentual(valores, FIDELIDADE_CASHBACK, true).orElse(p.fidelidade.cashbackPct()),
                naoNegativo(valores, FIDELIDADE_COMPRA_MINIMA).orElse(p.fidelidade.compraMinima()));
        Crediario crediario = new Crediario(
                inteiro(valores, CREDIARIO_MAX_PARCELAS, 1, Crediario.MAX_PARCELAS).orElse(p.crediario.maxParcelas()),
                inteiro(valores, CREDIARIO_DIAS_PRIMEIRA, 0, Crediario.DIAS_MAXIMO).orElse(p.crediario.diasPrimeira()),
                percentual(valores, CREDIARIO_JUROS, true).orElse(p.crediario.jurosMesPct()),
                percentual(valores, CREDIARIO_MULTA, true).orElse(p.crediario.multaPct()),
                inteiro(valores, CREDIARIO_CARENCIA, 0, Crediario.DIAS_MAXIMO).orElse(p.crediario.carenciaDias()),
                inteiro(valores, CREDIARIO_BLOQUEIO, 0, Crediario.DIAS_MAXIMO)
                        .orElse(p.crediario.bloqueioAtrasoDias()));
        return new Configuracoes(loja, venda, margem, dias, balanca, backup,
                texto(valores, COMPROVANTE_RODAPE, p.rodapeComprovante), seguranca, fidelidade,
                inteiro(valores, DEVOLUCAO_PRAZO, 0, DEVOLUCAO_PRAZO_MAXIMO).orElse(p.devolucaoPrazoDias), crediario,
                logico(valores, FINANCEIRO_INTEGRAR_CAIXA, p.integrarCaixa),
                new Compras(inteiro(valores, COMPRAS_DIAS_MEDIA, 1, Compras.DIAS_MAXIMO).orElse(p.compras.diasMedia()),
                        inteiro(valores, COMPRAS_COBERTURA, 0, Compras.DIAS_MAXIMO).orElse(p.compras.coberturaDias())),
                inteiro(valores, VALIDADE_ALERTA_DIAS, 0, VALIDADE_ALERTA_MAXIMO).orElse(p.validadeAlertaDias),
                new Etiquetas(inteiro(valores, ETIQUETA_COLUNAS, 1, Etiquetas.COLUNAS_MAXIMO).orElse(p.etiquetas.colunas()),
                        inteiro(valores, ETIQUETA_LINHAS, 1, Etiquetas.LINHAS_MAXIMO).orElse(p.etiquetas.linhas()),
                        texto(valores, ETIQUETA_MODELO_TERMICA, ""), texto(valores, ETIQUETA_IMPRESSORA_TERMICA, "")));
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
        m.put(BALANCA_PASTA_CARGA, balanca.pastaCarga());
        m.put(BACKUP_PASTA, backup.pasta());
        m.put(BACKUP_PG_DUMP, backup.pgDump());
        m.put(BACKUP_MANTER, String.valueOf(backup.manterUltimos()));
        m.put(COMPROVANTE_RODAPE, rodapeComprovante);
        m.put(SEGURANCA_INATIVIDADE, String.valueOf(seguranca.inatividadeMinutos()));
        m.put(SEGURANCA_SENHA_TAMANHO, String.valueOf(seguranca.senha().tamanhoMinimo()));
        m.put(SEGURANCA_SENHA_LETRA_E_NUMERO, String.valueOf(seguranca.senha().letraENumero()));
        m.put(FIDELIDADE_ATIVO, String.valueOf(fidelidade.ativo()));
        m.put(FIDELIDADE_CASHBACK, numero(fidelidade.cashbackPct()));
        m.put(FIDELIDADE_COMPRA_MINIMA, numero(fidelidade.compraMinima()));
        m.put(DEVOLUCAO_PRAZO, String.valueOf(devolucaoPrazoDias));
        m.put(CREDIARIO_MAX_PARCELAS, String.valueOf(crediario.maxParcelas()));
        m.put(CREDIARIO_DIAS_PRIMEIRA, String.valueOf(crediario.diasPrimeira()));
        m.put(CREDIARIO_JUROS, numero(crediario.jurosMesPct()));
        m.put(CREDIARIO_MULTA, numero(crediario.multaPct()));
        m.put(CREDIARIO_CARENCIA, String.valueOf(crediario.carenciaDias()));
        m.put(CREDIARIO_BLOQUEIO, String.valueOf(crediario.bloqueioAtrasoDias()));
        m.put(FINANCEIRO_INTEGRAR_CAIXA, String.valueOf(integrarCaixa));
        m.put(COMPRAS_DIAS_MEDIA, String.valueOf(compras.diasMedia()));
        m.put(COMPRAS_COBERTURA, String.valueOf(compras.coberturaDias()));
        m.put(VALIDADE_ALERTA_DIAS, String.valueOf(validadeAlertaDias));
        m.put(ETIQUETA_COLUNAS, String.valueOf(etiquetas.colunas()));
        m.put(ETIQUETA_LINHAS, String.valueOf(etiquetas.linhas()));
        m.put(ETIQUETA_MODELO_TERMICA, etiquetas.modeloTermica());
        m.put(ETIQUETA_IMPRESSORA_TERMICA, etiquetas.impressoraTermica());
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

    /** Valor ≥ 0, com ponto ou vírgula decimal (compra mínima da fidelidade). */
    private static Optional<BigDecimal> naoNegativo(Map<String, String> valores, String chave) {
        String valor = valores.get(chave);
        if (valor == null) {
            return Optional.empty();
        }
        try {
            BigDecimal v = new BigDecimal(valor.strip().replace(',', '.'));
            return v.signum() >= 0 ? Optional.of(v) : Optional.empty();
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
