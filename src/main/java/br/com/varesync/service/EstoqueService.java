package br.com.varesync.service;

import br.com.varesync.dao.MovimentoEstoqueDAO;
import br.com.varesync.dao.ProdutoDAO;
import br.com.varesync.model.OrigemMovimento;
import br.com.varesync.model.TipoMovimento;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * Único caminho para movimentar estoque (6.4): insere em {@code movimento_estoque} e o trigger atualiza o saldo.
 * Roda na transação de quem chama; a permissão é conferida pelo service da rotina (ex.: estoque inicial no
 * cadastro de produto exige PRODUTOS). Consulta, kardex e ajustes manuais entram na Fase 3.
 */
public class EstoqueService {

    static final int TAMANHO_MAXIMO_MOTIVO = 200;

    private final ProdutoDAO produtoDAO;
    private final MovimentoEstoqueDAO movimentoDAO;
    private final Clock clock;

    public EstoqueService(ProdutoDAO produtoDAO, MovimentoEstoqueDAO movimentoDAO, Clock clock) {
        this.produtoDAO = produtoDAO;
        this.movimentoDAO = movimentoDAO;
        this.clock = clock;
    }

    /**
     * Registra um movimento. Valida antes do banco para dar mensagem clara; o banco é a última barreira.
     *
     * @param quantidadeComSinal positiva entra, negativa sai; o sinal tem de combinar com o tipo
     * @param motivo             obrigatório em ajuste e perda
     * @param custoOpcional      nulo = custo atual do produto (o trigger preenche)
     * @return id do movimento
     */
    public long movimentar(Connection con, Sessao sessao, long produtoId, TipoMovimento tipo,
                           BigDecimal quantidadeComSinal, OrigemMovimento origem, String motivo,
                           BigDecimal custoOpcional) throws SQLException {
        if (!origem.compativelCom(tipo)) {
            throw new IllegalArgumentException("Origem " + origem + " não combina com o movimento " + tipo);
        }
        if (!tipo.aceitaQuantidade(quantidadeComSinal.signum())) {
            throw new NegocioException(quantidadeComSinal.signum() == 0
                    ? "A quantidade do movimento não pode ser zero."
                    : "A quantidade do movimento não corresponde ao tipo (entrada ou saída).");
        }
        String motivoNormalizado = Textos.vazio(motivo) ? null : Textos.normalizarEspacos(motivo);
        if (tipo.exigeMotivo() && motivoNormalizado == null) {
            throw new NegocioException("Informe o motivo do ajuste.");
        }
        if (motivoNormalizado != null && motivoNormalizado.length() > TAMANHO_MAXIMO_MOTIVO) {
            throw new NegocioException("O motivo pode ter no máximo " + TAMANHO_MAXIMO_MOTIVO + " caracteres.");
        }
        boolean fracionada = produtoDAO.unidadeFracionada(con, produtoId)
                .orElseThrow(() -> new NegocioException("Produto não encontrado."));
        ValidacaoProduto.quantidade(quantidadeComSinal.abs(), "A quantidade", fracionada);

        return movimentoDAO.inserir(con, produtoId, tipo, quantidadeComSinal, custoOpcional,
                origem.vendaItemId(), origem.inventarioItemId(), origem.entradaItemId(), motivoNormalizado,
                sessao.usuarioId(), OffsetDateTime.now(clock));
    }
}
