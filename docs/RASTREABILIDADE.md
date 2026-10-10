# Rastreabilidade

Requisito → telas e classes → testes automatizados → casos do roteiro manual (`docs/ROTEIRO_TESTES.md`).
Atualizado a cada fase. "—" = ainda não implementado (a fase de entrega está entre parênteses).

| Requisito | Telas e classes | Testes automatizados | Roteiro manual |
|---|---|---|---|
| RF01 Cadastrar produto | — (Fase 2) | — | — |
| RF02 Editar e excluir produto | — (Fase 2) | — | — |
| RF03 Consultar produtos | — (Fase 2). Base pronta: `Textos.normalizarBusca` (mesma regra de `produto.nome_busca`) | `TextosTest.normalizaBuscaComoONomeBuscaDoBanco`, `TextosTest.buscarAcucarEncontraAcucarComAcento` | — |
| RF04 Registrar vendas no PDV | — (Fase 4) | — | — |
| RF05 Selecionar produtos na venda | — (Fase 4) | — | — |
| RF06 Baixa automática de estoque | — (Fase 4). Garantido no banco: `trg_movimento_aplica_saldo` | `IntegridadeBancoIT` (cenários 3, 7 e 10 do script: movimento, venda e cancelamento) | CT-F0-05 |
| RF07 Criar inventário | — (Fase 5) | — | — |
| RF08 Atualizar estoque pelo inventário | — (Fase 5) | `IntegridadeBancoIT` (cenário 11 do script: aplica a diferença, A.8) | CT-F0-05 |
| RF09 Visualizar quantidade em estoque | — (Fase 3) | — | — |
| RF10 Relatórios | — (Fase 6) | — | — |
| RF11 Lucro e margem | — (Fase 6). Base pronta: `Dinheiro.percentualDe`, `Dinheiro.dividir` | `DinheiroTest.percentuaisDoApendiceA6` (A.6), `DinheiroTest.divisaoPorZeroNaoTemValor` | — |
| RF12 Login e senha | Telas **Primeiro acesso** (`PrimeiroAcessoController`, `primeiro-acesso.fxml`), **Login** (`LoginController`, `login.fxml`), **Troca de senha** (`TrocaSenhaController`, `troca-senha.fxml`), Alterar senha / Log off / Sair no menu (`PrincipalController`, `DialogoSenha`); `AcessoService` (primeiro acesso, `entrar` com bloqueio, `trocarSenha`, `sair`), `Senhas` (BCrypt 12), `UsuarioDAO`, `ConfiguracaoDAO`, `App` (sequência 3.4) | `AcessoServiceIT` (primeiro acesso, login normalizado, mensagem genérica, bloqueio de 5 min, troca obrigatória, inativo, logout), `SenhasTest`, `FxmlTest` | CT-F1-01 a CT-F1-05, CT-F1-11, CT-F1-12 |
| RF13 Supervisor define rotinas | Tela **Usuários** (`UsuariosController`, `usuarios.fxml`): lista, permissões, supervisor, redefinir senha, desbloquear, inativar; `UsuarioService`, `Permissao`, `Usuario` | `UsuarioServiceIT` (criar, alterar permissões, último supervisor, inativar a si mesmo, redefinir, desbloquear), `PermissaoIT`, `SessaoTest.descreveAsMudancasDePermissao` | CT-F1-06 a CT-F1-10 |
| RNF01 Integridade dos dados | `Migracao` (V1/V2 via Flyway), `Transacao` (uma transação por operação, rollback em erro), `TradutorErros`, `MensagensBanco` | `IntegridadeBancoIT` (15 cenários), `MigracaoIT`, `TransacaoIT`, `TradutorErrosTest`; `IntegracaoBase` confere `vw_estoque_inconsistente` vazia depois de cada teste de integração | CT-F0-03, CT-F0-05 |
| RNF02/03 Interface simples, informação clara | `PrincipalController` + `principal.fxml` (menu lateral, barra de status), `ConexaoController` + `conexao.fxml`, `TratadorExcecoes`, `Dialogos`, `Mensagem` (erro abaixo dos campos), `DialogoFormulario`, `Formatos`, `css/app.css` | `FormatosTest`, `BancoTest.descreveFalhasDeConexaoSemJargao`, `FxmlTest` | CT-F0-02, CT-F0-04, CT-F1-01, CT-F1-04 |
| RNF04 Acesso restrito | `Sessao.exigir`/`exigirSupervisor` + `PermissaoNegadaException` nos services; menu por permissão (`ItemMenu`); `LiberacaoSupervisorDialogo` + `AcessoService.liberar`; senha só como hash BCrypt; `AuditoriaService` (login, falhas, logout, usuários) | `SessaoTest`, `ItemMenuTest`, `UsuarioServiceIT.soOSupervisorAdministraUsuarios`, `AcessoServiceIT` (liberação, auditoria sem senha), `SenhasTest` | CT-F1-07, CT-F1-08, CT-F1-11 |
| RNF05 Tempo de resposta | `App` (conexão e migrations em `Task`); login, primeiro acesso e troca de senha em `Task` | — | CT-F0-04, CT-F1-03 (tempo de abertura até o login) |
| RNF06 Modular | Pacotes `config`, `model`, `dao`, `service`, `report`, `controller`, `ui`, `util`; `AppContext` (composition root) | — | — |
| RNF07 Desktop | `Launcher`, `App` (JavaFX 21) | — | CT-F0-04 |
| RNF08 Banco relacional | `Banco` (HikariCP + PostgreSQL), `Migracao` (Flyway), `AppConfig` | `MigracaoIT`, `BancoTest`, `AppConfigTest` | CT-F0-02, CT-F0-03 |

## Infraestrutura da Fase 0

| Item da especificação | Classes | Testes |
|---|---|---|
| 3.3 Configuração local | `AppConfig` | `AppConfigTest` |
| 3.3 Tela de conexão | `ConexaoController`, `fxml/conexao.fxml`, `Banco.testarConexao` | `BancoTest`; roteiro CT-F0-02 |
| 3.3 Logs | `logback.xml` (`~/.varesync/logs`, diário, 30 dias) | roteiro CT-F0-06 |
| 3.4 Sequência de inicialização | `Launcher`, `App`, `AppContext.iniciar` | `MigracaoIT`; roteiro CT-F0-03, CT-F0-04 |
| 4.3 Transações | `Transacao` | `TransacaoIT` |
| 4.4 Erros | `TradutorErros`, `MensagensBanco`, `NegocioException`, `ErroInesperadoException`, `TratadorExcecoes` | `TradutorErrosTest`, `TransacaoIT` |
| 4.8 Números e formatação | `Dinheiro`, `Formatos` | `DinheiroTest` (inclui A.6 e A.11), `FormatosTest` |
| 5.5 Script de integridade | — | `IntegridadeBancoIT` |
| 6.3 Normalização de nome e busca | `Textos` | `TextosTest` |

## Infraestrutura da Fase 1

| Item da especificação | Classes | Testes |
|---|---|---|
| 3.4 Sequência de inicialização (primeiro acesso → login → troca de senha → principal) | `App` (implementa `Navegacao`), `AppContext.criarController`, `AcessoService.precisaPrimeiroAcesso` | `AcessoServiceIT.primeiroAcessoCriaOSupervisorEGravaONomeDaLoja`; roteiro CT-F1-01 a CT-F1-05 |
| 4.1 Enums iguais ao banco | `Permissao`, `AcaoAuditoria` | `PermissaoIT` |
| 4.6 Sessão e permissões | `Sessao`, `PermissaoNegadaException`, `ItemMenu` | `SessaoTest`, `ItemMenuTest` |
| 4.9 Auditoria | `AuditoriaService`, `AuditoriaDAO` | `AcessoServiceIT`, `UsuarioServiceIT` (descrição de cada ação) |
| 6.1 Primeiro acesso, login e usuários | `AcessoService`, `UsuarioService`, `Senhas`, `ValidacaoUsuario`, `UsuarioDAO`, `ConfiguracaoDAO` | `AcessoServiceIT`, `UsuarioServiceIT`, `SenhasTest` |
| 6.2 Liberação de supervisor | `LiberacaoSupervisorDialogo`, `Liberacao`, `AcessoService.liberar` | `AcessoServiceIT.liberacaoPorSupervisorOuPorQuemTemAPermissao`, `AcessoServiceIT.senhaErradaNaLiberacaoContaParaOBloqueio` |
