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

Logs: `~/.varesync/logs/varesync.log` (um arquivo por dia, mantidos 30 dias).

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
