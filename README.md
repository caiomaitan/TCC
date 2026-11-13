# VareSync

ERP desktop simplificado para mercados, mercearias e lojas de conveniência: produtos, estoque, PDV com caixa,
inventário, compras e indicadores de rentabilidade (lucro bruto e margem). TCC de Engenharia de Software —
Universidade de Ribeirão Preto.

A especificação completa está em [`docs/ESPECIFICACAO.md`](docs/ESPECIFICACAO.md); o andamento, as decisões e as
métricas, em [`docs/PROGRESSO.md`](docs/PROGRESSO.md); o roteiro de testes manuais, em
[`docs/ROTEIRO_TESTES.md`](docs/ROTEIRO_TESTES.md).

Sumário: [1 Pré-requisitos](#1-pré-requisitos) · [2 Criação do banco](#2-criação-do-banco) ·
[3 Instalação do zero](#3-instalação-do-zero) · [4 Primeiro acesso](#4-primeiro-acesso) ·
[5 Configuração local e logs](#5-configuração-local-e-logs) ·
[6 Banco de demonstração e usuários de demonstração](#6-banco-de-demonstração-e-usuários-de-demonstração) ·
[7 Backup e restauração](#7-backup-e-restauração) · [8 Balança](#8-balança) ·
[9 Empacotamento (jpackage)](#9-empacotamento-jpackage) · [10 Testes](#10-testes) · [11 Estrutura](#11-estrutura)

## 1. Pré-requisitos

| Programa | Versão | Para quê |
|---|---|---|
| Windows | 10 ou 11, 64 bits | o pacote `VareSync.exe` é para Windows |
| PostgreSQL | 14 ou mais novo (testado no 16), porta 5432 | banco de dados — **sempre** |
| JDK | 21 (ex.: Eclipse Temurin 21), com `JAVA_HOME` apontando para ele | só para compilar, rodar pelo código-fonte ou gerar o pacote |
| Maven | 3.9 ou mais novo, `mvn` no PATH | idem |

Quem só **usa** o sistema precisa do PostgreSQL e da pasta `VareSync` gerada pelo empacotamento (seção 9): o Java vai
dentro dela.

Para compilar, confira o ambiente com `mvn -v`: a linha `Java version` deve mostrar 21. O `java -version` do PATH pode
mostrar outra versão (no Windows é comum um Java 8 da Oracle vir antes no PATH); o projeto usa o JDK do `JAVA_HOME`.

Na instalação do PostgreSQL (instalador de https://www.postgresql.org/download/windows), anote a senha do usuário
`postgres` e mantenha a porta 5432. O Stack Builder, no fim, pode ser desmarcado.

## 2. Criação do banco

Conectado ao PostgreSQL como `postgres` (no psql, ou no DBeaver/pgAdmin com auto-commit ligado), troque
`troque-esta-senha` por uma senha sua e execute:

```sql
CREATE ROLE varesync LOGIN PASSWORD 'troque-esta-senha';
CREATE DATABASE varesync      OWNER varesync ENCODING 'UTF8' TEMPLATE template0;
CREATE DATABASE varesync_test OWNER varesync ENCODING 'UTF8' TEMPLATE template0;
```

- `varesync` é o banco do sistema. As tabelas são criadas pelo próprio sistema (Flyway) na primeira execução: não
  rode nenhum script à mão.
- `varesync_test` só é usado pelos testes automáticos (seção 10) e **é apagado e recriado a cada execução dos
  testes**. Quem só usa o sistema pode deixá-lo de fora.
- Se o DBeaver mostrar "CREATE DATABASE cannot run inside a transaction block", ligue o auto-commit e execute de novo.

## 3. Instalação do zero

### 3.1 Pelo pacote (computador da loja)

1. Instale o PostgreSQL e crie o banco (seções 1 e 2).
2. Gere a pasta `VareSync` (seção 9) ou copie-a de quem a gerou. Coloque-a onde quiser, por exemplo `C:\VareSync`.
   Dentro dela ficam `VareSync.exe`, a pasta `app` (o sistema) e a pasta `runtime` (o Java).
3. Crie um atalho: clique com o botão direito em `VareSync.exe` › *Enviar para* › *Área de trabalho (criar atalho)*.
4. Abra o `VareSync.exe`. Se o Windows mostrar "O Windows protegeu o computador" (o pacote não é assinado), clique em
   *Mais informações* › *Executar assim mesmo*.
5. Siga a seção 4 (primeiro acesso).

Para desinstalar, apague a pasta `VareSync`. Os dados ficam no banco; a configuração e os logs, em
`C:\Users\<você>\.varesync`.

### 3.2 Pelo código-fonte (desenvolvimento)

1. Instale JDK 21, Maven e PostgreSQL e crie o banco (seções 1 e 2).
2. Na pasta do projeto, rode:

   ```bash
   mvn javafx:run
   ```

3. Siga a seção 4 (primeiro acesso).

O passo a passo do ambiente de desenvolvimento no Cursor (variáveis de ambiente, DBeaver) está em
[`COMO_USAR.md`](COMO_USAR.md).

## 4. Primeiro acesso

1. **Conexão com o banco.** Na primeira execução o sistema cria `~/.varesync/config.properties` (seção 5) sem a
   senha, e por isso abre a tela **Conexão com o banco**. Confira o endereço
   (`jdbc:postgresql://localhost:5432/varesync`) e o usuário (`varesync`), digite a senha criada na seção 2, clique
   em **Testar** e depois em **Salvar**. O sistema conecta e cria as tabelas (as migrations em
   `src/main/resources/db/migration`). Essa tela volta sempre que a conexão falhar (servidor parado, senha trocada).
2. **Assistente de primeiro acesso.** Com o banco sem usuários, o sistema pede o **nome da loja** e cria o
   **supervisor** (nome, login, senha e confirmação). Não existe usuário nem senha padrão. A senha tem de 6 a 50
   caracteres e é diferente do login (a regra pode ficar mais forte depois: veja **Política de senha** abaixo).
3. **Login.** Entre com o supervisor. Ele tem acesso a tudo: cadastre os demais usuários em **Usuários**, com as
   rotinas de cada um e uma senha provisória, que a pessoa troca no primeiro login. Em **Minhas preferências** (rodapé
   do menu, para todos) cada usuário escolhe o tema (claro ou escuro) e o tamanho da fonte (100%, 115% ou 130%); a
   escolha fica gravada para o usuário, vale na hora e em qualquer computador, e a tela de entrada continua no padrão
   (claro, 100%). O comprovante em tela, os PDFs e as etiquetas continuam claros.
   Em qualquer tela do menu, **F1** abre a ajuda da tela num painel à direita, com a lista de atalhos dela (no PDV
   também), e **Ctrl+K** abre a busca: produto (nome, código, código de barras, inclusive o da caixa, ou PLU),
   cliente (nome ou CPF), venda pelo número (segunda via do comprovante), fornecedor ou tela, só com o que o usuário
   pode abrir. No PDV o Ctrl+K não abre (use F2).
4. **Configurações** (só o supervisor): dados da loja (aparecem no comprovante; em Loja ficam também o regime
   tributário, a UF e a inscrição estadual, usados no cadastro fiscal), parâmetros de venda (estoque
   negativo, desconto máximo do operador, cancelamento de item com supervisor, prazo de devolução), margem mínima,
   balança, segurança (bloqueio por inatividade e política de senha), sugestão de compra, alerta de validade dos lotes,
   backup e formas de pagamento.
5. **Unidades de medida** (Produtos › *Unidades…*, com a permissão Produtos): além das que vêm prontas (UN, KG, L,
   M, CX, PCT, FD, DZ), cadastre outras com sigla de até 6 letras ou números, descrição e "fracionada" (aceita
   quantidade com casas decimais). A sigla e "fracionada" só mudam, e a unidade só é excluída, enquanto nenhum produto
   a usa; a descrição muda sempre. UN é a unidade padrão e não muda de sigla.
6. **Clientes** (menu Clientes, com a permissão Clientes):
   - cadastro com nome, CPF/CNPJ (único, com o dígito verificador conferido), contatos, endereço e o consentimento
     de marketing, que fica registrado com a data;
   - aba **Compras**: histórico, total gasto, ticket médio, última compra e produtos mais comprados;
   - aba **Crédito**: saldo de crédito do cliente e o extrato (cashback, uso no pagamento, estornos e ajustes). O
     supervisor faz **Ajustar…** (valor positivo credita, negativo debita, com motivo; fica na auditoria);
   - aba **LGPD**: **Exportar** os dados do cliente em PDF ou CSV, a pedido dele (cadastro, compras e extrato de
     crédito; fica na auditoria) e **Anonimizar** (só o supervisor, digitando o nome do cliente para confirmar).
     Anonimizar apaga os dados pessoais e mantém as vendas e o extrato, que passam a mostrar "Cliente anonimizado nº X".
     Só é possível com o saldo de crédito em zero. Não pode ser desfeito.

   No PDV, **F11** identifica o cliente da venda pelo CPF/CNPJ (Enter já identifica) ou pelo nome, com cadastro rápido
   (nome e CPF), e permite trocar ou remover o cliente até finalizar. Basta a permissão PDV. O F11 e o painel do PDV
   mostram o saldo de crédito. O comprovante mostra o nome e o CPF mascarado (`***.456.789-**`). O relatório **R18
   Clientes** mostra as compras de cada cliente no período.
7. **Cashback e crédito do cliente:** em **Configurações › Fidelidade** (só o supervisor), ligue o cashback e defina o
   percentual (padrão 1%) e a compra mínima. Com ele ligado, a venda com cliente identificado gera crédito: o
   percentual do total sem o que foi pago com crédito, se esse valor alcançar a compra mínima. No pagamento (F10), a
   forma **Crédito do cliente** usa o saldo, até o saldo e até o que falta, sem troco; ela não entra no dinheiro da
   gaveta. Cancelar a venda devolve o crédito usado e estorna o cashback (o saldo pode ficar negativo e é abatido
   pelos próximos créditos). O comprovante mostra o cashback gerado e o saldo. O relatório **R19 Saldos de crédito**
   lista quem tem saldo e o total devido aos clientes; **Configurações › Diagnóstico** confere os saldos.
8. **Devoluções e trocas** (menu Devoluções, com a permissão Devoluções e trocas; no PDV, **F9 › Devolver itens…**,
   Ctrl+D, para quem tem a permissão PDV, com liberação de quem tem Devoluções e trocas):
   - localize a venda pelo número, pelo período (padrão: os últimos 30 dias) ou pelo cliente (nome ou CPF/CNPJ) e
     clique em **Devolver itens…**;
   - informe a quantidade de cada item (até o que foi vendido menos o já devolvido; inteira em unidade como UN) e
     marque **Avariado** no item que não volta para a prateleira (ele entra e sai do estoque como perda, com o motivo
     "Devolução nº X — avariado: …"); o valor é o proporcional do item, com os descontos, e a última devolução do item
     leva o restante;
   - escolha o motivo e a forma de reembolso: **Dinheiro** (sai da gaveta do caixa aberto neste terminal e aparece no
     fechamento), **Vale-troca** (vira crédito do cliente; numa venda sem cliente, identifique ou cadastre na hora) ou
     **Outro** (estorno feito fora do sistema, no cartão ou no PIX);
   - o cashback da venda é estornado na proporção do valor devolvido; o comprovante de devolução mostra os itens, o
     reembolso e, quando há cliente, o saldo de crédito;
   - depois de **Configurações › Venda › Prazo de devolução** (padrão 30 dias; 0 = sempre), a devolução pede a
     liberação de um supervisor. Venda com devolução não pode mais ser cancelada: para desfazê-la, devolva os itens
     que faltam. Cliente anonimizado não recebe vale-troca; o saldo que sobrar dele o supervisor zera em **Clientes ›
     Crédito › Ajustar…**;
   - o relatório **R20 Devoluções** lista as devoluções do período, com os itens, o motivo e a forma de reembolso;
   - os relatórios de venda e lucro (R01, R02, R05 a R10, R18), o painel e a aba **Compras** do cliente descontam as
     devoluções na data em que foram feitas: receita líquida = vendas − devoluções. O R01 mostra as colunas Vendas,
     Devoluções e Receita líquida (o nº de vendas e o ticket médio contam só as vendas), e o R08 mostra, à parte, os
     reembolsos por forma.
9. **Crediário** (no PDV, forma de pagamento **Crediário**, com o cliente identificado por F11):
   - escolha as parcelas (até 3, no padrão) e o vencimento da 1ª (padrão: daqui a 30 dias); as demais vencem de 30 em 30
     dias, e os centavos que sobram ficam na 1ª. Pode ser combinado com outras formas; a parte no crediário não gera
     cashback, e o comprovante mostra as parcelas;
   - passar do limite do cliente, ou vender a quem tem título vencido há mais de 30 dias, pede a permissão Gerenciar
     crédito do crediário ou a liberação de quem a tem. O limite e o bloqueio ficam em **Clientes**, aba **Crediário**,
     com o saldo devedor, as parcelas e o extrato (em tela e PDF); as regras gerais, em **Configurações › Crediário**;
   - **receber** é na tela **Caixa** ou no PDV (**Ctrl+R**), com o caixa aberto: escolha o cliente, selecione os títulos
     ou digite um valor (vai do vencimento mais antigo para o mais novo) e pague como no PDV. Título em atraso paga multa
     (2%) e juros simples (2% ao mês), depois da carência; o pagamento parcial divide o valor entre principal e encargos;
   - o desconto nos encargos pede a permissão Gerenciar crédito ou a liberação, e vale para quitar os títulos. O estorno
     é na lista de recebimentos da tela Caixa, só na mesma sessão de caixa;
   - os relatórios são o **R21 Títulos a receber** (com as faixas de atraso) e o **R22 Recebimentos**; o Painel avisa os
     títulos vencidos;
   - a venda só é cancelada sem pagamento em nenhuma parcela, e os títulos são cancelados junto;
   - na devolução, o valor abate primeiro as parcelas em aberto da venda, da última para a primeira; o restante vai na
     forma escolhida (a devolução abatida por inteiro não mexe no caixa);
   - cliente com títulos em aberto não pode ser anonimizado.
10. **Financeiro** (menu **Financeiro**, permissão Financeiro):
   - **contas:** a instalação cria o **Cofre** (padrão do caixa) e o **Banco**. Lance o saldo inicial de cada conta
     (uma vez; depois, só ajuste com motivo) e faça transferências entre elas. O saldo pode ficar negativo (aparece em
     vermelho) e nunca bloqueia o caixa; o extrato mostra cada lançamento com o saldo depois dele;
   - **integração com o caixa:** em **Configurações › Financeiro** (vem desligada; ligue depois de lançar os saldos
     iniciais). Ligada, a abertura retira o fundo de troco de uma conta, a sangria entra numa conta, o suprimento sai de
     uma conta e o fechamento leva o dinheiro contado para uma conta; os diálogos já trazem a conta padrão. O caixa
     segue como foi aberto até o fechamento;
   - **recebíveis:** em **Configurações › Formas de pagamento**, escolha para cada forma a taxa (%), os dias para receber
     e a conta de destino (a instalação aponta PIX, cartões e vale para o Banco, com taxa 0 e 0 dias). Cada pagamento
     numa forma com conta, na venda ou no recebimento do crediário, vira um recebível; com 0 dias, já entra na conta.
     Na aba **Recebíveis**, selecione os previstos, corrija o líquido se o banco pagou diferente e **Concilie** (o total
     do extrato, se informado, precisa bater). Cancelar a venda ou estornar o recebimento cancela os previstos e
     estorna da conta os já recebidos;
   - **contas a pagar:** despesa avulsa com categoria, competência (o mês da despesa) e vencimento, com repetição
     mensal de até 12 meses; pagamento parcial, com juros e desconto, de uma conta; estorno com motivo (reabre a conta) e
     cancelamento sem pagamento. Em **Compras**, o botão **Parcelas…** registra as duplicatas da nota (o XML da NF-e já
     as traz); ao confirmar a entrada, cada parcela vira uma conta a pagar em "Compra de mercadorias";
   - na **devolução** com reembolso **Outro**, informe a conta de onde saiu o dinheiro; só a parte que não abateu
     parcelas do crediário sai da conta;
   - **fluxo de caixa** (aba própria): escolha o período (começa hoje ou antes) e a visão por dia, semana ou mês. O
     realizado são os lançamentos das contas por tipo (sem transferências entre contas e sem o saldo inicial, que entra
     no saldo de partida; ajustes numa linha própria); o previsto, de hoje em diante, são os recebíveis a receber, as
     parcelas do crediário e as contas a pagar em aberto. O que já venceu e continua em aberto aparece em "Em atraso",
     no primeiro dia da projeção. A tabela mostra o saldo no fim de cada período e o gráfico, entradas, saídas e saldo;
   - **relatórios** (com as permissões Relatórios e Financeiro): **R23 Contas a pagar** (situação, categoria,
     fornecedor e vencimento), **R24 Fluxo de caixa** e **R25 DRE gerencial** (mês escolhido e o anterior, com o % de
     cada linha sobre a receita líquida; despesas pela competência; compras de mercadoria ficam de fora porque o custo
     entra pelo CMV; a receita líquida e o lucro bruto batem com o R01 e o R05);
   - **painel:** quem tem Financeiro vê o saldo das contas, o resultado líquido do mês até hoje e os alertas de contas a
     pagar vencidas e vencendo hoje e de conta com saldo negativo (o clique abre o Financeiro já filtrado).
11. **Compras: fornecedor do produto, sugestão e pedido de compra:**
   - **fornecedores do produto** (cadastro do produto, aba **Fornecedores**, permissão Produtos): para cada fornecedor,
     o código do produto na nota dele (`cProd`), a unidade de compra (ex.: CX), o **fator** (quantas unidades de venda
     vêm em 1 unidade de compra: caixa com 12 = 12) e o prazo de entrega em dias. O primeiro fornecedor já fica como
     **principal**; o principal dá o prazo e o fator da sugestão;
   - **XML da NF-e:** item sem código de barras reconhecido é achado pelo código do fornecedor; a quantidade vira a da
     nota × fator e o custo, o da nota ÷ fator (2 caixas de 12 = 24 unidades). Ao escolher o produto de um item à mão, a
     conferência pergunta o fator e guarda o vínculo para as próximas notas do fornecedor. A entrada confirmada grava o
     último custo do fornecedor;
   - **sugestão de compra** (tela **Compras**, aba **Sugestão de compra**, permissão Compras; F5 calcula): por
     fornecedor principal ou por categoria. Média por dia = venda dos últimos 30 dias (já sem as devoluções) ÷ 30;
     sugestão = o que falta para cobrir 15 dias mais o prazo de entrega, ou para chegar ao estoque mínimo (o maior),
     arredondado para cima no fator. Mostra a cobertura atual em dias, a sugestão também na unidade de compra, o último
     custo e o valor estimado; produto sem saldo que vendeu nos últimos 30 dias aparece em vermelho (ruptura). Os 30 e os
     15 dias mudam em **Configurações › Compras**. O que falta receber dos pedidos enviados e parciais (**Pendente**)
     é descontado. Fornecedor principal inativo deixa de valer (prazo 0 e fator 1, linha em amarelo);
   - **gerar pedido:** marque as linhas na coluna **Pedir** (ou **Marcar os com sugestão**) e clique em **Gerar
     pedido…**: sai um rascunho para cada fornecedor principal, com a sugestão e o último custo; produto sem fornecedor
     principal ativo fica de fora, com aviso;
   - **pedidos de compra** (tela **Compras**, aba **Pedidos**, Ctrl+N cria um): o rascunho recebe os itens (quantidade
     na unidade de venda e custo previsto); **Enviar pedido…** o fecha para edição, e **PDF do pedido…** gera o arquivo
     para o fornecedor, com as quantidades também em caixas. Fornecedor inativo não recebe pedido novo. **Cancelar
     pedido…** vale para rascunho, enviado ou parcial;
   - **entrada com pedido:** na entrada aberta, **Pedido…** escolhe o pedido enviado do mesmo fornecedor que a nota
     atende, e **Conferir com o pedido…** mostra o que faltava, o que veio e os custos, com as diferenças em destaque.
     Ao confirmar a entrada, as quantidades entram como recebidas e o pedido fica **Parcial** ou **Recebido**;
   - **relatórios:** **R26 Sugestão de compra**, **R27 Pedidos pendentes** (o que falta receber, com os atrasados) e
     **R28 Cobertura e ruptura** (quantos dias o estoque dura e quais produtos estão em ruptura);
   - **painel:** quem tem Compras vê o alerta de produtos em ruptura; o clique abre a Sugestão de compra só com eles.
12. **Validade e lotes** (perecíveis):
   - **ligar:** no cadastro do produto, marque **Controla validade e lotes**. No produto novo com estoque inicial, informe
     o lote (código opcional) e a validade desse estoque; sem eles, ele fica no **"SEM LOTE"** (validade desconhecida).
     Num produto que já tem saldo, a tela avisa que o saldo atual vai para o "SEM LOTE"; depois, em **Estoque › aba
     Lotes › Transferir…**, passe esse saldo para os lotes certos. Ao desligar, os lotes ficam parados e saem dos alertas;
   - **entrada de mercadoria:** o item de produto com controle fica em amarelo até ser dividido em lotes pelo botão
     **Lotes…** (código, validade e quantidade, com o quanto falta dividir); a entrada só confirma com a divisão
     completa. O XML da NF-e com o grupo `rastro` já traz a divisão;
   - **saídas:** a venda tira primeiro do "SEM LOTE" e depois do lote que vence antes (FEFO), deixando os vencidos por
     último; o ajuste de saída e a perda deixam escolher o lote ("Automático" = a mesma regra, sem pular os vencidos), e
     a **perda por vencimento** exige o lote. Cancelamento e devolução voltam para os lotes de onde a mercadoria saiu;
   - **aba Lotes do Estoque** (permissão Estoque): lote, validade, saldo, dias para vencer e situação (vencido e "SEM
     LOTE" negativo em vermelho; vencendo em até 7 dias em amarelo), o razão do lote selecionado e **Transferir…**
     (para outro lote ou um lote novo; não muda o estoque do produto). O filtro **Só com SEM LOTE negativo** acha os
     produtos que venderam mais do que os lotes tinham;
   - **R29 Validade** e **painel:** lotes que vencem em até N dias (e, se marcado, os vencidos), com o valor a custo;
     quem tem Estoque vê no painel "N lotes vencem em até 7 dias", "N lotes vencidos" e os produtos com o "SEM LOTE"
     negativo. Os 7 dias mudam em **Configurações › Estoque**.

Cinco senhas erradas seguidas bloqueiam o usuário por 5 minutos; o supervisor pode desbloquear antes, em
**Usuários**.

**Segurança da sessão.**
- **Bloqueio por inatividade:** depois de 15 minutos sem teclado nem mouse (padrão), a tela mostra **Sessão
  bloqueada** por cima do que estava aberto, inclusive a venda em andamento no PDV. Para continuar, a pessoa logada
  digita a própria senha; senha errada conta para o bloqueio de 5 tentativas. **Trocar usuário** faz o log off (e
  pergunta antes se há venda em andamento). O tempo fica em **Configurações › Segurança** (0 desliga) e vale a partir
  do próximo login.
- **Acesso alterado pelo supervisor:** o que o supervisor muda em **Usuários** vale na próxima ação de quem está
  logado, em qualquer computador. Permissão retirada: a ação é recusada e o menu deixa de mostrar a rotina, sem log
  off. Usuário inativado ou com a senha redefinida: o sistema volta ao login com o aviso "Seu acesso foi alterado pelo
  supervisor" (no PDV, a venda em andamento é abandonada e fica na auditoria).
- **Política de senha:** em **Configurações › Segurança**, o tamanho mínimo (de 6 a 50; padrão 6) e, se marcado,
  letras e números obrigatórios. Vale para senhas novas e trocadas (primeiro acesso, troca de senha e senha
  provisória); as telas mostram a regra, e a senha recusada mostra a regra na mensagem. As senhas atuais continuam
  valendo até a próxima troca.

## 5. Configuração local e logs

Arquivo `~/.varesync/config.properties` (no Windows, `C:\Users\<você>\.varesync\config.properties`), criado na
primeira execução:

```properties
db.url=jdbc:postgresql://localhost:5432/varesync
db.usuario=varesync
db.senha=
terminal.id=CAIXA-01
```

- `db.senha` é preenchida pela tela **Conexão com o banco**. O arquivo fica na pasta do usuário, fora do repositório.
- `terminal.id` identifica o computador no controle de caixa e **deve ser único por máquina**. Com dois caixas, troque
  no segundo para `CAIXA-02` (com o sistema fechado).
- Com vários computadores, veja a seção 5.1.

Logs: `~/.varesync/logs/varesync.log`, um arquivo por dia, mantidos 30 dias. Erros técnicos vão para o log; a tela
mostra só a mensagem para o usuário.

### 5.1 Instalação em rede (um servidor e vários terminais)

Numa loja com mais de um computador, o banco fica num PC só, o **servidor**, e o VareSync de cada caixa ou escritório,
os **terminais**, se conecta a ele pela rede da loja. O servidor pode ser também um dos caixas, mas precisa estar
ligado sempre que alguém for usar o sistema.

**No servidor**

1. Dê ao PC um **IP fixo** na rede da loja (no roteador, reservando o IP para ele, ou nas configurações de rede do
   Windows). Nos exemplos: servidor `192.168.0.10`, rede da loja `192.168.0.0/24`.
2. Instale o PostgreSQL 16 e crie o usuário e o banco como na seção 2, com uma senha forte.
3. **Aceitar conexões da rede:** no `postgresql.conf` (no Windows, `C:\Program Files\PostgreSQL\16\data`), troque
   `listen_addresses`:

   ```
   listen_addresses = 'localhost,192.168.0.10'
   ```

4. **Liberar só a rede da loja:** no fim do `pg_hba.conf` (na mesma pasta), acrescente uma linha para o banco e o
   usuário do VareSync, só com a faixa da loja. Não use `0.0.0.0/0` (qualquer endereço) nem `trust` (sem senha):

   ```
   # TIPO  BANCO     USUÁRIO   ENDEREÇO          MÉTODO
   host    varesync  varesync  192.168.0.0/24    scram-sha-256
   ```

5. Reinicie o serviço do PostgreSQL (PowerShell como administrador): `Restart-Service postgresql-x64-16`.
6. **Firewall:** libere a porta 5432 só para a rede da loja (PowerShell como administrador):

   ```powershell
   New-NetFirewallRule -DisplayName "PostgreSQL (VareSync)" -Direction Inbound -Protocol TCP -LocalPort 5432 `
       -RemoteAddress 192.168.0.0/24 -Action Allow
   ```

   Não abra a porta 5432 no roteador para a internet.
7. **Backup no servidor:** o `pg_dump` vem com o PostgreSQL, então é no servidor que o backup funciona (seção 7). Abra o
   VareSync no servidor e, em **Configurações › Backup**, informe uma pasta local dele (ex.: `D:\VareSync\backup`) e,
   se preciso, o caminho do `pg_dump`. O backup automático roda no computador de quem sai primeiro do sistema no dia;
   num terminal sem o `pg_dump`, ele só registra a falha no log (o supervisor vê um aviso), e o backup daquele dia fica
   para o manual. Por isso, o supervisor faz o backup manual pelo servidor ou deixa o servidor ser o último a fechar o
   sistema. Copie a pasta de backup para fora do PC (pen drive ou nuvem) de vez em quando.

**Em cada terminal**

1. Instale o VareSync (seção 3.1). O PostgreSQL **não** precisa ser instalado nos terminais.
2. Abra o sistema uma vez: sem conexão, aparece a tela **Conexão com o banco**. Informe o endereço com o IP do
   servidor, `jdbc:postgresql://192.168.0.10:5432/varesync`, o usuário `varesync` e a senha, e clique em
   **Testar conexão**: a tela mostra a versão do PostgreSQL do servidor e o tempo de resposta (na rede da loja, poucos
   milissegundos; acima de 100 ms, confira o cabo ou o Wi-Fi). Depois, **Salvar**.
3. Com o sistema fechado, troque o `terminal.id` em `~/.varesync/config.properties` para um nome **único** por PC
   (`CAIXA-01`, `CAIXA-02`, `ESCRITORIO`…). Dois PCs com o mesmo nome dividiriam o mesmo caixa.

O primeiro acesso (seção 4) é feito uma vez só, no primeiro computador que abrir o sistema; os outros já mostram o
login. Vários terminais podem vender ao mesmo tempo: o banco trava o saldo de cada produto na mesma ordem, e as
situações de disputa (duas vendas do mesmo produto, duas sangrias, o mesmo crédito do cliente, a mesma parcela) são
testadas em `ConcorrenciaIT` (seção 10).

## 6. Banco de demonstração e usuários de demonstração

O gerador de dados de demonstração (**Configurações › Dados de demonstração**, só para o supervisor) preenche um banco
com 60 dias de operação de um mercado fictício, terminando ontem: cerca de 80 produtos (12 pesáveis, com PLU), 3
usuários, 3 fornecedores com uma entrada de mercadoria por semana, um caixa por dia com vendas, sangrias e fechamento,
descontos, cancelamentos, perdas, reajustes de preço e um inventário aplicado, 150 clientes (cerca de 40% das vendas
identificadas, com cashback de 1% e alguns pagamentos com o crédito) e cerca de 0,5% dos itens devolvidos de 1 a 5
dias depois da venda, em dinheiro, vale-troca ou outro, alguns avariados. No financeiro, liga a integração do caixa
com R$ 5.000,00 de saldo inicial no Cofre, põe taxa de 1% (1 dia) no débito, 3% (30 dias) no crédito e 0% (mesmo dia)
no PIX, deposita o Cofre no Banco e concilia os recebíveis toda segunda-feira (até o domingo anterior), lança aluguel,
energia, salários e os impostos de 6% da receita de cada mês (pagos no vencimento; a última conta de energia fica em
aberto) e registra duas duplicatas por entrada de mercadoria. Em compras, vincula cada produto reposto ao seu
fornecedor (caixas de 6, 12 ou 24, prazo de 3 dias) e lança um pedido de compra enviado 3 dias antes de cada entrada,
que a entrada recebe; o pedido da próxima entrega de cada fornecedor fica pendente. Os perecíveis embalados de Frios e
laticínios e Padaria controlam validade: cada entrada semanal vira um lote, a sobra vencida vira perda por vencimento
e, no fim, há lotes vencendo nos próximos dias e lotes vencidos da última semana com saldo (no painel e no R29). No
cadastro fiscal, põe a UF SP, cinco perfis tributários do Simples e o NCM e a origem de cada produto, com oito produtos
com inconsistências de propósito (o R30 lista os oito; o painel conta os seis com erro). Ele **recusa** um banco que já tenha vendas ou
movimentos nas contas. Use um banco separado:

1. Conectado ao PostgreSQL como `postgres`, crie o banco (o usuário `varesync` já existe):

   ```sql
   CREATE DATABASE varesync_demo OWNER varesync ENCODING 'UTF8' TEMPLATE template0;
   ```

2. Com o sistema fechado, edite `~/.varesync/config.properties` e troque o nome do banco no fim da URL:

   ```properties
   db.url=jdbc:postgresql://localhost:5432/varesync_demo
   ```

3. Abra o sistema. As tabelas são criadas e o assistente de primeiro acesso cria a loja e o supervisor.
4. Entre como supervisor, abra **Configurações › Dados de demonstração** e clique em **Gerar dados de
   demonstração…** (leva alguns segundos; a barra mostra o andamento). No fim aparecem os usuários criados.
5. Para voltar ao banco normal, feche o sistema e devolva `db.url` para `.../varesync`.

**Usuários de demonstração** (a semente é fixa, então as senhas provisórias são sempre estas; cada um troca a senha
no primeiro login):

| Login | Nome | Perfil | Senha provisória |
|---|---|---|---|
| `maria` | Maria Oliveira | Operadora de caixa (PDV) | `demo1835` |
| `jose` | José Santos | Operador de caixa (PDV) | `demo9076` |
| `carlos` | Carlos Pereira | Gerente (todas as permissões, sem ser supervisor) | `demo6999` |

O supervisor é o criado por você no assistente. Os dados de demonstração também ativam a balança (prefixo 2, 5
dígitos, valor em preço), a fidelidade (cashback de 1%, sem compra mínima) e a integração do caixa com as contas.

Para recomeçar a demonstração do zero (ou se a geração for interrompida no meio, o que deixa dados pela metade), com o
sistema fechado:

```sql
DROP DATABASE varesync_demo;
CREATE DATABASE varesync_demo OWNER varesync ENCODING 'UTF8' TEMPLATE template0;
```

Gerado no mesmo dia, o resultado é sempre o mesmo; as datas acompanham o dia em que você gera.

## 7. Backup e restauração

O backup usa o `pg_dump` do PostgreSQL (formato custom, `-Fc`). Ele precisa ser da **mesma versão do servidor ou mais
novo**; o sistema confere antes de começar.

- **Manual**: Configurações › Backup › *Fazer backup agora* (só o supervisor).
- **Automático**: ao sair do sistema (botão Sair ou fechar a janela), se ainda não houve backup no dia — no máximo um
  por dia, contando todos os computadores.
- **Onde**: na pasta configurada em Configurações › Backup; vazia, em `~/.varesync/backups`. O arquivo se chama
  `varesync_AAAAMMDD_HHmm.backup`; ficam só os últimos N (padrão 15), os mais antigos são apagados.
- **Qual `pg_dump`**: o informado em Configurações › Backup; vazio, o do PATH ou o de
  `C:\Program Files\PostgreSQL\<versão>\bin` (o de versão mais alta).
- A senha do banco vai para o `pg_dump` pela variável `PGPASSWORD` do processo, nunca na linha de comando.
- **Backup conferido:** depois do `pg_dump`, o sistema lê o arquivo com o `pg_restore --list` da **mesma pasta** do
  `pg_dump` (a instalação do PostgreSQL traz os dois). Se o `pg_restore` faltar ou não conseguir ler o arquivo, o
  backup é descartado, quem fez vê o aviso e ele não conta como "último backup" no Painel.
- O Painel avisa o supervisor quando o último backup tem mais de 7 dias. Guarde cópias também fora do computador
  (pen drive, nuvem).

**Restauração** (fora do sistema): feche o VareSync em **todos** os computadores e, num PowerShell, rode (troque a
versão do PostgreSQL e o caminho do arquivo; a senha pedida é a do usuário `varesync`):

```powershell
& "C:\Program Files\PostgreSQL\16\bin\pg_restore.exe" --clean --if-exists -h localhost -U varesync -d varesync C:\caminho\varesync_20261021_2200.backup
```

Ou restaure num banco novo, para conferir antes de substituir o de uso. Como `postgres`:

```sql
CREATE DATABASE varesync_restaurado OWNER varesync ENCODING 'UTF8' TEMPLATE template0;
```

```powershell
& "C:\Program Files\PostgreSQL\16\bin\pg_restore.exe" -h localhost -U varesync -d varesync_restaurado C:\caminho\varesync_20261021_2200.backup
```

Para usar o banco restaurado, aponte `db.url` em `~/.varesync/config.properties` para ele. A restauração completa
funciona com os triggers do banco (saldos, histórico de preço).

## 8. Balança

Com a balança ativa (Configurações › Balança), o PDV lê a etiqueta impressa pela balança (EAN-13 com o prefixo, o PLU
do produto e o preço ou o peso). Configure igual à balança: prefixo, quantidade de dígitos do PLU e o tipo de valor.

**Atenção no modo `PRECO`** (a etiqueta traz o total em R$): o sistema cobra o valor impresso e calcula a quantidade
baixada do estoque dividindo esse valor pelo preço do quilo **do sistema** (inclusive o promocional, se houver
promoção vigente). Por isso a balança tem de estar programada com **o mesmo preço por quilo** do cadastro, e cada
reajuste ou promoção de produto pesável tem de ser feito também na balança. Com preços diferentes, o estoque baixa uma
quantidade diferente da pesada. No modo `PESO` (a etiqueta traz os gramas), o sistema calcula o total com o próprio
preço e esse cuidado não é necessário.

**Carga de balança** (Produtos › Carga de balança…, Fase 18): grava o arquivo de itens da balança Toledo
(`ITENSMGV.TXT`, formato do MGV6) na pasta de Configurações › Balança, para o MGV importar e mandar à balança. A carga
leva todos os produtos **ativos, vendidos em KG, com PLU e preço maior que zero**, com o preço por kg vigente no dia
(inclusive promoção), a descrição e os dias de validade (campo "Validade na balança" no cadastro do produto, de 1 a
990; vazio = a balança não imprime validade). A tela marca em amarelo o que está **pendente**: nunca carregado, ou com
PLU, preço, descrição ou validade diferente da última carga. O Painel avisa "Carga de balança pendente" e "produtos com
etiqueta pendente" para quem tem a permissão Produtos. Gere a carga depois de cada reajuste ou promoção de pesável e
no começo e no fim de cada promoção.

Uma linha por produto, 150 caracteres e fim de linha CRLF (só a parte fixa do layout de itens da ajuda do MGV6; os
campos da versão 4 depois da posição 150 não são gravados):

| Pos. | Tam. | Campo | Valor gravado |
|---|---|---|---|
| 1–2 | 2 | departamento | `01` |
| 3 | 1 | tipo de produto | `0` (venda por peso) |
| 4–9 | 6 | código do item | PLU |
| 10–15 | 6 | preço por kg | em centavos (R$ 39,90 → `003990`); acima de R$ 9.999,99 a carga é recusada |
| 16–18 | 3 | dias de validade | `000` quando vazio |
| 19–43 | 25 | descrição, linha 1 | nome em maiúsculas e sem acentos, quebrado no último espaço |
| 44–68 | 25 | descrição, linha 2 | o resto do nome, cortado em 25 |
| 69–84 | 16 | informação extra, imagem e informação nutricional | zeros |
| 85 | 1 | imprime data de validade | `1` com dias de validade; `0` sem |
| 86 | 1 | imprime data de embalagem | igual à anterior |
| 87–150 | 64 | fornecedor, lote, EAN especial, versão do preço, som, tara, fracionador, campos extras 1 e 2, conservação, EAN do fornecedor | zeros |

> **Confira o formato com o manual da balança da loja antes do uso real** (MGV5, MGV6 e MGV7 têm diferenças). A tela
> mostra a linha de cada produto, para comparar com um arquivo exportado pelo próprio MGV.

**Etiquetas de gôndola** (Produtos › Etiquetas…): lista os produtos com a etiqueta pendente (preço ou promoção diferente
da última etiqueta impressa) e imprime em PDF A4 (`etiqueta.colunas` × `etiqueta.linhas` por folha, padrão 3 × 8) ou na
impressora térmica. Ao começar a usar, se as gôndolas já estão etiquetadas, use **Marcar como em dia**: sem isso, todos
os produtos com estoque aparecem pendentes. Para a térmica, em Configurações › Etiquetas escolha a impressora (o nome
dela no Windows), carregue o exemplo ZPL (Zebra) ou DPL (Datamax e Allegro), ajuste as posições ao tamanho da etiqueta
da loja e confira com **Imprimir teste** antes de usar: os exemplos são um ponto de partida e não foram testados em
todas as impressoras.

**Cadastro fiscal** (menu **Fiscal** ou Produtos › Fiscal…, com a permissão Cadastro fiscal, Fase 19): prepara uma emissão fiscal futura
(o sistema **não emite** NFC-e nem NF-e). Em **Perfis tributários**, cadastre a tributação (CFOP, CST do ICMS no regime
normal ou CSOSN no Simples, alíquota e redução de base, PIS, COFINS, código de benefício e, opcionais, o CST do IBS e
da CBS e o cClassTrib); só o formato de cada campo é conferido. Na aba **Produtos**, informe NCM, CEST, origem e perfil
de cada produto, ou aplique um perfil de uma vez aos selecionados, a uma categoria inteira ou a um NCM. Na aba
**Tabelas NCM e CEST**, importe a tabela NCM (um CSV `código;descrição` ou o JSON oficial da tabela NCM, baixado do
portal do Siscomex) e, se quiser, a tabela CEST (CSV `cest;ncm;descrição`): com elas, o código digitado tem de existir
na tabela. Ao gerar uma entrada pelo XML da NF-e, quem tem a permissão confirma o NCM, o CEST e a origem do fornecedor
nos produtos em que esses campos estão vazios (a origem 1 do fornecedor, importação direta, entra como 2, e a 6 como
7: a loja compra a mercadoria estrangeira no mercado interno).

O relatório **R30 Inconsistências fiscais** (Relatórios, com as permissões Relatórios e Cadastro fiscal) confere os
produtos ativos em 13 regras: sem NCM ou sem perfil, NCM e CEST fora do formato ou fora das tabelas importadas, CSOSN
no regime normal ou CST no Simples, CFOP, alíquota e redução de base do ICMS, código de benefício, ST sem CEST, origem
ausente, PIS e COFINS, IBS e CBS (só no regime normal) e o mesmo NCM em perfis diferentes. Cada linha diz se é erro ou
aviso; o filtro escolhe uma regra, e **Abrir produto…** (ou o duplo clique) abre o produto no Cadastro fiscal. O painel
mostra "N produtos com inconsistência fiscal" a quem tem a permissão, contando só os erros.

## 9. Empacotamento (jpackage)

Gera a pasta `VareSync` com `VareSync.exe` e o Java embutido (app-image do `jpackage`, sem instalador). Precisa do JDK
21 e do Maven (seção 1); o `jpackage` é o do JDK de `JAVA_HOME`. Não precisa de WiX nem de banco.

1. Na pasta do projeto, rode:

   ```bash
   mvn -Pempacotar clean package
   ```

   Os testes unitários rodam antes (os de integração, não). O perfil `empacotar` do `pom.xml` copia o jar do sistema
   e as bibliotecas para `target/empacotar` e chama o `jpackage --type app-image`.
2. O resultado fica em `target\dist\VareSync` (cerca de 190 MB: `VareSync.exe`, `app` e `runtime`).
3. Para conferir, abra `target\dist\VareSync\VareSync.exe`: deve abrir a tela de conexão, o assistente ou o login,
   conforme a situação do banco (seção 4).
4. Copie a pasta inteira `VareSync` para o computador da loja (seção 3.1). Ela não depende de nada do projeto nem do
   Java instalado.

A versão mostrada pelo Windows nas propriedades do `.exe` é a do `pom.xml` sem o `-SNAPSHOT` (`2.0.0-SNAPSHOT` →
`2.0.0`), porque o `jpackage` só aceita números.

### 9.1 Versão do sistema

A versão aparece no fim da barra de status da janela principal ("v2.0.0-SNAPSHOT"). Um clique nela, ou **Sobre o
VareSync…** na Ajuda (F1), abre a tela **Sobre**: versão, commit e data do build, e as versões do Java e do JavaFX.
O commit vem do Git na hora do build (plugin git-commit-id); compilado fora de um repositório Git, aparece
"desconhecido". A versão entregue ao TCC é a da tag `tcc-entrega`.

### 9.2 Instalador MSI (opcional)

Com o [WiX Toolset](https://wixtoolset.org/) 3.x instalado e no PATH (o `jpackage` do JDK 21 usa o `candle.exe` e o
`light.exe` dele), o perfil `instalador` transforma a pasta do item 9 num instalador do Windows:

```bash
mvn -Pempacotar,instalador clean package
```

O resultado fica em `target\instalador\VareSync-2.0.0.msi`: pergunta a pasta, cria o atalho na área de trabalho e no
Menu Iniciar e atualiza uma instalação anterior do VareSync. Sem o WiX, o `jpackage` para com a mensagem de que não o
encontrou e nada é gerado; a pasta do item 9 continua sendo o jeito de instalar.

Se o `mvn clean` disser "Failed to delete …\VareSync.exe", feche o VareSync aberto a partir de `target\dist` e rode
de novo; logo depois de abrir o `.exe`, o antivírus do Windows pode segurar o arquivo por alguns segundos.

## 10. Testes

```bash
mvn -q clean verify
```

- `mvn test`: só os unitários (`*Test`, sem banco).
- `mvn verify`: unitários e integração (`*IT`, no banco `varesync_test`), sem os testes de interface (seção 10.3).
- `IntegridadeBancoIT` executa `scripts/teste_integridade_banco.sql` (15 cenários, tudo desfeito no fim).
- `ConcorrenciaIT` roda 7 situações com duas operações ao mesmo tempo, em duas conexões (duas vendas com os mesmos
  produtos em ordens diferentes, duas sangrias, o mesmo produto em dois inventários, duas confirmações da mesma
  entrada, duas vendas do mesmo produto com lotes, duas vendas com o crédito do mesmo cliente e dois recebimentos da
  mesma parcela). Cada uma repete 3 vezes no `verify`; para repetir mais:
  `mvn verify -Dit.test=ConcorrenciaIT -Dtest=NenhumTest -Dsurefire.failIfNoSpecifiedTests=false -Dconcorrencia.repeticoes=20`.

Os testes de integração leem a conexão de variáveis de ambiente (a senha nunca vai para arquivo do repositório):

| Variável | Obrigatória | Padrão |
|---|---|---|
| `VARESYNC_DB_SENHA` | sim | — |
| `VARESYNC_DB_USUARIO` | não | `varesync` |
| `VARESYNC_DB_TESTE_URL` | não | `jdbc:postgresql://localhost:5432/varesync_test` |

Sem `VARESYNC_DB_SENHA`, os testes de integração falham com uma mensagem dizendo qual variável definir. No Windows:
Menu Iniciar › "variáveis de ambiente" › *Editar as variáveis de ambiente para sua conta* › Novo; depois abra um
terminal (ou o editor) **novo** para ele enxergar a variável.

O script de integridade também roda à mão: `psql -d varesync -v ON_ERROR_STOP=1 -f scripts/teste_integridade_banco.sql`,
ou no DBeaver com *Executar script* (Alt+X) — a aba Output mostra "OK 1" a "OK 15".

### 10.1 Cobertura e análise estática

```bash
mvn -Pqualidade verify
```

Roda os mesmos testes e, junto:

- **cobertura** (JaCoCo), contando os unitários e os de integração juntos: o relatório fica em
  `target/site/jacoco/index.html`, e o build falha abaixo de 80% das linhas em `util` ou de 70% em `service`;
- **SpotBugs**: o build falha com qualquer ocorrência de prioridade alta (relatório em `target/spotbugsXml.xml`);
- **Checkstyle** com as regras do projeto (`config/checkstyle.xml`): o build falha com qualquer erro, mostrado no
  console (relatório em `target/checkstyle-result.xml`).

Um falso positivo é suprimido no próprio código, com o motivo ao lado (`@SuppressWarnings("checkstyle:…")`).

### 10.2 Integração contínua (GitHub Actions)

O arquivo `.github/workflows/ci.yml` roda, a cada push e pull request, `mvn -B -Pqualidade verify` (o build e todos
os testes, com a cobertura e a análise estática, numa rodada só) e `mvn -B -Pui verify` (seção 10.3) num Linux com
JDK 21 e um PostgreSQL 16 descartável, e publica como artefatos a cobertura (`cobertura-jacoco` e
`cobertura-jacoco-ui`) e as capturas das telas (`capturas-telas`). Ele usa o fuso `America/Sao_Paulo` e o idioma pt-BR, como no computador da loja, e uma tela
virtual (`xvfb-run -a`) para os testes que abrem o JavaFX. O banco do CI aceita conexão sem senha (`trust`), então
nenhuma senha real vai para o repositório.

Para ativar, quando o repositório estiver no GitHub:

1. Envie o projeto ao GitHub com a pasta `.github` (ela já está no repositório).
2. Em **Settings › Actions › General**, deixe as Actions permitidas ("Allow all actions" ou ao menos as do GitHub:
   `actions/checkout`, `actions/setup-java` e `actions/upload-artifact`).
3. Faça um push: a execução aparece na aba **Actions**. O relatório de cobertura fica no fim da página da execução,
   em *Artifacts*.
4. Opcional: em **Settings › Branches**, exija o CI verde antes de juntar um pull request.

Nada precisa ser cadastrado em *Secrets*: a senha do banco do CI é um valor qualquer, que o `trust` ignora.

### 10.3 Testes de interface

```bash
mvn -Pui verify
```

Roda só os testes de interface (`*TelaIT`, em `src/test/java/br/com/varesync/tela`), que abrem o sistema inteiro
sobre o banco `varesync_test`:

- **primeiro acesso e login**, começando pela tela de conexão com "Testar conexão";
- **venda completa no PDV**, com cancelamento de item, desconto, pagamento no PIX e em dinheiro com troco, e depois o
  cancelamento da venda;
- **inventário** até a aplicação;
- **relatório** R01 gerado e exportado em CSV e PDF;
- **varredura de cortes:** todas as telas do menu, com as abas, o PDV e o pagamento, em 1280×720, no claro a 100% e no
  escuro a 130%. Nenhum texto pode sair com "…" fora das células, nenhum controle pode passar da borda da janela, e
  nenhuma tabela pode esconder colunas sem rolagem.

Os testes **não usam o teclado nem o mouse do sistema**: acionam os componentes do JavaFX direto, com as janelas fora
da tela e transparentes, e conferem por capturas, gravadas em `target/telas/<teste>/`. Dá para continuar usando o
computador enquanto rodam (cerca de 5 minutos). A pasta de usuário é `target/ui-home`: a configuração temporária, com a
senha do banco de teste, e os logs ficam lá, e o `~/.varesync` de verdade não é tocado. Antes de abrir o sistema, o
teste confere que a configuração aponta para o `varesync_test`; se não apontar, para sem abrir nada. A cobertura das
telas fica em `target/site/jacoco-ui` (sem meta). Rode o `-Pui` sozinho, não junto com outro `mvn` que use o banco de
teste.

## 11. Estrutura

```
src/main/java/br/com/varesync/
├── Launcher.java   main() (não estende Application: roda sem module-info)
├── App.java        inicialização e troca de telas
├── config/         AppConfig, Banco (HikariCP), Migracao (Flyway), AppContext, Servicos
├── model/ dao/ service/ report/ controller/ ui/ util/
src/main/resources/
├── db/migration/   V1 (esquema), V2 (dados de referência), V3 (horários da aplicação) — não editar; mudanças em V4…
├── fxml/ css/ reports/ logback.xml
├── versao.properties  versão e data do build (preenchidos pelo Maven)
src/test/java/br/com/varesync/
├── tela/           testes de interface (perfil ui, seção 10.3)
config/checkstyle.xml  regras do Checkstyle (perfil qualidade)
.github/workflows/     integração contínua (seção 10.2)
```

Arquitetura: `controller` (JavaFX) → `service` (regras e transação) → `dao` (SQL). Detalhes na seção 4 da
especificação.
