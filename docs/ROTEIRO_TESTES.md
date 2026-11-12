# Roteiro de testes manuais

Um caso por caso de uso do TCC (Quadros 11 a 20) e por funcionalidade ampliada. Atualizado a cada fase; alimenta o
Quadro 23 do TCC. Preencha "Resultado obtido" com OK / Falhou (e o que aconteceu) e a data.

## Fase 0 — Fundação

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F0-01 | JDK 21, Maven e PostgreSQL instalados; bancos criados (README); `VARESYNC_DB_SENHA` definida; terminal novo | 1. Na pasta do projeto, rodar `mvn -q verify` | Termina sem erros. `target/failsafe-reports` mostra `IntegridadeBancoIT`, `MigracaoIT` e `TransacaoIT` sem falhas | OK em 2026-10-06: 78 unitários e 10 de integração, sem falhas |
| CT-F0-02 | Sem `~/.varesync/config.properties` (primeira execução) | 1. Rodar `mvn javafx:run` | `~/.varesync/config.properties` é criado com `db.url`, `db.usuario=varesync`, `db.senha=` (vazio) e `terminal.id=CAIXA-01`. A tela **Conexão com o banco** abre com o motivo ("Informe a senha do banco.") e o foco no campo Senha | OK em 2026-10-06 (conferido pelo usuário) |
| CT-F0-03 | Tela de conexão aberta; banco `varesync` vazio | 1. Digitar uma senha errada e clicar **Testar** 2. Digitar a senha certa e clicar **Testar** 3. Pressionar **Enter** (Salvar) | 1. Mensagem em vermelho "Usuário ou senha do banco inválidos." 2. "Conexão bem-sucedida." em verde 3. A configuração é gravada, aparece "Atualizando o banco de dados…" e depois a janela principal. No DBeaver, o banco `varesync` tem 24 tabelas mais a `flyway_schema_history` (versões 1 e 2) | OK em 2026-10-06 (conferido pelo usuário) |
| CT-F0-04 | Configuração já salva e PostgreSQL rodando | 1. Rodar `mvn javafx:run` e cronometrar até a janela principal | A janela principal abre direto, sem a tela de conexão (menos de 5 s depois que a janela aparece). Ela tem: menu lateral escuro com "VareSync" e sem itens; área central "Bem-vindo ao VareSync"; barra de status com "Usuário: —", "Terminal: CAIXA-01", "Caixa: —", "Banco: conectado (localhost:5432/varesync)" e a data/hora atual (dd/MM/yyyy HH:mm) avançando. Não é possível reduzir a janela abaixo de 1280×720 | OK em 2026-10-06 (conferido pelo usuário) |
| CT-F0-05 | Banco `varesync` migrado | 1. No DBeaver, abrir `scripts/teste_integridade_banco.sql` na conexão `varesync` 2. Executar como script (Alt+X) | A aba Output mostra "OK 1" a "OK 15" e "Todos os 15 cenários passaram."; nenhum dado fica gravado (a tabela `usuario` continua vazia) | |
| CT-F0-06 | Sistema executado ao menos uma vez | 1. Abrir `~/.varesync/logs/varesync.log` | O log registra início, configuração (sem a senha), migrations aplicadas e encerramento. Nenhuma senha aparece no arquivo | OK em 2026-10-06: log com início, configuração sem senha, "Migrations aplicadas: 2" na primeira conexão e 0 na seguinte, e encerramento. A senha não aparece fora de trechos fixos do log (URL e nome do driver) |
| CT-F0-07 | Configuração salva; serviço do PostgreSQL parado (services.msc → postgresql-x64-16 → Parar) | 1. Rodar `mvn javafx:run` 2. Iniciar o serviço de novo 3. Clicar **Salvar** | 1. A tela de conexão abre com "Não foi possível conectar ao servidor do banco. Verifique se o PostgreSQL está em execução…" 3. A janela principal abre | |
| CT-F0-08 | Tela de conexão aberta | 1. Pressionar **Esc** | O sistema fecha | |

## Fase 1 — Acesso e usuários

Casos de uso do TCC: Autenticar usuário e Gerenciar usuários e permissões. Execute na ordem: cada caso parte do estado
deixado pelo anterior. Para recomeçar do zero (banco `varesync` sem usuários), no DBeaver:
`DELETE FROM auditoria; DELETE FROM usuario_permissao; DELETE FROM usuario;` (só enquanto não houver produtos e vendas).

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F1-01 | Banco `varesync` sem usuários | 1. `mvn javafx:run` 2. Em **Bem-vindo ao VareSync**, preencher Nome da loja "Mercado Bom Preço", Seu nome "Ana Admin", Login "Admin", Senha "12345", Confirmação "12345" e pressionar **Enter** 3. Trocar a senha para "segredo1" e a confirmação para "segredo2"; Enter 4. Confirmação "segredo1"; Enter | 1. Abre o assistente de primeiro acesso, com o foco em Nome da loja 2. Em vermelho: "A senha deve ter ao menos 6 caracteres." 3. "A confirmação não confere com a senha." 4. Aviso "Supervisor criado. Entre com o login "admin"…" e, ao fechar, a tela de login com Usuário = `admin` e o nome da loja. No DBeaver: `usuario` com `login = admin`, `supervisor = true`, `deve_trocar_senha = false` e `senha_hash` começando com `$2a$12$`; `configuracao` `empresa.nome` = "Mercado Bom Preço" | |
| CT-F1-02 | CT-F1-01 feito | 1. Na tela de login, Senha "errada"; Enter 2. Usuário "ninguem", Senha "errada"; Enter 3. Usuário " ADMIN ", Senha "segredo1"; Enter | 1 e 2. "Usuário ou senha inválidos." abaixo dos campos (a mesma mensagem nos dois casos); a senha é apagada 3. Abre a janela principal: menu com Painel, PDV, Produtos, Estoque, Inventário, Compras, Fornecedores, Relatórios, Usuários, Configurações; rodapé do menu com Alterar senha, Log off e Sair; área central "Olá, Ana Admin!"; barra de status "Usuário: Ana Admin (admin)" | |
| CT-F1-03 | Usuário cadastrado; sistema fechado | 1. `mvn javafx:run` e cronometrar até a tela de login | A tela de login aparece em menos de 5 s depois que a janela abre (métrica 9.4) | |
| CT-F1-04 | Tela de login | 1. Pressionar **Esc** 2. Abrir de novo, digitar usuário e senha só com o teclado (Tab entre os campos) e Enter | 1. O sistema fecha 2. Entra sem usar o mouse | |
| CT-F1-05 | Tela de login; usuário `admin` | 1. Errar a senha de `admin` 5 vezes seguidas 2. Digitar a senha certa 3. Esperar 5 minutos e entrar com a senha certa | 1. Nas 4 primeiras: "Usuário ou senha inválidos."; na 5ª: "Usuário bloqueado por excesso de tentativas. Tente novamente às HH:mm." (agora + 5 min) 2. A mesma mensagem de bloqueio 3. Entra normalmente | |
| CT-F1-06 | Logado como `admin` | 1. Menu **Usuários** 2. **Novo usuário**: Login "caixa1", Nome "Carlos Caixa", marcar só **PDV**, Senha provisória "inicial1" e Confirmação "inicial1"; **Salvar** 3. Tentar criar outro com o login "CAIXA1" | 1. Lista com `admin` (Supervisor, Ativo) e formulário "Novo usuário" 2. "Usuário salvo." em verde; `caixa1` na lista como Operador, "Ativo (senha provisória)" 3. "Este login já está em uso por outro usuário" | |
| CT-F1-07 | CT-F1-06 feito | 1. **Log off** 2. Entrar como `caixa1` / "inicial1" 3. Senha atual "inicial1", Nova "caixa1", Confirmação "caixa1"; Enter 4. Nova e Confirmação "minhasenha"; Enter | 1. Volta ao login 2. Abre a **Troca de senha** ("A sua senha é provisória…") em vez da janela principal 3. "A senha não pode ser igual ao login." 4. Abre a janela principal com o menu só com **Painel** e **PDV** (sem Produtos, sem Usuários, sem Configurações) | |
| CT-F1-08 | Logado como `admin`, tela Usuários | 1. Selecionar `admin` 2. Desmarcar **Supervisor** e Salvar | 1. O botão **Inativar** fica desabilitado para o próprio usuário 2. "Você não pode remover o seu próprio acesso de supervisor." | |
| CT-F1-09 | `caixa1` existe | 1. Na tela de login, errar a senha de `caixa1` 5 vezes 2. Entrar como `admin`, Usuários, selecionar `caixa1` 3. **Desbloquear** 4. **Redefinir senha**: "nova123" / "nova123"; Redefinir 5. Log off e entrar como `caixa1` / "nova123" | 2. Situação "Bloqueado até HH:mm" 3. "Usuário desbloqueado." e situação "Ativo" 4. "Senha provisória definida…" 5. Pede a troca de senha antes de entrar | |
| CT-F1-10 | Logado como `admin`, tela Usuários | 1. Selecionar `caixa1`, **Inativar**, confirmar com "Sim" 2. Log off e entrar como `caixa1` com a senha certa 3. Entrar como `admin`, **Reativar** `caixa1` | 1. "Usuário inativado."; situação "Inativo"; o botão passa a "Reativar" 2. "Este usuário está inativo. Procure o supervisor." 3. `caixa1` entra de novo | |
| CT-F1-11 | Casos anteriores feitos | 1. No DBeaver: `SELECT criado_em, usuario_id, acao, descricao, terminal FROM auditoria ORDER BY id;` | Há `USUARIO_CRIADO` (primeiro acesso e `caixa1`), `LOGIN` e `LOGOUT`, `LOGIN_FALHOU` com o motivo (senha incorreta com a tentativa, bloqueado, inativo, login inexistente com `usuario_id` vazio), `SENHA_REDEFINIDA`, `USUARIO_DESBLOQUEADO`, `USUARIO_ALTERADO` (inativado/reativado), todos com `terminal = CAIXA-01`. Nenhuma senha aparece na descrição | |
| CT-F1-12 | Logado como qualquer usuário | 1. **Alterar senha**: senha atual errada; Alterar 2. Dados corretos; Alterar 3. **Sair** e responder "Não" 4. **Sair** e responder "Sim" | 1. "A senha atual está incorreta." no próprio diálogo, que continua aberto 2. "Senha alterada." 3. Continua no sistema 4. O sistema fecha e a auditoria registra `LOGOUT` | |

O componente **Liberação de supervisor** é usado na sangria/suprimento (CT-F4-06), no cancelamento de venda
(CT-F4-19) e no desconto acima do limite (CT-F4-16); a regra está coberta por `AcessoServiceIT`, `CaixaServiceIT` e
`VendaServiceIT`.

## Fase 2 — Produtos

Casos de uso do TCC: Cadastrar produto, Editar produto, Excluir produto e Consultar produtos (mais categorias,
códigos de barras, simulador e histórico). Execute na ordem, logado como `admin` (supervisor), salvo indicação.
Os códigos de barras usados são válidos: `7891000315507`, `7891234567895` e `12345670`.

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F2-01 | Logado como `admin` | 1. Menu **Produtos** 2. **Novo produto** (ou Ctrl+N) 3. Nome "Arroz tipo 1 5 kg", Categoria "Mercearia", Unidade "UN", Preço "27,90", Custo "21,50", Estoque mínimo "5", Estoque inicial "10"; no campo de código de barras digitar `7891000315507` e Enter 4. Enter (Salvar) | 1. Lista de produtos (vazia: "Nenhum produto encontrado."), foco no campo de busca 2. Janela "Novo produto" com Código vazio, Unidade UN, Estoque atual "—" e foco em Nome 3. O código entra na lista como "(principal)"; Enter no campo de código **não** salva 4. "Produto salvo." em verde; Código preenchido; Estoque atual "10 UN"; campo Estoque inicial some; Última alteração com data/hora e "Ana Admin"; aba Histórico com uma linha "— → R$ 27,90". No DBeaver: `SELECT tipo, quantidade, saldo_posterior, motivo FROM movimento_estoque;` → uma linha `AJUSTE_ENTRADA`, 10, 10, "Estoque inicial" | |
| CT-F2-02 | Janela de produto aberta (novo) | 1. No campo de código de barras digitar `7891000315508` e Enter 2. Digitar `12345` e Enter 3. Digitar `78910003155O7` (letra O) e Enter | 1. "Código de barras 7891000315508 inválido: o dígito verificador não confere." e o código não entra na lista 2. "O código de barras deve ter 8, 12, 13 ou 14 dígitos." 3. "O código de barras deve ter só números." | |
| CT-F2-03 | CT-F2-01 feito | 1. Novo produto "Café 500 g", UN, preço "18,90", custo "12,00", código `7891000315507`; Salvar | "Não foi possível salvar: o código de barras 7891000315507 já pertence ao produto "Arroz tipo 1 5 kg" (código N)." e nada é gravado | |
| CT-F2-04 | Janela de produto aberta | 1. Custo "3,20" e Preço "5,49" 2. Margem desejada "30" 3. **Aplicar preço sugerido** 4. Preço "0" | 1. Enquanto digita: Lucro unitário R$ 2,29; Margem 41,71%; Markup 71,56% 2. Preço sugerido R$ 4,58 3. O preço passa a "4,58" e a margem a 30,13% 4. Margem "—" | |
| CT-F2-05 | CT-F2-01 feito | 1. Novo produto "ARROZ TIPO 1 5 KG", UN, preço "20,00", custo "21,50"; Salvar 2. Responder **Não** 3. Salvar e responder **Sim** | 1. Confirmação com "O preço está abaixo do custo: cada venda dará prejuízo.", "Já existe produto com este nome: "Arroz tipo 1 5 kg" (código N)." e "Salvar mesmo assim?" 2. Nada é gravado 3. "Produto salvo." | |
| CT-F2-06 | Janela de produto aberta (novo) | 1. Nome "A"; Salvar 2. Nome "Queijo muçarela", UN, preço "39,90", custo "28", estoque inicial "1,5"; Salvar 3. Trocar a unidade para "KG", estoque inicial "0,345"; Salvar 4. Preço "5,499"; Salvar 5. Preço "abc"; Salvar | 1. "O nome do produto deve ter ao menos 2 caracteres." 2. "O estoque inicial deve ser um número inteiro: a unidade do produto não aceita quantidade fracionada (1,5)." 3. Salvo; Estoque atual "0,345 KG" 4. "O preço de venda pode ter no máximo 2 casas decimais." 5. "Preço de venda: valor inválido ("abc")." | |
| CT-F2-07 | CT-F2-01 feito; usuário `estoq` com a permissão **Produtos** (criar em Usuários) | 1. Log off e entrar como `estoq` 2. Produtos, abrir "Arroz tipo 1 5 kg", trocar o preço para "29,90"; Salvar 3. Aba **Histórico** | 1. Menu com Painel e Produtos 2. "Produto salvo."; Última alteração com o nome de `estoq` 3. Duas linhas: a mais recente "R$ 27,90 → R$ 29,90" com o usuário `estoq`; a do cadastro "— → R$ 27,90" com Ana Admin. Na auditoria: `PRODUTO_ALTERADO` "… Preço alterado de R$ 27,90 para R$ 29,90" | |
| CT-F2-08 | Produto "Arroz tipo 1 5 kg" (tem estoque inicial) | 1. Abrir o produto, **Excluir**, confirmar com **Sim** 2. Na pergunta seguinte, **Sim** | 1. "O produto "Arroz tipo 1 5 kg" não pode ser excluído porque já tem movimentação de estoque. Você pode inativá-lo… Inativar o produto agora?" 2. "Produto inativado."; situação "Inativo — não aparece no PDV nem na busca padrão"; botão passa a **Reativar**. Ao fechar, o produto some da lista (filtro Ativos) | |
| CT-F2-09 | Um produto sem estoque inicial (ex.: "Café 500 g" sem código) | 1. Abrir, **Excluir**, **Sim** | A janela fecha e o produto sai da lista. Na auditoria: `PRODUTO_EXCLUIDO` | |
| CT-F2-10 | CT-F2-08 feito | 1. Filtro Situação "Inativos" 2. Abrir o arroz e **Reativar** | 1. O arroz aparece em itálico 2. "Produto reativado."; volta à lista de Ativos | |
| CT-F2-11 | Produtos "Açúcar refinado 1 kg" (código de barras `7891234567895`, categoria Mercearia) e "Arroz tipo 1 5 kg" cadastrados | 1. Buscar "acucar" (Enter) 2. Buscar "AÇÚ" 3. Buscar o código interno do arroz 4. Buscar `7891234567895` 5. Buscar `789123456789` (sem o último dígito) 6. Busca vazia, Categoria "Bebidas" | 1 e 2. Só "Açúcar refinado 1 kg" 3. Só o arroz 4. Só o açúcar 5. Nenhum produto 6. Só produtos de Bebidas. Colunas: Código, Nome, Categoria, Preço (R$, à direita), Estoque (com a unidade, à direita), Última alteração (data/hora — usuário) | |
| CT-F2-12 | Lista com produtos | 1. Duplo clique numa linha 2. Esc 3. Selecionar uma linha com as setas e Enter 4. Ctrl+N | 1. Abre a janela do produto 2. Fecha a janela 3. Abre o produto 4. Abre "Novo produto" | |
| CT-F2-13 | Usuário `caixa1` só com **PDV** (Fase 1) | 1. Entrar como `caixa1` | O menu não mostra Produtos (a recusa no service é coberta por `ProdutoServiceIT.operadorSemProdutosERecusadoNoService`) | |
| CT-F2-14 | Tela Produtos | 1. **Categorias…** 2. Nova categoria "mercearia"; Salvar 3. Nome "Sazonais"; Salvar 4. Cadastrar um produto na categoria Sazonais; em Categorias, selecionar Sazonais e **Excluir**, **Sim**, depois **Sim** 5. Abrir um produto novo e a lista Categoria 6. Criar "Temporária" e excluí-la | 1. Lista com as 9 categorias da V2 2. "Já existe uma categoria com o nome "mercearia"." 3. "Categoria salva." 4. "A categoria "Sazonais" é usada por 1 produto…"; ao confirmar, "Categoria inativada." 5. Sazonais não aparece (o produto que já a tem continua com ela) 6. "Categoria excluída." | |
| CT-F2-15 | Banco `varesync` com V1 e V2 (Fase 1) | 1. `mvn javafx:run` 2. No DBeaver: `SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;` 3. Alterar o preço de um produto e conferir `SELECT atualizado_em FROM produto WHERE id = …;` e a aba Histórico | 1. O log mostra "Migrations aplicadas: 1; versão do banco: 3" 2. Versões 1, 2 e 3, todas com `success = true` 3. `atualizado_em` e a linha do histórico com a data/hora da alteração (a do computador) | |

## Fase 3 — Estoque

Caso de uso do TCC: Visualizar quantidade em estoque (mais ajustes, perdas, alertas e diagnóstico). Execute na ordem,
logado como `admin` (supervisor), salvo indicação. Pré-condição comum: produtos "Café 500 g" (UN, custo 21,50,
estoque mínimo 5, estoque inicial 10, código de barras `7891234567895`) e "Queijo muçarela" (KG, custo 28,00,
estoque mínimo 0, sem estoque inicial), cadastrados em Produtos.

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F3-01 | Produtos da pré-condição | 1. Menu **Estoque** 2. Buscar "cafe" (Enter) 3. Buscar `7891234567895` 4. Buscar o código interno do queijo 5. Busca vazia | 1. Lista com os produtos ativos (Código, Produto, Categoria, Saldo com a unidade, Mínimo, Valor em estoque), foco no campo de busca, "Selecione um produto" no cabeçalho e botões de ajuste desabilitados 2 e 3. Só o café, já selecionado 4. Só o queijo 5. Todos os ativos | |
| CT-F3-02 | Café selecionado | 1. Conferir o cabeçalho e o kardex | Saldo **10 UN**, Estoque mínimo 5 UN, Custo médio R$ 21,5000, Valor em estoque R$ 215,00. Período de (hoje − 29 dias) até hoje, em dd/MM/aaaa. Kardex com uma linha: data/hora do cadastro, "Ajuste de entrada", **+10**, saldo após **10**, R$ 21,5000, Ana Admin, "Estoque inicial" | |
| CT-F3-03 | CT-F3-02 feito (saldo 10) | 1. **F5** (Ajustar saldo para…) 2. Novo saldo "7" 3. Motivo "Acerto de contagem" (já sugerido); Enter 4. F5 de novo, "7", Enter | 2. Abaixo do campo: "Será registrada uma saída de 3 UN." 3. O diálogo fecha; mensagem verde "Registrada saída de 3 UN em Café 500 g. Saldo: 7 UN."; kardex com a linha nova "Ajuste de saída", **−3** (vermelho), saldo após **7**, "Acerto de contagem"; cabeçalho Saldo 7 UN 4. No diálogo: "O saldo já é 7 UN: não há o que ajustar." (continua aberto; Esc fecha) | |
| CT-F3-04 | CT-F3-03 feito | 1. **F4** (Ajustar estoque) 2. Tipo Saída, Quantidade "2", Motivo "Devolução ao fornecedor", Observação "NF 123"; Enter | Kardex: +10 → 10; −3 → 7; −2 → **5**, origem "Devolução ao fornecedor: NF 123". Linhas em ordem de data/hora, a mais recente embaixo | |
| CT-F3-05 | Café selecionado | 1. F4, Tipo Entrada, Quantidade "2", Motivo vazio; Registrar 2. Quantidade "0", Motivo "Acerto"; Registrar 3. Quantidade "abc"; Registrar | 1. "Informe o motivo do ajuste." no diálogo, que continua aberto; nada é gravado 2. "A quantidade deve ser maior que zero." 3. "Quantidade: valor inválido ("abc")." | |
| CT-F3-06 | Café e queijo cadastrados | 1. Café: F4, Entrada, Quantidade "1,5", Motivo "Acerto"; Registrar 2. Esc; selecionar o queijo; F4, Entrada, "0,345", Motivo "Acerto de contagem"; Enter | 1. "A quantidade deve ser um número inteiro: a unidade do produto não aceita quantidade fracionada (1,5)." 2. Saldo do queijo **0,345 KG**; kardex "+0,345", saldo após "0,345" | |
| CT-F3-07 | Café com saldo 5 | 1. F4, Tipo **Perda**: abrir a lista de motivos 2. Escolher "Outros", Quantidade "1", sem observação; Registrar 3. Escolher "Vencimento", Observação "lote 12"; Registrar | 1. Lista fixa (não aceita digitação): Vencimento, Avaria, Furto/desvio, Consumo interno, Outros 2. "Com o motivo "Outros", descreva a perda na observação." 3. Kardex "Perda", −1, saldo após 4, origem "Vencimento: lote 12"; a linha do café na lista fica em **âmbar** e o cabeçalho mostra "Abaixo do estoque mínimo" | |
| CT-F3-08 | CT-F3-07 feito | 1. Marcar **Só abaixo do mínimo** 2. Desmarcar; selecionar o queijo; F4, Saída, "1", Motivo "Acerto"; Registrar | 1. Só o café aparece; resumo "1 produto · 1 abaixo do mínimo" 2. Saldo do queijo **−0,655 KG** em vermelho (lista e cabeçalho), "Saldo negativo" no cabeçalho, linha em âmbar (abaixo do mínimo 0) | |
| CT-F3-09 | CT-F3-08 feito | 1. Menu **Painel** 2. Clicar no link | 1. Abaixo da saudação: "2 produtos estão abaixo do estoque mínimo — ver no Estoque" 2. Abre o Estoque com **Só abaixo do mínimo** marcado e o item Estoque destacado no menu | |
| CT-F3-10 | Usuários `estoq` (só **Produtos**, Fase 2) e `contador` (criar com só **Estoque**) | 1. Entrar como `estoq` 2. Entrar como `contador`, Estoque, café, F4, Entrada, "1", Motivo "Acerto"; Registrar | 1. O menu não mostra Estoque; o Painel não mostra o alerta de mínimo 2. Menu com Painel e Estoque (sem Configurações); o kardex mostra o movimento com o usuário "contador" (nome cadastrado) | |
| CT-F3-11 | Casos anteriores feitos; logado como `admin` | 1. Menu **Configurações** 2. **Verificar agora** 3. No DBeaver: `SELECT * FROM vw_estoque_inconsistente;` 4. No DBeaver: `UPDATE produto_estoque SET quantidade = 0;` 5. No DBeaver: `SELECT descricao FROM auditoria WHERE acao = 'ESTOQUE_AJUSTADO' ORDER BY id;` | 1 e 2. Aba "Diagnóstico"; indicador de progresso e depois, em verde, "Tudo certo: o saldo de todos os produtos confere com a soma dos movimentos."; tabela vazia 3. Nenhuma linha 4. Erro "O estoque só pode ser alterado registrando um movimento de estoque." 5. Uma linha por ajuste, ex.: "Produto Café 500 g (código N): saldo ajustado para 7 UN — saída de 3 UN, saldo de 10 UN para 7 UN. Motivo: Acerto de contagem" | |
| CT-F3-12 | Estoque, café selecionado | 1. Período de "01/01/2020" até "31/12/2020"; **Filtrar** 2. De = hoje + 1 dia, Até = hoje; Filtrar 3. Voltar o período padrão; só com o teclado: Tab até a lista, setas para escolher o produto, F4, Tab entre os campos, Esc 4. Abrir um produto **inativo** marcando **Mostrar inativos** | 1. Kardex vazio com "Nenhum movimento no período." 2. "A data inicial não pode ser depois da data final." em vermelho 3. O diálogo abre e fecha sem o mouse; nada é gravado 4. Inativos aparecem em itálico; o título mostra "— inativo" e o ajuste continua permitido | |

## Fase 4 — Caixa e PDV

Funcionalidade ampliada: caixa (6.5) — abertura com fundo de troco, sangria, suprimento, fechamento às cegas e
relatório de fechamento (R11). Execute na ordem, no mesmo computador (terminal `CAIXA-01`), começando com o caixa
fechado. Usuários: `admin` (supervisor) e `caixa1` (só **PDV**, Fase 1). A tela do caixa fica no menu **Caixa**; o
menu **PDV** abre a venda (CT-F4-11 em diante).

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F4-01 | Logado como `admin`; nenhum caixa aberto | 1. Conferir a barra de status 2. Menu **Caixa** 3. **Abrir caixa…**, Fundo de troco "100"; Enter 4. No DBeaver: `SELECT id, terminal, status, valor_abertura, aberto_em FROM caixa_sessao;` | 1. "Caixa: fechado" 2. Tela Caixa com "Caixa fechado — abra o caixa…", campos com "—", só **Abrir caixa…** ativo (F8 e F12 desabilitados) 3. Mensagem verde "Caixa nº N aberto com fundo de troco de R$ 100,00."; Sessão nº N, Aberto por Ana Admin, Aberto em (agora), Fundo de troco R$ 100,00; barra de status "Caixa: aberto (nº N)" 4. Uma linha `CAIXA-01`, `ABERTO`, 100.00, com a data/hora da abertura | |
| CT-F4-02 | Caixa fechado (pule se já abriu: feche antes pelo CT-F4-07, ou teste num banco novo) | 1. Abrir caixa…, "-1"; Enter 2. "abc"; Enter 3. "10,005"; Enter 4. Vazio; Enter 5. "0"; Enter | 1. "O fundo de troco não pode ser negativo." no diálogo, que continua aberto 2. "Valor inválido ("abc")." 3. "O fundo de troco pode ter no máximo 2 casas decimais." 4. "Informe o fundo de troco." 5. Caixa aberto com fundo de troco R$ 0,00 | |
| CT-F4-03 | CT-F4-01 feito (caixa nº N aberto) | 1. **Log off** (sem aviso de caixa) 2. Entrar como `caixa1` 3. Menu Caixa | 1. Volta ao login sem perguntar nada sobre o caixa 2. Barra de status "Caixa: aberto (nº N)" 3. Mesma sessão nº N, "Aberto por Ana Admin"; não há botão Abrir caixa | |
| CT-F4-04 | Logado como `admin`, caixa nº N aberto com R$ 100,00 | 1. **F8**: Tipo Suprimento, Valor "20", Motivo "Reforço de troco"; Enter 2. F8: Tipo Sangria, Valor "500", Motivo "Recolhimento ao cofre"; Registrar 3. Valor "0"; Registrar 4. Valor "50", Motivo vazio; Registrar 5. Motivo "Recolhimento ao cofre"; Enter | 1. "Suprimento de R$ 20,00 registrado."; a lista mostra a linha (data/hora, Suprimento, R$ 20,00 à direita, motivo, Ana Admin) 2. "O valor da sangria é maior que o dinheiro que deveria estar na gaveta. Confira o valor." — **sem** mostrar o valor esperado; nada é gravado 3. "O valor deve ser maior que zero." 4. "Informe o motivo da sangria." 5. "Sangria de R$ 50,00 registrada."; lista com as duas linhas | |
| CT-F4-05 | Usuário `gerente` criado só com **Relatórios**; caixa aberto | 1. Entrar como `gerente` | O menu não mostra PDV nem Caixa; a barra de status mostra "Caixa: aberto (nº N)" | |
| CT-F4-06 | Entrar como `caixa1` (sem **Sangria e suprimento**); caixa aberto | 1. Menu Caixa, **F8** 2. Usuário `caixa1` com a senha dele; Liberar 3. Usuário `admin`, senha errada; Liberar 4. Usuário `admin`, senha certa; Liberar 5. Sangria, "10", Motivo "Pagamento de fornecedor"; Enter 6. No DBeaver: `SELECT descricao FROM auditoria WHERE acao IN ('SANGRIA','SUPRIMENTO') ORDER BY id;` | 1. Abre "Liberação de supervisor" ("Sangria ou suprimento precisa da autorização…") 2. "Este usuário não tem permissão para liberar esta ação (Sangria e suprimento)." 3. "Usuário ou senha inválidos." (senha apagada) 4. Abre o diálogo de sangria/suprimento 5. "Sangria de R$ 10,00 registrada."; a linha mostra o usuário **Operador caixa1** (nome cadastrado) 6. A última linha termina com "(liberado por Ana Admin (admin))" | |
| CT-F4-07 | Caixa nº N: abertura 100, suprimento 20, sangrias 50 e 10 (CT-F4-04 e 06); logado como `admin` | 1. **F12** 2. Dinheiro contado "59"; Enter 3. **Não** 4. F12, "59", Enter, **Sim** 5. Conferir o relatório 6. Enter 7. No DBeaver: `SELECT status, valor_esperado, valor_contado, diferenca, fechado_por, fechado_em FROM caixa_sessao WHERE id = N;` | 1. "Fechar caixa — CAIXA-01" pedindo só o dinheiro contado; o valor esperado não aparece em lugar nenhum 2. Pergunta "Fechar o caixa com R$ 59,00 contados? Depois de fechado não é possível alterar." 3. Volta à tela; o caixa continua aberto 4 e 5. Janela "Fechamento do caixa nº N": Abertura e Fechamento com data/hora e usuário; Nº de vendas 0; Total vendido R$ 0,00; Vendas canceladas 0; Descontos R$ 0,00; Fundo de troco R$ 100,00; (+) Vendas em dinheiro R$ 0,00; (+) Suprimentos R$ 20,00; (−) Sangrias R$ 60,00; (=) Dinheiro esperado **R$ 60,00**; Dinheiro contado **R$ 59,00**; Diferença **Falta de R$ 1,00** em vermelho; "Nenhuma venda nesta sessão."; as 3 sangrias/suprimentos 6. A janela fecha; a tela mostra "Caixa nº N fechado: falta de R$ 1,00." e "Caixa fechado"; barra de status "Caixa: fechado" 7. `FECHADO`, 60.00, 59.00, **-1.00**, o id do admin e a data/hora do fechamento | |
| CT-F4-08 | CT-F4-07 feito | 1. **Ver último fechamento** 2. Esc 3. Abrir caixa…, "80"; Enter 4. Ver último fechamento | 1. Reabre o relatório do caixa nº N 2. Fecha 3. Caixa nº N+1 aberto; lista de sangrias vazia 4. Continua mostrando o fechamento do caixa nº N | |
| CT-F4-09 | Caixa nº N+1 aberto; logado como `admin` | 1. **Sair** 2. **Não** 3. Fechar a janela pelo **X** 4. Não 5. Fechar o caixa (F12, contado "80", Sim, Enter) e clicar **Sair** 6. Sim | 1. "O caixa nº N+1 deste terminal está aberto (aberto por Ana Admin em …). Se o expediente acabou, feche o caixa antes de sair (menu Caixa › Fechar caixa). Sair mesmo assim?" 2. Continua no sistema 3. Mesma pergunta 4. Continua no sistema 5. Pergunta só "Deseja sair do VareSync?" 6. O sistema fecha | |
| CT-F4-10 | Caixa aberto; tela Caixa | Só com o teclado: 1. F8, Tab entre Tipo, Valor e Motivo, Esc 2. F12, Esc 3. Abrir um R11 (Ver último fechamento pelo Tab + Espaço) e fechar com Enter | 1 e 2. Os diálogos abrem e fecham sem o mouse; nada é gravado 3. O relatório fecha com Enter (ou Esc) | |

### PDV (venda, 6.6) e comprovante

Casos de uso do TCC: Registrar venda, Selecionar produtos e Atualizar estoque (mais descontos, cancelamentos, várias
formas de pagamento e comprovante). Execute na ordem, sem usar o mouse salvo indicação. Pré-condição comum (cadastrar
como `admin` em Produtos): "Refrigerante cola 2 L" (UN, preço 5,49, custo 3,20, estoque inicial 10, código de barras
`7891000315507`) e "Queijo muçarela" (KG, preço 39,90, custo 28,00, estoque inicial 2; anote o código interno, aqui
**Q**); caixa do terminal fechado; usuário `caixa1` só com **PDV**. Configuração padrão da V2 (desconto máximo 5%,
vende sem saldo e avisa, cancelar item sem supervisor).

Em 2026-10-17, os casos marcados "OK (roteiro)" foram executados por um roteiro descartável que abriu o sistema real
(JavaFX) sobre o banco `varesync_test`, digitou pelo teclado (Robot do JavaFX) e capturou cada tela; os valores do banco
foram conferidos ao final. Falta a execução pela pessoa, com o leitor de código de barras.

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F4-11 | Logado como `caixa1`; caixa fechado | 1. Menu **PDV** 2. **Esc** 3. Menu **PDV** de novo, Fundo de troco "100"; Enter | 1. Abre "Abrir caixa — CAIXA-01" (o PDV não vende sem caixa) 2. O diálogo fecha e o PDV **não** abre 3. Janela "VareSync — PDV" maximizada: faixa escura com "PDV", nome da loja, "Caixa nº N", "Terminal CAIXA-01", "Operador: Carlos Caixa"; lista vazia; painel escuro com "Caixa livre", Itens 0, Subtotal e Descontos R$ 0,00 e **TOTAL R$ 0,00** em amarelo, fonte grande; campo Código focado; barra de atalhos F1 a F12 e Esc | OK (roteiro) em 2026-10-17 |
| CT-F4-12 | CT-F4-11 feito | 1. Digitar "123456"; Enter | Mensagem vermelha no painel "Produto não encontrado (código 123456).", campo em vermelho por um instante, bipe, campo limpo e com o foco | OK (roteiro) em 2026-10-17 (o bipe não aparece na captura; conferir pelo som) |
| CT-F4-13 | PDV aberto | 1. Ler (ou digitar) "3*7891000315507"; Enter 2. "0,345*Q"; Enter | 1. Linha 001, código 7891000315507, "Refrigerante cola 2 L", 3 UN, R$ 5,49, total R$ 16,47 2. Linha 002, código Q, "Queijo muçarela", 0,345 KG, R$ 39,90, R$ 13,77 (13,7655 arredondado). Último item "Queijo muçarela — 0,345 KG x R$ 39,90 = R$ 13,77"; Itens 2; Subtotal e TOTAL **R$ 30,24** | OK (roteiro) em 2026-10-17 |
| CT-F4-14 | Venda em andamento | 1. Digitar Q; Enter 2. "abc"; Confirmar 3. "0,5"; Enter 4. "1,5*7891000315507"; Enter | 1. Diálogo "Quantidade — Queijo muçarela" pedindo a quantidade em KG 2. "Valor inválido para a quantidade ("abc")." no diálogo 3. Item de 0,500 KG, R$ 19,95 4. Mensagem "A quantidade deve ser um número inteiro: a unidade do produto não aceita quantidade fracionada (1,5)." e nada é incluído | |
| CT-F4-15 | Venda em andamento | 1. Digitar "refri"; Enter 2. Enter 3. **F3**, "2"; Enter; ler `7891000315507` 4. **F7**, digitar Q; Enter; Esc 5. **F3**, "2"; Enter; **Esc** | 1. "Buscar produto (F2)" com o refrigerante selecionado (inativos não aparecem) 2. O refrigerante entra com 1 UN 3. O painel mostra "Quantidade do próximo item: 2 (Esc limpa)"; o item entra com 2 UN e o aviso some 4. "Consultar preço (F7)" mostra nome, R$ 39,90 / KG e o estoque, sem incluir 5. Só a quantidade do próximo item é limpa (a venda continua) | |
| CT-F4-16 | Só os itens de CT-F4-13 na venda (abandone e refaça se preciso) | 1. **F5**, Desconto "5" (Em: %); Enter 2. Selecionar o item 001 com ↑; **F4**, "10" (%); Enter 3. Na liberação, **Esc** | 1. Descontos "-R$ 1,51 (4,99%)", **TOTAL R$ 28,73**, mensagem "Desconto na venda: R$ 1,51." (dentro do limite de 5%: sem liberação) 2. Abre "Liberação de supervisor" ("Desconto acima do limite precisa da autorização…") 3. Volta ao diálogo de desconto com "O desconto total da venda (…% do valor) passa do limite de 5,00% do operador…"; Esc; o item continua sem desconto | Passo 1: OK (roteiro) em 2026-10-17. Passos 2 e 3: |
| CT-F4-17 | CT-F4-16 feito (total R$ 28,73) | 1. **F10** 2. Shift+Tab, ↓ (PIX), Tab, "10"; Enter 3. Shift+Tab, ↑ (Dinheiro), Tab, "50"; Enter 4. Enter 5. Shift+Tab e Espaço (Ver comprovante) 6. **Salvar PDF…**, salvar na Área de Trabalho e abrir o arquivo 7. Esc 8. No DBeaver: `SELECT valor_bruto, desconto_venda, valor_total, troco FROM venda;`, `SELECT sequencia, desconto_rateado, valor_liquido FROM venda_item;`, `SELECT forma_pagamento_id, valor FROM venda_pagamento;`, `SELECT tipo, quantidade, saldo_posterior FROM movimento_estoque WHERE tipo = 'VENDA';` | 1. Janela Pagamento: Total R$ 28,73, Pago R$ 0,00, Falta R$ 28,73, forma Dinheiro, valor "28,73" selecionado 2. Linha PIX R$ 10,00; Falta R$ 18,73 3. Linha Dinheiro R$ 50,00; Pago R$ 60,00, Falta R$ 0,00, **Troco R$ 31,27** em verde; forma e valor desabilitados; foco em **Finalizar venda (F10)** 4. "Venda nº V finalizada" com **Troco R$ 31,27** em destaque; botões "Ver comprovante" e "Próxima venda" 5. Comprovante: Mercado Bom Preço, "NÃO É DOCUMENTO FISCAL", Venda nº V com data/hora, Operador Carlos Caixa, Terminal CAIXA-01, os 2 itens, Subtotal R$ 30,24, Desconto na venda -R$ 1,51, TOTAL R$ 28,73, PIX R$ 10,00, Dinheiro R$ 50,00, Troco R$ 31,27, rodapé 6. "PDF salvo em …"; o PDF (bobina estreita) tem o mesmo conteúdo, com acentos 7. Tela do PDV limpa, "Caixa livre", mensagem "Venda nº V finalizada. Troco: R$ 31,27." 8. 30.24, 1.51, 28.73, 31.27; rateio 0.82 e 0.69, líquidos 15.65 e 13.08; PIX 10.00 e Dinheiro **18.73** (recebido − troco); `VENDA` −3 (saldo 7) e −0,345 (saldo 1,655) | Passos 1 a 5, 7 e 8: OK (roteiro) em 2026-10-17. Passo 6: |
| CT-F4-18 | Venda de R$ 16,47 em andamento (3 refrigerantes) | 1. F10, Shift+Tab, ↓ (PIX), Tab, "20"; Enter 2. "16,47"; Enter; Enter | 1. "Só o pagamento em dinheiro pode passar do que falta. Em PIX, informe até R$ 16,47." 2. Finaliza "Sem troco" | |
| CT-F4-19 | CT-F4-17 feito; logado como `caixa1` | 1. **F9** 2. **Delete** 3. Usuário `admin`, Tab, senha; Enter 4. Motivo "Cliente desistiu"; Enter 5. Esc 6. No DBeaver: `SELECT status, cancelada_por, motivo_cancelamento FROM venda WHERE id = V;`, saldos em `produto_estoque`, `SELECT dinheiro_esperado FROM vw_caixa_resumo WHERE caixa_sessao_id = N;` e a auditoria `VENDA_CANCELADA` | 1. "Vendas da sessão de caixa (1 finalizada)" com a venda V selecionada (nº, data/hora, Carlos Caixa, 2 itens, R$ 28,73, Finalizada) 2. "Liberação de supervisor" para cancelar a venda nº V (`caixa1` não tem Cancelar venda) 3. "Cancelar a venda nº V": "Venda de R$ 28,73 às HH:mm. Os itens voltam ao estoque…", motivo com sugestões 4. "Venda nº V cancelada."; a linha fica "Cancelada" (itálico; legível também selecionada) 5. Volta ao PDV 6. `CANCELADA`, o id do `admin`, "Cliente desistiu"; saldos 10 e 2; esperado **R$ 100,00** (a venda saiu do caixa); auditoria "Venda nº V de R$ 28,73 cancelada no caixa nº N. Motivo: Cliente desistiu (liberado por Ana Admin (admin))" | OK (roteiro) em 2026-10-17 |
| CT-F4-20 | PDV aberto, sem venda | 1. "2*7891000315507"; Enter; "0,5*Q"; Enter 2. ↑ (item 001); **F6**; Enter (Sim) 3. ↓ 4. **Esc**; Enter (Sim) 5. Esc 6. No DBeaver: `SELECT acao, descricao FROM auditoria WHERE acao IN ('ITEM_CANCELADO','VENDA_ABANDONADA');` e `SELECT count(*) FROM venda;` | 2. Pergunta "Cancelar o item 1 — Refrigerante cola 2 L (2 UN, R$ 10,98)?"; depois "Item 1 cancelado." 3. A linha 001 aparece **riscada** com "(cancelado)" e fica fora do total (R$ 19,95; Itens 1) 4. "Abandonar a venda em andamento (1 item, R$ 19,95)?"; depois "Venda abandonada." e tela limpa 5. Sem venda, Esc sai do PDV 6. `ITEM_CANCELADO` "Item 1 cancelado durante a venda: 2 UN x Refrigerante cola 2 L (R$ 10,98)" e `VENDA_ABANDONADA` com os itens e o total; nenhuma venda nova | OK (roteiro) em 2026-10-17 |
| CT-F4-21 | Caixa aberto; leitor de código de barras ligado | Só com teclado e leitor, cronometrar: 1. Ler 3 produtos 2. F10, valor em dinheiro maior que o total; Enter; Enter 3. Enter (Próxima venda) | Venda finalizada com troco sem tocar no mouse (métrica 9.4); cada leitura aparece na lista na hora | |
| CT-F4-22 | Caixa nº N aberto com R$ 100,00, venda V cancelada (CT-F4-19); logado como `caixa1` | 1. **F8**; liberação `admin`; Tipo Sangria (já marcado), Tab, "50", Tab, "Recolhimento ao cofre"; Enter 2. Ler um produto e **F12** 3. Esc (abandonar, Sim) e **F12** 4. "49"; Enter; Enter (Sim) 5. Conferir o R11 e Esc 6. Menu **PDV** e Esc | 1. "Sangria de R$ 50,00 registrada." no painel 2. "Finalize ou abandone a venda em andamento antes de fechar o caixa." 3. "Fechar caixa — CAIXA-01" pedindo só o dinheiro contado 4. "Fechar o caixa com R$ 49,00 contados?…" 5. R11: Nº de vendas 0, Vendas canceladas 1, Fundo de troco R$ 100,00, (−) Sangrias R$ 50,00, (=) Dinheiro esperado **R$ 50,00**, contado R$ 49,00, **Falta de R$ 1,00** em vermelho; a sangria na lista. Ao fechar, a janela do PDV também fecha e a barra de status mostra "Caixa: fechado" 6. Pede de novo a abertura do caixa; Esc não abre o PDV (nenhuma venda com o caixa fechado) | Passos 1 e 3 a 6: OK (roteiro) em 2026-10-17. Passo 2: |

## Fase 5 — Inventário

Casos de uso do TCC: Criar inventário e Atualizar estoque pelo inventário (mais contagem cega com leitor,
divergências com valor — relatório R13 — e cancelamento). Execute na ordem, logado como `admin`, salvo indicação.
Pré-condição comum (cadastrar em Produtos): "Café 500 g" (UN, custo 21,50, estoque inicial 10, código de barras
`7891234567895`), "Arroz 5 kg" (UN, custo 20,00, estoque inicial 6; anote o código interno, aqui **A**) e "Queijo
muçarela" (KG, custo 28,00, estoque inicial 2; código interno **Q**); nenhum inventário aberto; usuário `conferente`
só com **Inventário** (criar em Usuários); `caixa1` só com **PDV** (Fase 4).

Em 2026-10-17, os casos marcados "OK (roteiro)" foram executados por um roteiro descartável que abriu a tela real
(JavaFX) sobre o banco `varesync_test` e a operou disparando os eventos nos próprios campos e botões (texto + Enter,
botões e diálogos de confirmação), com captura de cada tela; os saldos foram conferidos no banco. A venda do CT-F5-06
foi feita pelos services do PDV (a tela do PDV foi conferida na Fase 4). O roteiro não usou o teclado do sistema: a
ordem do Tab e os atalhos pelo teclado (CT-F5-12) ficam para a pessoa.

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F5-01 | Logado como `admin`; nenhum inventário | 1. Menu **Inventário** 2. **Ctrl+N** 3. Descrição vazia; Enter 4. "Contagem semanal"; Enter | 1. Lista vazia com "Nenhum inventário. Crie um em "Novo inventário…"."; embaixo "Selecione um inventário", Aplicar e Cancelar desabilitados 2. Diálogo "Novo inventário" com o campo Descrição focado 3. "Informe a descrição do inventário." no diálogo, que continua aberto 4. Lista com o inventário nº N, Aberto, criado em (data/hora) por Ana Admin, 0 itens; cabeçalho "Inventário nº N — Contagem semanal · Aberto — criado por Ana Admin em …"; a orientação "Conte com a loja fechada…"; aba Contagem; mensagem "Inventário nº N criado. Inclua os produtos a contar."; foco em **Incluir produtos** | Passos 1, 2 e 4: OK (roteiro) em 2026-10-17. Passo 3: |
| CT-F5-02 | CT-F5-01 feito | 1. Ler `7891234567895`; Enter 2. Digitar A; Enter 3. Digitar Q; Enter 4. Ler `7891234567895` de novo 5. Digitar "arroz"; Enter; Esc | 1. "1 produto incluído."; o café aparece na lista (código, código de barras, produto, unidade, Contado "—", em itálico) 2 e 3. Arroz e queijo incluídos 4. "O produto Café 500 g já está neste inventário." em vermelho, com o código selecionado no campo 5. Diálogo "Incluir produto no inventário" com as colunas Código, Produto, Unidade, Preço e **Categoria** (sem a coluna Estoque); Esc fecha sem incluir. Rodapé da lista: "3 itens · 0 contados · 3 a contar" | Passos 1 a 4: OK (roteiro) em 2026-10-17. Passo 5: |
| CT-F5-03 | CT-F5-02 feito (inventário N aberto com os 3 produtos) | 1. Ctrl+N, "Bebidas"; Enter 2. **Por categoria…**, escolher "Mercearia"; Enter 3. **Todos os ativos…**; Sim 4. Selecionar uma linha; **Remover item…**; Sim | 2. Mensagem sem incluir os 3 produtos do inventário N: "… não foram incluídos porque estão em outro inventário aberto: Arroz 5 kg (inventário nº N), …" (ou "Nenhum produto incluído." se a categoria só tiver esses) 3. "X produtos incluídos. … não foram incluídos porque estão em outro inventário aberto: …" (os inativos não entram) 4. "Remover … do inventário nº …?"; depois "… removido do inventário." | |
| CT-F5-04 | Inventário N selecionado (lista à esquerda), aba Contagem | 1. Clicar em **Contar** e ler `7891234567895` 2. "1,5"; Enter 3. "8"; Enter 4. Digitar "queijo"; Enter; "2,345"; Enter 5. Ler `7891000315507` (código que não é deste inventário) 6. Conferir as colunas | 1. "Café 500 g (UN)" ao lado do campo; foco em **Contado** 2. "A quantidade contada deve ser um número inteiro: a unidade do produto não aceita quantidade fracionada (1,5)." 3. "Contagem gravada: Café 500 g = 8 UN."; linha do café com 8, Ana Admin e data/hora (sem itálico); foco volta para Contar 4. Posiciona no queijo; "Contagem gravada: Queijo muçarela = 2,345 KG." 5. "Produto não encontrado." (ou "O produto … não está neste inventário.") 6. **Nenhuma coluna mostra o estoque do sistema** (contagem cega); rodapé "3 itens · 2 contados · 1 a contar" | OK (roteiro) em 2026-10-17 |
| CT-F5-05 | CT-F5-04 feito | 1. Log off; entrar como `conferente` 2. Menu Inventário, inventário N; Contar: ler o café, "8"; Enter 3. No DBeaver: `SELECT p.nome, i.quantidade_contada, i.estoque_sistema, i.diferenca, i.custo_unitario, i.contado_por, i.contado_em FROM inventario_item i JOIN produto p ON p.id = i.produto_id WHERE i.inventario_id = N;` | 1. O menu mostra só Painel e Inventário 2. Antes de gravar: "Café 500 g (UN) — já contado: 8; gravar de novo substitui"; depois, Contado por "Operador …" com a nova hora 3. Café: 8, sistema 10, diferença −2, custo 21.5000, `contado_por` = id do `conferente`; queijo: 2.345, 2.000, 0.345, 28.0000; arroz com os campos da contagem vazios | |
| CT-F5-06 | CT-F5-04 feito; logado como `admin` | 1. Vender 1 café no PDV (Fase 4) 2. Menu Inventário, inventário N, aba **Divergências** 3. Marcar **Só itens com diferença** | 1. Saldo do café 9 (Estoque) 2. "Resultado do inventário (R13)": Arroz em itálico com Sistema "—", Contado "não contado"; Café: Sistema 10, Contado 8, Diferença **−2** em vermelho, Custo R$ 21,5000, Valor **-R$ 43,00** em vermelho; Queijo: 2,000, 2,345, +0,345, R$ 28,0000, R$ 9,66. Totais: Sobra **R$ 9,66** (1 item com sobra), Falta **R$ 43,00** em vermelho (1 item com falta), Resultado **-R$ 33,34** (2 de 3 itens contados), Não contados **1** ("serão ignorados ao aplicar") 3. Some a linha do arroz; os totais continuam | Passos 1 e 2: OK (roteiro) em 2026-10-17. Passo 3: |
| CT-F5-07 | CT-F5-06 feito | 1. **Aplicar…** 2. Não 3. Aplicar…; Sim 4. Menu Estoque: café, queijo e arroz 5. No DBeaver: `SELECT tipo, quantidade, saldo_anterior, saldo_posterior, custo_unitario FROM movimento_estoque WHERE tipo = 'INVENTARIO';` e `SELECT descricao FROM auditoria WHERE acao = 'INVENTARIO_APLICADO';` | 1. "Aplicar o inventário nº N (Contagem semanal)? 2 de 3 itens contados; 2 itens com diferença: sobra de R$ 9,66 (1 item) e falta de R$ 43,00 (1 item). 1 item não contado será ignorado (o saldo dele não muda). Será registrado no estoque um movimento de inventário para cada item com diferença…" 2. Nada muda 3. "Inventário nº N aplicado: 2 itens com diferença tiveram o estoque ajustado."; a lista passa a mostrar aplicados (situação Aplicado, "Aplicado em / por"); a aba Divergências continua com o R13 e "foram ignorados na aplicação" 4. Café **7** (cenário A.8: a venda depois da contagem continua descontada; igualar ao contado daria 8), kardex com "Inventário −2 … Inventário nº N" e custo R$ 21,5000; queijo 2,345; arroz **6** (não contado não muda) 5. Dois movimentos: −2 (9 → 7, 21.5) e +0.345 (2 → 2.345, 28); auditoria "Inventário nº N (Contagem semanal) aplicado: 3 itens, 2 contados, 2 com diferença (sobra de R$ 9,66, falta de R$ 43,00), 1 não contados (ignorados)." | Passos 1, 3 e 4: OK (roteiro) em 2026-10-17. Passos 2 e 5: |
| CT-F5-08 | CT-F5-07 feito | 1. Com **Mostrar aplicados e cancelados** marcado, selecionar o inventário N 2. Aba Contagem | 1. Cabeçalho "Aplicado por Ana Admin em … — somente leitura"; Aplicar e Cancelar desabilitados; sem a orientação de contagem 2. Campos de inclusão e de contagem desabilitados; rodapé "3 itens · 2 contados · 1 não contado" | OK (roteiro) em 2026-10-17 |
| CT-F5-09 | CT-F5-07 feito; inventário "Bebidas" (CT-F5-03) aberto | 1. Inventário "Bebidas": incluir o café pelo código (agora livre) 2. Ctrl+N "Recontagem"; ler o café 3. Com "Recontagem" selecionado: **Cancelar inventário…**; Sim 4. No DBeaver: `SELECT descricao FROM auditoria WHERE acao = 'INVENTARIO_CANCELADO';` | 1. "1 produto incluído." 2. "O produto Café 500 g já está no inventário nº B, que está aberto. Um produto não pode estar em dois inventários abertos." em vermelho 3. "Cancelar o inventário nº … (Recontagem)? O estoque não muda…"; depois "Inventário nº … cancelado."; ele continua selecionado, com "Cancelado — somente leitura" 4. "Inventário nº … (Recontagem) cancelado: 0 itens, 0 contados. O estoque não foi alterado." | Passos 2 e 3: OK (roteiro) em 2026-10-17. Passos 1 e 4: |
| CT-F5-10 | Inventário "Bebidas" aberto, sem nenhuma contagem | 1. Aplicar… | "Nenhum item foi contado. Conte ao menos um item antes de aplicar, ou cancele o inventário." (sem pergunta) | |
| CT-F5-11 | Usuários `caixa1` e `conferente` | 1. Entrar como `caixa1` 2. Entrar como `conferente`, contar um item do inventário "Bebidas" e Aplicar | 1. O menu não mostra Inventário 2. Aplica (a permissão Inventário basta) | |
| CT-F5-12 | Tela Inventário | Só com o teclado: 1. Ctrl+N, Esc 2. Tab até a lista de itens, setas, **Enter** 3. Digitar o contado; Enter 4. Tab até Aplicar…, Espaço, Esc | 1. O diálogo abre e fecha sem o mouse 2. Enter na lista leva o foco para **Contado** do item selecionado 3. Grava e volta o foco para Contar 4. A confirmação fecha sem aplicar | |

## Fase 6 — Relatórios e dados de demonstração (parte 1: gerador, infraestrutura, R01 a R05)

Caso de uso do TCC: Gerar relatórios (RF10) e calcular lucro e margem (RF11). Pré-condição comum: um banco de
demonstração **vazio** (`varesync_demo`, README › "Banco de demonstração") com o sistema apontado para ele e o
supervisor `admin` criado pelo primeiro acesso.

Em 2026-10-17, os casos marcados "OK (roteiro)" foram executados por um roteiro descartável (removido depois) que abriu
as telas reais (JavaFX) sobre o banco `varesync_test`, com as janelas fora da tela, acionando os componentes por eventos
(`Button.fire`, valores nos campos, "Sim" pelo próprio `DialogPane`) e conferindo por capturas (`snapshot`); os PDFs
foram desenhados como imagem e os CSV abertos como texto. A escolha do arquivo (janela "Salvar como" do sistema) e a
abertura no Excel e no leitor de PDF não foram exercitadas: ficam para a pessoa (CT-F6-07 e CT-F6-08).

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F6-01 | Banco vazio; logado como `admin` | 1. Menu **Configurações** › aba **Dados de demonstração** 2. **Gerar dados de demonstração…** 3. **Sim** | 1. Texto explicando o que será criado 2. Confirmação "Gerar os dados de demonstração neste banco? …" 3. Barra de progresso com "Dia N de 60 (dd/MM/aaaa): venda X de Y"; em poucos minutos "Dados de demonstração gerados: 80 produtos e … vendas de … a (ontem)" e a lista dos usuários `maria`, `jose` e `carlos` com as senhas provisórias | OK (roteiro): 80 produtos, 1.974 vendas de 18/08/2026 a 16/10/2026 em 8 s (banco de teste, BCrypt de custo 4); senhas mostradas. Conferir o tempo com BCrypt 12 |
| CT-F6-02 | CT-F6-01 feito | 1. Gerar dados de demonstração… de novo | "Este banco já tem N venda(s). Os dados de demonstração só podem ser gerados num banco sem vendas: crie um banco separado (ex.: varesync_demo, veja o README)…", sem pergunta | OK (roteiro) |
| CT-F6-03 | CT-F6-01 feito | 1. Log off; entrar como `carlos` com a senha mostrada 2. Trocar a senha 3. Ver o menu | 1–2. Troca obrigatória 3. Menu com PDV, Caixa, Produtos, Estoque, Inventário e Relatórios; sem Usuários e Configurações | |
| CT-F6-04 | CT-F6-01 feito; `admin` | 1. Menu **Relatórios**; R01; período = os 60 dias; **Gerar** 2. Clicar no título "Lucro" 3. Marcar **Detalhar por venda**; Gerar 4. Operador "Maria Oliveira"; Gerar | 1. Uma linha por dia (60), colunas Dia, Vendas, Receita, Descontos, CMV, Lucro, Margem %, Ticket médio; linha **Total** com a margem do período (Σ lucro ÷ Σ receita) e "60 linhas · gerado em x s" (< 3 s) 2. Ordena pelo valor 3. Uma linha por venda (Venda nº, Data/hora, Operador) 4. Só as vendas da Maria | OK (roteiro) passos 1 e 3: total R$ 104.942,70, lucro R$ 31.408,84, margem 29,93%; 1.950 vendas (as 24 canceladas fora) |
| CT-F6-05 | CT-F6-04 feito | 1. R05; mesmo período; Gerar 2. Comparar com o total do R01 | 1. Produtos do maior para o menor lucro, com margem, markup e situação (OK, Baixa — ex.: margarina — e Negativa — leite UHT e fralda, vendidos abaixo do custo) 2. Receita, lucro e margem do total iguais aos do R01 | OK (roteiro): R$ 104.942,70, R$ 31.408,84 e 29,93% nos dois |
| CT-F6-06 | CT-F6-01 feito | 1. R02; critério Lucro; "10" primeiros; Gerar 2. R03; **Só abaixo do mínimo**; Gerar 3. R04; produto = código de barras `7896543000019`; Gerar 4. R04; Tipo "Perda"; produto vazio; Gerar | 1. 10 produtos numerados por lucro, com % da receita 2. Só os produtos com saldo abaixo do mínimo; total do valor em estoque 3. Kardex do arroz no período com saldo após, custo, usuário e "Venda nº X"; totais de quantidade, entradas e saídas a custo 4. Só as perdas, com o motivo | OK (roteiro) passos 1 a 3 (10, 3 e 186 linhas) |
| CT-F6-07 | Relatório gerado | 1. **Exportar CSV…**; salvar 2. Abrir no Excel (duplo clique) | 1. "CSV salvo em …" 2. Acentos certos, uma coluna por campo, valores como número (soma funciona), última linha "Total" | OK (roteiro) no texto: BOM UTF-8, `;`, vírgula decimal; falta abrir no Excel |
| CT-F6-08 | Relatório gerado | 1. **Exportar PDF…**; salvar 2. Abrir o PDF | Cabeçalho com a loja, o título e os filtros em todas as páginas; tabela; totais no fim; rodapé "Emitido por … em …" e "Página x de y"; R04 e R05 em página deitada | OK (roteiro) no desenho das páginas (R01 por venda com 38 páginas); falta abrir no leitor de PDF |
| CT-F6-09 | Tela Relatórios | 1. Data inicial depois da final; Gerar 2. R04 com produto "999999"; Gerar 3. Durante a geração | 1. "A data inicial é depois da final." 2. "Produto não encontrado (código 999999)." 3. Gerar e a escolha do relatório desabilitados, indicador girando | OK (roteiro) passo 1 |
| CT-F6-10 | Usuário `maria` (só PDV) | 1. Entrar como `maria` | O menu não mostra Relatórios | |
| CT-F6-11 | CT-F6-01 feito | 1. Menu Estoque (alerta do Painel) 2. Configurações › Diagnóstico › Verificar agora | 1. Alguns produtos abaixo do mínimo 2. "Tudo certo: o saldo de todos os produtos confere com a soma dos movimentos." | |
| CT-F6-12 | Tela Relatórios | Só com o teclado: Tab até o relatório, setas, Tab pelos filtros, **Enter** (Gerar) | Gera sem o mouse (Gerar é o botão padrão) | |

### Fase 6, parte 2 — Painel, R06 a R16, exportação do R11 e do R13

Pré-condição comum: CT-F6-01 feito (dados de demonstração), logado como `admin`. Para os cartões do painel terem
venda hoje, abrir o caixa e fazer uma ou duas vendas no PDV antes.

Em 2026-10-19, os casos marcados "OK (roteiro)" foram executados por um roteiro descartável (removido depois) que abriu
as telas reais (JavaFX) sobre o banco `varesync_test` com os dados de demonstração (2.048 vendas de 20/08 a 18/10),
mais três vendas, um caixa aberto e um inventário aberto no dia; janelas fora da tela, componentes acionados por eventos
(`Button.fire`, `Hyperlink.fire`, valores nos campos), conferência por capturas (`snapshot`), PDFs desenhados como
imagem e CSV abertos como texto. Sem teclado nem mouse do sistema. A janela "Salvar como" do sistema, o Excel e o
leitor de PDF não foram exercitados (ficam para a pessoa, como no CT-F6-07 e CT-F6-08).

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F6-13 | Comum | 1. Entrar (abre o Painel) 2. Ver o canto superior direito | 1. "Olá, Ana Admin!"; cinco cartões (Faturamento, Vendas, Ticket médio, Lucro bruto, Margem) com o valor de hoje, a variação (▲ verde, ▼ vermelho; margem em p.p.) e o valor do mesmo dia da semana anterior até a mesma hora; gráfico "Faturamento e lucro — últimos 30 dias"; "Top 5 do mês por lucro" 2. "Atualizado às HH:mm (x s)" com menos de 2 s | OK (roteiro): R$ 40,44 hoje × R$ 1.542,83 na segunda anterior (▼ 97,38%), margem ▲ 0,35 p.p.; gráfico de 20/09 a 19/10; top 5 com arroz, carne moída, queijo, presunto e alcatra; "Atualizado às 22:27 (0,4 s)" |
| CT-F6-14 | CT-F6-13 | Ver a seção Alertas | "N produtos estão abaixo do estoque mínimo — ver no Estoque", "N produtos têm preço de venda abaixo do custo… — ver em Produtos", "O caixa nº X deste terminal está aberto desde … — ver no Caixa", "N inventário(s) aberto(s) — ver no Inventário"; sem alerta, "Nenhum alerta no momento." | OK (roteiro): 3 abaixo do mínimo, 2 abaixo do custo, caixa aberto, 1 inventário |
| CT-F6-15 | CT-F6-14 | Clicar em cada alerta (voltar ao Painel entre um e outro) | 1. Estoque com "Só abaixo do mínimo" marcado 2. Produtos com a situação "Preço abaixo do custo" (só os ativos com preço < custo) 3. Tela Caixa 4. Tela Inventário; o item do menu fica destacado | OK (roteiro): os quatro destinos; produtos: fralda e leite UHT. Defeito achado e corrigido: o filtro "Preço ab…" aparecia cortado |
| CT-F6-16 | CT-F6-13 | Clicar em um atalho (ex.: Relatórios); voltar ao Painel; **F5** | Abre a rotina; F5 recarrega o painel | OK (roteiro) no atalho; F5 fica para o teclado de verdade |
| CT-F6-17 | Usuário `maria` (só PDV) | Entrar | Painel sem cartões, gráfico e top 5; só o alerta de caixa aberto (se houver) e os atalhos PDV e Caixa | OK (roteiro) |
| CT-F6-18 | Tela Relatórios | 1. R06; período de 60 dias; Gerar 2. Comparar com o R05 | 1. Uma linha por categoria (e "Sem categoria", se houver) com receita, CMV, lucro, margem e % do lucro; total 100,00% 2. Lucro e margem do total iguais aos do R05 | OK (roteiro): 9 categorias |
| CT-F6-19 | Tela Relatórios | 1. R07; critério Receita; Gerar 2. Critério Lucro; Gerar 3. Abrir a lista de critérios | 1. Produtos do maior para o menor com classe A (até 80%), B (até 95%) e C, %, % acumulado 2. Produtos vendidos com prejuízo no fim, classe C, sem % acumulado 3. Só Receita e Lucro | OK (roteiro): 75 produtos nos dois critérios |
| CT-F6-20 | Tela Relatórios | 1. R08; Gerar 2. Comparar com o R01 | 1. Uma linha por forma (dinheiro já sem troco), nº de vendas, valor e %; no total, cada venda conta uma vez 2. Total do valor = receita do R01 | OK (roteiro): 5 formas; R$ 108.981,70 (igual ao total do R10); 2.027 vendas |
| CT-F6-21 | Tela Relatórios | R09; Gerar | Por operador: vendas, receita, ticket médio, descontos, vendas canceladas e itens cancelados | OK (roteiro): 4 operadores |
| CT-F6-22 | Tela Relatórios | 1. R10; critério Receita; Gerar 2. Critério Nº de vendas; Gerar | Grade com uma linha por hora (da primeira à última com venda) e colunas Dom a Sáb + Total; picos nas faixas 11h–13h e 17h–19h | OK (roteiro): 08h a 22h (a venda de hoje às 22h aparece na segunda) |
| CT-F6-23 | Tela Relatórios | 1. R12 (o período some; aparece "Sem venda há [30] dias ou mais"); Gerar 2. Trocar para 1; Gerar 3. "0"; Gerar | 1. Produtos ativos com estoque e sem venda há 30 dias ou mais (os nunca vendidos com "—" na última venda), do maior valor parado para o menor 2. Mais produtos 3. "Os dias sem venda devem estar entre 1 e 3650." | OK (roteiro) passo 1: 5 produtos, campo com 30 (da configuração) |
| CT-F6-24 | Tela Relatórios | 1. R14; Gerar 2. Abrir a lista de tipos 3. Tipo Perda; Gerar | 1. Por tipo, motivo e produto: movimentos, quantidade e valor a custo (saídas negativas); inventário como "Inventário nº X" 2. Todos, Perda, Ajuste de saída, Ajuste de entrada, Inventário 3. Só perdas | OK (roteiro) passos 1 e 2: 15 linhas |
| CT-F6-25 | Tela Relatórios | 1. R15; Gerar 2. R16; Gerar 3. R16 com operador "Maria Oliveira" | 1. Alterações de preço com data, código de barras, preço anterior e novo, variação % e usuário (sem linha de total) 2. Vendas canceladas (pela data do cancelamento) e itens cancelados na venda, com motivo, quem cancelou e quem liberou ("Carlos Pereira (carlos)") 3. Só os da Maria | OK (roteiro) passos 1 e 2: 12 alterações (reajustes dos dias 20 e 45); 127 cancelamentos |
| CT-F6-26 | Relatório gerado (R06 a R16) | Exportar CSV… e PDF… de cada um | CSV com BOM, `;` e vírgula decimal; PDF com cabeçalho, filtros, totais e "Página x de y"; R15 e R16 em página deitada | OK (roteiro): todos os 11 (R07 e R10 nos dois critérios). Defeitos achados e corrigidos: filtros longos cortados na 1ª linha do PDF; "Total (n linhas)" quebrando numa coluna estreita; "Venda nº" quebrando no R16 |
| CT-F6-27 | Inventário aplicado (o dos dados de demonstração) | Inventário › marcar "Mostrar aplicados e cancelados" › escolher › aba Divergências › **Exportar CSV…** e **Exportar PDF…** | Arquivo "R13_inventario_N"; todos os itens (mesmo com "Só itens com diferença" marcado), colunas Sobra (R$) e Falta (R$) com os totais da aba; no cabeçalho do PDF o inventário, quem aplicou, o resultado e os não contados | OK (roteiro): 16 itens, sobra R$ 18,50, falta R$ 4,30, "1 item não contado (ignorado ao aplicar)" |
| CT-F6-28 | Caixa fechado | Caixa › **Ver último fechamento** › **Exportar CSV…** e **Exportar PDF…** (também na janela que abre depois do F12) | "R11_fechamento_caixa_N"; mesmas seções da janela: resumo, dinheiro na gaveta (esperado × contado e situação), vendas por forma com total, sangrias e suprimentos; PDF em A4 de layout próprio | OK (roteiro), pela janela de fechamento com o último caixa: 26 vendas, R$ 1.237,12, sem diferença. Defeito achado e corrigido: os botões encolhiam para "…" com a mensagem do arquivo salvo |

## Fase 7 — Fornecedores e compras

Pré-condição comum: logado como `admin` (ou usuário com FORNECEDORES e COMPRAS), ao menos um produto com estoque.

Em 2026-10-20, os casos marcados "OK (roteiro)" foram executados por um roteiro descartável (removido depois) que abriu
as telas reais (JavaFX) sobre o banco `varesync_test`, com as janelas fora da tela e os componentes acionados por
eventos (texto, `fire`, botões dos diálogos pelo `DialogPane`), sem teclas nem cliques do sistema; conferido pelas
capturas (`snapshot`). A ordem do Tab, o Ctrl+N pelo teclado, o leitor de código de barras e a janela do produto
aberta pelo atalho de reajuste não foram exercitados pela tela.

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F7-01 | — | Menu **Fornecedores** › Novo fornecedor: razão social, CNPJ `12abc34501de35`, telefone `16 33334444`, e-mail com maiúsculas, cidade, UF SP › Salvar | Ao sair do campo, CNPJ vira `12.ABC.345/01DE-35` e telefone `(16) 3333-4444`; "Fornecedor salvo."; na lista com máscara; e-mail em minúsculas | OK (roteiro) |
| CT-F7-02 | — | Novo fornecedor com CNPJ `12.ABC.345/01DE-34` › Salvar | "CNPJ 12.ABC.345/01DE-34 inválido: o dígito verificador não confere." junto do formulário; nada gravado | OK (roteiro) |
| CT-F7-03 | CT-F7-01 | Novo fornecedor com o mesmo CNPJ (sem máscara) › Salvar | "Não foi possível salvar: o CNPJ 12.ABC.345/01DE-35 já pertence ao fornecedor "…" (código 1)." | OK (roteiro) |
| CT-F7-04 | — | Fornecedor com CPF `529.982.247-25`; outro com `11111111111` | O primeiro grava e aparece com máscara; o segundo é recusado (CPF inválido) | Coberto por `FornecedorServiceIT` |
| CT-F7-05 | Fornecedor com entrada | Selecionar › Excluir… | Mensagem de que tem entradas e pergunta se quer inativar; Sim → inativo (esmaecido, com "Mostrar inativos") | Coberto por `FornecedorServiceIT` |
| CT-F7-06 | CT-F7-01 | Busca por "ribeirao" (sem acento), pela cidade e por parte do CNPJ | Encontra o fornecedor nos três casos | Coberto por `FornecedorServiceIT` |
| CT-F7-07 | CT-F7-01 | Menu **Compras** › Nova entrada… (Ctrl+N): fornecedor, NF 1234, valor da nota R$ 50,00 › Criar | Entrada nº N aberta na lista; foco no campo de produto | Entrada criada (o diálogo foi conferido no CT-F7-12) |
| CT-F7-08 | CT-F7-07; Café com saldo 7 a R$ 3,2000 | Ler `7891000315507` (Enter), quantidade 12 (Enter), custo unitário 3,5 (Enter) | Linha com 12 × R$ 3,5000 = R$ 42,00 e custo médio atual R$ 3,2000; rodapé "Soma dos itens: R$ 42,00 · Valor da nota: R$ 50,00 · Diferença: R$ 8,00" (em vermelho) | OK (roteiro) |
| CT-F7-09 | CT-F7-08 | Ler o Café de novo e incluir | "O produto … já está nesta entrada. Altere a quantidade da linha em vez de lançá-lo de novo." | OK (roteiro) |
| CT-F7-10 | CT-F7-08; Queijo KG com saldo 2 a R$ 28,00 | Produto pelo código interno, quantidade 3, modo **Valor total da linha** = 114,00 › Incluir | Custo unitário calculado R$ 38,0000; soma R$ 156,00 | OK (roteiro) |
| CT-F7-11 | CT-F7-10 | **Confirmar entrada…** › Sim | A confirmação traz o aviso "ATENÇÃO: A soma dos itens (R$ 156,00) é diferente do valor total da nota (R$ 50,00)…" (não bloqueia); depois, o resultado: Café R$ 3,2000 → R$ 3,3895 (A.5), Queijo R$ 28,0000 → R$ 34,0000; "1 produto ficou abaixo da margem mínima (15,00%)": Queijo, margem 14,79%, preço sugerido R$ 40,00, botão "Reajustar preço do produto selecionado…" (abre o cadastro do produto). A entrada fica "Confirmada por … — somente leitura", sem os botões de editar, excluir e confirmar | OK (roteiro), com A.5 conferido no resultado. Defeito achado e corrigido: os botões desabilitados cortavam o título ("Dados da not…") |
| CT-F7-12 | CT-F7-11 | Nova entrada… com o mesmo fornecedor e NF 1234 › Criar | No diálogo: "Esta nota já foi lançada (entrada nº 1): NF 1234 de …"; o diálogo continua aberto | OK (roteiro). Defeito achado e corrigido: a instrução e a mensagem do diálogo saíam cortadas com "…" |
| CT-F7-13 | CT-F7-11 | Estoque › Café: kardex | Último movimento "Entrada de compra" +12, saldo após 19, custo R$ 3,5000, origem "Entrada nº 1"; aba Histórico do produto com custo 3,2000 → 3,3895, usuário e data | Coberto por `EntradaServiceIT` (kardex e histórico) |
| CT-F7-14 | CT-F7-11 | Tentar alterar a entrada confirmada | Painel de itens desabilitado; o service recusa qualquer alteração ("já foi confirmada… use Estoque › Ajustar estoque") | Tela OK (roteiro); recusa coberta por `EntradaServiceIT` |
| CT-F7-15 | CT-F7-11 | Relatórios › **R17 — Compras por fornecedor**, período do mês, Fornecedor "Todos" › Gerar | Uma linha: código, fornecedor, CNPJ com máscara, 1 entrada, 2 itens, R$ 156,00, última entrada; total. Com outro fornecedor no filtro: nenhuma linha | OK (roteiro), "Todos"; o filtro por fornecedor coberto por `RelatoriosIT` |
| CT-F7-16 | Produtos com código ≥ 1000 (dados de demonstração não chegam a isso: use um banco com muitos produtos) | R03, R05 ou R12 › Gerar | Coluna Código sem separador de milhar ("5505") na tela, no PDF e no CSV | Na tela, códigos 1 e 2 (roteiro); "5505" coberto por `RelatoriosParte2Test.colunaCodigoSemSeparadorDeMilharNaTelaNoPdfENoCsv` |
| CT-F7-17 | Banco novo (`varesync_demo`) | Configurações › Dados de demonstração › Gerar | Além do que pede 6.14: 3 fornecedores (dois com CNPJ alfanumérico) e uma entrada confirmada por semana de cada um, sempre no mesmo dia; o resumo mostra "Fornecedores: 3 (N entradas de mercadoria confirmadas)"; R17 com 3 linhas | Coberto por `GeradorDemoIT.fornecedoresEEntradasSemanaisComCustoVariando` |
| CT-F7-18 | Usuário só com COMPRAS | Entrar | Menu mostra Compras e não Fornecedores; o inverso com só FORNECEDORES | Coberto por `ItemMenuTest` e pelos testes de permissão dos services |

## Fase 8 — Extras, parte 1 (configurações, promoções, etiqueta de balança, importação por planilha)

Pré-condição comum: logado como `admin` (supervisor). Configurações é só do supervisor; Promoções e Importar planilha
ficam na tela **Produtos** (permissão PRODUTOS).

Em 2026-10-20, os casos marcados "OK (roteiro)" foram executados por um roteiro descartável (removido depois) que abriu
as telas reais (JavaFX) sobre o banco `varesync_test`, com as janelas fora da tela e os componentes acionados por
eventos (texto, `fire`, Enter como `ActionEvent` no campo, botões dos diálogos pelo `DialogPane`), sem teclas nem
cliques do sistema; conferido pelas capturas (`snapshot`, 23 imagens). Não foram exercitados pela tela: o teclado de
verdade (Tab, Ctrl+N, Enter pelo teclado), o leitor de código de barras e a balança de verdade, as janelas "Abrir" e
"Salvar como" do sistema (o arquivo foi carregado pelo mesmo método que a janela usa), arrastar o arquivo para a lista,
a busca de produto pelo diálogo na promoção e a finalização da venda com etiqueta (coberta por `EtiquetaBalancaIT`).

### Configurações (6.13)

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F8-01 | — | Menu **Configurações** › abas Loja, Venda, Relatórios, Balança, Formas de pagamento | Cada aba mostra os valores gravados (os da V2 num banco novo; o nome da loja do primeiro acesso); "Salvar alterações" e "Desfazer" só nas quatro primeiras abas | OK (roteiro) |
| CT-F8-02 | CT-F8-01 | Loja: nome com espaços duplos, CNPJ `11222333000181`, telefone `16 3333-4444`, endereço, mensagem do comprovante; Venda: desmarcar "Permitir vender produto sem saldo", desconto `7,5`; Relatórios: margem `20`, dias `45`; Balança: marcar a leitura › **Salvar alterações** | "Configurações salvas: nome da loja de … para …; …" com cada mudança; CNPJ com máscara, telefone `(16) 3333-4444`, nome sem espaço duplo; auditoria `CONFIGURACAO_ALTERADA` com antes e depois | OK (roteiro). Defeito achado e corrigido: com a mensagem longa, os botões viravam "…" |
| CT-F8-03 | CT-F8-02 | PDV: vender produto sem saldo; dar desconto de 7% na venda | Sem saldo é recusado ("Estoque insuficiente…"); 7% passa sem liberação (limite 7,5%): o valor novo vale na hora | Coberto por `ConfiguracaoServiceIT` (o PDV lê os parâmetros novos) |
| CT-F8-04 | — | Venda: desconto `abc` › Salvar; depois Desfazer | "Desconto máximo do operador: valor inválido ("abc")."; nada gravado; Desfazer volta ao gravado | OK (roteiro) |
| CT-F8-05 | — | Desconto `101`, margem `100`, dias `0`, CNPJ com DV errado, telefone `3333` | Cada um recusado com mensagem; nada gravado | Coberto por `ConfiguracaoServiceIT.valoresInvalidosSaoRecusadosComMensagem` |
| CT-F8-06 | — | Balança: dígitos 4 e tipo Peso | O exemplo muda para "Exemplo: 2 0123 0000345 0 = PLU 123 com 345 g." | OK (roteiro) |
| CT-F8-07 | Produto com PLU 12345 e balança com 5 dígitos | Balança: dígitos 4 › Salvar | Recusado: "Com 4 dígitos de código, o PLU vai até 9999, mas 1 produto tem PLU maior: …" | Coberto por `ConfiguracaoServiceIT` |
| CT-F8-08 | — | Formas de pagamento: "Vale presente" (Outros) › Criar; selecionar PIX › Desativar › Sim | Nova forma na lista; PIX "Desativada" (esmaecida) e fora do pagamento do PDV | OK (roteiro) |
| CT-F8-09 | Só uma forma ativa | Desativar a última | "… é a única forma de pagamento ativa. Ative outra antes de desativar esta." | Coberto por `ConfiguracaoServiceIT` |
| CT-F8-10 | Usuário com todas as permissões, sem ser supervisor | Entrar | Menu sem Configurações; o service recusa | Coberto por `ConfiguracaoServiceIT.soSupervisorAbreEAltera` e `ItemMenuTest` |

### Promoções (6.8)

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F8-11 | Refrigerante R$ 5,49 (custo R$ 3,20) com código de barras | Produtos › **Promoções…** › Nova promoção: código de barras + Enter, preço `4,99`, início hoje, fim hoje + 6 › Criar | O produto aparece com preço e custo; "Promoção nº 1 criada."; na lista "Vigentes": preço de venda, promocional, desconto 9,11%, período, "Vigente" | OK (roteiro). Defeito achado e corrigido: coluna "Criada por" cortada |
| CT-F8-12 | CT-F8-11 | Nova promoção do mesmo produto, `4,49`, mesmo período | No diálogo: "Já existe uma promoção ativa de "Refrigerante…" de … (nº 1, R$ 4,99) que se sobrepõe a este período…"; o diálogo continua aberto | OK (roteiro) |
| CT-F8-13 | CT-F8-11 | Nova promoção `2,99`, de hoje + 10 a hoje + 12 | Confirmação "O preço promocional (R$ 2,99) está abaixo do custo (R$ 3,2000)… Salvar mesmo assim?"; Sim → criada; aparece em "Futuras" e em "Todas" | OK (roteiro) |
| CT-F8-14 | CT-F8-11 | PDV: ler o refrigerante; F7 com o mesmo código | Item a R$ 4,99 com "(promoção)"; F7 mostra "R$ 4,99 / UN (promoção; preço normal R$ 5,49)"; finalizada, o item grava `promocao_id` | Item OK (roteiro); F7 e `promocao_id` cobertos por `PromocaoServiceIT` |
| CT-F8-15 | CT-F8-11 | Desativar a promoção; vender | Preço de venda normal | Coberto por `PromocaoServiceIT` |
| CT-F8-16 | Promoção já usada numa venda | Excluir | "… já foi aplicada em vendas e não pode ser excluída. Você pode desativá-la." | Coberto por `PromocaoServiceIT` |
| CT-F8-17 | — | Filtros Vigentes, Futuras, Encerradas e desativadas, Todas; busca pelo nome | Cada filtro mostra só as suas; encerradas e desativadas esmaecidas | "Todas" OK (roteiro); os demais cobertos por `PromocaoServiceIT.filtrosVigentesFuturasEEncerradas` |

### Etiqueta de balança no PDV (6.9, A.7)

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F8-18 | Leitura ligada (prefixo 2, 5 dígitos, Preço); Queijo KG R$ 39,90 com PLU 123; caixa aberto | PDV: ler `2001230013776` | Item "Queijo muçarela" 0,345 KG × R$ 39,90 = R$ 13,77, com a etiqueta como código | OK (roteiro) |
| CT-F8-19 | CT-F8-18 com o tipo Peso | Ler `2001230003456` | 0,345 KG, total R$ 13,77 | Coberto por `EtiquetaBalancaIT.pesoComCincoDigitosA7` |
| CT-F8-20 | 4 dígitos, Preço | Ler `2012300013770` | PLU 123, R$ 13,77 | Coberto por `EtiquetaBalancaIT` |
| CT-F8-21 | Carne R$ 29,99/kg com PLU 124 | Ler a etiqueta de R$ 10,00 | 0,333 kg e total R$ 10,00 (o valor da etiqueta, sem recalcular para R$ 9,99) | Coberto por `EtiquetaBalancaIT.valorDaEtiquetaNaoERecalculadoA7` |
| CT-F8-22 | CT-F8-18 | Ler `2009990013777` (PLU sem produto) | "Produto de balança não encontrado (PLU 999)."; campo em vermelho, limpo | OK (roteiro) |
| CT-F8-23 | CT-F8-18 | Ler `3*2001230013776` | "A etiqueta de balança já traz a quantidade: leia a etiqueta sem informar a quantidade (F3 ou *)." | OK (roteiro) |
| CT-F8-24 | Leitura desligada | Ler `2001230013776` | "Produto não encontrado (código 2001230013776)." | Coberto por `EtiquetaBalancaIT` |
| CT-F8-25 | CT-F8-18 | Venda completa: etiqueta + F10 em dinheiro | Venda gravada com 0,345 KG e R$ 13,77; baixa de 0,345 no estoque | Coberto por `EtiquetaBalancaIT.precoComCincoDigitosA7PelaVendaCompleta` |

### Importação de produtos por planilha (6.11)

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F8-26 | — | Produtos › **Importar planilha…** › Baixar modelo… | Arquivo `modelo_produtos.csv` com o cabeçalho e dois exemplos; abre no Excel com acentos | Conteúdo coberto por `PlanilhaProdutosTest` e `ImportacaoProdutosIT.modeloEPermissao`; falta abrir no Excel |
| CT-F8-27 | Planilha com 3 produtos, o da linha 3 com custo `abc` | Escolher planilha… | Prévia: linha 3 em vermelho e já selecionada, "Linha 3: Erro — custo: "abc" não é um número válido…"; resumo "2 linhas prontas, 1 com erro…"; Importar desabilitado; nada gravado | OK (roteiro). Defeito achado e corrigido: o erro saía cortado na coluna Situação |
| CT-F8-28 | CT-F8-27 | Marcar "Ignorar as linhas com erro" › Importar › Sim | "2 produtos importados (códigos …); 1 linha(s) com erro ignorada(s)."; os produtos na lista de Produtos ao fechar; estoque inicial no kardex com motivo "Importação" | OK (roteiro); motivo no kardex coberto por `ImportacaoProdutosIT` |
| CT-F8-29 | — | Planilha sem erros com uma categoria nova | Prévia mostra "Categoria (nova)" e "Categorias que serão criadas: …"; Importar cria a categoria e todos os produtos | Coberto por `ImportacaoProdutosIT.planilhaValidaImportaTudoComCategoriaNovaEEstoqueInicial` |
| CT-F8-30 | — | Planilha salva pelo Excel como "CSV (separado por vírgulas)" (Windows-1252) | Acentos corretos; "(Windows-1252, …)" ao lado do nome do arquivo | Coberto por `ImportacaoProdutosIT.windows1252ECategoriaSemDiferenciarMaiusculas`; falta com um arquivo do Excel |
| CT-F8-31 | — | Planilha com código de barras já cadastrado, DV errado, em notação científica (`7,89123E+12`), repetido na planilha, unidade inexistente, categoria inativa | Cada linha com o erro na coluna certa | Coberto por `ImportacaoProdutosIT.codigoDeBarrasRepetidoInvalidoUnidadeECategoriaInativa` |
| CT-F8-32 | — | Arrastar o arquivo .csv do Explorer para a lista | Mesma prévia do CT-F8-27 | Não executado |

## Fase 8 — Extras, parte 2 (busca do Estoque, backup, auditoria, impressão do comprovante, XML de NF-e)

Pré-condição comum: logado como `admin` (supervisor), com o PostgreSQL instalado no computador (o `pg_dump` vem com
ele).

Em 2026-10-21, os casos marcados "OK (roteiro)" foram executados por um roteiro descartável (removido depois) que abriu
as telas reais (JavaFX) sobre o banco `varesync_test`, com as janelas fora da tela e os componentes acionados por
eventos (texto, `fire`, botões dos diálogos pelo `DialogPane`), sem teclas nem cliques do sistema; conferido pelas
capturas (`snapshot`, 17 imagens). O backup rodou com o `pg_dump` real (PostgreSQL 16) e a impressão foi para a
impressora "Microsoft Print to PDF" com destino em arquivo (o PDF gerado foi conferido), não para a impressora padrão.
Não foram exercitados pela tela: o teclado de verdade (F5 no comprovante, Tab), as janelas "Abrir", "Escolher pasta" e
"Salvar como" do sistema (o arquivo foi carregado pelo mesmo método que a janela usa), o botão Sair e o X da janela
principal (o mesmo método do backup ao sair foi chamado direto), uma impressora térmica de verdade, o "Cadastrar
produto…" da NF-e e uma restauração com `pg_restore`.

### Busca do Estoque (defeito da parte 1)

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F8-33 | Produtos "Desconto 50% caixa", "Desconto 500 caixa", "Tampa a_b", "Tampa axb" | Estoque: buscar `50%`; depois `a_b` | Uma linha em cada busca (o `%` e o `_` valem como texto) | OK (roteiro); `\` coberto por `EstoqueServiceIT` |

### Backup (6.12)

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F8-34 | Nenhum backup feito | Painel do supervisor | Alerta "Nenhum backup do banco foi feito ainda — fazer em Configurações › Backup"; o clique abre Configurações na aba Backup | OK (roteiro) |
| CT-F8-35 | CT-F8-34; operador logado | Sair do sistema | Janela "Fazendo o backup automático do banco de dados (um por dia)…" e o sistema fecha; arquivo `varesync_AAAAMMDD_HHmm.backup` na pasta padrão (`~/.varesync/backups`); auditoria "Backup automático (ao sair do sistema)…" com o operador | OK (roteiro; o método do Sair chamado direto) |
| CT-F8-36 | CT-F8-35 | Sair de novo no mesmo dia | Sai sem backup (no máximo um por dia) | Coberto por `BackupIT.automaticoNoMaximoUmPorDiaParaQualquerUsuario` |
| CT-F8-37 | — | Configurações › Backup: pasta, manter 2 › Fazer backup agora (sem salvar) | "Os campos de backup foram alterados: clique em "Salvar alterações" antes…" | OK (roteiro) |
| CT-F8-38 | CT-F8-37 | Salvar alterações › Fazer backup agora três vezes, em minutos diferentes | "Backup gravado em … (100 KB)."; no terceiro, "Apagados os mais antigos: …"; ficam só 2 arquivos; "Será usado o pg_dump C:\Program Files\PostgreSQL\16\bin\pg_dump.exe"; último backup e lista atualizados | OK (roteiro) |
| CT-F8-39 | Backup feito hoje | Painel do supervisor | Sem alerta de backup; com o último backup há 8 dias, "O último backup do banco foi há 8 dias (…)" | Sem alerta OK (roteiro); 8 dias coberto por `BackupIT.alertaDoPainelSoParaOSupervisorComBackupHaMaisDeSeteDias` |
| CT-F8-40 | — | Configurações › Backup: pg_dump inexistente, pasta relativa, manter 0 › Salvar | Cada um recusado com mensagem | Coberto por `ConfiguracaoServiceIT.camposDeBackupValidadosEGravados` |
| CT-F8-41 | Computador sem PostgreSQL (ou pg_dump antigo) | Fazer backup agora | "Não encontrei o pg_dump…" / "O pg_dump encontrado é da versão 9, mas o servidor… é da versão 16…"; nenhum arquivo pela metade | Coberto por `BackupIT.semPgDumpVersaoAntigaOuFalhaDoProgramaDaoMensagemClaraESemArquivo` |
| CT-F8-42 | Um backup gerado | Restaurar num banco novo, seguindo o README | Banco restaurado com os dados; o sistema abre apontando para ele | Não executado |
| CT-F8-43 | Usuário com todas as permissões, sem ser supervisor | Entrar | Sem Configurações no menu; o painel não mostra o alerta de backup; o service recusa o backup manual | Coberto por `BackupIT` |

### Auditoria (6.13)

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F8-44 | Logins, backups e alterações feitos | Configurações › Auditoria | Últimos 7 dias, do mais recente para o mais antigo: data e hora com segundos, usuário "Nome (login)", ação, descrição, terminal; "N registros…" | OK (roteiro) |
| CT-F8-45 | CT-F8-44 | Ação "Backup" › Consultar; selecionar a primeira linha | Só os backups; a descrição inteira aparece embaixo da lista | OK (roteiro) |
| CT-F8-46 | CT-F8-44 | Usuário "Bruno Caixa (caixa1)" › Consultar | Só as ações dele, inclusive "Login recusado"; login inexistente aparece como "(não identificado)" | Coberto por `ConsultaAuditoriaIT` |
| CT-F8-47 | — | Data "31/02/2026" › Consultar | "Informe a data inicial e a final do período." | OK (roteiro). Defeito achado e corrigido: a data impossível virava 28/02 |
| CT-F8-48 | Mais de 5.000 registros no filtro | Consultar | Mostra os 5.000 mais recentes e avisa para reduzir o filtro | Coberto por `ConsultaAuditoriaIT.limitaAsLinhasEAvisa` |

### Impressão do comprovante

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F8-49 | Impressora padrão configurada (de preferência a térmica de 80 mm) | PDV: venda › Ver comprovante › Imprimir (F5) | "Comprovante enviado para a impressora …"; sai no layout de 80 mm (o mesmo do PDF) | Na "Microsoft Print to PDF" com destino em arquivo: OK (roteiro), PDF de 53 KB conferido; falta numa impressora de verdade |
| CT-F8-50 | Sem impressora padrão no Windows | Imprimir | "Nenhuma impressora padrão está configurada no Windows…" | OK (roteiro, com a impressora ausente simulada) |
| CT-F8-51 | Venda da sessão | F9 › reimprimir › Imprimir | Mesmo comprovante | Mesma janela do CT-F8-49 (não exercitado pelo F9) |

### XML de NF-e na entrada de mercadoria (6.11)

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F8-52 | Produtos com os códigos 7891000315507 e 7896004000015 e um queijo KG sem código; fornecedor do CNPJ não cadastrado | Compras › Importar XML da NF-e… (`src/test/resources/nfe/nfe-distribuidora.xml`) | Conferência: emitente "… — será cadastrado como fornecedor", emissão, valor, 4 itens; item 3 com "48 UN (trib.)" (2 caixas); item 2 "(sem GTIN)" em vermelho, sem vínculo; "Gerar entrada" desabilitado | OK (roteiro) |
| CT-F8-53 | CT-F8-52 | Selecionar o item 2 › Escolher produto… › Queijo › Escolher | Linha vinculada; "Todos os itens têm produto."; "Gerar entrada" habilitado | OK (roteiro) |
| CT-F8-54 | CT-F8-53 | Gerar entrada | Entrada ABERTA selecionada, com o fornecedor cadastrado, NF 1234, emissão e valor da nota; café com 12 (itens 1 e 4 somados) a R$ 12,4167; latas 48 a R$ 2,5000; "Soma confere com a nota" | OK (roteiro) |
| CT-F8-55 | CT-F8-54 | Importar o mesmo XML de novo | "Esta nota já foi lançada (entrada nº 1)…"; "Gerar entrada" desabilitado | OK (roteiro) |
| CT-F8-56 | — | Importar um XML que não é de NF-e | "O arquivo não é o XML de uma NF-e (não tem o grupo infNFe)." | OK (roteiro) |
| CT-F8-57 | Item sem produto cadastrado | Cadastrar produto… › Salvar › fechar | O cadastro abre com o nome da nota, o código de barras e o custo; o item fica vinculado ao produto novo | Não executado pela tela (o vínculo pelo código cadastrado está em `ImportacaoNfeIT`) |
| CT-F8-58 | CT-F8-54 | Confirmar entrada | Estoque e custo médio atualizados como na Fase 7 | Coberto por `ImportacaoNfeIT.geraEntradaAbertaComFornecedorNovoItensSomadosEUnidadeTributavel` |

## Fase 9 — Qualidade e entrega, parte 1 (README, empacotamento, instalação do zero, métricas)

Em 2026-10-22, os casos marcados "OK (roteiro)" foram executados por um roteiro descartável (removido depois) que abriu
o sistema real (`App`) com as bibliotecas da pasta `app` do pacote gerado, uma pasta de usuário vazia e o banco
`varesync_test` esvaziado, com as janelas fora da tela e os componentes acionados por eventos (texto, `fire`, o botão OK
do diálogo pelo `DialogPane`), sem teclas nem cliques do sistema; conferido por 5 capturas (`snapshot`). Os casos
"OK (exe)" abriram o próprio `VareSync.exe` (a janela de login apareceu na tela e o processo foi encerrado logo depois
da linha do log).

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F9-01 | JDK 21 e Maven (README, seção 1) | 1. Na pasta do projeto, `mvn -Pempacotar clean package` | Termina sem erro; `target\dist\VareSync` tem `VareSync.exe`, `app` e `runtime` (cerca de 190 MB) | OK em 2026-10-22: 290 testes unitários verdes, pasta com 190 MB |
| CT-F9-02 | Pacote gerado; PostgreSQL com o banco vazio; sem `~/.varesync` | 1. Abrir `VareSync.exe` 2. Na tela **Conexão com o banco**, digitar a senha e clicar **Salvar** 3. No assistente, preencher loja, nome, login `admin`, senha e confirmação e clicar **Criar supervisor** 4. Fechar o aviso 5. Entrar com `admin` | 1. `config.properties` criado sem senha; a tela de conexão abre com "Informe a senha do banco." 2. As 3 migrations são aplicadas e abre o assistente 3. Aviso "Supervisor criado. Entre com o login "admin"…" 4. Login com o nome da loja e o usuário preenchido 5. Janela principal com o Painel; todos os itens do menu visíveis em 1280×720 e a barra de status embaixo (usuário, terminal, caixa fechado, banco conectado, data/hora) | OK (roteiro), com as bibliotecas do pacote: as 5 telas conferidas nas capturas. A primeira execução achou a barra de status fora da janela em 1280×720 (corrigido; ver PROGRESSO) |
| CT-F9-03 | Computador **sem JDK** e sem o projeto, com PostgreSQL e o banco criado | 1. Copiar a pasta `VareSync` para `C:\VareSync` 2. Criar o atalho 3. Abrir pelo atalho e seguir o CT-F9-02 | O sistema abre com o Java do próprio pacote; aviso do SmartScreen na primeira vez ("Mais informações › Executar assim mesmo") | Parcial (exe): o `VareSync.exe` abriu com o Java embutido ("Java 21.0.12.1" no log) nesta máquina, que tem JDK; falta conferir num computador sem JDK |
| CT-F9-04 | Pacote gerado; banco migrado com o supervisor | 1. Abrir `VareSync.exe` e cronometrar até a tela de login | Tela de login em menos de 5 s; o log registra "Primeira tela aberta em N ms" | OK (exe), 3 aberturas: 1,95 s, 1,75 s e 1,73 s pelo log; 2,1 s, 1,8 s e 1,8 s pelo cronômetro externo |
| CT-F9-05 | Logado com PDV, ESTOQUE e INVENTARIO | 1. Na abertura do caixa, digitar `abc` no fundo de troco 2. No ajuste de estoque, `1,2,3` na quantidade 3. No PDV, F3 com `x` 4. Na contagem do inventário, `?` | Mensagens "Fundo de troco: valor inválido ("abc").", "Quantidade: valor inválido ("1,2,3").", "Quantidade: valor inválido ("x")." e "Quantidade contada: valor inválido ("?")." | |
| CT-F9-06 | Caixa aberto; 3 produtos com código de barras | 1. Vender 3 itens e pagar em dinheiro só com o teclado (leitor, F10, forma, valor, Enter) | Venda finalizada sem usar o mouse (métrica 9.4) | Não refeito nesta fase (feito na Fase 4 com o Robot do JavaFX; ver PROGRESSO › Métricas) |
| CT-F9-07 | Banco `varesync_demo` (README, seção 6) | 1. Gerar os dados de demonstração 2. Log off e entrar como `maria` com `demo1835`, `jose` com `demo9076` e `carlos` com `demo6999` | Cada um entra e é obrigado a trocar a senha; `maria` e `jose` só veem Painel, PDV e Caixa; `carlos` vê todas as rotinas, menos Usuários e Configurações | Senhas conferidas por `GeradorDemoIT`; pela tela, falta a pessoa executar |
| CT-F9-08 | README aberto | 1. Seguir a seção 7 (restauração num banco novo `varesync_restaurado`) com um backup do sistema | `pg_restore` termina sem erro; apontando `db.url` para o banco restaurado, o sistema abre com os mesmos dados | |

## Fase 9 — Qualidade e entrega, parte 2 (material do TCC e da banca, ícone)

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F9-09 | Pacote gerado de novo (`mvn -Pempacotar clean package`) | 1. Ver `target\dist\VareSync\VareSync.exe` no Explorer 2. Abrir o `.exe` e olhar a barra de título e a barra de tarefas 3. Entrar, abrir um produto (janela modal) e o PDV | 1. O `.exe` tem o ícone do VareSync (quadrado azul com "V" branco), não o ícone padrão do Java 2 e 3. O mesmo ícone na janela de login, na principal, nas janelas modais e na barra de tarefas | Passo 1: OK em 2026-10-22 (ícone extraído do `VareSync.exe` pelo Windows, 32 px, igual ao do sistema). Passos 2 e 3: não executados (abrir o `.exe` mostra a janela na tela) |
| CT-F9-10 | `docs/DEMO.md`, seção 1 (preparação) feita | 1. Executar a seção 2 de `docs/DEMO.md` cronometrando cada passo 2. Comparar as telas com `docs/telas/` | 1. Tudo em até 10 minutos; total da venda R$ 69,53 e troco R$ 10,47; café com saldo = contado − 1 depois de aplicar o inventário; NF-e 7701 gera a entrada com 4 itens e cadastra "Laticinios Vale do Pardo Ltda" 2. Mesmas telas e valores (os saldos podem variar com o dia da geração) | |

## Fase 10 — Robustez e segurança, parte A (sessão revalidada, bloqueio por inatividade, cancelamentos na venda)

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F10-01 | Operador com PDV e Produtos logado no computador A; supervisor no computador B | 1. No A, abrir Produtos 2. No B, tirar a permissão Produtos do operador e salvar 3. No A, clicar em Buscar | 3. Aviso "Você não tem permissão para usar a rotina Produtos. Procure o supervisor."; o menu deixa de mostrar Produtos; o operador continua logado (PDV e Caixa funcionam) | OK em 2026-10-24 pelo roteiro de tela (eventos nos componentes, capturas `01` e `02`): menu antes [Painel, PDV, Caixa, Produtos], depois [Painel, PDV, Caixa], aviso com a mensagem esperada |
| CT-F10-02 | Operador logado no A com uma venda em andamento no PDV | 1. No B, inativar o operador 2. No A, ler outro produto | 2. O PDV fecha, a venda é abandonada (auditoria `VENDA_ABANDONADA` "… (sessão encerrada: acesso alterado pelo supervisor)") e o login abre com "Seu acesso foi alterado pelo supervisor. Entre novamente." | OK em 2026-10-24 pelo roteiro de tela (captura `10`) |
| CT-F10-03 | Operador logado no A | 1. No B, Usuários › Redefinir senha do operador 2. No A, qualquer ação | 2. Volta ao login com o aviso do CT-F10-02; com a senha provisória, pede a troca | Coberto por `SessaoRevalidadaIT.senhaRedefinidaPeloSupervisorVaiParaOLoginNaProximaAcao`; pela tela, não executado |
| CT-F10-04 | Configurações › Segurança com 1 minuto (supervisor; vale a partir do próximo login); operador logado com um item no PDV | 1. Não mexer no teclado nem no mouse por 1 minuto | 1. Aparece "Sessão bloqueada" por cima do PDV, com o nome do usuário e o campo de senha; o conteúdo fica escondido | OK em 2026-10-24 pelo roteiro de tela: bloqueou 58 s depois da última ação (verificação a cada segundo; capturas `03` e `04`) |
| CT-F10-05 | Tela bloqueada (CT-F10-04) | 1. Digitar uma senha errada e Desbloquear 2. Digitar a senha certa | 1. "Usuário ou senha inválidos." 2. A tela volta ao ponto exato: PDV com o mesmo item e o mesmo total | OK em 2026-10-24 pelo roteiro de tela (capturas `05` e `06`: 1 item, R$ 5,49) |
| CT-F10-06 | Tela bloqueada | 1. Errar a senha 5 vezes | 1. "Usuário bloqueado por excesso de tentativas. Tente novamente às HH:mm."; o login também fica bloqueado por 5 minutos | OK em 2026-10-24 pelo roteiro de tela (captura `07`; `bloqueado_ate` gravado) |
| CT-F10-07 | Tela bloqueada com venda em andamento no PDV | 1. Trocar usuário 2. Responder Sim | 1. Pergunta "Há uma venda em andamento (1 item, R$ 5,49). Abandonar a venda e trocar de usuário?" 2. Login; auditoria `VENDA_ABANDONADA` "… (troca de usuário na tela bloqueada)". Respondendo Não, a tela continua bloqueada | Sim: OK em 2026-10-24 pelo roteiro de tela (capturas `08` e `09`); Não: não executado |
| CT-F10-08 | Operador sem CANCELAR_VENDA; "Cancelar item pede liberação" ligado | 1. Vender 2 itens, cancelar o item 2 com a liberação do supervisor, finalizar 2. Cancelar a venda (F9) com a liberação 3. Relatórios › R16 | 3. Item: cancelado pelo operador, liberado pelo supervisor; venda: cancelada pelo operador, liberada pelo supervisor (colunas novas da venda) | OK em 2026-10-24 pelo roteiro de tela (captura `11`); registros anteriores à V4 conferidos em `RelatoriosIT` |

## Fase 10 — Robustez e segurança, parte B (kardex, unidades, senha, PDF grande, inventário grande, backup conferido)

Conferência pela tela em 2026-10-24 por um roteiro descartável (fora do repositório): o sistema real aberto sobre o
banco `varesync_test` com os dados de demonstração, pasta de usuário temporária e configuração sem BOM (a URL conferida
antes de abrir), janelas fora da tela, componentes acionados direto (sem teclado nem mouse do sistema) e capturas por
snapshot `00` a `10`.

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F10-09 | Produto com movimentos antes e dentro dos últimos 30 dias (dados de demonstração) | 1. Estoque › buscar o produto 2. Rolar o kardex até o topo | 2. A primeira linha, em negrito, é "Saldo anterior" com a data do início do período e, em "Saldo após", o saldo depois do último movimento anterior (igual ao saldo anterior do primeiro movimento do período); sem quantidade, custo, usuário nem origem | OK em 2026-10-24 pelo roteiro de tela (captura `01`: Cerveja pilsen, saldo anterior 95 = saldo anterior da venda seguinte) |
| CT-F10-10 | Mesmo produto | 1. Mudar o período para começar depois de todos os movimentos 2. Filtrar | 2. Só a linha "Saldo anterior", com o saldo atual | Coberto por `EstoqueServiceIT.kardexComecaPeloSaldoAnteriorAoPeriodo`; pela tela, não executado |
| CT-F10-11 | Dados de demonstração | 1. Relatórios › R04, 60 dias, Produto = código do produto 2. Gerar 3. Exportar PDF e CSV | 2. Primeira linha "Saldo anterior" (sem quantidade nem valores; não entra nos totais) 3. A linha aparece também no PDF e no CSV | 2. OK em 2026-10-24 pelo roteiro de tela (captura `02`: saldo anterior 66, 231 linhas); 3. não executado (o PDF e o CSV usam as mesmas colunas da tela) |
| CT-F10-12 | Produto KG em uso (dados de demonstração) | 1. Produtos › Unidades… 2. Selecionar KG | 2. Sigla e "Fracionada" desabilitadas, Excluir desabilitado, descrição editável; aviso "Usada por N produtos: só a descrição pode mudar, e ela não pode ser excluída." | OK em 2026-10-24 pelo roteiro de tela (captura `04`: 15 produtos) |
| CT-F10-13 | Tela Unidades | 1. Nova unidade: sigla "pc", descrição "Peça avulsa" 2. Salvar 3. Excluir e confirmar | 2. "Unidade PC salva." (sigla em maiúsculas) e a unidade aparece no cadastro de produto 3. "Unidade PC excluída." | 1–3 OK em 2026-10-24 pelo roteiro de tela (captura `05`); a lista do cadastro de produto, não conferida pela tela (coberta por `UnidadeServiceIT.unidadeNovaValeNoCadastroDeProdutoComARegraDeFracionada`) |
| CT-F10-14 | Tela Unidades | 1. Sigla "M²" ou "ABCDEFG" 2. UN: trocar a sigla ou excluir | 1. "A sigla deve ter de 1 a 6 letras (sem acento) ou números, como KG ou PCT." 2. Sigla travada e Excluir desabilitado | Coberto por `UnidadeServiceIT`; pela tela, não executado |
| CT-F10-15 | Supervisor | 1. Configurações › Segurança: tamanho mínimo 8 2. Salvar alterações 3. Usuários › Novo usuário com a senha "abc123" 4. Salvar | 2. "Configurações salvas: tamanho mínimo da senha de 6 caracteres para 8 caracteres." 3. A senha mostra a dica "ao menos 8 caracteres" 4. "A senha deve ter ao menos 8 caracteres e ser diferente do login." | OK em 2026-10-24 pelo roteiro de tela (capturas `06` e `07`) |
| CT-F10-16 | Tamanho mínimo 8 e "Exigir letras e números"; um usuário com senha antiga de 7 caracteres | 1. Esse usuário entra com a senha antiga 2. Alterar senha para "abcdefgh" 3. Para "abcdefg1" | 1. Entra 2. "A senha deve ter ao menos 8 caracteres, com letras e números, e ser diferente do login." 3. Aceita | Coberto por `ConfiguracaoServiceIT.politicaDeSenhaValeParaSenhasNovasETrocadasEAsAntigasContinuam` e `SenhasTest`; pela tela, não executado |
| CT-F10-17 | Dados de demonstração | 1. Relatórios › R04, 60 dias, sem produto 2. Gerar 3. Exportar PDF… 4. Responder Não | 3. Pergunta "O PDF terá cerca de 242 páginas (7979 linhas). Para um arquivo menor, filtre por um produto, um tipo de movimento ou um período menor. … Gerar o PDF mesmo assim?" 4. Nada é gerado (a janela de salvar não abre). Respondendo Sim, segue para a janela de salvar | 1–4 OK em 2026-10-24 pelo roteiro de tela (captura `03`); "Sim", não executado (abriria a janela de salvar do sistema) |
| CT-F10-18 | Inventário aberto com 5 mil itens (Todos os ativos) | 1. Contar: ler o código de um item, quantidade 2, Gravar (repetir com outros itens) | 1. A linha do item mostra o contado, quem e quando; o resumo passa a "… N contados"; a aba não pisca nem perde a posição; cada gravação em menos de 300 ms | OK em 2026-10-24 pelo roteiro de tela (captura `08`): 6 gravações, de 38 a 70 ms do clique no Gravar até a tela atualizada (inventário com 5.080 itens) |
| CT-F10-19 | Configurações › Backup com um pg_dump cuja pasta não tem um pg_restore que leia o arquivo (simulado: cópia da pasta bin com o pg_restore trocado) | 1. Fazer backup agora | 1. "O backup não foi concluído: o arquivo gerado não passou na conferência (pg_restore --list) e foi descartado. …"; nenhum arquivo na pasta; Painel continua sem "último backup" (auditoria sem `BACKUP_REALIZADO`) | OK em 2026-10-24 pelo roteiro de tela (captura `09`) |
| CT-F10-20 | pg_dump da instalação do PostgreSQL | 1. Fazer backup agora | 1. "Backup gravado em …"; o arquivo aparece na lista; `BACKUP_REALIZADO` na auditoria | OK em 2026-10-24 pelo roteiro de tela (captura `10`); também em `BackupIT` com o pg_dump e o pg_restore de verdade |

## Fase 12 — Clientes, crédito e cashback, parte A (cadastro, identificação na venda, LGPD e R18)

Conferência pela tela em 2026-10-24 por um roteiro descartável (fora do repositório): o sistema real aberto sobre o
banco `varesync_test` limpo, com 3 clientes, 2 produtos e 3 vendas identificadas criados pelos services; pasta de
usuário temporária e configuração sem BOM (a URL conferida antes de abrir), janelas fora da tela, componentes acionados
direto (sem teclado nem mouse do sistema) e capturas por snapshot `01` a `13`. 19 verificações, todas OK.

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F12-01 | Usuário com a permissão Clientes (ou supervisor) | 1. Menu Clientes 2. Novo cliente (Ctrl+N): nome "Maria da Silva", CPF 52998224725, telefone 16999998888, nascimento 25/03/1985, CEP 14010000, cidade e UF, marcar "Aceita receber ofertas" 3. Salvar | 2. Ao sair dos campos, CPF, telefone e CEP ganham máscara 3. "Cliente salvo."; a lista mostra o cliente; abaixo do consentimento, "Consentimento registrado em dd/mm/aaaa hh:mm." | OK em 2026-10-24 pelo roteiro de tela (captura `01`, cliente criado pelo service); a máscara ao sair do campo não foi exercitada (depende do foco pelo teclado) |
| CT-F12-02 | Tela Clientes | 1. Novo cliente com o CPF 529.982.247-24 2. Salvar | 2. "CPF 529.982.247-24 inválido: o dígito verificador não confere."; nada é gravado | OK em 2026-10-24 pelo roteiro de tela (captura `03`) |
| CT-F12-03 | Maria da Silva cadastrada com o CPF 529.982.247-25 | 1. Novo cliente com o mesmo CPF 2. Salvar | 2. "Não foi possível salvar: o CPF 529.982.247-25 já pertence ao cliente "Maria da Silva" (código N)." | OK em 2026-10-24 pelo roteiro de tela (captura `04`) |
| CT-F12-04 | Clientes cadastrados, um com vendas | 1. Buscar "jose" (sem acento), parte do CPF ("98224") e parte do telefone ("99999") 2. Excluir o cliente sem vendas 3. Excluir o cliente com vendas | 1. Acha pelo nome, pelo CPF e pelo telefone 2. "Cliente excluído." 3. Oferece inativar ("… tem N vendas. Você pode inativá-lo …"); inativo some da lista e volta com "Mostrar inativos" | Coberto por `ClienteServiceIT`; pela tela, só a lista (captura `01`) |
| CT-F12-05 | Caixa aberto; cliente Maria cadastrada | 1. PDV: ler um produto 2. F11, digitar "maria", Enter | 2. A lista mostra Maria com o CPF mascarado (***.982.247-**); Identificar (Enter ou duplo clique) põe "Cliente: Maria da Silva (***.982.247-**)" no painel do PDV | OK em 2026-10-24 pelo roteiro de tela (captura `10`) |
| CT-F12-06 | PDV com venda em andamento | 1. F11, Cadastro rápido…: nome "Rafael Lima", CPF 123.456.789-09 2. Cadastrar e identificar | 2. O cliente é cadastrado (com a permissão PDV) e identificado na venda: "Cliente: Rafael Lima (***.456.789-**)" | OK em 2026-10-24 pelo roteiro de tela (captura `11`) |
| CT-F12-07 | PDV com cliente identificado | 1. F11, digitar o CPF completo de outro cliente, Enter 2. F11, Remover cliente | 1. Troca direto (sem escolher na lista) 2. "Sem cliente (F11)" no painel | 1. OK em 2026-10-24 pelo roteiro de tela (captura `12`); 2. coberto por `ClienteVendaIT`, não executado pela tela |
| CT-F12-08 | PDV com Maria identificada | 1. F10, pagar em dinheiro 2. Ver comprovante 3. F9, reimprimir | 2. O comprovante mostra "Cliente: Maria da Silva" e "CPF: ***.982.247-**" (nunca o CPF inteiro) 3. Igual na reimpressão | 2. OK em 2026-10-24 pelo roteiro de tela (captura `13`); 3. coberto por `ClienteVendaIT` |
| CT-F12-09 | Cliente com compras | 1. Clientes › selecionar o cliente › aba Compras | 1. Total gasto, número de compras, ticket médio e última compra; histórico (venda, data, itens, valor) e produtos mais comprados; vendas canceladas e sem cliente ficam de fora | OK em 2026-10-24 pelo roteiro de tela (captura `02`: R$ 30,24 em 2 compras, ticket R$ 15,12) |
| CT-F12-10 | Vendas identificadas no período | 1. Relatórios › R18 Clientes, período 2. Gerar 3. Exportar PDF e CSV | 2. Por cliente: compras, valor, ticket médio e última compra; CPF mascarado; total com o ticket médio do agregado | 2. OK em 2026-10-24 pelo roteiro de tela (captura `09`); 3. PDF coberto por `RelatorioPdfTest`, não executado pela tela |
| CT-F12-11 | Cliente com compras | 1. Aba LGPD › Exportar PDF… e Exportar CSV… 2. Abrir os arquivos 3. Configurações › Auditoria | 2. Cadastro, resumo das compras, compras e produtos comprados 3. "Dados do cliente nº N exportados a pedido do titular (…)", sem o nome nem o CPF | Coberto por `ClienteServiceIT` e `RelatorioPdfTest`; pela tela, só a aba (captura `05`): a janela "Salvar como" do sistema não é aberta pelo roteiro |
| CT-F12-12 | Supervisor; cliente com uma venda | 1. Aba LGPD › Anonimizar… 2. Digitar só o primeiro nome e confirmar 3. Digitar o nome completo e confirmar 4. Aba Compras | 2. "Para confirmar, digite o nome do cliente exatamente como está no cadastro."; nada muda 3. Nome "Cliente anonimizado nº N", sem CPF, contatos, endereço, nascimento nem observação; situação Anonimizado; Salvar e Reativar desabilitados 4. A venda continua; a auditoria não guarda o nome antigo. Operador sem supervisor não vê o botão | 2–4 OK em 2026-10-24 pelo roteiro de tela (capturas `06` a `08`); operador sem supervisor coberto por `ClienteServiceIT` |

## Fase 12 — Clientes, crédito e cashback, parte B (crédito do cliente, cashback, R19 e gerador)

Conferência pela tela em 2026-10-27 por um roteiro descartável (fora do repositório): o sistema real aberto sobre o
banco `varesync_test` limpo, com 1 produto (Arroz 5 kg, R$ 10,00, código 7896006716112), 2 clientes e o caixa aberto,
criados pelos services; pasta de usuário temporária e configuração sem BOM (a URL conferida antes de abrir), janelas
fora da tela, componentes acionados direto (sem teclado nem mouse do sistema) e capturas por snapshot `01` a `13`.
19 verificações, todas OK. As capturas acharam 2 defeitos visuais, corrigidos antes da execução final (PROGRESSO,
Métricas).

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F12-13 | Supervisor | 1. Configurações › Fidelidade 2. Marcar "Gerar cashback…", 1% e compra mínima 0 3. Salvar alterações 4. Auditoria | 1. Vem desligada, 1,00 e 0,00 3. "Configurações salvas: cashback de desligado para ligado." 4. `CONFIGURACAO_ALTERADA` com a mudança | 1–3 OK em 2026-10-27 pelo roteiro de tela (captura `01`); 4. coberto por `ConfiguracaoServiceIT` |
| CT-F12-14 | Fidelidade ligada com 1%; Maria com saldo R$ 0,00; caixa aberto | 1. PDV: 10 × Arroz (R$ 100,00) 2. F11 com o CPF 529.982.247-25 3. F10, dinheiro, finalizar 4. Ver o comprovante | 2. Painel: "Cliente: Maria da Silva (***.982.247-**) · crédito R$ 0,00" 3. Pagamento mostra "Crédito do cliente disponível: R$ 0,00." 4. "Cashback gerado R$ 1,00" e "Saldo de crédito R$ 1,00" (**aceite F12**) | OK em 2026-10-27 pelo roteiro de tela (captura `02`) |
| CT-F12-15 | Maria com saldo R$ 1,00 | 1. PDV: 1 × Arroz (R$ 10,00), F11 com o CPF 2. F10, forma "Crédito do cliente", valor 1,01 3. Valor 1,00 4. Dinheiro 9,00 e finalizar | 1. Painel com "crédito R$ 1,00" 2. "Saldo de crédito insuficiente: o cliente tem R$ 1,00 disponível." (**aceite F12**, limite) 4. Comprovante com "Crédito do cliente R$ 1,00", "Cashback gerado R$ 0,09" e "Saldo de crédito R$ 0,09" (**aceite F12**, uso) | OK em 2026-10-27 pelo roteiro de tela (capturas `03` a `06`) |
| CT-F12-16 | Venda sem cliente identificado | 1. F10, forma "Crédito do cliente" | 1. "Para pagar com o crédito do cliente, identifique o cliente (F11)." | Coberto por `CreditoClienteIT` e `RecebimentoTest`; não executado pela tela |
| CT-F12-17 | Venda de R$ 100,00 de Maria com cashback de R$ 1,00 | 1. F9, cancelar a venda (motivo, liberação se preciso) 2. Clientes › Maria › Crédito | 2. Extrato com "Estorno de cashback" de -R$ 1,00 e saldo R$ 0,00 (**aceite F12**); se o crédito já foi usado, o saldo fica negativo, em vermelho | Coberto por `CreditoClienteIT` (inclusive o saldo negativo); pela tela, não executado (o F9 com cancelamento é da Fase 4) |
| CT-F12-18 | Maria com saldo R$ 0,09 | 1. Clientes › Maria › LGPD › Anonimizar… 2. Digitar o nome e confirmar | 2. "O cliente tem saldo de crédito de R$ 0,09 e não pode ser anonimizado. Use o saldo numa venda ou zere-o por um ajuste (aba Crédito, só o supervisor) antes." (**aceite F12**) | OK em 2026-10-27 pelo roteiro de tela (captura `08`) |
| CT-F12-19 | Supervisor; Maria com saldo R$ 0,09 | 1. Aba Crédito › Ajustar…: -0,09, motivo "Zerado a pedido da titular" 2. LGPD › Anonimizar com o nome 3. Aba Crédito | 1. Saldo R$ 0,00; o ajuste entra no extrato; selecionar um movimento mostra o detalhe com o usuário 2. Cliente anonimizado 3. O extrato continua (4 movimentos) | OK em 2026-10-27 pelo roteiro de tela (capturas `07`, `10` a `12`); operador sem supervisor (sem o botão e recusado no service) coberto por `CreditoClienteIT` |
| CT-F12-20 | Cliente com saldo diferente de zero | 1. Relatórios › R19 Saldos de crédito › Gerar 2. Exportar PDF e CSV | 1. Clientes com saldo ≠ 0 (CPF mascarado, situação, saldo, último movimento) e o total devido | 1. OK em 2026-10-27 pelo roteiro de tela (captura `09`); 2. PDF coberto por `RelatorioPdfTest`, não executado pela tela |
| CT-F12-21 | Supervisor | 1. Configurações › Diagnóstico › Verificar agora | 1. "Tudo certo: o saldo de crédito de todos os clientes confere com a soma do extrato." e tabela vazia | OK em 2026-10-27 pelo roteiro de tela (captura `13`) |
| CT-F12-22 | Banco vazio (varesync_demo recriado) | 1. Gerar os dados de demonstração 2. Clientes, R18 e R19 | 1. O resumo mostra 150 clientes, as vendas identificadas e os usos de crédito; a fidelidade fica ligada com 1% 2. Clientes com compras e saldos | Coberto por `GeradorDemoIT` (150 clientes, ~40% das vendas identificadas, usos de crédito, R18 e R19 com linhas); não executado pela tela |


## Fase 13 — Devoluções e trocas, parte A (banco, regras, tela Devoluções, F9 do PDV, comprovante e R20)

Conferência pela tela em 2026-10-27 por um roteiro descartável (fora do repositório): o sistema real aberto sobre o
banco `varesync_test` limpo, com 2 produtos (Refrigerante cola 2 L a R$ 5,49, código 7891000315507; Arroz 5 kg a
R$ 10,00, código 7896006716112), 2 clientes (Maria da Silva; José Antônio, anonimizado), o caixa aberto e 5 vendas
(2 de 06/10 e 3 de hoje, uma com desconto de R$ 0,82 na venda e outra de R$ 100,00 da Maria com cashback de R$ 1,00),
criados pelos services; pasta de usuário temporária e configuração sem BOM (a URL conferida antes de abrir), janelas
fora da tela, componentes acionados direto (sem teclado nem mouse do sistema) e capturas por snapshot `01` a `18`.
26 verificações, todas OK. As capturas acharam 2 defeitos visuais, corrigidos antes da execução final (PROGRESSO,
Métricas).

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F13-01 | Supervisor | 1. Configurações › Venda › Prazo de devolução: trocar 30 por 15 › Salvar alterações | 1. "Configurações salvas: prazo de devolução de 30 dias para 15 dias." e auditoria | OK em 2026-10-27 pelo roteiro de tela (captura `01`); validação de 0 a 365 em `ConfiguracaoServiceIT.prazoDeDevolucaoValidadoEAuditado` |
| CT-F13-02 | Vendas finalizadas | 1. Menu Devoluções 2. Buscar por número, por período e por cliente (nome ou parte do CPF) | 1. Vendas finalizadas dos últimos 30 dias, a mais recente primeiro, com o já devolvido 2. A busca filtra | 1. OK em 2026-10-27 pelo roteiro de tela (captura `02`); 2. número pela tela; período e cliente em `DevolucaoServiceIT.buscaDaTelaEReimpressaoDoComprovante` |
| CT-F13-03 | Caixa aberto; venda de 3 × R$ 5,49 em dinheiro | 1. Devolver itens… 2. Devolver 1, motivo, Dinheiro 3. Registrar (F10) › Sim 4. Mostrar o comprovante | 1. Itens com vendida, já devolvida e valor na hora 2. Total R$ 5,49 3. Estoque +1 e dinheiro esperado −R$ 5,49 4. Comprovante "DEVOLUÇÃO DE MERCADORIA" com "NÃO É DOCUMENTO FISCAL", total e reembolso | 1–2, 4. OK em 2026-10-27 pelo roteiro de tela (capturas `03` a `05`); 3. `DevolucaoServiceIT.devolucaoEmDinheiroReembolsaVoltaAoEstoqueESaiDoDinheiroEsperado` |
| CT-F13-04 | Venda de 3 × R$ 5,49 com R$ 0,82 de desconto na venda (líquido R$ 15,65) | 1. Devolver 1 (Outro) 2. Devolver 3 3. Devolver 2 (Outro) | 1. R$ 5,22 2. Valor "—" (passa do disponível) 3. R$ 10,43; a lista mostra R$ 15,65 devolvidos | OK em 2026-10-27 pelo roteiro de tela (captura `06`) |
| CT-F13-05 | Venda já toda devolvida, ou quantidade maior que a vendida | 1. Registrar com quantidade acima da disponível 2. Abrir a venda toda devolvida | 1. "A quantidade devolvida de … passa do que pode ser devolvido: vendida 3 UN, já devolvida 2 UN." 2. "Todos os itens desta venda já foram devolvidos." e Registrar desabilitado | 1. `DevolucaoServiceIT.quantidadeAcimaDaVendidaOuFracionadaEmUnidadeERecusada`; 2. não executado pela tela |
| CT-F13-06 | Venda de mais de 15 dias (prazo 15) | 1. Supervisor: abrir a devolução 2. Operador com Devoluções e trocas: registrar 3. Liberação de quem não é supervisor 4. Liberação do supervisor | 1. Aviso "Fora do prazo de devolução: é preciso a liberação de um supervisor." 2. Pede a liberação de supervisor 3. "Esta liberação precisa de um supervisor." 4. Registra; comprovante e auditoria com quem liberou | 1. OK em 2026-10-27 pelo roteiro de tela (captura `09`); 2–4. `DevolucaoServiceIT.foraDoPrazoSoComLiberacaoDeSupervisor`; falta a pessoa executar |
| CT-F13-07 | Operador só com PDV | 1. PDV › F9 › Devolver itens… › registrar | 1. Pede a liberação de quem tem Devoluções e trocas; com ela, registra (quem registrou = operador, quem liberou = supervisor) | `DevolucaoServiceIT.semAPermissaoDevolucaoSoComLiberacao`; falta a pessoa executar |
| CT-F13-08 | Caixa aberto | 1. PDV › F9 › selecionar a venda › Devolver itens… (Ctrl+D) 2. Devolver 1 marcando Avariado › Registrar › Sim | 1. Abre a mesma devolução da tela Devoluções 2. "Devolução nº … registrada: R$ 5,49." na janela do F9; estoque: devolução +1 e perda −1, saldo igual | OK em 2026-10-27 pelo roteiro de tela (capturas `11` e `12`) |
| CT-F13-09 | Venda com devolução | 1. F9 › Cancelar venda… › motivo › Cancelar venda | 1. "A venda nº … tem devolução e não pode mais ser cancelada. Para desfazê-la por inteiro, devolva os itens que faltam (Devolver itens)." | OK em 2026-10-27 pelo roteiro de tela (captura `13`) |
| CT-F13-10 | Devoluções em dinheiro no caixa | 1. Fechar o caixa 2. Caixa › Ver último fechamento | 1–2. Linha "(−) Devoluções em dinheiro" com o total; dinheiro esperado já sem elas | OK em 2026-10-27 pelo roteiro de tela (captura `13b`; caixa fechado pelo service) |
| CT-F13-11 | Item devolvido avariado | 1. Estoque › produto › kardex 2. Relatórios › R14 | 1. "Devolução de venda" (+1, origem "Devolução nº X") e "Perda" (−1, "Devolução nº X — avariado: …") 2. A perda aparece com o motivo | OK em 2026-10-27 pelo roteiro de tela (capturas `14` e `16`) |
| CT-F13-12 | Venda de R$ 100,00 da Maria com cashback de R$ 1,00 | 1. Devolver 4 (R$ 40,00) em Vale-troca 2. Clientes › Maria › Crédito 3. Nova venda paga com o crédito | 1. Comprovante: "Cashback estornado -R$ 0,40" e "Saldo do vale-troca R$ 40,60" 2. Saldo R$ 40,60 3. O vale-troca paga a venda | 1–2. OK em 2026-10-27 pelo roteiro de tela (capturas `07`, `08` e `17`); 3. `DevolucaoServiceIT.valeTrocaAumentaOSaldoEPagaOutraVenda` |
| CT-F13-13 | Venda sem cliente | 1. Devolver em Vale-troca sem identificar 2. Identificar cliente… (F11) ou cadastro rápido › Registrar | 1. "O vale-troca fica no crédito do cliente: identifique ou cadastre o cliente." 2. O crédito vai para o cliente escolhido | `DevolucaoServiceIT.valeTrocaAumentaOSaldoEPagaOutraVenda`; falta a pessoa executar |
| CT-F13-14 | Venda de cliente anonimizado | 1. Abrir a devolução 2. Devolver em dinheiro com cashback a estornar 3. Clientes › anonimizado › Crédito › Ajustar… | 1. Aviso "Cliente anonimizado (LGPD): a devolução não pode virar vale-troca." e sem Vale-troca na forma 2. O saldo fica negativo 3. O diálogo já traz o valor que zera; outro valor é recusado | 1. OK em 2026-10-27 pelo roteiro de tela (captura `10`); 2–3. `DevolucaoServiceIT.clienteAnonimizadoNaoRecebeValeTrocaEOSupervisorZeraOSaldo`; falta a pessoa executar o 3 |
| CT-F13-15 | Devoluções no período | 1. Relatórios › R20 Devoluções › Gerar 2. Exportar PDF e CSV | 1. Devoluções com data, número, venda, quem registrou, cliente (CPF mascarado), itens (avariados marcados), motivo, reembolso e valor; total 2. Arquivos abrem | 1. OK em 2026-10-27 pelo roteiro de tela (captura `15`); 2. PDF coberto por `RelatorioPdfTest`, não executado pela tela |
| CT-F13-16 | Devolução registrada | 1. Devoluções › venda › devolução › Ver comprovante 2. Imprimir (F5) e Salvar PDF… | 1. O mesmo comprovante, com o saldo de crédito atual 2. Impressora padrão e PDF | 1. `DevolucaoServiceIT.buscaDaTelaEReimpressaoDoComprovante`; 2. falta a pessoa executar |
| CT-F13-17 | Supervisor | 1. Configurações › Diagnóstico › Verificar agora | 1. Estoque e crédito: "Tudo certo" | OK em 2026-10-27 pelo roteiro de tela (captura `18`) |

## Fase 13 — Devoluções e trocas, parte B (resultado comercial com as devoluções e gerador)

Conferência pela tela em 2026-10-29 por um roteiro descartável (fora do repositório): o sistema real aberto sobre o
banco `varesync_test` limpo, com os dados de demonstração (60 dias até ontem, 43 devoluções do gerador) e, hoje, um
produto de R$ 5,49 (custo R$ 3,20), o caixa aberto, a venda de 3 unidades em dinheiro e a devolução de 1 em dinheiro,
criados pelos services; pasta de usuário temporária e configuração sem BOM (a URL conferida antes de abrir), janelas
fora da tela, componentes acionados direto (sem teclado nem mouse do sistema) e capturas por snapshot `01` a `10`.
15 verificações, todas OK. As capturas acharam 2 defeitos visuais, corrigidos antes da execução final (PROGRESSO,
Métricas).

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F13-18 | Hoje: venda de 3 × R$ 5,49 em dinheiro e devolução de 1 em dinheiro | 1. Relatórios › R01 › período de hoje › Gerar 2. Marcar "Detalhar por venda" › Gerar | 1. Nº vendas 1, Vendas R$ 16,47, Devoluções R$ 5,49, Receita líquida R$ 10,98, CMV R$ 6,40, Lucro R$ 4,58, Margem 41,71%, Ticket médio R$ 16,47 2. Uma linha da venda e uma da devolução (com o nº dela) | 1. OK em 2026-10-29 pelo roteiro de tela (captura `02`); 2. `ResultadoComercialIT.aceiteR01DoDiaComDevolucaoEmDinheiro` |
| CT-F13-19 | O mesmo dia | 1. Painel › Atualizar (F5) | 1. Faturamento R$ 10,98 (receita líquida), Vendas 1, Ticket médio R$ 16,47, Lucro bruto R$ 4,58 | OK em 2026-10-29 pelo roteiro de tela (captura `01`) |
| CT-F13-20 | Dados de demonstração | 1. R01 dos 60 dias 2. R05 do mesmo período | 1–2. Receita líquida, CMV, lucro e margem do total iguais nos dois | OK em 2026-10-29 pelo roteiro de tela (capturas `03` e `04`: R$ 109.021,82, R$ 77.602,39, R$ 31.419,43, 28,82%) |
| CT-F13-21 | Dados de demonstração | 1. R08 dos 60 dias | 1. As formas de pagamento e, à parte, "Reembolso: Dinheiro", "Reembolso: Vale-troca" e "Reembolso: Outro"; total de Vendas = coluna Vendas do R01, total de Reembolsos = coluna Devoluções do R01 | OK em 2026-10-29 pelo roteiro de tela (captura `05`: R$ 109.552,09 e R$ 530,27) |
| CT-F13-22 | Dados de demonstração | 1. R09, R10 e R18 dos 60 dias | 1. R09 com Vendas, Devoluções e Receita líquida por operador (a devolução fica com quem fez a venda); R10 com o total igual à receita líquida do R01; R18 com Comprado, Devoluções e Valor líquido | OK em 2026-10-29 pelo roteiro de tela (capturas `06` a `08`) |
| CT-F13-23 | Devolução de um item noutro dia | 1. R01 do dia da venda ao da devolução 2. R05 só do dia da devolução | 1. O dia da devolução aparece com 0 vendas, receita negativa e ticket "—" 2. O produto aparece com receita e lucro negativos (situação Negativa) | `ResultadoComercialIT.devolucaoEntraNaDataDaDevolucaoENoOperadorDaVenda`; falta a pessoa executar pela tela |
| CT-F13-24 | Cliente com vale-troca (dados de demonstração) | 1. Clientes › cliente › aba Compras | 1. Coluna "Devolvido" na compra com devolução; Valor e Total gasto já sem as devoluções; ticket médio sobre o comprado; produto devolvido por inteiro sai dos mais comprados | 1. OK em 2026-10-29 pelo roteiro de tela (captura `10`); o produto devolvido por inteiro em `ResultadoComercialIT.clienteR18EHistoricoDescontamAsDevolucoes` |
| CT-F13-25 | Banco de demonstração novo | 1. Configurações › Dados de demonstração › Gerar 2. R20 dos 60 dias | 1. O resumo mostra "Devoluções: N" 2. As devoluções do gerador, em dinheiro, vale-troca e outro, algumas com item avariado | 1. Falta a pessoa executar pela tela (o roteiro gerou pelo service); 2. OK pelo roteiro (captura `09`) e `GeradorDemoIT.devolucoesDaFase13` |
| CT-F13-26 | Dados de demonstração | 1. R01 › Exportar PDF | 1. PDF deitado (A4 paisagem), com as colunas Vendas, Devoluções e Receita líquida | `RelatorioPdfTest.muitasColunasViramPaginaDeitada`; falta a pessoa abrir o PDF |

## Fase 14 — Crediário e contas a receber, parte A

"OK pelo roteiro de tela" = roteiro descartável de 2026-10-30, sem teclado nem mouse do sistema, no `varesync_test`. O limite de crédito ainda não tem tela: para os casos com limite, use `UPDATE cliente SET limite_credito = 1000 WHERE id = ...` no banco de testes. Sem isso, entre como supervisor, que vende acima do limite (com auditoria).

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F14-01 | Caixa aberto, venda sem cliente | 1. F10 2. Forma Crediário 3. Enter | 1–2. Aparecem Parcelas e 1º vencimento 3. "identifique o cliente (F11)" | OK pelo roteiro de tela (captura `01`) |
| CT-F14-02 | Cliente com limite de R$ 1.000,00 | 1. F11 com o CPF 2. 3 × produto de R$ 100,00 3. F10, Crediário, 3 parcelas, Enter 4. F10 5. Ver comprovante | 3. Limite disponível e a prévia 100,00 + 100,00 + 100,00 em +30, +60 e +90 dias 5. Comprovante com as 3 parcelas | OK pelo roteiro de tela (capturas `02` e `03`) |
| CT-F14-03 | O mesmo cliente | 1. Venda de R$ 100,00 em 3× | 1. 33,34 + 33,33 + 33,33 | OK pelo roteiro de tela |
| CT-F14-04 | O mesmo cliente | 1. Venda de R$ 100,00: R$ 40,00 em dinheiro e o resto no crediário | 1. Um título de R$ 60,00; só R$ 40,00 no dinheiro esperado | OK pelo roteiro de tela (captura `04`) |
| CT-F14-05 | Fidelidade ligada com 1% | 1. Venda de R$ 100,00 com R$ 40,00 em dinheiro e R$ 60,00 no crediário | 1. Cashback de R$ 0,40 | `CrediarioIT.parteNoCrediarioNaoGeraCashback`; falta a pessoa executar pela tela |
| CT-F14-06 | Operador sem a permissão; cliente com limite de R$ 50,00 | 1. Venda de R$ 100,00 no crediário, F10 2. Liberação com supervisor | 1. Mensagem com o limite disponível e o pedido de liberação 2. Venda gravada e auditoria "Crediário liberado" | OK pelo roteiro de tela (captura `05`) |
| CT-F14-07 | Cliente com título vencido há mais de 30 dias | 1. Venda no crediário com operador sem a permissão | 1. Pede a liberação | `CrediarioIT.tituloVencidoHaMaisDosDiasDeBloqueioPedeLiberacao`; falta a pessoa executar pela tela |
| CT-F14-08 | Venda em 3× de R$ 300,00 | 1. Devoluções › devolver R$ 120,00 2. Registrar 3. Ver comprovante | 1. "Abate R$ 120,00 das parcelas em aberto; nada a reembolsar" e sem forma a escolher 3. Forma Abatimento; parcelas 1/3 R$ 100,00 e 2/3 R$ 80,00; gaveta intacta | OK pelo roteiro de tela (capturas `06` e `07`) |
| CT-F14-09 | Venda de CT-F14-04 | 1. Devolver tudo em dinheiro 2. Ver comprovante | 1. "Abate R$ 60,00…; R$ 40,00 na forma" 2. Abatido R$ 60,00 e "Reembolso: Dinheiro" R$ 40,00; o dinheiro esperado cai só R$ 40,00 | OK pelo roteiro de tela (capturas `08` e `09`) |
| CT-F14-10 | Depois de CT-F14-08 e CT-F14-09 | 1. R08 do dia 2. R20 do dia | 1. Forma Crediário, "Reembolso: Abatimento" e "Reembolso: Dinheiro"; totais iguais às colunas Vendas e Devoluções do R01; a devolução mista conta uma vez 2. Colunas Abatido e Parcelas em aberto | OK pelo roteiro de tela (capturas `10` e `11`) |
| CT-F14-11 | Cliente com título em aberto, supervisor | 1. Clientes › LGPD › Anonimizar, com o nome certo | 1. Recusado: "títulos do crediário em aberto" | OK pelo roteiro de tela (captura `12`) |
| CT-F14-12 | Supervisor | 1. Configurações › Formas de pagamento | 1. "Crediário" na lista; o tipo não é oferecido para criar outra; desativá-la é permitido e ela não conta como forma ativa | OK pelo roteiro de tela (captura `13`); a desativação em `CrediarioIT.formaCrediarioEUnicaENaoContaComoFormaAtiva` |
| CT-F14-13 | Venda no crediário com parcela paga (por SQL na parte A; de verdade em CT-F14-25) | 1. F9 › Cancelar | 1. Recusado: "parcela do crediário com pagamento" | OK pelo roteiro de tela (captura `14`) |
| CT-F14-14 | Venda no crediário sem pagamento | 1. F9 › Cancelar | 1. Venda cancelada e títulos CANCELADO, com o motivo | OK pelo roteiro de tela |

## Fase 14 — Crediário e contas a receber, parte B (recebimento, extrato, configurações, R21, R22, painel e gerador)

Conferência em 2026-10-30 por roteiro descartável, sem teclado nem mouse do sistema (40 verificações, 21 capturas). Onde está
"Não executado", falta a pessoa executar.

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F14-15 | Título de R$ 100,00 vencido há 10 dias, padrões da V10, caixa aberto | 1. Caixa › Receber crediário › CPF + Enter | 1. Para quitar R$ 102,67; multa R$ 2,00; juros R$ 0,67 | OK pelo roteiro de tela (captura `03`) |
| CT-F14-16 | CT-F14-15 | 1. Valor a receber 51,34 + Enter 2. PIX 3. Confirmar | 1. Principal R$ 50,00, multa R$ 1,00, juros R$ 0,34 2. Comprovante; ficam R$ 50,00 em aberto | OK pelo roteiro de tela (capturas `04` e `05`) |
| CT-F14-17 | CT-F14-16 | 1. Receber o resto: R$ 51,33 2. Dinheiro 60,00 3. Confirmar | 1. Encargos sobre o que ficou 2. Troco R$ 8,67 3. Esperado sobe R$ 51,33 | OK pelo roteiro de tela (capturas `06` e `07`) |
| CT-F14-18 | Título a vencer | 1. Receber R$ 50,00 de um título de R$ 100,00 | 1. Sem multa nem juros; ficam R$ 50,00 | OK pelo roteiro de tela |
| CT-F14-19 | Cliente com várias parcelas | 1. Digitar um valor maior que a primeira | 1. Vai do vencimento mais antigo para o mais novo | Coberto por `RecebimentoIT.valorVaiDoVencimentoMaisAntigoParaOMaisNovo`; não executado pela tela |
| CT-F14-20 | Cliente com várias parcelas | 1. Selecionar uma parcela com o mouse | 1. O valor passa a ser o dela; só ela é quitada | Coberto por `RecebimentoIT.selecionarTituloQuitaSoEle`; não executado pela tela |
| CT-F14-21 | Valor maior que o total para quitar | 1. Digitar e dar Enter | 1. "passa do que falta" | OK pelo roteiro de tela |
| CT-F14-22 | Usuário sem RECEBIMENTO | 1. Caixa › Ctrl+R | 1. Pede liberação de supervisor; auditoria com quem liberou | OK pelo roteiro de tela (captura `19`) |
| CT-F14-23 | Usuário sem LIMITE_CREDITO, título em atraso | 1. Desconto nos encargos 2,67 + Enter | 1. Pede liberação; total R$ 100,00; comprovante com o desconto | OK pelo roteiro de tela (captura `20`) |
| CT-F14-24 | PDV com venda em andamento | 1. Ctrl+R, receber | 1. O recebimento grava; a venda continua como estava | OK pelo roteiro de tela (captura `10`) |
| CT-F14-25 | Venda a prazo com parcela paga pelo recebimento | 1. F9 › Cancelar | 1. Recusado: "parcela do crediário com pagamento" | OK pelo roteiro de tela (captura `11`) |
| CT-F14-26 | Recebimento em dinheiro na sessão | 1. Caixa › selecionar › Estornar… › motivo | 1. Situação "Estornado"; esperado volta; títulos voltam a ficar em aberto | OK pelo roteiro de tela (capturas `08` e `09`) |
| CT-F14-27 | Caixa com recebimentos fechado | 1. Ver último fechamento | 1. "(+) Recebimentos em dinheiro" e os recebimentos por forma | OK pelo roteiro de tela (captura `21`); falta pelo F12 |
| CT-F14-28 | Venda em 3× com duas devoluções | 1. Clientes › aba Crediário 2. Selecionar um lançamento | 1. Saldo devedor, vencido e disponível 2. Detalhe com devolução e parcela | OK pelo roteiro de tela (captura `12`) |
| CT-F14-29 | Supervisor | 1. Aba Crediário: limite 777,00 e bloquear › Salvar limite | 1. Gravado e auditado; disponível novo | OK pelo roteiro de tela (captura `13`) |
| CT-F14-30 | Usuário sem LIMITE_CREDITO | 1. Clientes › aba Crediário | 1. Limite só para leitura, sem o botão | OK pelo roteiro de tela (captura `18`) |
| CT-F14-31 | Supervisor | 1. Configurações › Crediário: mudar a carência › Salvar 2. Diagnóstico › Verificar agora | 1. Gravado e auditado 2. "Tudo certo" nos abatimentos | OK pelo roteiro de tela (capturas `14` e `15`) |
| CT-F14-32 | Títulos vencidos, usuário com RECEBIMENTO | 1. Painel 2. Clicar no alerta | 1. "N títulos vencidos (R$ X)" 2. R21 só com os vencidos | OK pelo roteiro de tela (capturas `01` e `02`) |
| CT-F14-33 | Carteira com atrasos | 1. R21 e R22 2. Exportar CSV e PDF | 1. Faixas de atraso; R22 sem estornados 2. Arquivos abrem | Tela OK (capturas `16` e `17`); não executado: abrir os arquivos |
| CT-F14-34 | Banco vazio | 1. Gerar os dados de demonstração | 1. 30 clientes com limite, vendas a prazo, recebimentos e títulos vencidos | Coberto por `GeradorDemoIT.crediarioDaFase14`; não executado pela tela |

## Fase 15 — Financeiro, parte A (contas, integração com o caixa, recebíveis e contas a pagar)

Conferência em 2026-11-02 por roteiro descartável, sem teclado nem mouse do sistema (24 verificações, 13 capturas). Onde
está "Não executado", falta a pessoa executar.

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F15-01 | Supervisor | 1. Financeiro › Contas 2. Selecionar o Cofre › Saldo inicial… 500,00 3. Saldo inicial… de novo | 1. Cofre (padrão do caixa) e Banco 2. Saldo R$ 500,00 e o extrato 3. "já tem saldo inicial" | OK pelo roteiro de tela (captura `01`); 3 coberto por `ContaFinanceiraIT` |
| CT-F15-02 | CT-F15-01 | 1. Ajuste… sem motivo 2. Com motivo, −5,00 3. Transferir… R$ 300,00 do Cofre para o Banco | 1. "Informe o motivo do ajuste." 2. Saldo cai 3. As duas pernas no extrato | Coberto por `ContaFinanceiraIT`; não executado pela tela |
| CT-F15-03 | Conta com saldo, padrão do caixa ou recebendo uma forma | 1. Editar… › desmarcar Ativa | 1. Recusado com o motivo | Coberto por `ContaFinanceiraIT.cadastroDaContaEInativacao`; não executado pela tela |
| CT-F15-04 | Configurações › Financeiro › integração ligada | 1. Caixa › Abrir caixa… R$ 100,00 (conta Cofre já escolhida) 2. Sangria de R$ 50,00 3. Fechar com R$ 49,00 contados | 1 a 3. Cofre −100, +50, +49; R11 com diferença −R$ 1,00 | OK pelo roteiro de tela (capturas `06`, `07`, `11`) |
| CT-F15-05 | Integração desligada | 1. Abrir, sangria e fechar | 1. Os diálogos não mostram a conta; nenhuma conta se move | OK pelo roteiro de tela nos diálogos; razão coberto por `ContaFinanceiraIT` |
| CT-F15-06 | Caixa aberto com a integração desligada | 1. Ligar a integração 2. Sangria e fechamento | 1 e 2. O caixa continua sem mover as contas até fechar | Coberto por `ContaFinanceiraIT`; não executado pela tela |
| CT-F15-07 | Supervisor | 1. Configurações › Formas de pagamento › Cartão de crédito: taxa 3, 30 dias, Banco › Salvar recebimento 2. Selecionar Dinheiro | 1. Gravado e auditado 2. Campos desabilitados | OK pelo roteiro de tela (captura `12`) |
| CT-F15-08 | CT-F15-07 | 1. Vender R$ 100,00 no crédito 2. Financeiro › Recebíveis | 1 e 2. Previsto de R$ 97,00 (taxa R$ 3,00) para daqui a 30 dias | OK pelo roteiro de tela (captura `02`) |
| CT-F15-09 | CT-F15-08 | 1. Selecionar › Conciliar… com total 90,00 2. Com total 97,00 | 1. "é diferente" 2. Banco recebe R$ 97,00; recebível Recebido | OK pelo roteiro de tela (captura `03`) |
| CT-F15-10 | Recebível previsto | 1. Corrigir líquido… 98,50 › Conciliar | 1. Taxa passa a ser bruto − líquido | Coberto por `RecebivelIT.liquidoCorrigidoNaConciliacaoMudaATaxa`; não executado pela tela |
| CT-F15-11 | Venda no PIX (0 dias) na sessão | 1. F9 › Cancelar a venda | 1. Banco recebe e depois estorna (ESTORNO_RECEBIVEL) | Coberto por `RecebivelIT`; não executado pela tela |
| CT-F15-12 | Título do crediário | 1. Receber no PIX 2. Estornar o recebimento | 1. Recebível do recebimento entra no Banco 2. Estornado | Coberto por `RecebivelIT`; não executado pela tela |
| CT-F15-13 | Supervisor | 1. Financeiro › Contas a pagar › Nova despesa… com repetição 3 2. Pagar… parcial com juros 3. Estornar pagamento… 4. Cancelar conta… | 1. 4 títulos mês a mês 2. Em aberto diminui; conta sai com valor + juros 3. Reabre 4. Recusado com pagamento | Pagamento OK pelo roteiro de tela (capturas `04` e `05`, saldo negativo em vermelho); o resto coberto por `ContaPagarIT` |
| CT-F15-14 | Entrada aberta com valor da nota R$ 120,00 | 1. Compras › Parcelas… duas de R$ 60,00 2. Confirmar entrada | 1. Salvas, sem aviso 2. A confirmação avisa as parcelas; duas contas a pagar em "Compra de mercadorias" | OK pelo roteiro de tela (captura `08`) |
| CT-F15-15 | XML de NF-e com duplicatas | 1. Compras › Importar XML da NF-e 2. Parcelas… | 1 e 2. As duplicatas com vencimento já vêm como parcelas | Coberto por `ImportacaoNfeIT`; não executado pela tela |
| CT-F15-16 | Venda no PIX | 1. Devoluções › Devolver › Reembolso Outro 2. Trocar para Dinheiro 3. Outro › Registrar | 1. Aparece "Saiu da conta" com o Banco 2. Some 3. Reembolso sai do Banco | OK pelo roteiro de tela (capturas `09` e `10`) |

## Fase 15 — Financeiro, parte B (fluxo de caixa, DRE, painel, R23 e gerador)

Executados pelo roteiro de tela descartável (2026-11-02, dados de demonstração no `varesync_test`) quando indicado; os
demais estão "Não executado", falta a pessoa executar.

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F15-17 | Dados de demonstração gerados | 1. Painel com o supervisor | 1. Seção Financeiro com o saldo das contas (e o de cada uma) e o resultado líquido do mês; alertas de conta a pagar vencida, de contas que vencem hoje e de conta negativa | OK pelo roteiro de tela (saldo e resultado conferidos com o banco; captura `01`) |
| CT-F15-18 | CT-F15-17 | 1. Clicar no alerta de contas vencidas 2. Voltar e clicar no da conta negativa | 1. Financeiro › Contas a pagar só com as vencidas em aberto (a energia) 2. Aba Contas com a conta negativa selecionada | OK pelo roteiro de tela (capturas `02` e `05`) |
| CT-F15-19 | CT-F15-17 | 1. Financeiro › Fluxo de caixa (visão por semana) 2. Selecionar a semana de hoje 3. Visão por mês, de dois meses atrás › Gerar | 1. Resumo com o saldo no início, hoje e projetado; gráfico de entradas, saídas e saldo 2. Detalhe com a linha "Em atraso" no previsto 3. Quatro meses, sem transferências nem saldo inicial no realizado | OK pelo roteiro de tela (capturas `03` e `04`) |
| CT-F15-20 | Saldo inicial do Cofre, transferência para o Banco, ajustes e uma despesa vencida | 1. Fluxo do mês por dia | 1. Partida = saldos iniciais; transferência fora; ajustes numa linha com entradas e saídas; a despesa vencida em "Em atraso" hoje | Coberto por `FinanceiroParteBIT.fluxoDeCaixaRealizadoPrevistoEEmAtraso`; não executado pela tela |
| CT-F15-21 | CT-F15-17 | 1. Relatórios › R25 do mês passado 2. R01 e R05 do mesmo mês | 1. 14 linhas, despesas por categoria, % sobre a receita líquida, coluna do mês anterior 2. Receita líquida e lucro bruto iguais às linhas 4 e 6 | OK pelo roteiro de tela (outubro: R$ 56.995,25 e R$ 16.449,34; capturas `06` a `08`) |
| CT-F15-22 | Venda no crédito a 3%, caixa com diferença de −R$ 1,00, aluguel de março pago em abril, entrada com 2 parcelas | 1. R25 do mês da venda 2. R25 de março e de abril 3. R24 de abril | 1. −R$ 3,00 em taxas e −R$ 1,00 em diferenças de caixa 2. Aluguel em março; nenhuma compra de mercadoria como despesa 3. Saída do aluguel | Coberto por `FinanceiroParteBIT`; não executado pela tela |
| CT-F15-23 | CT-F15-17 | 1. R24 por semana 2. R23 com situação Vencidos 3. Exportar CSV e PDF | 1. Saldo no início e saldo depois de cada linha 2. Só contas vencidas, com dias de atraso 3. Arquivos abrem | 1 e 2 OK pelo roteiro de tela (capturas `09` e `10`); 3 não executado |
| CT-F15-24 | Usuário com Relatórios, sem Financeiro | 1. Entrar 2. Relatórios | 1. Sem o menu Financeiro e sem a seção do painel 2. Sem R23, R24 e R25 | OK pelo roteiro de tela (captura `11`) |

## Fase 16 — Compras, parte A (fornecedor do produto, vínculo pela NF-e, sugestão de compra, R26 e R28)

Executados pelo roteiro de tela descartável (2026-11-02, `varesync_test` com 3 produtos, 30 leites e 5 pães vendidos
hoje e a NF-e fictícia `src/test/resources/nfe/nfe-caixa-fator.xml`) quando indicado; os demais estão "Não executado",
falta a pessoa executar.

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F16-01 | Supervisor | 1. Configurações › Compras 2. Dias da média = 3 › Salvar alterações | 1. 30 e 15 dias (padrão) 2. Gravado; a auditoria registra "dias da média de venda de 30 dias para 3 dias" | OK pelo roteiro de tela (captura `01`) |
| CT-F16-02 | Produto "Leite integral 1 L" e fornecedor ativo | 1. Produtos › abrir o leite › aba Fornecedores › Incluir… 2. Fornecedor, código LEITE-CX12, unidade cx, fator 12, prazo 3 › Incluir | 1. Lista vazia 2. Linha com o código, "12 UN por CX", prazo 3 e principal "Sim" (o primeiro nasce principal) | OK pelo roteiro de tela (capturas `02` e `03`) |
| CT-F16-03 | CT-F16-02 | 1. Incluir… o mesmo fornecedor | 1. Recusado: "… já é fornecedor deste produto. Altere a linha dele." | OK pelo roteiro de tela (captura `04`) |
| CT-F16-04 | Produto com dois fornecedores | 1. Selecionar o segundo › Tornar principal 2. Remover… o principal | 1. Só ele fica "Sim" 2. O produto fica sem principal | Coberto por `ProdutoFornecedorIT`; não executado pela tela |
| CT-F16-05 | Produto UN | 1. Incluir… com fator 2,5 2. Com fator 0,5 3. Prazo 400 | 1. Recusado (inteiro na unidade UN) 2. Recusado (1 ou mais) 3. Recusado (0 a 365) | Coberto por `ProdutoFornecedorIT`; não executado pela tela |
| CT-F16-06 | CT-F16-02; NF-e com o leite SEM GTIN (`cProd` LEITE-CX12, 2 CX a R$ 48,00) e um iogurte sem vínculo | 1. Compras › Importar XML da NF-e… | 1. Leite vinculado ("Cód. fornecedor"), quantidade "2 CX × 12 = 24"; iogurte em vermelho, sem vínculo; "Gerar entrada" desabilitado | OK pelo roteiro de tela (captura `05`) |
| CT-F16-07 | CT-F16-06 | 1. Selecionar o iogurte › Escolher produto… › escolher 2. Fator 1 › Confirmar 3. Gerar entrada | 1. Abre o fator 2. "Escolhido agora", "6 UN × 1 = 6" 3. Entrada aberta com 24 leites a R$ 4,0000 (48,00 ÷ 12) e o iogurte; vínculo IOG-170 gravado no iogurte | OK pelo roteiro de tela (capturas `06` a `08`) |
| CT-F16-08 | CT-F16-07 | 1. Importar a próxima nota do mesmo fornecedor (outro número) | 1. Os dois itens já vêm vinculados pelo código do fornecedor | OK pelo roteiro de tela (captura `15`) |
| CT-F16-09 | CT-F16-07 | 1. Confirmar a entrada 2. Abrir o leite › Fornecedores | 2. Último custo R$ 4,0000 | OK pelo roteiro de tela (captura `12`; entrada confirmada pelo service) |
| CT-F16-10 | Média de 3 dias; 30 leites vendidos hoje (saldo 40); 5 pães vendidos (saldo 0) | 1. Compras › Sugestão de compra › F5 2. Fornecedor principal = o do leite; desmarcar "Só com sugestão" 3. Estoque mínimo do leite = 200 › Calcular | 1. Leite: média 10, cobertura 4, sugestão 144 = 12 CX, R$ 460,80; pão em vermelho (ruptura) 2. Leite e iogurte do fornecedor 3. Sugestão 168 = 14 CX | OK pelo roteiro de tela (capturas `09` a `11`) |
| CT-F16-11 | CT-F16-10 | 1. Devolver 6 leites › Calcular | 1. Média 8 (30 − 6 em 3 dias) e a sugestão cai | Coberto por `SugestaoCompraIT.devolucaoReduzAMedia`; não executado pela tela |
| CT-F16-12 | CT-F16-10 | 1. Relatórios › R26 2. R28 3. Exportar CSV e PDF | 1. Só os produtos com sugestão, por fornecedor principal 2. Todos os ativos, o pão em ruptura primeiro, total "1 em ruptura" 3. Arquivos abrem | 1 e 2 OK pelo roteiro de tela (capturas `13` e `14`); 3 não executado (PDF coberto por `RelatorioPdfTest`) |

## Fase 16 — Compras, parte B (pedido de compra, "Gerar pedido", conferência, fornecedor inativo, R27, painel e gerador)

Executados pelo roteiro de tela descartável (2026-11-04, `varesync_test` com 3 produtos, 3 fornecedores — um deles
inativo e principal do queijo —, média de 3 dias e 30 leites, 5 pães e 6 queijos vendidos hoje) quando indicado; os
demais estão "Não executado", falta a pessoa executar.

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F16-13 | Pão sem saldo que vendeu hoje; usuário com COMPRAS | 1. Painel 2. Clicar no alerta | 1. "1 produto está em ruptura (sem estoque e com venda nos últimos 30 dias) — ver na Sugestão de compra" 2. Compras › Sugestão de compra com "Só em ruptura" marcado, só o pão | OK pelo roteiro de tela (capturas `01` e `02`) |
| CT-F16-14 | Queijo com o fornecedor principal inativo | 1. Sugestão de compra › Calcular | 1. Queijo com "(principal inativo)", linha em amarelo, prazo 0 e fator 1 (sugestão 26, sem caixa); a dica da linha diz "Fornecedor principal inativo (…): prazo 0 e fator 1" | OK pelo roteiro de tela (captura `03`) |
| CT-F16-15 | CT-F16-14 | 1. Marcar os com sugestão 2. Gerar pedido (3)… › Sim | 1. Botão "Gerar pedido (3)…" 2. Aba Pedidos com dois rascunhos (um por fornecedor principal); o queijo fica de fora, com aviso; no do leite, 144 UN = 12 CX a R$ 3,2000 com o código LEITE-CX12 | OK pelo roteiro de tela (captura `04`) |
| CT-F16-16 | Rascunho do leite | 1. Dados do pedido… › previsão daqui a 3 dias › Salvar 2. Enviar pedido… › Sim 3. PDF do pedido… | 1. Previsão gravada 2. "Enviado … · previsão … — somente leitura"; itens bloqueados; auditoria "Pedido de compra enviado" 3. PDF com a coluna "Na unidade de compra" (12 CX) e o fornecedor no cabeçalho | 1 e 2 OK pelo roteiro de tela (capturas `05` e `06`); 3 gravado pelo roteiro sem a janela "Salvar como" (falta abrir num leitor de PDF) |
| CT-F16-17 | Pedido de 144 enviado | 1. Sugestão de compra › desmarcar "Só com sugestão" › Calcular | 1. Leite com pendente 144 e sugestão 0 (o pendente reduz a sugestão) | OK pelo roteiro de tela (captura `07`) |
| CT-F16-18 | CT-F16-17; entrada aberta do mesmo fornecedor com 120 leites a R$ 3,50 e 2 queijos | 1. Entradas › selecionar › Pedido… › escolher o pedido › Salvar 2. Conferir com o pedido… | 1. Só o pedido enviado do fornecedor na lista; título da entrada com "pedido nº X" 2. Leite "Abaixo do pendente · custo diferente" (faltava 144, nesta nota 120, falta depois 24), custo da nota em vermelho; queijo "Fora do pedido"; linhas em amarelo | OK pelo roteiro de tela (capturas `08` e `09`) |
| CT-F16-19 | CT-F16-18 | 1. Confirmar entrada… › Sim 2. Aba Pedidos | 1. A confirmação diz que as quantidades entram no pedido e avisa da divergência 2. Pedido "Parcial", recebida 120, falta 24, "Entradas vinculadas: nº …" | OK pelo roteiro de tela (capturas `10` e `11`) |
| CT-F16-20 | CT-F16-19 | 1. Relatórios › R27 | 1. O leite com 24 pendentes, R$ 76,80 a custo previsto, situação Parcial; filtro só de fornecedor | OK pelo roteiro de tela (captura `12`) |
| CT-F16-21 | CT-F16-19 | 1. Nova entrada de 24 leites vinculada ao pedido › Confirmar 2. Aba Pedidos › Situação "Todas" | 2. Pedido "Recebido" | OK pelo roteiro de tela (captura `13`; a entrada de 24 foi lançada e confirmada pelo service) |
| CT-F16-22 | Rascunho de outro fornecedor | 1. Cancelar pedido… › Sim | 1. "Cancelado … · cancelado por …" | OK pelo roteiro de tela (captura `13`) |
| CT-F16-23 | Pedido PARCIAL | 1. Cancelar pedido… › Sim 2. Sugestão de compra | 1. A confirmação diz que o que falta deixa de ser pendente 2. Pendente 0 | Coberto por `PedidoCompraIT.cancelarParcialTiraOPendenteEOsDemaisNaoCancelam`; não executado pela tela |
| CT-F16-24 | Entrada aberta vinculada a um pedido | 1. Cancelar o pedido 2. Dados da nota… › outro fornecedor | 1. Recusado: "A entrada nº X, ainda aberta, está vinculada…" 2. Recusado: "Desvincule o pedido antes de trocar o fornecedor" | Coberto por `PedidoCompraIT`; não executado pela tela |
| CT-F16-25 | Fornecedor inativo | 1. Pedidos › Novo pedido… 2. Enviar um rascunho dele | 1. Ele não aparece na lista 2. Recusado: "… está inativo e não recebe pedido novo" | 1 OK pelo roteiro de tela (captura `14`); 2 coberto por `PedidoCompraIT.fornecedorInativo` |
| CT-F16-26 | Pedido rascunho | 1. Ler o código de um produto já no pedido › quantidade › Incluir 2. Quantidade 1,5 num produto UN 3. Ctrl+N na aba Pedidos | 1. Recusado: "já está neste pedido" 2. Recusado 3. Abre "Novo pedido de compra" | 1 e 2 cobertos por `PedidoCompraIT.rascunhoEnvioEItens`; não executado pela tela (teclado de verdade) |
| CT-F16-27 | Banco vazio | 1. Configurações › Dados de demonstração › Gerar 2. Compras › Pedidos (situação Todas) 3. Relatórios › R27 | 2. Um pedido recebido por entrada semanal e um enviado por fornecedor (a próxima entrega) 3. Os 3 pedidos enviados | Coberto por `GeradorDemoIT.vinculosEPedidosDaFase16`; não executado pela tela |

## Fase 17 — Validade e lotes, parte A (banco e regras, sem tela nova)

Nesta parte não há tela nova: o cadastro com a caixa "Controla validade e lotes", a aba Lotes do Estoque, a escolha do
lote no ajuste e a divisão da entrada são da parte B. Os casos abaixo valem como roteiro para depois da parte B; hoje
estão cobertos pelos testes de integração indicados (`LoteServiceIT`, banco `varesync_test`) e "Não executado" pela
tela.

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F17-01 | Produto com controle, lote A (vence em 10 dias, 20 un) e B (40 dias, 20 un) | 1. Vender 25 2. Cancelar a venda | 1. A com 0 e B com 15 (FEFO) 2. A com 20 e B com 20 | Coberto por `LoteServiceIT.fefoDoAceite` e `cancelamentoVoltaParaOsLotesDaVendaDoAceite`; não executado pela tela |
| CT-F17-02 | Produto com 5 no "SEM LOTE" e lote A com 20 | 1. Vender 8 | 1. "SEM LOTE" com 0 e A com 17 | Coberto por `LoteServiceIT.semLotePrimeiroDoAceite`; não executado pela tela |
| CT-F17-03 | CT-F17-01 sem o cancelamento | 1. Devolver 3 2. Devolver mais 4 3. Em outra venda de 25, devolver 1 avariado | 1. Volta para B 2. 2 para B e 2 para A 3. B recebe 1 e perde 1; saldos dos lotes não mudam | Coberto por `LoteServiceIT.devolucaoParcialNaOrdemInversaDoAceite` e `devolucaoAvariadaEntraESaiDoMesmoLoteDoAceite`; não executado pela tela |
| CT-F17-04 | Produto sem controle com saldo 13 | 1. Vender 3 2. Ligar o controle 3. Devolver 2 da venda do passo 1 | 2. 10 no "SEM LOTE" (auditoria "saldo inicial de 10 UN no SEM LOTE") 3. "SEM LOTE" com 12 | Coberto por `LoteServiceIT.vendaAntesDoControleEDevolucaoParaOSemLoteDoAceite`; não executado pela tela |
| CT-F17-05 | Produto com controle; entrada aberta com 30 un | 1. Confirmar sem divisão 2. Dividir 10 + 15 e confirmar 3. Dividir 10 + 20 e confirmar | 1. Recusado: "O produto … controla validade: divida o item em lotes" 2. Recusado: "soma 25, mas o item tem 30" 3. Dois lotes com 10 e 20 | Coberto por `LoteServiceIT.entradaEmDoisLotesDoAceite`; não executado pela tela |
| CT-F17-06 | Leite com controle e vínculo LEITE-CX12 (fator 12) | 1. Importar `src/test/resources/nfe/nfe-rastro-lotes.xml` (3 CX, rastro com qLote 1 e 2) 2. Confirmar | 1. Divisão L2610A 12 e L2610B 24 2. Lotes com 12 e 24 | Coberto por `LoteServiceIT.rastroDaNfeComFatorDoAceite`, `rastroQueNaoSomaEsperaADivisaoSerAcertada`, `LeitorNfeTest.rastroDosLotes`; não executado pela tela |
| CT-F17-07 | Produto com controle e só o lote A (20); estoque negativo permitido | 1. Vender 30 2. Transferir 2 de A (depois de nova entrada) para o "SEM LOTE" 3. Transferir mais do que a origem tem | 1. A com 0 e "SEM LOTE" com −10 2. Saldo do produto não muda 3. Recusado: "tem só …" | Coberto por `LoteServiceIT.faltaSaiDoSemLoteNegativoDoAceite` e `transferenciaEntreLotesDoAceite`; não executado pela tela |
| CT-F17-08 | CT-F17-01 | 1. Perda por vencimento sem lote 2. Perda por vencimento de 5 no lote B 3. Movimento inserido direto no banco, sem lotes | 1. Recusado: "escolha o lote que venceu" 2. B com 15 e estoque com 35 3. Recusado no commit: "não foi distribuído nos lotes" | Coberto por `LoteServiceIT.perdaPorVencimentoNoLoteEscolhidoDoAceite` e `movimentoSemDistribuicaoRecusadoNoCommitDoAceite`; não executado pela tela |

## Fase 17 — Validade e lotes, parte B (telas, R29, painel e gerador)

Pela tela em 2026-11-06, com o roteiro descartável (eventos nos componentes, sem teclado nem mouse do sistema, banco
`varesync_test`); os números são as capturas. Com a parte B, os casos CT-F17-01 a CT-F17-08 da parte A também podem ser
executados pela tela.

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F17-09 | Produto com controle, lote V vencido ontem (5 un) e lote A (vence em 10 dias, 20 un) | 1. Vender 3 no PDV 2. Inventário: contar 20 e aplicar | 1. Sai de A (V continua com 5) 2. A falta de 2 sai de V | Coberto por `LoteServiceIT.vencidoNaVendaFicaPorUltimoDoAceite` e `RegraLotesTest.vencidoFicaPorUltimoSoNaVendaDoAceite`; não executado pela tela |
| CT-F17-10 | Produto sem controle com saldo 10 | 1. Produtos › abrir o produto 2. Marcar "Controla validade e lotes" e Salvar 3. Responder Sim 4. Desmarcar, Salvar e responder Não | 2. Aviso: o saldo (10 UN) vai para o "SEM LOTE" e "Transferir…" passa para os lotes certos 3. "SEM LOTE" com 10 4. Aviso de que os lotes ficam parados; o controle continua ligado | OK pela tela (capturas `10` e `11`) |
| CT-F17-11 | — | 1. Produtos › Novo produto, estoque inicial 12 2. Marcar a caixa; "Lote inicial" IG1 com validade 3. Salvar | 2. "Lote inicial" aparece só com a caixa marcada 3. Lote IG1 com 12 | OK pela tela (captura `12`) |
| CT-F17-12 | Produto com lotes A (3 dias), B (20 dias) e V (vencido) | 1. Estoque › buscar › aba Lotes 2. Selecionar A 3. Zerar V e marcar "Mostrar lotes zerados" | 1. A em amarelo ("Vence em 3 dias"), V em vermelho ("Vencido", −1), B "OK" 2. Razão do A: "Ajuste de entrada · Compra sem nota · +10" 3. V some da lista e volta como "Zerado" | OK pela tela (capturas `04` e `08`) |
| CT-F17-13 | CT-F17-12 | 1. Selecionar A › Transferir… 2. Destino "Lote novo…" C, validade, 4, motivo | 2. A com 6 e C com 4; saldo do produto igual (22) e nenhum movimento de estoque novo; razão: "Para o lote C…" | OK pela tela (capturas `05` e `06`) |
| CT-F17-14 | CT-F17-13 | 1. F4: Perda, Vencimento, 2, lote "Automático (FEFO)" 2. Escolher o lote V e Registrar 3. F4: Entrada 5 em "Lote novo…" D1 com validade | 1. Recusado: "Na perda por vencimento, escolha o lote que venceu." 2. V com 0 e estoque 20 3. Lote D1 com 5 | OK pela tela (capturas `07` e `09`) |
| CT-F17-15 | Produto com o "SEM LOTE" em −3 e outro sem negativo | 1. Estoque › "Só com SEM LOTE negativo" | 1. Só o produto com o negativo; na aba Lotes, "SEM LOTE" −3 em vermelho | OK pela tela (captura `03`, pelo alerta do painel) |
| CT-F17-16 | Entrada aberta com 30 un de produto com controle, sem divisão | 1. Selecionar a entrada 2. Confirmar entrada… 3. Selecionar o item › Lotes…: M1 18 e M2 12 › Salvar 4. Confirmar | 1. Item em amarelo, "— lotes: faltam 30 UN" 2. "Falta dividir em lotes (botão "Lotes…"): Manteiga com sal 200 g (faltam 30 UN)." 3. "Falta dividir 12 UN" até a segunda linha; depois "Divisão completa: 30 UN."; item sem destaque 4. Lotes M1 18 e M2 12 | OK pela tela (capturas `13` a `15`) |
| CT-F17-17 | CT-F17-12 | 1. Relatórios › R29 (7 dias, com os vencidos) › Gerar 2. Desmarcar os vencidos e gerar | 1. V (−1, R$ 10,00) e A (3 dias, R$ 50,00); sem B nem o "SEM LOTE"; total "2 lotes (1 vencido)" e R$ 60,00 2. Só A | OK pela tela (capturas `02` e `18`) |
| CT-F17-18 | CT-F17-12 e CT-F17-15 | 1. Painel › Alertas 2. Clicar em "lote vencido" 3. Clicar em "negativo no SEM LOTE" | 1. "1 lote vence em até 7 dias", "1 lote vencido com saldo…", "1 produto com saldo negativo no SEM LOTE" 2. R29 já filtrado e gerado 3. Estoque filtrado na aba Lotes | OK pela tela (capturas `01` a `03`) |
| CT-F17-19 | Supervisor | 1. Configurações › Estoque: 5 › Salvar alterações 2. Diagnóstico › Verificar agora | 1. Gravado e auditado ("alerta de validade dos lotes"); o R29 abre com 5 dias 2. "Tudo certo" nos lotes (a aba rola até a seção) | OK pela tela (capturas `16`, `17` e `18`) |
| CT-F17-20 | Banco vazio | 1. Configurações › Dados de demonstração › Gerar 2. Painel 3. Clicar no alerta de vencidos | 2. "N lotes vencem em até 7 dias" e "N lotes vencidos com saldo" 3. R29 com os mesmos números, de Frios e laticínios e Padaria | OK pela tela com o gerador chamado pelo service (4 vencendo e 6 vencidos; capturas `D1` e `D2`); a aba Dados de demonstração não foi usada |

## Fase 18 — Etiquetas de gôndola e carga de balança, parte A (F18.1)

Pela tela em 2026-11-06, com o roteiro descartável (eventos nos componentes, sem teclado nem mouse do sistema, banco
`varesync_test`, nada enviado para impressora: a térmica configurada com um nome que não existe); os números são as
capturas. Dados: café (EAN-13), arroz (EAN-13), feijão (sem código), refrigerante lata 350 ml (EAN-8, em promoção),
biscoito 500 g (UPC-A) e queijo pesável (PLU 123), todos com estoque.

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F18-01 | Produtos com estoque, nunca etiquetados | 1. Produtos › Etiquetas… | 1. Critério "Pendentes"; os 6 em amarelo; "6 produtos, 6 etiquetas (6 pendentes)"; refrigerante "R$ 3,99 (DE R$ 4,49, até …)"; biscoito "R$ 8,99 · R$ 17,98 o kg"; queijo "PLU 123" | OK pela tela (capturas `01`, `02`) |
| CT-F18-02 | CT-F18-01 | 1. Marcar como em dia › Sim | 1. "6 produtos marcados como em dia."; lista vazia; nada impresso | OK pela tela (captura `03`) |
| CT-F18-03 | CT-F18-02 | 1. Alterar o preço de café, arroz e feijão 2. Etiquetas… › Carregar | 2. Exatamente os 3, com o preço novo | OK pela tela |
| CT-F18-04 | CT-F18-03 | 1. Incluir "Refrigerante", "Biscoito", "Queijo" 2. Incluir "Biscoito" de novo 3. Qtd. do café 2; Qtd. 0 no arroz 4. Posição 25 › Gerar PDF… | 1. Entram direto (um resultado) 2. "já está na lista" 3. 0 recusado; "6 produtos, 7 etiquetas (3 pendentes)" 4. "A posição inicial deve ser de 1 a 24 (folha de 3 × 8…)" | OK pela tela (captura `04`) |
| CT-F18-05 | CT-F18-04 | 1. Posição 3 › Gerar PDF… › salvar 2. Abrir o PDF num leitor | 1. "PDF salvo em … (7 etiquetas, 1 folha)."; lista de pendentes vazia 2. Começa na 3ª posição; café 2 vezes; DE R$ 4,49 POR R$ 3,99 com "Oferta válida até"; "500 g · R$ 17,98 o kg"; "350 ml · R$ 11,40 o L"; "PLU 123" no queijo; "Código 3" no feijão; códigos de barras legíveis | OK pela tela (captura `05`); o PDF foi desenhado em PNG e os 4 códigos (EAN-13, EAN-8, UPC-A) foram lidos pelo ZXing. Falta conferir com um leitor de código de barras de verdade numa folha impressa |
| CT-F18-06 | CT-F18-05 | 1. Critério "Lista manual" › incluir "Café" | 1. Café "em dia" (sem "pendente"): a reimpressão é sempre possível | OK pela tela |
| CT-F18-07 | Térmica configurada com um nome que não existe | 1. Imprimir na térmica › Sim | 1. "A impressora térmica "…" não foi encontrada no Windows…"; nada registrado | OK pela tela (captura `06`) |
| CT-F18-08 | Impressora térmica de verdade instalada e modelo conferido | 1. Configurações › Etiquetas: escolher a impressora › Exemplo ZPL (ou DPL) › Imprimir teste 2. Etiquetas… › Imprimir na térmica | 1. Sai a etiqueta do "PRODUTO TESTE", sem acentos 2. Uma etiqueta por unidade da quantidade | Não executado (sem impressora térmica; nenhum envio a impressora de verdade nesta sessão) |
| CT-F18-09 | Produto "Biscoito 500 g" | 1. Abrir o produto 2. Conteúdo 400 › Salvar 3. Conteúdo 300 sem unidade › Salvar | 1. "500", "g" e "= R$ 17,98 o kg na etiqueta" 2. "= R$ 22,48 o kg"; gravado e auditado 3. "Escolha a unidade do conteúdo líquido (g, kg, ml ou L)." | OK pela tela (captura `07`) |
| CT-F18-10 | Supervisor | 1. Configurações › Etiquetas 2. Exemplo DPL › Sim 3. Imprimir teste 4. 2 × 5 › Salvar alterações 5. 7 por linha › Salvar | 1. 3 × 8, impressora e modelo gravados; o combo lista as impressoras instaladas 2. Modelo começa com "<STX>L" 3. Erro "não foi encontrada" 4. Gravado e auditado 5. "entre 1 e 6" | OK pela tela (captura `08`) |
| CT-F18-11 | Produto em promoção etiquetado | 1. Esperar o dia seguinte ao fim da promoção 2. Etiquetas… | 2. O produto volta a ficar pendente, com o preço normal e sem DE/POR | Coberto por `EtiquetaServiceIT.promocaoSaiComoDePorEVoltaAFicarPendenteNoFim`; falta pela tela |
| CT-F18-12 | Entrada de mercadoria nº N e uma categoria com produtos | 1. Critério "Produtos de uma entrada", nº N › Carregar 2. "Categoria" › Carregar 3. "Preço alterado desde" uma data › Carregar | 1. Os produtos da entrada 2. Os ativos da categoria 3. Os que mudaram de preço desde a data | Coberto por `EtiquetaServiceIT.criteriosDeSelecao`; falta pela tela |

## Fase 18 — Etiquetas de gôndola e carga de balança, parte B (F18.2 e F18.3)

Pela tela em 2026-11-07, com o roteiro descartável (eventos nos componentes, sem teclado nem mouse do sistema, banco
`varesync_test`, o `ITENSMGV.TXT` gravado numa pasta temporária); os números são as capturas. Dados: balança ativa;
queijo "Queijo Muçarela Fatiado Especial Kg" (KG, PLU 123, R$ 39,90, validade 5 dias), presunto cozido (KG, PLU 124,
R$ 29,90, em promoção a R$ 27,90), banana prata (KG sem PLU) e café (UN), todos com estoque e nunca etiquetados.

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F18-13 | Dados acima, nada carregado | 1. Painel | 1. "4 produtos com etiqueta pendente (sem etiqueta ou com preço novo)…" e "Carga de balança pendente (2 produtos novos ou alterados)…" | OK pela tela (captura `01`) |
| CT-F18-14 | CT-F18-13 | 1. Clicar no alerta da carga | 1. Abre Produtos e a Carga de balança: só PLU 123 e 124; presunto "R$ 27,90 (promoção)"; queijo "QUEIJO MUCARELA FATIADO ESPECIAL KG", "5 dias", "Nunca carregado", em amarelo; a linha do queijo é a amostra do layout (150 caracteres); "2 produtos na balança, 2 pendentes" | OK pela tela (captura `02`) |
| CT-F18-15 | CT-F18-14 | 1. Gerar carga › Sim 2. Abrir o `ITENSMGV.TXT` | 1. "Carga gerada: 2 produtos em …ITENSMGV.TXT…"; "nenhum pendente"; "Última carga: …" 2. Duas linhas de 150 caracteres com CRLF (304 bytes), a do presunto com 002790 | OK pela tela (captura `03`): 304 bytes, igual ao esperado |
| CT-F18-16 | CT-F18-15 | 1. Alterar o preço do queijo para R$ 41,90 2. Atualizar › "Mostrar só os pendentes" 3. Gerar carga › Sim | 2. Só o queijo, "Preço alterado"; a linha começa com 010000123004190005 3. Lista de pendentes vazia; o arquivo tem o preço novo | OK pela tela (captura `04`) |
| CT-F18-17 | CT-F18-16 | 1. Painel › clicar em "produtos com etiqueta pendente" 2. Marcar como em dia › Sim 3. Painel | 1. Etiquetas aberta nas pendentes (4) 3. Os dois alertas sumiram | OK pela tela (captura `05`) |
| CT-F18-18 | Produto queijo | 1. Abrir o produto 2. Validade 991 › Salvar 3. Validade 7 › Salvar 4. Apagar o PLU | 1. "Validade na balança" = 5, habilitada 2. "entre 1 e 990" 3. Gravado e auditado; a carga fica pendente ("Validade alterada") 4. A validade fica desabilitada | OK pela tela (captura `06`) |
| CT-F18-19 | Produto banana (KG sem PLU) | 1. Abrir o produto | 1. "Validade na balança" vazia e desabilitada | OK pela tela |
| CT-F18-20 | Supervisor | 1. Configurações › Balança 2. Pasta "itens" › Salvar alterações 3. Pasta vazia › Salvar | 1. A pasta configurada 2. "Informe o caminho completo…" 3. Gravado e auditado ("pasta da carga de balança de … para (vazio)") | OK pela tela (captura `07`) |
| CT-F18-21 | CT-F18-20 | 1. Produtos › Carga de balança… › Gerar carga | 1. "não configurada (Configurações › Balança)"; "Configure a pasta da carga em Configurações › Balança…"; nada registrado | OK pela tela (captura `08`) |
| CT-F18-22 | Balança Toledo com o MGV6 da loja | 1. Gerar carga na pasta do MGV 2. Importar os itens no MGV 3. Pesar um produto | 2. O MGV importa sem erro 3. A etiqueta sai com o PLU, o preço por kg, a descrição e a validade da carga | Não executado (sem balança nem MGV nesta máquina): é o risco registrado no PROGRESSO |

## Fase 19 — Cadastro fiscal e consistência, parte A (F19.1 a F19.3)

Pela tela em 2026-11-08, com o roteiro descartável (eventos nos componentes, sem teclado nem mouse do sistema, banco
`varesync_test`, os arquivos numa pasta temporária, sem abrir as janelas "Abrir"); os números são as capturas. Dados:
cerveja, refrigerante (EAN 7896004000015) e água em Bebidas; café (EAN 7891000315507) e arroz (EAN 7891234567895) em
Mercearia, sem dados fiscais; usuário "cadastro" com Produtos e Compras, sem Cadastro fiscal; a NF-e de teste
`src/test/resources/nfe/nfe-fiscal.xml`.

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F19-01 | Supervisor | 1. Configurações › Loja 2. IE "12A45" › Salvar alterações 3. UF SP, IE "123.456.789.110" › Salvar | 1. Regime "Simples Nacional", UF e IE vazias 2. "A inscrição estadual tem só dígitos (até 14) ou é "ISENTO"." 3. Gravadas sem pontos e auditadas | OK pela tela (captura `01`) |
| CT-F19-02 | CT-F19-01 | 1. Produtos › Fiscal… | 1. "Empresa: Simples Nacional · UF SP · IE 123456789110…"; 5 produtos em amarelo ("5 sem NCM ou sem perfil") | OK pela tela (captura `02`) |
| CT-F19-03 | CT-F19-02 | 1. Perfis tributários: nome "Simples tributado", CFOP 51, CSOSN 102, PIS e COFINS 49 › Salvar 2. CFOP 5102 › Salvar 3. Novo perfil "Simples ST", 5405, CSOSN 500, PIS/COFINS 04, cBenef "sem cbenef", CST IBS/CBS 000, cClassTrib 000001 › Salvar | 1. O tipo começa em CSOSN; "O CFOP tem 4 dígitos (ex.: 5102)." 2. "Perfil criado." 3. Dois perfis; cBenef "SEM CBENEF" | OK pela tela (captura `03`) |
| CT-F19-04 | CT-F19-02 | 1. Tabelas NCM e CEST › Importar tabela NCM: CSV `código;descrição` com um capítulo e 2 códigos 2. Importar o JSON oficial (Siscomex) 3. Importar a tabela CEST (3 linhas) | 1. "2 códigos (1 nível intermediário pulado)" 2. "4 códigos (3 níveis intermediários pulados)"; a tabela foi trocada 3. "Tabela CEST: 3 códigos." | OK pela tela (captura `04`) |
| CT-F19-05 | CT-F19-04 | 1. Produtos: café, NCM 1234.56.78 › Salvar dados fiscais 2. NCM 0901.21.00, origem 0, perfil "Simples tributado" › Salvar | 1. "O NCM 1234.56.78 não existe na tabela NCM importada…" 2. "Não descafeinado" ao lado do NCM; gravado e auditado | OK pela tela (captura `05`) |
| CT-F19-06 | CT-F19-05 | 1. Aplicar perfil em lote: "Simples ST", "A toda a categoria" Bebidas › Aplicar perfil… › Sim | 1. "Perfil "Simples ST" aplicado a 3 produtos."; o café continua com "Simples tributado" | OK pela tela (captura `06`) |
| CT-F19-07 | CT-F19-06 | 1. Compras › Importar XML da NF-e… (`nfe-fiscal.xml`) › Gerar entrada | 1. Diálogo "Dados fiscais da NF-e" com 2 produtos: café (CEST 17.096.00) e refrigerante (NCM 2202.10.00, CEST 03.007.00, origem 2); o arroz (campos fora do formato) não aparece | OK pela tela (captura `07`) |
| CT-F19-08 | CT-F19-07 | 1. Preencher os marcados | 1. "Entrada nº 1 gerada… Dados fiscais preenchidos em 2 produtos."; o NCM e a origem do café não mudam | OK pela tela (captura `08`) |
| CT-F19-09 | CT-F19-08 | 1. Produtos › Fiscal… 2. "Só sem NCM ou sem perfil" | 1. Refrigerante com NCM 2202.10.00, CEST 03.007.00 e "Simples ST" 2. "3 produtos, 3 sem NCM ou sem perfil" | OK pela tela (captura `09`) |
| CT-F19-10 | Usuário "cadastro" (sem Cadastro fiscal) | 1. Log off › entrar 2. Produtos | 2. Sem o botão Fiscal… | OK pela tela (captura `10`) |

### Fase 19 — Cadastro fiscal e consistência, parte B (RF30, RF29)

Pela tela em 2026-11-09, com o roteiro descartável (eventos nos componentes, sem teclado nem mouse do sistema, banco
`varesync_test` com os dados de demonstração gerados pelo service); os números são as capturas. Usuário "fiscal" só com
o Cadastro fiscal (FISCAL). A NF-e é a de teste (`src/test/resources/nfe/nfe-fiscal.xml`) com a origem do item 2 trocada
para 1.

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F19-11 | Dados de demonstração; supervisor | 1. Painel | 1. "6 produtos com inconsistência fiscal — conferir o cadastro fiscal" (as duas cervejas só têm aviso); o menu tem o item Fiscal | OK pela tela (captura `01`) |
| CT-F19-12 | CT-F19-11 | 1. Clicar no alerta | 1. Relatórios com o R30 já gerado: 10 linhas em 8 produtos (guarda-chuva, pilha, vinho, energético, carvão, azeite e as duas cervejas); totais "8 produtos" e "10 inconsistências (8 erros, 2 avisos)"; Abrir produto… desabilitado sem seleção | OK pela tela (captura `02`; PDF e CSV conferidos) |
| CT-F19-13 | CT-F19-12 | 1. Regra "13 — Mesmo NCM em perfis diferentes" › Gerar 2. Regra "Todas" › Gerar | 1. As duas cervejas, como aviso 2. As 10 linhas de novo | OK pela tela (captura `03`) |
| CT-F19-14 | CT-F19-13 | 1. Selecionar a linha do energético › Abrir produto… 2. CEST 03.005.00 › Salvar dados fiscais 3. Fechar a janela | 1. Cadastro fiscal (janela) na aba Produtos com "Produto selecionado: Energético lata 250 ml" 2. Gravado 3. O R30 é gerado de novo, com 9 linhas, sem o energético | OK pela tela (capturas `04` e `05`) |
| CT-F19-15 | CT-F19-14 | 1. Duplo clique na linha da pilha 2. Fechar | 1. Cadastro fiscal com a pilha (sem perfil) selecionada | OK pela tela (duplo clique por evento na linha) |
| CT-F19-16 | CT-F19-15 | 1. Painel | 1. "5 produtos com inconsistência fiscal" | OK pela tela |
| CT-F19-17 | Usuário "fiscal" (só FISCAL) | 1. Entrar 2. Clicar no alerta | 1. Menu só com Painel e Fiscal; o alerta "5 produtos com inconsistência fiscal" 2. Sem Relatórios: o Cadastro fiscal na área de conteúdo, sem "Esc fecha a janela" | OK pela tela (capturas `06` e `07`) |
| CT-F19-18 | CT-F19-17 | 1. Painel 2. Menu Fiscal | 2. O Cadastro fiscal | OK pela tela (captura `08`) |
| CT-F19-19 | Supervisor; produto com o EAN 7896004000015 sem dados fiscais | 1. Compras › Importar XML da NF-e… (origem 1 no item 2) › Gerar entrada 2. Preencher os marcados | 1. "Dados fiscais da NF-e" com o refrigerante na origem "2 — Estrangeira — adquirida no mercado interno" e a explicação da troca 2. Origem gravada 2 | OK pela tela (captura `09`) |

### Fase 20 — Embalagens de venda e preço de atacado, parte A (RF31, RF17)

Pela tela em 2026-11-09, com o roteiro descartável (eventos nos componentes, sem teclado nem mouse do sistema, banco
`varesync_test`); os números são as capturas. Supervisor; caixa aberto. Refrigerante cola 2 L a R$ 5,49 (custo
R$ 3,20, estoque 100) com o código da unidade 7891000315507 e o da caixa 17891000315504. Até a parte B (cadastro), a
embalagem e a faixa são gravadas direto no banco:

```sql
UPDATE produto_codigo_barras SET quantidade = 12, preco_embalagem = 59.90 WHERE codigo = '17891000315504';
INSERT INTO produto_preco_quantidade (produto_id, quantidade_minima, preco)
SELECT produto_id, 6, 4.99 FROM produto_codigo_barras WHERE codigo = '7891000315507';
```

A NF-e é a de teste `src/test/resources/nfe/nfe-embalagem.xml` (5 CX pelo EAN da caixa, a R$ 39,00).

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F20-01 | Embalagem e faixa acima | 1. PDV: ler 17891000315504 2. Ler 2*17891000315504 3. Ler 1,5*17891000315504 4. F10, dinheiro, finalizar | 1. Item de 12 UN a R$ 4,99 = R$ 59,90 2. Item de 24 UN = R$ 119,80; total R$ 179,70 3. "…informe um número inteiro de embalagens antes do *…"; nada entra 4. Estoque 64 (100 − 12 − 24) | OK pela tela (captura `01`; recusa do 1,5 na mensagem do PDV; estoque 64 no banco) |
| CT-F20-02 | CT-F20-01 | 1. Ler 7891000315507 cinco vezes 2. Ler a 6ª | 1. Total R$ 27,45 2. Os seis a R$ 4,99, "(atacado)"; total R$ 29,94 | OK pela tela (capturas `02` e `03`) |
| CT-F20-03 | CT-F20-02 | 1. Selecionar o item 6 › F6 › Sim | 1. Os cinco voltam a R$ 5,49 sem "(atacado…)"; total R$ 27,45 | OK pela tela (captura `04`) |
| CT-F20-04 | CT-F20-03 | 1. Ler mais uma 2. F10, dinheiro, finalizar › Ver comprovante | 1. Total R$ 29,94 2. "Preço de atacado (a partir de 6 un)" abaixo dos seis itens; o cancelado com "ITEM CANCELADO" | OK pela tela (captura `05`; no banco, 6 itens com a faixa) |
| CT-F20-05 | Promoção de R$ 4,79 vigente (Produtos › Promoções) | 1. Ler a caixa 2. Ler a unidade seis vezes 3. Finalizar | 1. 12 UN por R$ 57,48 (12 × R$ 4,79), "(promoção)" 2. As seis a R$ 4,79 (R$ 28,74); total R$ 86,22 | OK pela tela (captura `06`) |
| CT-F20-06 | Venda do CT-F20-04 | 1. Devoluções › nº da venda › Devolver itens… 2. Quantidade 1 no primeiro item, motivo › Registrar › Sim | 2. "TOTAL DEVOLVIDO R$ 4,99" | OK pela tela (capturas `07` e `08`) |
| CT-F20-07 | Supervisor | 1. Compras › Importar XML da NF-e… (`nfe-embalagem.xml`) | 1. Item "5 CX × 12 = 60", produto Refrigerante cola 2 L, vínculo "Cód. embalagem" | OK pela tela (captura `09`) |
| CT-F20-08 | CT-F20-07 | 1. Gerar entrada | 1. Entrada aberta com 60 UN a R$ 3,2500 (R$ 39,00 ÷ 12), R$ 195,00 | OK pela tela (captura `10`) |
| CT-F20-09 | Produto com a caixa de 12 | 1. Produtos › abrir o refrigerante › mudar o preço › Salvar | 1. A caixa continua com 12 un e R$ 59,90 (conferir no banco ou lendo a caixa no PDV) | Automático (`EmbalagemAtacadoIT.salvarOProdutoNoCadastroMantemAsEmbalagens`) |

### Fase 20, parte B — cadastro, F7, etiqueta, segunda via, entrada manual e inventário (F20.3)

Pela tela em 2026-11-09, com o roteiro descartável (eventos nos componentes, sem teclado nem mouse do sistema, banco
`varesync_test`); os números são as capturas. Supervisor; caixa aberto; balança ativa (prefixo 2, 5 dígitos, PRECO).
Refrigerante cola 2 L a R$ 5,49 (custo R$ 3,20, estoque 100) com o código da unidade 7891000315507 e o da caixa
17891000315504, **sem** embalagem nem faixa (a tela grava as duas); promoção de R$ 5,99 no refrigerante e de R$ 42,90
no Queijo muçarela (KG, R$ 39,90, PLU 123), as duas **acima** do preço normal; uma entrada aberta e um inventário
"Bebidas" com o refrigerante. Para os dados de demonstração da versão nova, use o banco `varesync_amostra`
(COMO_CONTINUAR.md), nunca o `varesync_demo`.

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F20-10 | Acima | 1. Produtos › abrir o refrigerante 2. Selecionar 17891000315504 › Embalagem… 3. Qtd. 12, preço 59,90 › OK | 3. Na lista: "17891000315504 · 12 · R$ 59,90"; o principal com 1 e "—" | OK pela tela (capturas `01` e `02`) |
| CT-F20-11 | CT-F20-10 | 1. Aba Preço por quantidade › Incluir… 2. A partir de 6, preço 4,99 › OK 3. Salvar | 2. "6 un · R$ 4,99 · 9,11% · margem" 3. "Produto salvo."; no banco, a caixa com 12 e R$ 59,90 e a faixa de 6 | OK pela tela (capturas `03` e `04`; no banco, a caixa e a faixa) |
| CT-F20-12 | CT-F20-11 | 1. Incluir… a partir de 12 a 5,09 › OK › Salvar 2. Alterar… a de 12 para 3,10 › OK › Salvar › Não 3. Remover a de 12 › Salvar | 1. "…deve ser menor que o da faixa a partir de 6 (R$ 4,99)."; nada gravado 2. Confirmação "…abaixo do custo…"; "Não" não grava 3. "Produto salvo." com uma faixa | OK pela tela (capturas `05` e `06`) |
| CT-F20-13 | CT-F20-11 | 1. Selecionar a caixa › Marcar principal | 1. "O código principal é o da unidade…"; o principal continua 7891000315507 | OK pela tela (mensagem conferida) |
| CT-F20-14 | CT-F20-11 | 1. Produtos › Etiquetas… (pendentes) | 1. Refrigerante "R$ 5,49 · A partir de 6 un: R$ 4,99", sem "DE" (a promoção de R$ 5,99 não vale), "nunca" | OK pela tela (captura `07`) |
| CT-F20-15 | CT-F20-14 | 1. Gerar PDF… (posição 1) 2. Abrir o PDF | 2. A etiqueta com R$ 5,49 e a linha "A partir de 6 un: R$ 4,99"; a impressão grava a faixa | OK pela tela (PDF `08_etiquetas.pdf` desenhado; no banco, a impressão com a faixa) |
| CT-F20-16 | CT-F20-15 | 1. Mudar o preço da faixa para 4,89 no cadastro › Salvar 2. Etiquetas… › Carregar | 2. O refrigerante volta às pendentes com "A partir de 6 un: R$ 4,89" | OK pela tela (captura `09`) |
| CT-F20-17 | Acima | 1. Produtos › Carga de balança… | 1. Queijo muçarela com R$ 39,90 (a promoção de R$ 42,90 não vale) | OK pela tela (captura `10`) |
| CT-F20-18 | CT-F20-16 | 1. PDV › F7 › ler 17891000315504 | 1. "R$ 5,49 / UN" sem promoção; "Código lido: embalagem com 12 un"; "A partir de 6 un: R$ 4,89 / UN"; "Embalagem com 12 un (17891000315504): R$ 59,90" | OK pela tela (captura `11`) |
| CT-F20-19 | CT-F20-18 | 1. Ler 6*7891000315507 › F10 › dinheiro › finalizar › Ver comprovante 2. Mudar a faixa para "a partir de 10" › F9 › Comprovante 3. Apagar a faixa › F9 › Comprovante | 1. Total R$ 29,34; "Preço de atacado (a partir de 6 un)" 2 e 3. A segunda via continua com "a partir de 6 un" | OK pela tela (capturas `12` a `14`) |
| CT-F20-20 | Entrada aberta | 1. Compras › a entrada › ler 17891000315504 2. Quantidade 5, custo 39,00 3. Incluir | 1. "…embalagem com 12 UN: quantidade em embalagens e custo por embalagem" 2. "5 embalagens × 12 = 60 UN a R$ 3,2500 (R$ 195,00)" 3. Item de 60 UN a R$ 3,2500, R$ 195,00 | OK pela tela (capturas `15` e `16`) |
| CT-F20-21 | Inventário "Bebidas" | 1. Inventário › Contagem › ler 17891000315504 | 1. Posiciona no refrigerante e avisa "…embalagem com 12 UN: a contagem é em unidades (UN)… (a embalagem tem 12)." | OK pela tela (captura `17`) |
| CT-F20-22 | Dados de demonstração no `varesync_amostra` | 1. Relatórios › R01 e R05 dos 60 dias 2. PDV › F7 › ler o fardo do refrigerante cola (7896543900012) | 1. Receita e lucro iguais 2. "Embalagem com 6 un…: R$ 49,90" | Automático (`GeradorDemoIT.embalagensEAtacadoDaFase20`, `r01Er05BatemEVendaCanceladaFicaDeFora`) |

### Fase 21, parte A — preferências por usuário, tema escuro, fonte de 115% e 130% e cortes (F21.1, F21.2)

Pela tela em 2026-11-10, com o roteiro descartável (eventos nos componentes, sem teclado nem mouse do sistema, banco
`varesync_test` com os dados de demonstração); os nomes são as capturas. Supervisor sem preferências gravadas; uma
operadora "caixa" só com PDV; caixa aberto. Para os dados de demonstração da versão nova, use o banco
`varesync_amostra` (COMO_CONTINUAR.md), nunca o `varesync_demo`. Na janela do sistema em 1280×720 (o tamanho mínimo).

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F21-01 | Sistema aberto | 1. Olhar a tela de login 2. Entrar como supervisor | 1. Claro, fonte normal 2. Janela principal clara de 100% (nunca gravou preferências) | OK pela tela (capturas `00` e `01`) |
| CT-F21-02 | CT-F21-01 | 1. Menu › Minhas preferências 2. Tema Escuro, Tamanho da fonte Maior (130%) › Salvar | 1. Diálogo com Tema e Tamanho da fonte, para qualquer usuário 2. A janela fica escura e a fonte maior na hora, sem sair; no banco, `usuario_preferencia` com ESCURO e 130 | OK pela tela (captura `02`; no banco, a linha gravada) |
| CT-F21-03 | CT-F21-02 | 1. Percorrer Painel, PDV (ler dois produtos › F10), Produtos e o cadastro de um produto, Estoque, Compras (Entradas e Pedidos), Clientes, Caixa, Financeiro (Contas e Contas a pagar), Relatórios (gerar R01 e R27) e Configurações | 1. Nenhum rótulo, botão, título de coluna ou caixa de escolha com "…"; nada passa da borda direita; barras de botões quebram a linha; no PDV, o total e a barra de leitura visíveis; no R27, "Custo previsto" e "Valor pendente" inteiros | OK pela tela (capturas `escuro130_03` a `escuro130_17`; o roteiro procura "…" e controles fora da borda) |
| CT-F21-04 | CT-F21-03, R27 gerado | 1. Rolar a tabela do R27 para a direita | 1. A tabela rola de lado e a linha de totais acompanha ("R$ 6.437,76" sob "Valor pendente") | OK pela tela (captura `escuro130_14b`) |
| CT-F21-05 | CT-F21-02 | 1. Log off 2. Olhar o login 3. Entrar como "caixa" 4. Log off e entrar como supervisor | 2. Login claro de 100% 3. A operadora entra clara de 100% 4. O supervisor volta ao escuro de 130% | OK pela tela (captura `40`) |
| CT-F21-06 | CT-F21-05 | 1. Minhas preferências › Claro, Maior (130%) 2. Percorrer as telas de CT-F21-03 3. Minhas preferências › Escuro, Grande (115%) › Painel 4. Claro, Normal (100%) › percorrer de novo | 2. Claro de 130% sem cortes 3. Escuro de 115% 4. Igual a antes da fase | OK pela tela (capturas `claro130_*`, `escuro115_painel` e `claro100_*`) |
| CT-F21-07 | Escuro, 130% | 1. PDV › vender › Ver comprovante | 1. O comprovante em tela continua claro (fundo branco, texto escuro); o PDF e a impressão não mudam | Pendente (pessoa) |
| CT-F21-08 | Escuro, 130% | 1. Abrir uma caixa de escolha (Categoria em Produtos) e uma data 2. Passar o Tab pelo diálogo de preferências e confirmar com Enter | 1. Lista e calendário escuros e legíveis 2. Ordem lógica; Enter salva, Esc cancela | Pendente (pessoa: popups e teclado de verdade) |

### Fase 21, parte B — ajuda contextual (F1) e busca global (Ctrl+K) (F21.3)

Pela tela em 2026-11-11, com o roteiro descartável (eventos nos componentes, sem teclado nem mouse do sistema, banco
`varesync_test` com os dados de demonstração); os nomes são as capturas. Supervisor e uma estoquista (só ESTOQUE) no
escuro de 130%; um operador "caixa" (só PDV) no padrão; caixa aberto. Para os dados de demonstração da versão nova,
use o banco `varesync_amostra` (COMO_CONTINUAR.md), nunca o `varesync_demo`. Janela em 1280×720.

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F21-09 | Supervisor, escuro de 130% | 1. Em cada tela do menu (Painel, Caixa, Clientes, Devoluções, Produtos, Fiscal, Estoque, Inventário, Compras, Fornecedores, Financeiro, Relatórios, Usuários, Configurações), teclar F1 2. F1 de novo | 1. Painel lateral "Ajuda — <tela>" com o texto da tela, "Atalhos desta tela" (as teclas da tela) e "Em todas as telas" (F1 e Ctrl+K), sem textos cortados 2. O painel fecha | OK pela tela (capturas `01_ajuda_painel` a `14_ajuda_configuracoes`; a lista conferida com o registro da tela) |
| CT-F21-10 | CT-F21-09 | 1. Estoque › F1 2. Menu › Produtos 3. Fechar (F1) 4. Estoque › escolher um produto › F4 | 2. A ajuda passa a ser a de Produtos 3. Fecha 4. Abre o ajuste de estoque, como a ajuda diz | OK pela tela (captura `16`) |
| CT-F21-11 | Caixa aberto | 1. PDV › ler um produto › F1 2. Ctrl+K 3. Esc 4. Esc | 1. Mesmo painel "Ajuda — PDV", com F1 a F12, Ctrl+R, Esc, ↑ e ↓; o foco continua no campo Código 2. Nada abre 3. A ajuda fecha e a venda continua 4. Pergunta se abandona a venda | OK pela tela (capturas `escuro130_17` e `escuro130_18`) |
| CT-F21-12 | Supervisor | 1. Ctrl+K › digitar "a" 2. Digitar 7896543900012 (fardo do refrigerante) › Enter | 1. "Digite ao menos 2 letras ou números", sem resultados 2. Produtos: o refrigerante cola, "embalagem com 6 UN (código 7896543900012)"; Enter abre o cadastro dele | OK pela tela (capturas `20` a `22`) |
| CT-F21-13 | Supervisor | 1. Ctrl+K › CPF de um cliente › Enter 2. Ctrl+K › nome de um fornecedor › ↓ até ele › Enter | 1. Clientes: o cliente, com o CPF oculto (***.xxx.xxx-**); Enter abre a ficha em Clientes 2. Abre o cadastro em Fornecedores | OK pela tela (capturas `23`, `24` e `27`) |
| CT-F21-14 | Supervisor | 1. Ctrl+K › número de uma venda › ↓ até Vendas › Enter 2. Ctrl+K › "inventa" › Enter 3. Ctrl+K › "refri" › Esc | 1. "Venda nº N" com data e total; a segunda via do comprovante, com "Devolver itens…" 2. Telas: Inventário; abre a tela 3. Fecha | OK pela tela (capturas `25`, `26`, `28` e `29`) |
| CT-F21-15 | Estoquista (só ESTOQUE) | 1. Ctrl+K › "silva", o número de uma venda, "con" 2. Ctrl+K › 7896543900012 › Enter 3. F1 | 1. Nenhum cliente, fornecedor, venda, Configurações ou Compras 2. O produto abre no Estoque (sem PRODUTOS) 3. A ajuda do Estoque | OK pela tela (capturas `30` a `32_estoquista_ajuda_estoque`) |
| CT-F21-16 | Operador "caixa" (só PDV) | 1. Ctrl+K › número de uma venda › Enter | 1. Só a venda; a segunda via sem "Devolver itens…" | OK pela tela (captura `33`) |
| CT-F21-17 | Qualquer usuário | 1. Teclar F1 e Ctrl+K com o teclado de verdade 2. Na busca, ↑ e ↓, clique duplo num resultado 3. Ler a ajuda de cada tela | 1. Abrem como no roteiro 2. Escolhem e abrem 3. Textos certos para a loja | Pendente (pessoa: teclado e mouse de verdade, revisão dos textos) |

### Fase 21, parte C — assistente "Primeiros passos" e padronização das telas (F21.4, F21.5)

| ID | Pré-condição | Passos | Resultado esperado | Resultado obtido |
|---|---|---|---|---|
| CT-F21-18 | Banco novo, só o supervisor (primeiro acesso), escuro de 130% | 1. Entrar 2. Olhar o Painel | 2. Quadro "Primeiros passos" no alto: Dados da loja, Formas de pagamento, Produtos, Usuários, Balança e Backup, todos pendentes, cada um com o que falta e o botão que abre a tela | OK pela tela (captura `escuro130_01`) |
| CT-F21-19 | CT-F21-18 | 1. Formas de pagamento › Revisei 2. Usuários › Só eu uso o sistema 3. Balança › Não uso balança | 1. Formas concluído 2. e 3. Dispensados; Configurações › Primeiros passos com as três caixas marcadas; auditoria "Configurações alteradas" | OK pela tela (captura `02`; no banco, as três chaves e a auditoria) |
| CT-F21-20 | CT-F21-19 | 1. Dados da loja › Abrir os dados da loja 2. CNPJ, endereço e UF › Salvar alterações | 1. Configurações já na seção Loja (lista à esquerda, sem barra de abas) 2. Aviso discreto no canto de baixo ("Configurações salvas: …"), que some sozinho; o foco continua no campo; nenhuma janela de OK | OK pela tela (capturas `03_notificacao` e `04_configuracoes_01_loja`) |
| CT-F21-21 | CT-F21-20 | 1. Cadastrar um produto ativo 2. Fazer um backup 3. Painel | 3. O quadro não aparece mais (tudo concluído ou dispensado) | OK pela tela (produto e backup criados pelo roteiro; o quadro some) |
| CT-F21-22 | CT-F21-21 | 1. F1 › Primeiros passos… 2. Fechar | 1. O Painel com o quadro, os seis passos feitos e "tudo pronto" 2. O quadro some | OK pela tela (captura `05`) |
| CT-F21-23 | Supervisor, escuro de 130%, 1280×720 | 1. Configurações 2. Escolher cada seção na lista | 1. A lista com as 17 seções cabe sem rolar; foco na lista 2. Cada seção abre sem texto cortado nem nada fora da janela; a barra Salvar aparece nas seções de ajustes | OK pela tela (capturas `04_configuracoes_01` a `17`) |
| CT-F21-24 | Supervisor, Financeiro com despesas do mês | 1. Painel › cartão "Resultado líquido do mês" | 1. O texto diz que as despesas do mês inteiro já entram pela competência e que no começo do mês o resultado costuma sair negativo | OK pela tela (captura `06`) |
| CT-F21-25 | Dados de demonstração, escuro de 130% | 1. Estoque › Buscar 2. Arrastar a divisória para baixo e para cima | 1. A lista mostra ao menos 5 linhas; rodapé "F4 ajustar estoque · F5 ajustar saldo para… · Enter no campo de busca pesquisa" 2. A divisória se move; com ela mais baixa, a lista mostra mais linhas | OK pela tela (captura `07_estoque`: 5,4 linhas; 8,6 com a divisória mais baixa) |
| CT-F21-26 | Dados de demonstração, escuro de 130% | 1. Percorrer Produtos, Clientes, Caixa, Financeiro (Contas, Recebíveis, Contas a pagar, Fluxo de caixa), Inventário, Usuários, Fornecedores, Devoluções, Fiscal, Compras (Entradas, Pedidos, Sugestão), Relatórios (R01, R05 e R27) 2. Parar o mouse num nome de produto com "…" | 1. Nenhum valor (dinheiro, quantidade, data) cortado nas tabelas; rótulos e botões inteiros; nada fora da janela; rodapés do Caixa, Inventário, Compras e Pedidos com as teclas do registro 2. A dica mostra o nome inteiro | OK pela tela (capturas `08_*`, `09_*` e `10_*`; a dica conferida num nome de produto) |
| CT-F21-27 | Produtos | 1. Abrir Promoções, Unidades, Categorias, Etiquetas e Carga de balança 2. Categorias › escolher uma › Inativar › Não | 1. Sem textos cortados no escuro de 130% 2. Pergunta "Inativar a categoria …?"; com Não, continua ativa | OK pela tela (capturas `11_*` e `11_categorias_confirmacao`) |
| CT-F21-28 | Dados de demonstração | 1. Ctrl+K › 7 | 1. "Venda nº 7" (um dígito procura só a venda); Enter abre a segunda via | OK pela tela (captura `12_busca_venda_7`) |
| CT-F21-29 | Caixa aberto | 1. PDV 2. Olhar a barra de atalhos e teclar F1 | 1. Barra "F1 ajuda · F2 buscar · … · Esc abandonar venda / sair", tirada do registro 2. As mesmas teclas na ajuda | OK pela tela (captura `13_pdv`; a barra igual à do registro e à de antes) |
| CT-F21-30 | Usuário de compras, claro de 100%, entrada aberta | 1. Compras › escolher a entrada aberta 2. Tab a partir do campo do produto | 1. A linha de inclusão do item cabe numa linha 2. Produto → Quantidade → modo → valor | OK pela tela (captura `claro100_14_compras_entrada`; o Tab conferido a partir da lista de entradas) |
| CT-F21-31 | Impressora térmica | 1. Configurações › Etiquetas › Exemplo ZPL (ou DPL) › Imprimir teste 2. Imprimir a etiqueta de um produto com faixa e de um sem faixa | 1. A etiqueta de teste traz "A partir de 6 UN: R$ 11,90" 2. Com faixa, a linha de atacado; sem faixa, a linha não sai | Pendente (pessoa: impressora de verdade); `ModeloTermicaTest` OK |
| CT-F21-32 | Qualquer tela | 1. Tab e Shift+Tab com o teclado de verdade 2. Excluir, cancelar, estornar ou inativar algo em cada tela | 1. Ordem lógica, foco inicial no primeiro campo 2. Sempre pergunta antes (lista no PROGRESSO) | Pendente (pessoa: teclado e mouse de verdade) |
