package br.com.varesync.controller;

import br.com.varesync.model.ComprovanteRecebimento;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.RecebimentoSessao;
import br.com.varesync.report.ComprovantePdf;
import br.com.varesync.report.ImpressaoComprovante;
import br.com.varesync.service.AcessoService;
import br.com.varesync.service.CaixaService;
import br.com.varesync.service.Liberacao;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.RecebimentoService;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.Janelas;
import br.com.varesync.ui.DialogosPdv;
import br.com.varesync.ui.LiberacaoSupervisorDialogo;
import java.util.Optional;
import javafx.stage.Window;

/**
 * Recebimento do crediário com os diálogos, usado pela tela Caixa e pelo PDV (Ctrl+R): liberação, a janela do
 * recebimento, o comprovante e o estorno.
 */
public class AcoesRecebimento {

    private final RecebimentoService service;
    private final CaixaService caixaService;
    private final AcessoService acessoService;
    private final ComprovantePdf comprovantePdf;
    private final ImpressaoComprovante impressao;
    private final Sessao sessao;

    public AcoesRecebimento(RecebimentoService service, CaixaService caixaService, AcessoService acessoService,
                            ComprovantePdf comprovantePdf, ImpressaoComprovante impressao, Sessao sessao) {
        this.service = service;
        this.caixaService = caixaService;
        this.acessoService = acessoService;
        this.comprovantePdf = comprovantePdf;
        this.impressao = impressao;
        this.sessao = sessao;
    }

    /**
     * Abre o recebimento: exige caixa aberto; sem RECEBIMENTO, pede antes a liberação. Gravado, mostra o comprovante.
     *
     * @return verdadeiro se gravou
     */
    public boolean receber(Window dona) {
        if (caixaService.caixaAberto(sessao).isEmpty()) {
            throw new NegocioException("Abra o caixa deste terminal para receber o crediário.");
        }
        Liberacao liberacao = null;
        if (!sessao.pode(Permissao.RECEBIMENTO)) {
            Optional<Liberacao> concedida = LiberacaoSupervisorDialogo.solicitar("Receber crediário",
                    (login, senha) -> acessoService.liberar(login, senha, Permissao.RECEBIMENTO));
            if (concedida.isEmpty()) {
                return false;
            }
            liberacao = concedida.get();
        }
        Liberacao liberada = liberacao;
        RecebimentoController fechado = Janelas.abrirModal(dona, "/fxml/recebimento.fxml", "Receber crediário",
                tipo -> new RecebimentoController(service, acessoService, sessao, liberada),
                (RecebimentoController c) -> { });
        Optional<ComprovanteRecebimento> comprovante = fechado.resultado();
        comprovante.ifPresent(c -> ComprovanteController.abrir(dona, c, comprovantePdf, impressao));
        return comprovante.isPresent();
    }

    /** Reimprime o comprovante do recebimento. */
    public void mostrarComprovante(Window dona, long recebimentoId) {
        ComprovanteController.abrir(dona, service.comprovante(sessao, recebimentoId), comprovantePdf, impressao);
    }

    /**
     * Estorno (F14.3): sem CANCELAR_VENDA, pede antes a liberação; depois, o motivo.
     *
     * @return verdadeiro se estornou
     */
    public boolean estornar(RecebimentoSessao recebimento) {
        Liberacao liberacao = null;
        if (!sessao.pode(Permissao.CANCELAR_VENDA)) {
            Optional<Liberacao> concedida = LiberacaoSupervisorDialogo.solicitar("Estornar recebimento",
                    (login, senha) -> acessoService.liberar(login, senha, Permissao.CANCELAR_VENDA));
            if (concedida.isEmpty()) {
                return false;
            }
            liberacao = concedida.get();
        }
        Liberacao liberada = liberacao;
        return DialogosPdv.motivoCancelamento("Estornar o recebimento nº " + recebimento.id(),
                "Volta o que foi pago dos títulos e deixa de contar no dinheiro do caixa. Informe o motivo.", motivo -> {
                    service.estornar(sessao, recebimento.id(), motivo, liberada);
                    return Boolean.TRUE;
                }).isPresent();
    }
}
