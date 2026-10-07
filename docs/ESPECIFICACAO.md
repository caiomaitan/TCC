# VareSync — Especificação para implementação

Documento-mestre do VareSync, ERP desktop para o pequeno e médio varejo (TCC de Engenharia de Software — Universidade de Ribeirão Preto). É a fonte da verdade para quem implementa. A implementação é feita **em fases** (seção 10): em cada conversa, implemente somente a fase pedida.

Sumário: 0 Instruções ao agente · 1 Produto · 2 Escopo · 3 Stack e projeto · 4 Arquitetura · 5 Banco de dados · 6 Regras de negócio · 7 Indicadores e relatórios · 8 Interface · 9 Testes e qualidade · 10 Plano de fases · 11 Fora do escopo · Apêndice A (vetores de teste)

---

## 0. Instruções ao agente

1. Leia este documento inteiro e `.cursor/rules/varesync.mdc` antes de começar qualquer fase.
2. Implemente **só a fase pedida**. Não crie telas, classes ou tabelas de fases futuras, a não ser o mínimo que a fase atual exigir.
3. No início da fase, apresente um plano curto: arquivos a criar/alterar, classes, testes.
4. No fim da fase:
   - `mvn -q verify` sem falhas;
   - percorra **cada** critério de aceite da fase e diga como verificou;
   - atualize `docs/PROGRESSO.md`, `docs/RASTREABILIDADE.md` e `docs/ROTEIRO_TESTES.md`;
   - termine com: o que foi feito, como testar manualmente, pendências e uma mensagem de commit.
5. O banco já está pronto e testado: `src/main/resources/db/migration/V1__schema_inicial.sql` e `V2__dados_iniciais.sql`. **Não edite esses arquivos.** Toda mudança de banco é uma nova migration (`V3__...`), justificada em `docs/PROGRESSO.md`.
6. Diante de ambiguidade, escolha a opção mais simples que respeite este documento e registre a decisão. Pergunte apenas quando a decisão for irreversível ou mudar o banco.
7. Nunca desative testes nem troque o valor esperado de um teste sem entender a regra que ele protege.
8. Idioma: interface, mensagens e documentação em português do Brasil. Identificadores Java em português sem acento (`ProdutoService`, `calcularMargem`); termos técnicos consagrados podem ficar em inglês (`DAO`, `Controller`).

---

## 1. Produto

**VareSync** é um ERP desktop simplificado para mercados, mercearias e lojas de conveniência: cadastro de produtos, controle de estoque, vendas em PDV, inventário e indicadores de rentabilidade (lucro bruto e margem).

Personas do TCC:
- **João, 45, dono de pequeno varejo** — controla tudo sozinho, usa planilhas e anotações, não consegue ver o lucro real dos produtos. Quer controle simples e confiável.
- **Carlos, 32, gerente de médio varejo** — acompanha a operação diária e precisa identificar rapidamente os produtos mais rentáveis.

Pesquisa do TCC (42 respondentes do varejo): metade controla vendas em planilha; dores principais são falta de organização (78,6%), controle de estoque ruim (64,3%) e dificuldade de acompanhar o lucro (50%); recursos mais desejados são estoque automático (81%), relatórios detalhados (73,8%) e facilidade de uso (71,4%).

Princípios de produto:
- **Simples antes de completo**: poucos cliques, telas limpas, linguagem do lojista.
- **Número certo**: estoque e lucro têm de bater sempre; integridade vem antes de conveniência.
- **Teclado primeiro** no PDV e nos cadastros.

---

## 2. Escopo

### 2.1 Requisitos do TCC (MVP) e fase em que são entregues

| Código | Requisito | Fase |
|---|---|---|
| RF01 | Cadastrar produto (nome, custo, preço de venda) | 2 |
| RF02 | Editar e excluir produto | 2 |
| RF03 | Consultar produtos | 2 |
| RF04 | Registrar vendas no PDV | 4 |
| RF05 | Selecionar produtos durante a venda | 4 |
| RF06 | Atualizar o estoque automaticamente após a venda | 4 |
| RF07 | Criar inventário para conferência do estoque | 5 |
| RF08 | Atualizar o estoque pelas divergências do inventário | 5 |
| RF09 | Visualizar a quantidade em estoque | 3 |
| RF10 | Gerar relatórios de vendas e desempenho dos produtos | 6 |
| RF11 | Calcular lucro e margem | 6 |
| RF12 | Autenticação por login e senha | 1 |
| RF13 | Supervisor define as rotinas de cada usuário | 1 |
| RNF01 | Integridade dos dados | todas (triggers, constraints, transações) |
| RNF02/03 | Interface simples; informação clara | seção 8 |
| RNF04 | Acesso restrito por permissão | 1 |
| RNF05 | Tempo de resposta adequado | metas na seção 9.4 |
| RNF06 | Modular | seção 4 |
| RNF07 | Desktop | JavaFX |
| RNF08 | Banco relacional | PostgreSQL |

As fases 0 a 6 entregam todos os requisitos do TCC. As fases 7 a 9 completam o ERP.

### 2.2 Ampliações (o que transforma o MVP num ERP completo)
- **Caixa**: abertura com fundo de troco, sangria, suprimento e fechamento às cegas (fase 4).
- **PDV completo**: várias formas de pagamento, troco, descontos com limite e rateio, cancelamento de item e de venda com liberação de supervisor, comprovante não fiscal (fase 4).
- **Cadastro**: categorias, unidades de medida, vários códigos de barras por produto, estoque mínimo, histórico de preço e custo, simulador de margem (fase 2).
- **Estoque**: kardex, perdas/quebras com motivo, alertas de reposição (fase 3).
- **Relatórios**: painel com gráficos e alertas, 12 relatórios além dos 5 do TCC, exportação PDF e CSV, gerador de dados de demonstração (fase 6).
- **Compras**: fornecedores (com CNPJ alfanumérico) e entrada de mercadorias com custo médio ponderado (fase 7). No TCC I este item estava fora do MVP (seção 3.4); entra como evolução.
- **Extras**: promoções com vigência, leitura de etiqueta de balança, importação de produtos por planilha, backup, consulta de auditoria, impressão do comprovante, importação de XML de NF-e (fase 8).

### 2.3 Regras que mudaram em relação ao texto do TCC
- **Inventário** aplica a *diferença* capturada no momento da contagem, em vez de igualar o saldo ao contado (Apêndice C do TCC). Motivo e exemplo em 6.7 e A.8.
- **"Produto não disponível" no PDV**: vender sem saldo passa a ser configurável (padrão: permite e avisa). Bloquear a venda porque o estoque do sistema está errado trava o caixa com o cliente na fila. Produto inativo ou sem preço continua bloqueado.
- **"Estoque Atual" deixa de ser editável no cadastro** (Fig. 20): estoque só muda por movimento. O cadastro oferece "estoque inicial", que gera um ajuste.
- **Lucro** é calculado com o custo gravado no item no momento da venda, não com o custo atual do produto.
- **Exclusão de produto** só sem nenhum vínculo; caso contrário o sistema oferece inativar (já previsto no caso de uso "Excluir produto").

---

## 3. Stack e projeto

### 3.1 Tecnologias

| Item | Escolha | Observações |
|---|---|---|
| Linguagem | Java 21 (LTS) | `maven.compiler.release` = 21 |
| Interface | JavaFX 21.0.x — `org.openjfx:javafx-controls`, `javafx-fxml` | FXML + CSS. JavaFX 24+ exige JDK mais novo |
| Build | Maven | `org.openjfx:javafx-maven-plugin` para `mvn javafx:run` |
| Banco | PostgreSQL 14+ (testado no 16) | driver `org.postgresql:postgresql` |
| Pool | `com.zaxxer:HikariCP` | |
| Migrations | `org.flywaydb:flyway-core` **e** `org.flywaydb:flyway-database-postgresql` | desde o Flyway 10 o PostgreSQL é módulo separado |
| Senhas | `at.favre.lib:bcrypt` | custo 12 |
| PDF | `net.sf.jasperreports:jasperreports` + `net.sf.jasperreports:jasperreports-pdf` (linha 7.x) | ver 3.5 |
| Log | `org.slf4j:slf4j-api` + `ch.qos.logback:logback-classic` | |
| Testes | `org.junit.jupiter:junit-jupiter` (+ `org.assertj:assertj-core`, opcional) | |
| Tema (opcional) | `io.github.mkpaz:atlantafx-base` | visual moderno com pouco esforço |
| Ícones (opcional) | `org.kordamp.ikonli` (`ikonli-javafx` + um pacote de ícones) | |

Use a versão estável mais recente de cada biblioteca compatível com JDK 21 e confirme que o build resolve. Não usar Spring, JPA/Hibernate nem Lombok.

### 3.2 Estrutura

```
varesync/
├── pom.xml
├── README.md
├── docs/            ESPECIFICACAO.md · PROGRESSO.md · RASTREABILIDADE.md · ROTEIRO_TESTES.md
├── scripts/         teste_integridade_banco.sql
└── src/
    ├── main/java/br/com/varesync/
    │   ├── Launcher.java      main() que chama App.main(args)
    │   ├── App.java           Application: inicialização e troca de telas
    │   ├── config/            AppConfig, Banco (HikariCP), Migracao (Flyway), AppContext
    │   ├── model/             entidades e enums de domínio
    │   ├── dao/               um DAO por agregado, JDBC puro
    │   ├── service/           regras de negócio e transações
    │   ├── report/            consultas de relatório, exportação CSV e PDF
    │   ├── controller/        um controller JavaFX por tela
    │   ├── ui/                componentes reutilizáveis (campo de moeda, diálogos, liberação de supervisor, tabela genérica)
    │   └── util/              Dinheiro, Formatos, Textos, validadores (GTIN, CPF, CNPJ)
    ├── main/resources/
    │   ├── db/migration/      V1 e V2 prontas; futuras V3...
    │   ├── fxml/ css/ reports/ img/
    │   └── logback.xml
    └── test/java/br/com/varesync/   unitários e integração
```

### 3.3 Configuração local

Arquivo `~/.varesync/config.properties`, criado na primeira execução:

```properties
db.url=jdbc:postgresql://localhost:5432/varesync
db.usuario=varesync
db.senha=
terminal.id=CAIXA-01
```

- Falha de conexão ao iniciar → tela **Conexão com o banco** (URL, usuário, senha, botão Testar, Salvar).
- `terminal.id` identifica a máquina no controle de caixa e deve ser único por computador.
- Logs em `~/.varesync/logs/varesync.log`, rotação diária, 30 dias.

Criação do banco (vai para o README):

```sql
CREATE ROLE varesync LOGIN PASSWORD 'troque-esta-senha';
CREATE DATABASE varesync      OWNER varesync ENCODING 'UTF8' TEMPLATE template0;
CREATE DATABASE varesync_test OWNER varesync ENCODING 'UTF8' TEMPLATE template0;
```

### 3.4 Sequência de inicialização
`Launcher` → lê/cria config → testa conexão (senão, tela de conexão) → Flyway `migrate` → nenhum usuário cadastrado? assistente de primeiro acesso → login → troca de senha obrigatória, se marcada → janela principal.

### 3.5 Armadilhas conhecidas
- **JavaFX sem `module-info`**: rodar pela classe `Launcher` (que não estende `Application`) evita "JavaFX runtime components are missing".
- **Flyway 10+** sem `flyway-database-postgresql` falha com "No database found to handle jdbc:postgresql".
- **JasperReports 7**: a exportação PDF virou módulo próprio (`jasperreports-pdf`) e JRXML no formato da versão 6 não carrega. Antes de criar vários relatórios, faça **um** JRXML mínimo, compile-o num teste automatizado e só então replique o padrão. Se a compilação de JRXML acusar falta de compilador, acrescente `jasperreports-jdt`. Use a fonte DejaVu Sans (artefato `jasperreports-fonts`) para acentos no PDF. Para visualizar: exportar para PDF temporário e abrir com `getHostServices().showDocument(...)` (o JasperViewer é Swing).
- **Fuso horário**: o driver usa o fuso da JVM na sessão. Grave e leia TIMESTAMPTZ como `OffsetDateTime` (`setObject` / `getObject(coluna, OffsetDateTime.class)`) e converta para `ZoneId.systemDefault()` antes de exibir (o driver devolve em UTC).
- **Colunas que o Java não escreve**: ids (identity `GENERATED ALWAYS`, use `RETURNING id`), `produto.nome_busca`, `inventario_item.diferenca`, `caixa_sessao.diferenca` (geradas), `movimento_estoque.saldo_anterior/saldo_posterior` (preenchidas por trigger), `atualizado_em` (trigger).
- **Testes de integração** limpam o banco com `flyway.clean()`; habilite `cleanDisabled(false)` **somente** na configuração de teste.

---

## 4. Arquitetura e convenções

### 4.1 Camadas
`controller` (JavaFX) → `service` (regras e transação) → `dao` (SQL) → PostgreSQL.
- Controller não conhece SQL nem DAO. Service não importa JavaFX. DAO não tem regra de negócio.
- Modelos: classes simples para entidades editáveis; `record` para linhas de consulta e relatório.
- Enums Java com exatamente os mesmos valores dos CHECK do banco: `Permissao`, `TipoMovimento`, `StatusVenda`, `StatusCaixa`, `TipoCaixaMovimento`, `StatusInventario`, `StatusEntrada`, `TipoFormaPagamento`, `AcaoAuditoria`.

### 4.2 Montagem (composition root)
`AppContext` cria `DataSource`, `Clock`, DAOs e services (injeção pelo construtor) e entrega os controllers via `FXMLLoader.setControllerFactory(...)`. Sem singletons estáticos de serviço.

### 4.3 Transações

```java
public final class Transacao {
    private final DataSource dataSource;

    public Transacao(DataSource dataSource) { this.dataSource = dataSource; }

    public <T> T executar(Trabalho<T> trabalho) {
        try (Connection con = dataSource.getConnection()) {
            con.setAutoCommit(false);
            try {
                T resultado = trabalho.executar(con);
                con.commit();
                return resultado;
            } catch (Exception e) {
                con.rollback();
                throw TradutorErros.traduzir(e);
            }
        } catch (SQLException e) {
            throw TradutorErros.traduzir(e);
        }
    }

    @FunctionalInterface
    public interface Trabalho<T> { T executar(Connection con) throws Exception; }
}
```

- Todo método de service que grava abre **uma** transação e passa a mesma `Connection` a todos os DAOs envolvidos.
- DAO nunca abre conexão, nunca faz commit/rollback.
- `TradutorErros.traduzir` devolve `NegocioException` sem alteração e não perde a exceção original (falha no rollback entra como `addSuppressed`).
- SQL em text blocks, sempre `PreparedStatement` com parâmetros.

### 4.4 Erros
- `NegocioException`: violação de regra, com mensagem pronta para o usuário.
- `TradutorErros` converte `SQLException` pelo SQLState, usando o **nome da constraint** (todas são nomeadas no V1) e o mapa `MensagensBanco`:
  - `23505` (único): ex. `uq_pcb_codigo` → "Este código de barras já pertence a outro produto";
  - `23503` (chave estrangeira): "Este registro está em uso e não pode ser excluído";
  - `23514` (check): mensagem por constraint, ex. `ck_me_origem` → "Informe o motivo do ajuste";
  - `P0001` (trigger): a própria mensagem do banco, já em português;
  - demais: "Erro inesperado. Os detalhes foram registrados no log."
- Handler global de exceções: registra no log e mostra alerta amigável. Nunca engolir exceção em silêncio.

### 4.5 Interface e threads
Relatórios, painel, backup, importações e gerador de dados rodam em `javafx.concurrent.Task`, com indicador de progresso e botões desabilitados durante a execução. Buscas por chave no PDV (código de barras) podem ser síncronas.

### 4.6 Sessão e permissões
`Sessao` guarda usuário, permissões, terminal e sessão de caixa aberta. O service valida (`sessao.exigir(Permissao.ESTOQUE)`) e a interface esconde o que o usuário não pode usar. Supervisor tem todas as permissões.

### 4.7 Tempo
Services recebem `java.time.Clock`; usar `OffsetDateTime.now(clock)`. Isso permite testar datas e gerar dados de demonstração no passado.

### 4.8 Números e formatação
- `BigDecimal` para dinheiro, custo, quantidade e percentual; nunca `double`/`float`.
- Escalas: dinheiro 2, custo unitário 4, quantidade 3, percentual 2. `RoundingMode.HALF_UP` (igual ao `round()` do PostgreSQL para positivos). Exceção: preço sugerido arredonda para cima (A.6).
- Comparar com `compareTo`, nunca `equals`. Divisão sempre com escala e modo; divisor zero → exibir "—".
- Locale pt-BR (`Locale.of("pt", "BR")`): `R$ 1.234,56`; quantidade sem casas para unidades inteiras e com 3 casas para fracionadas; datas `dd/MM/yyyy` e `dd/MM/yyyy HH:mm`.
- Entrada de valores aceita `1.234,56`, `1234,56` e `1234.56`.

### 4.9 Auditoria
`AuditoriaService.registrar(con, acao, entidade, entidadeId, descricao)` na mesma transação da operação. Descrição legível ("Preço alterado de R$ 5,49 para R$ 5,99"). Quando houver liberação de supervisor, registrar quem liberou.

Ações: `LOGIN`, `LOGIN_FALHOU`, `LOGOUT`, `USUARIO_CRIADO`, `USUARIO_ALTERADO`, `PERMISSOES_ALTERADAS`, `SENHA_REDEFINIDA`, `USUARIO_DESBLOQUEADO`, `PRODUTO_CRIADO`, `PRODUTO_ALTERADO`, `PRODUTO_INATIVADO`, `PRODUTO_EXCLUIDO`, `ESTOQUE_AJUSTADO`, `INVENTARIO_APLICADO`, `INVENTARIO_CANCELADO`, `CAIXA_ABERTO`, `CAIXA_FECHADO`, `SANGRIA`, `SUPRIMENTO`, `ITEM_CANCELADO`, `VENDA_ABANDONADA`, `VENDA_CANCELADA`, `DESCONTO_LIBERADO`, `ENTRADA_CONFIRMADA`, `PROMOCAO_ALTERADA`, `CONFIGURACAO_ALTERADA`, `BACKUP_REALIZADO`, `DADOS_DEMO_GERADOS`.

### 4.10 Logs
SLF4J com Logback. Nunca registrar senha, hash ou senha do banco. Erro técnico vai para o log com stack trace; a tela mostra só a mensagem amigável.

---

## 5. Banco de dados

O esquema está em `V1__schema_inicial.sql` (24 tabelas, 4 views, 6 funções de trigger) e os dados de referência em `V2__dados_iniciais.sql`. Ambos foram executados num PostgreSQL 16 junto com `scripts/teste_integridade_banco.sql` (15 cenários) e com teste de concorrência e de backup/restauração.

### 5.1 Correções em relação ao modelo do TCC (DER/MER)

| # | No TCC | Agora | Por quê |
|---|---|---|---|
| 1 | `USUARIO.senha VARCHAR(15)` em texto puro | `usuario.senha_hash` (BCrypt) | senha não pode ficar legível no banco (RNF04) |
| 2 | Permissões como colunas `pdv/produtos/estoque/relatorios`; não existia `inventario`, embora a Fig. 16 tenha esse checkbox | tabelas `permissao` + `usuario_permissao`; `supervisor` continua como acesso total | nova rotina não exige `ALTER TABLE`; corrige a permissão de inventário que faltava |
| 3 | `USUARIO.nome VARCHAR(15)` servia de login | `login` (único, minúsculo) + `nome` (80) | nome de exibição é diferente de credencial |
| 4 | `PRODUTOS.nome VARCHAR(20)` | `produto.nome VARCHAR(120)` + `nome_busca` (sem acento) | descrição de varejo passa de 20 caracteres ("REFRIGERANTE COLA PET 2L" tem 24) |
| 5 | Produto sem código de barras, unidade, categoria e estoque mínimo | `produto_codigo_barras` (vários por produto), `unidade_medida`, `categoria`, `estoque_minimo` | o PDV lê código de barras; KG exige quantidade fracionada; relatórios por categoria; alerta de reposição |
| 6 | `custo DECIMAL(10,2)` | `custo NUMERIC(14,4)` (custo médio) + `ultimo_custo` | custo unitário tem mais de 2 casas (caixa com 12 por R$ 10,00 = 0,8333) |
| 7 | `inativo`, `data_alt`, `codigo_usuario` | `ativo`, `criado_em/por`, `atualizado_em/por` + `produto_historico_preco` (trigger) | saber quem mudou preço ou custo, e quando |
| 8 | `VENDA_ITEM` sem custo | `custo_unitario` e `custo_total` gravados na venda | sem isso, mudar o custo do produto altera o lucro de vendas passadas e o relatório de rentabilidade — objetivo central do TCC — fica errado |
| 9 | PK de `VENDA_ITEM` = (`id_cupom`, `cod_produto`) | `id` próprio + `sequencia` | o mesmo produto pode aparecer em duas linhas (bipado de novo, outro desconto, item cancelado) e o movimento de estoque precisa apontar para o item |
| 10 | `VENDA.data` e `VENDA_ITEM.data` como `DATE` | só `venda.data_hora TIMESTAMPTZ` | data repetida no item; `DATE` não guarda hora (relatório por horário, ordem das vendas, fechamento de caixa) |
| 11 | `VENDA` só com `valor`; `numero_cupom` | valor bruto, descontos, total, troco, status e dados de cancelamento; `venda_pagamento`; o id é o número da venda | descontos, várias formas de pagamento e cancelamento auditável; sem emissão fiscal não existe "cupom" |
| 12 | Sem controle de caixa | `caixa_sessao` + `caixa_movimento` + `vw_caixa_resumo` | abertura, sangria, suprimento e conferência do dinheiro |
| 13 | `MOVIMENTO_ESTOQUE.cod_produto` aceitava NULL | `produto_id NOT NULL` | todo movimento é de um produto |
| 14 | `MOVIMENTO_ESTOQUE.id_cupom` apontava para a venda e não para o item; `tipo_movimento VARCHAR(1)`; o DER tinha `saldo` e o MER não | `venda_item_id` / `inventario_item_id` / `entrada_item_id` com CHECK por tipo; tipo com domínio fechado e sinal validado; `saldo_anterior`, `saldo_posterior`, `custo_unitario`, `usuario_id`, `motivo` | kardex rastreável e valorizado em R$ |
| 15 | `PROD_ESTOQUE` atualizado pela aplicação | atualizado por trigger a partir do movimento; escrita direta bloqueada; movimentos imutáveis | saldo sempre igual à soma dos movimentos (RNF01), mesmo com edição manual no DBeaver |
| 16 | `INVENTARIO.atualizado BOOLEAN`; `ITENS_INV.diferenca` gravada | `status` (ABERTO/APLICADO/CANCELADO) + quem e quando aplicou; `diferenca` calculada pelo banco; `estoque_sistema` capturado na contagem (com custo, quem e quando); produto único por inventário | ciclo de vida claro; diferença nunca inconsistente |
| 17 | Ajuste "conforme a quantidade contada" | ajuste pela diferença capturada na contagem | se houver venda entre a contagem e a aplicação, igualar ao contado apaga essa venda do estoque (A.8) |
| 18 | Nomes mistos (`PRODUTOS` × `VENDA`, `ITENS_INV`, `cod_inv`) e DER/MER divergentes (`cod_movimento`, `data_inv`) | snake_case, singular, nomes por extenso, um único modelo | consistência e documentação |
| 19 | — | `fornecedor`, `entrada_mercadoria`, `entrada_mercadoria_item`, `promocao`, `configuracao`, `auditoria` | ampliações |

### 5.2 Tabelas

- **Acesso**: `usuario`, `permissao`, `usuario_permissao`, `configuracao`, `auditoria`.
- **Cadastros**: `unidade_medida`, `categoria`, `produto`, `produto_codigo_barras`, `produto_historico_preco`, `promocao`, `fornecedor`.
- **Estoque**: `produto_estoque` (saldo atual, 1:1 com produto), `movimento_estoque` (kardex).
- **Caixa e vendas**: `forma_pagamento`, `caixa_sessao`, `caixa_movimento`, `venda`, `venda_item`, `venda_pagamento`.
- **Inventário**: `inventario`, `inventario_item`.
- **Compras**: `entrada_mercadoria`, `entrada_mercadoria_item`.

Domínios fixos (iguais aos enums Java):
- `movimento_estoque.tipo`: `ENTRADA_COMPRA` (+), `VENDA` (−), `CANCELAMENTO_VENDA` (+), `AJUSTE_ENTRADA` (+), `AJUSTE_SAIDA` (−), `PERDA` (−), `INVENTARIO` (±).
- `forma_pagamento.tipo`: `DINHEIRO`, `PIX`, `CARTAO_DEBITO`, `CARTAO_CREDITO`, `OUTROS`.
- `venda.status`: `FINALIZADA`, `CANCELADA` · `caixa_sessao.status`: `ABERTO`, `FECHADO` · `caixa_movimento.tipo`: `SANGRIA`, `SUPRIMENTO` · `inventario.status`: `ABERTO`, `APLICADO`, `CANCELADO` · `entrada_mercadoria.status`: `ABERTA`, `CONFIRMADA`.
- Permissões (V2): `PRODUTOS`, `FORNECEDORES`, `COMPRAS`, `ESTOQUE`, `INVENTARIO`, `PDV`, `SANGRIA_SUPRIMENTO`, `CANCELAR_VENDA`, `DESCONTO_ACIMA_LIMITE`, `RELATORIOS`.
- Configurações (V2, tabela `configuracao`): `empresa.nome`, `empresa.documento`, `empresa.endereco`, `empresa.telefone`, `venda.permitir_estoque_negativo`, `venda.desconto_maximo_operador_pct`, `venda.cancelar_item_exige_supervisor`, `relatorio.margem_minima_pct`, `relatorio.dias_sem_giro`, `balanca.ativo`, `balanca.prefixo`, `balanca.digitos_codigo`, `balanca.tipo_valor` (`PRECO`|`PESO`), `backup.pasta`, `backup.pg_dump`, `backup.manter_ultimos`, `comprovante.rodape`. Acesso tipado por uma classe `Configuracoes`.

### 5.3 O que o banco já garante — o Java não reimplementa nem contorna

| Regra | Mecanismo | O que fazer no Java |
|---|---|---|
| Saldo muda só por movimento, com bloqueio da linha (seguro com vários caixas) | trigger `trg_movimento_aplica_saldo` | inserir em `movimento_estoque` via `EstoqueService.movimentar`; nunca escrever em `produto_estoque` (o banco recusa) |
| `saldo_anterior`/`saldo_posterior` e custo do movimento | mesmo trigger | não informar saldos; informar custo só em entrada de compra e inventário (senão vale o custo atual do produto) |
| Kardex imutável | `trg_movimento_imutavel` | correção = novo movimento de ajuste |
| Produto nasce com saldo 0 | `trg_produto_cria_estoque` | não inserir em `produto_estoque` |
| Histórico de preço e custo | `trg_produto_historico_preco` | sempre preencher `atualizado_por` no UPDATE de produto |
| `atualizado_em` | `fn_set_atualizado_em` (usuário, produto, fornecedor) | não preencher |
| Sinal × tipo; origem × tipo; motivo obrigatório em ajuste e perda | `ck_me_sinal`, `ck_me_origem` | validar antes para dar mensagem boa; o banco é a última barreira |
| Uma sessão de caixa aberta por terminal | `ux_caixa_aberto_por_terminal` | tratar 23505 com mensagem clara |
| Item de venda não baixa nem estorna duas vezes; item de inventário/entrada não aplica duas vezes | `ux_me_venda_item`, `ux_me_inventario_item`, `ux_me_entrada_item` | — |
| Total = bruto − descontos; líquido do item = bruto − descontos | `ck_venda_total`, `ck_vi_liquido` | calcular com as mesmas escalas e arredondamento |
| Cancelamento exige quem, quando e motivo | `ck_venda_cancelamento` | — |
| Produto com vínculo não pode ser excluído | chaves estrangeiras | verificar antes e oferecer "Inativar" |
| Mesma nota do mesmo fornecedor | `ux_em_documento` | mensagem "Esta nota já foi lançada (entrada nº X)" |

### 5.4 Views
- `vw_venda_item_rentabilidade` — itens de vendas FINALIZADAS e não cancelados, com receita (`valor_liquido`), `cmv`, `lucro_bruto`, produto e categoria. **Base de todo relatório de venda e lucro.**
- `vw_estoque_atual` — saldo, mínimo, custo médio, valor em estoque e flag `abaixo_do_minimo`.
- `vw_estoque_inconsistente` — produtos cujo saldo difere da soma dos movimentos. Deve estar **sempre vazia**; usada em teste e na tela de diagnóstico.
- `vw_caixa_resumo` — por sessão: abertura, suprimentos, sangrias, vendas em dinheiro, quantidade e total de vendas, canceladas e `dinheiro_esperado`.

### 5.5 Script de integridade
`scripts/teste_integridade_banco.sql` roda 15 cenários dentro de `BEGIN … ROLLBACK` (não deixa dados). Crie o teste de integração `IntegridadeBancoIT` que executa esse arquivo via JDBC no banco de teste e falha se houver erro.

---

## 6. Regras de negócio

### 6.1 Primeiro acesso, login e usuários
- **Primeiro acesso** (tabela `usuario` vazia): assistente pede o nome da loja (`empresa.nome`) e cria o supervisor (nome, login, senha e confirmação), com `deve_trocar_senha = false`. Não existe senha padrão.
- **Login**: normalizar (trim, minúsculas). Falha → mensagem genérica "Usuário ou senha inválidos". Cinco falhas seguidas → `bloqueado_ate = agora + 5 min` e mensagem com o horário de liberação. Sucesso zera `tentativas_falhas` e grava `ultimo_login_em`. Usuário inativo não entra.
- **Senha**: BCrypt custo 12; mínimo 6 caracteres; diferente do login. `deve_trocar_senha = true` obriga a troca antes de entrar.
- **Usuários** (só supervisor): listar, criar (senha provisória → `deve_trocar_senha = true`), editar nome, ativar/inativar, redefinir senha, desbloquear, marcar supervisor e permissões. Login não muda depois de criado.
- **Proteções**: ninguém inativa a si mesmo; sempre existe ao menos um supervisor ativo.
- **Log off** volta ao login. **Sair** encerra o sistema (avisa se há caixa aberto neste terminal).

### 6.2 Permissões
- Rotinas e ações liberáveis: as 10 permissões de 5.2.
- Exclusivo do supervisor: usuários, configurações, formas de pagamento, backup, auditoria, diagnóstico e gerador de dados de demonstração.
- **Liberação de supervisor** (componente reutilizável): quando a ação exige uma permissão que o usuário não tem (cancelar venda, desconto acima do limite, sangria), abre um diálogo pedindo login e senha de quem tem. Se válido, a ação vale só daquela vez e a auditoria registra quem liberou.
- Valores de lucro e margem (painel e relatórios) só para quem tem `RELATORIOS`.

### 6.3 Produtos
- **Campos**: código (gerado, somente leitura), nome*, categoria, unidade* (padrão UN), preço de venda*, custo*, estoque mínimo, PLU (só com balança ativa e unidade fracionada), códigos de barras (lista: adicionar, remover, marcar principal), ativo, estoque atual (somente leitura), última alteração (usuário e data/hora).
- **Estoque inicial** (só no cadastro novo): opcional; gera `AJUSTE_ENTRADA` com motivo "Estoque inicial" na mesma transação.
- **Validações**: nome com 2 a 120 caracteres (trim, espaços duplos removidos); preço ≥ 0 (produto com preço 0 não é vendido); custo ≥ 0; código de barras só com dígitos, tamanho 8/12/13/14 e dígito verificador válido (A.1); código de barras repetido → mensagem dizendo em qual produto ele está; nome repetido → só aviso.
- **Preço abaixo do custo** → confirmação: "O preço está abaixo do custo: cada venda dará prejuízo. Salvar mesmo assim?".
- **Simulador** na própria tela, atualizado enquanto digita: lucro unitário, margem % (lucro ÷ preço) e markup % (lucro ÷ custo). Campo "margem desejada %" com botão "Aplicar preço sugerido" = custo ÷ (1 − margem), arredondado **para cima** no centavo (A.6).
- **Excluir**: só sem vendas, movimentos, inventários, entradas ou promoções; senão oferecer **Inativar**. Inativo não aparece no PDV nem na busca padrão (filtro "mostrar inativos"), mas continua nos relatórios.
- **Busca**: texto → nome contém o termo, sem diferenciar acento ou maiúsculas (`nome_busca LIKE '%termo%'`, termo normalizado em Java pela mesma regra: minúsculas e sem acento); só dígitos → código interno ou código de barras exato. Filtros: categoria e situação. Colunas: código, nome, categoria, preço, estoque, última alteração. Duplo clique abre o produto.
- **Histórico** (aba): data, usuário, preço anterior → novo, custo anterior → novo.
- **Categorias**: cadastro simples, nome único sem diferenciar maiúsculas; em uso → inativar em vez de excluir.
- Unidades: só leitura nesta versão (vêm do V2).

### 6.4 Estoque
- `EstoqueService.movimentar(con, produtoId, tipo, quantidadeComSinal, origem, motivo, custoOpcional)` é o **único** caminho para movimentar estoque; todos os módulos o usam.
- Quantidade de unidade não fracionada tem de ser inteira — regra válida no sistema todo.
- **Consulta** (Fig. 17 revisada): busca por código, código de barras ou nome; cabeçalho com saldo, mínimo, custo médio e valor em estoque; kardex com data/hora, tipo (descrição amigável), quantidade (+/−), saldo após, custo, usuário e origem ("Venda nº 123", "Inventário nº 4", "Entrada nº 9" ou o motivo); filtro de período (padrão: últimos 30 dias).
- **Ajuste manual** (permissão ESTOQUE): tipo Entrada, Saída ou Perda; quantidade > 0 (o sinal vem do tipo); motivo obrigatório (lista de sugestões + texto livre; para perda: Vencimento, Avaria, Furto/desvio, Consumo interno, Outros, gravado como "Vencimento: observação"). Atalho **"Ajustar saldo para…"**: calcula a diferença e gera entrada ou saída.
- **Alertas**: saldo abaixo do mínimo (destaque na consulta e no painel); saldo negativo em vermelho.
- **Diagnóstico** (supervisor, em Configurações): mostra `vw_estoque_inconsistente` (esperado: vazia).

### 6.5 Caixa
- O PDV só vende com sessão `ABERTO` no terminal deste computador. Sem sessão → diálogo de abertura com fundo de troco (≥ 0).
- Havendo sessão aberta no terminal, quem entrar continua nela; cada venda grava o operador que a fez.
- **Sangria e suprimento** (permissão `SANGRIA_SUPRIMENTO` ou liberação): valor > 0 e motivo. Sangria não pode passar do `dinheiro_esperado` atual.
- **Fechamento às cegas**: o operador informa o dinheiro contado sem ver o esperado. O sistema grava `valor_esperado` (= `dinheiro_esperado` de `vw_caixa_resumo`), `valor_contado`, `fechado_em`, `fechado_por` e status `FECHADO`; só então mostra sobra/falta e o **relatório de fechamento** (R11): abertura, vendas por forma de pagamento, sangrias, suprimentos, esperado × contado, número de vendas, canceladas e descontos concedidos.
- Venda e cancelamento de venda só em sessão aberta.

### 6.6 PDV

**Leitura de produto**
1. Campo de leitura sempre focado; o leitor de código de barras funciona como teclado + Enter.
2. Entradas aceitas: `código` · `quantidade*código` (ex.: `3*7891000315507`, `0,345*123`) · etiqueta de balança (6.9) · texto (abre a busca F2 já filtrada).
3. Ordem de busca: código de barras exato → código interno → etiqueta de balança (se ativa; entra na fase 8) → "Produto não encontrado" (alerta visual e sonoro, campo limpo, foco mantido).
4. Inativo ou com preço 0 → bloqueia com mensagem.
5. Preço do item = promoção vigente na data da venda (6.8) ou preço de venda; o item guarda `promocao_id`. Promoções entram na fase 8; até lá vale o preço de venda, mas deixe o ponto de decisão do preço isolado num método (`PrecoService`) para a fase 8 só preenchê-lo.
6. Unidade fracionada sem quantidade informada e fora de etiqueta de balança → pede a quantidade (3 casas).
7. Estoque: com `venda.permitir_estoque_negativo = false`, bloqueia se (saldo − quantidade já no carrinho − nova quantidade) < 0; com `true`, mostra aviso não bloqueante.
8. O carrinho fica em memória; a venda só vai para o banco na finalização, numa única transação.

**Tela** (Fig. 19 revisada): total em fonte grande; lista de itens (seq., código, descrição, quantidade, unitário, desconto, total; cancelados riscados); último item em destaque; painel com subtotal, descontos, total, operador, terminal e nº da sessão; barra de atalhos visível.

**Atalhos**: F1 ajuda · F2 buscar produto · F3 quantidade do próximo item · F4 desconto no item selecionado · F5 desconto na venda · F6 cancelar item selecionado · F7 consultar preço (sem vender) · F8 sangria/suprimento · F9 vendas da sessão (reimprimir ou cancelar) · F10 pagamento/finalizar · F12 fechar caixa · Esc abandonar a venda em andamento (com confirmação).

**Descontos**
- No item: em R$ ou %, até o valor do item. Na venda: em R$ ou %, até o total.
- Limite: (descontos dos itens + desconto da venda) ÷ valor bruto ≤ `venda.desconto_maximo_operador_pct`; acima disso exige `DESCONTO_ACIMA_LIMITE` ou liberação (auditoria `DESCONTO_LIBERADO`).
- O desconto da venda é **rateado** entre os itens não cancelados, proporcional a (valor bruto − desconto do item), pelo método do maior resto em centavos (A.4). A soma dos rateios é exatamente o desconto da venda.

**Cancelamentos durante a venda**
- Item (F6): fica riscado e sai do total. Se `venda.cancelar_item_exige_supervisor = true`, pede liberação. Auditoria `ITEM_CANCELADO`. É gravado com `cancelado = true`, sem rateio e sem movimento de estoque.
- Abandonar (Esc): confirmação; auditoria `VENDA_ABANDONADA` com itens e total; nada é gravado em `venda`.

**Pagamento (F10)**
- Diálogo com as formas ativas; pode combinar várias; mostra total, pago, falta e troco.
- Só dinheiro pode ultrapassar o que falta (gera troco); as demais formas ficam limitadas ao que falta.
- Finaliza quando pago ≥ total. Em `venda_pagamento.valor` grava-se o valor abatido: no dinheiro, recebido − troco. `venda.troco` = troco devolvido.
- Total zero (100% de desconto) dispensa pagamento.

**Finalização** (`VendaService.finalizar`, uma transação)
1. Reconfere: sessão aberta no terminal, produtos ativos, regra de estoque.
2. Calcula cada item: `valor_bruto` = round(quantidade × preço, 2) — em etiqueta de balança com preço, o valor da etiqueta —; `desconto`; `desconto_rateado`; `valor_liquido`; `custo_unitario` = custo do produto lido **dentro** da transação; `custo_total` = round(quantidade × custo_unitario, 2).
3. Insere `venda` com os totais → `venda_item` (sequência = ordem de registro, incluindo cancelados) → `venda_pagamento` → para cada item não cancelado, `EstoqueService.movimentar(VENDA, −quantidade, venda_item_id)`.
4. Commit → troco em destaque → oferece o comprovante → limpa a tela.
- Falha: rollback, carrinho preservado, mensagem clara.

**Cancelamento de venda finalizada** (F9): permissão `CANCELAR_VENDA` ou liberação; só vendas da sessão aberta; motivo obrigatório. Numa transação: status `CANCELADA` + `cancelada_em/por` + `motivo_cancelamento`, movimento `CANCELAMENTO_VENDA` (+quantidade) para cada item não cancelado e auditoria. `cancelada_por` é quem autorizou (o próprio usuário, se tiver a permissão, ou o supervisor da liberação); a auditoria registra o operador e quem liberou. A venda sai do faturamento e do dinheiro esperado.

**Comprovante não fiscal**: dados da loja; **"NÃO É DOCUMENTO FISCAL"**; nº da venda, data/hora e operador; itens; descontos; total; pagamentos; troco; rodapé (`comprovante.rodape`). Visualizar e salvar em PDF; imprimir na impressora padrão (fase 8).

### 6.7 Inventário (Fig. 18 revisada)
- **Criar**: descrição; adicionar produtos por busca/código de barras, por categoria ou "todos os ativos". Um produto não pode estar em dois inventários `ABERTO`.
- **Aba Contagem**: código, descrição, unidade e campo de quantidade contada; o leitor de código de barras posiciona no item. **Contagem cega**: o estoque do sistema não aparece nesta aba.
- **Ao gravar uma contagem**: `quantidade_contada`, `estoque_sistema` = saldo naquele momento, `custo_unitario` = custo atual, `contado_em`, `contado_por`. Recontagem sobrescreve os cinco campos.
- **Aba Divergências**: sistema, contado, diferença, valor da diferença (diferença × custo), totais de sobra e falta em R$ e quantidade de itens não contados.
- **Aplicar** (permissão INVENTARIO, confirmação com resumo): para cada item contado com diferença ≠ 0, movimento `INVENTARIO` com quantidade = `diferenca` e custo = `custo_unitario` do item. Itens não contados são ignorados (aviso com a quantidade). Status `APLICADO`, `aplicado_por/em`, auditoria. Depois de aplicado fica somente leitura.
- **Cancelar** inventário aberto → `CANCELADO`.
- **Por que aplicar a diferença**: entre a contagem e a aplicação o estoque continua mexendo (vendas, perdas). Igualar o saldo ao contado apagaria essas saídas. Exemplo em A.8.
- Orientação na tela: contar com a loja fechada ou digitar a contagem logo após contar cada item.

### 6.8 Promoções (fase 8)
- Produto, preço promocional (> 0), data de início e de fim (inclusive), ativa.
- Não permitir duas promoções ativas do mesmo produto com períodos sobrepostos.
- Preço promocional abaixo do custo → confirmação.
- No PDV vale a promoção cujo período contém a data da venda.
- Lista com filtros: vigentes, futuras, encerradas.

### 6.9 Etiqueta de balança (fase 8)
- Configuração: `balanca.ativo`, `balanca.prefixo` (1 dígito, padrão 2), `balanca.digitos_codigo` (4 a 6), `balanca.tipo_valor` (`PRECO` ou `PESO`).
- Reconhecimento: EAN-13 que começa com o prefixo e tem dígito verificador válido. PLU = os N dígitos após o prefixo; valor = os dígitos seguintes até o 12º, como inteiro.
- `PRECO`: valor em centavos é o **total do item, sem recálculo**; quantidade = valor ÷ preço por kg, 3 casas, HALF_UP.
- `PESO`: valor em gramas; quantidade = valor ÷ 1000; total = round(quantidade × preço, 2).
- PLU sem produto → "Produto de balança não encontrado (PLU n)". Vetores em A.7.

### 6.10 Fornecedores e compras (fase 7)
- **Fornecedor**: razão social*, nome fantasia, CPF/CNPJ (opcional), telefone, e-mail, contato, cidade/UF, observação, ativo. CPF com 11 dígitos; CNPJ com 14 posições, numérico ou **alfanumérico** (novo formato da Receita Federal: letras nas 12 primeiras posições, dígitos verificadores numéricos), DV validado conforme A.3. Gravar sem máscara, exibir com máscara.
- **Entrada de mercadoria**: fornecedor*, número do documento (NF), data de emissão, valor total da nota (para conferência) e itens (produto por busca ou código de barras, quantidade, custo unitário — ou valor total da linha, que calcula o custo com 4 casas). Status `ABERTA`: pode editar e excluir.
- **Confirmar** (permissão COMPRAS), uma transação, item a item na ordem:
  1. `SELECT quantidade FROM produto_estoque WHERE produto_id = ? FOR UPDATE`;
  2. novo custo médio (A.5): saldo > 0 → (saldo × custo atual + quantidade × custo da entrada) ÷ (saldo + quantidade), 4 casas; saldo ≤ 0 → custo da entrada;
  3. `UPDATE produto SET custo, ultimo_custo, atualizado_por` (o trigger grava o histórico);
  4. movimento `ENTRADA_COMPRA` (+quantidade, custo da entrada, `entrada_item_id`).
  
  Depois: status `CONFIRMADA`, `confirmado_por/em`, auditoria.
- Soma dos itens diferente do valor total da nota → aviso antes de confirmar (não bloqueia).
- Entrada confirmada é imutável (correções por ajuste de estoque).
- Após confirmar, listar os produtos cuja margem ficou abaixo de `relatorio.margem_minima_pct` com o novo custo, com preço sugerido e atalho para reajustar.

### 6.11 Importações (fase 8)
- **Produtos por planilha (CSV)**: colunas `nome;codigo_barras;unidade;categoria;custo;preco_venda;estoque_minimo;estoque_inicial`; separador `;`, decimal `,`; UTF-8 (aceitar também Windows-1252). Pré-visualização com validação linha a linha; erros apontam a linha e o campo; nada é importado enquanto houver erro (ou o usuário escolhe ignorar as linhas com erro). Categoria inexistente é criada. Estoque inicial gera `AJUSTE_ENTRADA` com motivo "Importação". Oferecer modelo `.csv` para baixar. (A pesquisa do TCC mostrou que metade dos lojistas usa planilha: isto facilita a migração.)
- **XML de NF-e (opcional)**, na entrada de mercadoria: ler o emitente (CNPJ → fornecedor; cria se não existir), número e data de emissão, e os itens (`cEAN`/`cEANTrib`, `xProd`, `qCom`/`vUnCom`). Quando houver EAN tributável e a unidade tributável for diferente da comercial, preferir `qTrib`/`vUnTrib`, que costumam estar na unidade de venda. Vincular itens a produtos pelo código de barras; itens sem vínculo: o usuário escolhe o produto ou cadastra. Gera uma entrada `ABERTA` para conferência.

### 6.12 Backup (fase 8, supervisor)
- Manual (Configurações) e automático ao sair do sistema (no máximo um por dia), com `pg_dump -Fc`. Caminho do `pg_dump` configurável; se vazio, procurar no PATH e, no Windows, em `C:\Program Files\PostgreSQL\*\bin`. Senha via variável de ambiente `PGPASSWORD` no `ProcessBuilder`, nunca na linha de comando. Arquivo `varesync_AAAAMMDD_HHmm.backup`. Manter os últimos `backup.manter_ultimos`.
- O `pg_dump` deve ser da mesma versão do servidor ou mais novo.
- Restauração fica **fora** da aplicação e documentada no README: com o sistema fechado, `pg_restore --clean --if-exists -d varesync arquivo.backup` (ou restaurar num banco novo). Restauração completa funciona com os triggers (testado).

### 6.13 Configurações (supervisor)
Dados da loja; parâmetros de venda; margem mínima; dias sem giro; balança; backup; formas de pagamento (criar, ativar/desativar); diagnóstico do estoque; consulta de auditoria (período, usuário, ação); gerador de dados de demonstração.

### 6.14 Gerador de dados de demonstração (fase 6, ampliado na fase 7)
- Em Configurações, só para supervisor e só se não houver vendas. Semente fixa (resultado reproduzível). Roda em `Task` com progresso.
- Cria: 2 operadores e 1 gerente (senhas exibidas ao final); cerca de 80 produtos realistas em todas as categorias, com códigos de barras válidos, alguns pesáveis com PLU, e estoque inicial; 60 dias de operação com uma sessão de caixa por dia (abertura, 1 ou 2 sangrias, fechamento às vezes com pequena diferença), 20 a 60 vendas por dia com picos entre 11h–13h e 17h–19h, 1 a 15 itens por venda, formas de pagamento variadas, alguns descontos, ~2% de itens e ~1% de vendas canceladas; perdas eventuais; um inventário aplicado com divergências; reajustes de preço; alguns produtos sem venda (relatório de sem giro) e alguns com preço abaixo do custo (alerta).
- Usa os próprios services (regras e triggers valendo) com um `Clock` ajustável para datas passadas.
- Fase 7: acrescenta 3 fornecedores e entradas semanais com variação de custo.

---

## 7. Indicadores e relatórios

### 7.1 Fórmulas (Tabela 2 do TCC, completada)

| Indicador | Fórmula |
|---|---|
| Receita (faturamento) | Σ `receita` (= `valor_liquido`) de `vw_venda_item_rentabilidade` |
| CMV | Σ `cmv` (custo gravado na venda) |
| Lucro bruto | Receita − CMV |
| Margem % | Lucro ÷ Receita × 100; no agregado, Σ lucro ÷ Σ receita (nunca média de margens — A.6) |
| Markup % | Lucro ÷ CMV × 100 |
| Ticket médio | Receita ÷ número de vendas |
| Valor em estoque | saldo × custo médio; no total, saldo negativo conta como zero |
| Margem negativa | lucro < 0 (custo maior que a receita) |
| Baixa rentabilidade | 0 ≤ margem < `relatorio.margem_minima_pct` |
| Divergência de inventário | contado − sistema; em R$, × custo do item |
| Curva ABC | ordenar por receita (ou lucro) decrescente; % acumulado **antes** do item < 80% → A; < 95% → B; senão C (A.9) |
| Produto sem giro | saldo > 0 e nenhuma venda há `relatorio.dias_sem_giro` dias ou mais |
| Diferença de caixa | contado − esperado |

### 7.2 Regras comuns
- Fontes: `vw_venda_item_rentabilidade` (venda e lucro), `vw_estoque_atual` (estoque), `vw_caixa_resumo` (caixa). Assim todos os relatórios e o painel batem entre si.
- Período: `data_hora >= início 00:00 AND data_hora < (fim + 1 dia) 00:00`. Nunca `BETWEEN` com datas.
- Agrupamento por dia/hora: `data_hora::date` e `extract(hour from data_hora)` funcionam no fuso da JVM, porque o driver define o fuso da sessão.
- Vendas e itens cancelados ficam fora de faturamento e lucro (a view garante) e aparecem só no relatório de cancelamentos.
- Todo relatório tem linha de totais e colunas ordenáveis.

### 7.3 Catálogo

| ID | Relatório | Filtros | Colunas principais | Fase |
|---|---|---|---|---|
| R01 | Vendas por período | período, operador | por dia: nº de vendas, receita, descontos, CMV, lucro, margem %, ticket médio; detalhe por venda opcional | 6 |
| R02 | Produtos mais vendidos | período, categoria, top N, critério (quantidade, receita ou lucro) | posição, produto, quantidade, receita, lucro, margem %, % da receita | 6 |
| R03 | Estoque atual | categoria, só abaixo do mínimo, incluir inativos | produto, unidade, saldo, mínimo, custo médio, valor em estoque | 6 |
| R04 | Movimentação de estoque | período, produto, tipo | data/hora, produto, tipo, quantidade, saldo após, custo, usuário, origem/motivo; totais de entradas e saídas | 6 |
| R05 | Rentabilidade por produto | período, categoria | produto, quantidade, receita, CMV, lucro, margem %, markup %, situação (negativa / baixa / ok) | 6 |
| R06 | Rentabilidade por categoria | período | categoria, receita, CMV, lucro, margem %, % do lucro total | 6 |
| R07 | Curva ABC | período, critério (receita ou lucro) | classe, produto, valor, %, % acumulado | 6 |
| R08 | Vendas por forma de pagamento | período | forma, nº de vendas, valor, % | 6 |
| R09 | Vendas por operador | período | operador, nº de vendas, receita, ticket médio, descontos, cancelamentos | 6 |
| R10 | Vendas por hora e dia da semana | período | grade hora × dia com receita e nº de vendas | 6 |
| R11 | Fechamento de caixa | sessão | ver 6.5 | 4 |
| R12 | Produtos sem giro | dias, categoria | produto, saldo, valor parado, última venda | 6 |
| R13 | Resultado de inventário | inventário | produto, sistema, contado, diferença, valor; totais de sobra e falta | 5 |
| R14 | Perdas e ajustes | período, tipo | motivo, quantidade, valor a custo | 6 |
| R15 | Alterações de preço | período | produto, data, preço anterior e novo, usuário (para trocar etiquetas de gôndola) | 6 |
| R16 | Cancelamentos | período, operador | vendas e itens cancelados, motivo, quem fez e quem liberou | 6 |
| R17 | Compras por fornecedor | período, fornecedor | fornecedor, nº de entradas, valor, itens | 7 |

R01 a R05 são obrigatórios (são os do diagrama de casos de uso do TCC). Os demais podem ficar para a fase 9 se o prazo apertar.

### 7.4 Implementação
- Cada relatório é uma classe que implementa `Relatorio<T>`: nome, filtros usados, consulta parametrizada que devolve `List<T>` (records) e definição de colunas (título, tipo — texto, moeda, quantidade, percentual, data —, alinhamento, totalizável).
- Tela genérica (Fig. 22 revisada): escolher o relatório → aparecem só os filtros dele → Gerar (em `Task`) → tabela com linha de totais → **Exportar CSV** (genérico: `;`, decimal `,`, UTF-8 com BOM para abrir certo no Excel) → **Exportar PDF**.
- PDF com cabeçalho e rodapé padrão: nome da loja, título, período e filtros, "emitido por … em …", página x de y. Para os relatórios tabulares, prefira um gerador genérico a partir da definição de colunas (montando o layout pela API do JasperReports); se isso se mostrar instável, use um JRXML por relatório. Comprovante e fechamento de caixa têm layout próprio.
- Teste automatizado que compila todos os JRXML existentes e gera PDF com dados fictícios.

### 7.5 Painel (tela principal, Fig. 15 revisada)
- Cartões do dia (só com `RELATORIOS`): faturamento, nº de vendas, ticket médio, lucro bruto e margem %, cada um com variação em relação ao mesmo dia da semana anterior.
- Gráfico: faturamento e lucro dos últimos 30 dias.
- Top 5 produtos do mês por lucro.
- Alertas clicáveis: produtos abaixo do mínimo; produtos com preço abaixo do custo; caixa aberto neste terminal; inventários abertos; último backup há mais de 7 dias (supervisor).
- Sem `RELATORIOS`: só atalhos e alertas operacionais.
- Barra de status em todas as telas: usuário, terminal, situação do caixa, data/hora, conexão.

---

## 8. Interface

### 8.1 Diretrizes
- Janela principal com menu lateral (Painel, PDV, Produtos, Estoque, Inventário, Compras, Fornecedores, Relatórios, Usuários, Configurações), exibindo só o permitido; conteúdo à direita. O PDV abre em janela própria, maximizada.
- Teclado: Enter confirma, Esc cancela/fecha, Tab em ordem lógica, foco inicial no primeiro campo, atalhos documentados no rodapé ou na ajuda (F1).
- Formulários: rótulos claros, obrigatórios com *, validação ao salvar com a mensagem junto do campo; máscaras de moeda, quantidade, CPF/CNPJ e telefone.
- Feedback: notificação discreta para sucesso; diálogo só para confirmação e erro; ações destrutivas sempre confirmadas.
- Visual: tema claro e limpo (AtlantaFX ou CSS próprio), uma cor de destaque, fonte do sistema de 13–14 px; PDV com fonte grande (total ≥ 40 px) e alto contraste; números alinhados à direita; negativos em vermelho.
- Janela mínima 1280×720; tabelas com colunas redimensionáveis e ordenáveis.
- Linguagem do lojista: "Não foi possível salvar: este código de barras já pertence ao produto 'Café 500 g'".

### 8.2 Ajustes nos protótipos do TCC
- **Fig. 14 Login**: campo "Usuário" (login) e "Senha"; Enter entra; mensagem de erro abaixo dos campos.
- **Fig. 15 Principal**: painel de 7.5; "Log off" e "Sair" mantidos.
- **Fig. 16 Usuários**: lista + formulário; permissões do catálogo (inclui Inventário) e marcação de Supervisor; botões redefinir senha, desbloquear e inativar.
- **Fig. 17 Estoque**: busca por código, código de barras ou nome; kardex com saldo após e origem.
- **Fig. 18 Inventário**: abas Contagem (cega) e Divergências; botão Aplicar.
- **Fig. 19 PDV**: layout de 6.6.
- **Fig. 20 Produto**: Estoque Atual somente leitura; estoque inicial; simulador; códigos de barras; aba de histórico.
- **Fig. 21 Busca**: filtros de categoria e situação.
- **Fig. 22 Relatórios**: filtros conforme o relatório, prévia na tela, exportar PDF e CSV.

---

## 9. Testes e qualidade

### 9.1 Unitários (JUnit 5, sem banco)
Validadores de GTIN, CPF e CNPJ; `Dinheiro` (arredondamento e leitura pt-BR); rateio; custo médio; preço sugerido; margem e markup; curva ABC; leitura de etiqueta de balança; leitura da entrada do PDV (`3*789...`); cálculo do carrinho (totais, limite de desconto, troco). **Todos os vetores do Apêndice A são obrigatórios.**

### 9.2 Integração (banco `varesync_test`)
- Antes da suíte: `flyway.clean()` + `migrate()`. Configuração por `src/test/resources/teste.properties` ou variáveis de ambiente.
- `IntegridadeBancoIT` executa `scripts/teste_integridade_banco.sql`.
- Por service: login e bloqueio; produto com estoque inicial; venda completa (saldo, movimentos, pagamentos, totais); cancelamento de venda; inventário com movimento depois da contagem; entrada com custo médio; fechamento de caixa; permissão negada no service.
- Ao fim de cada teste de integração: `vw_estoque_inconsistente` vazia.

### 9.3 Roteiro de testes manuais (`docs/ROTEIRO_TESTES.md`)
Um caso por caso de uso do TCC (Quadros 11 a 20) e por funcionalidade ampliada: ID, pré-condição, passos, resultado esperado, resultado obtido. Atualizar a cada fase. (Alimenta o Quadro 23 do TCC.)

### 9.4 Métricas (Quadro 24 do TCC) — medir e registrar em `docs/PROGRESSO.md`
- Registrar um item no PDV: < 300 ms com 5 mil produtos.
- Relatório de 60 dias com os dados de demonstração: < 3 s. Painel: < 2 s.
- Abertura do sistema até a tela de login: < 5 s.
- Venda de 3 itens paga em dinheiro: só teclado, sem mouse.
- Defeitos encontrados por fase.

### 9.5 Rastreabilidade (`docs/RASTREABILIDADE.md`)
Tabela RF/RNF → telas e classes → testes automatizados → casos do roteiro manual.

### 9.6 `docs/PROGRESSO.md` (modelo)

```markdown
# Progresso
## Fase atual
## Fases concluídas  (data · resumo · testes)
## Decisões  (data · decisão · motivo)
## Pendências e riscos
## Métricas
```

---

## 10. Plano de fases

### Fase 0 — Fundação
**Entregas**: `pom.xml`; estrutura de pacotes; `Launcher` e `App`; `AppConfig` + tela de conexão; `Banco` (HikariCP); `Migracao` (Flyway usando as V1/V2 do repositório); `AppContext`; `Transacao`; `TradutorErros` e `MensagensBanco`; `Dinheiro`, `Formatos`, `Textos`; `logback.xml`; janela principal com menu lateral vazio e barra de status; README (pré-requisitos, criação do banco, como rodar); `docs/PROGRESSO.md`, `RASTREABILIDADE.md`, `ROTEIRO_TESTES.md`; `IntegridadeBancoIT`; testes unitários de `Dinheiro` e `Textos`.
**Aceite**: `mvn -q verify` passa; `mvn javafx:run` abre o sistema; com banco vazio as migrations rodam; sem banco aparece a tela de conexão; `IntegridadeBancoIT` verde.

### Fase 1 — Acesso e usuários (RF12, RF13, RNF04)
**Entregas**: assistente de primeiro acesso; login com bloqueio; troca de senha obrigatória; `Sessao`; menu por permissão; tela de usuários e permissões; componente de liberação de supervisor; `AuditoriaService` (login, logout, usuários); log off e sair.
**Aceite**: primeiro acesso cria o supervisor; 5 senhas erradas bloqueiam por 5 minutos; operador sem `PRODUTOS` não vê o menu e o service recusa a chamada; não é possível inativar o último supervisor; auditoria registra os logins.

### Fase 2 — Produtos (RF01, RF02, RF03)
**Entregas**: categorias; cadastro de produto completo (validações, códigos de barras com DV, simulador, estoque inicial, inativar/excluir, histórico); busca; validadores com testes.
**Aceite**: produto com estoque inicial 10 → saldo 10 e um movimento `AJUSTE_ENTRADA`; código de barras com DV errado recusado; código repetido informa o produto dono; mudança de preço aparece no histórico com usuário; produto movimentado não exclui e oferece inativar; buscar "acucar" encontra "Açúcar".

### Fase 3 — Estoque (RF09)
**Entregas**: `EstoqueService.movimentar`; consulta com kardex; ajustes (entrada, saída, perda, ajustar saldo para); alertas de mínimo; tela de diagnóstico.
**Aceite**: kardex mostra o saldo após cada movimento; ajuste sem motivo é recusado; unidade UN não aceita 1,5; "ajustar saldo para 7" com saldo 10 gera saída de 3; `vw_estoque_inconsistente` vazia.

### Fase 4 — Caixa e PDV (RF04, RF05, RF06)
**Entregas**: abertura, sangria, suprimento e fechamento às cegas com relatório R11; PDV completo (leitura, multiplicador, quantidade fracionada, carrinho, descontos com limite e rateio, cancelamento de item, abandono, pagamento múltiplo com troco, finalização transacional, cancelamento de venda com liberação); comprovante em tela e PDF; testes unitários do carrinho e de integração da venda.
**Aceite** (roteiro): abrir caixa com R$ 100,00; vender 3 × produto de R$ 5,49 + 0,345 kg de produto KG; desconto de 5% na venda (dentro do limite) com rateio conferindo; pagar R$ 10,00 no PIX e o restante em dinheiro com uma nota de R$ 50,00, com troco correto; saldos baixados e um movimento `VENDA` por item; cancelar a venda com liberação → saldos devolvidos e venda fora do dinheiro esperado do caixa; sangria de R$ 50,00; fechamento às cegas mostra a diferença; nenhuma venda com caixa fechado.

### Fase 5 — Inventário (RF07, RF08)
**Entregas**: criar e adicionar itens (busca, categoria, todos); contagem cega com leitor; divergências com valor; aplicar; cancelar; relatório R13.
**Aceite**: cenário A.8 (sistema 10, contagem 8, sai 1 depois, aplicação → saldo 7); item não contado não altera o saldo; inventário aplicado fica somente leitura; produto não entra em dois inventários abertos.

### Fase 6 — Relatórios, painel e dados de demonstração (RF10, RF11)
**Entregas**: infraestrutura de relatórios (`Relatorio<T>`, tela genérica, CSV, PDF); R01 a R05; depois R06 a R10, R12, R14 a R16; painel (7.5); gerador de dados de demonstração.
**Aceite**: com os dados de demonstração, receita e lucro de R01 e R05 batem no mesmo período; margem agregada correta; PDF e CSV abrem (CSV no Excel com acentos); painel carrega em menos de 2 s; venda cancelada não entra no faturamento.
**Fim do MVP do TCC: RF01 a RF13 atendidos.**

### Fase 7 — Fornecedores e compras
**Entregas**: fornecedores (CPF/CNPJ alfanumérico); entrada de mercadoria (`ABERTA`/`CONFIRMADA`); custo médio; aviso de divergência do total; nota duplicada; sugestão de reajuste; R17; gerador de dados ampliado.
**Aceite**: vetores de A.5 (saldo 7 a 3,2000 + 12 a 3,5000 → 3,3895; saldo ≤ 0 usa o custo da entrada); histórico de custo registrado; nota repetida recusada com mensagem clara.

### Fase 8 — Extras (nesta ordem, cada um independente)
**Entregas**: promoções; etiqueta de balança; importação de produtos por CSV; backup manual e automático; tela de auditoria; impressão do comprovante; importação de XML de NF-e (opcional).
**Aceite**: vetores de A.7; promoção sobreposta recusada; CSV com erro na linha 3 aponta a linha e não importa nada; backup gera o arquivo e mantém só os últimos N.

### Fase 9 — Qualidade e entrega
**Entregas**: revisão geral (duplicações, nomes, exceções engolidas); testes dos services principais; roteiro manual completo com resultados; métricas medidas; README final (instalação, backup e restauração, usuários de demonstração); empacotamento com `jpackage` (app-image para Windows, opcional); roteiro de demonstração de 10 minutos para a banca em `docs/DEMO.md`.
**Aceite**: build limpo; todos os testes verdes; instalação do zero seguindo só o README funciona.

---

## 11. Fora do escopo (trabalhos futuros no TCC)
- Emissão fiscal (NFC-e) e integração com a SEFAZ. Por isso o comprovante traz "NÃO É DOCUMENTO FISCAL"; o uso real em loja exigiria um emissor fiscal.
- Integração com maquininha (TEF), financeiro completo (contas a pagar e receber, fluxo de caixa), múltiplas lojas, versão web/mobile, nuvem.

---

## Apêndice A — Vetores de teste (conferidos em código)

### A.1 GTIN (EAN-8/12/13/14)
Algoritmo: sem o último dígito, da direita para a esquerda, pesos 3, 1, 3, 1…; DV = (10 − soma mod 10) mod 10.
- `789100031550` → DV 7 → `7891000315507` válido
- `789123456789` → DV 5 → `7891234567895` válido
- `1234567` (GTIN-8) → `12345670` válido
- `7891234567890` → inválido

### A.2 CPF
- `52998224725` válido · `52998224724` inválido · `11111111111` inválido (dígitos todos iguais)

### A.3 CNPJ numérico e alfanumérico
Algoritmo: valor de cada caractere = código ASCII − 48 (dígitos valem 0–9; letras A–Z valem 17–42); pesos 2 a 9 da direita para a esquerda, reiniciando; resto = soma mod 11; DV = 0 se resto < 2, senão 11 − resto; o 2º DV inclui o 1º.
- `11222333000181` válido · `11222333000180` inválido
- `12ABC34501DE35` válido (exemplo da Receita Federal) · `12ABC34501DE34` inválido
- `AB12CD34EFGH83` válido
- `00000000000000` inválido (caracteres todos iguais)

### A.4 Rateio do desconto da venda (maior resto, em centavos)
Cada item recebe a parte inteira de (centavos do desconto × base do item ÷ soma das bases); os centavos que sobram vão, um a um, para os itens com maior parte fracionária (empate: maior base; depois ordem do item). Base = valor bruto − desconto do item.
- bases 1,00 · 1,00 · 1,00; desconto 0,10 → 0,04 · 0,03 · 0,03
- bases 10,00 · 20,00 · 30,01; desconto 5,00 → 0,83 · 1,67 · 2,50
- bases 1,00 · 1,00 · 1,00 · 1,00; desconto 0,02 → 0,01 · 0,01 · 0,00 · 0,00
- base 16,47; desconto 0,47 → 0,47

### A.5 Custo médio ponderado
- saldo 10 a 2,5000 + entrada de 20 a 3,1000 → **2,9000**
- saldo 7 a 3,2000 + entrada de 12 a 3,5000 → **3,3895**
- saldo −3 (ou 0) + entrada de 12 a 3,5000 → **3,5000** (usa o custo da entrada)

### A.6 Margem, markup e preço sugerido
- custo 3,20 e preço 5,49 → lucro 2,29 · margem **41,71%** · markup **71,56%**
- custo 3,20 e margem desejada 30% → preço sugerido **4,58** (arredondado para cima; 4,57 daria margem de 29,98%)
- Margem agregada: produto A com receita 100 e lucro 50 (50%); produto B com receita 1.000 e lucro 100 (10%) → margem do período **13,64%** (150 ÷ 1.100), e não 30% (média simples).

### A.7 Etiqueta de balança (prefixo 2, PLU 123, produto a R$ 39,90/kg)
- 5 dígitos de código, `PRECO`: `2001230013776` → PLU 123, R$ 13,77 → quantidade **0,345** kg, total **13,77**
- 5 dígitos de código, `PESO`: `2001230003456` → 345 g → quantidade **0,345** kg, total **13,77** (0,345 × 39,90 = 13,7655)
- 4 dígitos de código, `PRECO`: `2012300013770` → PLU 123, R$ 13,77
- Por que não recalcular em `PRECO`: produto a R$ 29,99/kg com etiqueta de R$ 10,00 → quantidade 0,333 → 0,333 × 29,99 = 9,99. Vale o valor impresso na etiqueta.

### A.8 Inventário aplica a diferença
Sistema 10, contagem 8 (diferença −2). Depois da contagem sai 1 unidade (sistema 9; físico 7). Aplicar −2 → saldo **7** (correto). Igualar ao contado daria 8 (errado: a saída posterior sumiria).

### A.9 Curva ABC
Receitas 500 · 300 · 100 · 60 · 40 (total 1.000). % acumulado antes de cada item: 0 · 50 · 80 · 90 · 96 → classes **A · A · B · B · C**.

### A.10 Caixa
Abertura 100,00. Venda de 16,00: 10,00 no PIX e o restante em dinheiro — o cliente entrega 10,00 e recebe 4,00 de troco, então o pagamento em dinheiro grava `valor` = 6,00 e a venda grava `troco` = 4,00. Sangria de 50,00 → dinheiro esperado **56,00** (100 + 6 − 50). Se a venda for cancelada → **50,00**.

### A.11 Item com quantidade fracionada
0,345 kg × R$ 39,90 = 13,7655 → `valor_bruto` **13,77**.
