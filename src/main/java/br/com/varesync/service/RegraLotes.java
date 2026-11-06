package br.com.varesync.service;

import br.com.varesync.dao.LoteDAO;
import br.com.varesync.model.Lote;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Regras de distribuição de um movimento nos lotes (F17.2), sem banco. As quantidades são positivas; quem grava põe o
 * sinal do movimento.
 */
final class RegraLotes {

    /** Parte num lote; {@code loteId} nulo = o "SEM LOTE", que ainda não existe e será criado. */
    record Parte(Long loteId, BigDecimal quantidade) {
    }

    private RegraLotes() {
    }

    /**
     * Saída sem lote escolhido: primeiro o saldo positivo do "SEM LOTE" (validade desconhecida conta como a mais
     * próxima), depois os lotes com saldo por FEFO (validade mais próxima e, no empate, o lote mais antigo); o que
     * faltar sai do "SEM LOTE", que pode ficar negativo.
     */
    static List<Parte> saida(List<Lote> lotes, BigDecimal quantidade) {
        Lote semLote = lotes.stream().filter(Lote::semLote).findFirst().orElse(null);
        Map<Long, BigDecimal> partes = new LinkedHashMap<>();
        BigDecimal resta = quantidade;
        if (semLote != null && semLote.saldo().signum() > 0) {
            BigDecimal sai = semLote.saldo().min(resta);
            partes.merge(semLote.id(), sai, BigDecimal::add);
            resta = resta.subtract(sai);
        }
        List<Lote> fefo = lotes.stream()
                .filter(l -> !l.semLote() && l.saldo().signum() > 0)
                .sorted(Comparator.comparing(Lote::validade)
                        .thenComparing(Lote::criadoEm)
                        .thenComparingLong(Lote::id))
                .toList();
        for (Lote l : fefo) {
            if (resta.signum() <= 0) {
                break;
            }
            BigDecimal sai = l.saldo().min(resta);
            partes.merge(l.id(), sai, BigDecimal::add);
            resta = resta.subtract(sai);
        }
        List<Parte> resultado = new ArrayList<>();
        partes.forEach((id, q) -> resultado.add(new Parte(id, q)));
        if (resta.signum() > 0) {
            juntarNoSemLote(resultado, semLote, resta);
        }
        return resultado;
    }

    /**
     * Devolução: volta na ordem inversa do consumo da venda (a última linha primeiro), descontado o que já voltou
     * para cada lote em devoluções anteriores do mesmo item. Venda sem linhas (controle desligado na venda) ou o que
     * sobrar vão para o "SEM LOTE".
     *
     * @param consumo    linhas da venda, na ordem de gravação (quantidades negativas, como no razão)
     * @param devolvido  quanto já voltou para cada lote (positivo)
     * @param semLoteId  o "SEM LOTE" do produto, ou nulo se ainda não existe
     */
    static List<Parte> devolucao(List<LoteDAO.Linha> consumo, Map<Long, BigDecimal> devolvido, Long semLoteId,
                                 BigDecimal quantidade) {
        Map<Long, BigDecimal> jaVoltou = new HashMap<>(devolvido);
        Map<Long, BigDecimal> partes = new LinkedHashMap<>();
        BigDecimal resta = quantidade;
        for (int i = consumo.size() - 1; i >= 0 && resta.signum() > 0; i--) {
            LoteDAO.Linha linha = consumo.get(i);
            BigDecimal saiu = linha.quantidade().abs();
            BigDecimal voltou = jaVoltou.getOrDefault(linha.loteId(), BigDecimal.ZERO).min(saiu);
            jaVoltou.merge(linha.loteId(), voltou.negate(), BigDecimal::add);
            BigDecimal volta = saiu.subtract(voltou).min(resta);
            if (volta.signum() > 0) {
                partes.merge(linha.loteId(), volta, BigDecimal::add);
                resta = resta.subtract(volta);
            }
        }
        List<Parte> resultado = new ArrayList<>();
        partes.forEach((id, q) -> resultado.add(new Parte(id, q)));
        if (resta.signum() > 0) {
            if (semLoteId != null && partes.containsKey(semLoteId)) {
                substituir(resultado, semLoteId, resta);
            } else {
                resultado.add(new Parte(semLoteId, resta));
            }
        }
        return resultado;
    }

    /** Cancelamento: volta exatamente para os lotes de onde saiu (vazio: tudo para o "SEM LOTE"). */
    static List<Parte> cancelamento(List<LoteDAO.Linha> consumo, Long semLoteId, BigDecimal quantidade) {
        return devolucao(consumo, Map.of(), semLoteId, quantidade);
    }

    private static void juntarNoSemLote(List<Parte> partes, Lote semLote, BigDecimal resta) {
        if (semLote != null) {
            for (int i = 0; i < partes.size(); i++) {
                if (semLote.id() == partes.get(i).loteId()) {
                    partes.set(i, new Parte(semLote.id(), partes.get(i).quantidade().add(resta)));
                    return;
                }
            }
        }
        partes.add(new Parte(semLote == null ? null : semLote.id(), resta));
    }

    private static void substituir(List<Parte> partes, long loteId, BigDecimal mais) {
        for (int i = 0; i < partes.size(); i++) {
            if (partes.get(i).loteId() == loteId) {
                partes.set(i, new Parte(loteId, partes.get(i).quantidade().add(mais)));
                return;
            }
        }
    }
}
