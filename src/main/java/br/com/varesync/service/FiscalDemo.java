package br.com.varesync.service;

import br.com.varesync.model.DadosPerfil;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cadastro fiscal do gerador de dados de demonstração (F19.5): a loja no Simples Nacional, três perfis comuns
 * (tributado, ST e isento), dois perfis "(revisar)" com combinações erradas e o NCM, o CEST e o perfil de cada produto
 * do catálogo ({@link CatalogoDemo}), todos com origem 0. NCM e CEST são plausíveis, sem conferência de um contador.
 *
 * <p>Inconsistências de propósito (o R30 lista os 8 produtos; o painel conta os 6 com erro):
 * <ul>
 * <li>Guarda-chuva sem NCM e pilha sem perfil (regra 1);</li>
 * <li>vinho sem origem (regra 10);</li>
 * <li>energético com ST sem CEST (regra 9);</li>
 * <li>carvão no "Simples ST (revisar)", CFOP 5405 com CSOSN 102 (regra 5);</li>
 * <li>azeite no "Tributado regime normal (revisar)", CST 00 e PIS e COFINS com alíquota no Simples (regras 4 e 11);</li>
 * <li>as duas cervejas com o mesmo NCM em perfis diferentes (regra 13, só aviso);</li>
 * <li>F22.5: a cerveja pilsen e o energético, com ST, sem o ST retido (regra 16, só aviso).</li>
 * </ul>
 * A tabela IBPT de demonstração ({@link #tabelaIbpt}) tem todos os NCM do catálogo, e os refrigerantes e as águas têm
 * o ST retido ({@link #stRetido}).
 */
final class FiscalDemo {

    static final String TRIBUTADO = "Simples tributado";
    static final String ST = "Simples ST";
    static final String ISENTO = "Simples isento";
    static final String ST_REVISAR = "Simples ST (revisar)";
    static final String NORMAL_REVISAR = "Tributado regime normal (revisar)";

    /** Produtos com alguma inconsistência e, deles, os que só têm aviso. */
    static final Set<String> COM_INCONSISTENCIA = Set.of("Guarda-chuva dobrável", "Pilha alcalina AA com 2",
            "Vinho tinto suave 750 ml", "Energético lata 250 ml", "Carvão vegetal 3 kg",
            "Azeite de oliva extravirgem 500 ml", "Cerveja pilsen lata 350 ml", "Cerveja puro malte long neck 330 ml");
    static final Set<String> SO_AVISO = Set.of("Cerveja pilsen lata 350 ml", "Cerveja puro malte long neck 330 ml");

    /** @param perfil nome do perfil; nulo = sem perfil */
    record Dados(String ncm, String cest, Integer origem, String perfil) {
    }

    private FiscalDemo() {
    }

    /** Os cinco perfis, na ordem de criação. */
    static List<DadosPerfil> perfis() {
        return List.of(
                simples(TRIBUTADO, "5102", "102"),
                simples(ST, "5405", "500"),
                simples(ISENTO, "5102", "400"),
                simples(ST_REVISAR, "5405", "102"),
                new DadosPerfil(NORMAL_REVISAR, "5102", false, "00", new BigDecimal("18"), null, "01",
                        new BigDecimal("1.65"), "01", new BigDecimal("7.60"), null, null, null, true));
    }

    /** No Simples, PIS e COFINS 49 com alíquota zero; sem IBS e CBS (NT 2026.005). */
    private static DadosPerfil simples(String nome, String cfop, String csosn) {
        return new DadosPerfil(nome, cfop, true, csosn, BigDecimal.ZERO, null, "49", BigDecimal.ZERO, "49",
                BigDecimal.ZERO, null, null, null, true);
    }

    /** Nome do produto do catálogo → dados fiscais. */
    static Map<String, Dados> produtos() {
        Map<String, Dados> m = new LinkedHashMap<>();
        // Mercearia
        t(m, "Arroz branco tipo 1 5 kg", "1006.30.21");
        t(m, "Feijão carioca 1 kg", "0713.33.19");
        t(m, "Feijão preto 1 kg", "0713.33.19");
        t(m, "Açúcar refinado 1 kg", "1701.99.00");
        t(m, "Café torrado e moído 500 g", "0901.21.00");
        t(m, "Óleo de soja 900 ml", "1507.90.11");
        t(m, "Macarrão espaguete 500 g", "1902.19.00");
        t(m, "Macarrão parafuso 500 g", "1902.19.00");
        t(m, "Farinha de trigo 1 kg", "1101.00.10");
        t(m, "Sal refinado 1 kg", "2501.00.20");
        t(m, "Molho de tomate 340 g", "2103.20.10");
        t(m, "Extrato de tomate 190 g", "2002.90.90");
        t(m, "Leite condensado 395 g", "0402.99.00");
        t(m, "Creme de leite 200 g", "0401.50.10");
        t(m, "Achocolatado em pó 400 g", "1806.90.00");
        t(m, "Biscoito recheado chocolate 130 g", "1905.31.00");
        t(m, "Biscoito cream cracker 200 g", "1905.90.20");
        t(m, "Milho verde em conserva 170 g", "2005.80.00");
        t(m, "Atum ralado em óleo 170 g", "1604.14.10");
        m.put("Azeite de oliva extravirgem 500 ml", new Dados("1509.20.00", null, 0, NORMAL_REVISAR));
        t(m, "Fondue de chocolate kit presente", "1806.90.00");
        // Bebidas
        m.put("Refrigerante cola PET 2 L", new Dados("2202.10.00", "03.011.00", 0, ST));
        m.put("Refrigerante guaraná PET 2 L", new Dados("2202.10.00", "03.011.00", 0, ST));
        m.put("Água mineral sem gás 500 ml", new Dados("2201.10.00", "03.001.00", 0, ST));
        m.put("Água mineral com gás 500 ml", new Dados("2201.10.00", "03.001.00", 0, ST));
        t(m, "Suco de laranja integral 1 L", "2009.12.00");
        m.put("Cerveja pilsen lata 350 ml", new Dados("2203.00.00", "03.021.00", 0, ST));
        m.put("Cerveja puro malte long neck 330 ml", new Dados("2203.00.00", "03.021.00", 0, TRIBUTADO));
        m.put("Energético lata 250 ml", new Dados("2202.99.00", null, 0, ST));
        m.put("Vinho tinto suave 750 ml", new Dados("2204.21.00", null, null, TRIBUTADO));
        t(m, "Leite UHT integral 1 L", "0401.20.10");
        t(m, "Espumante brut 750 ml", "2204.10.10");
        // Hortifruti (isento)
        i(m, "Banana prata", "0803.90.00");
        i(m, "Tomate italiano", "0702.00.00");
        i(m, "Batata inglesa", "0701.90.00");
        i(m, "Cebola", "0703.10.19");
        i(m, "Maçã gala", "0808.10.00");
        i(m, "Laranja pera", "0805.10.00");
        i(m, "Alface crespa unidade", "0705.11.00");
        i(m, "Ovos brancos dúzia", "0407.21.00");
        // Frios e laticínios
        t(m, "Queijo mussarela fatiado", "0406.10.10");
        t(m, "Presunto cozido fatiado", "1602.41.00");
        t(m, "Mortadela fatiada", "1601.00.00");
        t(m, "Iogurte natural 170 g", "0403.20.00");
        t(m, "Manteiga com sal 200 g", "0405.10.00");
        t(m, "Requeijão cremoso 200 g", "0406.10.90");
        t(m, "Margarina 500 g", "1517.10.00");
        t(m, "Queijo parmesão ralado 50 g", "0406.20.00");
        // Açougue
        t(m, "Alcatra bovina", "0201.30.00");
        t(m, "Carne moída patinho", "0201.30.00");
        t(m, "Coxa e sobrecoxa de frango", "0207.14.00");
        t(m, "Linguiça toscana", "1601.00.00");
        t(m, "Costela bovina", "0201.20.20");
        // Padaria
        t(m, "Pão francês", "1905.90.90");
        t(m, "Pão de forma tradicional 500 g", "1905.90.10");
        t(m, "Bolo de fubá unidade", "1905.90.90");
        t(m, "Bisnaguinha 300 g", "1905.90.10");
        t(m, "Panetone 400 g", "1905.20.90");
        // Limpeza
        t(m, "Detergente líquido 500 ml", "3402.20.00");
        t(m, "Sabão em pó 1,6 kg", "3402.20.00");
        t(m, "Água sanitária 2 L", "2828.90.11");
        t(m, "Amaciante de roupas 2 L", "3809.91.90");
        t(m, "Desinfetante lavanda 2 L", "3808.94.19");
        t(m, "Esponja de louça pacote com 3", "6805.30.10");
        t(m, "Saco de lixo 50 L com 10", "3923.21.90");
        t(m, "Pá de lixo plástica", "3924.90.00");
        // Higiene e perfumaria
        t(m, "Papel higiênico folha dupla com 12", "4818.10.00");
        t(m, "Creme dental 90 g", "3306.10.00");
        t(m, "Sabonete em barra 85 g", "3401.11.90");
        t(m, "Shampoo 350 ml", "3305.10.00");
        t(m, "Condicionador 350 ml", "3305.90.00");
        t(m, "Desodorante aerosol 150 ml", "3307.20.10");
        t(m, "Fralda descartável G com 30", "9619.00.00");
        t(m, "Absorvente com abas 8 unidades", "9619.00.00");
        // Outros
        m.put("Carvão vegetal 3 kg", new Dados("4402.90.00", null, 0, ST_REVISAR));
        t(m, "Gelo em cubos 5 kg", "2201.90.00");
        m.put("Pilha alcalina AA com 2", new Dados("8506.10.20", null, 0, null));
        t(m, "Fósforo pacote com 10", "3605.00.00");
        t(m, "Vela branca pacote com 8", "3406.00.00");
        m.put("Guarda-chuva dobrável", new Dados(null, null, 0, TRIBUTADO));
        return m;
    }

    /** Fonte da tabela IBPT de demonstração (F22.5). */
    static final String FONTE_IBPT = "Demonstração";
    static final String VERSAO_IBPT = "DEMO.1";

    /**
     * Tabela IBPT de demonstração (F22.5), no leiaute do IBPT, só com os NCM do catálogo e alíquotas plausíveis (sem
     * conferência): alimentos dos capítulos 01 a 21 com federal de 7,85%, hortifrúti e ovos com 4,20% e estadual zero,
     * bebidas (capítulo 22) com 17,30% e estadual de 25%, o resto com 13,45% e estadual de 18%; importados = nacional +
     * 2 pontos; municipal zero.
     */
    static byte[] tabelaIbpt(java.time.LocalDate inicio, java.time.LocalDate fim) {
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
        StringBuilder csv = new StringBuilder("codigo;ex;tipo;descricao;nacionalfederal;importadosfederal;estadual;"
                + "municipal;vigenciainicio;vigenciafim;chave;versao;fonte\n");
        java.util.Set<String> ncms = new java.util.TreeSet<>();
        produtos().values().forEach(d -> {
            if (d.ncm() != null) {
                ncms.add(d.ncm().replace(".", ""));
            }
        });
        for (String ncm : ncms) {
            int capitulo = Integer.parseInt(ncm.substring(0, 2));
            BigDecimal nacional;
            BigDecimal estadual;
            if (capitulo == 7 || capitulo == 8 || ncm.startsWith("0407")) {
                nacional = new BigDecimal("4.20");
                estadual = BigDecimal.ZERO;
            } else if (capitulo == 22) {
                nacional = new BigDecimal("17.30");
                estadual = new BigDecimal("25.00");
            } else if (capitulo <= 21) {
                nacional = new BigDecimal("7.85");
                estadual = new BigDecimal("18.00");
            } else {
                nacional = new BigDecimal("13.45");
                estadual = new BigDecimal("18.00");
            }
            csv.append(ncm).append(";;0;NCM ").append(ncm).append(" (demonstração);")
                    .append(nacional.toPlainString().replace('.', ',')).append(';')
                    .append(nacional.add(BigDecimal.valueOf(2)).toPlainString().replace('.', ',')).append(';')
                    .append(estadual.toPlainString().replace('.', ',')).append(";0,00;").append(inicio.format(f))
                    .append(';').append(fim.format(f)).append(";DEMO;").append(VERSAO_IBPT).append(';')
                    .append(FONTE_IBPT).append('\n');
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * ST retido por unidade (F22.5) em parte dos produtos com ST (os refrigerantes e as águas), como viria da NF-e de
     * compra; a cerveja pilsen e o energético ficam sem, para a regra 16 do R30 avisar.
     */
    static Map<String, br.com.varesync.model.ItemTributavel.StRetido> stRetido() {
        Map<String, br.com.varesync.model.ItemTributavel.StRetido> m = new LinkedHashMap<>();
        br.com.varesync.model.ItemTributavel.StRetido refrigerante = new br.com.varesync.model.ItemTributavel.StRetido(
                new BigDecimal("9.2000"), new BigDecimal("18.00"), new BigDecimal("0.8000"), new BigDecimal("0.8560"));
        br.com.varesync.model.ItemTributavel.StRetido agua = new br.com.varesync.model.ItemTributavel.StRetido(
                new BigDecimal("2.4000"), new BigDecimal("18.00"), new BigDecimal("0.2000"), new BigDecimal("0.2320"));
        m.put("Refrigerante cola PET 2 L", refrigerante);
        m.put("Refrigerante guaraná PET 2 L", refrigerante);
        m.put("Água mineral sem gás 500 ml", agua);
        m.put("Água mineral com gás 500 ml", agua);
        return m;
    }

    private static void t(Map<String, Dados> m, String nome, String ncm) {
        m.put(nome, new Dados(ncm, null, 0, TRIBUTADO));
    }

    private static void i(Map<String, Dados> m, String nome, String ncm) {
        m.put(nome, new Dados(ncm, null, 0, ISENTO));
    }
}
