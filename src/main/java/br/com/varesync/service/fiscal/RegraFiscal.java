package br.com.varesync.service.fiscal;

import br.com.varesync.model.PerfilTributario;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Uma regra do R30 (F19.4, tabela confirmada pelo usuário): confere um produto ativo e devolve o que achou (vazio = em
 * ordem). As regras do perfil não acusam nada no produto sem perfil (a regra 1 já acusa).
 */
public interface RegraFiscal {

    /** CST do ICMS sem destaque do imposto (regra 6). */
    Set<String> CST_SEM_DESTAQUE = Set.of("40", "41", "60");
    /** CSOSN sem destaque do ICMS (regra 6). */
    Set<String> CSOSN_SEM_DESTAQUE = Set.of("102", "103", "300", "400", "500");

    /** 1 a 17. */
    int numero();

    /** Título curto, para o filtro do R30. */
    String titulo();

    List<Inconsistencia> verificar(ItemFiscal item, ContextoFiscal contexto);

    /** "5 — CFOP". */
    default String rotulo() {
        return numero() + " — " + titulo();
    }

    default Inconsistencia erro(String mensagem) {
        return new Inconsistencia(numero(), Gravidade.ERRO, mensagem);
    }

    default Inconsistencia aviso(String mensagem) {
        return new Inconsistencia(numero(), Gravidade.AVISO, mensagem);
    }

    /** Situação com substituição tributária: CST 60 ou CSOSN 500. */
    static boolean comSt(PerfilTributario p) {
        return "60".equals(p.cstIcms()) || "500".equals(p.csosn());
    }

    static boolean positivo(BigDecimal valor) {
        return valor != null && valor.signum() > 0;
    }
}
