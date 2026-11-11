package br.com.varesync.controller;

import br.com.varesync.service.BuscaGlobalService;
import br.com.varesync.service.Sessao;
import br.com.varesync.util.Textos;
import java.util.List;

/** As telas do menu na busca global (Ctrl+K, F21.3): só as que o usuário vê no menu, pelo nome sem acento. */
final class BuscaTelas {

    private BuscaTelas() {
    }

    static List<ItemMenu> filtrar(String texto, Sessao sessao) {
        String termo = Textos.normalizarBusca(Textos.normalizarEspacos(texto));
        if (termo.length() < BuscaGlobalService.MINIMO) {
            return List.of();
        }
        return ItemMenu.visiveis(sessao).stream()
                .filter(item -> Textos.normalizarBusca(item.rotulo()).contains(termo))
                .toList();
    }
}
