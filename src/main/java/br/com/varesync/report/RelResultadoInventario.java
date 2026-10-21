package br.com.varesync.report;

import br.com.varesync.model.InventarioResumo;
import br.com.varesync.model.ItemDivergencia;
import br.com.varesync.model.ResultadoInventario;
import br.com.varesync.model.StatusInventario;
import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * R13 — Resultado de inventário (7.3), para exportar em CSV e PDF pela infraestrutura genérica: as mesmas linhas da
 * aba Divergências (sistema, contado, diferença, custo do item) com o valor separado em sobra e falta, cujos totais
 * são os do R13. O inventário vai no cabeçalho, no lugar dos filtros. Não fica no catálogo da tela Relatórios: as
 * linhas vêm do {@code InventarioService} (permissão INVENTARIO), na tela Inventário.
 */
public final class RelResultadoInventario implements Relatorio<ItemDivergencia> {

    private final ResultadoInventario resultado;

    public RelResultadoInventario(ResultadoInventario resultado) {
        this.resultado = resultado;
    }

    /** O R13 pronto para exportar. */
    public static ResultadoRelatorio<ItemDivergencia> exportacao(ResultadoInventario resultado, Emissao emissao) {
        return ResultadoRelatorio.de(new RelResultadoInventario(resultado), Filtros.vazios(), resultado.itens(),
                emissao);
    }

    @Override
    public String codigo() {
        return "R13";
    }

    @Override
    public String nome() {
        return "Resultado de inventário";
    }

    @Override
    public String descricao() {
        return "Sistema, contado, diferença e valor de cada item do inventário; totais de sobra e falta.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return Set.of();
    }

    @Override
    public List<ItemDivergencia> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros) {
        return resultado.itens();
    }

    /** "Inventário nº 4 — Mercearia · Aplicado em 10/10/2026 14:00 por Ana · Resultado: falta de R$ 3,20 · …". */
    @Override
    public String descreverFiltros(Filtros filtros) {
        InventarioResumo inv = resultado.inventario();
        List<String> partes = new ArrayList<>();
        partes.add(inv.titulo());
        partes.add(switch (inv.status()) {
            case ABERTO -> "Aberto (criado em " + Formatos.dataHora(inv.criadoEm()) + " por " + inv.criadoPorNome()
                    + ")";
            case APLICADO -> "Aplicado em " + Formatos.dataHora(inv.aplicadoEm()) + " por " + inv.aplicadoPorNome();
            case CANCELADO -> "Cancelado";
        });
        BigDecimal liquido = resultado.valorLiquido();
        partes.add("Resultado: " + (liquido.signum() == 0 ? "sem diferença em R$"
                : (liquido.signum() > 0 ? "sobra de " : "falta de ") + Formatos.moeda(liquido.abs())));
        long naoContados = resultado.itensNaoContados();
        if (naoContados > 0) {
            partes.add(naoContados == 1
                    ? "1 item não contado" + (inv.status() == StatusInventario.CANCELADO ? "" : " (ignorado ao aplicar)")
                    : naoContados + " itens não contados"
                            + (inv.status() == StatusInventario.CANCELADO ? "" : " (ignorados ao aplicar)"));
        }
        return String.join(" · ", partes);
    }

    @Override
    public List<Coluna<ItemDivergencia>> colunas(Filtros filtros) {
        return List.of(
                Coluna.<ItemDivergencia>codigo("Código", ItemDivergencia::produtoId).comLargura(0.8),
                Coluna.texto("Produto", ItemDivergencia::nome),
                Coluna.texto("Un.", ItemDivergencia::unidade).comLargura(0.6),
                Coluna.quantidade("Sistema", ItemDivergencia::estoqueSistema, ItemDivergencia::unidadeFracionada),
                Coluna.quantidade("Contado", ItemDivergencia::quantidadeContada, ItemDivergencia::unidadeFracionada),
                Coluna.quantidade("Diferença", ItemDivergencia::diferenca, ItemDivergencia::unidadeFracionada),
                Coluna.<ItemDivergencia>de("Custo unit.", TipoColuna.CUSTO, ItemDivergencia::custoUnitario),
                Coluna.moeda("Sobra (R$)", RelResultadoInventario::sobra),
                Coluna.moeda("Falta (R$)", RelResultadoInventario::falta));
    }

    static BigDecimal sobra(ItemDivergencia item) {
        return item.contado() && item.diferenca().signum() > 0 ? item.valorDiferenca() : null;
    }

    /** Positiva, como o total de falta do R13. */
    static BigDecimal falta(ItemDivergencia item) {
        return item.contado() && item.diferenca().signum() < 0 ? item.valorDiferenca().negate() : null;
    }
}
