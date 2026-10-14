package br.com.varesync.controller;

import br.com.varesync.model.Permissao;
import br.com.varesync.service.Sessao;
import java.util.Arrays;
import java.util.List;

/**
 * Itens do menu lateral (8.1) e o que cada um exige. O menu mostra só o permitido;
 * a verificação que vale é a do service.
 */
public enum ItemMenu {
    PAINEL("Painel", null, false, null),
    PDV("PDV", Permissao.PDV, false, null),
    PRODUTOS("Produtos", Permissao.PRODUTOS, false, "/fxml/produtos.fxml"),
    ESTOQUE("Estoque", Permissao.ESTOQUE, false, "/fxml/estoque.fxml"),
    INVENTARIO("Inventário", Permissao.INVENTARIO, false, null),
    COMPRAS("Compras", Permissao.COMPRAS, false, null),
    FORNECEDORES("Fornecedores", Permissao.FORNECEDORES, false, null),
    RELATORIOS("Relatórios", Permissao.RELATORIOS, false, null),
    USUARIOS("Usuários", null, true, "/fxml/usuarios.fxml"),
    CONFIGURACOES("Configurações", null, true, "/fxml/configuracoes.fxml");

    private final String rotulo;
    private final Permissao permissao;
    private final boolean soSupervisor;
    private final String fxml;

    ItemMenu(String rotulo, Permissao permissao, boolean soSupervisor, String fxml) {
        this.rotulo = rotulo;
        this.permissao = permissao;
        this.soSupervisor = soSupervisor;
        this.fxml = fxml;
    }

    public String rotulo() {
        return rotulo;
    }

    /** Tela da rotina; nulo enquanto ela não foi implementada. */
    public String fxml() {
        return fxml;
    }

    public boolean visivelPara(Sessao sessao) {
        if (soSupervisor) {
            return sessao.supervisor();
        }
        return permissao == null || sessao.pode(permissao);
    }

    public static List<ItemMenu> visiveis(Sessao sessao) {
        return Arrays.stream(values()).filter(item -> item.visivelPara(sessao)).toList();
    }
}
