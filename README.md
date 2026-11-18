# VareSync

Sistema de gestão para mercados, mercearias e lojas de conveniência, para Windows: produtos, estoque, PDV com
controle de caixa, inventário, compras, financeiro e relatórios de lucro e margem.

TCC de Engenharia de Software — Universidade de Ribeirão Preto.

## 1. O que é preciso

- Um computador com Windows 10 ou 11, de 64 bits.
- O PostgreSQL 16, gratuito, que guarda os dados do sistema (passo 2).
- O arquivo `VareSync-2.0.0.zip`, na página **Releases** deste repositório. O Java já vem dentro dele, então não é
  preciso instalar mais nada.

## 2. PostgreSQL e banco de dados

Uma vez só, no computador que vai guardar os dados.

1. Baixe o instalador em https://www.postgresql.org/download/windows e instale. Anote a senha do usuário `postgres` e
   mantenha a porta `5432`. No fim, pode desmarcar o Stack Builder.
2. No Menu Iniciar, abra o **SQL Shell (psql)**. Tecle Enter nas perguntas de servidor, banco, porta e usuário e, quando
   pedir, digite a senha do `postgres`.
3. Troque `troque-esta-senha` por uma senha sua, cole os dois comandos abaixo e tecle Enter:

   ```sql
   CREATE ROLE varesync LOGIN PASSWORD 'troque-esta-senha';
   CREATE DATABASE varesync OWNER varesync ENCODING 'UTF8' TEMPLATE template0;
   ```

4. Anote essa senha: o VareSync pede ela no primeiro acesso. As tabelas são criadas pelo próprio sistema.

## 3. Instalação do VareSync

1. Extraia o `VareSync-2.0.0.zip` (botão direito › **Extrair tudo…**) e coloque a pasta `VareSync` onde quiser, por
   exemplo em `C:\VareSync`. Dentro dela ficam o `VareSync.exe` e as pastas `app` e `runtime`.
2. Crie um atalho: clique com o botão direito em `VareSync.exe` › **Enviar para** › **Área de trabalho (criar
   atalho)**.
3. Abra o `VareSync.exe`. Se o Windows mostrar "O Windows protegeu o computador", clique em **Mais informações** ›
   **Executar assim mesmo**.

Para desinstalar, apague a pasta. Os dados ficam no banco; a configuração e os registros de erro, em
`C:\Users\<você>\.varesync`.

## 4. Primeiro acesso e uso no dia a dia

### 4.1 Primeiro acesso

1. **Conexão com o banco:** confira o endereço (`jdbc:postgresql://localhost:5432/varesync`) e o usuário
   (`varesync`), digite a senha do passo 2 e clique em **Testar conexão** e depois em **Salvar**. Essa tela volta
   sempre que a conexão falhar, por exemplo com o PostgreSQL parado.
2. **Loja e supervisor:** informe o nome da loja, crie o supervisor (nome, login e senha de 6 a 50 caracteres) e
   clique em **Criar supervisor**. Não existe usuário nem senha padrão.
3. **Login:** entre com o supervisor. No **Painel**, o assistente **Primeiros passos** mostra o que ainda falta
   configurar e abre a tela de cada passo.
4. **Configurações:** confira os dados da loja, que saem no comprovante, as formas de pagamento e as regras de venda,
   como o desconto máximo do operador.
5. **Usuários:** cadastre os demais usuários, com as rotinas que cada um pode usar e uma senha provisória, que a
   pessoa troca no primeiro login.

### 4.2 No dia a dia

1. **Produtos:** cadastre em **Produtos › Novo produto (Ctrl+N)** ou traga de uma planilha em **Produtos › Importar
   planilha…**, que tem um modelo para baixar.
2. **Abrir o caixa:** em **Caixa › Abrir caixa…**, informe o fundo de troco.
3. **Vender:** no **PDV**, leia o código de barras ou digite o código e tecle Enter. **F2** busca o produto pelo nome,
   **F11** identifica o cliente e **F10** abre o pagamento, que aceita várias formas na mesma venda.
4. **Fechar o caixa:** em **Caixa › Fechar caixa… (F12)**, informe o dinheiro contado. Só depois o sistema mostra a
   sobra ou a falta e o relatório de fechamento.
5. **Acompanhar:** o **Painel** mostra o faturamento, o lucro e os alertas do dia, e em **Relatórios** ficam os
   relatórios, todos com exportação em PDF e CSV.

Em qualquer tela, **F1** abre a ajuda da tela, com os atalhos.

A NFC-e vem desligada e só funciona no ambiente de homologação da SEFAZ, que é de teste e não tem valor fiscal. Para
ligá-la, é preciso o certificado digital A1 da loja, em **Configurações › NFC-e**.

## 5. Vários computadores na loja

Com mais de um computador, o banco fica num só, o **servidor**, e os outros, os **terminais**, se conectam a ele pela
rede da loja. O servidor precisa estar ligado sempre que alguém for usar o sistema.

**No servidor**

1. Dê ao computador um **IP fixo** na rede da loja. Nos exemplos: servidor `192.168.0.10` e rede `192.168.0.0/24`.
2. Instale o PostgreSQL e crie o banco como no passo 2, com uma senha forte.
3. No arquivo `postgresql.conf`, em `C:\Program Files\PostgreSQL\16\data`, troque a linha `listen_addresses` por:

   ```
   listen_addresses = 'localhost,192.168.0.10'
   ```

4. No fim do `pg_hba.conf`, na mesma pasta, acrescente esta linha, que libera só a rede da loja:

   ```
   host    varesync  varesync  192.168.0.0/24    scram-sha-256
   ```

5. Num PowerShell aberto como administrador, reinicie o PostgreSQL e libere a porta 5432 só para a rede da loja:

   ```powershell
   Restart-Service postgresql-x64-16
   New-NetFirewallRule -DisplayName "PostgreSQL (VareSync)" -Direction Inbound -Protocol TCP -LocalPort 5432 -RemoteAddress 192.168.0.0/24 -Action Allow
   ```

   Não abra a porta 5432 no roteador para a internet.
6. **Backup:** abra o VareSync no servidor e escolha uma pasta dele em **Configurações › Backup**. O backup usa o
   `pg_dump`, que só existe no servidor, então faça o backup manual por ele (passo 7).

**Em cada terminal**

1. Instale o VareSync (passo 3). O PostgreSQL não precisa ser instalado nos terminais.
2. Na tela **Conexão com o banco**, informe o endereço com o IP do servidor
   (`jdbc:postgresql://192.168.0.10:5432/varesync`), o usuário `varesync` e a senha, e clique em **Testar conexão** e
   em **Salvar**.
3. Com o sistema fechado, abra `C:\Users\<você>\.varesync\config.properties` no Bloco de Notas e troque o
   `terminal.id` por um nome único para o computador (`CAIXA-02`, `ESCRITORIO`…). Dois computadores com o mesmo nome
   dividiriam o mesmo caixa.

O primeiro acesso (passo 4.1) é feito uma vez só, no primeiro computador que abrir o sistema.

## 6. Banco de demonstração

Para testar com dados prontos, o gerador de **Configurações › Dados de demonstração** (só o supervisor) preenche um
banco **vazio** com 60 dias de operação de um mercado fictício. Use um banco separado, o `varesync_amostra`:

1. No **SQL Shell (psql)**, entrando como `postgres`, crie o banco:

   ```sql
   CREATE DATABASE varesync_amostra OWNER varesync ENCODING 'UTF8' TEMPLATE template0;
   ```

2. Com o sistema fechado, abra `C:\Users\<você>\.varesync\config.properties` no Bloco de Notas e troque o nome do banco
   no fim da linha `db.url`:

   ```properties
   db.url=jdbc:postgresql://localhost:5432/varesync_amostra
   ```

3. Abra o sistema, faça o primeiro acesso (loja e supervisor) e, em **Configurações › Dados de demonstração**, clique
   em **Gerar dados de demonstração…**. No fim, aparecem os usuários criados, que trocam a senha no primeiro login:

   | Login | Perfil | Senha provisória |
   |---|---|---|
   | `maria` | Operadora de caixa | `demo1835` |
   | `jose` | Operador de caixa | `demo9076` |
   | `carlos` | Gerente | `demo6999` |

4. Para voltar ao banco da loja, feche o sistema e devolva a linha `db.url` para `.../varesync`.

Para recomeçar do zero, ou se a geração for interrompida no meio, feche o sistema e rode no psql:

```sql
DROP DATABASE varesync_amostra;
CREATE DATABASE varesync_amostra OWNER varesync ENCODING 'UTF8' TEMPLATE template0;
```

## 7. Backup e restauração

- **Automático:** ao sair do sistema, uma vez por dia.
- **Manual:** **Configurações › Backup › Fazer backup agora** (só o supervisor).
- **Onde fica:** na pasta escolhida em **Configurações › Backup** ou, sem pasta escolhida, em
  `C:\Users\<você>\.varesync\backups`. Por padrão, ficam os 15 últimos. Guarde cópias também fora do computador, num
  pen drive ou na nuvem.

**Restauração:** feche o VareSync em todos os computadores e, num PowerShell, rode o comando abaixo, trocando o
caminho do arquivo (e o `16`, se a versão do PostgreSQL for outra). A senha pedida é a do usuário `varesync`.

```powershell
& "C:\Program Files\PostgreSQL\16\bin\pg_restore.exe" --clean --if-exists -h localhost -U varesync -d varesync C:\caminho\varesync_20261021_2200.backup
```

## 8. Balança

Com a balança ativa em **Configurações › Balança**, o PDV lê a etiqueta impressa pela balança (EAN-13 com o prefixo,
o PLU do produto e o preço ou o peso). Configure igual à balança: prefixo, quantidade de dígitos do PLU e tipo de
valor.

**Atenção no modo `PRECO`** (a etiqueta traz o total em R$): o sistema cobra o valor impresso e calcula a quantidade
que sai do estoque dividindo esse valor pelo preço do quilo do sistema, inclusive o promocional. Por isso, a balança
tem de estar com o mesmo preço por quilo do cadastro, e cada reajuste ou promoção de produto pesável tem de ser feito
também na balança. No modo `PESO` (a etiqueta traz os gramas), esse cuidado não é necessário.

**Carga de balança** (**Produtos › Carga de balança…**): grava o arquivo de itens da balança Toledo (`ITENSMGV.TXT`,
formato do MGV6) na pasta de **Configurações › Balança**, para o MGV importar e mandar à balança. Vão todos os
produtos ativos, vendidos em KG, com PLU e preço maior que zero, com o preço por quilo do dia (inclusive promoção), a
descrição e os dias de validade. A tela marca em amarelo o que está pendente. Gere a carga depois de cada reajuste ou
promoção de pesável e no começo e no fim de cada promoção.

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
