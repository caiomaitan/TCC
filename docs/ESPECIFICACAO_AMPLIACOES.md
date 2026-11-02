# VareSync — Especificação das ampliações (Fases 10 a 21)

Complementa `docs/ESPECIFICACAO.md`, que continua valendo inteira: arquitetura em camadas, uma transação por operação, `BigDecimal` com HALF_UP, `Clock`, auditoria na mesma transação, permissão conferida no service, constraints nomeadas traduzidas pelo `TradutorErros`, interface em pt-BR e testes. Valem também as decisões registradas em `docs/PROGRESSO.md`.

Este documento descreve só o que é novo. Para as funcionalidades novas, vale o que está aqui.

Sumário: 0 Regras gerais · F10 Robustez e segurança · F11 Engenharia de software · F12 Clientes, crédito e cashback · F13 Devoluções e trocas · F14 Crediário e contas a receber · F15 Financeiro · F16 Compras: fornecedor do produto, sugestão e pedido · F17 Validade e lotes · F18 Etiquetas e carga de balança · F19 Cadastro fiscal · F20 Embalagens e preço de atacado · F21 Usabilidade · Fora do escopo

---

## 0. Regras gerais

### 0.1 Ordem, dependências e partes

Cada parte cabe numa sessão do agente e termina com `mvn -q verify` verde e commit.

| Fase | Assunto | Depende de | Parte A | Parte B |
|---|---|---|---|---|
| F10 | Robustez e segurança | — | F10.1 a F10.3 | F10.4 a F10.9 |
| F11 | Engenharia de software | — | F11.1, F11.2, F11.4, F11.6, F11.7 | F11.3, F11.5, F11.8 |
| F12 | Clientes, crédito e cashback | — | F12.1, F12.2, F12.6, R18 | F12.3 a F12.5, R19, gerador |
| F13 | Devoluções e trocas | F12 | F13.1, F13.2, F13.4 | F13.3, gerador |
| F14 | Crediário e contas a receber | F12, F13 | F14.1, F14.2 | F14.3, F14.4 |
| F15 | Financeiro | F13, F14 | F15.1 a F15.3 | F15.4 a F15.8 |
| F16 | Compras: fornecedor do produto, sugestão e pedido | — | F16.1, F16.2 | F16.3, F16.4 |
| F17 | Validade e lotes | — | F17.1, F17.2 | F17.3 |
| F18 | Etiquetas de gôndola e carga de balança | — | F18.1 | F18.2, F18.3 |
| F19 | Cadastro fiscal e consistência | — | F19.1 a F19.3 | F19.4, F19.5 |
| F20 | Embalagens e preço de atacado | — | fase inteira | — |
| F21 | Usabilidade e acessibilidade | as fases que forem feitas | fase inteira | — |

Ordem sugerida:
1. F10 e F11 primeiro: reforçam o que já existe e fecham riscos registrados no PROGRESSO.
2. F12 a F15: núcleo comercial e financeiro.
3. F16 a F20, em qualquer ordem.
4. F21 por último.

### 0.2 Banco

- **Migrations:** cada parte que muda o banco cria uma migration nova, com a próxima versão livre (V4, V5, …), justificada no PROGRESSO. Migration já aplicada nunca é editada.
- **CHECK nomeado** se altera na mesma migration: `ALTER TABLE t DROP CONSTRAINT c, ADD CONSTRAINT c CHECK (...)`. Os enums Java continuam iguais aos CHECKs, com teste que compara, como `VendaServiceIT.enumsDaVendaIguaisAosChecksDoBanco`.
- **Linhas antigas:**
  - coluna nova `NOT NULL` em tabela com dados leva `DEFAULT` ou é preenchida na própria migration;
  - regra que não pode valer para linhas antigas entra como `CHECK ... NOT VALID`.
- **Views:** `CREATE OR REPLACE VIEW` mantém as colunas existentes na mesma ordem, com o mesmo nome e tipo; colunas novas vão no fim.
- **Dados de referência novos** (permissões, configurações, formas de pagamento, categorias de despesa, contas) entram por migration.
- **Saldos novos seguem o padrão do estoque:**
  - razão imutável: o trigger recusa UPDATE e DELETE;
  - tabela de saldo protegida, alterada só pelo trigger do razão (mesmo mecanismo de `fn_movimento_aplica_saldo` e `fn_produto_estoque_protegido`, com `set_config` local);
  - view `vw_*_inconsistente` que precisa ficar vazia: é conferida ao fim de cada teste de integração, como a do estoque, e aparece no Diagnóstico de Configurações.

  Vale para o crédito do cliente (F12), as contas financeiras (F15) e os lotes (F17). A limpeza dos testes usa `TRUNCATE`, que não aciona triggers de linha: ela precisa limpar o razão e o saldo juntos.
- **Horários:** sempre enviados pela aplicação a partir do `Clock` (V3). Tabela nova com `atualizado_em` usa o trigger `fn_set_atualizado_em`.
- **Travas:** na mesma ordem dos módulos existentes (saldo do produto na ordem do produto). Antes de conferir limite ou saldo, o razão novo trava a linha do saldo (`FOR UPDATE`).

### 0.3 Permissões novas

| Código | Descrição | Fase |
|---|---|---|
| CLIENTES | Cadastro de clientes | F12 |
| DEVOLUCAO | Registrar devoluções e trocas | F13 |
| RECEBIMENTO | Receber títulos do crediário | F14 |
| LIMITE_CREDITO | Gerenciar crédito: vender acima do limite ou a cliente em atraso e dispensar encargos | F14 |
| FINANCEIRO | Contas financeiras, contas a pagar, fluxo de caixa e DRE | F15 |
| FISCAL | Cadastro tributário e consistência fiscal | F19 |

As permissões existentes cobrem o resto: pedidos e sugestão de compra (COMPRAS), lotes (ESTOQUE), etiquetas, carga de balança e embalagens (PRODUTOS).

### 0.4 Configurações novas (chave = padrão)

| Fase | Chaves |
|---|---|
| F10 | `seguranca.inatividade_minutos` = 15 (0 desliga) · `seguranca.senha_tamanho_minimo` = 6 · `seguranca.senha_letra_e_numero` = false |
| F12 | `fidelidade.ativo` = false · `fidelidade.cashback_pct` = 1 · `fidelidade.compra_minima` = 0 |
| F13 | `devolucao.prazo_dias` = 30 |
| F14 | `crediario.max_parcelas` = 3 · `crediario.dias_primeira` = 30 · `crediario.juros_mes_pct` = 2 · `crediario.multa_pct` = 2 · `crediario.carencia_dias` = 0 · `crediario.bloqueio_atraso_dias` = 30 |
| F15 | `financeiro.integrar_caixa` = false (ligar depois de lançar o saldo inicial das contas) |
| F16 | `compras.dias_media` = 30 · `compras.cobertura_dias` = 15 |
| F17 | `validade.alerta_dias` = 7 |
| F18 | `etiqueta.colunas` = 3 · `etiqueta.linhas` = 8 · `etiqueta.modelo_termica` = (vazio) · `balanca.pasta_carga` = (vazio) |
| F19 | `empresa.regime_tributario` = SIMPLES (SIMPLES ou NORMAL) · `empresa.uf` = (vazio) · `empresa.ie` = (vazio) |

Todas entram na classe `Configuracoes` e são editadas na tela de Configurações, na aba da área (existente ou nova), com validação e auditoria `CONFIGURACAO_ALTERADA`.

### 0.5 Menu e telas

- **Menu lateral:** entram só três itens novos: **Clientes** (F12), **Devoluções** (F13) e **Financeiro** (F15). O menu já rola em 1280×720.
- **O resto vai para as telas existentes:**

  | Tela | O que entra |
  |---|---|
  | Caixa e PDV | recebimento do crediário (F14) |
  | Compras | abas Pedidos e Sugestão de compra (F16) |
  | Estoque | aba Lotes (F17) |
  | Produtos | Unidades (F10); Etiquetas, Carga de balança e Fiscal, como botões ou abas (F18, F19) |
  | Configurações | uma aba por área com configuração nova (seção 0.4) e o Diagnóstico com as views novas |

- **Atalhos novos no PDV:**
  - F11 identifica o cliente (F12);
  - Ctrl+R recebe o crediário (F14);
  - a janela do F9 ganha "Devolver itens" (F13).

  Os demais atalhos não mudam.

### 0.6 Bibliotecas e plugins já autorizados

- **F11:**
  - plugins de build JaCoCo, SpotBugs, Checkstyle e git-commit-id;
  - só para testes: TestFX com Monocle headless (`org.testfx:openjfx-monocle` 21.0.2, feito para o JavaFX 21).
- **F18:** ZXing (`com.google.zxing:core`), para desenhar códigos de barras e, nos testes, lê-los de volta.
- **Qualquer outra:** registrar a decisão no PROGRESSO antes, como manda a regra do projeto.

### 0.7 Requisitos novos (para a rastreabilidade)

| Código | Requisito | Fase |
|---|---|---|
| RF14 | Cadastro de clientes e identificação na venda | F12 |
| RF15 | Crédito do cliente e cashback | F12 |
| RF16 | Tratamento de dados pessoais (LGPD) | F12 |
| RF17 | Devoluções e trocas | F13 |
| RF18 | Venda no crediário | F14 |
| RF19 | Contas a receber e recebimento | F14 |
| RF20 | Contas financeiras e recebíveis de cartão e PIX | F15 |
| RF21 | Contas a pagar e despesas | F15 |
| RF22 | Fluxo de caixa | F15 |
| RF23 | DRE gerencial | F15 |
| RF24 | Fornecedor do produto e sugestão de compra | F16 |
| RF25 | Pedido de compra | F16 |
| RF26 | Controle de validade e lotes | F17 |
| RF27 | Etiquetas de gôndola | F18 |
| RF28 | Carga de balança | F18 |
| RF29 | Cadastro tributário | F19 |
| RF30 | Relatório de inconsistências fiscais | F19 |
| RF31 | Embalagens de venda e preço por quantidade | F20 |
| RNF09 | Segurança da sessão | F10 |
| RNF10 | Qualidade verificável: cobertura, análise estática, testes de interface, concorrência e integração contínua | F11 |
| RNF11 | Implantação em rede local | F11 |
| RNF12 | Usabilidade e acessibilidade | F21 |

As demais entregas da F10 entram nos requisitos existentes: unidades em RF01, kardex em RF09 e backup em RNF01.

### 0.8 Relatórios novos (continuação do catálogo)

| Fase | Relatórios |
|---|---|
| F12 | R18 Clientes · R19 Saldos de crédito |
| F13 | R20 Devoluções |
| F14 | R21 Títulos a receber · R22 Recebimentos |
| F15 | R23 Contas a pagar · R24 Fluxo de caixa · R25 DRE gerencial |
| F16 | R26 Sugestão de compra · R27 Pedidos pendentes · R28 Cobertura e ruptura |
| F17 | R29 Validade |
| F19 | R30 Inconsistências fiscais |

Todos usam a infraestrutura `Relatorio<T>`, com tela, CSV e PDF. Totais agregados sempre como soma ÷ soma.

### 0.9 Em toda parte

- **Gerador de demonstração:**
  - é ampliado com os dados novos, com semente própria (SEMENTE + n, como na Fase 7);
  - não muda os produtos, códigos, preços, usuários e senhas que o README e o DEMO.md usam;
  - o `GeradorDemoReproducaoIT` continua passando, e o gerador continua recusando banco com vendas;
  - quando a fase tem uma configuração que vem desligada (fidelidade, integração do caixa), o gerador a liga.
- **Painel:** alerta novo aparece só para quem tem a permissão da tela de destino; o clique abre a tela já filtrada.
- **Auditoria:** cada ação nova entra no enum `AcaoAuditoria`, com rótulo legível, e é gravada na mesma transação.
- **Documentos:**
  - atualizar PROGRESSO, RASTREABILIDADE (com os requisitos de 0.7), ROTEIRO_TESTES (casos CT-F10-01 em diante) e, quando a instalação ou o uso mudarem, o README;
  - **não alterar** `docs/TCC_RESULTADOS.md`, `docs/TCC_MUDANCAS.md`, `docs/DEMO.md`, `docs/telas/` nem `docs/diagramas/`: eles descrevem a versão entregue ao TCC (tag `tcc-entrega`).
- **Conferência pela tela:** sem teclado nem mouse do sistema. Usar eventos direto nos componentes, janelas fora da tela e capturas por snapshot.

---

## F10 — Robustez e segurança (RNF09)

Fecha riscos e pendências que o PROGRESSO registrou nas Fases 1 a 9.

### F10.1 Sessão revalidada

Risco aceito na Fase 9: "a sessão aberta continua sem revalidação".

- Nova coluna `usuario.versao_acesso INTEGER NOT NULL DEFAULT 0`.
  - Um trigger a incrementa quando mudam `ativo`, `supervisor`, `senha_hash` ou `deve_trocar_senha`, e quando uma linha de `usuario_permissao` é incluída ou excluída.
  - A `Sessao` (que continua imutável) guarda a versão lida no login.
- Toda conferência de permissão (`exigir`, `exigirOuLiberado`, `exigirSupervisor`) passa a olhar também o banco, na transação da própria operação, com uma consulta só: `ativo`, `supervisor`, `deve_trocar_senha`, `versao_acesso` e as permissões atuais. Conforme o resultado:
  - **usuário inativo, ou com a senha redefinida pelo supervisor** (`deve_trocar_senha = true`): `SessaoEncerradaException` com "Seu acesso foi alterado pelo supervisor. Entre novamente.". A interface faz o log off e abre o login. No PDV, a venda em andamento é abandonada, com a auditoria `VENDA_ABANDONADA`;
  - **versão diferente:** a permissão é conferida pelo que está no banco agora; sem ela, aparece a mensagem de permissão de sempre. A interface troca a `Sessao` por uma nova, com as permissões atuais, e refaz o menu;
  - **versão igual:** tudo segue como hoje.
- A liberação de supervisor confere a situação atual de quem libera.
- Nada é interrompido no meio: a mudança vale a partir da próxima operação.

### F10.2 Bloqueio por inatividade

- Depois de `seguranca.inatividade_minutos` sem teclado nem mouse, a janela principal e o PDV mostram a tela "Sessão bloqueada" sobre o conteúdo.
- O estado das telas fica preservado, inclusive o carrinho do PDV.
- Para desbloquear, o usuário logado digita a senha. Senha errada conta para o bloqueio de 5 tentativas.
- "Trocar usuário" faz o log off; com venda em andamento, pede confirmação para abandoná-la.

### F10.3 Cancelamentos e liberações gravados na venda

Hoje o R16 tira "quem liberou" do texto da auditoria (risco registrado na Fase 6).

- **`venda_item`:**
  - `cancelado_em` e `cancelado_por` (FK usuario: quem cancelou);
  - `cancelamento_liberado_por` (FK usuario, nula quando não houve liberação);
  - `CHECK ... NOT VALID`: item cancelado exige `cancelado_em` e `cancelado_por`.
- **`venda`:**
  - `cancelamento_operador_id` (FK usuario): quem executou o cancelamento. `cancelada_por` continua sendo quem autorizou;
  - `desconto_liberado_por` (FK usuario): quem autorizou o desconto acima do limite (o supervisor da liberação, ou o próprio operador quando tem DESCONTO_ACIMA_LIMITE). Fica nula com o desconto dentro do limite;
  - `CHECK ... NOT VALID`: venda CANCELADA exige `cancelamento_operador_id`.
- O PDV grava esses campos, e o R16 passa a lê-los. Para registros anteriores à migration (campos nulos), o R16 mantém a leitura atual pela auditoria.

### F10.4 Kardex com saldo anterior

Pendência registrada na Fase 3.

- A consulta de estoque e o R04 filtrado por produto começam com a linha "Saldo anterior".
- O valor é o `saldo_posterior` do último movimento antes do início do período, ou 0 se não houver.

### F10.5 Unidades de medida

A seção 6.3 dizia "só leitura nesta versão".

- Cadastro com a permissão PRODUTOS:
  - sigla única, de 1 a 6 letras maiúsculas ou dígitos;
  - descrição;
  - fracionada.
- A sigla e "fracionada" só mudam se nenhum produto usa a unidade. Excluir também só sem uso.

### F10.6 Política de senha

- Senhas novas e trocadas respeitam `seguranca.senha_tamanho_minimo` e, se estiver ligado, `seguranca.senha_letra_e_numero`. Os diálogos mostram a regra.
- O limite de 50 caracteres e 71 bytes continua.
- Senhas antigas continuam valendo até a próxima troca.

### F10.7 PDF grande

Risco da Fase 6: o R04 de 60 dias passa de 150 páginas.

- Antes de gerar o PDF, o sistema estima as páginas pelo número de linhas.
- Acima de 50 páginas, pede confirmação e sugere filtros.

### F10.8 Inventário grande

Pendência das Fases 5 e 6.

- Gravar uma contagem atualiza só a linha, sem recarregar a aba Contagem inteira.
- Medir com 5 mil itens e registrar nas Métricas.

### F10.9 Backup conferido

- Depois do `pg_dump`, o sistema roda `pg_restore --list` no arquivo, com o `pg_restore` da mesma pasta do `pg_dump`.
- Se a conferência falhar, o arquivo é descartado como backup que falhou (mesma regra do `.tmp`):
  - o supervisor vê o aviso;
  - `BACKUP_REALIZADO` não é registrado, então o "último backup" do painel só conta backups bons.

### Aceite F10

- **Permissão retirada:** um operador logado num terminal perde a permissão PRODUTOS em outro terminal. A próxima ação dele em Produtos é recusada e o menu deixa de mostrar Produtos, sem log off.
- **Acesso encerrado:** um usuário inativado, ou com a senha redefinida pelo supervisor, vai para o login na próxima ação.
- **Inatividade:** com inatividade de 1 minuto, a tela bloqueia. A senha certa volta ao ponto exato, inclusive com venda em andamento no PDV; 5 senhas erradas bloqueiam o usuário.
- **R16:** num item cancelado com liberação, mostra o operador e o supervisor pelas colunas novas; os registros antigos continuam aparecendo como antes.
- **Kardex:** com movimentos antes do período, a primeira linha mostra o saldo anterior certo.
- **Unidade em uso:** não muda "fracionada" nem pode ser excluída.
- **Senha:** com tamanho mínimo 8, a senha "abc123" é recusada com a regra na mensagem.
- **PDF grande:** o R04 de 60 dias sem filtro de produto pede confirmação antes do PDF.
- **Inventário:** num inventário com 5 mil itens, gravar uma contagem leva menos de 300 ms.
- **Backup:** um backup que o `pg_restore --list` não lê (simulado no teste) é descartado e avisado.

---

## F11 — Engenharia de software (RNF10, RNF11)

### F11.1 Cobertura

- JaCoCo no perfil `-Pqualidade`, com relatório em `target/site/jacoco`.
- Metas: 80% das linhas em `util` e 70% em `service`.
- A cobertura conta os testes unitários e os de integração juntos, porque os services são testados nos `*IT`.
- Abaixo da meta, escrever os testes que faltam. Não baixar a meta nem tirar classes da conta sem registrar o motivo.
- O valor medido vai para as Métricas do PROGRESSO.

### F11.2 Análise estática

- No mesmo perfil:
  - SpotBugs, sem ocorrência de prioridade alta;
  - Checkstyle, sem erros, com regras do projeto adaptadas ao estilo atual.
- Falso positivo é suprimido com justificativa no próprio código.

### F11.3 Testes de interface versionados

- Ficam no perfil `-Pui`.
- **Ferramenta:** TestFX só em modo headless com Monocle (`testfx.headless=true`, `glass.platform=Monocle`, `monocle.platform=Headless`). **É proibido o robô do sistema operacional.**
  - Se o Monocle não funcionar com o JavaFX 21.0.12, não usar TestFX. Nesse caso, versionar os testes no padrão dos roteiros (eventos direto nos componentes, janelas fora da tela, snapshot) e registrar a decisão.
- **Fluxos cobertos:**
  - primeiro acesso e login;
  - venda completa no PDV, com desconto, pagamento misto e cancelamento;
  - inventário até a aplicação;
  - geração e exportação de um relatório.

### F11.4 Integração contínua

- Arquivo `.github/workflows/ci.yml`:
  - JDK 21;
  - PostgreSQL 16 como serviço, com `POSTGRES_USER=varesync`, `POSTGRES_DB=varesync_test` e `POSTGRES_HOST_AUTH_METHOD=trust` (banco descartável; nenhuma senha real no repositório). `VARESYNC_DB_SENHA` recebe um valor qualquer, que o `trust` ignora;
  - roda `mvn -B verify` e `-Pqualidade`;
  - como o Linux do CI não tem tela, os testes que abrem o JavaFX rodam com tela virtual (`xvfb-run -a`);
  - usa o fuso `America/Sao_Paulo` e o idioma pt-BR, como no computador da loja;
  - publica o relatório de cobertura como artefato.
- O README explica como ativar quando o repositório estiver no GitHub.

### F11.5 Instalação em rede

- **README do servidor:**
  - PostgreSQL num PC;
  - `listen_addresses`;
  - `pg_hba.conf` liberando só a rede da loja;
  - firewall na porta 5432;
  - backup configurado no servidor (no terminal sem `pg_dump`, o backup automático só registra no log).
- **README dos terminais:**
  - `db.url` com o IP do servidor;
  - `terminal.id` único por PC.
- **Tela de conexão:** ganha "Testar conexão", que mostra a versão do servidor e o tempo de resposta.

### F11.6 Versão do sistema

- O pom passa para `1.1.0-SNAPSHOT`. A versão do TCC é a marcada pela tag `tcc-entrega`.
- Tela **Sobre** (menu ou rodapé): versão, commit (plugin git-commit-id; "desconhecido" sem Git) e data do build. A versão também aparece na barra de status.
- O `jpackage` usa a versão do pom, sem `-SNAPSHOT`.

### F11.7 Instalador MSI (opcional)

- Perfil que gera o `.msi` com o `jpackage` quando o WiX estiver instalado. Sem o WiX, só documentar.

### F11.8 Testes de concorrência

Testes de integração com duas conexões simultâneas:

| Situação | Resultado esperado |
|---|---|
| Duas vendas com os mesmos produtos em ordens diferentes | sem deadlock e saldos certos |
| Duas sangrias juntas | não passam do dinheiro esperado |
| Dois terminais incluindo o mesmo produto em inventários diferentes | só um consegue |
| Duas confirmações da mesma entrada | só uma vale |

### Aceite F11

- `mvn -Pqualidade verify` passa e gera os relatórios dentro das metas.
- `mvn -Pui verify` roda os 4 fluxos sem abrir janela visível.
- O workflow de CI está pronto: verde, se o repositório já estiver no GitHub.
- Os 4 testes de concorrência passam repetidos 20 vezes seguidas.
- O README tem a instalação em rede.
- A tela Sobre mostra a versão e o commit.

---

## F12 — Clientes, crédito do cliente e cashback (RF14, RF15, RF16)

### F12.1 Cadastro de clientes (permissão CLIENTES)

- Tabela `cliente`, com estes campos:
  - nome (obrigatório);
  - `cpf_cnpj`: opcional, único, sem máscara, com dígito verificador conferido como em fornecedor (`CpfCnpj`);
  - telefone (10 ou 11 dígitos com DDD), email e data_nascimento;
  - endereço: cep, logradouro, numero, complemento, bairro, cidade e uf;
  - observacao;
  - `consentimento_marketing`, com a data do consentimento;
  - `anonimizado` e `ativo`;
  - `criado_em/por` e `atualizado_em/por`;
  - `nome_busca`, gerada como a de produto (mesmo `translate`).
- Busca por nome sem acento, CPF/CNPJ (inteiro ou parte) ou telefone.
- CPF/CNPJ repetido mostra o cliente que já o tem.
- Excluir só sem vínculo; senão, inativar.

### F12.2 Identificação na venda

- Nova coluna `venda.cliente_id` (FK, nula).
- No PDV, F11 identifica o cliente por CPF/CNPJ ou pela busca:
  - mostra o nome e o saldo de crédito;
  - o cliente pode ser trocado ou removido até a finalização;
  - cadastro rápido (nome e CPF) sem sair do PDV, com a permissão PDV.
- O comprovante mostra o nome e o CPF mascarado (`***.456.789-**`).
- Tela do cliente:
  - histórico de compras;
  - total gasto, ticket médio e última compra;
  - produtos mais comprados.

### F12.3 Crédito do cliente (razão no padrão da seção 0.2)

- Razão `cliente_credito_movimento`, com os campos `cliente_id`, `tipo`, `valor` (≠ 0, com o sinal do tipo), `venda_id`, `motivo`, `usuario_id` e `criado_em`. Tipos:

  | Tipo | Sinal | Observação |
  |---|---|---|
  | CASHBACK | + | |
  | ESTORNO_CASHBACK | − | |
  | CREDITO_DEVOLUCAO | + | a partir da F13 |
  | USO_EM_VENDA | − | |
  | ESTORNO_USO | + | |
  | AJUSTE | ± | motivo obrigatório; só supervisor |

- Saldo protegido em `cliente_credito`, mais a view `vw_cliente_credito_inconsistente`.
- **Saldo negativo:**
  - USO_EM_VENDA não pode deixar o saldo negativo (conferido com a linha do saldo travada);
  - os estornos podem: o saldo negativo aparece em vermelho e é abatido pelos próximos créditos.

### F12.4 Pagamento com crédito

- Novo tipo de forma de pagamento CREDITO_CLIENTE (altera `ck_forma_pagamento_tipo`) e nova forma "Crédito do cliente", ativa. Só pode existir uma forma desse tipo.
- **Regras do pagamento:**
  - exige cliente identificado;
  - o valor não pode passar do saldo nem do que falta pagar;
  - não gera troco;
  - não entra no dinheiro esperado do caixa.
- A finalização grava USO_EM_VENDA na mesma transação. O cancelamento da venda grava ESTORNO_USO.

### F12.5 Cashback

- Vale com `fidelidade.ativo` ligado e cliente identificado.
- Na finalização:
  - base = valor_total − valor pago com crédito do cliente;
  - CASHBACK = round(base × `cashback_pct` ÷ 100, 2), gerado só se a base for ≥ `compra_minima`.
- O cancelamento da venda gera ESTORNO_CASHBACK do mesmo valor.
- O comprovante mostra o cashback gerado e o saldo atual.

### F12.6 LGPD

- **Consentimento:** o consentimento de marketing é registrado com a data.
- **Exportar dados do cliente** (CLIENTES): PDF e CSV com o cadastro, as compras e o extrato de crédito.
- **Anonimizar** (só supervisor, com confirmação digitando o nome do cliente):
  - troca o nome por "Cliente anonimizado nº X";
  - apaga CPF/CNPJ, contatos, endereço e data de nascimento;
  - mantém as vendas e o extrato;
  - registra na auditoria.

  Fica bloqueado se o saldo de crédito for diferente de zero ou se houver títulos em aberto (F14).

### F12.7 Relatórios

- **R18 Clientes:** por período, com nº de compras, valor, ticket médio e última compra.
- **R19 Saldos de crédito:** clientes com saldo diferente de zero e o total (valor devido aos clientes).

### F12.8 Gerador

- 150 clientes.
- Cerca de 40% das vendas identificadas.
- Fidelidade ligada com 1%.
- Alguns usos de crédito.

### Aceite F12

- **Cadastro:** CPF inválido é recusado; CPF repetido mostra o cliente que já o tem.
- **Cashback:** venda identificada de R$ 100,00 com cashback de 1% deixa saldo de R$ 1,00. Ao cancelar a venda, o saldo volta a R$ 0,00.
- **Uso do crédito:** com saldo de R$ 1,00, uma venda de R$ 10,00 paga com R$ 1,00 de crédito e R$ 9,00 em dinheiro deixa saldo de R$ 0,09 (cashback sobre R$ 9,00).
- **Limite do pagamento:** pagar com crédito acima do saldo é recusado.
- **LGPD:** anonimizar remove os dados pessoais e mantém as vendas; com saldo diferente de zero, é recusado.
- **Consistência:** `vw_cliente_credito_inconsistente` fica vazia em todos os testes.

---

## F13 — Devoluções e trocas (RF17)

### F13.1 Banco

- Tabela `devolucao`:
  - `venda_id`, `usuario_id`, `data_hora`, `motivo`, `forma_reembolso` e `valor_total`;
  - `cliente_id` e `liberado_por` (opcionais) e `observacao`;
  - `caixa_sessao_id`, obrigatório quando o reembolso é em dinheiro;
  - `forma_reembolso`: DINHEIRO, CREDITO_CLIENTE ou OUTRO.
- Tabela `devolucao_item`: `devolucao_id`, `venda_item_id`, `quantidade` (> 0), `valor`, `custo_total` e `volta_estoque`.
- **Movimento de estoque novo:** DEVOLUCAO_VENDA (+):
  - origem pela coluna nova `movimento_estoque.devolucao_item_id`, com FK e índice único com o tipo;
  - altera `ck_me_tipo`, `ck_me_sinal` e `ck_me_origem`; os tipos existentes passam a exigir `devolucao_item_id` nulo.
- **Crédito do cliente (tabelas da V7):**
  - nova coluna `cliente_credito_movimento.devolucao_id` (FK);
  - CREDITO_DEVOLUCAO passa a exigir `devolucao_id` (altera `ck_ccm_origem`);
  - uma venda pode ter várias devoluções parciais, e cada uma estorna a sua parte do cashback. Por isso o índice único `ux_ccm_venda_tipo` passa a valer só para movimentos sem devolução, e os movimentos de devolução ficam únicos por (`devolucao_id`, tipo).
- `vw_caixa_resumo` ganha `devolucoes_dinheiro`, no fim, e o `dinheiro_esperado` passa a descontá-la.

### F13.2 Regras

- **Quais vendas:**
  - só vendas FINALIZADAS;
  - até `devolucao.prazo_dias` depois da venda; passado o prazo, só com liberação de supervisor;
  - exige a permissão DEVOLUCAO ou liberação.
- **Quantidade por item:**
  - no máximo a quantidade vendida menos a já devolvida;
  - itens cancelados não entram;
  - inteira em produto de unidade não fracionada.
- **Valor e custo proporcionais:**
  - valor = round(`valor_liquido` × q ÷ quantidade, 2);
  - custo = round(`custo_total` × q ÷ quantidade, 2);
  - na devolução que completa o item, usa-se o restante, para que as devoluções somem exatamente o `valor_liquido` e o `custo_total` do item.
- **Estoque:**
  - volta ao estoque: DEVOLUCAO_VENDA (+q), com o custo unitário da venda;
  - avariado (não volta): DEVOLUCAO_VENDA (+q) e PERDA (−q) com o motivo "Devolução nº X — avariado: …", na mesma transação.
- **Reembolso em DINHEIRO:**
  - exige caixa aberto no terminal;
  - não pode passar do dinheiro esperado;
  - o R11 mostra as devoluções em dinheiro.
- **Reembolso em CREDITO_CLIENTE** (vale-troca):
  - exige cliente; se a venda não tinha, ele é identificado ou cadastrado na hora;
  - grava CREDITO_DEVOLUCAO.
- **Reembolso OUTRO:** estorno feito fora do sistema (cartão ou PIX); o sistema só registra.
- **Cashback:** a devolução estorna o cashback da venda na proporção do valor devolvido: round(cashback × valor devolvido ÷ valor_total da venda, 2). Na devolução que completa a venda, estorna o restante.
- **Cliente anonimizado:**
  - a devolução de uma venda dele não reembolsa em crédito, só em dinheiro ou OUTRO: não há mais a quem dar o vale-troca;
  - o supervisor pode fazer um AJUSTE no crédito de um cliente anonimizado, só para zerar o saldo e com motivo. Isso resolve o saldo que sobra quando uma venda dele é cancelada ou devolvida depois da anonimização (pendência registrada na F12).
- **Cancelamento:** venda com devolução não pode mais ser cancelada; para desfazê-la por inteiro, devolvem-se todos os itens.
- **Comprovante de devolução:** em tela, PDF e impressão, com o saldo do vale-troca quando houver.

### F13.3 Resultado comercial

- Nova view `vw_resultado_item`, com produto, categoria, operador e cliente em cada linha:
  - **linhas de venda:** itens não cancelados de vendas finalizadas, na data da venda, com sinal +;
  - **linhas de devolução:** na data da devolução, com quantidade, receita e CMV com sinal − e o operador da venda original.
- R01, R02, R05, R06, R07, R09, R10, R18, a tela do cliente (histórico, total gasto e produtos mais comprados) e o painel passam a usar essa view: receita = vendas − devoluções.
- **R01:** ganha as colunas "Devoluções" e "Receita líquida". O nº de vendas e o ticket médio continuam calculados só sobre as vendas.
- **Painel:** o faturamento é a receita líquida (bate com o R01).
- **R08:** mostra as vendas por forma de pagamento e, à parte, os reembolsos por forma. As vendas por forma continuam batendo com a coluna "Vendas" do R01, e os reembolsos somam a coluna "Devoluções".

### F13.4 Telas e relatórios

- **Tela Devoluções** (menu próprio):
  - localizar a venda por número, período ou cliente;
  - marcar itens e quantidades e indicar se o item está avariado;
  - informar o motivo e a forma de reembolso.
- **No PDV:** a janela do F9 ganha "Devolver itens".
- **R20 Devoluções:** período, operador, motivo, forma de reembolso, valor e itens.
- **Gerador** (parte B): cerca de 0,5% dos itens devolvidos, com formas de reembolso variadas.

### Aceite F13

- **Devolução em dinheiro:** venda de 3 × R$ 5,49 paga em dinheiro; devolução de 1 unidade em dinheiro. Reembolso de R$ 5,49, estoque +1 e dinheiro esperado −R$ 5,49. No R01 do dia: vendas R$ 16,47, devoluções R$ 5,49, receita líquida R$ 10,98.
- **Desconto rateado:** item de 3 × R$ 5,49 com rateio de R$ 0,82 (líquido R$ 15,65). Devolver 1 unidade dá R$ 5,22; devolver as outras 2 dá R$ 10,43. A soma fecha em R$ 15,65.
- **Limites:** quantidade acima da vendida é recusada; fora do prazo, só com liberação.
- **Avariado:** o kardex mostra a devolução e a perda, o saldo não muda e o R14 mostra a perda.
- **Vale-troca:** aumenta o saldo do cliente e paga outra venda.
- **Cashback:** venda de R$ 100,00 com cashback de R$ 1,00; devolver R$ 40,00 estorna R$ 0,40.
- **Cancelamento:** venda com devolução não pode ser cancelada.
- **Cliente anonimizado:** a devolução não oferece vale-troca, e o supervisor zera o saldo que sobrar com um ajuste; um ajuste que não zera o saldo é recusado.
- **Consistência:** R01 e R05 continuam batendo, o R08 bate com as colunas "Vendas" e "Devoluções" do R01, e `vw_estoque_inconsistente` e `vw_cliente_credito_inconsistente` ficam vazias.

---

## F14 — Crediário e contas a receber (RF18, RF19)

### F14.1 Banco

- **`cliente`:** `limite_credito` (≥ 0, padrão 0) e `crediario_bloqueado`.
- **Forma de pagamento:** novo tipo CREDIARIO (altera `ck_forma_pagamento_tipo`) e nova forma "Crediário", única desse tipo. Como a de crédito do cliente, ela não conta para a regra de sempre haver uma forma ativa: sem cliente identificado, ninguém pagaria com ela.
- **`titulo_receber`:**
  - `cliente_id` (obrigatório) e `venda_id`;
  - `parcela`, `total_parcelas`, `emissao` e `vencimento`;
  - `valor` (> 0), `valor_pago` e `valor_abatido` (≥ 0, com `valor_pago + valor_abatido ≤ valor`);
  - `status`: ABERTO, QUITADO ou CANCELADO;
  - `motivo_cancelamento`, `criado_em/por`.

  Em aberto = `valor − valor_pago − valor_abatido`; quando chega a zero, o título vira QUITADO.
- **`recebimento`:**
  - `cliente_id`, `caixa_sessao_id` e `usuario_id` (obrigatórios);
  - `liberado_por` e `data_hora`;
  - `valor_principal`, `multa`, `juros`, `desconto`, `valor_total` e `troco`;
  - `estornado`, com quem estornou e quando.
- **Tabelas de apoio:**
  - `recebimento_titulo`: principal, multa, juros e desconto de cada título;
  - `recebimento_pagamento`: forma e valor, como em `venda_pagamento` (no dinheiro, já sem o troco).
- **`devolucao`:**
  - nova coluna `valor_abatido` (de 0 até `valor_total`, padrão 0): a parte da devolução que abateu títulos em aberto da venda;
  - `forma_reembolso` passa a aceitar ABATIMENTO, usada quando a devolução inteira foi abatida.
- **`vw_caixa_resumo`:**
  - ganha `recebimentos_dinheiro`, no fim, somado ao `dinheiro_esperado` (recebimentos estornados não entram);
  - `devolucoes_dinheiro` passa a contar só a parte devolvida em dinheiro (`valor_total − valor_abatido`).

### F14.2 Venda no crediário

- **Cliente:** identificado, ativo e não bloqueado.
- **Parcelas:**
  - de 1 a `max_parcelas`;
  - a 1ª vence em `dias_primeira` dias (data editável na venda); as demais, de 30 em 30 dias a partir dela;
  - parcelas iguais, com os centavos que sobram na 1ª (R$ 100,00 em 3× = 33,34 + 33,33 + 33,33).
- **Limite:**
  - limite disponível = `limite_credito` − soma do que está em aberto;
  - passar do limite, ou ter título vencido há mais de `bloqueio_atraso_dias`, exige LIMITE_CREDITO ou liberação, com auditoria.
- **Combinação:** o crediário pode ser combinado com outras formas. A parte paga no crediário não gera cashback (a base da F12.5 também desconta o crediário).
- **Cancelamento da venda:** só se nenhuma parcela tiver pagamento; os títulos são cancelados junto.
- **Devolução de venda com crediário:**
  - primeiro, o valor devolvido abate os títulos em aberto dessa venda, da última parcela para a primeira (`valor_abatido`, na devolução e nos títulos), sem multa nem juros;
  - o restante, se houver (a parte paga em outra forma ou as parcelas já pagas), é reembolsado na forma escolhida: dinheiro, vale-troca ou outro;
  - a devolução abatida por inteiro fica com a forma ABATIMENTO;
  - o R08 mostra o abatimento como reembolso ("Reembolso: Abatimento"), e o R20 e o comprovante de devolução mostram o valor abatido e as parcelas que ficaram.
- **LGPD:** anonimizar o cliente (F12.6) passa a ser recusado também quando ele tem títulos em aberto.

### F14.3 Recebimento (permissão RECEBIMENTO ou liberação)

- **Onde:** na tela Caixa e no PDV (Ctrl+R), com caixa aberto.
- **Fluxo:**
  1. escolher o cliente;
  2. ver os títulos em aberto, com dias de atraso, encargos e valor para quitar;
  3. selecionar títulos ou digitar um valor, que é aplicado do vencimento mais antigo para o mais novo.

  Título que não é quitado continua ABERTO.
- **Encargos de cada título, na data do recebimento:**
  - dias de atraso = data do recebimento − vencimento. Com até `carencia_dias` dias de atraso, não há encargos;
  - acima disso:
    - multa = round(em aberto × `multa_pct` ÷ 100, 2);
    - juros simples = round(em aberto × `juros_mes_pct` ÷ 100 ÷ 30 × dias de atraso, 2);
  - valor para quitar = em aberto + multa + juros.
- **Pagamento parcial de título em atraso:** o valor pago é dividido na proporção do valor para quitar, de modo que os encargos só incidem sobre a parte paga e o que fica em aberto continua sujeito a eles:
  - principal = round(pago × em aberto ÷ valor para quitar, 2);
  - o resto do valor pago são encargos;
  - multa = round(encargos × multa ÷ (multa + juros), 2), e juros = encargos − multa.
- **Desconto nos encargos:** exige LIMITE_CREDITO ou liberação.
- **Pagamento:**
  - formas como no PDV, inclusive dinheiro com troco;
  - o dinheiro recebido entra no dinheiro esperado (`recebimentos_dinheiro`);
  - o R11 mostra os recebimentos.
- **Comprovante de recebimento.**
- **Estorno:** só na mesma sessão de caixa, com liberação. Volta o que foi pago dos títulos.

### F14.4 Telas e relatórios

- **Cliente:** limite, bloqueio, saldo devedor e extrato (vendas a prazo, parcelas, pagamentos e abatimentos), em tela e PDF.
- **Abatimentos por parcela:**
  - nova tabela `devolucao_titulo` (`devolucao_id`, `titulo_id`, `valor` > 0), gravada pela devolução, com quanto ela abateu de cada parcela;
  - o extrato do cliente e a reimpressão do comprovante de devolução passam a usar essa tabela, e o comprovante mostra as parcelas como ficaram no dia da devolução;
  - as devoluções feitas antes da migration recebem as linhas pela mesma regra: da última parcela para a primeira, na ordem das devoluções. O resultado é exato, porque antes desta parte ainda não existia recebimento;
  - conferência: em cada título, `valor_abatido` é a soma das linhas dele; em cada devolução, `valor_abatido` é a soma das linhas dela. A view `vw_abatimento_inconsistente` precisa ficar vazia e entra nos testes de integração e no Diagnóstico.
- **Configurações:** aba Crediário, com as chaves `crediario.*` da seção 0.4.
- **R21 Títulos a receber:** por situação, cliente e vencimento, com faixas de atraso: a vencer, 1–30, 31–60, 61–90 e mais de 90 dias.
- **R22 Recebimentos:** por período e forma, com principal, multa e juros.
- **Painel:** alerta "N títulos vencidos (R$ X)", para quem tem RECEBIMENTO.
- **Gerador:**
  - 30 clientes com limite;
  - parte das vendas no crediário;
  - recebimentos em dia e com atraso;
  - alguns títulos vencidos em aberto.

### Aceite F14

- **Parcelamento:** venda de R$ 300,00 em 3× gera 3 títulos de R$ 100,00, com vencimentos em +30, +60 e +90 dias.
- **Centavos:** R$ 100,00 em 3× dá 33,34 + 33,33 + 33,33.
- **Limite:** venda acima do limite é recusada sem liberação.
- **Encargos:** título de R$ 100,00 vencido há 10 dias (multa de 2%, juros de 2% ao mês, carência 0). Para quitar: R$ 102,67 (multa R$ 2,00 e juros R$ 0,67).
- **Parcial com atraso:** pagar R$ 51,34 desse título abate R$ 50,00 do principal (encargos de R$ 1,34: multa R$ 1,00 e juros R$ 0,34) e deixa R$ 50,00 em aberto.
- **Parcial sem atraso:** pagar R$ 50,00 de um título de R$ 100,00 ainda não vencido deixa R$ 50,00 em aberto.
- **Caixa:** recebimento em dinheiro aumenta o dinheiro esperado; estornado, deixa de contar.
- **Cancelamento:** cancelar venda com parcela paga é recusado.
- **Devolução só com abatimento:** venda de R$ 300,00 em 3×. Devolver R$ 120,00 abate R$ 100,00 da 3ª parcela e R$ 20,00 da 2ª, e a devolução fica com a forma ABATIMENTO.
- **Devolução com abatimento e restante:** venda de R$ 100,00, com R$ 40,00 em dinheiro e R$ 60,00 no crediário. Devolver tudo abate os R$ 60,00 e devolve R$ 40,00 na forma escolhida; em dinheiro, o dinheiro esperado do caixa cai só R$ 40,00.
- **LGPD:** cliente com título em aberto não pode ser anonimizado.
- **Extrato:** depois de duas devoluções da mesma venda, o extrato do cliente mostra quanto cada uma abateu de cada parcela.
- **Consistência:** o R08 continua batendo com o R01: a venda no crediário aparece na forma Crediário, e o abatimento, como reembolso. `vw_abatimento_inconsistente` fica vazia.

---

## F15 — Financeiro (RF20 a RF23)

### Parte A — contas, recebíveis e contas a pagar

#### F15.1 Contas financeiras (razão no padrão da seção 0.2)

- **`conta_financeira`:**
  - nome, tipo (COFRE, BANCO ou OUTRA) e ativa;
  - `padrao_caixa`: no máximo uma conta marcada.

  A migration cria "Cofre" (padrão do caixa) e "Banco".
- **Razão `conta_movimento`:**
  - campos: `conta_id`, `tipo`, `valor` (≠ 0, com o sinal do tipo), `data_hora`, `descricao` e `usuario_id`;
  - origem, no máximo uma: `caixa_sessao_id`, `caixa_movimento_id`, `recebivel_id`, `titulo_pagar_pagamento_id`, `devolucao_id` ou `transferencia_id` (id que agrupa as duas pernas).
- **Tipos de `conta_movimento`:**

  | Tipo | Sinal |
  |---|---|
  | ABERTURA_CAIXA | − |
  | FECHAMENTO_CAIXA | + |
  | SANGRIA | + |
  | SUPRIMENTO | − |
  | RECEBIVEL | + |
  | ESTORNO_RECEBIVEL | − |
  | PAGAMENTO | − |
  | ESTORNO_PAGAMENTO | + |
  | REEMBOLSO | − |
  | TRANSFERENCIA | ± |
  | SALDO_INICIAL | ± |
  | AJUSTE | ± (motivo obrigatório) |

- **Saldo:** protegido em `conta_saldo`, mais a view `vw_conta_inconsistente`.
  - O saldo pode ficar negativo: aparece em vermelho e gera alerta.
  - Saldo de conta nunca bloqueia uma operação de caixa.
- **Integração com o caixa** (com `financeiro.integrar_caixa` ligado):

  | Operação do caixa | Efeito na conta |
  |---|---|
  | Abertura | retira o fundo de troco da conta padrão do caixa |
  | Sangria | entra numa conta de destino |
  | Suprimento | sai de uma conta de origem |
  | Fechamento | o valor contado entra numa conta de destino |

  - As contas aparecem nos diálogos já preenchidas com a conta padrão.
  - Para isso, entram as colunas `caixa_movimento.conta_id`, `caixa_sessao.conta_origem_id` e `caixa_sessao.conta_destino_id`.
  - Sessões anteriores à fase não geram movimentos; o saldo de partida entra como SALDO_INICIAL.
- **Devolução com reembolso OUTRO:** passa a pedir a conta de onde saiu o dinheiro e lança REEMBOLSO só com a parte reembolsada (`valor_total − valor_abatido`). A parte abatida das parcelas (F14) não movimenta conta.
- **Permissão:** transferência entre contas, saldo inicial e ajuste exigem FINANCEIRO.

#### F15.2 Recebíveis de cartão e PIX

- **`forma_pagamento`:** novas colunas `taxa_pct` (0 a 20), `dias_recebimento` (0 a 60, dias corridos) e `conta_destino_id`.
  - A migration aponta para "Banco", com taxa 0, todas as formas menos Dinheiro, Crédito do cliente e Crediário; o lojista configura.
  - Essas três nunca têm conta de destino: o dinheiro vai para a gaveta, e o crédito do cliente e o crediário não movimentam dinheiro na venda.
- **Tabela `recebivel`:**
  - origem: `venda_pagamento_id` ou `recebimento_pagamento_id`;
  - forma, `valor_bruto`, `taxa` = round(`valor_bruto` × `taxa_pct` ÷ 100, 2) e `valor_liquido` = bruto − taxa;
  - `previsto_para` = data da venda + `dias_recebimento`;
  - `status`: PREVISTO, RECEBIDO ou CANCELADO;
  - `recebido_em` e `conta_id`.
- **Quando nasce:** na finalização da venda e no recebimento do crediário, para cada pagamento numa forma com conta de destino.
  - Com `dias_recebimento` = 0, o recebível já nasce RECEBIDO e lança RECEBIVEL na conta.
- **Cancelamento da venda:**
  - recebíveis PREVISTOS viram CANCELADO;
  - recebíveis já RECEBIDOS ganham ESTORNO_RECEBIVEL (a devolução do dinheiro ao cliente é feita fora do sistema).
- **Estorno de recebimento do crediário (F14.3):** faz o mesmo com os recebíveis desse recebimento.
- **Conciliação:**
  - marcar recebíveis como recebidos, por forma e período, conferindo o total;
  - o valor líquido pode ser corrigido para o que o banco pagou (a taxa passa a ser bruto − líquido);
  - lança RECEBIVEL na conta.

#### F15.3 Contas a pagar

- **Tabela `categoria_despesa`:** nome, grupo (MERCADORIA, OPERACIONAL, IMPOSTO, FINANCEIRA ou OUTRA) e ativa. Categorias iniciais:

  | Grupo | Categorias |
  |---|---|
  | MERCADORIA | Compra de mercadorias |
  | OPERACIONAL | Aluguel, Energia, Água, Internet e telefone, Salários e encargos, Pró-labore, Manutenção, Marketing |
  | IMPOSTO | Impostos sobre vendas |
  | FINANCEIRA | Tarifas bancárias e juros |
  | OUTRA | Outras |

- **Tabela `titulo_pagar`:**
  - obrigatórios: `categoria_id`, `descricao`, `competencia` (1º dia do mês) e `vencimento`;
  - opcionais: `fornecedor_id`, `entrada_id`, `documento` e `emissao`;
  - `valor` (> 0) e `valor_pago`;
  - `status`: ABERTO, PAGO ou CANCELADO;
  - `criado_em/por`.
- **Tabela `titulo_pagar_pagamento`:**
  - campos: `titulo_pagar_id`, `conta_id`, `data`, `valor`, `juros`, `desconto` e `usuario_id`;
  - lança PAGAMENTO na conta com valor + juros − desconto;
  - pagamento parcial é permitido.
- **Estorno de pagamento** (FINANCEIRO, com motivo): lança ESTORNO_PAGAMENTO e reabre o título.
- **Parcelas da entrada de mercadoria:**
  - tabela `entrada_mercadoria_parcela` (`entrada_id`, `numero`, `vencimento`, `valor`), editável enquanto a entrada está ABERTA;
  - se a soma das parcelas for diferente do valor da nota, aparece um aviso;
  - na importação de NF-e, as duplicatas do XML (`cobr/dup`: `nDup`, `dVenc`, `vDup`) já vêm preenchidas;
  - ao confirmar a entrada, cada parcela vira um título a pagar na categoria "Compra de mercadorias".
- **Despesa avulsa:** pode ter repetição mensal, que gera na hora os títulos dos próximos N meses (até 12).
- **Permissão:** tudo exige FINANCEIRO.

### Parte B — fluxo de caixa, DRE e painel

#### F15.4 Fluxo de caixa (tela e R24)

- Visão por dia, semana ou mês, com duas partes:
  - **realizado:** os lançamentos das contas, agrupados por tipo. Transferências entre contas e o saldo inicial não entram (o saldo inicial faz parte do saldo de partida), e os ajustes aparecem numa linha própria;
  - **previsto:** recebíveis PREVISTOS (líquido, na data prevista) e títulos a receber e a pagar ABERTOS (o que falta, no vencimento). O que já devia ter entrado ou saído e continua em aberto entra no primeiro dia da projeção, numa linha "Em atraso".
- Saldo projetado a partir do saldo atual das contas.
- Gráfico.

#### F15.5 DRE gerencial (R25)

Mensal, com a coluna do mês anterior e o percentual de cada linha sobre a receita líquida.

| # | Linha |
|---|---|
| 1 | Receita bruta (Σ `valor_bruto` dos itens vendidos) |
| 2 | (−) Descontos concedidos |
| 3 | (−) Devoluções |
| 4 | **= Receita líquida** (igual à receita líquida do R01 no mês) |
| 5 | (−) CMV (custo vendido − custo devolvido) |
| 6 | **= Lucro bruto**, com a margem bruta (igual ao lucro do R05 no mês) |
| 7 | (−) Perdas e ajustes de estoque, a custo (o valor do R14 no mês, sem "Estoque inicial" e "Importação") |
| 8 | (−) Cashback concedido (CASHBACK − ESTORNO_CASHBACK) |
| 9 | (−) Despesas operacionais, por categoria (títulos por competência dos grupos OPERACIONAL e OUTRA) |
| 10 | (−) Impostos sobre vendas (grupo IMPOSTO, por competência) |
| 11 | (−) Despesas financeiras (taxas de cartão e PIX pela data da venda, juros pagos e grupo FINANCEIRA) |
| 12 | (+) Receitas financeiras (multas e juros recebidos, menos o desconto dado nos encargos, só dos recebimentos não estornados; e descontos obtidos em pagamentos) |
| 13 | (±) Diferenças de caixa (sobras − faltas dos fechamentos do mês) |
| 14 | **= Resultado líquido**, com a margem líquida |

- Compras de mercadoria (grupo MERCADORIA) não entram no DRE: o custo da mercadoria aparece pelo CMV.
- Impostos e cashback ficam abaixo do lucro bruto para que as linhas 4 e 6 batam com os relatórios de venda.

#### F15.6 Painel

- Saldo das contas e resultado líquido do mês até hoje.
- Alertas de contas a pagar vencidas e vencendo hoje e de conta com saldo negativo.
- Tudo só para quem tem FINANCEIRO.

#### F15.7 R23 Contas a pagar

- Filtros: situação, categoria, fornecedor e vencimento.

#### F15.8 Gerador

- Integração com o caixa ligada, com saldo inicial no Cofre.
- Taxas nas formas de pagamento: débito 1%, crédito 3% com D+30, PIX 0% com D+0.
- Recebíveis conciliados até a semana anterior.
- Despesas mensais: aluguel, energia, salários e impostos de 6% da receita.
- Entradas de mercadoria com 2 duplicatas.

### Aceite F15

- **Recebível de cartão:** venda de R$ 100,00 no crédito, com taxa de 3% e D+30, gera recebível de R$ 97,00 para +30 dias. Conciliado, o Banco recebe R$ 97,00. No DRE do mês da venda, R$ 3,00 aparecem em despesas financeiras.
- **Caixa e Cofre:** abertura com R$ 100,00 do Cofre, sangria de R$ 50,00 para o Cofre e fechamento contado de R$ 49,00 (esperado R$ 50,00). O Cofre registra −100, +50 e +49. No DRE, a diferença de caixa é −R$ 1,00.
- **Competência:** aluguel de R$ 2.000,00 com competência em março, pago em abril. Entra no DRE de março e aparece como saída no fluxo de caixa de abril.
- **Compra de mercadoria:** entrada com 2 parcelas gera 2 títulos a pagar, que não aparecem como despesa no DRE.
- **Estorno de recebível:** cancelar uma venda paga em PIX (D+0) lança ESTORNO_RECEBIVEL no Banco.
- **Consistência:** com os dados de demonstração, a receita líquida e o lucro bruto do DRE batem com o R01 e o R05 do mesmo mês; `vw_conta_inconsistente` fica vazia.

---

## F16 — Compras: fornecedor do produto, sugestão e pedido (RF24, RF25)

### F16.1 Fornecedor do produto

- **Tabela `produto_fornecedor`:**
  - campos: `produto_id`, `fornecedor_id`, `codigo_no_fornecedor`, `unidade_compra`, `ultimo_custo`, `prazo_entrega_dias` e `principal`;
  - `fator`: quantas unidades de venda vêm em 1 unidade de compra (≥ 1);
  - único por (`fornecedor_id`, `codigo_no_fornecedor`);
  - no máximo um principal por produto.
- **Importação de NF-e:**
  - item sem EAN reconhecido é vinculado pelo par (fornecedor, `cProd`);
  - um vínculo feito à mão na importação fica gravado para as próximas notas;
  - no item vinculado pelo código do fornecedor, quantidade = `qCom` × fator e custo unitário = `vUnCom` ÷ fator (4 casas);
  - item reconhecido pelo EAN tributável usa `qTrib`, como hoje, sem aplicar o fator.
- A entrada confirmada atualiza o `ultimo_custo` do vínculo.

### F16.2 Sugestão de compra (aba de Compras e R26)

- Filtro por fornecedor principal ou por categoria.
- **Cálculo** (devoluções só entram se a F13 existir):
  - média diária = (quantidade vendida − devolvida nos últimos `compras.dias_media` dias) ÷ dias;
  - necessidade = média × (`cobertura_dias` + `prazo_entrega_dias` do fornecedor principal);
  - sugestão = o maior entre (necessidade − saldo − pendente em pedidos) e (`estoque_minimo` − saldo − pendente), nunca negativa;
  - arredondada para cima no múltiplo do fator (sem vínculo, fator 1).
- **Colunas:** produto, saldo, média por dia, cobertura atual em dias (saldo ÷ média), pendente, sugestão, último custo e valor estimado.
- "Gerar pedido" com as linhas marcadas.
- **R28 Cobertura e ruptura:** produtos com saldo ≤ 0 que venderam nos últimos 30 dias e a cobertura em dias de todos os produtos.

### F16.3 Pedido de compra (permissão COMPRAS)

- **`pedido_compra`:**
  - `fornecedor_id` (obrigatório);
  - `status`: RASCUNHO, ENVIADO, PARCIAL, RECEBIDO ou CANCELADO;
  - `previsao_entrega`, `observacao`, `criado_em/por` e `enviado_em`.
- **`pedido_compra_item`:**
  - campos: `produto_id`, `quantidade` (na unidade de venda), `custo_previsto` e `quantidade_recebida`;
  - um produto por pedido, como na entrada.
- **PDF do pedido** para enviar ao fornecedor, com as quantidades também na unidade de compra (quantidade ÷ fator).
- **Entrada vinculada a pedido:**
  - nova coluna `entrada_mercadoria.pedido_id`: a entrada pode ser vinculada a um pedido ENVIADO ou PARCIAL;
  - na confirmação, soma a `quantidade_recebida` e muda o status para PARCIAL ou RECEBIDO;
  - tela de conferência pedido × nota, com quantidade e custo divergentes em destaque.
- **Pendente em pedidos:** soma de (quantidade − recebida) dos pedidos ENVIADO e PARCIAL.

### F16.4 Relatórios, painel e gerador

- **R27 Pedidos pendentes.**
- **Painel:** alerta de ruptura (produto com saldo ≤ 0 que vendeu nos últimos 30 dias), para quem tem COMPRAS.
- **Gerador:** vínculos produto × fornecedor com fator e pedidos semanais ligados às entradas.

### Aceite F16

- **Sugestão básica:** média de 10 por dia, cobertura de 15, prazo de 3, saldo de 40, nada pendente e fator 12 dão sugestão de 144.
- **Estoque mínimo:** o mesmo produto com estoque mínimo de 200 dá sugestão de 168.
- **Vínculo pela NF-e:** item sem EAN, com `cProd` já vinculado, é reconhecido sozinho; 2 caixas com fator 12 viram 24 unidades, e o custo é o da caixa ÷ 12.
- **Pedido parcial:** pedido de 144 e entrada de 120 deixam o pedido PARCIAL com 24 pendentes; uma nova entrada de 24 deixa o pedido RECEBIDO.
- **Pendente:** quantidade pendente em pedidos reduz a sugestão.

---

## F17 — Validade e lotes (RF26)

### F17.1 Banco

- Nova coluna `produto.controla_validade BOOLEAN NOT NULL DEFAULT false`.
- **`lote`:**
  - campos: `produto_id`, `codigo` (pode ser vazio), `validade` (nula = sem validade conhecida), `criado_em/por`;
  - índice único em (`produto_id`, `codigo`, coalesce(`validade`, 'infinity'));
  - o lote especial "SEM LOTE" (sem validade) existe no máximo uma vez por produto.
- **Razão `lote_movimento`:**
  - campos: `lote_id`, `movimento_estoque_id` (nulo só nos tipos sem movimento de estoque), `tipo` (MOVIMENTO, SALDO_INICIAL ou TRANSFERENCIA), `quantidade` (≠ 0, com sinal), `usuario_id` e `criado_em`;
  - um movimento de estoque pode ser dividido em várias linhas.
- **Saldo** protegido em `lote_saldo`.
- **`vw_lote_inconsistente`** acusa duas coisas:
  - saldo do lote diferente da soma do razão;
  - num produto com o controle ligado, soma dos saldos dos lotes diferente do saldo do produto.
- **Garantia no banco:** um trigger de restrição adiado (`DEFERRABLE INITIALLY DEFERRED`) em `movimento_estoque` confere, no commit, que todo movimento de produto com o controle ligado foi inteiramente distribuído em `lote_movimento`.
- **Entrada em vários lotes:** tabela `entrada_mercadoria_item_lote` (`entrada_item_id`, `codigo`, `validade`, `quantidade`).

### F17.2 Regras (só para produtos com controle de validade)

- **Onde fica a distribuição:** dentro de `EstoqueService.movimentar`, o único caminho do estoque, que passa a receber opcionalmente a lista de lotes.
- **Saídas** (venda, ajuste de saída, perda sem lote escolhido, inventário negativo):
  - consomem os lotes com saldo por FEFO: validade mais próxima primeiro, lotes sem validade por último;
  - se os lotes não bastarem, o resto sai do "SEM LOTE", que pode ficar negativo e entra no alerta.
- **Entradas:**
  - **compra:** pela divisão em lotes da entrada; lote e validade são obrigatórios. O grupo `rastro` da NF-e (`nLote`, `qLote`, `dVal`) preenche os lotes quando existir;
  - **cancelamento de venda:** volta exatamente para os lotes de onde a mercadoria saiu, pelo `lote_movimento` da venda;
  - **devolução parcial:** volta na ordem inversa do consumo (o último lote consumido primeiro);
  - **ajuste de entrada:** vai para o lote escolhido no diálogo ou para o "SEM LOTE";
  - **sobra de inventário:** vai para o "SEM LOTE".
- **Perda por vencimento:** o usuário escolhe o lote; o motivo é "Vencimento".
- **Transferência entre lotes** do mesmo produto (ESTOQUE, com motivo): sem movimento de estoque, duas linhas TRANSFERENCIA que somam zero. Serve para corrigir lote digitado errado.
- **Ligar o controle:** lança no "SEM LOTE" a diferença entre o saldo do produto e a soma dos lotes (SALDO_INICIAL). Isso vale para a primeira vez e para quando o controle volta a ser ligado.
- **Desligar o controle:** os lotes deixam de ser movimentados e saem dos alertas e da conferência.

### F17.3 Telas, relatórios, painel e gerador

- **Estoque:** nova aba "Lotes", com lote, validade, saldo, dias para vencer e transferência.
- **Entrada de mercadoria:** divisão do item em lotes (código, validade e quantidade).
- **R29 Validade:** lotes a vencer em N dias e vencidos, com o valor a custo.
- **Painel:** alertas "N lotes vencem em até X dias" e "N lotes vencidos", para quem tem ESTOQUE.
- **Gerador:** perecíveis (laticínios, frios, padaria) com controle e lotes.

### Aceite F17

- **FEFO:** lote A (vence em 10 dias, 20 un) e lote B (vence em 40 dias, 20 un). Uma venda de 25 deixa A com 0 e B com 15.
- **Cancelamento:** cancelar essa venda devolve 20 para A e 5 para B.
- **Devolução parcial:** sem o cancelamento, devolver 3 dessa venda volta para B.
- **Entrada em lotes:** entrada de 30 em dois lotes (10 + 20) cria os dois lotes com esses saldos.
- **Transferência:** passar 2 do lote A para o "SEM LOTE" mantém o saldo do produto.
- **Perda:** a perda por vencimento no lote escolhido baixa o lote e o estoque.
- **Garantia:** movimento de produto com controle sem distribuição em lotes é recusado no commit.
- **Alerta:** aparece quando faltam 7 dias ou menos para vencer.
- **Consistência:** `vw_lote_inconsistente` e `vw_estoque_inconsistente` ficam vazias.

---

## F18 — Etiquetas de gôndola e carga de balança (RF27, RF28)

### F18.1 Etiquetas (permissão PRODUTOS)

- **Seleção de produtos**, por um destes critérios:
  - preço alterado desde uma data (pelo histórico);
  - produtos de uma entrada de mercadoria;
  - categoria;
  - lista manual.
- **Etiquetas pendentes:** produtos ativos com estoque > 0 cujo preço vigente hoje (com promoção) é diferente do preço da última etiqueta impressa, ou que nunca tiveram etiqueta.
  - A tabela `etiqueta_impressao` (`produto_id`, `preco`, `promocao_id`, `usuario_id`, `criado_em`) registra cada impressão.
  - Reimprimir é sempre possível.
- **PDF A4**, com `etiqueta.colunas` × `etiqueta.linhas` etiquetas por folha. Cada etiqueta mostra:
  - nome, preço em destaque, unidade e data;
  - preço por kg ou litro, quando fizer sentido;
  - código de barras EAN-13 ou EAN-8 desenhado (ZXing);
  - em promoção, "DE R$ X POR R$ Y", com a validade da promoção;
  - com a F20, "A partir de N un: R$ Z".
- **Impressora térmica:**
  - modelo de texto ZPL ou DPL em `etiqueta.modelo_termica`, com as variáveis {NOME}, {PRECO}, {PRECO_DE}, {UNIDADE}, {CODBARRAS} e {VALIDADE};
  - enviado em modo RAW à impressora escolhida;
  - botão "Imprimir teste".
- **Testes:**
  - o código de barras do PDF é lido de volta (ZXing) e confere com o EAN do produto;
  - o texto RAW gerado confere com o esperado.

### F18.2 Carga de balança

- **Arquivo de itens da balança Toledo (MGV, `ITENSMGV.TXT`)**, com os produtos pesáveis que têm PLU:
  - PLU, descrição e tipo de venda: por peso (o VareSync só aceita PLU em unidade fracionada);
  - preço por kg vigente na data, inclusive promoção;
  - validade em dias (opcional).

  O arquivo é gravado em `balanca.pasta_carga`.
- **Layout:**
  - fica isolado numa classe, com o formato documentado e teste por linha de amostra;
  - o formato exato (MGV5, MGV6 ou MGV7) precisa ser conferido com o manual da balança da loja antes do uso real (registrar como risco). O componente ACBrCargaBal, do Projeto ACBr, serve de referência.
- **Carga pendente:**
  - as tabelas `carga_balanca` e `carga_balanca_item` (`produto_id`, `preco`) registram cada carga;
  - a carga fica pendente quando um pesável tem preço vigente diferente da última carga.

  Isso resolve o risco registrado na Fase 8: etiqueta com valor total impressa por balança com preço desatualizado.

### F18.3 Painel

- Alertas "N produtos com etiqueta pendente" e "carga de balança pendente", para quem tem PRODUTOS.

### Aceite F18

- **Pendências:** alterar o preço de 3 produtos gera 3 etiquetas pendentes. Imprimir em PDF zera as pendências, e a reimpressão continua possível.
- **Promoção:** produto em promoção sai como "DE/POR"; quando a promoção termina, a etiqueta volta a ficar pendente.
- **Código de barras:** o código do PDF é lido no teste e confere com o EAN.
- **Térmica:** o texto ZPL ou DPL gerado para um produto confere com o esperado.
- **Carga de balança:** contém só pesáveis com PLU, usa o preço promocional do dia e fica pendente depois de uma alteração de preço.

---

## F19 — Cadastro fiscal e consistência (RF29, RF30)

Preparação para uma emissão fiscal futura. Esta fase não emite documento fiscal.

### F19.1 Empresa

- Regime tributário (SIMPLES ou NORMAL), UF e IE, em Configurações › Loja.

### F19.2 Perfis tributários (permissão FISCAL)

- **Tabela `perfil_tributario`**, com estes campos:
  - nome e `cfop` (4 dígitos);
  - `cst_icms` ou `csosn`, conforme o regime, e `aliquota_icms`;
  - `cst_pis`, `aliquota_pis`, `cst_cofins` e `aliquota_cofins`;
  - `codigo_beneficio` (opcional).
- **Novas colunas em `produto`:** `ncm` (8 dígitos), `cest` (7 dígitos), `origem` (0 a 8) e `perfil_tributario_id`.
- **Aplicação em lote:** o perfil pode ser aplicado a vários produtos de uma vez, por categoria, por seleção ou por NCM.

### F19.3 Tabelas de apoio

- **Importação:** tabela NCM a partir de um CSV oficial (`código;descrição`) e, opcionalmente, tabela CEST (`cest;ncm;descrição`). Sem tabela importada, o sistema valida só o formato.
- **XML de NF-e:** o NCM e o CEST do fornecedor preenchem os campos vazios do produto, com confirmação na tela.

### F19.4 R30 Inconsistências fiscais

- Filtro por regra e ação "abrir produto". Regras iniciais:

| # | Regra |
|---|---|
| 1 | Produto ativo sem NCM ou sem perfil tributário |
| 2 | NCM com formato inválido ou inexistente na tabela importada |
| 3 | CEST com formato inválido; CEST que não corresponde ao NCM (se a tabela CEST foi importada) |
| 4 | CSOSN em regime NORMAL ou CST de ICMS em regime SIMPLES |
| 5 | CFOP 5405 com situação que não indica ST (diferente de CST 60 e de CSOSN 500); CFOP 5102 com CST 60 ou CSOSN 500 |
| 6 | Situação sem destaque de ICMS (CST 40, 41 ou 60; CSOSN 102, 103, 300, 400 ou 500) com alíquota de ICMS maior que zero; CST tributado (00 ou 20) com alíquota zero |
| 7 | Produto com ST (CST 60 ou CSOSN 500) sem CEST |
| 8 | Origem ausente |
| 9 | PIS ou COFINS com CST 04 (monofásico), 06 (alíquota zero), 07 (isenta), 08 (sem incidência) ou 09 (suspensão) e alíquota maior que zero |
| 10 | Produtos com o mesmo NCM em perfis diferentes (aviso, não erro) |

- Cada regra é uma classe, com teste próprio.
- **A lista é revisada pelo usuário antes da implementação:** o agente mostra a tabela no plano e espera a confirmação.

### F19.5 Painel

- Alerta "N produtos com inconsistência fiscal", para quem tem FISCAL.

### Fora desta fase

- Emissão de NFC-e e NF-e. Exige certificado digital A1, CSC e homologação na SEFAZ. Pode ser feita no futuro integrando um emissor, como o ACBr.

### Aceite F19

- Cada regra tem um caso que dispara e um que não dispara.
- Aplicar um perfil a uma categoria inteira funciona.
- O XML preenche NCM e CEST vazios.
- A tabela NCM é importada, e a existência do código passa a ser validada.

---

## F20 — Embalagens de venda e preço de atacado (RF31)

### F20.1 Banco

- **`produto_codigo_barras`:**
  - `quantidade` (> 0, padrão 1): quantas unidades de venda a leitura desse código representa;
  - `preco_embalagem` (opcional, > 0);
  - o código principal tem quantidade 1.
- **Tabela `produto_preco_quantidade`:** `produto_id`, `quantidade_minima` (> 1, única por produto) e `preco` (> 0). Um produto pode ter várias faixas.
- **`venda_item.preco_quantidade_id`** (FK, nula): a faixa usada no item.

### F20.2 Regras

- **Embalagem lida no PDV** (caixa, fardo):
  - o item entra com quantidade = quantidade da embalagem × multiplicador (`2*código` = 2 embalagens);
  - **com `preco_embalagem`:** valor bruto fixo = `preco_embalagem` × nº de embalagens, como na etiqueta de balança; preço unitário = round(valor ÷ quantidade, 2);
  - **sem `preco_embalagem`:** preço unitário vigente × quantidade, e o item participa das faixas;
  - o estoque baixa a quantidade em unidades.
- **Preço por quantidade:**
  - quando a quantidade do produto no carrinho alcança uma faixa, os itens dele passam ao preço da maior faixa alcançada;
  - se a quantidade cair (cancelamento de item), os itens voltam ao preço anterior;
  - entram na contagem os itens não cancelados, exceto os de embalagem com preço próprio e os de balança.
- **Preço que vale:** o menor entre o normal, a promoção vigente e a faixa alcançada.
- **Item com desconto que muda de preço:** o desconto é mantido se couber no novo valor; senão, é retirado com aviso (mesma regra do cancelamento de item, Fase 4). A liberação de desconto é pedida de novo se a proporção aumentar.
- **Avisos:** preço de embalagem ou de faixa abaixo do custo pede confirmação, como no cadastro.
- **Comprovante:** mostra "Preço de atacado (a partir de N un)".

### F20.3 Telas

- **Cadastro de produto:**
  - a lista de códigos de barras ganha "Qtd. da embalagem" e "Preço da embalagem";
  - nova aba "Preço por quantidade".
- **F7 (consulta de preço)** mostra as faixas.

### Aceite F20

- **Embalagem com preço próprio:** caixa com 12 e preço de embalagem de R$ 59,90. Ler o código da caixa registra 12 UN por R$ 59,90 e baixa 12 do estoque; "2*código da caixa" registra 24 UN por R$ 119,80.
- **Faixa:** preço de R$ 5,49 e faixa de R$ 4,99 a partir de 6 un. Cinco unidades somam R$ 27,45; ao registrar a 6ª, todas passam a R$ 4,99 (R$ 29,94); cancelar uma volta a 5 × R$ 5,49 (R$ 27,45).
- **Promoção:** com promoção vigente de R$ 4,79, as 6 unidades saem a R$ 4,79 (R$ 28,74).
- **Consistência:** a venda finalizada continua com `ck_venda_total` e `ck_vi_liquido` válidos, e o R01 bate com o R05.

---

## F21 — Usabilidade e acessibilidade (RNF12)

- **Ajuda contextual (F1):** abre a ajuda da tela atual num painel lateral, com a lista de atalhos.
  - Os textos ficam em `resources/ajuda/*.md`.
  - No PDV, o F1 atual passa a usar o mesmo painel.
- **Busca global (Ctrl+K):** encontra produtos (por nome, código ou código de barras), clientes, venda por número, fornecedores e telas do menu, respeitando as permissões. Enter abre o resultado.
- **Preferências por usuário** (tabela `usuario_preferencia`), aplicadas no login:
  - tema claro ou escuro;
  - tamanho de fonte: 100%, 115% ou 130%.
- **Assistente "Primeiros passos"** para o supervisor:
  - checklist com progresso: dados da loja, formas de pagamento, produtos ou importação, usuários, balança e backup;
  - some quando estiver completo e pode ser reaberto pela Ajuda.
- **Padronização das telas:**
  - confirmação em toda ação destrutiva;
  - sucesso em notificação discreta;
  - foco inicial e ordem do Tab revisados;
  - contraste mínimo AA no tema escuro.

### Aceite F21

- F1 mostra a ajuda da tela atual.
- Ctrl+K encontra produto, cliente, venda e tela.
- Tema escuro e fonte de 130% ficam salvos por usuário e não cortam textos nas telas principais (conferido por capturas).
- O assistente marca os passos concluídos.

---

## Fora do escopo (trabalhos futuros)

- Emissão de NFC-e, NF-e e SAT.
- Integração TEF com as maquininhas de cartão.
- Multi-loja (estoque e caixa por loja, transferências entre lojas).
- Kits e produtos compostos.
- Comissão de vendedores e metas.
- Convênio com empresas (venda a funcionários com cobrança mensal).
- Orçamento e pré-venda.
- Coletor de dados para inventário.
- E-commerce e marketplaces.
- Aplicativo móvel, nuvem e BI externo.
