package br.com.varesync.report;

import br.com.varesync.model.Cliente;
import br.com.varesync.model.ExtratoCrediario;
import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Extrato do crediário do cliente em PDF (F14.4), pela infraestrutura genérica: resumo, parcelas e lançamentos. O cliente
 * vai no cabeçalho, no lugar dos filtros. Não fica no catálogo da tela Relatórios: as linhas vêm do
 * {@code ClienteService} (permissão CLIENTES).
 */
public final class RelExtratoCrediario implements Relatorio<RelExtratoCrediario.Linha> {

    public static final String RESUMO = "Resumo";
    public static final String PARCELAS = "Parcelas";
    public static final String LANCAMENTOS = "Lançamentos";

    /** Uma linha: seção, item, detalhe, valor e (nos lançamentos) o saldo devedor depois dele. */
    public record Linha(String secao, String item, String detalhe, BigDecimal valor, BigDecimal saldo) {
    }

    private final Cliente cliente;

    public RelExtratoCrediario(Cliente cliente) {
        this.cliente = cliente;
    }

    public static ResultadoRelatorio<Linha> exportacao(Cliente cliente, ExtratoCrediario extrato, Emissao emissao) {
        return ResultadoRelatorio.de(new RelExtratoCrediario(cliente), Filtros.vazios(), linhas(extrato), emissao);
    }

    static List<Linha> linhas(ExtratoCrediario e) {
        List<Linha> linhas = new ArrayList<>();
        linhas.add(new Linha(RESUMO, "Limite de crédito", e.bloqueado() ? "Crediário bloqueado" : "", e.limite(), null));
        linhas.add(new Linha(RESUMO, "Saldo devedor", "", e.saldoDevedor(), null));
        linhas.add(new Linha(RESUMO, "Vencido", "", e.vencido(), null));
        linhas.add(new Linha(RESUMO, "Limite disponível", "", e.limiteDisponivel(), null));
        for (ExtratoCrediario.Parcela p : e.parcelas()) {
            linhas.add(new Linha(PARCELAS, "Venda nº " + p.vendaId() + ", parcela " + p.rotulo(),
                    "Vence " + Formatos.data(p.vencimento()) + " — " + p.status().rotulo().toLowerCase()
                            + " — valor " + Formatos.moeda(p.valor()) + ", pago " + Formatos.moeda(p.pago())
                            + ", abatido " + Formatos.moeda(p.abatido())
                            + (p.diasAtraso() > 0 ? " — " + p.diasAtraso() + " dias de atraso" : ""),
                    p.emAberto(), null));
        }
        for (ExtratoCrediario.Lancamento l : e.lancamentos()) {
            linhas.add(new Linha(LANCAMENTOS, l.tipo(), Formatos.dataHora(l.dataHora()) + " — " + l.documento()
                    + (l.encargos().signum() == 0 ? "" : " — encargos " + Formatos.moeda(l.encargos())),
                    l.valor(), l.saldo()));
        }
        return linhas;
    }

    @Override
    public String codigo() {
        return "EXT";
    }

    @Override
    public String nome() {
        return "Extrato do crediário";
    }

    @Override
    public String descricao() {
        return "Limite, saldo devedor, parcelas, vendas a prazo, pagamentos e abatimentos do cliente.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return Set.of();
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros) {
        throw new UnsupportedOperationException("As linhas vêm do ClienteService");
    }

    @Override
    public String descreverFiltros(Filtros filtros) {
        return "Cliente nº " + cliente.id() + " — " + cliente.nome();
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        return List.of(
                Coluna.<Linha>texto("Seção", Linha::secao).comLargura(1),
                Coluna.<Linha>texto("Item", Linha::item).comLargura(1.8),
                Coluna.<Linha>texto("Detalhe", Linha::detalhe).comLargura(4),
                Coluna.<Linha>de("Valor", TipoColuna.MOEDA, Linha::valor),
                Coluna.<Linha>de("Saldo", TipoColuna.MOEDA, Linha::saldo));
    }
}
