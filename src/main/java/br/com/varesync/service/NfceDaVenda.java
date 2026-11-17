package br.com.varesync.service;

import br.com.varesync.service.nfce.RespostaSefaz;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;

/**
 * O que a venda precisa da NFC-e (F23.6), dentro das transações do {@link VendaService}: se está ligada, o certificado
 * deste terminal, os dados fiscais do produto, o CPF ou CNPJ na nota com os limites de valor e a reserva do número.
 * Com a NFC-e desligada, nada muda ({@link #DESLIGADA}, que também serve ao gerador de demonstração, que não emite).
 */
public interface NfceDaVenda {

    /** {@code nfce.ativa}, lido na transação. */
    boolean ligada(Connection con) throws SQLException;

    /** O certificado deste terminal carregado e na validade; sem isso, a recusa explica e pede o supervisor. */
    void exigirCertificado();

    /** Recusa o produto sem os dados fiscais completos para a NFC-e, com o que falta. */
    void conferirProduto(Connection con, long produtoId) throws SQLException;

    /**
     * Confere o CPF ou CNPJ na nota (opcional, conferido pelo dígito) e os limites: a partir de
     * {@code nfce.valor_identificacao} ele é obrigatório; a partir de {@code nfce.valor_maximo}, a venda não fecha.
     *
     * @return o documento só com os dígitos, ou nulo sem documento
     */
    String conferirConsumidor(Connection con, BigDecimal total, String documento) throws SQLException;

    /** Reserva o número da NFC-e da venda (PENDENTE), na transação da venda: a última trava, depois dos recebíveis. */
    long reservar(Connection con, long vendaId, OffsetDateTime emitidaEm) throws SQLException;

    /**
     * A NFC-e AUTORIZADA que o cancelamento da venda cancela pelo evento 110111 (F23.8).
     *
     * @param justificativa o motivo do cancelamento da venda (de 15 a 255 caracteres)
     */
    record Cancelamento(long nfceId, int numero, int serie, String chave, String protocolo, String justificativa) {
    }

    /**
     * Confere a NFC-e da venda no cancelamento (F23.8), na transação de quem chama: nula quando não há o que cancelar
     * na SEFAZ (sem NFC-e, REJEITADA, cujo número o transmissor inutiliza, ou DENEGADA, com o número já gasto). Recusa
     * a PENDENTE, a de contingência ainda não autorizada, a sem resposta que o transmissor ainda resolve, a autorizada
     * fora de {@code nfce.prazo_cancelamento_minutos} e a justificativa fora de 15 a 255 caracteres.
     */
    Cancelamento conferirCancelamento(Connection con, long vendaId, String justificativa) throws SQLException;

    /**
     * O evento 110111, fora da transação. Se a SEFAZ recusa ou não responde, a recusa explica, e a venda não é
     * cancelada; a duplicidade de evento (573) conta como cancelada.
     */
    RespostaSefaz cancelarNaSefaz(Cancelamento cancelamento);

    /** Na transação do cancelamento da venda: a NFC-e CANCELADA, o evento (procEventoNFe) e a auditoria. */
    void registrarCancelamento(Connection con, Sessao sessao, Cancelamento cancelamento, RespostaSefaz evento)
            throws SQLException;

    /** NFC-e desligada: nada muda na venda. */
    NfceDaVenda DESLIGADA = new NfceDaVenda() {
        @Override
        public boolean ligada(Connection con) {
            return false;
        }

        @Override
        public void exigirCertificado() {
        }

        @Override
        public void conferirProduto(Connection con, long produtoId) {
        }

        @Override
        public String conferirConsumidor(Connection con, BigDecimal total, String documento) {
            return null;
        }

        @Override
        public long reservar(Connection con, long vendaId, OffsetDateTime emitidaEm) {
            throw new IllegalStateException("NFC-e desligada");
        }

        @Override
        public Cancelamento conferirCancelamento(Connection con, long vendaId, String justificativa) {
            return null;
        }

        @Override
        public RespostaSefaz cancelarNaSefaz(Cancelamento cancelamento) {
            throw new IllegalStateException("NFC-e desligada");
        }

        @Override
        public void registrarCancelamento(Connection con, Sessao sessao, Cancelamento cancelamento,
                                          RespostaSefaz evento) {
            throw new IllegalStateException("NFC-e desligada");
        }
    };
}
