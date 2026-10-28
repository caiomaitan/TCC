package br.com.varesync.report;

import br.com.varesync.model.Cliente;
import br.com.varesync.model.ExtratoCredito;
import br.com.varesync.model.HistoricoCliente;
import br.com.varesync.model.MovimentoCredito;
import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Exportação dos dados do cliente a pedido do titular (F12.6, LGPD), em CSV e PDF pela infraestrutura genérica: uma
 * tabela de seções (cadastro, resumo das compras, compras, produtos comprados e extrato de crédito). O cliente vai no cabeçalho, no lugar
 * dos filtros. Não fica no catálogo da tela Relatórios: as linhas vêm do {@code ClienteService} (permissão CLIENTES).
 */
public final class RelDadosCliente implements Relatorio<RelDadosCliente.Linha> {

    public static final String CADASTRO = "Cadastro";
    public static final String RESUMO = "Resumo das compras";
    public static final String COMPRAS = "Compras";
    public static final String PRODUTOS = "Produtos comprados";
    public static final String CREDITO = "Extrato de crédito";

    /** Uma linha: seção, item, detalhe e, nas compras e nos produtos, o valor. */
    public record Linha(String secao, String item, String detalhe, BigDecimal valor) {
    }

    private final Cliente cliente;

    public RelDadosCliente(Cliente cliente) {
        this.cliente = cliente;
    }

    /** Os dados do cliente prontos para exportar. */
    public static ResultadoRelatorio<Linha> exportacao(Cliente cliente, HistoricoCliente historico,
                                                       ExtratoCredito extrato, Emissao emissao) {
        return ResultadoRelatorio.de(new RelDadosCliente(cliente), Filtros.vazios(),
                linhas(cliente, historico, extrato), emissao);
    }

    static List<Linha> linhas(Cliente c, HistoricoCliente historico, ExtratoCredito extrato) {
        List<Linha> linhas = new ArrayList<>();
        cadastro(linhas, "Código", String.valueOf(c.id()));
        cadastro(linhas, "Nome", c.nome());
        cadastro(linhas, "CPF/CNPJ", c.documentoFormatado());
        cadastro(linhas, "Telefone", c.telefoneFormatado());
        cadastro(linhas, "E-mail", c.email());
        cadastro(linhas, "Data de nascimento", c.dataNascimento() == null ? "" : Formatos.data(c.dataNascimento()));
        cadastro(linhas, "Endereço", c.endereco());
        cadastro(linhas, "Observação", c.observacao());
        cadastro(linhas, "Consentimento de marketing", c.consentimentoMarketing()
                ? "Sim, desde " + Formatos.dataHora(c.consentimentoEm()) : "Não");
        cadastro(linhas, "Situação", c.situacao());
        cadastro(linhas, "Cadastrado em", Formatos.dataHora(c.criadoEm()) + " por " + c.criadoPorNome());
        cadastro(linhas, "Última alteração", Formatos.dataHora(c.atualizadoEm()) + " por " + c.atualizadoPorNome());

        linhas.add(new Linha(RESUMO, "Compras", String.valueOf(historico.compras().size()), null));
        linhas.add(new Linha(RESUMO, "Total gasto", "", historico.totalGasto()));
        linhas.add(new Linha(RESUMO, "Ticket médio", historico.ticketMedio().isEmpty() ? Formatos.SEM_VALOR : "",
                historico.ticketMedio().orElse(null)));
        linhas.add(new Linha(RESUMO, "Última compra",
                historico.ultimaCompra().map(Formatos::dataHora).orElse(Formatos.SEM_VALOR), null));

        for (HistoricoCliente.Compra compra : historico.compras()) {
            linhas.add(new Linha(COMPRAS, "Venda nº " + compra.vendaId(), Formatos.dataHora(compra.dataHora()) + " — "
                    + (compra.itens() == 1 ? "1 item" : compra.itens() + " itens"), compra.valor()));
        }
        for (HistoricoCliente.ProdutoComprado p : historico.produtos()) {
            linhas.add(new Linha(PRODUTOS, p.nome(), Formatos.quantidade(p.quantidade(), p.fracionada()) + " "
                    + p.unidade() + " em " + (p.compras() == 1 ? "1 compra" : p.compras() + " compras"), p.valor()));
        }
        linhas.add(new Linha(CREDITO, "Saldo atual", "", extrato.saldo()));
        for (MovimentoCredito m : extrato.movimentos()) {
            linhas.add(new Linha(CREDITO, m.tipo().rotulo(), Formatos.dataHora(m.criadoEm()) + " — " + m.origem()
                    + " — saldo " + Formatos.moeda(m.saldoPosterior()), m.valor()));
        }
        return linhas;
    }

    private static void cadastro(List<Linha> linhas, String item, String valor) {
        linhas.add(new Linha(CADASTRO, item, valor == null ? "" : valor, null));
    }

    @Override
    public String codigo() {
        return "LGPD";
    }

    @Override
    public String nome() {
        return "Dados do cliente";
    }

    @Override
    public String descricao() {
        return "Cadastro, compras, produtos comprados e extrato de crédito do cliente, a pedido do titular dos dados.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return Set.of();
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros) {
        throw new UnsupportedOperationException("As linhas vêm do ClienteService");
    }

    /** "Cliente nº 5 — Maria Silva · exportação a pedido do titular dos dados (LGPD)". */
    @Override
    public String descreverFiltros(Filtros filtros) {
        return "Cliente nº " + cliente.id() + " — " + cliente.nome() + " · exportação a pedido do titular dos dados "
                + "(LGPD)";
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        return List.of(
                Coluna.<Linha>texto("Seção", Linha::secao).comLargura(1.4),
                Coluna.<Linha>texto("Item", Linha::item).comLargura(2),
                Coluna.<Linha>texto("Detalhe", Linha::detalhe).comLargura(3),
                Coluna.<Linha>de("Valor", TipoColuna.MOEDA, Linha::valor));
    }
}
