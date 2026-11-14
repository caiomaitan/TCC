package br.com.varesync.service;

import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.TributoVendaDAO;
import br.com.varesync.model.Configuracoes;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;

/**
 * Registra os tributos de cada item da venda (F22.2), na transação da finalização ({@link VendaService#finalizar}).
 * Só os itens não cancelados cujo produto tem NCM, origem e perfil tributário ativo ganham uma linha em
 * {@code venda_item_tributo}; o cálculo é do {@link CalculoTributos} e nenhum valor da venda muda. Vale também com a
 * NFC-e desligada.
 */
public class TributosVendaService {

    private final TributoVendaDAO tributoDAO;
    private final ConfiguracaoDAO configuracaoDAO;

    public TributosVendaService(TributoVendaDAO tributoDAO, ConfiguracaoDAO configuracaoDAO) {
        this.tributoDAO = tributoDAO;
        this.configuracaoDAO = configuracaoDAO;
    }

    /**
     * Calcula e grava os tributos dos itens da venda, com a configuração e a tabela IBPT vigentes em {@code data}.
     *
     * @return quantos itens ganharam tributos
     */
    public int registrar(Connection con, long vendaId, LocalDate data) throws SQLException {
        Configuracoes config = configuracaoDAO.carregar(con);
        int registrados = 0;
        for (TributoVendaDAO.ItemParaCalculo item : tributoDAO.itensParaCalculo(con, vendaId, data)) {
            tributoDAO.inserir(con, item.vendaItemId(),
                    CalculoTributos.calcular(item.item(), config.loja().regime(), config.tributos()));
            registrados++;
        }
        return registrados;
    }

    /** Soma dos tributos aproximados gravados na venda (comprovante e segunda via, F22.5); nula sem nenhum. */
    public br.com.varesync.model.Comprovante.TributosAproximados aproximados(Connection con, long vendaId)
            throws SQLException {
        return tributoDAO.aproximadosDaVenda(con, vendaId).orElse(null);
    }
}
