package br.com.varesync.service;

import br.com.varesync.util.Gtin;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;

/**
 * Produtos do gerador de dados de demonstração (6.14): cerca de 80 itens de mercearia em todas as categorias da V2,
 * com custo, preço e a "popularidade" (peso no sorteio das vendas). Os pesáveis (KG) têm PLU e não têm código de
 * barras; os demais recebem um EAN-13 fictício com dígito verificador válido (prefixo 789 + 6543).
 *
 * <p>Casos de propósito: popularidade 0 = nunca vendido (relatório de produtos sem giro); preço abaixo do custo
 * (alerta de margem negativa); margem baixa (abaixo de {@code relatorio.margem_minima_pct}); estoque baixo (termina
 * abaixo do mínimo). Desde a fase 7, também os fornecedores, cada um com as suas categorias e o dia da entrega.
 */
final class CatalogoDemo {

    static final String PREFIXO_GTIN = "7896543";

    /**
     * @param popularidade peso relativo no sorteio dos itens (0 = nunca vendido)
     * @param estoqueBaixo o estoque inicial cobre só a demanda: termina abaixo do mínimo
     */
    record ItemCatalogo(String nome, String categoria, String unidade, BigDecimal custo, BigDecimal preco,
                        int popularidade, BigDecimal minimo, Integer plu, String codigoBarras,
                        boolean estoqueBaixo) {

        boolean pesavel() {
            return plu != null;
        }
    }

    private CatalogoDemo() {
    }

    static List<ItemCatalogo> itens() {
        Montagem m = new Montagem();
        // Mercearia
        m.un("Arroz branco tipo 1 5 kg", "Mercearia", "22.90", "29.90", 9, 10);
        m.un("Feijão carioca 1 kg", "Mercearia", "6.20", "8.49", 9, 15);
        m.un("Feijão preto 1 kg", "Mercearia", "6.80", "9.29", 4, 10);
        m.un("Açúcar refinado 1 kg", "Mercearia", "3.60", "4.99", 7, 15);
        m.un("Café torrado e moído 500 g", "Mercearia", "14.30", "18.90", 8, 12);
        m.un("Óleo de soja 900 ml", "Mercearia", "5.90", "7.49", 7, 15);
        m.un("Macarrão espaguete 500 g", "Mercearia", "3.10", "4.59", 6, 15);
        m.un("Macarrão parafuso 500 g", "Mercearia", "3.20", "4.79", 4, 10);
        m.un("Farinha de trigo 1 kg", "Mercearia", "4.10", "5.79", 4, 10);
        m.un("Sal refinado 1 kg", "Mercearia", "1.60", "2.89", 3, 10);
        m.un("Molho de tomate 340 g", "Mercearia", "1.90", "2.99", 6, 20);
        m.un("Extrato de tomate 190 g", "Mercearia", "2.30", "3.49", 3, 10);
        m.un("Leite condensado 395 g", "Mercearia", "4.70", "6.49", 4, 10);
        m.un("Creme de leite 200 g", "Mercearia", "2.60", "3.79", 4, 10);
        m.un("Achocolatado em pó 400 g", "Mercearia", "6.90", "9.49", 3, 8);
        m.un("Biscoito recheado chocolate 130 g", "Mercearia", "1.80", "2.99", 6, 20);
        m.un("Biscoito cream cracker 200 g", "Mercearia", "2.90", "4.29", 4, 12);
        m.un("Milho verde em conserva 170 g", "Mercearia", "2.40", "3.59", 2, 8);
        m.un("Atum ralado em óleo 170 g", "Mercearia", "6.80", "9.99", 2, 6);
        m.un("Azeite de oliva extravirgem 500 ml", "Mercearia", "32.00", "39.90", 1, 4);
        m.un("Fondue de chocolate kit presente", "Mercearia", "38.00", "59.90", 0, 0);
        // Bebidas
        m.un("Refrigerante cola PET 2 L", "Bebidas", "6.10", "9.49", 9, 24);
        m.un("Refrigerante guaraná PET 2 L", "Bebidas", "5.20", "7.99", 7, 18);
        m.un("Água mineral sem gás 500 ml", "Bebidas", "0.90", "2.29", 7, 24);
        m.un("Água mineral com gás 500 ml", "Bebidas", "1.10", "2.69", 2, 12);
        m.un("Suco de laranja integral 1 L", "Bebidas", "7.40", "10.90", 3, 8);
        m.un("Cerveja pilsen lata 350 ml", "Bebidas", "2.90", "4.29", 9, 48);
        m.un("Cerveja puro malte long neck 330 ml", "Bebidas", "4.30", "6.49", 4, 24);
        m.un("Energético lata 250 ml", "Bebidas", "5.60", "8.99", 2, 12);
        m.un("Vinho tinto suave 750 ml", "Bebidas", "16.50", "24.90", 1, 6);
        m.un("Leite UHT integral 1 L", "Bebidas", "4.89", "4.79", 9, 24); // abaixo do custo (concorrência)
        m.un("Espumante brut 750 ml", "Bebidas", "42.00", "64.90", 0, 0);
        // Hortifruti
        m.kg("Banana prata", "Hortifruti", "3.40", "5.99", 8, 5);
        m.kg("Tomate italiano", "Hortifruti", "5.10", "8.99", 7, 5);
        m.kg("Batata inglesa", "Hortifruti", "3.90", "5.99", 6, 5);
        m.kg("Cebola", "Hortifruti", "3.20", "4.99", 6, 5);
        m.kg("Maçã gala", "Hortifruti", "7.20", "11.90", 4, 3);
        m.kg("Laranja pera", "Hortifruti", "2.80", "4.49", 4, 5);
        m.un("Alface crespa unidade", "Hortifruti", "1.80", "3.49", 4, 6);
        m.un("Ovos brancos dúzia", "Hortifruti", "8.90", "12.90", 7, 10);
        // Frios e laticínios
        m.kg("Queijo mussarela fatiado", "Frios e laticínios", "34.00", "49.90", 6, 2);
        m.kg("Presunto cozido fatiado", "Frios e laticínios", "24.00", "36.90", 5, 2);
        m.kg("Mortadela fatiada", "Frios e laticínios", "13.50", "21.90", 3, 2);
        m.un("Iogurte natural 170 g", "Frios e laticínios", "1.90", "3.29", 4, 12);
        m.un("Manteiga com sal 200 g", "Frios e laticínios", "9.80", "13.90", 3, 6);
        m.un("Requeijão cremoso 200 g", "Frios e laticínios", "5.90", "8.49", 4, 8);
        m.un("Margarina 500 g", "Frios e laticínios", "5.60", "6.29", 5, 10); // margem baixa
        m.unBaixo("Queijo parmesão ralado 50 g", "Frios e laticínios", "4.20", "6.99", 3, 10);
        // Açougue
        m.kg("Alcatra bovina", "Açougue", "36.00", "49.90", 4, 3);
        m.kg("Carne moída patinho", "Açougue", "30.00", "42.90", 6, 3);
        m.kg("Coxa e sobrecoxa de frango", "Açougue", "10.40", "14.90", 6, 4);
        m.kg("Linguiça toscana", "Açougue", "17.00", "24.90", 4, 3);
        m.kg("Costela bovina", "Açougue", "23.50", "26.90", 2, 3); // margem baixa
        // Padaria
        m.kg("Pão francês", "Padaria", "9.50", "16.90", 10, 3);
        m.un("Pão de forma tradicional 500 g", "Padaria", "6.20", "8.99", 5, 8);
        m.un("Bolo de fubá unidade", "Padaria", "7.00", "12.90", 2, 3);
        m.un("Bisnaguinha 300 g", "Padaria", "5.10", "7.49", 3, 6);
        m.un("Panetone 400 g", "Padaria", "15.00", "24.90", 0, 0);
        // Limpeza
        m.un("Detergente líquido 500 ml", "Limpeza", "1.70", "2.79", 6, 20);
        m.un("Sabão em pó 1,6 kg", "Limpeza", "14.90", "21.90", 4, 8);
        m.un("Água sanitária 2 L", "Limpeza", "4.30", "6.49", 4, 10);
        m.un("Amaciante de roupas 2 L", "Limpeza", "11.20", "16.90", 3, 6);
        m.un("Desinfetante lavanda 2 L", "Limpeza", "6.40", "9.49", 3, 6);
        m.un("Esponja de louça pacote com 3", "Limpeza", "2.90", "4.99", 4, 10);
        m.unBaixo("Saco de lixo 50 L com 10", "Limpeza", "5.10", "7.99", 3, 10);
        m.un("Pá de lixo plástica", "Limpeza", "6.00", "11.90", 0, 0);
        // Higiene e perfumaria
        m.un("Papel higiênico folha dupla com 12", "Higiene e perfumaria", "16.90", "22.90", 6, 10);
        m.un("Creme dental 90 g", "Higiene e perfumaria", "3.10", "4.99", 5, 12);
        m.un("Sabonete em barra 85 g", "Higiene e perfumaria", "1.60", "2.69", 6, 20);
        m.un("Shampoo 350 ml", "Higiene e perfumaria", "11.40", "16.90", 3, 6);
        m.un("Condicionador 350 ml", "Higiene e perfumaria", "12.10", "17.90", 2, 6);
        m.un("Desodorante aerosol 150 ml", "Higiene e perfumaria", "10.90", "15.90", 3, 6);
        m.un("Fralda descartável G com 30", "Higiene e perfumaria", "44.00", "41.90", 2, 4); // abaixo do custo
        m.un("Absorvente com abas 8 unidades", "Higiene e perfumaria", "3.90", "5.99", 3, 8);
        // Outros
        m.un("Carvão vegetal 3 kg", "Outros", "11.00", "17.90", 2, 5);
        m.un("Gelo em cubos 5 kg", "Outros", "6.50", "11.90", 3, 8);
        m.un("Pilha alcalina AA com 2", "Outros", "6.90", "10.90", 1, 6);
        m.un("Fósforo pacote com 10", "Outros", "3.40", "5.49", 2, 8);
        m.unBaixo("Vela branca pacote com 8", "Outros", "4.80", "7.49", 1, 6);
        m.un("Guarda-chuva dobrável", "Outros", "19.00", "34.90", 0, 0);
        return List.copyOf(m.itens);
    }

    /**
     * Fornecedor de demonstração (fase 7): entrega toda semana no mesmo dia os produtos das suas categorias.
     *
     * @param primeiraNota número da primeira nota; as seguintes somam 1
     */
    record FornecedorDemo(String razaoSocial, String nomeFantasia, String cnpj, String telefone, String email,
                          String contato, String cidade, String uf, List<String> categorias, DayOfWeek diaEntrega,
                          int primeiraNota) {
    }

    /** Três fornecedores que cobrem as 9 categorias; um com CNPJ alfanumérico (A.3). */
    static List<FornecedorDemo> fornecedores() {
        return List.of(
                new FornecedorDemo("Distribuidora Ribeirão Alimentos Ltda", "Ribeirão Alimentos", "11222333000181",
                        "1633334444", "vendas@ribeiraoalimentos.com.br", "Paulo Mendes", "Ribeirão Preto", "SP",
                        List.of("Mercearia", "Padaria", "Outros"), DayOfWeek.MONDAY, 45120),
                new FornecedorDemo("Bebidas e Frios Sertãozinho Ltda", "Sertãozinho Bebidas", "12ABC34501DE35",
                        "1632221100", "pedidos@sertaozinhobebidas.com.br", "Fernanda Lima", "Sertãozinho", "SP",
                        List.of("Bebidas", "Frios e laticínios", "Açougue", "Hortifruti"), DayOfWeek.TUESDAY, 8812),
                new FornecedorDemo("Atacado Limpa Tudo Comércio Ltda", "Limpa Tudo", "AB12CD34EFGH83", "16988887777",
                        "comercial@limpatudo.com.br", "Ricardo Alves", "Franca", "SP",
                        List.of("Limpeza", "Higiene e perfumaria"), DayOfWeek.WEDNESDAY, 30455));
    }

    /** Cliente de demonstração (F12.8): todos com CPF fictício válido; parte com telefone e consentimento. */
    record ClienteDemo(String nome, String cpf, String telefone, boolean consentimento) {
    }

    public static final int CLIENTES = 150;
    private static final List<String> PRENOMES = List.of("Ana", "Bruno", "Camila", "Daniel", "Eduarda", "Felipe",
            "Gabriela", "Henrique", "Isabela", "João", "Larissa", "Marcos", "Natália", "Otávio", "Patrícia");
    private static final List<String> SOBRENOMES = List.of("Almeida", "Barbosa", "Cardoso", "Duarte", "Ferreira",
            "Gomes", "Lopes", "Moreira", "Nunes", "Ribeiro");

    /** 150 clientes, cada combinação de prenome e sobrenome uma vez; CPF 4xx.xxx.xxx-DV (base fixa pelo índice). */
    static List<ClienteDemo> clientes() {
        List<ClienteDemo> clientes = new ArrayList<>();
        for (int i = 0; i < CLIENTES; i++) {
            String nome = PRENOMES.get(i % PRENOMES.size()) + " " + SOBRENOMES.get(i / PRENOMES.size());
            String telefone = i % 3 == 0 ? null : String.format("169%08d", 81_000_000 + i * 137);
            clientes.add(new ClienteDemo(nome, cpf(400_000_000 + i * 3_571), telefone, i % 4 == 0));
        }
        return clientes;
    }

    /** CPF com os 9 dígitos da base e os dois dígitos verificadores. */
    static String cpf(int base) {
        String digitos = String.format("%09d", base);
        for (int dv = 0; dv < 2; dv++) {
            int soma = 0;
            for (int i = 0; i < digitos.length(); i++) {
                soma += (digitos.charAt(i) - '0') * (digitos.length() + 1 - i);
            }
            int resto = soma % 11;
            digitos += resto < 2 ? 0 : 11 - resto;
        }
        return digitos;
    }

    /** EAN-13 fictício: 789 + 6543 + sequência de 5 dígitos + dígito verificador. */
    static String gtin(int sequencia) {
        String semDigito = PREFIXO_GTIN + String.format("%05d", sequencia);
        return semDigito + Gtin.digitoVerificador(semDigito);
    }

    private static final class Montagem {
        private final List<ItemCatalogo> itens = new ArrayList<>();
        private int sequenciaGtin;
        private int plu;

        void un(String nome, String categoria, String custo, String preco, int popularidade, int minimo) {
            adicionar(nome, categoria, custo, preco, popularidade, minimo, false);
        }

        void unBaixo(String nome, String categoria, String custo, String preco, int popularidade, int minimo) {
            adicionar(nome, categoria, custo, preco, popularidade, minimo, true);
        }

        void kg(String nome, String categoria, String custo, String preco, int popularidade, int minimo) {
            itens.add(new ItemCatalogo(nome, categoria, "KG", new BigDecimal(custo), new BigDecimal(preco),
                    popularidade, BigDecimal.valueOf(minimo), ++plu, null, false));
        }

        private void adicionar(String nome, String categoria, String custo, String preco, int popularidade,
                               int minimo, boolean estoqueBaixo) {
            itens.add(new ItemCatalogo(nome, categoria, "UN", new BigDecimal(custo), new BigDecimal(preco),
                    popularidade, BigDecimal.valueOf(minimo), null, gtin(++sequenciaGtin), estoqueBaixo));
        }
    }
}
