package br.com.varesync.service;

import br.com.varesync.dao.ClienteDAO;
import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.CreditoClienteDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Cliente;
import br.com.varesync.model.ClienteVenda;
import br.com.varesync.model.DadosCliente;
import br.com.varesync.model.ExtratoCredito;
import br.com.varesync.model.HistoricoCliente;
import br.com.varesync.model.Permissao;
import br.com.varesync.report.Emissao;
import br.com.varesync.report.RelDadosCliente;
import br.com.varesync.report.ResultadoRelatorio;
import br.com.varesync.util.CpfCnpj;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Clientes (F12.1, F12.2 e F12.6). Cadastro, busca, histórico, exportação dos dados e anonimização com a permissão
 * CLIENTES (anonimizar, só o supervisor); busca e cadastro rápido para identificar o cliente no PDV com a permissão
 * PDV. CPF/CNPJ único, gravado sem máscara. Anonimizado não volta a ser editado nem reativado.
 */
public class ClienteService {

    public static final String ENTIDADE_CLIENTE = "cliente";
    /** Produtos mais comprados na tela do cliente. */
    public static final int PRODUTOS_MAIS_COMPRADOS = 10;
    private static final Pattern MASCARA_TELEFONE = Pattern.compile("[\\s().\\-]");
    private static final Pattern DOCUMENTO_PARCIAL = Pattern.compile("[0-9A-Z]{1,14}");

    private final Transacao transacao;
    private final ClienteDAO dao;
    private final CreditoClienteDAO creditoDAO;
    private final ConfiguracaoDAO configuracaoDAO;
    private final AuditoriaService auditoria;
    private final Clock clock;

    public ClienteService(Transacao transacao, ClienteDAO dao, CreditoClienteDAO creditoDAO,
                          ConfiguracaoDAO configuracaoDAO, AuditoriaService auditoria, Clock clock) {
        this.transacao = transacao;
        this.dao = dao;
        this.creditoDAO = creditoDAO;
        this.configuracaoDAO = configuracaoDAO;
        this.auditoria = auditoria;
        this.clock = clock;
    }

    // ----- Cadastro (CLIENTES) -----

    /**
     * Busca (F12.1) por nome sem acento nem maiúsculas, por CPF/CNPJ (com ou sem máscara, inteiro ou parte) ou por
     * telefone (dígitos, inteiro ou parte). Vazio = todos.
     */
    public List<Cliente> pesquisar(Sessao sessao, String texto, boolean incluirInativos) {
        sessao.exigir(Permissao.CLIENTES);
        return transacao.executar(con -> dao.pesquisar(con, busca(texto, incluirInativos)));
    }

    public Cliente buscar(Sessao sessao, long id) {
        sessao.exigir(Permissao.CLIENTES);
        return transacao.executar(con -> buscar(con, id));
    }

    /** @return código (id) do cliente */
    public long criar(Sessao sessao, DadosCliente dados) {
        sessao.exigir(Permissao.CLIENTES);
        return inserir(sessao, dados);
    }

    /** Consentimento marcado agora grava a data; desmarcado apaga; mantido conserva a data original (F12.6). */
    public void alterar(Sessao sessao, long id, DadosCliente dados) {
        sessao.exigir(Permissao.CLIENTES);
        OffsetDateTime agora = OffsetDateTime.now(clock);
        ClienteDAO.Gravacao g = ValidacaoCliente.validar(dados, hoje(agora));
        transacao.executar(con -> {
            Cliente atual = travar(con, id);
            exigirNaoAnonimizado(atual);
            conferirDocumentoLivre(con, g.cpfCnpj(), id);
            OffsetDateTime consentimento = !dados.consentimentoMarketing() ? null
                    : atual.consentimentoMarketing() ? atual.consentimentoEm() : agora;
            dao.alterar(con, id, g, consentimento, sessao.usuarioId(), agora);
            return null;
        });
    }

    /** Inativo não aparece no PDV, mas continua nas vendas e nos relatórios. */
    public void alterarSituacao(Sessao sessao, long id, boolean ativo) {
        sessao.exigir(Permissao.CLIENTES);
        transacao.executar(con -> {
            Cliente atual = travar(con, id);
            exigirNaoAnonimizado(atual);
            if (atual.ativo() != ativo) {
                dao.alterarSituacao(con, id, ativo, sessao.usuarioId(), OffsetDateTime.now(clock));
            }
            return null;
        });
    }

    /**
     * Exclui o cliente só se nenhuma venda o usa.
     *
     * @throws RegistroEmUsoException se houver venda (a tela oferece inativar)
     */
    public void excluir(Sessao sessao, long id) {
        sessao.exigir(Permissao.CLIENTES);
        transacao.executar(con -> {
            Cliente atual = travar(con, id);
            int vendas = dao.contarVendas(con, id);
            if (vendas > 0) {
                throw new RegistroEmUsoException("O cliente \"" + atual.nome() + "\" não pode ser excluído porque "
                        + "tem " + (vendas == 1 ? "1 venda" : vendas + " vendas") + ". Você pode inativá-lo: ele sai "
                        + "da identificação no PDV, mas continua nas vendas e nos relatórios.");
            }
            if (creditoDAO.contarMovimentos(con, id) > 0) {
                throw new RegistroEmUsoException("O cliente \"" + atual.nome() + "\" não pode ser excluído porque "
                        + "tem movimentos de crédito. Você pode inativá-lo: o extrato continua guardado.");
            }
            dao.excluir(con, id);
            return null;
        });
    }

    /** Histórico de compras (F12.2): compras, total gasto, ticket médio, última compra e os 10 mais comprados. */
    public HistoricoCliente historico(Sessao sessao, long id) {
        sessao.exigir(Permissao.CLIENTES);
        return transacao.executar(con -> {
            buscar(con, id);
            return new HistoricoCliente(dao.compras(con, id), dao.produtos(con, id, PRODUTOS_MAIS_COMPRADOS));
        });
    }

    // ----- LGPD (F12.6) -----

    /**
     * Dados do cliente para exportar em PDF ou CSV (cadastro, resumo, compras e todos os produtos comprados), com
     * auditoria {@code CLIENTE_DADOS_EXPORTADOS} (sem dados pessoais na descrição).
     */
    public ResultadoRelatorio<RelDadosCliente.Linha> exportar(Sessao sessao, long id) {
        sessao.exigir(Permissao.CLIENTES);
        OffsetDateTime agora = OffsetDateTime.now(clock);
        return transacao.executar(con -> {
            Cliente cliente = buscar(con, id);
            HistoricoCliente historico = new HistoricoCliente(dao.compras(con, id), dao.produtos(con, id, null));
            ExtratoCredito extrato = new ExtratoCredito(creditoDAO.saldo(con, id).orElse(Dinheiro.ZERO),
                    creditoDAO.movimentos(con, id));
            auditoria.registrar(con, sessao, AcaoAuditoria.CLIENTE_DADOS_EXPORTADOS, ENTIDADE_CLIENTE, id,
                    "Dados do cliente nº " + id + " exportados a pedido do titular (cadastro, "
                            + (historico.compras().size() == 1 ? "1 compra" : historico.compras().size() + " compras")
                            + " e " + (extrato.movimentos().size() == 1 ? "1 movimento"
                            : extrato.movimentos().size() + " movimentos") + " de crédito)");
            String loja = configuracaoDAO.carregar(con).loja().nome();
            return RelDadosCliente.exportacao(cliente, historico, extrato,
                    new Emissao(loja.isBlank() ? "VareSync" : loja, sessao.usuario().nome(), agora));
        });
    }

    /**
     * Anonimiza o cliente (F12.6, só o supervisor): confirmação pelo nome digitado; nome trocado por "Cliente
     * anonimizado nº X"; CPF/CNPJ, contatos, endereço, nascimento, observação e consentimento apagados; inativo. As
     * vendas e o extrato de crédito continuam. Com saldo de crédito diferente de zero, é recusado. A auditoria não
     * guarda o nome antigo.
     */
    public void anonimizar(Sessao sessao, long id, String nomeDigitado) {
        sessao.exigirSupervisor();
        transacao.executar(con -> {
            Cliente atual = travar(con, id);
            exigirNaoAnonimizado(atual);
            BigDecimal saldo = creditoDAO.travarSaldo(con, id).orElse(Dinheiro.ZERO);
            if (saldo.signum() != 0) {
                throw new NegocioException("O cliente tem saldo de crédito de " + Formatos.moeda(saldo) + " e não "
                        + "pode ser anonimizado. Use o saldo numa venda ou zere-o por um ajuste (aba Crédito, só o "
                        + "supervisor) antes.");
            }
            if (!Textos.normalizarEspacos(nomeDigitado).equalsIgnoreCase(atual.nome())) {
                throw new NegocioException("Para confirmar, digite o nome do cliente exatamente como está no "
                        + "cadastro.");
            }
            dao.anonimizar(con, id, nomeAnonimizado(id), sessao.usuarioId(), OffsetDateTime.now(clock));
            int vendas = dao.contarVendas(con, id);
            auditoria.registrar(con, sessao, AcaoAuditoria.CLIENTE_ANONIMIZADO, ENTIDADE_CLIENTE, id,
                    "Cliente nº " + id + " anonimizado a pedido do titular: nome, CPF/CNPJ, contatos, endereço, "
                            + "data de nascimento e observação apagados; "
                            + (vendas == 1 ? "1 venda mantida" : vendas + " vendas mantidas"));
            return null;
        });
    }

    public static String nomeAnonimizado(long id) {
        return "Cliente anonimizado nº " + id;
    }

    // ----- PDV (F12.2, permissão PDV) -----

    /**
     * Busca do F11: CPF/CNPJ completo e válido → o cliente dono dele (ou nenhum); senão, como a busca do cadastro.
     * Só clientes ativos.
     */
    public List<ClienteVenda> pesquisarParaVenda(Sessao sessao, String texto) {
        sessao.exigir(Permissao.PDV);
        return transacao.executar(con -> {
            Optional<String> documento = documentoCompleto(texto);
            if (documento.isPresent()) {
                return dao.buscarPorDocumento(con, documento.get()).filter(ClienteVenda::ativo).stream().toList();
            }
            return dao.pesquisarParaVenda(con, busca(texto, false));
        });
    }

    /** Cadastro rápido no PDV (F12.2): nome e CPF/CNPJ, com a permissão PDV. @return o cliente para identificar */
    public ClienteVenda cadastrarRapido(Sessao sessao, String nome, String cpfCnpj) {
        sessao.exigir(Permissao.PDV);
        long id = inserir(sessao, DadosCliente.rapido(nome, cpfCnpj));
        return transacao.executar(con -> dao.buscarParaVenda(con, id)).orElseThrow();
    }

    /** CPF/CNPJ digitado, se é um documento completo e válido (11 dígitos ou 14 posições com o DV certo). */
    public static Optional<String> documentoCompleto(String texto) {
        String documento = CpfCnpj.normalizar(texto);
        return (documento.length() == 11 || documento.length() == 14) && CpfCnpj.validar(documento).isEmpty()
                ? Optional.of(documento) : Optional.empty();
    }

    // ----- Apoio -----

    private long inserir(Sessao sessao, DadosCliente dados) {
        OffsetDateTime agora = OffsetDateTime.now(clock);
        ClienteDAO.Gravacao g = ValidacaoCliente.validar(dados, hoje(agora));
        return transacao.executar(con -> {
            conferirDocumentoLivre(con, g.cpfCnpj(), null);
            return dao.inserir(con, g, dados.consentimentoMarketing() ? agora : null, sessao.usuarioId(), agora);
        });
    }

    /**
     * Termos da busca: nome sem acento; CPF/CNPJ sem máscara (só se sobrarem letras e dígitos, com algum dígito);
     * telefone só com dígitos (só se o texto, sem a máscara, for todo de dígitos).
     */
    static ClienteDAO.Busca busca(String texto, boolean incluirInativos) {
        String nome = Textos.normalizarBusca(texto);
        if (nome.isEmpty()) {
            return new ClienteDAO.Busca(null, null, null, incluirInativos);
        }
        String documento = CpfCnpj.normalizar(texto);
        String telefone = MASCARA_TELEFONE.matcher(texto).replaceAll("");
        boolean pareceDocumento = DOCUMENTO_PARCIAL.matcher(documento).matches()
                && documento.chars().anyMatch(Character::isDigit);
        return new ClienteDAO.Busca(nome, pareceDocumento ? documento : null,
                Textos.soDigitos(telefone) ? telefone : null, incluirInativos);
    }

    private LocalDate hoje(OffsetDateTime agora) {
        return agora.atZoneSameInstant(clock.getZone()).toLocalDate();
    }

    private Cliente buscar(Connection con, long id) throws SQLException {
        return dao.buscar(con, id).orElseThrow(() -> new NegocioException("Cliente não encontrado."));
    }

    private Cliente travar(Connection con, long id) throws SQLException {
        return dao.buscarParaAtualizar(con, id).orElseThrow(() -> new NegocioException("Cliente não encontrado."));
    }

    private static void exigirNaoAnonimizado(Cliente cliente) {
        if (cliente.anonimizado()) {
            throw new NegocioException("O cliente nº " + cliente.id() + " foi anonimizado (LGPD) e não pode ser "
                    + "alterado.");
        }
    }

    /** CPF/CNPJ repetido: a mensagem diz qual cliente já o tem (F12.1). */
    private void conferirDocumentoLivre(Connection con, String cpfCnpj, Long ignorarId) throws SQLException {
        if (cpfCnpj == null) {
            return;
        }
        dao.donoDoDocumento(con, cpfCnpj, ignorarId).ifPresent(dono -> {
            throw new NegocioException("Não foi possível salvar: o " + (cpfCnpj.length() == 11 ? "CPF " : "CNPJ ")
                    + CpfCnpj.formatar(cpfCnpj) + " já pertence ao cliente \"" + dono.nome() + "\" (código "
                    + dono.id() + ").");
        });
    }
}
