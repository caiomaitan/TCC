package br.com.varesync.service.fiscal;

import br.com.varesync.model.PerfilTributario;
import java.util.List;

/**
 * Um produto ativo com o perfil dele, como as regras do R30 o veem (F19.4). As consultas às tabelas importadas vêm
 * prontas do banco.
 *
 * @param ncm                  como está gravado (pode estar fora do formato, se gravado fora do sistema); nulo = vazio
 * @param cest                 idem
 * @param perfil               nulo = sem perfil tributário
 * @param ncmNaTabela          o NCM existe na tabela NCM; nulo = sem tabela importada, ou NCM vazio ou fora do formato
 * @param cestNaTabela         o CEST existe na tabela CEST; nulo = sem tabela importada, ou CEST vazio ou fora do
 *                             formato
 * @param cestCorrespondeAoNcm algum par da tabela CEST tem este CEST e um NCM pelo qual o NCM do produto começa; nulo =
 *                             sem tabela, sem CEST ou NCM válidos
 * @param outrosPerfisDoNcm    nomes dos outros perfis em produtos ativos com o mesmo NCM (regra 13)
 */
public record ItemFiscal(long produtoId, String produto, String categoria, String ncm, String cest, Integer origem,
                         PerfilTributario perfil, Boolean ncmNaTabela, Boolean cestNaTabela,
                         Boolean cestCorrespondeAoNcm, List<String> outrosPerfisDoNcm) {

    public ItemFiscal {
        outrosPerfisDoNcm = outrosPerfisDoNcm == null ? List.of() : List.copyOf(outrosPerfisDoNcm);
    }
}
