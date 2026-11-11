package br.com.varesync.controller;

import br.com.varesync.model.Permissao;
import br.com.varesync.report.ComprovantePdf;
import br.com.varesync.report.ImpressaoComprovante;
import br.com.varesync.service.AcessoService;
import br.com.varesync.service.ClienteService;
import br.com.varesync.service.DevolucaoService;
import br.com.varesync.service.Sessao;
import br.com.varesync.service.VendaService;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.TratadorExcecoes;
import br.com.varesync.util.Formatos;
import java.util.Optional;
import javafx.stage.Window;

/**
 * O que a venda achada pela busca global abre (F21.3): a segunda via do comprovante e, para quem tem DEVOLUCAO, o
 * "Devolver itens…" dela (a mesma janela de Devoluções).
 */
public class AcoesBusca {

    private final VendaService vendaService;
    private final DevolucaoService devolucaoService;
    private final ClienteService clienteService;
    private final AcessoService acessoService;
    private final ComprovantePdf comprovantePdf;
    private final ImpressaoComprovante impressao;

    public AcoesBusca(VendaService vendaService, DevolucaoService devolucaoService, ClienteService clienteService,
                      AcessoService acessoService, ComprovantePdf comprovantePdf, ImpressaoComprovante impressao) {
        this.vendaService = vendaService;
        this.devolucaoService = devolucaoService;
        this.clienteService = clienteService;
        this.acessoService = acessoService;
        this.comprovantePdf = comprovantePdf;
        this.impressao = impressao;
    }

    public void segundaVia(Window dona, Sessao sessao, long vendaId) {
        try {
            Runnable devolver = sessao.pode(Permissao.DEVOLUCAO) ? () -> devolver(dona, sessao, vendaId) : null;
            ComprovanteController.abrir(dona, vendaService.segundaVia(sessao, vendaId), comprovantePdf, impressao,
                    devolver);
        } catch (RuntimeException e) {
            TratadorExcecoes.mostrar(e);
        }
    }

    private void devolver(Window dona, Sessao sessao, long vendaId) {
        try {
            Optional<DevolucaoService.DevolucaoRegistrada> feita = DevolucaoController.abrir(dona, devolucaoService,
                    clienteService, acessoService, sessao, vendaId);
            if (feita.isPresent() && Dialogos.confirmar(dona, "Devolução nº " + feita.get().id() + " registrada: "
                    + Formatos.moeda(feita.get().valor()) + ". Mostrar o comprovante?")) {
                ComprovanteController.abrir(dona, feita.get().comprovante(), comprovantePdf, impressao);
            }
        } catch (RuntimeException e) {
            TratadorExcecoes.mostrar(e);
        }
    }
}
