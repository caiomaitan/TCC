package br.com.varesync.service;

import br.com.varesync.dao.BuscaGlobalDAO;
import br.com.varesync.dao.FornecedorDAO;
import br.com.varesync.model.Fornecedor;
import br.com.varesync.model.GrupoBusca;
import br.com.varesync.model.ItemBusca;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.StatusVenda;
import br.com.varesync.util.CpfCnpj;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Busca global (Ctrl+K, F21.3): produtos, clientes, venda pelo número e fornecedores, até {@link #POR_GRUPO} por
 * grupo. Cada grupo só entra se o usuário pode abrir o que o resultado abre:
 * <ul>
 *   <li>produto: o cadastro (PRODUTOS) ou, sem essa permissão, o produto no Estoque (ESTOQUE);</li>
 *   <li>cliente: a ficha em Clientes (CLIENTES);</li>
 *   <li>venda: a segunda via do comprovante (PDV, RELATORIOS ou DEVOLUCAO);</li>
 *   <li>fornecedor: o cadastro em Fornecedores (FORNECEDORES).</li>
 * </ul>
 * As telas do menu são filtradas pela interface, que conhece o menu.
 */
public class BuscaGlobalService {

    /** Mínimo de caracteres (sem os espaços das pontas) para procurar. */
    public static final int MINIMO = 2;
    public static final int POR_GRUPO = 8;
    /** Número de venda: até 18 dígitos (cabe num {@code BIGINT}). */
    private static final Pattern NUMERO_VENDA = Pattern.compile("\\d{1,18}");

    private final Transacao transacao;
    private final BuscaGlobalDAO dao;
    private final FornecedorDAO fornecedorDAO;

    public BuscaGlobalService(Transacao transacao, BuscaGlobalDAO dao, FornecedorDAO fornecedorDAO) {
        this.transacao = transacao;
        this.dao = dao;
        this.fornecedorDAO = fornecedorDAO;
    }

    /** O produto achado abre o cadastro (PRODUTOS) ou, sem ele, o Estoque. */
    public static boolean podeVerProdutos(Sessao sessao) {
        return sessao.pode(Permissao.PRODUTOS) || sessao.pode(Permissao.ESTOQUE);
    }

    /** A venda achada abre a segunda via do comprovante (F21.3). */
    public static boolean podeVerVendas(Sessao sessao) {
        return sessao.pode(Permissao.PDV) || sessao.pode(Permissao.RELATORIOS) || sessao.pode(Permissao.DEVOLUCAO);
    }

    /**
     * Já dá para procurar? A partir de {@link #MINIMO} caracteres; um número puro, a partir de 1 dígito, mas só a
     * venda pelo número (F21.5): "7" acha a venda nº 7, e os outros grupos continuam pedindo 2 caracteres.
     */
    public static boolean procuravel(String texto) {
        String termo = Textos.normalizarEspacos(texto == null ? "" : texto);
        return termo.length() >= MINIMO || (!termo.isEmpty() && Textos.soDigitos(termo));
    }

    /**
     * Resultados em ordem de grupo (produtos, clientes, vendas, fornecedores). Menos de {@link #MINIMO} caracteres →
     * só a venda, se for um número de 1 dígito ({@link #procuravel}); senão
     * lista vazia, sem consultar o banco.
     */
    public List<ItemBusca> buscar(Sessao sessao, String texto) {
        String termo = Textos.normalizarEspacos(texto);
        if (termo.length() < MINIMO) {
            if (!procuravel(termo)) {
                return List.of();
            }
            Sessao conferida = sessao.conferida();
            if (!podeVerVendas(conferida)) {
                return List.of();
            }
            return transacao.executar(con -> dao.venda(con, Long.parseLong(termo))
                    .map(v -> List.of(new ItemBusca(GrupoBusca.VENDAS, v.id(), "Venda nº " + v.id(), detalhe(v))))
                    .orElse(List.of()));
        }
        Sessao atual = sessao.conferida(); // F10.1: vale o acesso de agora
        String nome = Textos.normalizarBusca(termo);
        String digitos = Textos.soDigitos(termo) ? termo : null;
        String documento = documento(termo);
        return transacao.executar(con -> {
            List<ItemBusca> itens = new ArrayList<>();
            if (podeVerProdutos(atual)) {
                for (BuscaGlobalDAO.ProdutoAchado p : dao.produtos(con, nome, digitos, POR_GRUPO)) {
                    itens.add(new ItemBusca(GrupoBusca.PRODUTOS, p.id(), p.nome(), detalhe(p, digitos)));
                }
            }
            if (atual.pode(Permissao.CLIENTES)) {
                for (BuscaGlobalDAO.ClienteAchado c : dao.clientes(con, nome, documento, POR_GRUPO)) {
                    itens.add(new ItemBusca(GrupoBusca.CLIENTES, c.id(), c.nome(), detalhe(c)));
                }
            }
            if (podeVerVendas(atual) && digitos != null && NUMERO_VENDA.matcher(digitos).matches()) {
                dao.venda(con, Long.parseLong(digitos)).ifPresent(v -> itens.add(new ItemBusca(GrupoBusca.VENDAS,
                        v.id(), "Venda nº " + v.id(), detalhe(v))));
            }
            if (atual.pode(Permissao.FORNECEDORES)) {
                fornecedorDAO.listar(con, true).stream()
                        .filter(f -> contem(f, nome, documento))
                        .sorted((a, b) -> Boolean.compare(b.ativo(), a.ativo()))
                        .limit(POR_GRUPO)
                        .forEach(f -> itens.add(new ItemBusca(GrupoBusca.FORNECEDORES, f.id(), f.razaoSocial(),
                                detalhe(f))));
            }
            return itens;
        });
    }

    /** CPF/CNPJ sem máscara quando o termo parece um documento (com ao menos um dígito); senão nulo. */
    private static String documento(String termo) {
        String documento = CpfCnpj.normalizar(termo);
        boolean parece = documento.matches("[0-9A-Z]+") && documento.chars().anyMatch(Character::isDigit);
        return parece ? documento : null;
    }

    private static String detalhe(BuscaGlobalDAO.ProdutoAchado p, String digitos) {
        StringBuilder texto = new StringBuilder("Código ").append(p.id());
        if (p.codigoLido() != null) {
            BigDecimal quantidade = p.quantidadeEmbalagem();
            texto.append(quantidade != null && quantidade.compareTo(BigDecimal.ONE) > 0
                    ? " · embalagem com " + Formatos.quantidade(quantidade, p.fracionada()) + " " + p.unidade()
                    + " (código " + p.codigoLido() + ")"
                    : " · código de barras " + p.codigoLido());
        } else if (p.plu() != null && digitos != null && digitos.length() <= 6
                && p.plu() == Integer.parseInt(digitos) && p.id() != p.plu()) {
            texto.append(" · PLU ").append(p.plu());
        }
        texto.append(" · ").append(Formatos.moeda(p.preco())).append(" / ").append(p.unidade());
        if (!p.ativo()) {
            texto.append(" · inativo");
        }
        return texto.toString();
    }

    private static String detalhe(BuscaGlobalDAO.ClienteAchado c) {
        List<String> partes = new ArrayList<>();
        partes.add("Código " + c.id());
        if (c.cpfCnpj() != null) {
            partes.add(CpfCnpj.ocultar(c.cpfCnpj()));
        }
        if (c.cidade() != null) {
            partes.add(c.cidade() + (c.uf() == null ? "" : "/" + c.uf()));
        }
        if (!c.ativo()) {
            partes.add("inativo");
        }
        return String.join(" · ", partes);
    }

    private static String detalhe(BuscaGlobalDAO.VendaAchada v) {
        return Formatos.dataHora(v.dataHora()) + " · " + Formatos.moeda(v.total())
                + (v.clienteNome() == null ? "" : " · " + v.clienteNome())
                + (v.status() == StatusVenda.CANCELADA ? " · cancelada" : "");
    }

    private static String detalhe(Fornecedor f) {
        List<String> partes = new ArrayList<>();
        if (f.nomeFantasia() != null && !f.nomeFantasia().isBlank()) {
            partes.add(f.nomeFantasia());
        }
        if (f.cpfCnpj() != null) {
            partes.add(f.documentoFormatado());
        }
        if (f.cidade() != null && !f.cidade().isBlank()) {
            partes.add(f.cidade());
        }
        if (!f.ativo()) {
            partes.add("inativo");
        }
        return partes.isEmpty() ? "Código " + f.id() : String.join(" · ", partes);
    }

    /** Mesma regra da busca de Fornecedores: razão social, nome fantasia ou cidade (sem acento) ou parte do CPF/CNPJ. */
    private static boolean contem(Fornecedor f, String nome, String documento) {
        return contem(f.razaoSocial(), nome) || contem(f.nomeFantasia(), nome) || contem(f.cidade(), nome)
                || (documento != null && f.cpfCnpj() != null && f.cpfCnpj().contains(documento));
    }

    private static boolean contem(String campo, String termo) {
        return campo != null && Textos.normalizarBusca(campo).contains(termo);
    }
}
