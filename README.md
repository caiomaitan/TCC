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
   rotinas de cada um e uma senha provisória, que a pessoa troca no primeiro login.
4. **Configurações** (só o supervisor): dados da loja (aparecem no comprovante), parâmetros de venda (estoque
   negativo, desconto máximo do operador, cancelamento de item com supervisor, prazo de devolução), margem mínima,
   balança, segurança (bloqueio por inatividade e política de senha), backup e formas de pagamento.
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
   - o relatório **R20 Devoluções** lista as devoluções do período, com os itens, o motivo e a forma de reembolso.

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
- Com vários computadores, todos apontam `db.url` para o servidor do banco (ex.:
  `jdbc:postgresql://192.168.0.10:5432/varesync`). No servidor, o PostgreSQL precisa aceitar conexões da rede
  (`listen_addresses` no `postgresql.conf` e uma linha para a rede da loja no `pg_hba.conf`).

Logs: `~/.varesync/logs/varesync.log`, um arquivo por dia, mantidos 30 dias. Erros técnicos vão para o log; a tela
mostra só a mensagem para o usuário.

## 6. Banco de demonstração e usuários de demonstração

O gerador de dados de demonstração (**Configurações › Dados de demonstração**, só para o supervisor) preenche um banco
com 60 dias de operação de um mercado fictício, terminando ontem: cerca de 80 produtos (12 pesáveis, com PLU), 3
usuários, 3 fornecedores com uma entrada de mercadoria por semana, um caixa por dia com vendas, sangrias e fechamento,
descontos, cancelamentos, perdas, reajustes de preço e um inventário aplicado, 150 clientes (cerca de 40% das vendas
identificadas, com cashback de 1% e alguns pagamentos com o crédito). Ele **recusa** um banco que já tenha
vendas. Use um banco separado:

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
dígitos, valor em preço) e a fidelidade (cashback de 1%, sem compra mínima).

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

A versão mostrada pelo Windows nas propriedades do `.exe` vem da propriedade `app.versao` do perfil, no `pom.xml`.

Se o `mvn clean` disser "Failed to delete …\VareSync.exe", feche o VareSync aberto a partir de `target\dist` e rode
de novo; logo depois de abrir o `.exe`, o antivírus do Windows pode segurar o arquivo por alguns segundos.

## 10. Testes

```bash
mvn -q clean verify
```

- `mvn test`: só os unitários (`*Test`, sem banco).
- `mvn verify`: unitários e integração (`*IT`, no banco `varesync_test`).
- `IntegridadeBancoIT` executa `scripts/teste_integridade_banco.sql` (15 cenários, tudo desfeito no fim).

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
```

Arquitetura: `controller` (JavaFX) → `service` (regras e transação) → `dao` (SQL). Detalhes na seção 4 da
especificação.
