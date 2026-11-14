package br.com.varesync.service;

import br.com.varesync.dao.CategoriaDAO;
import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.FiscalDAO;
import br.com.varesync.dao.PerfilTributarioDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.AlvoPerfil;
import br.com.varesync.model.Categoria;
import br.com.varesync.model.CodigoCest;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.DadosFiscais;
import br.com.varesync.model.DadosPerfil;
import br.com.varesync.model.OrigemMercadoria;
import br.com.varesync.model.PerfilTributario;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.ProdutoFiscal;
import br.com.varesync.model.SugestaoFiscal;
import br.com.varesync.util.Textos;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cadastro fiscal (F19.2 e F19.3), com a permissão FISCAL: perfis tributários, dados fiscais do produto (NCM, CEST,
 * origem e perfil), aplicação do perfil em lote, importação das tabelas NCM e CEST e o preenchimento dos campos vazios
 * pela NF-e. Só o formato de cada campo é conferido ({@link ValidacaoFiscal}); com a tabela NCM (ou CEST) importada,
 * o código também tem de existir nela. As combinações erradas são do R30 (F19.4).
 */
public class FiscalService {

    static final String ENTIDADE_PERFIL = "perfil_tributario";
    static final String ENTIDADE_TABELA = "tabela_fiscal";
    /** Quantos nomes de produto a auditoria da aplicação em lote mostra. */
    private static final int NOMES_NA_AUDITORIA = 10;

    private final Transacao transacao;
    private final PerfilTributarioDAO perfilDAO;
    private final FiscalDAO fiscalDAO;
    private final CategoriaDAO categoriaDAO;
    private final ImportacaoNfeService nfe;
    private final ConfiguracaoDAO configuracaoDAO;
    private final AuditoriaService auditoria;
    private final Clock clock;

    /** Situação das tabelas de apoio: quantos códigos NCM e CEST estão importados (0 = só o formato é conferido). */
    public record Tabelas(long ncm, long cest) {
    }

    /** Resultado da importação de uma tabela. */
    public record Importacao(String tabela, int linhas, int pulados, String formato) {
    }

    /** Produtos do alvo da aplicação em lote. */
    public record Previa(long produtos, long jaComOPerfil) {

        public long mudam() {
            return produtos - jaComOPerfil;
        }
    }

    public FiscalService(Transacao transacao, PerfilTributarioDAO perfilDAO, FiscalDAO fiscalDAO,
                         CategoriaDAO categoriaDAO, ImportacaoNfeService nfe, ConfiguracaoDAO configuracaoDAO,
                         AuditoriaService auditoria, Clock clock) {
        this.transacao = transacao;
        this.perfilDAO = perfilDAO;
        this.fiscalDAO = fiscalDAO;
        this.categoriaDAO = categoriaDAO;
        this.nfe = nfe;
        this.configuracaoDAO = configuracaoDAO;
        this.auditoria = auditoria;
        this.clock = clock;
    }

    /** Regime, UF e IE da loja (F19.1), para o cabeçalho e o tipo de situação do ICMS de um perfil novo. */
    public Configuracoes.Loja empresa(Sessao sessao) {
        sessao.exigir(Permissao.FISCAL);
        return transacao.executar(con -> configuracaoDAO.carregar(con).loja());
    }

    /**
     * Categorias (ativas e inativas) para o filtro e a aplicação em lote, pela permissão FISCAL: quem tem só FISCAL
     * abre o Cadastro fiscal pelo menu (F19.5) sem a permissão PRODUTOS, que o {@code CategoriaService} pede.
     */
    public List<Categoria> categorias(Sessao sessao) {
        sessao.exigir(Permissao.FISCAL);
        return transacao.executar(con -> categoriaDAO.listar(con, true));
    }

    // ----- Perfis tributários -----

    public List<PerfilTributario> perfis(Sessao sessao, boolean soAtivos) {
        sessao.exigir(Permissao.FISCAL);
        return transacao.executar(con -> perfilDAO.listar(con, soAtivos));
    }

    /**
     * Cria ({@code id} nulo) ou altera o perfil. Só o formato de cada campo é conferido.
     *
     * @return id do perfil
     */
    public long salvarPerfil(Sessao sessao, Long id, DadosPerfil dados) {
        sessao.exigir(Permissao.FISCAL);
        PerfilTributarioDAO.Gravacao g = validar(dados);
        return transacao.executar(con -> {
            perfilDAO.mesmoNome(con, g.nome(), id).ifPresent(p -> {
                throw new NegocioException("Já existe o perfil tributário \"" + p.nome() + "\""
                        + (p.ativo() ? "." : ", inativo. Ative-o em vez de criar outro."));
            });
            OffsetDateTime agora = OffsetDateTime.now(clock);
            if (id == null) {
                long novo = perfilDAO.inserir(con, g, sessao.usuarioId(), agora);
                PerfilTributario p = perfilDAO.buscar(con, novo).orElseThrow();
                auditoria.registrar(con, sessao, AcaoAuditoria.PERFIL_TRIBUTARIO_ALTERADO, ENTIDADE_PERFIL, novo,
                        "Perfil tributário \"" + p.nome() + "\" criado: " + descrever(p));
                return novo;
            }
            PerfilTributario antes = perfilDAO.travar(con, id)
                    .orElseThrow(() -> new NegocioException("Perfil tributário não encontrado."));
            perfilDAO.alterar(con, id, g, sessao.usuarioId(), agora);
            PerfilTributario depois = perfilDAO.buscar(con, id).orElseThrow();
            List<String> mudancas = mudancas(antes, depois);
            if (!mudancas.isEmpty()) {
                auditoria.registrar(con, sessao, AcaoAuditoria.PERFIL_TRIBUTARIO_ALTERADO, ENTIDADE_PERFIL, id,
                        "Perfil tributário \"" + depois.nome() + "\" alterado: " + String.join("; ", mudancas));
            }
            return id;
        });
    }

    /** Exclui o perfil que nenhum produto usa; em uso, a tela oferece inativar. */
    public void excluirPerfil(Sessao sessao, long id) {
        sessao.exigir(Permissao.FISCAL);
        transacao.executar(con -> {
            PerfilTributario p = perfilDAO.travar(con, id)
                    .orElseThrow(() -> new NegocioException("Perfil tributário não encontrado."));
            if (p.produtos() > 0) {
                throw new RegistroEmUsoException("O perfil \"" + p.nome() + "\" está em " + (p.produtos() == 1
                        ? "1 produto" : p.produtos() + " produtos") + " e não pode ser excluído. Inative-o: ele "
                        + "continua nesses produtos, mas não é mais oferecido.");
            }
            perfilDAO.excluir(con, id);
            auditoria.registrar(con, sessao, AcaoAuditoria.PERFIL_TRIBUTARIO_ALTERADO, ENTIDADE_PERFIL, id,
                    "Perfil tributário \"" + p.nome() + "\" excluído");
            return null;
        });
    }

    static PerfilTributarioDAO.Gravacao validar(DadosPerfil d) {
        String nome = ValidacaoFiscal.nomePerfil(d.nome());
        String cfop = ValidacaoFiscal.cfop(d.cfop());
        String cst = d.usaCsosn() ? null : ValidacaoFiscal.cstIcms(d.situacaoIcms());
        String csosn = d.usaCsosn() ? ValidacaoFiscal.csosn(d.situacaoIcms()) : null;
        return new PerfilTributarioDAO.Gravacao(nome, cfop, cst, csosn,
                ValidacaoFiscal.aliquota(d.aliquotaIcms(), "do ICMS"), ValidacaoFiscal.reducaoBase(d.reducaoBaseIcms()),
                ValidacaoFiscal.cstPisCofins(d.cstPis(), "PIS"), ValidacaoFiscal.aliquota(d.aliquotaPis(), "do PIS"),
                ValidacaoFiscal.cstPisCofins(d.cstCofins(), "COFINS"),
                ValidacaoFiscal.aliquota(d.aliquotaCofins(), "da COFINS"),
                ValidacaoFiscal.codigoBeneficio(d.codigoBeneficio()), ValidacaoFiscal.cstIbsCbs(d.cstIbsCbs()),
                ValidacaoFiscal.cclassTrib(d.cclassTrib()), d.ativo(),
                ValidacaoFiscal.aliquota(d.aliquotaFcp(), "do FCP"),
                ValidacaoFiscal.reducaoAliquotaIbsCbs(d.reducaoAliquotaIbsCbs()));
    }

    /** Todos os campos, para a auditoria da criação. */
    private static String descrever(PerfilTributario p) {
        return p.resumo() + " · PIS " + br.com.varesync.util.Formatos.percentual(p.aliquotaPis()) + " · COFINS "
                + br.com.varesync.util.Formatos.percentual(p.aliquotaCofins())
                + (p.codigoBeneficio() == null ? "" : " · cBenef " + p.codigoBeneficio())
                + (p.cstIbsCbs() == null ? "" : " · CST IBS/CBS " + p.cstIbsCbs())
                + (p.cclassTrib() == null ? "" : " · cClassTrib " + p.cclassTrib())
                + (p.reducaoAliquotaIbsCbs() == null ? "" : " · redução do IBS e da CBS "
                + br.com.varesync.util.Formatos.percentual(p.reducaoAliquotaIbsCbs()))
                + (p.ativo() ? "" : " · inativo");
    }

    static List<String> mudancas(PerfilTributario a, PerfilTributario n) {
        List<String> m = new ArrayList<>();
        comparar(m, "nome", a.nome(), n.nome());
        comparar(m, "CFOP", a.cfop(), n.cfop());
        comparar(m, "situação do ICMS", a.situacaoIcms(), n.situacaoIcms());
        comparar(m, "alíquota do ICMS", pct(a.aliquotaIcms()), pct(n.aliquotaIcms()));
        comparar(m, "redução da base", pct(a.reducaoBaseIcms()), pct(n.reducaoBaseIcms()));
        comparar(m, "CST do PIS", a.cstPis(), n.cstPis());
        comparar(m, "alíquota do PIS", pct(a.aliquotaPis()), pct(n.aliquotaPis()));
        comparar(m, "CST da COFINS", a.cstCofins(), n.cstCofins());
        comparar(m, "alíquota da COFINS", pct(a.aliquotaCofins()), pct(n.aliquotaCofins()));
        comparar(m, "código de benefício", a.codigoBeneficio(), n.codigoBeneficio());
        comparar(m, "CST do IBS e da CBS", a.cstIbsCbs(), n.cstIbsCbs());
        comparar(m, "cClassTrib", a.cclassTrib(), n.cclassTrib());
        comparar(m, "alíquota do FCP", pct(a.aliquotaFcp()), pct(n.aliquotaFcp()));
        comparar(m, "redução da alíquota do IBS e da CBS", pct(a.reducaoAliquotaIbsCbs()),
                pct(n.reducaoAliquotaIbsCbs()));
        if (a.ativo() != n.ativo()) {
            m.add(n.ativo() ? "ativado" : "inativado");
        }
        return m;
    }

    private static String pct(java.math.BigDecimal valor) {
        return valor == null ? null : br.com.varesync.util.Formatos.percentual(valor);
    }

    private static void comparar(List<String> m, String campo, String antes, String depois) {
        if (!Objects.equals(antes, depois)) {
            m.add(campo + ": " + (antes == null ? "(vazio)" : antes) + " → " + (depois == null ? "(vazio)" : depois));
        }
    }

    // ----- Dados fiscais do produto -----

    /**
     * Produtos para a aba Produtos: texto (nome contém, sem acento) ou só dígitos (código interno ou de barras),
     * categoria, perfil e "só pendentes" (ativos sem NCM ou sem perfil).
     */
    public List<ProdutoFiscal> produtos(Sessao sessao, String texto, Long categoriaId, Long perfilId,
                                        boolean soPendentes, boolean incluirInativos) {
        sessao.exigir(Permissao.FISCAL);
        String termo = Textos.normalizarEspacos(texto);
        FiscalDAO.Filtro filtro = Textos.soDigitos(termo) && !termo.isEmpty()
                ? new FiscalDAO.Filtro(null, termo, categoriaId, perfilId, soPendentes, incluirInativos)
                : new FiscalDAO.Filtro(Textos.normalizarBusca(termo), null, categoriaId, perfilId, soPendentes,
                incluirInativos);
        return transacao.executar(con -> fiscalDAO.listar(con, filtro));
    }

    /** Grava NCM, CEST, origem e perfil do produto. Com a tabela importada, o NCM (e o CEST) tem de existir nela. */
    public void salvarDadosFiscais(Sessao sessao, long produtoId, DadosFiscais dados) {
        sessao.exigir(Permissao.FISCAL);
        String ncm = ValidacaoFiscal.ncm(dados.ncm());
        String cest = ValidacaoFiscal.cest(dados.cest());
        Integer origem = ValidacaoFiscal.origem(dados.origem());
        transacao.executar(con -> {
            ProdutoFiscal antes = fiscalDAO.travar(con, produtoId)
                    .orElseThrow(() -> new NegocioException("Produto não encontrado."));
            conferirNasTabelas(con, ncm, cest);
            String perfilNome = null;
            if (dados.perfilId() != null) {
                PerfilTributario perfil = perfilDAO.buscar(con, dados.perfilId())
                        .orElseThrow(() -> new NegocioException("Perfil tributário não encontrado."));
                if (!perfil.ativo() && !dados.perfilId().equals(antes.perfilId())) {
                    throw new NegocioException("O perfil \"" + perfil.nome() + "\" está inativo. Ative-o ou escolha "
                            + "outro.");
                }
                perfilNome = perfil.nome();
            }
            List<String> m = new ArrayList<>();
            comparar(m, "NCM", formatado(antes.ncm(), true), formatado(ncm, true));
            comparar(m, "CEST", formatado(antes.cest(), false), formatado(cest, false));
            comparar(m, "origem", antes.origem() == null ? null : OrigemMercadoria.rotulo(antes.origem()),
                    origem == null ? null : OrigemMercadoria.rotulo(origem));
            comparar(m, "perfil tributário", antes.perfilNome(), perfilNome);
            if (m.isEmpty()) {
                return null;
            }
            fiscalDAO.gravar(con, produtoId, ncm, cest, origem, dados.perfilId(), sessao.usuarioId(),
                    OffsetDateTime.now(clock));
            auditoria.registrar(con, sessao, AcaoAuditoria.DADOS_FISCAIS_ALTERADOS, AuditoriaService.ENTIDADE_PRODUTO,
                    produtoId, "Dados fiscais de " + ProdutoService.descrever(produtoId, antes.nome()) + ": "
                            + String.join("; ", m));
            return null;
        });
    }

    /** Descrição do NCM na tabela importada; vazia sem a tabela ou se o código não estiver nela. */
    public String descricaoNcm(Sessao sessao, String ncm) {
        sessao.exigir(Permissao.FISCAL);
        String codigo;
        try {
            codigo = ValidacaoFiscal.ncm(ncm);
        } catch (NegocioException e) {
            return "";
        }
        return codigo == null ? "" : transacao.executar(con -> fiscalDAO.descricaoNcm(con, codigo).orElse(""));
    }

    private void conferirNasTabelas(Connection con, String ncm, String cest) throws SQLException {
        if (ncm != null && fiscalDAO.contarNcm(con) > 0 && !fiscalDAO.ncmExiste(con, ncm)) {
            throw new NegocioException("O NCM " + ValidacaoFiscal.formatarNcm(ncm) + " não existe na tabela NCM "
                    + "importada. Confira o código ou importe a tabela atualizada.");
        }
        if (cest != null && fiscalDAO.contarCest(con) > 0 && !fiscalDAO.cestExiste(con, cest)) {
            throw new NegocioException("O CEST " + ValidacaoFiscal.formatarCest(cest) + " não existe na tabela CEST "
                    + "importada. Confira o código ou importe a tabela atualizada.");
        }
    }

    private static String formatado(String codigo, boolean ncm) {
        return codigo == null ? null : ncm ? ValidacaoFiscal.formatarNcm(codigo) : ValidacaoFiscal.formatarCest(codigo);
    }

    // ----- Aplicação em lote -----

    /** Quantos produtos o alvo alcança e quantos já têm o perfil (para a confirmação). */
    public Previa previa(Sessao sessao, long perfilId, AlvoPerfil alvo) {
        sessao.exigir(Permissao.FISCAL);
        AlvoPerfil normalizado = normalizar(alvo);
        return transacao.executar(con -> {
            FiscalDAO.Contagem c = fiscalDAO.contarAlvo(con, normalizado, perfilId);
            return new Previa(c.produtos(), c.jaComOPerfil());
        });
    }

    /**
     * Aplica o perfil ativo a todos os produtos do alvo (ativos e inativos), numa transação e com uma auditoria.
     *
     * @return quantos produtos mudaram de perfil
     */
    public int aplicarPerfil(Sessao sessao, long perfilId, AlvoPerfil alvo) {
        sessao.exigir(Permissao.FISCAL);
        AlvoPerfil normalizado = normalizar(alvo);
        return transacao.executar(con -> {
            PerfilTributario perfil = perfilDAO.travar(con, perfilId)
                    .orElseThrow(() -> new NegocioException("Perfil tributário não encontrado."));
            if (!perfil.ativo()) {
                throw new NegocioException("O perfil \"" + perfil.nome() + "\" está inativo e não pode ser aplicado.");
            }
            FiscalDAO.Contagem c = fiscalDAO.contarAlvo(con, normalizado, perfilId);
            String categoria = normalizado.tipo() == AlvoPerfil.Tipo.CATEGORIA
                    ? fiscalDAO.nomeCategoria(con, normalizado.categoriaId()).orElse("?") : null;
            if (c.produtos() == 0) {
                throw new NegocioException("Nenhum produto " + descrever(normalizado, categoria) + ".");
            }
            List<String> nomes = fiscalDAO.nomesQueMudam(con, normalizado, perfilId, NOMES_NA_AUDITORIA);
            int alterados = fiscalDAO.aplicarPerfil(con, normalizado, perfilId, sessao.usuarioId(),
                    OffsetDateTime.now(clock));
            if (alterados > 0) {
                auditoria.registrar(con, sessao, AcaoAuditoria.PERFIL_TRIBUTARIO_APLICADO, ENTIDADE_PERFIL, perfilId,
                        "Perfil \"" + perfil.nome() + "\" aplicado a " + (alterados == 1 ? "1 produto" : alterados
                                + " produtos") + " (" + rotulo(normalizado, categoria) + "): " + String.join(", ", nomes)
                                + (alterados > nomes.size() ? " e mais " + (alterados - nomes.size()) : ""));
            }
            return alterados;
        });
    }

    private static AlvoPerfil normalizar(AlvoPerfil alvo) {
        if (alvo == null) {
            throw new NegocioException("Escolha a quem aplicar o perfil.");
        }
        return switch (alvo.tipo()) {
            case SELECAO -> {
                if (alvo.produtos().isEmpty()) {
                    throw new NegocioException("Selecione os produtos na lista.");
                }
                yield AlvoPerfil.selecao(alvo.produtos().stream().distinct().toList());
            }
            case CATEGORIA -> {
                if (alvo.categoriaId() == null) {
                    throw new NegocioException("Escolha a categoria.");
                }
                yield alvo;
            }
            case NCM -> {
                String ncm = ValidacaoFiscal.semSeparadores(alvo.ncm());
                if (ncm == null || ncm.length() < 2 || ncm.length() > 8) {
                    throw new NegocioException("Informe o NCM ou o começo dele (de 2 a 8 dígitos, ex.: 2203).");
                }
                yield AlvoPerfil.ncm(ncm);
            }
        };
    }

    /** "na categoria "Bebidas"", "com o NCM começando por 2203"… (mensagem de nenhum produto). */
    private static String descrever(AlvoPerfil alvo, String categoria) {
        return switch (alvo.tipo()) {
            case SELECAO -> "na seleção";
            case CATEGORIA -> "na categoria \"" + categoria + "\"";
            case NCM -> alvo.ncm().length() == 8 ? "com o NCM " + ValidacaoFiscal.formatarNcm(alvo.ncm())
                    : "com o NCM começando por " + alvo.ncm();
        };
    }

    /** "seleção", "categoria "Bebidas"", "NCM 2203.00.00" ou "NCM começando por 22" (auditoria). */
    private static String rotulo(AlvoPerfil alvo, String categoria) {
        return switch (alvo.tipo()) {
            case SELECAO -> "seleção";
            case CATEGORIA -> "categoria \"" + categoria + "\"";
            case NCM -> alvo.ncm().length() == 8 ? "NCM " + ValidacaoFiscal.formatarNcm(alvo.ncm())
                    : "NCM começando por " + alvo.ncm();
        };
    }

    // ----- Tabelas NCM e CEST -----

    public Tabelas tabelas(Sessao sessao) {
        sessao.exigir(Permissao.FISCAL);
        return transacao.executar(con -> new Tabelas(fiscalDAO.contarNcm(con), fiscalDAO.contarCest(con)));
    }

    /** Troca a tabela NCM pela do arquivo (CSV ou JSON oficial). Nada muda se o arquivo tiver erro. */
    public Importacao importarNcm(Sessao sessao, byte[] arquivo, String nomeArquivo) {
        sessao.exigir(Permissao.FISCAL);
        TabelaFiscal.LeituraNcm leitura = TabelaFiscal.lerNcm(arquivo);
        return transacao.executar(con -> {
            fiscalDAO.substituirNcm(con, leitura.codigos());
            auditoria.registrar(con, sessao, AcaoAuditoria.TABELA_FISCAL_IMPORTADA, ENTIDADE_TABELA, null,
                    "Tabela NCM importada (" + leitura.formato() + ", " + nomeArquivo + "): " + leitura.codigos().size()
                            + " códigos de 8 dígitos" + (leitura.pulados() == 0 ? "" : leitura.pulados() == 1
                            ? ", 1 nível intermediário pulado" : ", " + leitura.pulados()
                            + " níveis intermediários pulados"));
            return new Importacao("NCM", leitura.codigos().size(), leitura.pulados(), leitura.formato());
        });
    }

    /** Troca a tabela CEST pela do arquivo ({@code cest;ncm;descrição}). Nada muda se o arquivo tiver erro. */
    public Importacao importarCest(Sessao sessao, byte[] arquivo, String nomeArquivo) {
        sessao.exigir(Permissao.FISCAL);
        List<CodigoCest> linhas = TabelaFiscal.lerCest(arquivo);
        long codigos = linhas.stream().map(CodigoCest::cest).distinct().count();
        return transacao.executar(con -> {
            fiscalDAO.substituirCest(con, linhas);
            auditoria.registrar(con, sessao, AcaoAuditoria.TABELA_FISCAL_IMPORTADA, ENTIDADE_TABELA, null,
                    "Tabela CEST importada (" + nomeArquivo + "): " + codigos + " CEST em " + linhas.size()
                            + " pares CEST × NCM");
            return new Importacao("CEST", (int) codigos, 0, "CSV");
        });
    }

    // ----- Tabela IBPT (F22.3) -----

    /**
     * Situação da tabela IBPT: linhas de NCM, versão, vigência e fonte; {@code vencida} quando a vigência acabou antes
     * de hoje ("Baixe a tabela nova no site do IBPT").
     */
    public record SituacaoIbpt(long linhas, String versao, java.time.LocalDate inicio, java.time.LocalDate fim,
                               String fonte, boolean vencida) {
    }

    public SituacaoIbpt situacaoIbpt(Sessao sessao) {
        sessao.exigir(Permissao.FISCAL);
        return transacao.executar(con -> {
            FiscalDAO.SituacaoIbpt s = fiscalDAO.situacaoIbpt(con);
            boolean vencida = s.fim() != null && s.fim().isBefore(java.time.LocalDate.now(clock));
            return new SituacaoIbpt(s.linhas(), s.versao(), s.inicio(), s.fim(), s.fonte(), vencida);
        });
    }

    /**
     * Troca a tabela IBPT inteira pela do arquivo (só as linhas de NCM), numa transação e com auditoria. Nada muda se
     * o arquivo tiver erro. A confirmação da UF do nome do arquivo é da tela ({@link TabelaFiscal#ufDoArquivo}).
     */
    public Importacao importarIbpt(Sessao sessao, byte[] arquivo, String nomeArquivo) {
        sessao.exigir(Permissao.FISCAL);
        TabelaFiscal.LeituraIbpt leitura = TabelaFiscal.lerIbpt(arquivo);
        return transacao.executar(con -> {
            fiscalDAO.substituirIbpt(con, leitura.linhas());
            auditoria.registrar(con, sessao, AcaoAuditoria.TABELA_FISCAL_IMPORTADA, ENTIDADE_TABELA, null,
                    "Tabela IBPT importada (" + nomeArquivo + "): versão "
                            + (leitura.versao().isEmpty() ? "(sem versão)" : leitura.versao()) + ", vigência de "
                            + br.com.varesync.util.Formatos.data(leitura.inicio()) + " a "
                            + br.com.varesync.util.Formatos.data(leitura.fim()) + ", " + leitura.linhas().size()
                            + (leitura.linhas().size() == 1 ? " linha de NCM" : " linhas de NCM")
                            + (leitura.puladas() == 0 ? "" : leitura.puladas() == 1 ? " (1 linha de serviço fora)"
                            : " (" + leitura.puladas() + " linhas de serviço fora)"));
            return new Importacao("IBPT", leitura.linhas().size(), leitura.puladas(), "CSV");
        });
    }

    // ----- NF-e -----

    /**
     * O que a NF-e preenche nos produtos dela (F19.3): para cada produto vinculado (pelo código de barras, pelo código
     * do fornecedor ou escolhido à mão na conferência), o NCM, o CEST e a origem da nota nos campos que estão vazios no
     * produto. Um campo só entra com formato válido e, com a tabela importada, existindo nela; o mesmo produto em vários
     * itens fica com o primeiro item que traz cada campo. A origem 1 do fornecedor entra como 2, e a 6 como 7
     * ({@link OrigemMercadoria#daNotaDoFornecedor}).
     *
     * @param escolhidos número do item → produto escolhido à mão na conferência
     */
    public List<SugestaoFiscal> sugestoesDaNota(Sessao sessao, byte[] xml, Map<Integer, Long> escolhidos) {
        sessao.exigir(Permissao.FISCAL);
        LeitorNfe.Nota nota = LeitorNfe.ler(xml);
        return transacao.executar(con -> {
            Map<Long, List<LeitorNfe.Item>> itensDoProduto = new LinkedHashMap<>();
            Map<Integer, Long> produtoDoItem = nfe.produtosDaNota(con, nota, escolhidos);
            for (LeitorNfe.Item item : nota.itens()) {
                Long produtoId = produtoDoItem.get(item.numero());
                if (produtoId != null) {
                    itensDoProduto.computeIfAbsent(produtoId, k -> new ArrayList<>()).add(item);
                }
            }
            boolean comNcm = fiscalDAO.contarNcm(con) > 0;
            boolean comCest = fiscalDAO.contarCest(con) > 0;
            List<SugestaoFiscal> sugestoes = new ArrayList<>();
            for (var e : itensDoProduto.entrySet()) {
                ProdutoFiscal p = fiscalDAO.buscar(con, e.getKey()).orElse(null);
                if (p == null) {
                    continue;
                }
                String ncm = null;
                String cest = null;
                Integer origem = null;
                List<Integer> usados = new ArrayList<>();
                for (LeitorNfe.Item item : e.getValue()) {
                    LeitorNfe.Fiscal f = item.fiscal();
                    boolean usou = false;
                    if (p.ncm() == null && ncm == null && f.ncm() != null
                            && (!comNcm || fiscalDAO.ncmExiste(con, f.ncm()))) {
                        ncm = f.ncm();
                        usou = true;
                    }
                    if (p.cest() == null && cest == null && f.cest() != null
                            && (!comCest || fiscalDAO.cestExiste(con, f.cest()))) {
                        cest = f.cest();
                        usou = true;
                    }
                    if (p.origem() == null && origem == null && f.origem() != null) {
                        origem = OrigemMercadoria.daNotaDoFornecedor(f.origem()); // 1 → 2 e 6 → 7
                        usou = true;
                    }
                    if (usou) {
                        usados.add(item.numero());
                    }
                }
                SugestaoFiscal s = new SugestaoFiscal(p.id(), p.nome(), usados.stream().map(String::valueOf)
                        .reduce((a, b) -> a + ", " + b).orElse(""), ncm, cest, origem);
                if (!s.vazia()) {
                    sugestoes.add(s);
                }
            }
            return sugestoes;
        });
    }

    /**
     * Preenche os campos confirmados na tela, só onde o produto ainda está vazio (o que foi preenchido por outra pessoa
     * entre a sugestão e a confirmação fica como está). Uma auditoria por produto alterado.
     *
     * @param numeroNota número da NF-e, para a auditoria
     * @return quantos produtos mudaram
     */
    public int preencherDaNota(Sessao sessao, List<SugestaoFiscal> confirmadas, String numeroNota) {
        sessao.exigir(Permissao.FISCAL);
        return transacao.executar(con -> {
            int alterados = 0;
            for (SugestaoFiscal s : confirmadas.stream()
                    .sorted(java.util.Comparator.comparingLong(SugestaoFiscal::produtoId)).toList()) {
                ProdutoFiscal p = fiscalDAO.travar(con, s.produtoId()).orElse(null);
                if (p == null) {
                    continue;
                }
                String ncm = p.ncm() == null ? ValidacaoFiscal.ncm(s.ncm()) : p.ncm();
                String cest = p.cest() == null ? ValidacaoFiscal.cest(s.cest()) : p.cest();
                Integer origem = p.origem() == null ? ValidacaoFiscal.origem(s.origem()) : p.origem();
                List<String> m = new ArrayList<>();
                comparar(m, "NCM", formatado(p.ncm(), true), formatado(ncm, true));
                comparar(m, "CEST", formatado(p.cest(), false), formatado(cest, false));
                comparar(m, "origem", p.origem() == null ? null : OrigemMercadoria.rotulo(p.origem()),
                        origem == null ? null : OrigemMercadoria.rotulo(origem));
                if (m.isEmpty()) {
                    continue;
                }
                fiscalDAO.gravar(con, p.id(), ncm, cest, origem, p.perfilId(), sessao.usuarioId(),
                        OffsetDateTime.now(clock));
                auditoria.registrar(con, sessao, AcaoAuditoria.DADOS_FISCAIS_ALTERADOS,
                        AuditoriaService.ENTIDADE_PRODUTO, p.id(), "Dados fiscais de "
                                + ProdutoService.descrever(p.id(), p.nome()) + " pela NF-e"
                                + (Textos.vazio(numeroNota) ? "" : " nº " + numeroNota.strip()) + ": "
                                + String.join("; ", m));
                alterados++;
            }
            return alterados;
        });
    }
}
