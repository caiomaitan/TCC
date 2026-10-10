# Progresso

## Fase atual

Fase 1 — Acesso e usuários: implementada em 2026-10-06. `mvn -q verify` passa: 98 testes unitários e 33 de
integração. Falta executar o roteiro manual CT-F1-01 a CT-F1-12 (telas).

Próxima: Fase 2 — Produtos.

## Fases concluídas  (data · resumo · testes)

| Data | Fase | Resumo | Testes |
|---|---|---|---|
| 2026-10-06 | 0 — Fundação | Projeto Maven (Java 21, JavaFX 21.0.12); `Launcher`/`App` com inicialização em `Task`; `AppConfig` (`~/.varesync/config.properties`) e tela **Conexão com o banco**; `Banco` (HikariCP); `Migracao` (Flyway com as V1/V2 do repositório); `AppContext`; `Transacao`; `TradutorErros` + `MensagensBanco`; `NegocioException`/`ErroInesperadoException`; tratador global de exceções; `Dinheiro`, `Formatos`, `Textos`; `logback.xml`; janela principal com menu lateral vazio e barra de status; README. | Unitários: 78 (`DinheiroTest`, `TextosTest`, `FormatosTest`, `AppConfigTest`, `BancoTest`, `TradutorErrosTest`, `FxmlTest`). Integração: 10, no banco `varesync_test` (`IntegridadeBancoIT` 1, `MigracaoIT` 2, `TransacaoIT` 7), todos verdes. |
| 2026-10-06 | 1 — Acesso e usuários | Assistente de primeiro acesso (nome da loja + supervisor, sem senha padrão); login com mensagem genérica, bloqueio de 5 min após 5 falhas seguidas e auditoria de toda tentativa; troca de senha obrigatória; `Sessao` (`exigir`/`exigirSupervisor`); menu lateral por permissão (`ItemMenu`) com Alterar senha, Log off e Sair; tela **Usuários** (criar, editar nome/supervisor/permissões, inativar/reativar, redefinir senha, desbloquear; ninguém inativa a si mesmo; sempre um supervisor ativo); `LiberacaoSupervisorDialogo` + `AcessoService.liberar`; `AuditoriaService` (LOGIN, LOGIN_FALHOU, LOGOUT, USUARIO_CRIADO, USUARIO_ALTERADO, PERMISSOES_ALTERADAS, SENHA_REDEFINIDA, USUARIO_DESBLOQUEADO); `UsuarioDAO`, `AuditoriaDAO`, `ConfiguracaoDAO`; enums `Permissao` e `AcaoAuditoria`; BCrypt custo 12. Sem migration nova. | Unitários: 98 (+20: `SenhasTest` 7, `SessaoTest` 4, `ItemMenuTest` 5, `FxmlTest` +4 telas). Integração: 33 (+23: `AcessoServiceIT` 13, `UsuarioServiceIT` 9, `PermissaoIT` 1), todos verdes. |

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
| 2026-10-06 | Fase 1 sem migration nova. | `usuario`, `permissao`, `usuario_permissao`, `auditoria` e `configuracao` (V1/V2) já cobrem tudo. |
| 2026-10-06 | BCrypt: `at.favre.lib:bcrypt` 0.10.2 (versão mais recente), custo 12 em produção. Os testes de integração usam custo 4; `SenhasTest` confere que o padrão é 12. | Custo 12 leva ~0,3 s por hash; com dezenas de hashes a suíte ficaria lenta sem testar nada a mais. O custo é parâmetro do construtor de `Senhas`, montado no `AppContext`. |
| 2026-10-06 | Senha: 6 a 50 caracteres e no máximo 71 bytes em UTF-8; comparação com o login sem diferenciar maiúsculas; a troca pela própria pessoa exige a senha atual e uma nova diferente da atual. | 6.1 define só o mínimo e "diferente do login". O BCrypt ignora o que passa de 72 bytes, por isso o limite. |
| 2026-10-06 | Bloqueio: na 5ª falha seguida grava `bloqueado_ate = agora + 5 min` e zera `tentativas_falhas`; durante o bloqueio a senha nem é conferida e a tentativa não conta. Depois de 5 min o usuário tem de novo 5 tentativas. Login certo, redefinição de senha e Desbloquear zeram tentativas e bloqueio. | 6.1 não diz o que acontece depois do bloqueio. Sem zerar, a 6ª falha bloquearia de novo na primeira tentativa. |
| 2026-10-06 | Mensagens de login: login inexistente ou senha errada → "Usuário ou senha inválidos."; bloqueado → "Usuário bloqueado por excesso de tentativas. Tente novamente às HH:mm."; inativo → "Este usuário está inativo. Procure o supervisor." só quando a senha está certa (com senha errada, a mensagem genérica). Login inexistente gasta o mesmo tempo de BCrypt que um login real. | Mensagem genérica (6.1) sem revelar quais logins existem. O horário de liberação é pedido na 6.1. |
| 2026-10-06 | Toda tentativa de login vira `LOGIN_FALHOU` com o motivo; com login inexistente, `usuario_id` fica nulo e o texto digitado **não** é gravado. | Quem digita a senha no campo Usuário a deixaria gravada na auditoria. |
| 2026-10-06 | A falha de login é gravada e a exceção só sai depois do commit (o service devolve o resultado da transação e lança fora dela). | A `Transacao` faz rollback em qualquer exceção; lançar dentro dela desfaria a contagem de tentativas e a auditoria. |
| 2026-10-06 | `AuditoriaService.registrar(con, sessao, acao, entidade, entidadeId, descricao)` e uma sobrecarga com `Long usuarioId` (login sem sessão). O terminal vem da sessão ou da configuração. | A assinatura da 4.9 não diz de onde vêm o usuário e o terminal; a `Sessao` é a fonte natural. |
| 2026-10-06 | A `Sessao` é imutável e passada como parâmetro a cada método de service (`usuarioService.listar(sessao)`); a `App` guarda a sessão atual e a entrega aos controllers pelo `AppContext`. | Sem estado global nem singleton (4.2); o service fica testável com qualquer sessão. |
| 2026-10-06 | Supervisor não pode tirar o próprio perfil de supervisor (além de não poder se inativar). Supervisores ativos são travados (`FOR UPDATE`) antes de conferir "ao menos um supervisor ativo". O primeiro acesso trava a tabela `usuario` antes de conferir que está vazia. | Mesmo risco de "inativar a si mesmo" (6.1). As travas evitam que dois computadores, ao mesmo tempo, deixem o sistema sem supervisor ou criem dois supervisores iniciais. |
| 2026-10-06 | As permissões marcadas continuam gravadas quando o usuário vira supervisor (a tela só as desabilita). | Supervisor tem todas de qualquer jeito (`Sessao`); se ele deixar de ser supervisor, volta às rotinas que tinha. |
| 2026-10-06 | Menu: Painel para todos; PDV, Produtos, Estoque, Inventário, Compras, Fornecedores e Relatórios pela permissão de mesmo nome; Usuários e Configurações só supervisor. Rotinas de fases futuras aparecem no menu com o aviso "Esta rotina ainda está em construção". | 8.1 e 6.2. Assim o critério "operador sem PRODUTOS não vê o menu" já pode ser conferido. |
| 2026-10-06 | Liberação de supervisor: `AcessoService.liberar(login, senha, permissao)` devolve `Liberacao` (quem liberou); senha errada conta para o bloqueio, como no login. Quem chama grava na auditoria da própria ação `liberacao.descricao()`. O diálogo (`LiberacaoSupervisorDialogo`) recebe o validador como função. | 6.2. Sem a contagem, o diálogo permitiria tentar senhas do supervisor sem limite. |
| 2026-10-06 | Login, primeiro acesso e troca de senha obrigatória rodam em `Task`; os diálogos de senha (Alterar senha, Redefinir senha) e as ações da tela Usuários rodam na FX thread. | BCrypt + banco levam ~0,3 s: aceitável num diálogo modal; nas telas de entrada o `Task` mantém a janela respondendo. |
| 2026-10-06 | Sair pede confirmação; fechar a janela também registra `LOGOUT`. | 6.1 manda avisar se há caixa aberto; o caixa entra na Fase 4 e o aviso será acrescentado lá. |
| 2026-10-06 | `Migracao` registra a versão atual pelo histórico (`flyway.info().current()`). | Pendência da Fase 0 ("versão do banco: null" quando não há migration pendente). |

## Pendências e riscos

- Roteiro CT-F0-07 (PostgreSQL parado) e CT-F0-08 (Esc na tela de conexão) ainda não foram executados.
- Roteiro CT-F1-01 a CT-F1-12 (telas da Fase 1) ainda não foi executado. Os testes automáticos cobrem os services;
  das telas, foi conferido só que o assistente de primeiro acesso abre sem erro (pelo log).
- "O service recusa a chamada" para `PRODUTOS`: ainda não existe `ProdutoService` (Fase 2). Hoje a recusa está
  coberta por `SessaoTest` (`exigir(PRODUTOS)`) e por `UsuarioServiceIT` (operador chamando o service de usuários). Na
  Fase 2, todo método de `ProdutoService` começa com `sessao.exigir(Permissao.PRODUTOS)`, com teste de integração.
- `LiberacaoSupervisorDialogo` ainda não é usado por nenhuma tela: os primeiros usos (sangria, cancelar venda,
  desconto) são da Fase 4. A regra (`AcessoService.liberar`) está coberta por `AcessoServiceIT`.
- Sair ainda não avisa se há caixa aberto no terminal (o caixa entra na Fase 4).
- Uma sessão aberta não é revalidada: usuário inativado ou com permissões retiradas continua com a sessão atual até o
  log off. Avaliar na Fase 9 se vale reler permissões a cada abertura de rotina.
- Aviso antigo do compilador (Fase 0): `Banco.java:36`, recurso `con` não usado no corpo do `try`. Não afeta o
  funcionamento.

## Métricas

| Métrica (9.4) | Meta | Medido |
|---|---|---|
| Abertura do sistema até a tela de login | < 5 s | Fase 1 (pelo log, banco já migrado, 2026-10-06): 0,7 s entre "Iniciando o VareSync" e a primeira tela de acesso (aqui, o assistente de primeiro acesso, que abre na mesma etapa do login). Conferir com cronômetro no CT-F1-03 |
| Registrar um item no PDV com 5 mil produtos | < 300 ms | Fase 4 |
| Relatório de 60 dias | < 3 s | Fase 6 |
| Painel | < 2 s | Fase 6 |
| Venda de 3 itens em dinheiro só com teclado | sim | Fase 4 |
| Defeitos encontrados na fase | — | Fase 0: 1 (FXML sem `fx:controller`, a `controllerFactory` não era chamada; achado ao rodar `mvn javafx:run`, corrigido e coberto por `FxmlTest`). Fase 1: 0 no código de produção (1 asserção errada no próprio teste, corrigida) |
