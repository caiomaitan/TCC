package br.com.varesync.controller;

import br.com.varesync.model.Permissao;
import br.com.varesync.service.Sessao;
import java.util.Arrays;
import java.util.List;

/**
 * Itens do menu lateral (8.1) e o que cada um exige. O menu mostra só o permitido;
 * a verificação que vale é a do service. O PDV abre em janela própria, maximizada; os demais, na área de conteúdo.
 */
public enum ItemMenu {
    PAINEL("Painel", null, false, null, false),
    PDV("PDV", Permissao.PDV, false, "/fxml/pdv.fxml", true),
    CAIXA("Caixa", Permissao.PDV, false, "/fxml/caixa.fxml", false),
    CLIENTES("Clientes", Permissao.CLIENTES, false, "/fxml/clientes.fxml", false),
    PRODUTOS("Produtos", Permissao.PRODUTOS, false, "/fxml/produtos.fxml", false),
    ESTOQUE("Estoque", Permissao.ESTOQUE, false, "/fxml/estoque.fxml", false),
    INVENTARIO("Inventário", Permissao.INVENTARIO, false, "/fxml/inventario.fxml", false),
    COMPRAS("Compras", Permissao.COMPRAS, false, "/fxml/compras.fxml", false),
    FORNECEDORES("Fornecedores", Permissao.FORNECEDORES, false, "/fxml/fornecedores.fxml", false),
    RELATORIOS("Relatórios", Permissao.RELATORIOS, false, "/fxml/relatorios.fxml", false),
    USUARIOS("Usuários", null, true, "/fxml/usuarios.fxml", false),
    CONFIGURACOES("Configurações", null, true, "/fxml/configuracoes.fxml", false);

    private final String rotulo;
    private final Permissao permissao;
    private final boolean soSupervisor;
    private final String fxml;
    private final boolean janelaPropria;

    ItemMenu(String rotulo, Permissao permissao, boolean soSupervisor, String fxml, boolean janelaPropria) {
        this.rotulo = rotulo;
        this.permissao = permissao;
        this.soSupervisor = soSupervisor;
        this.fxml = fxml;
        this.janelaPropria = janelaPropria;
    }

    public String rotulo() {
        return rotulo;
    }

    /** Tela da rotina; nulo enquanto ela não foi implementada. */
    public String fxml() {
        return fxml;
    }

    /** Abre em janela própria (o PDV), em vez da área de conteúdo da janela principal. */
    public boolean janelaPropria() {
        return janelaPropria;
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
