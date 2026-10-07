# Progresso

## Fase atual

Fase 0 — Fundação: concluída em 2026-10-06. `mvn -q verify` passa: 78 testes unitários e 10 de integração.
Falta a conferência visual da janela principal com o banco real (roteiro CT-F0-03 e CT-F0-04).

Próxima: Fase 1 — Acesso e usuários.

## Fases concluídas  (data · resumo · testes)

| Data | Fase | Resumo | Testes |
|---|---|---|---|
| 2026-10-06 | 0 — Fundação | Projeto Maven (Java 21, JavaFX 21.0.12); `Launcher`/`App` com inicialização em `Task`; `AppConfig` (`~/.varesync/config.properties`) e tela **Conexão com o banco**; `Banco` (HikariCP); `Migracao` (Flyway com as V1/V2 do repositório); `AppContext`; `Transacao`; `TradutorErros` + `MensagensBanco`; `NegocioException`/`ErroInesperadoException`; tratador global de exceções; `Dinheiro`, `Formatos`, `Textos`; `logback.xml`; janela principal com menu lateral vazio e barra de status; README. | Unitários: 78 (`DinheiroTest`, `TextosTest`, `FormatosTest`, `AppConfigTest`, `BancoTest`, `TradutorErrosTest`, `FxmlTest`). Integração: 10, no banco `varesync_test` (`IntegridadeBancoIT` 1, `MigracaoIT` 2, `TransacaoIT` 7), todos verdes. |

## Decisões  (data · decisão · motivo)

| Data | Decisão | Motivo |
|---|---|---|
| 2026-10-06 | Versões: JavaFX 21.0.12, PostgreSQL JDBC 42.7.13, HikariCP 7.1.0, Flyway 13.9.0 (+ `flyway-database-postgresql`), SLF4J 2.0.20, Logback 1.6.5, JUnit 6.1.3, AssertJ 3.27.7. | Versão estável mais recente de cada uma no Maven Central, compatível com JDK 21 (3.1). JavaFX fica na linha 21 porque o 24 ou mais novo exige JDK mais novo. |
| 2026-10-06 | BCrypt, JasperReports, AtlantaFX e Ikonli ainda não estão no `pom.xml`. | Não adiantar fases: BCrypt entra na Fase 1 e JasperReports na 4/6. Tema e ícones são opcionais; por ora o visual é CSS próprio (`css/app.css`). |
| 2026-10-06 | `Transacao`, `TradutorErros`, `MensagensBanco`, `NegocioException` e `ErroInesperadoException` ficam no pacote `service`. | A seção 3.2 não define o pacote. São usadas pelos services; assim o `dao` não depende de `service`. |
| 2026-10-06 | Erro sem tradução vira `ErroInesperadoException` ("Erro inesperado. Os detalhes foram registrados no log."), com a exceção original como causa. | 4.4 pede a mensagem genérica sem perder a exceção original. Uma classe própria separa do `NegocioException` o que deve ir para o log com stack trace. |
| 2026-10-06 | `Transacao`: se o rollback falhar, essa falha entra como `addSuppressed` da exceção original antes da tradução. | 4.3: "falha no rollback entra como addSuppressed". No código de exemplo, a falha do rollback escaparia e esconderia o erro original. |
| 2026-10-06 | 23503 (chave estrangeira) sempre mostra "Este registro está em uso e não pode ser excluído". | Literal da 4.4. O texto do erro do PostgreSQL depende do idioma do servidor, então não serve para distinguir INSERT de DELETE. Os services validam antes (5.3). |
| 2026-10-06 | `Dinheiro.ler`: um único ponto sem vírgula é separador decimal (`1.5` = 1,5; `1234.56` = 1234,56). Ponto como milhar só com vírgula decimal (`1.234,56`) ou com dois ou mais grupos (`1.234.567`). | 4.8 pede aceitar `1234.56`. `1.234` sozinho é ambíguo; vale decimal, que também atende quantidade em kg. |
| 2026-10-06 | `Textos.normalizarBusca` usa a mesma tabela de acentos do `translate(...)` de `produto.nome_busca` (V1), e não o `Normalizer` do Java. | 6.3: o termo tem de ser normalizado "pela mesma regra" do banco. Assim o resultado é idêntico ao da coluna gerada. |
| 2026-10-06 | Moeda formatada com espaço comum (`R$ 1.234,56`); negativo como `-R$ 1,00`. | O `NumberFormat` do JDK usa espaço inseparável (U+00A0), que atrapalha comparação e CSV. |
| 2026-10-06 | `IntegridadeBancoIT` executa o bloco `DO $$ … END $$;` do script dentro de uma transação JDBC terminada em rollback, em vez de enviar o `BEGIN`/`ROLLBACK` do arquivo. | Mesmo efeito (nada fica gravado), sem misturar o controle de transação do script com o do driver. O arquivo não é alterado. O teste confere "OK 1" a "OK 15" nas mensagens NOTICE. |
| 2026-10-06 | Testes de integração com sufixo `IT` (Failsafe, fase `verify`); unitários com `Test` (Surefire). | `mvn test` roda sem banco; `mvn verify` roda tudo. |
| 2026-10-06 | Na inicialização, a conexão é testada antes de abrir o pool (`Banco.testarConexao`). Falha de conexão abre a tela de conexão com o motivo (senha inválida, banco inexistente, servidor fora do ar). Falha de migration também volta para a tela de conexão, com mensagem genérica e detalhes no log. | 3.3/3.4. Conexão e migration rodam em `Task`, fora da FX Application Thread. |
| 2026-10-06 | A tela de conexão grava a senha em `~/.varesync/config.properties`, como define a 3.3. | O arquivo fica na pasta do usuário, fora do repositório; o `.gitignore` também ignora `config.properties`. `AppConfig.toString()` nunca inclui a senha (vai para o log). |

## Pendências e riscos

- `~/.varesync/config.properties` ainda está com `db.senha=` vazio. Na próxima execução de `mvn javafx:run` abre a
  tela de conexão: informe a senha e salve. Isso aplica V1 e V2 no banco `varesync` (roteiro CT-F0-03).
- A conferência visual da tela de conexão e da janela principal é manual (CT-F0-02 a CT-F0-04). Pelo log, a tela de
  conexão carrega sem erro.

## Métricas

| Métrica (9.4) | Meta | Medido |
|---|---|---|
| Abertura do sistema até a tela de login | < 5 s | a medir (o login entra na Fase 1) |
| Registrar um item no PDV com 5 mil produtos | < 300 ms | Fase 4 |
| Relatório de 60 dias | < 3 s | Fase 6 |
| Painel | < 2 s | Fase 6 |
| Venda de 3 itens em dinheiro só com teclado | sim | Fase 4 |
| Defeitos encontrados na fase | — | Fase 0: 1 (FXML sem `fx:controller`, a `controllerFactory` não era chamada; achado ao rodar `mvn javafx:run`, corrigido e coberto por `FxmlTest`) |
