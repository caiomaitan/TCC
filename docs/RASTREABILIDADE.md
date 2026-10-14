# Rastreabilidade

Requisito → telas e classes → testes automatizados → casos do roteiro manual (`docs/ROTEIRO_TESTES.md`).
Atualizado a cada fase. "—" = ainda não implementado (a fase de entrega está entre parênteses).

| Requisito | Telas e classes | Testes automatizados | Roteiro manual |
|---|---|---|---|
| RF01 Cadastrar produto | Tela **Produto** (`ProdutoController`, `produto.fxml`): campos de 6.3, códigos de barras, estoque inicial, simulador; tela **Categorias** (`CategoriasController`, `categorias.fxml`); `ProdutoService.criar`/`avisos`, `ValidacaoProduto`, `CategoriaService`, `EstoqueService.movimentar` (estoque inicial), `ProdutoDAO`, `CategoriaDAO`, `UnidadeMedidaDAO`, `MovimentoEstoqueDAO`, `Gtin`, `Precificacao` | `ProdutoServiceIT` (estoque inicial 10 → saldo 10 e um `AJUSTE_ENTRADA`; DV errado; código repetido com o dono; validações; unidade inteira; avisos; PLU), `CategoriaServiceIT`, `EstoqueServiceIT`, `GtinTest` (A.1), `PrecificacaoTest` (A.6), `ValidacaoProdutoTest`, `FxmlTest` | CT-F2-01 a CT-F2-06, CT-F2-14 |
| RF02 Editar e excluir produto | Tela **Produto**: Salvar, Excluir (oferece Inativar), Inativar/Reativar, aba Histórico; `ProdutoService.alterar`/`excluir`/`alterarSituacao`/`historico`, `RegistroEmUsoException`; trigger `trg_produto_historico_preco` | `ProdutoServiceIT` (mudança de preço no histórico com o usuário; produto movimentado não exclui e oferece inativar; produto sem vínculo é excluído; alteração sem mudança não gera histórico) | CT-F2-07 a CT-F2-10 |
| RF03 Consultar produtos | Tela **Produtos** (`ProdutosController`, `produtos.fxml`): busca por nome sem acento, código ou código de barras, filtros de categoria e situação, duplo clique abre; `ProdutoService.pesquisar`, `ProdutoDAO.pesquisar`, `Textos.normalizarBusca` | `ProdutoServiceIT.buscarAcucarEncontraAcucar`, `ProdutoServiceIT.buscaFiltraPorCategoriaEMostraColunasDaLista`, `ProdutoServiceIT.produtoMovimentadoNaoExcluiEOfereceInativar` (filtro de situação), `TextosTest.buscarAcucarEncontraAcucarComAcento` | CT-F2-11, CT-F2-12 |
| RF04 Registrar vendas no PDV | Fase 4, parte 1 (caixa, pré-requisito da venda, 6.5): tela **Caixa** pelo menu PDV (`CaixaController`, `caixa.fxml`), `DialogosCaixa` (abertura, F8 sangria/suprimento, F12 fechamento às cegas), janela **Fechamento de caixa** R11 (`FechamentoCaixaController`, `fechamento-caixa.fxml`); `CaixaService` (`abrir`, `caixaAberto`, `registrarMovimento`, `fechar`, `relatorioFechamento`, `ultimoFechamento`, `travarAberta` para a venda), `CaixaDAO`, `CaixaSessao`, `CaixaResumo`, `CaixaMovimento`, `VendasPorForma`, `RelatorioFechamento`, `StatusCaixa`, `TipoCaixaMovimento`. Venda no PDV: — (Fase 4, parte 2) | `CaixaServiceIT` (abertura com R$ 100,00; um caixa por terminal; quem entra continua na sessão; sangria/suprimento com motivo e limite do esperado; liberação; fechamento às cegas A.10 com esperado 56,00 e 50,00 com venda cancelada; R11; nenhuma venda sem caixa aberto via `travarAberta`), `RelatorioFechamentoTest`, `CaixaValorTest`, `FxmlTest` | CT-F4-01 a CT-F4-10 |
| RF05 Selecionar produtos na venda | — (Fase 4) | — | — |
| RF06 Baixa automática de estoque | — (Fase 4). Garantido no banco: `trg_movimento_aplica_saldo` | `IntegridadeBancoIT` (cenários 3, 7 e 10 do script: movimento, venda e cancelamento) | CT-F0-05 |
| RF07 Criar inventário | — (Fase 5) | — | — |
| RF08 Atualizar estoque pelo inventário | — (Fase 5) | `IntegridadeBancoIT` (cenário 11 do script: aplica a diferença, A.8) | CT-F0-05 |
| RF09 Visualizar quantidade em estoque | Tela **Estoque** (`EstoqueController`, `estoque.fxml`): busca por código, código de barras ou nome; cabeçalho com saldo, mínimo, custo médio e valor em estoque; kardex com saldo após e origem, filtro de período; ajustes (`DialogosEstoque`: Entrada/Saída/Perda e "Ajustar saldo para…"); destaque abaixo do mínimo e negativo; alerta no Painel (`PrincipalController`); tela **Configurações** › Diagnóstico (`ConfiguracoesController`, `configuracoes.fxml`); `EstoqueService` (`pesquisar`, `saldo`, `kardex`, `ajustar`, `ajustarSaldoPara`, `contarAbaixoDoMinimo`, `diagnostico`), `EstoqueDAO`, `MovimentoEstoqueDAO.listar`, `SaldoEstoque`, `MovimentoKardex`, `TipoAjuste`, `MotivoPerda`, `Periodo` | `EstoqueServiceIT` (kardex com o saldo após cada movimento; ajuste sem motivo recusado; UN não aceita 1,5 e KG aceita 0,345; "ajustar saldo para 7" com saldo 10 gera saída de 3; perda com motivo da lista; saldo negativo; período com as duas datas incluídas; busca e filtro abaixo do mínimo; permissões; diagnóstico vazio e detectando diferença), `EstoqueMotivoTest`, `EstoqueModeloTest`, `FxmlTest` | CT-F3-01 a CT-F3-12 |
| RF10 Relatórios | — (Fase 6) | — | — |
| RF11 Lucro e margem | — (Fase 6). Base pronta: `Dinheiro.percentualDe`, `Dinheiro.dividir` | `DinheiroTest.percentuaisDoApendiceA6` (A.6), `DinheiroTest.divisaoPorZeroNaoTemValor` | — |
| RF12 Login e senha | Telas **Primeiro acesso** (`PrimeiroAcessoController`, `primeiro-acesso.fxml`), **Login** (`LoginController`, `login.fxml`), **Troca de senha** (`TrocaSenhaController`, `troca-senha.fxml`), Alterar senha / Log off / Sair no menu (`PrincipalController`, `DialogoSenha`); `AcessoService` (primeiro acesso, `entrar` com bloqueio, `trocarSenha`, `sair`), `Senhas` (BCrypt 12), `UsuarioDAO`, `ConfiguracaoDAO`, `App` (sequência 3.4) | `AcessoServiceIT` (primeiro acesso, login normalizado, mensagem genérica, bloqueio de 5 min, troca obrigatória, inativo, logout), `SenhasTest`, `FxmlTest` | CT-F1-01 a CT-F1-05, CT-F1-11, CT-F1-12 |
| RF13 Supervisor define rotinas | Tela **Usuários** (`UsuariosController`, `usuarios.fxml`): lista, permissões, supervisor, redefinir senha, desbloquear, inativar; `UsuarioService`, `Permissao`, `Usuario` | `UsuarioServiceIT` (criar, alterar permissões, último supervisor, inativar a si mesmo, redefinir, desbloquear), `PermissaoIT`, `SessaoTest.descreveAsMudancasDePermissao` | CT-F1-06 a CT-F1-10 |
| RNF01 Integridade dos dados | `Migracao` (V1/V2 via Flyway), `Transacao` (uma transação por operação, rollback em erro), `TradutorErros`, `MensagensBanco`; `EstoqueService.movimentar` (único caminho do estoque; saldo pelo trigger); auditoria de produto na mesma transação ; V3 (`fn_set_atualizado_em`, `fn_movimento_aplica_saldo`, `fn_produto_cria_estoque`): horários pelo `Clock` ; Fase 3: ajustes só por `EstoqueService.movimentar`, saldo travado (`EstoqueDAO.travarSaldo`) em "ajustar saldo para", diagnóstico de `vw_estoque_inconsistente` | `IntegridadeBancoIT` (15 cenários), `EstoqueServiceIT.diagnosticoVazioEDetectaSaldoDiferenteDosMovimentos`, `EstoqueServiceIT` (sinal, motivo, unidade inteira, enum `TipoMovimento` = `ck_me_tipo`), `ProdutoServiceIT` (rollback do cadastro quando o estoque inicial é inválido), `ProdutoServiceIT.alteracaoDePrecoComClockNoPassadoGravaEssaData`, `ProdutoServiceIT.updateSemAtualizadoEmUsaAHoraDoBanco`, `UsuarioServiceIT.horariosDoUsuarioVemDoClock`, `MigracaoIT` (V1 a V3), `MigracaoIT`, `TransacaoIT`, `TradutorErrosTest`; `IntegracaoBase` confere `vw_estoque_inconsistente` vazia depois de cada teste de integração | CT-F0-03, CT-F0-05, CT-F2-15, CT-F3-11 |
| RNF02/03 Interface simples, informação clara | `PrincipalController` + `principal.fxml` (menu lateral, barra de status), `ConexaoController` + `conexao.fxml`, `TratadorExcecoes`, `Dialogos`, `Mensagem` (erro abaixo dos campos), `DialogoFormulario`, `Formatos`, `css/app.css` | `FormatosTest`, `BancoTest.descreveFalhasDeConexaoSemJargao`, `FxmlTest` | CT-F0-02, CT-F0-04, CT-F1-01, CT-F1-04 |
| RNF04 Acesso restrito | `Sessao.exigir`/`exigirSupervisor` + `PermissaoNegadaException` nos services; menu por permissão (`ItemMenu`); `LiberacaoSupervisorDialogo` + `AcessoService.liberar`; senha só como hash BCrypt; `AuditoriaService` (login, falhas, logout, usuários); `sessao.exigir(PRODUTOS)` em todo método de `ProdutoService` e `CategoriaService`; `sessao.exigir(ESTOQUE)` na rotina de estoque e `exigirSupervisor` no diagnóstico; auditoria `ESTOQUE_AJUSTADO`; Fase 4: `sessao.exigir(PDV)` nas operações de caixa, `SANGRIA_SUPRIMENTO` ou `Liberacao` da mesma permissão na sangria/suprimento (`LiberacaoSupervisorDialogo` na tela Caixa), auditoria `CAIXA_ABERTO`, `SANGRIA`, `SUPRIMENTO`, `CAIXA_FECHADO` com quem liberou | `SessaoTest`, `ItemMenuTest`, `UsuarioServiceIT.soOSupervisorAdministraUsuarios`, `ProdutoServiceIT.operadorSemProdutosERecusadoNoService`, `EstoqueServiceIT.semPermissaoEstoqueOServiceRecusaEDiagnosticoSoSupervisor`, `AcessoServiceIT` (liberação, auditoria sem senha), `SenhasTest`, `CaixaServiceIT.semPermissaoDeSangriaPrecisaDeLiberacaoQueValeSoParaEla`, `CaixaServiceIT.semPermissaoPdvNaoOperaOCaixaMasVeASituacao` | CT-F1-07, CT-F1-08, CT-F1-11, CT-F2-13, CT-F3-10, CT-F4-05, CT-F4-06 |
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

## Infraestrutura da Fase 2

| Item da especificação | Classes | Testes |
|---|---|---|
| 4.1 Enums iguais ao banco | `TipoMovimento` | `EstoqueServiceIT.enumTipoMovimentoIgualAoCheckDoBanco` |
| 4.8 Números (escalas, preço sugerido para cima) | `ValidacaoProduto`, `Precificacao` | `ValidacaoProdutoTest`, `PrecificacaoTest` (A.6) |
| 4.9 Auditoria de produto | `ProdutoService` (`PRODUTO_CRIADO`, `PRODUTO_ALTERADO`, `PRODUTO_INATIVADO`, `PRODUTO_EXCLUIDO`) | `ProdutoServiceIT` (descrição de cada ação) |
| 5.3 Produto com vínculo não é excluído | `ProdutoDAO.vinculos`, `RegistroEmUsoException` | `ProdutoServiceIT.produtoMovimentadoNaoExcluiEOfereceInativar` |
| 5.3 Histórico de preço por trigger (`atualizado_por` sempre preenchido) | `ProdutoDAO.alterar`/`alterarSituacao`, `ProdutoDAO.historico` | `ProdutoServiceIT.mudancaDePrecoApareceNoHistoricoComUsuario` |
| 6.3 Produtos (validações, códigos, simulador, estoque inicial, excluir/inativar, busca, histórico, categorias) | `ProdutoService`, `ValidacaoProduto`, `CategoriaService`, `Gtin`, `Precificacao`, telas Produtos/Produto/Categorias | `ProdutoServiceIT`, `CategoriaServiceIT`, `GtinTest` (A.1), `PrecificacaoTest` (A.6), `ValidacaoProdutoTest` |
| 6.4 Único caminho para movimentar estoque; unidade inteira | `EstoqueService.movimentar`, `MovimentoEstoqueDAO`, `OrigemMovimento` | `EstoqueServiceIT`, `ValidacaoProdutoTest.sinalEOrigemDoMovimentoPorTipo` |
| 8.1 Teclado e janelas | `Janelas` (modal, Esc fecha), Ctrl+N, Enter no código de barras | roteiro CT-F2-02, CT-F2-12 |
| 3.5 / 5.3 Horários vêm da aplicação (V3) | `V3__horario_da_aplicacao.sql`; `UsuarioDAO`, `ProdutoDAO`, `MovimentoEstoqueDAO`, `AuditoriaDAO` recebem o horário do `Clock` | `ProdutoServiceIT.alteracaoDePrecoComClockNoPassadoGravaEssaData`, `ProdutoServiceIT.updateSemAtualizadoEmUsaAHoraDoBanco`, `UsuarioServiceIT.horariosDoUsuarioVemDoClock`; roteiro CT-F2-15 |

## Infraestrutura da Fase 3

| Item da especificação | Classes | Testes |
|---|---|---|
| 4.3 Uma transação por operação; trava do saldo | `EstoqueService.ajustar`/`ajustarSaldoPara`, `EstoqueDAO.travarSaldo` (`SELECT … FOR UPDATE`, só leitura) | `EstoqueServiceIT.ajustarSaldoPara7ComSaldo10GeraSaidaDe3` |
| 4.5 Nada demorado na FX thread | `ConfiguracoesController` (diagnóstico em `Task`), `PrincipalController.carregarAlertas` (contagem em `Task`) | roteiro CT-F3-09, CT-F3-11 |
| 4.9 Auditoria de ajuste | `EstoqueService` (`ESTOQUE_AJUSTADO`, com motivo e saldo antes/depois) | `EstoqueServiceIT` (descrições conferidas) |
| 5.3 Saldo só por movimento; movimento imutável; regra de ouro "estoque parte de `vw_estoque_atual`" | `EstoqueService.movimentar`, `EstoqueDAO` (lê `vw_estoque_atual`), `MovimentoEstoqueDAO` (só INSERT e SELECT) | `EstoqueServiceIT`, `IntegracaoBase` (`vw_estoque_inconsistente` vazia depois de cada teste) |
| 6.4 Consulta, kardex, período padrão de 30 dias | `EstoqueService.pesquisar`/`saldo`/`kardex`/`periodoPadrao`, `Periodo`, `MovimentoKardex.origem` | `EstoqueServiceIT.kardexMostraOSaldoAposCadaMovimento`, `kardexFiltraPeloPeriodoComAsDuasDatasIncluidas`, `pesquisaPorNomeCodigoECodigoDeBarrasEFiltraAbaixoDoMinimo`, `EstoqueModeloTest` |
| 6.4 Ajuste manual (Entrada/Saída/Perda, motivo, "Ajustar saldo para…") | `EstoqueService.ajustar`/`ajustarSaldoPara`/`comporMotivo`, `TipoAjuste`, `MotivoPerda`, `DialogosEstoque` | `EstoqueServiceIT` (aceite), `EstoqueMotivoTest` |
| 6.4 Unidade não fracionada só aceita inteiro | `ValidacaoProduto.quantidade` (via `movimentar` e `ajustarSaldoPara`) | `EstoqueServiceIT.unidadeUnNaoAceitaUmEMeioEKgAceita` |
| 6.4 Alertas (abaixo do mínimo, negativo) | `EstoqueService.contarAbaixoDoMinimo`, `EstoqueController` (estilos `abaixo-minimo` e `negativo`), link no Painel | `EstoqueServiceIT.pesquisaPorNomeCodigoECodigoDeBarrasEFiltraAbaixoDoMinimo`, `saidaMaiorQueOSaldoDeixaNegativoEAjustarParaZeroCorrige`; roteiro CT-F3-08, CT-F3-09 |
| 6.4 Diagnóstico (supervisor) | `EstoqueService.diagnostico`, `EstoqueDAO.inconsistencias`, `InconsistenciaEstoque`, tela Configurações | `EstoqueServiceIT.diagnosticoVazioEDetectaSaldoDiferenteDosMovimentos`; roteiro CT-F3-11 |
| 8.1 Teclado | F4/F5 na tela Estoque; Enter confirma e Esc cancela nos diálogos (`DialogoFormulario`) | roteiro CT-F3-12 |

## Infraestrutura da Fase 4 — parte 1 (caixa)

| Item da especificação | Classes | Testes |
|---|---|---|
| 4.1 Enums iguais ao banco | `StatusCaixa`, `TipoCaixaMovimento` | `CaixaServiceIT.enumsDoCaixaIguaisAosChecksDoBanco` |
| 4.3 Uma transação por operação; trava da sessão de caixa | `CaixaService` (abrir, sangria/suprimento, fechar), `CaixaDAO.travarAberta` (`SELECT … FOR UPDATE OF s`); `CaixaService.travarAberta(con, sessao)` para a transação da venda (parte 2) | `CaixaServiceIT.semCaixaAbertoNaoHaSangriaFechamentoNemVenda` |
| 3.5 / 4.7 Horários pelo `Clock` | `CaixaDAO.abrir`/`inserirMovimento`/`fechar` recebem `aberto_em`, `criado_em` e `fechado_em` | `CaixaServiceIT.fechamentoAsCegasDoApendiceA10ComRelatorio` (horários conferidos) |
| 4.8 Números (dinheiro com 2 casas) | `CaixaService.valor` | `CaixaValorTest` |
| 4.9 Auditoria do caixa | `CaixaService` (`CAIXA_ABERTO`, `SANGRIA`, `SUPRIMENTO` com "liberado por…", `CAIXA_FECHADO` com esperado, contado e diferença) | `CaixaServiceIT` (descrição de cada ação) |
| 5.3 Uma sessão aberta por terminal (`ux_caixa_aberto_por_terminal`, 23505 com mensagem clara) | `CaixaService.abrir` (confere antes), `MensagensBanco` | `CaixaServiceIT.segundoCaixaNoMesmoTerminalRecusadoEOutroTerminalAbre` |
| 5.4 / regra de ouro: caixa parte de `vw_caixa_resumo` | `CaixaDAO.resumo`; esperado do fechamento e limite da sangria | `CaixaServiceIT.fechamentoAsCegasDoApendiceA10ComRelatorio`, `vendaCanceladaSaiDoEsperadoEDescontosEFormasEntramNoRelatorio` (A.10) |
| 6.1 Sair avisa se há caixa aberto | `PrincipalController.confirmarSaida` (botão Sair e X da janela, via `App`) | roteiro CT-F4-09 |
| 6.2 Liberação de supervisor | `LiberacaoSupervisorDialogo` na sangria/suprimento; `Liberacao.permissao()` conferida no service | `CaixaServiceIT.semPermissaoDeSangriaPrecisaDeLiberacaoQueValeSoParaEla`; roteiro CT-F4-06 |
| 6.5 Caixa (abertura, sangria, suprimento, fechamento às cegas, R11) | `CaixaService`, `CaixaDAO`, `CaixaController`, `DialogosCaixa`, `FechamentoCaixaController`, `RelatorioFechamento` | `CaixaServiceIT`, `RelatorioFechamentoTest`; roteiro CT-F4-01 a CT-F4-08 |
| 7.3 R11 Fechamento de caixa | `CaixaService.fechar`/`relatorioFechamento`/`ultimoFechamento`, `CaixaDAO.vendasPorForma`/`descontos`/`listarMovimentos`, janela Fechamento de caixa | `CaixaServiceIT` (R11 conferido), `RelatorioFechamentoTest`; roteiro CT-F4-07, CT-F4-08 |
| 7.5 Barra de status com a situação do caixa | `PrincipalController.atualizarCaixa` | roteiro CT-F4-01, CT-F4-07 |
| 8.1 Teclado | F8 e F12 na tela Caixa; Enter confirma e Esc cancela nos diálogos; Enter/Esc fecham o R11 | roteiro CT-F4-10 |
