package br.com.varesync.service;

import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.PrimeirosPassosDAO;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.PrimeirosPassos;
import br.com.varesync.model.PrimeirosPassos.Etapa;
import br.com.varesync.model.PrimeirosPassos.Passo;
import br.com.varesync.model.PrimeirosPassos.Situacao;
import br.com.varesync.util.Textos;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Assistente "Primeiros passos" do supervisor (F21.4). Cada passo é conferido nos dados; o que não dá para conferir
 * sozinho (formas revisadas, "Só eu uso o sistema" e "Não uso balança") fica nas configurações {@code assistente.*}
 * (V22), gravadas pelo {@link ConfiguracaoService#salvar} (validação e auditoria {@code CONFIGURACAO_ALTERADA}).
 */
public class PrimeirosPassosService {

    private final Transacao transacao;
    private final ConfiguracaoDAO configuracaoDAO;
    private final PrimeirosPassosDAO dao;
    private final ConfiguracaoService configuracoes;

    public PrimeirosPassosService(Transacao transacao, ConfiguracaoDAO configuracaoDAO, PrimeirosPassosDAO dao,
                                  ConfiguracaoService configuracoes) {
        this.transacao = transacao;
        this.configuracaoDAO = configuracaoDAO;
        this.dao = dao;
        this.configuracoes = configuracoes;
    }

    public PrimeirosPassos carregar(Sessao sessao) {
        sessao.exigirSupervisor();
        return transacao.executar(con -> {
            Configuracoes c = configuracaoDAO.carregar(con);
            return montar(c, dao.produtosAtivos(con), dao.usuariosAtivos(con), dao.backupsFeitos(con));
        });
    }

    /** Situação de cada passo a partir das configurações e das contagens (sem banco, para o teste unitário). */
    static PrimeirosPassos montar(Configuracoes c, int produtosAtivos, int usuariosAtivos, int backups) {
        List<Passo> passos = new ArrayList<>();
        passos.add(loja(c.loja()));
        passos.add(c.assistente().formasRevisadas()
                ? new Passo(Etapa.FORMAS, Situacao.CONCLUIDO, "Revisadas.")
                : new Passo(Etapa.FORMAS, Situacao.PENDENTE, "Abra a lista, confira e clique em \"Revisei\"."));
        passos.add(produtosAtivos > 0
                ? new Passo(Etapa.PRODUTOS, Situacao.CONCLUIDO, produtosAtivos == 1 ? "1 produto ativo."
                        : produtosAtivos + " produtos ativos.")
                : new Passo(Etapa.PRODUTOS, Situacao.PENDENTE, "Nenhum produto ativo ainda."));
        if (usuariosAtivos > 1) {
            passos.add(new Passo(Etapa.USUARIOS, Situacao.CONCLUIDO, (usuariosAtivos - 1)
                    + (usuariosAtivos == 2 ? " usuário além do supervisor." : " usuários além do supervisor.")));
        } else if (c.assistente().usuariosDispensado()) {
            passos.add(new Passo(Etapa.USUARIOS, Situacao.DISPENSADO, "Só o supervisor usa o sistema."));
        } else {
            passos.add(new Passo(Etapa.USUARIOS, Situacao.PENDENTE, "Só o supervisor está cadastrado."));
        }
        if (c.balanca().ativo()) {
            passos.add(new Passo(Etapa.BALANCA, Situacao.CONCLUIDO, "Leitura da etiqueta ligada."));
        } else if (c.assistente().balancaDispensada()) {
            passos.add(new Passo(Etapa.BALANCA, Situacao.DISPENSADO, "A loja não usa balança."));
        } else {
            passos.add(new Passo(Etapa.BALANCA, Situacao.PENDENTE, "Leitura da etiqueta desligada."));
        }
        passos.add(backups > 0
                ? new Passo(Etapa.BACKUP, Situacao.CONCLUIDO, "Backup feito.")
                : new Passo(Etapa.BACKUP, Situacao.PENDENTE, "Nenhum backup feito ainda."));
        return new PrimeirosPassos(passos);
    }

    private static Passo loja(Configuracoes.Loja loja) {
        List<String> faltam = new ArrayList<>();
        if (Textos.vazio(loja.nome())) {
            faltam.add("o nome");
        }
        if (Textos.vazio(loja.documento())) {
            faltam.add("o CNPJ");
        }
        if (Textos.vazio(loja.endereco())) {
            faltam.add("o endereço");
        }
        if (Textos.vazio(loja.uf())) {
            faltam.add("a UF");
        }
        if (faltam.isEmpty()) {
            return new Passo(Etapa.LOJA, Situacao.CONCLUIDO, "Preenchidos.");
        }
        String lista = faltam.size() == 1 ? faltam.get(0)
                : String.join(", ", faltam.subList(0, faltam.size() - 1)) + " e " + faltam.get(faltam.size() - 1);
        return new Passo(Etapa.LOJA, Situacao.PENDENTE, (faltam.size() == 1 ? "Falta " : "Faltam ") + lista + ".");
    }

    /** "Revisei" no passo Formas de pagamento. */
    public PrimeirosPassos marcarFormasRevisadas(Sessao sessao) {
        return alterar(sessao, a -> new Configuracoes.Assistente(true, a.usuariosDispensado(), a.balancaDispensada()));
    }

    /** "Só eu uso o sistema" no passo Usuários. */
    public PrimeirosPassos dispensarUsuarios(Sessao sessao) {
        return alterar(sessao, a -> new Configuracoes.Assistente(a.formasRevisadas(), true, a.balancaDispensada()));
    }

    /** "Não uso balança" no passo Balança. */
    public PrimeirosPassos dispensarBalanca(Sessao sessao) {
        return alterar(sessao, a -> new Configuracoes.Assistente(a.formasRevisadas(), a.usuariosDispensado(), true));
    }

    private PrimeirosPassos alterar(Sessao sessao, UnaryOperator<Configuracoes.Assistente> mudanca) {
        Configuracoes atuais = configuracoes.carregar(sessao);
        configuracoes.salvar(sessao, atuais.comAssistente(mudanca.apply(atuais.assistente())));
        return carregar(sessao);
    }
}
