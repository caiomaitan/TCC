# VareSync

ERP desktop simplificado para mercados, mercearias e lojas de conveniência: produtos, estoque, PDV com caixa,
inventário e indicadores de rentabilidade (lucro bruto e margem). TCC de Engenharia de Software — Universidade de
Ribeirão Preto.

A especificação completa está em [`docs/ESPECIFICACAO.md`](docs/ESPECIFICACAO.md); o andamento, em
[`docs/PROGRESSO.md`](docs/PROGRESSO.md).

## Pré-requisitos

| Programa | Versão | Observação |
|---|---|---|
| JDK | 21 (ex.: Eclipse Temurin 21) | `JAVA_HOME` apontando para ele |
| Maven | 3.9 ou mais novo | `mvn` no PATH |
| PostgreSQL | 14 ou mais novo (testado no 16) | porta 5432 |

Confira o ambiente com `mvn -v`: a linha `Java version` deve mostrar 21. O `java -version` do PATH pode mostrar
outra versão (no Windows é comum um Java 8 da Oracle vir antes no PATH); o projeto usa o JDK do `JAVA_HOME`.

## Criação do banco

Conectado ao PostgreSQL como `postgres` (psql ou DBeaver com auto-commit ligado), troque a senha e execute:

```sql
CREATE ROLE varesync LOGIN PASSWORD 'troque-esta-senha';
CREATE DATABASE varesync      OWNER varesync ENCODING 'UTF8' TEMPLATE template0;
CREATE DATABASE varesync_test OWNER varesync ENCODING 'UTF8' TEMPLATE template0;
```

- `varesync` é o banco do sistema; as tabelas são criadas pelo próprio sistema (Flyway) na primeira execução.
- `varesync_test` é usado pelos testes automáticos e **é apagado e recriado a cada execução dos testes**.

## Variáveis de ambiente (testes de integração)

| Variável | Obrigatória | Padrão |
|---|---|---|
| `VARESYNC_DB_SENHA` | sim | — |
| `VARESYNC_DB_USUARIO` | não | `varesync` |
| `VARESYNC_DB_TESTE_URL` | não | `jdbc:postgresql://localhost:5432/varesync_test` |

No Windows: Menu Iniciar → "variáveis de ambiente" → *Editar as variáveis de ambiente para sua conta* → Novo.
Depois abra um terminal (ou o editor) **novo** para ele enxergar a variável. A senha nunca vai para arquivo do
repositório.

## Como rodar

```bash
mvn javafx:run
```

Na primeira execução o sistema cria `~/.varesync/config.properties`:

```properties
db.url=jdbc:postgresql://localhost:5432/varesync
db.usuario=varesync
db.senha=
terminal.id=CAIXA-01
```

Se não conseguir conectar, abre a tela **Conexão com o banco** (endereço, usuário, senha, Testar, Salvar). Ao
salvar, o sistema conecta e aplica as migrations (`src/main/resources/db/migration`). `terminal.id` identifica o
computador no controle de caixa e deve ser único por máquina.

Primeiro acesso: com a tabela `usuario` vazia, o sistema abre um assistente que pede o nome da loja e cria o
supervisor (nome, login e senha). Não existe usuário nem senha padrão. Depois disso abre a tela de login. Os demais
usuários são cadastrados pelo supervisor em **Usuários**, com senha provisória que é trocada no primeiro login.
Cinco senhas erradas seguidas bloqueiam o usuário por 5 minutos; o supervisor pode desbloquear antes.

Logs: `~/.varesync/logs/varesync.log` (um arquivo por dia, mantidos 30 dias).

## Banco de demonstração (`varesync_demo`)

O gerador de dados de demonstração (**Configurações › Dados de demonstração**, só para o supervisor) preenche um banco
com 60 dias de operação de um mercado fictício, terminando ontem: cerca de 80 produtos, 3 usuários, um caixa por dia
com vendas, sangrias e fechamento, descontos, cancelamentos, perdas, reajustes de preço e um inventário. Ele **recusa**
um banco que já tenha vendas — como o `varesync` usado nos seus testes. Use um banco separado:

1. Conectado ao PostgreSQL como `postgres`, crie o banco (o usuário `varesync` já existe):

   ```sql
   CREATE DATABASE varesync_demo OWNER varesync ENCODING 'UTF8' TEMPLATE template0;
   ```

2. Com o sistema fechado, edite `~/.varesync/config.properties` (no Windows,
   `C:\Users\<você>\.varesync\config.properties`) e troque o nome do banco no fim da URL:

   ```properties
   db.url=jdbc:postgresql://localhost:5432/varesync_demo
   ```

   (Se a senha ainda não estiver no arquivo, a tela **Conexão com o banco** abre e pede; ela só fica nesse arquivo,
   fora do repositório.)

3. Abra o sistema (`mvn javafx:run`). As tabelas são criadas, e o assistente de primeiro acesso cria a loja e o
   supervisor.
4. Entre como supervisor, abra **Configurações › Dados de demonstração** e clique em **Gerar dados de
   demonstração…**. No fim aparecem os usuários criados — `maria` e `jose` (operadores de caixa) e `carlos` (gerente) —
   com as senhas provisórias: **anote**, elas não são mostradas de novo. Cada um troca a senha no primeiro login.
5. Para voltar ao banco normal, feche o sistema e devolva `db.url` para `.../varesync`.

Para recomeçar a demonstração do zero (ou se a geração for interrompida no meio, o que deixa dados pela metade), com o
sistema fechado:

```sql
DROP DATABASE varesync_demo;
CREATE DATABASE varesync_demo OWNER varesync ENCODING 'UTF8' TEMPLATE template0;
```

A semente é fixa: gerado no mesmo dia, o resultado é sempre o mesmo. As datas acompanham o dia em que você gera.

## Testes

```bash
mvn -q verify
```

- `mvn test`: só os unitários (`*Test`, sem banco).
- `mvn verify`: unitários e integração (`*IT`, no banco `varesync_test`). Sem `VARESYNC_DB_SENHA`, os testes de
  integração falham com uma mensagem dizendo qual variável definir.
- `IntegridadeBancoIT` executa `scripts/teste_integridade_banco.sql` (15 cenários, tudo desfeito no fim).

O script também roda à mão: `psql -d varesync -v ON_ERROR_STOP=1 -f scripts/teste_integridade_banco.sql`, ou no
DBeaver com *Executar script* (Alt+X) — a aba Output mostra "OK 1" a "OK 15".

## Estrutura

```
src/main/java/br/com/varesync/
├── Launcher.java   main() (não estende Application: roda sem module-info)
├── App.java        inicialização e troca de telas
├── config/         AppConfig, Banco (HikariCP), Migracao (Flyway), AppContext
├── model/ dao/ service/ report/ controller/ ui/ util/
src/main/resources/
├── db/migration/   V1 (esquema) e V2 (dados de referência) — não editar; mudanças em V3, V4…
├── fxml/ css/ logback.xml
```

Arquitetura: `controller` (JavaFX) → `service` (regras e transação) → `dao` (SQL). Detalhes na seção 4 da
especificação.
