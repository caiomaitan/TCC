package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
 */
public record AlertasPainel(Integer abaixoDoMinimo, Integer precoAbaixoDoCusto, Optional<CaixaSessao> caixaAberto,
                            Integer inventariosAbertos, BackupAtrasado backupAtrasado,
                            TitulosVencidos titulosVencidos) {

    /** Títulos em aberto com vencimento antes de hoje: quantos e quanto em aberto (RECEBIMENTO, F14.4). */
    public record TitulosVencidos(int quantidade, BigDecimal valor) {
    }

    /**
     * @param ultimo data do último backup; nula se nunca houve
     * @param dias   dias desde o último backup (0 se nunca houve)
     */
    public record BackupAtrasado(OffsetDateTime ultimo, long dias) {
    }

    public AlertasPainel {
        caixaAberto = caixaAberto == null ? Optional.empty() : caixaAberto;
    }

    /** Há algo a mostrar? */
    public boolean algum() {
        return positivo(abaixoDoMinimo) || positivo(precoAbaixoDoCusto) || caixaAberto.isPresent()
                || positivo(inventariosAbertos) || backupAtrasado != null
                || (titulosVencidos != null && titulosVencidos.quantidade() > 0);
    }

    private static boolean positivo(Integer valor) {
        return valor != null && valor > 0;
    }
}
