package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Alertas do painel (7.5). Cada um só é calculado para quem pode abrir a tela a que ele leva; nulo (ou vazio) =
 * não se aplica a este usuário.
 *
 * @param abaixoDoMinimo     produtos ativos abaixo do estoque mínimo (ESTOQUE)
 * @param precoAbaixoDoCusto produtos ativos com preço de venda menor que o custo (PRODUTOS)
 * @param caixaAberto        caixa aberto neste terminal (PDV)
 * @param inventariosAbertos inventários abertos (INVENTARIO)
 * @param backupAtrasado     último backup há mais de 7 dias, ou nenhum (só supervisor); nulo = em dia ou não se aplica
 * @param pagarVencidas      contas a pagar em aberto vencidas antes de hoje (FINANCEIRO, F15.6)
 * @param pagarHoje          contas a pagar em aberto que vencem hoje (FINANCEIRO)
 * @param contasNegativas    contas financeiras com saldo negativo (FINANCEIRO); vazia sem a permissão
 * @param rupturas           produtos ativos com saldo ≤ 0 que venderam nos últimos 30 dias (COMPRAS, F16.4)
 * @param lotes              lotes vencendo e vencidos e "SEM LOTE" negativo (ESTOQUE, F17.3)
 * @param etiquetasPendentes produtos com etiqueta de gôndola pendente (PRODUTOS, F18.3)
 * @param cargaPendente      pesáveis pendentes na carga de balança (PRODUTOS, F18.3)
 */
public record AlertasPainel(Integer abaixoDoMinimo, Integer precoAbaixoDoCusto, Optional<CaixaSessao> caixaAberto,
                            Integer inventariosAbertos, BackupAtrasado backupAtrasado,
                            TitulosVencidos titulosVencidos, ContasAPagar pagarVencidas, ContasAPagar pagarHoje,
                            List<ContaFinanceira> contasNegativas, Integer rupturas, Lotes lotes,
                            Integer etiquetasPendentes, Integer cargaPendente) {

    /**
     * Lotes com saldo positivo de produtos ativos com o controle ligado (F17.3).
     *
     * @param dias            o prazo do alerta ({@code validade.alerta_dias})
     * @param vencendo        lotes que vencem de hoje até hoje + dias
     * @param vencidos        lotes com a validade antes de hoje
     * @param semLoteNegativo produtos com o "SEM LOTE" negativo
     */
    public record Lotes(int dias, int vencendo, int vencidos, int semLoteNegativo) {
    }

    /** Títulos em aberto com vencimento antes de hoje: quantos e quanto em aberto (RECEBIMENTO, F14.4). */
    public record TitulosVencidos(int quantidade, BigDecimal valor) {
    }

    /** Contas a pagar em aberto: quantas e quanto falta pagar (F15.6). */
    public record ContasAPagar(int quantidade, BigDecimal valor) {
    }

    /**
     * @param ultimo data do último backup; nula se nunca houve
     * @param dias   dias desde o último backup (0 se nunca houve)
     */
    public record BackupAtrasado(OffsetDateTime ultimo, long dias) {
    }

    public AlertasPainel {
        caixaAberto = caixaAberto == null ? Optional.empty() : caixaAberto;
        contasNegativas = contasNegativas == null ? List.of() : List.copyOf(contasNegativas);
    }

    /** Há algo a mostrar? */
    public boolean algum() {
        return positivo(abaixoDoMinimo) || positivo(precoAbaixoDoCusto) || caixaAberto.isPresent()
                || positivo(inventariosAbertos) || backupAtrasado != null
                || (titulosVencidos != null && titulosVencidos.quantidade() > 0)
                || (pagarVencidas != null && pagarVencidas.quantidade() > 0)
                || (pagarHoje != null && pagarHoje.quantidade() > 0) || !contasNegativas.isEmpty()
                || positivo(rupturas)
                || (lotes != null && (lotes.vencendo() > 0 || lotes.vencidos() > 0 || lotes.semLoteNegativo() > 0))
                || positivo(etiquetasPendentes) || positivo(cargaPendente);
    }

    private static boolean positivo(Integer valor) {
        return valor != null && valor > 0;
    }
}
