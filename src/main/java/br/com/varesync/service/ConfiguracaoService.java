package br.com.varesync.service;

import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.ContaFinanceiraDAO;
import br.com.varesync.dao.FormaPagamentoDAO;
import br.com.varesync.dao.ProdutoDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.ContaFinanceira;
import br.com.varesync.model.FormaPagamentoCadastro;
import br.com.varesync.model.MeioPagamentoNfce;
import br.com.varesync.model.ParametrosVenda;
import br.com.varesync.model.PoliticaSenha;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.util.CpfCnpj;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Configurações (6.13, só supervisor): dados da loja, parâmetros de venda, margem mínima, dias sem giro, balança,
 * bloqueio por inatividade (F10.2), fidelidade (F12.5) e formas de pagamento. Toda alteração é auditada
 * ({@code CONFIGURACAO_ALTERADA}) na mesma transação, com o valor anterior e o novo.
 */
public class ConfiguracaoService {

    public static final String ENTIDADE_CONFIGURACAO = "configuracao";
    public static final String ENTIDADE_FORMA_PAGAMENTO = "forma_pagamento";
    static final int TAMANHO_NOME_LOJA = AcessoService.TAMANHO_MAXIMO_NOME_LOJA;
    static final int TAMANHO_ENDERECO = 200;
    static final int TAMANHO_RODAPE = 200;
    static final int TAMANHO_FORMA = 40;
    static final int TAMANHO_IMPRESSORA = 200;
    static final int TAMANHO_MODELO = 10_000;
    /** Cabe em {@code carga_balanca.arquivo} (500) com o nome do arquivo. */
    static final int TAMANHO_PASTA_CARGA = 480;
    private static final BigDecimal CEM = BigDecimal.valueOf(100);
    private static final BigDecimal TAXA_MAXIMA = BigDecimal.valueOf(20);
    private static final int DIAS_RECEBIMENTO_MAXIMO = 60;

    private final Transacao transacao;
    private final ConfiguracaoDAO configuracaoDAO;
    private final FormaPagamentoDAO formaPagamentoDAO;
    private final ProdutoDAO produtoDAO;
    private final ContaFinanceiraDAO contaDAO;
    private final AuditoriaService auditoria;

    public ConfiguracaoService(Transacao transacao, ConfiguracaoDAO configuracaoDAO,
                               FormaPagamentoDAO formaPagamentoDAO, ProdutoDAO produtoDAO, ContaFinanceiraDAO contaDAO,
                               AuditoriaService auditoria) {
        this.contaDAO = contaDAO;
        this.transacao = transacao;
        this.configuracaoDAO = configuracaoDAO;
        this.formaPagamentoDAO = formaPagamentoDAO;
        this.produtoDAO = produtoDAO;
        this.auditoria = auditoria;
    }

    public Configuracoes carregar(Sessao sessao) {
        sessao.exigirSupervisor();
        return transacao.executar(configuracaoDAO::carregar);
    }

    /**
     * Valida e grava os parâmetros (loja, venda, relatórios, balança e backup); só as chaves que mudaram são escritas.
     *
     * @return descrição das mudanças (vazia se nada mudou)
     */
    public List<String> salvar(Sessao sessao, Configuracoes informadas) {
        sessao.exigirSupervisor();
        Configuracoes novas = validar(informadas);
        return transacao.executar(con -> {
            configuracaoDAO.travar(con);
            Configuracoes atuais = configuracaoDAO.carregar(con);
            if (novas.balanca().ativo()) {
                conferirPlus(con, novas.balanca());
            }
            List<String> mudancas = mudancas(atuais, novas);
            if (mudancas.isEmpty()) {
                return mudancas;
            }
            var antes = atuais.paraMapa();
            for (var valor : novas.paraMapa().entrySet()) {
                if (!Objects.equals(antes.get(valor.getKey()), valor.getValue())) {
                    configuracaoDAO.gravar(con, valor.getKey(), valor.getValue());
                }
            }
            auditoria.registrar(con, sessao, AcaoAuditoria.CONFIGURACAO_ALTERADA, ENTIDADE_CONFIGURACAO, null,
                    "Configurações alteradas: " + String.join("; ", mudancas));
            return mudancas;
        });
    }

    // ----- Formas de pagamento -----

    public List<FormaPagamentoCadastro> formasPagamento(Sessao sessao) {
        sessao.exigirSupervisor();
        return transacao.executar(formaPagamentoDAO::listar);
    }

    /** Nova forma, já ativa. Descrição única, sem diferenciar maiúsculas. @return id */
    public int criarForma(Sessao sessao, String descricaoDigitada, TipoFormaPagamento tipo) {
        sessao.exigirSupervisor();
        String descricao = Textos.normalizarEspacos(descricaoDigitada);
        if (descricao.isEmpty()) {
            throw new NegocioException("Informe a descrição da forma de pagamento.");
        }
        if (descricao.length() > TAMANHO_FORMA) {
            throw new NegocioException("A descrição pode ter no máximo " + TAMANHO_FORMA + " caracteres.");
        }
        if (tipo == null) {
            throw new NegocioException("Escolha o tipo da forma de pagamento.");
        }
        if (tipo == TipoFormaPagamento.CREDITO_CLIENTE || tipo == TipoFormaPagamento.CREDIARIO) {
            throw new NegocioException("Só pode existir uma forma do tipo " + tipo.rotulo() + ", e ela já vem "
                    + "cadastrada (\"" + tipo.rotulo() + "\"). Ative-a se estiver desativada.");
        }
        return transacao.executar(con -> {
            formaPagamentoDAO.buscarPorDescricao(con, descricao).ifPresent(f -> {
                throw new NegocioException("Já existe a forma de pagamento \"" + f.descricao() + "\""
                        + (f.ativa() ? "." : ", desativada. Ative-a em vez de criar outra."));
            });
            int id = formaPagamentoDAO.inserir(con, descricao, tipo);
            auditoria.registrar(con, sessao, AcaoAuditoria.CONFIGURACAO_ALTERADA, ENTIDADE_FORMA_PAGAMENTO, (long) id,
                    "Forma de pagamento \"" + descricao + "\" (" + tipo.rotulo() + ") criada");
            return id;
        });
    }

    /**
     * Ativa ou desativa; sempre fica ao menos uma forma ativa além do crédito do cliente e do crediário (sem nenhuma,
     * o PDV não recebe).
     */
    public void alterarSituacaoForma(Sessao sessao, int id, boolean ativa) {
        sessao.exigirSupervisor();
        transacao.executar(con -> {
            List<FormaPagamentoCadastro> formas = formaPagamentoDAO.travar(con);
            FormaPagamentoCadastro forma = formas.stream().filter(f -> f.id() == id).findFirst()
                    .orElseThrow(() -> new NegocioException("Forma de pagamento não encontrada."));
            if (forma.ativa() == ativa) {
                return null;
            }
            // Crédito do cliente e crediário não contam: sem cliente identificado, a venda não teria como ser paga
            // (F12.4 e F14.1).
            if (!ativa && !exigeCliente(forma.tipo()) && formas.stream()
                    .filter(f -> f.ativa() && !exigeCliente(f.tipo())).count() == 1) {
                throw new NegocioException("\"" + forma.descricao() + "\" é a única forma de pagamento ativa. Ative "
                        + "outra antes de desativar esta.");
            }
            formaPagamentoDAO.alterarSituacao(con, id, ativa);
            auditoria.registrar(con, sessao, AcaoAuditoria.CONFIGURACAO_ALTERADA, ENTIDADE_FORMA_PAGAMENTO, (long) id,
                    "Forma de pagamento \"" + forma.descricao() + "\" " + (ativa ? "ativada" : "desativada"));
            return null;
        });
    }

    /** Contas ativas, para a conta de destino da forma. */
    public List<ContaFinanceira> contasAtivas(Sessao sessao) {
        sessao.exigirSupervisor();
        return transacao.executar(con -> contaDAO.listar(con).stream().filter(ContaFinanceira::ativa).toList());
    }

    /**
     * Como a forma recebe (F15.2): taxa de 0 a 20%, de 0 a 60 dias corridos e a conta de destino. Sem conta, o pagamento
     * não gera recebível. Vale para os próximos pagamentos; os recebíveis já gerados não mudam.
     */
    public void alterarRecebimentoForma(Sessao sessao, int id, BigDecimal taxaPct, int dias, Long contaId) {
        sessao.exigirSupervisor();
        if (taxaPct == null || taxaPct.signum() < 0 || taxaPct.compareTo(TAXA_MAXIMA) > 0) {
            throw new NegocioException("A taxa deve estar entre 0 e 20%.");
        }
        if (ValidacaoProduto.casas(taxaPct) > Dinheiro.ESCALA_PERCENTUAL) {
            throw new NegocioException("A taxa pode ter no máximo " + Dinheiro.ESCALA_PERCENTUAL + " casas decimais.");
        }
        if (dias < 0 || dias > DIAS_RECEBIMENTO_MAXIMO) {
            throw new NegocioException("Os dias para receber devem estar entre 0 e " + DIAS_RECEBIMENTO_MAXIMO + ".");
        }
        transacao.executar(con -> {
            FormaPagamentoCadastro forma = formaPagamentoDAO.buscar(con, id)
                    .orElseThrow(() -> new NegocioException("Forma de pagamento não encontrada."));
            if (contaId != null && !forma.aceitaContaDestino()) {
                throw new NegocioException("\"" + forma.descricao() + "\" não tem conta de destino: o dinheiro vai para "
                        + "a gaveta, e o crédito do cliente e o crediário não movimentam dinheiro na venda.");
            }
            String conta = "sem conta";
            if (contaId != null) {
                ContaFinanceira destino = contaDAO.buscar(con, contaId)
                        .orElseThrow(() -> new NegocioException("Conta não encontrada."));
                if (!destino.ativa()) {
                    throw new NegocioException("A conta \"" + destino.nome() + "\" está inativa.");
                }
                conta = "conta \"" + destino.nome() + "\"";
            }
            formaPagamentoDAO.alterarRecebimento(con, id, taxaPct, dias, contaId);
            auditoria.registrar(con, sessao, AcaoAuditoria.CONFIGURACAO_ALTERADA, ENTIDADE_FORMA_PAGAMENTO, (long) id,
                    "Forma de pagamento \"" + forma.descricao() + "\": taxa " + Formatos.percentual(taxaPct) + ", "
                            + dias + " dia(s) para receber, " + conta);
            return null;
        });
    }

    /**
     * Meio de pagamento da forma na NFC-e (tPag, F22.1), um dos códigos de {@link MeioPagamentoNfce}. Vale para as
     * notas que forem emitidas; não muda nada nas vendas.
     */
    public void alterarTpagForma(Sessao sessao, int id, String tpag) {
        sessao.exigirSupervisor();
        MeioPagamentoNfce meio = MeioPagamentoNfce.doCodigo(tpag == null ? null : tpag.strip())
                .orElseThrow(() -> new NegocioException("Escolha o meio de pagamento da NFC-e na lista."));
        transacao.executar(con -> {
            FormaPagamentoCadastro forma = formaPagamentoDAO.buscar(con, id)
                    .orElseThrow(() -> new NegocioException("Forma de pagamento não encontrada."));
            if (forma.tpag().equals(meio.codigo())) {
                return null;
            }
            formaPagamentoDAO.alterarTpag(con, id, meio.codigo());
            auditoria.registrar(con, sessao, AcaoAuditoria.CONFIGURACAO_ALTERADA, ENTIDADE_FORMA_PAGAMENTO, (long) id,
                    "Forma de pagamento \"" + forma.descricao() + "\": meio de pagamento da NFC-e de "
                            + MeioPagamentoNfce.rotulo(forma.tpag()) + " para " + meio);
            return null;
        });
    }

    private static boolean exigeCliente(TipoFormaPagamento tipo) {
        return tipo == TipoFormaPagamento.CREDITO_CLIENTE || tipo == TipoFormaPagamento.CREDIARIO;
    }

    // ----- Validação -----

    /** Normaliza e valida o que vem da tela. */
    static Configuracoes validar(Configuracoes c) {
        Configuracoes.Loja loja = c.loja();
        String nome = Textos.normalizarEspacos(loja.nome());
        if (nome.isEmpty()) {
            throw new NegocioException("Informe o nome da loja.");
        }
        limite(nome, TAMANHO_NOME_LOJA, "O nome da loja");
        String documento = ValidacaoFornecedor.documento(loja.documento());
        String telefone = ValidacaoFornecedor.telefone(loja.telefone());
        String endereco = limite(Textos.normalizarEspacos(loja.endereco()), TAMANHO_ENDERECO, "O endereço");
        String rodape = limite(Textos.normalizarEspacos(c.rodapeComprovante()), TAMANHO_RODAPE,
                "A mensagem do comprovante");

        ParametrosVenda venda = c.venda();
        BigDecimal desconto = percentual(venda.descontoMaximoPct(), "O desconto máximo do operador", true);
        BigDecimal margem = percentual(c.margemMinimaPct(), "A margem mínima", false);
        if (c.diasSemGiro() < 1 || c.diasSemGiro() > Configuracoes.DIAS_SEM_GIRO_MAXIMO) {
            throw new NegocioException("Os dias sem giro devem estar entre 1 e " + Configuracoes.DIAS_SEM_GIRO_MAXIMO
                    + ".");
        }
        Configuracoes.Balanca balanca = c.balanca();
        if (balanca.prefixo() < 0 || balanca.prefixo() > 9) {
            throw new NegocioException("O prefixo da etiqueta de balança é um dígito, de 0 a 9 (normalmente 2).");
        }
        if (balanca.digitosCodigo() < Configuracoes.DIGITOS_PLU_MINIMO
                || balanca.digitosCodigo() > Configuracoes.DIGITOS_PLU_MAXIMO) {
            throw new NegocioException("Os dígitos do código (PLU) na etiqueta devem ser 4, 5 ou 6.");
        }
        if (balanca.tipoValor() == null) {
            throw new NegocioException("Escolha o que a etiqueta de balança traz: preço total ou peso.");
        }
        balanca = new Configuracoes.Balanca(balanca.ativo(), balanca.prefixo(), balanca.digitosCodigo(),
                balanca.tipoValor(), pastaCarga(balanca.pastaCarga()));
        Configuracoes.Backup backup = backup(c.backup());
        if (c.inatividadeMinutos() < 0 || c.inatividadeMinutos() > Configuracoes.INATIVIDADE_MAXIMA) {
            throw new NegocioException("O bloqueio por inatividade deve estar entre 0 (desligado) e "
                    + Configuracoes.INATIVIDADE_MAXIMA + " minutos.");
        }
        int tamanhoSenha = c.politicaSenha().tamanhoMinimo();
        if (tamanhoSenha < PoliticaSenha.TAMANHO_MINIMO || tamanhoSenha > PoliticaSenha.TAMANHO_MAXIMO) {
            throw new NegocioException("O tamanho mínimo da senha deve estar entre " + PoliticaSenha.TAMANHO_MINIMO
                    + " e " + PoliticaSenha.TAMANHO_MAXIMO + " caracteres.");
        }
        Configuracoes.Fidelidade fidelidade = fidelidade(c.fidelidade());
        if (c.devolucaoPrazoDias() < 0 || c.devolucaoPrazoDias() > Configuracoes.DEVOLUCAO_PRAZO_MAXIMO) {
            throw new NegocioException("O prazo de devolução deve estar entre 0 (sempre pede liberação) e "
                    + Configuracoes.DEVOLUCAO_PRAZO_MAXIMO + " dias.");
        }
        Configuracoes.Crediario crediario = crediario(c.crediario());
        Configuracoes.Compras compras = compras(c.compras());
        if (c.validadeAlertaDias() < 0 || c.validadeAlertaDias() > Configuracoes.VALIDADE_ALERTA_MAXIMO) {
            throw new NegocioException("O alerta de validade deve estar entre 0 (só no dia do vencimento) e "
                    + Configuracoes.VALIDADE_ALERTA_MAXIMO + " dias.");
        }
        String uf = ValidacaoFiscal.uf(loja.uf());
        String ie = ValidacaoFiscal.ie(loja.ie());
        return new Configuracoes(new Configuracoes.Loja(nome, documento == null ? "" : documento, endereco,
                telefone == null ? "" : telefone, loja.regime(), uf, ie),
                new ParametrosVenda(venda.permitirEstoqueNegativo(), desconto, venda.cancelarItemExigeSupervisor()),
                margem, c.diasSemGiro(), balanca, backup, rodape, c.seguranca(), fidelidade, c.devolucaoPrazoDias(),
                crediario, c.integrarCaixa(), compras, c.validadeAlertaDias(), etiquetas(c.etiquetas()),
                c.assistente() == null ? Configuracoes.Assistente.PADRAO : c.assistente(), tributos(c.tributos()));
    }

    /** Tributos da venda (F22.2, seção 0.4): alíquotas do IBS e da CBS de 0 a 100%, com até 2 casas. */
    static Configuracoes.Tributos tributos(Configuracoes.Tributos t) {
        return new Configuracoes.Tributos(percentual(t.aliquotaCbs(), "A alíquota da CBS", true),
                percentual(t.aliquotaIbsUf(), "A alíquota do IBS da UF", true),
                percentual(t.aliquotaIbsMun(), "A alíquota do IBS do município", true), t.excluirIcmsBasePisCofins());
    }


    /** Etiquetas (F18.1): de 1 a 6 colunas e 1 a 20 linhas; impressora até 200 e modelo até 10.000 caracteres. */
    static Configuracoes.Etiquetas etiquetas(Configuracoes.Etiquetas e) {
        if (e.colunas() < 1 || e.colunas() > Configuracoes.Etiquetas.COLUNAS_MAXIMO) {
            throw new NegocioException("As etiquetas por linha devem estar entre 1 e "
                    + Configuracoes.Etiquetas.COLUNAS_MAXIMO + ".");
        }
        if (e.linhas() < 1 || e.linhas() > Configuracoes.Etiquetas.LINHAS_MAXIMO) {
            throw new NegocioException("As linhas de etiquetas por folha devem estar entre 1 e "
                    + Configuracoes.Etiquetas.LINHAS_MAXIMO + ".");
        }
        String impressora = e.impressoraTermica() == null ? "" : e.impressoraTermica().strip();
        String modelo = e.modeloTermica() == null ? "" : e.modeloTermica().strip();
        limite(impressora, TAMANHO_IMPRESSORA, "O nome da impressora térmica");
        limite(modelo, TAMANHO_MODELO, "O modelo da etiqueta térmica");
        return new Configuracoes.Etiquetas(e.colunas(), e.linhas(), modelo, impressora);
    }
    /** Sugestão de compra (F16.2, seção 0.4): média de 1 a 365 dias; cobertura de 0 a 365 dias. */
    static Configuracoes.Compras compras(Configuracoes.Compras c) {
        if (c.diasMedia() < 1 || c.diasMedia() > Configuracoes.Compras.DIAS_MAXIMO) {
            throw new NegocioException("Os dias da média de venda devem estar entre 1 e "
                    + Configuracoes.Compras.DIAS_MAXIMO + ".");
        }
        if (c.coberturaDias() < 0 || c.coberturaDias() > Configuracoes.Compras.DIAS_MAXIMO) {
            throw new NegocioException("A cobertura deve estar entre 0 e " + Configuracoes.Compras.DIAS_MAXIMO
                    + " dias.");
        }
        return c;
    }

    /** Crediário (F14, seção 0.4): de 1 a 60 parcelas; dias de 0 a 10 anos; juros e multa de 0 a 100%. */
    static Configuracoes.Crediario crediario(Configuracoes.Crediario c) {
        if (c.maxParcelas() < 1 || c.maxParcelas() > Configuracoes.Crediario.MAX_PARCELAS) {
            throw new NegocioException("O máximo de parcelas deve estar entre 1 e "
                    + Configuracoes.Crediario.MAX_PARCELAS + ".");
        }
        conferirDias(c.diasPrimeira(), "Os dias até a 1ª parcela");
        conferirDias(c.carenciaDias(), "A carência");
        conferirDias(c.bloqueioAtrasoDias(), "O atraso que pede liberação");
        return new Configuracoes.Crediario(c.maxParcelas(), c.diasPrimeira(),
                percentual(c.jurosMesPct(), "Os juros ao mês", true), percentual(c.multaPct(), "A multa por atraso", true),
                c.carenciaDias(), c.bloqueioAtrasoDias());
    }

    private static void conferirDias(int dias, String rotulo) {
        if (dias < 0 || dias > Configuracoes.Crediario.DIAS_MAXIMO) {
            throw new NegocioException(rotulo + " deve ficar entre 0 e " + Configuracoes.Crediario.DIAS_MAXIMO
                    + " dias.");
        }
    }

    /** Fidelidade (F12.5): cashback de 0 a 100% (maior que zero com ela ligada) e compra mínima ≥ 0, em reais. */
    static Configuracoes.Fidelidade fidelidade(Configuracoes.Fidelidade f) {
        BigDecimal pct = percentual(f.cashbackPct(), "O percentual de cashback", true);
        if (f.ativo() && pct.signum() == 0) {
            throw new NegocioException("Com a fidelidade ligada, o percentual de cashback deve ser maior que zero.");
        }
        if (f.compraMinima() != null && f.compraMinima().signum() < 0) {
            throw new NegocioException("A compra mínima para o cashback não pode ser negativa.");
        }
        BigDecimal minima = CaixaService.valor(f.compraMinima(), "a compra mínima para o cashback", true);
        return new Configuracoes.Fidelidade(f.ativo(), pct, minima);
    }

    /**
     * Backup (6.12): pasta vazia = padrão (~/.varesync/backups), senão um caminho completo que não seja um arquivo;
     * pg_dump vazio = detectar, senão o arquivo (ou a pasta bin) que existe; manter de 1 a 999.
     */
    /** Pasta da carga de balança (F18.2): vazia ou um caminho completo que não seja um arquivo; até 500 caracteres. */
    static String pastaCarga(String valor) {
        String pasta = valor == null ? "" : valor.strip();
        if (pasta.isEmpty()) {
            return pasta;
        }
        limite(pasta, TAMANHO_PASTA_CARGA, "A pasta da carga de balança");
        Path caminho;
        try {
            caminho = Path.of(pasta);
        } catch (InvalidPathException e) {
            throw new NegocioException("A pasta da carga de balança não é um caminho válido: " + pasta + ".");
        }
        if (!caminho.isAbsolute()) {
            throw new NegocioException("Informe o caminho completo da pasta da carga de balança (ex.: C:\\MGV6\\Itens).");
        }
        if (Files.isRegularFile(caminho)) {
            throw new NegocioException("O caminho da carga de balança é um arquivo, não uma pasta: " + pasta + ".");
        }
        return pasta;
    }

    static Configuracoes.Backup backup(Configuracoes.Backup b) {
        String pasta = b.pasta() == null ? "" : b.pasta().strip();
        if (!pasta.isEmpty()) {
            Path caminho;
            try {
                caminho = Path.of(pasta);
            } catch (InvalidPathException e) {
                throw new NegocioException("A pasta de backup não é um caminho válido: " + pasta + ".");
            }
            if (!caminho.isAbsolute()) {
                throw new NegocioException("Informe o caminho completo da pasta de backup (ex.: D:\\Backups\\VareSync).");
            }
            if (Files.isRegularFile(caminho)) {
                throw new NegocioException("O caminho de backup é um arquivo, não uma pasta: " + pasta + ".");
            }
        }
        String pgDump = b.pgDump() == null ? "" : b.pgDump().strip();
        if (!pgDump.isEmpty()) {
            boolean existe;
            try {
                existe = Files.exists(Path.of(pgDump));
            } catch (InvalidPathException e) {
                existe = false;
            }
            if (!existe) {
                throw new NegocioException("O pg_dump informado não existe: " + pgDump + ". Deixe vazio para o sistema "
                        + "procurar sozinho.");
            }
        }
        if (b.manterUltimos() < 1 || b.manterUltimos() > 999) {
            throw new NegocioException("A quantidade de backups mantidos deve estar entre 1 e 999.");
        }
        return new Configuracoes.Backup(pasta, pgDump, b.manterUltimos());
    }

    private static BigDecimal percentual(BigDecimal valor, String rotulo, boolean aceitaCem) {
        if (valor == null) {
            throw new NegocioException("Informe " + rotulo.substring(0, 1).toLowerCase() + rotulo.substring(1) + ".");
        }
        if (valor.signum() < 0 || (aceitaCem ? valor.compareTo(CEM) > 0 : valor.compareTo(CEM) >= 0)) {
            throw new NegocioException(rotulo + " deve estar entre 0 e " + (aceitaCem ? "100" : "99,99") + "%.");
        }
        if (ValidacaoProduto.casas(valor) > Dinheiro.ESCALA_PERCENTUAL) {
            throw new NegocioException(rotulo + " pode ter no máximo " + Dinheiro.ESCALA_PERCENTUAL
                    + " casas decimais.");
        }
        return valor;
    }

    private static String limite(String texto, int tamanho, String rotulo) {
        if (texto.length() > tamanho) {
            throw new NegocioException(rotulo + " pode ter no máximo " + tamanho + " caracteres.");
        }
        return texto;
    }

    /** Com menos dígitos de código, um PLU já cadastrado poderia não caber na etiqueta. */
    private void conferirPlus(Connection con, Configuracoes.Balanca balanca) throws java.sql.SQLException {
        List<ProdutoDAO.Dono> fora = produtoDAO.comPluAcima(con, balanca.pluMaximo());
        if (!fora.isEmpty()) {
            String nomes = fora.stream().limit(5).map(p -> ProdutoService.descrever(p.id(), p.nome()))
                    .collect(Collectors.joining(", "));
            throw new NegocioException("Com " + balanca.digitosCodigo() + " dígitos de código, o PLU vai até "
                    + balanca.pluMaximo() + ", mas " + (fora.size() == 1 ? "1 produto tem" : fora.size()
                    + " produtos têm") + " PLU maior: " + nomes + (fora.size() > 5 ? " e mais " + (fora.size() - 5)
                    : "") + ". Altere o PLU desses produtos antes.");
        }
    }

    // ----- Auditoria -----

    static List<String> mudancas(Configuracoes a, Configuracoes n) {
        List<String> m = new ArrayList<>();
        comparar(m, "nome da loja", texto(a.loja().nome()), texto(n.loja().nome()));
        comparar(m, "CNPJ/CPF da loja", documento(a.loja().documento()), documento(n.loja().documento()));
        comparar(m, "endereço", texto(a.loja().endereco()), texto(n.loja().endereco()));
        comparar(m, "telefone", telefone(a.loja().telefone()), telefone(n.loja().telefone()));
        comparar(m, "regime tributário", a.loja().regime().rotulo(), n.loja().regime().rotulo());
        comparar(m, "UF da loja", texto(a.loja().uf()), texto(n.loja().uf()));
        comparar(m, "inscrição estadual", texto(a.loja().ie()), texto(n.loja().ie()));
        comparar(m, "mensagem do comprovante", texto(a.rodapeComprovante()), texto(n.rodapeComprovante()));
        comparar(m, "venda sem estoque", estoqueNegativo(a.venda().permitirEstoqueNegativo()),
                estoqueNegativo(n.venda().permitirEstoqueNegativo()));
        comparar(m, "desconto máximo do operador", Formatos.percentual(a.venda().descontoMaximoPct()),
                Formatos.percentual(n.venda().descontoMaximoPct()));
        comparar(m, "cancelar item no PDV", cancelarItem(a.venda().cancelarItemExigeSupervisor()),
                cancelarItem(n.venda().cancelarItemExigeSupervisor()));
        comparar(m, "margem mínima", Formatos.percentual(a.margemMinimaPct()), Formatos.percentual(n.margemMinimaPct()));
        comparar(m, "dias sem giro", String.valueOf(a.diasSemGiro()), String.valueOf(n.diasSemGiro()));
        comparar(m, "etiqueta de balança", a.balanca().ativo() ? "lida no PDV" : "desligada",
                n.balanca().ativo() ? "lida no PDV" : "desligada");
        comparar(m, "prefixo da etiqueta", String.valueOf(a.balanca().prefixo()), String.valueOf(n.balanca().prefixo()));
        comparar(m, "dígitos do PLU na etiqueta", String.valueOf(a.balanca().digitosCodigo()),
                String.valueOf(n.balanca().digitosCodigo()));
        comparar(m, "valor da etiqueta", a.balanca().tipoValor().rotulo().toLowerCase(),
                n.balanca().tipoValor().rotulo().toLowerCase());
        comparar(m, "pasta da carga de balança", texto(a.balanca().pastaCarga()), texto(n.balanca().pastaCarga()));
        comparar(m, "pasta de backup", caminho(a.backup().pasta()), caminho(n.backup().pasta()));
        comparar(m, "pg_dump", caminho(a.backup().pgDump()), caminho(n.backup().pgDump()));
        comparar(m, "backups mantidos", String.valueOf(a.backup().manterUltimos()),
                String.valueOf(n.backup().manterUltimos()));
        comparar(m, "bloqueio por inatividade", inatividade(a.inatividadeMinutos()), inatividade(n.inatividadeMinutos()));
        comparar(m, "tamanho mínimo da senha", a.politicaSenha().tamanhoMinimo() + " caracteres",
                n.politicaSenha().tamanhoMinimo() + " caracteres");
        comparar(m, "senha com letras e números", a.politicaSenha().letraENumero() ? "exigida" : "não exigida",
                n.politicaSenha().letraENumero() ? "exigida" : "não exigida");
        comparar(m, "cashback", a.fidelidade().ativo() ? "ligado" : "desligado",
                n.fidelidade().ativo() ? "ligado" : "desligado");
        comparar(m, "percentual de cashback", Formatos.percentual(a.fidelidade().cashbackPct()),
                Formatos.percentual(n.fidelidade().cashbackPct()));
        comparar(m, "compra mínima para o cashback", Formatos.moeda(a.fidelidade().compraMinima()),
                Formatos.moeda(n.fidelidade().compraMinima()));
        comparar(m, "prazo de devolução", a.devolucaoPrazoDias() + " dias", n.devolucaoPrazoDias() + " dias");
        comparar(m, "máximo de parcelas do crediário", String.valueOf(a.crediario().maxParcelas()),
                String.valueOf(n.crediario().maxParcelas()));
        comparar(m, "dias até a 1ª parcela", a.crediario().diasPrimeira() + " dias",
                n.crediario().diasPrimeira() + " dias");
        comparar(m, "juros do crediário", Formatos.percentual(a.crediario().jurosMesPct()),
                Formatos.percentual(n.crediario().jurosMesPct()));
        comparar(m, "multa do crediário", Formatos.percentual(a.crediario().multaPct()),
                Formatos.percentual(n.crediario().multaPct()));
        comparar(m, "carência do crediário", a.crediario().carenciaDias() + " dias",
                n.crediario().carenciaDias() + " dias");
        comparar(m, "atraso que pede liberação", a.crediario().bloqueioAtrasoDias() + " dias",
                n.crediario().bloqueioAtrasoDias() + " dias");
        comparar(m, "integração do caixa com as contas", a.integrarCaixa() ? "ligada" : "desligada",
                n.integrarCaixa() ? "ligada" : "desligada");
        comparar(m, "dias da média de venda", a.compras().diasMedia() + " dias", n.compras().diasMedia() + " dias");
        comparar(m, "cobertura da sugestão de compra", a.compras().coberturaDias() + " dias",
                n.compras().coberturaDias() + " dias");
        comparar(m, "alerta de validade dos lotes", a.validadeAlertaDias() + " dias", n.validadeAlertaDias() + " dias");
        comparar(m, "etiquetas por linha na folha", String.valueOf(a.etiquetas().colunas()),
                String.valueOf(n.etiquetas().colunas()));
        comparar(m, "linhas de etiquetas na folha", String.valueOf(a.etiquetas().linhas()),
                String.valueOf(n.etiquetas().linhas()));
        comparar(m, "impressora térmica", texto(a.etiquetas().impressoraTermica()),
                texto(n.etiquetas().impressoraTermica()));
        comparar(m, "formas de pagamento revisadas (primeiros passos)", a.assistente().formasRevisadas() ? "sim"
                : "não", n.assistente().formasRevisadas() ? "sim" : "não");
        comparar(m, "só eu uso o sistema (primeiros passos)", a.assistente().usuariosDispensado() ? "sim" : "não",
                n.assistente().usuariosDispensado() ? "sim" : "não");
        comparar(m, "não uso balança (primeiros passos)", a.assistente().balancaDispensada() ? "sim" : "não",
                n.assistente().balancaDispensada() ? "sim" : "não");
        comparar(m, "alíquota da CBS", Formatos.percentual(a.tributos().aliquotaCbs()),
                Formatos.percentual(n.tributos().aliquotaCbs()));
        comparar(m, "alíquota do IBS da UF", Formatos.percentual(a.tributos().aliquotaIbsUf()),
                Formatos.percentual(n.tributos().aliquotaIbsUf()));
        comparar(m, "alíquota do IBS do município", Formatos.percentual(a.tributos().aliquotaIbsMun()),
                Formatos.percentual(n.tributos().aliquotaIbsMun()));
        comparar(m, "ICMS na base do PIS e da COFINS", a.tributos().excluirIcmsBasePisCofins() ? "excluído"
                : "incluído", n.tributos().excluirIcmsBasePisCofins() ? "excluído" : "incluído");
        if (!a.etiquetas().modeloTermica().equals(n.etiquetas().modeloTermica())) {
            m.add(n.etiquetas().modeloTermica().isEmpty() ? "modelo da impressora térmica apagado"
                    : "modelo da impressora térmica alterado (" + n.etiquetas().modeloTermica().length()
                    + " caracteres)");
        }
        return m;
    }

    private static String inatividade(int minutos) {
        return minutos == 0 ? "desligado" : minutos + " min";
    }

    private static String caminho(String valor) {
        return valor.isEmpty() ? "(padrão)" : "\"" + valor + "\"";
    }

    private static void comparar(List<String> mudancas, String rotulo, String antes, String depois) {
        if (!antes.equals(depois)) {
            mudancas.add(rotulo + " de " + antes + " para " + depois);
        }
    }

    private static String texto(String valor) {
        return valor.isEmpty() ? "(vazio)" : "\"" + valor + "\"";
    }

    private static String documento(String valor) {
        return valor.isEmpty() ? "(vazio)" : CpfCnpj.formatar(valor);
    }

    private static String telefone(String valor) {
        return valor.isEmpty() ? "(vazio)" : Formatos.telefone(valor);
    }

    private static String estoqueNegativo(boolean permite) {
        return permite ? "\"permite e avisa\"" : "\"bloqueia\"";
    }

    private static String cancelarItem(boolean exige) {
        return exige ? "\"pede liberação\"" : "\"livre\"";
    }
}
