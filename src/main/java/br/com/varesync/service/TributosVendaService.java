package br.com.varesync.service;

import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.TributoVendaDAO;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.TributoItem;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Corrigir e reenviar (F23.9): apaga os tributos gravados e calcula de novo, com o cadastro atual e a tabela IBPT
     * vigente em {@code data}.
     */
    public int recalcular(Connection con, long vendaId, LocalDate data) throws SQLException {
        tributoDAO.apagarDaVenda(con, vendaId);
        return registrar(con, vendaId, data);
    }

    /**
     * Na NFC-e de contingência, a lei manda manter os valores (Ajuste SINIEF 19/16): o que mudaria nos valores dos
     * impostos se os tributos fossem calculados de novo com o cadastro atual (a tabela IBPT vigente em {@code data}).
     *
     * @return "item 2 (Arroz): ICMS, PIS"; vazio quando só os códigos mudam
     */
    public List<String> valoresQueMudariam(Connection con, long vendaId, LocalDate data) throws SQLException {
        Configuracoes config = configuracaoDAO.carregar(con);
        Map<Long, TributoItem> novos = new HashMap<>();
        for (TributoVendaDAO.ItemParaCalculo item : tributoDAO.itensParaCalculo(con, vendaId, data)) {
            novos.put(item.vendaItemId(), CalculoTributos.calcular(item.item(), config.loja().regime(),
                    config.tributos()));
        }
        List<String> mudancas = new ArrayList<>();
        for (Map.Entry<Long, String> item : tributoDAO.itensDaVenda(con, vendaId).entrySet()) {
            TributoItem antes = tributoDAO.buscar(con, item.getKey()).orElse(null);
            TributoItem depois = novos.get(item.getKey());
            List<String> campos = camposQueMudam(antes, depois);
            if (!campos.isEmpty()) {
                mudancas.add(item.getValue() + ": " + String.join(", ", campos));
            }
        }
        return mudancas;
    }

    /** Os valores (não os códigos) que mudam entre dois cálculos do mesmo item; nulo = sem tributos. */
    static List<String> camposQueMudam(TributoItem antes, TributoItem depois) {
        if (antes == null && depois == null) {
            return List.of();
        }
        if (antes == null || depois == null) {
            return List.of(antes == null ? "passaria a ter tributos" : "ficaria sem tributos");
        }
        List<String> campos = new ArrayList<>();
        comparar(campos, "base do ICMS", antes.baseIcms(), depois.baseIcms());
        comparar(campos, "ICMS", antes.valorIcms(), depois.valorIcms());
        comparar(campos, "FCP", antes.valorFcp(), depois.valorFcp());
        comparar(campos, "ST retido", antes.icmsStRetido(), depois.icmsStRetido());
        comparar(campos, "base do ST retido", antes.baseStRetido(), depois.baseStRetido());
        comparar(campos, "ICMS do substituto", antes.icmsSubstituto(), depois.icmsSubstituto());
        comparar(campos, "PIS", antes.valorPis(), depois.valorPis());
        comparar(campos, "COFINS", antes.valorCofins(), depois.valorCofins());
        comparar(campos, "base do IBS e da CBS", antes.baseIbsCbs(), depois.baseIbsCbs());
        comparar(campos, "CBS", antes.valorCbs(), depois.valorCbs());
        comparar(campos, "IBS da UF", antes.valorIbsUf(), depois.valorIbsUf());
        comparar(campos, "IBS do município", antes.valorIbsMun(), depois.valorIbsMun());
        comparar(campos, "tributos aproximados", antes.tributosAproximados(), depois.tributosAproximados());
        return campos;
    }

    /** Nulo vale zero: o grupo sem o valor e o valor zero dão o mesmo imposto. */
    private static void comparar(List<String> campos, String nome, BigDecimal a, BigDecimal b) {
        BigDecimal x = a == null ? BigDecimal.ZERO : a;
        BigDecimal y = b == null ? BigDecimal.ZERO : b;
        if (x.compareTo(y) != 0) {
            campos.add(nome);
        }
    }

    /** Soma dos tributos aproximados gravados na venda (comprovante e segunda via, F22.5); nula sem nenhum. */
    public br.com.varesync.model.Comprovante.TributosAproximados aproximados(Connection con, long vendaId)
            throws SQLException {
        return tributoDAO.aproximadosDaVenda(con, vendaId).orElse(null);
    }
}
