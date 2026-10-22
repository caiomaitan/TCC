package br.com.varesync.controller;

import br.com.varesync.model.CaixaSessao;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.RelatorioFechamento;
import br.com.varesync.model.TipoCaixaMovimento;
import br.com.varesync.report.FechamentoCaixaPdf;
import br.com.varesync.service.AcessoService;
import br.com.varesync.service.CaixaService;
import br.com.varesync.service.Liberacao;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.CampoNumero;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.DialogosCaixa;
import br.com.varesync.ui.Janelas;
import br.com.varesync.ui.LiberacaoSupervisorDialogo;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.util.Optional;
import javafx.stage.Window;

/**
 * Operações de caixa com os diálogos de {@link DialogosCaixa}, usadas pela tela Caixa, pelo PDV (F8 e F12) e pela
 * abertura do caixa antes de vender: abrir, sangria/suprimento (com a liberação de supervisor) e fechamento às cegas.
 */
public class AcoesCaixa {

    private final CaixaService caixaService;
    private final AcessoService acessoService;
    private final FechamentoCaixaPdf fechamentoPdf;
    private final Sessao sessao;

    public AcoesCaixa(CaixaService caixaService, AcessoService acessoService, FechamentoCaixaPdf fechamentoPdf,
                      Sessao sessao) {
        this.caixaService = caixaService;
        this.acessoService = acessoService;
        this.fechamentoPdf = fechamentoPdf;
        this.sessao = sessao;
    }

    /** @return o caixa aberto, ou vazio se cancelado */
    public Optional<CaixaSessao> abrir() {
        return DialogosCaixa.abrir(sessao.terminal(),
                texto -> caixaService.abrir(sessao, CampoNumero.ler(texto, "Fundo de troco")));
    }

    /**
     * Sangria ou suprimento (F8). Sem a permissão, pede antes a liberação de supervisor.
     *
     * @return a mensagem de sucesso, ou vazio se cancelado
     */
    public Optional<String> movimentar() {
        Liberacao liberacao = null;
        if (!sessao.pode(Permissao.SANGRIA_SUPRIMENTO)) {
            Optional<Liberacao> concedida = LiberacaoSupervisorDialogo.solicitar("Sangria ou suprimento",
                    (login, senha) -> acessoService.liberar(login, senha, Permissao.SANGRIA_SUPRIMENTO));
            if (concedida.isEmpty()) {
                return Optional.empty();
            }
            liberacao = concedida.get();
        }
        Liberacao liberada = liberacao;
        return DialogosCaixa.movimentar((tipo, valor, motivo) -> {
            BigDecimal lido = CampoNumero.ler(valor, "Valor");
            caixaService.registrarMovimento(sessao, tipo, lido, motivo, liberada);
            return tipo.descricao() + " de " + Formatos.moeda(Dinheiro.dinheiro(lido)) + " registrad"
                    + (tipo == TipoCaixaMovimento.SUPRIMENTO ? "o" : "a") + ".";
        });
    }

    /**
     * Fechamento às cegas (F12): pede o dinheiro contado e confirma antes de gravar.
     *
     * @return o relatório R11, ou vazio se cancelado
     */
    public Optional<RelatorioFechamento> fechar() {
        return DialogosCaixa.fechar(sessao.terminal(), texto -> {
            BigDecimal contado = CampoNumero.ler(texto, "Dinheiro contado");
            if (contado == null) {
                throw new NegocioException("Informe o dinheiro contado.");
            }
            if (!Dialogos.confirmar("Fechar o caixa com " + Formatos.moeda(Dinheiro.dinheiro(contado))
                    + " contados? Depois de fechado não é possível alterar.")) {
                return null;
            }
            return caixaService.fechar(sessao, contado);
        });
    }

    /** Relatório de fechamento (R11) numa janela modal, com a exportação em CSV e PDF. */
    public void mostrarRelatorio(Window dona, RelatorioFechamento relatorio) {
        Janelas.abrirModal(dona, "/fxml/fechamento-caixa.fxml", "Fechamento de caixa nº " + relatorio.sessao().id(),
                tipo -> new FechamentoCaixaController(fechamentoPdf, () -> acessoService.emissao(sessao)),
                (FechamentoCaixaController c) -> c.mostrar(relatorio));
    }
}
