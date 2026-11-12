package br.com.varesync.model;

import java.util.List;

/**
 * Assistente "Primeiros passos" do supervisor (F21.4): os seis passos de quem começa a usar o sistema, cada um
 * concluído, dispensado ou pendente.
 */
public record PrimeirosPassos(List<Passo> passos) {

    public PrimeirosPassos {
        passos = List.copyOf(passos);
    }

    /** Os passos, na ordem em que aparecem. */
    public enum Etapa {
        LOJA("Dados da loja", "Nome, CNPJ, endereço e UF, que saem no comprovante e nos relatórios."),
        FORMAS("Formas de pagamento", "Confira as formas que a loja aceita e desative as que não usa."),
        PRODUTOS("Produtos", "Cadastre os produtos, um a um ou pela planilha (Importar planilha…)."),
        USUARIOS("Usuários", "Crie um usuário para cada pessoa que usa o sistema, com as rotinas dela."),
        BALANCA("Balança", "Ligue a leitura da etiqueta da balança no PDV, se a loja vende por peso."),
        BACKUP("Backup", "Faça a primeira cópia de segurança do banco.");

        private final String titulo;
        private final String descricao;

        Etapa(String titulo, String descricao) {
            this.titulo = titulo;
            this.descricao = descricao;
        }

        public String titulo() {
            return titulo;
        }

        public String descricao() {
            return descricao;
        }
    }

    public enum Situacao {
        PENDENTE("Pendente"), CONCLUIDO("Concluído"), DISPENSADO("Dispensado");

        private final String rotulo;

        Situacao(String rotulo) {
            this.rotulo = rotulo;
        }

        public String rotulo() {
            return rotulo;
        }
    }

    /**
     * @param detalhe o que falta ou o que foi feito ("Faltam o CNPJ e a UF", "3 produtos ativos")
     */
    public record Passo(Etapa etapa, Situacao situacao, String detalhe) {

        public boolean pendente() {
            return situacao == Situacao.PENDENTE;
        }
    }

    public Passo passo(Etapa etapa) {
        return passos.stream().filter(p -> p.etapa() == etapa).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(etapa.name()));
    }

    /** Todos concluídos ou dispensados: o assistente some do painel. */
    public boolean terminado() {
        return passos.stream().noneMatch(Passo::pendente);
    }

    /** Concluídos ou dispensados. */
    public long feitos() {
        return passos.stream().filter(p -> !p.pendente()).count();
    }
}
