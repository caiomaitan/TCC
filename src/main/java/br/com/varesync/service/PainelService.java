package br.com.varesync.service;

import br.com.varesync.dao.EstoqueDAO;
import br.com.varesync.dao.PainelDAO;
import br.com.varesync.dao.AuditoriaDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.AlertasPainel;
import br.com.varesync.model.CaixaSessao;
import br.com.varesync.model.DiaPainel;
import br.com.varesync.model.IndicadoresDia;
import br.com.varesync.model.Painel;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.ProdutoPainel;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Painel da tela principal (7.5), calculado numa única transação de leitura (a tela chama em {@code Task}).
 *
 * <ul>
 * <li>Só com RELATORIOS (6.2): cartões de hoje com a variação em relação ao mesmo dia da semana anterior, até a
 * mesma hora; faturamento e lucro dos últimos 30 dias (hoje incluído); top 5 do mês por lucro.</li>
 * <li>Alertas, cada um para quem pode abrir a tela a que ele leva: abaixo do mínimo (ESTOQUE), preço abaixo do custo
 * (PRODUTOS), caixa aberto neste terminal (PDV) e inventários abertos (INVENTARIO). O alerta de backup é da
 * Fase 8.</li>
 * </ul>
 */
public class PainelService {

    static final int DIAS_GRAFICO = 30;
    static final int TOP_PRODUTOS = 5;

    private final Transacao transacao;
    private final PainelDAO painelDAO;
    private final EstoqueDAO estoqueDAO;
    private final CaixaService caixaService;
    private final AuditoriaDAO auditoriaDAO;
    private final Clock clock;

    public PainelService(Transacao transacao, PainelDAO painelDAO, EstoqueDAO estoqueDAO, CaixaService caixaService,
                         AuditoriaDAO auditoriaDAO,
                         Clock clock) {
        this.transacao = transacao;
        this.painelDAO = painelDAO;
        this.estoqueDAO = estoqueDAO;
        this.caixaService = caixaService;
        this.auditoriaDAO = auditoriaDAO;
        this.clock = clock;
    }

    public Painel carregar(Sessao informada) {
        Sessao sessao = informada.conferida(); // o painel segue as permissões atuais (F10.1)
        OffsetDateTime agora = OffsetDateTime.now(clock);
        ZoneId zona = clock.getZone();
        LocalDate hoje = agora.atZoneSameInstant(zona).toLocalDate();
        boolean comIndicadores = sessao.pode(Permissao.RELATORIOS);
        return transacao.executar(con -> {
            IndicadoresDia diaHoje = IndicadoresDia.VAZIO;
            IndicadoresDia diaAnterior = IndicadoresDia.VAZIO;
            List<DiaPainel> dias = List.of();
            List<ProdutoPainel> top = List.of();
            if (comIndicadores) {
                OffsetDateTime inicioGrafico = hoje.minusDays(DIAS_GRAFICO - 1L).atStartOfDay(zona).toOffsetDateTime();
                OffsetDateTime amanha = hoje.plusDays(1).atStartOfDay(zona).toOffsetDateTime();
                List<PainelDAO.VendaPainel> vendas = painelDAO.vendas(con, inicioGrafico, amanha);
                dias = porDia(vendas, hoje, zona);
                diaHoje = somar(vendas, hoje.atStartOfDay(zona).toOffsetDateTime(), agora);
                diaAnterior = somar(vendas, hoje.minusWeeks(1).atStartOfDay(zona).toOffsetDateTime(),
                        agora.minusWeeks(1));
                top = painelDAO.maisLucrativos(con, hoje.withDayOfMonth(1).atStartOfDay(zona).toOffsetDateTime(),
                        amanha, TOP_PRODUTOS);
            }
            return new Painel(comIndicadores, diaHoje, diaAnterior, hoje.minusWeeks(1), dias, top,
                    alertas(con, sessao), agora);
        });
    }

    private AlertasPainel alertas(Connection con, Sessao sessao) throws SQLException {
        Integer abaixo = sessao.pode(Permissao.ESTOQUE) ? estoqueDAO.contarAbaixoDoMinimo(con) : null;
        Integer custo = sessao.pode(Permissao.PRODUTOS) ? painelDAO.contarPrecoAbaixoDoCusto(con) : null;
        Optional<CaixaSessao> caixa = sessao.pode(Permissao.PDV) ? caixaService.caixaAberto(con, sessao)
                : Optional.empty();
        Integer inventarios = sessao.pode(Permissao.INVENTARIO) ? painelDAO.contarInventariosAbertos(con) : null;
        return new AlertasPainel(abaixo, custo, caixa, inventarios, sessao.supervisor() ? backupAtrasado(con) : null);
    }

    /** Último backup há mais de {@link BackupService#DIAS_ALERTA} dias (pelo último {@code BACKUP_REALIZADO}), ou nenhum. */
    private AlertasPainel.BackupAtrasado backupAtrasado(Connection con) throws SQLException {
        Optional<OffsetDateTime> ultimo = auditoriaDAO.ultimo(con, AcaoAuditoria.BACKUP_REALIZADO);
        if (ultimo.isEmpty()) {
            return new AlertasPainel.BackupAtrasado(null, 0);
        }
        long dias = ChronoUnit.DAYS.between(ultimo.get().atZoneSameInstant(clock.getZone()).toLocalDate(),
                LocalDate.now(clock));
        return dias > BackupService.DIAS_ALERTA ? new AlertasPainel.BackupAtrasado(ultimo.get(), dias) : null;
    }

    /** Vendas e devoluções com {@code inicio <= data_hora < fim}. */
    static IndicadoresDia somar(List<PainelDAO.VendaPainel> vendas, OffsetDateTime inicio, OffsetDateTime fim) {
        IndicadoresDia total = IndicadoresDia.VAZIO;
        for (PainelDAO.VendaPainel v : vendas) {
            if (!v.dataHora().isBefore(inicio) && v.dataHora().isBefore(fim)) {
                total = v.venda() ? total.somar(v.receita(), v.lucro())
                        : total.somarDevolucao(v.receita(), v.lucro());
            }
        }
        return total;
    }

    /** Os 30 dias até hoje, no fuso do sistema; dia sem venda com zero. */
    static List<DiaPainel> porDia(List<PainelDAO.VendaPainel> vendas, LocalDate hoje, ZoneId zona) {
        Map<LocalDate, BigDecimal[]> dias = new LinkedHashMap<>();
        for (int i = DIAS_GRAFICO - 1; i >= 0; i--) {
            dias.put(hoje.minusDays(i), new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
        }
        for (PainelDAO.VendaPainel v : vendas) {
            BigDecimal[] dia = dias.get(v.dataHora().atZoneSameInstant(zona).toLocalDate());
            if (dia != null) {
                dia[0] = dia[0].add(v.receita());
                dia[1] = dia[1].add(v.lucro());
            }
        }
        List<DiaPainel> lista = new ArrayList<>();
        dias.forEach((dia, valores) -> lista.add(new DiaPainel(dia, valores[0], valores[1])));
        return lista;
    }
}
