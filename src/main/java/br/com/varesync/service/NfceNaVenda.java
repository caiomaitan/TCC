package br.com.varesync.service;

import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.TributoVendaDAO;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.TipoEmissaoNfce;
import br.com.varesync.service.nfce.DadosFiscaisNfce;
import br.com.varesync.util.CpfCnpj;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

/** A NFC-e na venda (F23.6), com o certificado deste terminal e a reserva do {@link NfceService}. */
public class NfceNaVenda implements NfceDaVenda {

    private final ConfiguracaoDAO configuracaoDAO;
    private final TributoVendaDAO tributoDAO;
    private final CertificadoService certificados;
    private final NfceService nfce;

    public NfceNaVenda(ConfiguracaoDAO configuracaoDAO, TributoVendaDAO tributoDAO, CertificadoService certificados,
                       NfceService nfce) {
        this.configuracaoDAO = configuracaoDAO;
        this.tributoDAO = tributoDAO;
        this.certificados = certificados;
        this.nfce = nfce;
    }

    @Override
    public boolean ligada(Connection con) throws SQLException {
        return configuracaoDAO.carregar(con).nfce().ativa();
    }

    @Override
    public void exigirCertificado() {
        try {
            certificados.doTerminal();
        } catch (NegocioException e) {
            throw new NegocioException("A NFC-e está ligada, e a venda não pode começar: " + e.getMessage()
                    + " Chame o supervisor.");
        }
    }

    @Override
    public void conferirProduto(Connection con, long produtoId) throws SQLException {
        var cadastro = tributoDAO.cadastroFiscal(con, produtoId)
                .orElseThrow(() -> new NegocioException("Produto não encontrado."));
        String recusa = DadosFiscaisNfce.recusa(cadastro, configuracaoDAO.carregar(con).loja().regime());
        if (recusa != null) {
            throw new NegocioException(recusa);
        }
    }

    @Override
    public String conferirConsumidor(Connection con, BigDecimal total, String documento) throws SQLException {
        return consumidor(documento, total, configuracaoDAO.carregar(con).nfce());
    }

    /** A regra do CPF ou CNPJ na nota e dos limites de valor (F23.6), sem banco. */
    static String consumidor(String digitado, BigDecimal total, Configuracoes.Nfce config) {
        if (total.compareTo(config.valorMaximo()) >= 0) {
            throw new NegocioException("A venda de " + Formatos.moeda(total) + " não pode ser fechada: a partir de "
                    + Formatos.moeda(config.valorMaximo()) + " ela exige NF-e, que o sistema não emite. Divida a "
                    + "venda ou emita a NF-e por outro sistema.");
        }
        String documento = CpfCnpj.normalizar(digitado);
        if (documento.isEmpty()) {
            if (total.compareTo(config.valorIdentificacao()) >= 0) {
                throw new NegocioException("Informe o CPF ou o CNPJ na nota: a partir de "
                        + Formatos.moeda(config.valorIdentificacao()) + " a NFC-e exige a identificação do "
                        + "consumidor.");
            }
            return null;
        }
        Optional<String> erro = CpfCnpj.validar(documento);
        if (erro.isPresent()) {
            throw new NegocioException("CPF/CNPJ na nota: " + erro.get());
        }
        if (!Textos.soDigitos(documento)) {
            throw new NegocioException("CPF/CNPJ na nota: o CNPJ com letras ainda não é aceito na NFC-e.");
        }
        return documento;
    }

    @Override
    public long reservar(Connection con, long vendaId, OffsetDateTime emitidaEm) throws SQLException {
        return nfce.reservar(con, vendaId, TipoEmissaoNfce.NORMAL, emitidaEm, null);
    }
}
