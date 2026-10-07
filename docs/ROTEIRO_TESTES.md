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
| CT-F0-06 | Sistema executado ao menos uma vez | 1. Abrir `~/.varesync/logs/varesync.log` | O log registra início, configuração (sem a senha), migrations aplicadas e encerramento. Nenhuma senha aparece no arquivo | |
| CT-F0-07 | Configuração salva; serviço do PostgreSQL parado (services.msc → postgresql-x64-16 → Parar) | 1. Rodar `mvn javafx:run` 2. Iniciar o serviço de novo 3. Clicar **Salvar** | 1. A tela de conexão abre com "Não foi possível conectar ao servidor do banco. Verifique se o PostgreSQL está em execução…" 3. A janela principal abre | |
| CT-F0-08 | Tela de conexão aberta | 1. Pressionar **Esc** | O sistema fecha | |
