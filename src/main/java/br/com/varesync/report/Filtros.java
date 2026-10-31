package br.com.varesync.report;

import br.com.varesync.model.Periodo;
import br.com.varesync.model.TipoMovimento;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Valores dos filtros escolhidos na tela. Cada relatório lê só os que declara em {@link Relatorio#filtros()}; os
 * demais são ignorados. Nulo = "todos".
 *
 * @param topN quantos produtos no ranking (R02); nulo = todos
 * @param dias dias sem venda para o produto ser "sem giro" (R12); nulo = {@code relatorio.dias_sem_giro}
 * @param fornecedor fornecedor das entradas (R17); nulo = todos
 * @param crediario filtros do R21 e do R22 (F14.4); nunca nulo
 */
public record Filtros(Periodo periodo, Opcao operador, Opcao categoria, Integer topN, Criterio criterio,
                      boolean soAbaixoDoMinimo, boolean incluirInativos, Opcao produto, TipoMovimento tipo,
                      boolean detalharVendas, Integer dias, Opcao fornecedor, FiltroCrediario crediario) {

    public static Filtros vazios() {
        return new Filtros(null, null, null, null, null, false, false, null, null, false, null, null, FiltroCrediario.VAZIO);
    }

    public Filtros comPeriodo(Periodo novo) {
        return new Filtros(novo, operador, categoria, topN, criterio, soAbaixoDoMinimo, incluirInativos, produto,
                tipo, detalharVendas, dias, fornecedor, crediario);
    }

    public Filtros comOperador(Opcao novo) {
        return new Filtros(periodo, novo, categoria, topN, criterio, soAbaixoDoMinimo, incluirInativos, produto,
                tipo, detalharVendas, dias, fornecedor, crediario);
    }

    public Filtros comCategoria(Opcao nova) {
        return new Filtros(periodo, operador, nova, topN, criterio, soAbaixoDoMinimo, incluirInativos, produto,
                tipo, detalharVendas, dias, fornecedor, crediario);
    }

    public Filtros comRanking(Integer novoTopN, Criterio novoCriterio) {
        return new Filtros(periodo, operador, categoria, novoTopN, novoCriterio, soAbaixoDoMinimo, incluirInativos,
                produto, tipo, detalharVendas, dias, fornecedor, crediario);
    }

    public Filtros comCriterio(Criterio novo) {
        return comRanking(topN, novo);
    }

    public Filtros comEstoque(boolean soAbaixo, boolean inativos) {
        return new Filtros(periodo, operador, categoria, topN, criterio, soAbaixo, inativos, produto, tipo,
                detalharVendas, dias, fornecedor, crediario);
    }

    public Filtros comProduto(Opcao novo) {
        return new Filtros(periodo, operador, categoria, topN, criterio, soAbaixoDoMinimo, incluirInativos, novo,
                tipo, detalharVendas, dias, fornecedor, crediario);
    }

    public Filtros comTipo(TipoMovimento novo) {
        return new Filtros(periodo, operador, categoria, topN, criterio, soAbaixoDoMinimo, incluirInativos, produto,
                novo, detalharVendas, dias, fornecedor, crediario);
    }

    public Filtros comDetalhe(boolean detalhar) {
        return new Filtros(periodo, operador, categoria, topN, criterio, soAbaixoDoMinimo, incluirInativos, produto,
                tipo, detalhar, dias, fornecedor, crediario);
    }

    public Filtros comDias(Integer novos) {
        return new Filtros(periodo, operador, categoria, topN, criterio, soAbaixoDoMinimo, incluirInativos, produto,
                tipo, detalharVendas, novos, fornecedor, crediario);
    }

    public Filtros comFornecedor(Opcao novo) {
        return new Filtros(periodo, operador, categoria, topN, criterio, soAbaixoDoMinimo, incluirInativos, produto,
                tipo, detalharVendas, dias, novo, crediario);
    }

    public Filtros comCrediario(FiltroCrediario novo) {
        return new Filtros(periodo, operador, categoria, topN, criterio, soAbaixoDoMinimo, incluirInativos, produto,
                tipo, detalharVendas, dias, fornecedor, novo);
    }

    public Criterio criterioOuPadrao() {
        return criterio == null ? Criterio.RECEITA : criterio;
    }

    public Long operadorId() {
        return operador == null ? null : operador.id();
    }

    public Long categoriaId() {
        return categoria == null ? null : categoria.id();
    }

    public Long produtoId() {
        return produto == null ? null : produto.id();
    }

    public Long fornecedorId() {
        return fornecedor == null ? null : fornecedor.id();
    }

    /** Cabeçalho do PDF: "Período: 01/09/2026 a 30/09/2026 · Operador: todos · Categoria: Bebidas". */
    public String descrever(Set<TipoFiltro> usados) {
        List<String> partes = new ArrayList<>();
        if (usados.contains(TipoFiltro.PERIODO) && periodo != null) {
            partes.add("Período: " + Formatos.data(periodo.inicio()) + " a " + Formatos.data(periodo.fim()));
        }
        if (usados.contains(TipoFiltro.SITUACAO_TITULO)) {
            partes.add("Situação: " + crediario.situacaoOuPadrao().rotulo().toLowerCase());
        }
        if (usados.contains(TipoFiltro.VENCIMENTO)
                && (crediario.vencimentoDe() != null || crediario.vencimentoAte() != null)) {
            partes.add("Vencimento: " + (crediario.vencimentoDe() == null ? "até "
                    : Formatos.data(crediario.vencimentoDe()) + (crediario.vencimentoAte() == null ? " em diante"
                    : " a ")) + (crediario.vencimentoAte() == null ? "" : Formatos.data(crediario.vencimentoAte())));
        }
        if (usados.contains(TipoFiltro.FORMA_PAGAMENTO)) {
            partes.add("Forma: " + (crediario.forma() == null ? "todas" : crediario.forma().nome()));
        }
        if (usados.contains(TipoFiltro.CLIENTE) && !Textos.vazio(crediario.cliente())) {
            partes.add("Cliente: " + crediario.cliente());
        }
        if (usados.contains(TipoFiltro.OPERADOR)) {
            partes.add("Operador: " + (operador == null ? "todos" : operador.nome()));
        }
        if (usados.contains(TipoFiltro.CATEGORIA)) {
            partes.add("Categoria: " + (categoria == null ? "todas" : categoria.nome()));
        }
        if (usados.contains(TipoFiltro.FORNECEDOR)) {
            partes.add("Fornecedor: " + (fornecedor == null ? "todos" : fornecedor.nome()));
        }
        if (usados.contains(TipoFiltro.PRODUTO)) {
            partes.add("Produto: " + (produto == null ? "todos" : produto.nome() + " (código " + produto.id() + ")"));
        }
        if (usados.contains(TipoFiltro.TIPO_MOVIMENTO)) {
            partes.add("Tipo: " + (tipo == null ? "todos" : tipo.descricao()));
        }
        if (usados.contains(TipoFiltro.CRITERIO)) {
            partes.add("Critério: " + criterioOuPadrao().rotulo().toLowerCase());
        }
        if (usados.contains(TipoFiltro.TOP_N)) {
            partes.add(topN == null ? "Todos os produtos" : "Top " + topN);
        }
        if (usados.contains(TipoFiltro.DIAS) && dias != null) {
            partes.add("Sem venda há " + dias + (dias == 1 ? " dia" : " dias") + " ou mais");
        }
        if (usados.contains(TipoFiltro.SO_ABAIXO_MINIMO) && soAbaixoDoMinimo) {
            partes.add("Só abaixo do mínimo");
        }
        if (usados.contains(TipoFiltro.INCLUIR_INATIVOS)) {
            partes.add(incluirInativos ? "Com inativos" : "Só ativos");
        }
        if (usados.contains(TipoFiltro.DETALHAR_VENDAS) && detalharVendas) {
            partes.add("Detalhado por venda");
        }
        return String.join(" · ", partes);
    }
}
