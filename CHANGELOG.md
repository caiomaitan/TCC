# Histórico de versões

## 2.0.1 — 2026-11-19

Correções encontradas pela integração contínua (GitHub Actions, Linux), na primeira vez que ela rodou. Nenhuma regra
de negócio nova e nenhuma atualização do banco.

- **Configurações com o emitente e a NFC-e em branco.** Dependendo da ordem em que o Java carregava as partes das
  configurações, os valores padrão podiam ficar sem os dados do emitente e da NFC-e até o sistema ser fechado, e a
  tela de NFC-e e o PDV com a NFC-e ligada davam erro. No uso normal o sistema carregava na ordem certa, por isso o
  problema só apareceu nos testes do Linux. Agora a ordem não importa, e há um teste que confere todas as ordens.
- **Senha do certificado de teste fora do log.** Os testes geram um certificado A1 de teste com o `keytool` do Java, e
  a senha de teste ia na linha de comando, que o próprio Java registra no log de depuração. Agora a senha vai por uma
  variável de ambiente do processo, como a do banco no backup. Só os testes usam o `keytool`; a senha do certificado
  da loja nunca passou por linha de comando.
- **Testes de integração que dependiam da ordem.** O teste das migrations passou a conferir o banco recém-criado num
  schema próprio e vazio, e o teste do script de integridade limpa o banco antes de rodar. Antes, os dois só passavam
  se rodassem antes dos testes que gravam dados.
- **Testes de interface do CI com uma fonte como a do Windows.** No Linux, a fonte padrão é mais larga que a Segoe UI
  do Windows, e com a fonte em 130% a tela de Configurações e a barra de status passavam da borda da janela só nos
  testes do CI. Agora o CI instala a Selawik, fonte livre da Microsoft com as mesmas medidas da Segoe UI, e os testes
  medem as telas como no computador da loja. As telas do sistema não mudaram.

## 2.0.0 — 2026-11-17

A 2.0.0 é a primeira versão depois da que foi entregue e apresentada no TCC (tag `tcc-entrega`, versão 0.1.0). Ela
traz as fases 10 a 23 do plano de ampliações (`docs/ESPECIFICACAO_AMPLIACOES.md`). Os detalhes de cada fase, as
decisões e os números dos testes estão em [`docs/PROGRESSO.md`](docs/PROGRESSO.md).

> **A NFC-e é só de homologação, sem valor fiscal.** O sistema monta, assina e transmite a NFC-e apenas no ambiente
> de homologação da SEFAZ (ambiente de testes, `tpAmb 2`), e o DANFE sai com o aviso "EMITIDA EM AMBIENTE DE
> HOMOLOGAÇÃO – SEM VALOR FISCAL". Não use a 2.0 para emitir nota fiscal de verdade. Os cálculos de tributos e as
> regras fiscais não foram conferidos por um contador.

### Antes de instalar

- **O banco é atualizado sozinho.** Ao abrir a 2.0 num banco da versão do TCC, o sistema aplica as atualizações do
  banco (migrations V4 a V24). Depois disso, a versão do TCC não abre mais esse banco. Faça um backup antes
  (Configurações › Backup).
- **O banco da banca fica protegido.** A 2.0 não abre o `varesync_demo`, que é o banco da apresentação do TCC: se a
  configuração apontar para ele, a tela de conexão explica e pede outro banco. Para ver a versão nova com dados de
  demonstração, use um banco separado, o `varesync_amostra` (README, seção 6).
- **Pré-requisitos:** os mesmos da versão do TCC (Windows 10 ou 11, PostgreSQL 14 ou mais novo).

### Novidades, fase por fase

#### Fase 10 — Robustez e segurança
- Quando o supervisor muda as permissões de alguém, a mudança vale na hora, sem a pessoa sair do sistema; se a
  pessoa for inativada ou tiver a senha redefinida, ela é desconectada (a venda em andamento é abandonada e
  registrada).
- Bloqueio da tela por inatividade (o tempo é configurável): volta com a senha ou com "Trocar usuário".
- Política de senha configurável (tamanho mínimo, letra e número), mostrada em todas as telas que pedem senha.
- O relatório de cancelamentos mostra quem cancelou cada item e quem liberou.
- O extrato do estoque (kardex) começa pelo saldo anterior ao período.
- Cadastro de unidades de medida.
- Aviso antes de gerar um PDF muito grande (mais de 50 páginas), com a sugestão de usar os filtros.
- A contagem do inventário grande ficou rápida (a tela só atualiza a linha contada).
- O backup é conferido depois de gravado; se o arquivo não abrir, ele é descartado e o sistema avisa.

#### Fase 11 — Engenharia de software
- Tela **Sobre** (Ajuda › Sobre o VareSync… ou o número da versão na barra de status) com a versão, o commit e a
  data do build.
- "Testar conexão" na tela de conexão mostra a versão do PostgreSQL e o tempo de resposta.
- Instalação em rede (o banco num computador e os caixas em outros) explicada no README.
- Para quem desenvolve: análise de qualidade e cobertura (`-Pqualidade`), testes de tela (`-Pui`), testes de
  concorrência, integração contínua no GitHub e instalador MSI opcional.

#### Fase 12 — Clientes, crédito e cashback
- Cadastro de clientes com busca por nome, CPF/CNPJ ou telefone, histórico de compras, total gasto, ticket médio e
  produtos mais comprados.
- Cliente identificado na venda (F11 no PDV), com o nome e o CPF mascarado no comprovante.
- Crédito do cliente (saldo com extrato) e pagamento com o crédito na venda.
- Cashback configurável sobre as compras.
- LGPD: consentimento de marketing, exportação dos dados do cliente (PDF e CSV) e anonimização.
- Relatórios R18 Clientes e R19 Saldos de crédito.

#### Fase 13 — Devoluções e trocas
- Devolução total ou parcial de uma venda (F9 › Devolver itens…, ou o menu Devoluções), com prazo configurável,
  item avariado lançado como perda e comprovante de devolução.
- Reembolso em dinheiro, em vale-troca (vira crédito do cliente) ou de outra forma; o cashback é estornado na
  proporção.
- Os relatórios de vendas e de lucro, o painel e o histórico do cliente passam a descontar as devoluções (receita
  líquida).
- Relatório R20 Devoluções.

#### Fase 14 — Crediário e contas a receber
- Venda no crediário, em parcelas, com limite de crédito por cliente, bloqueio e liberação pelo gerente.
- Recebimento das parcelas no caixa ou no PDV (Ctrl+R), com juros e multa do atraso, pagamento parcial, desconto e
  comprovante.
- Devolução de venda no crediário abate das parcelas em aberto.
- Ficha do crediário do cliente, alertas no painel e relatórios R21 e R22.

#### Fase 15 — Financeiro
- Contas financeiras (Cofre e Banco) com saldo, extrato, transferências e ajustes; o caixa pode lançar a abertura,
  as sangrias e o fechamento nessas contas.
- Recebíveis dos cartões e do PIX, com taxa e prazo por forma de pagamento, e conciliação.
- Contas a pagar com categorias, despesas que se repetem, juros, desconto e as duplicatas da nota de entrada.
- Fluxo de caixa (realizado e previsto), DRE do mês e alertas de contas vencidas no painel.
- Relatórios R23 Contas a pagar, R24 Fluxo de caixa e R25 DRE.

#### Fase 16 — Compras
- Fornecedores de cada produto, com o código no fornecedor, a caixa de compra e o último custo; a nota do fornecedor
  (XML) reconhece o produto também pelo código do fornecedor.
- Sugestão de compra pela venda dos últimos dias, com ruptura e cobertura.
- Pedido de compra (rascunho, enviado, parcial, recebido), em PDF, gerado direto da sugestão e conferido na entrada
  da mercadoria.
- Alerta de ruptura no painel e relatórios R26, R27 e R28.

#### Fase 17 — Validade e lotes
- Controle de validade e lotes por produto: a entrada divide a quantidade em lotes, e a venda sai do lote que vence
  primeiro.
- Aba Lotes no Estoque, transferência entre lotes e perda por vencimento.
- Alertas de lotes vencendo e vencidos no painel e relatório R29 Validade.

#### Fase 18 — Etiquetas e carga de balança
- Etiquetas de gôndola em folha A4 (com código de barras e "de/por" na promoção) ou em impressora térmica, com a
  lista dos produtos que mudaram de preço.
- Arquivo de carga para a balança (formato MGV), com as pendências de preço, descrição e validade.
- Alertas de etiquetas e de carga pendentes no painel.

#### Fase 19 — Cadastro fiscal e consistência
- Regime tributário, UF e inscrição estadual da loja.
- Perfis tributários, NCM, CEST e origem no cadastro do produto, aplicados em lote; tabelas NCM e CEST importadas.
- Conferência fiscal dos produtos (R30 Inconsistências fiscais) e alerta no painel.

#### Fase 20 — Embalagens e preço de atacado
- Código de barras da caixa ou do fardo (a leitura no PDV entra com a quantidade da embalagem).
- Preço por quantidade (atacado), aplicado sozinho no PDV, no comprovante, na consulta de preço (F7) e na etiqueta.

#### Fase 21 — Usabilidade e acessibilidade
- Tema escuro e fonte maior (115% e 130%), por usuário (Minhas preferências).
- Ajuda de cada tela (F1) com os atalhos, e busca geral (Ctrl+K) por produtos, clientes, vendas e telas.
- Primeiros passos no painel para a loja nova.
- Mensagens de sucesso discretas, telas reorganizadas e textos que não se cortam na tela de 1280×720.

#### Fase 22 — Tributos da venda
- Cálculo dos tributos de cada item vendido (ICMS, PIS, COFINS e os novos IBS e CBS da reforma tributária), com a
  substituição tributária lida da nota do fornecedor.
- Tributos aproximados (tabela IBPT) no comprovante.
- Relatório R31 Impostos das vendas e novas conferências no R30.

#### Fase 23 — NFC-e em homologação (sem valor fiscal)
- Emissão da NFC-e no PDV no ambiente de homologação, com o certificado A1 do terminal, o CPF/CNPJ na nota e o DANFE
  de 80 mm com o QR Code.
- Contingência off-line quando a SEFAZ não responde: a venda continua, e as notas são transmitidas depois, em
  segundo plano.
- Cancelamento da NFC-e junto com a venda (no prazo), aba Notas fiscais (ver DANFE, salvar XML, transmitir,
  corrigir e reenviar, inutilizar números, ZIP do mês para o contador), alertas no painel e relatório R32.

### Pendências conhecidas

- **NFC-e:** nada foi transmitido a uma SEFAZ de verdade; tudo foi conferido com uma SEFAZ simulada. A homologação
  real depende de um certificado A1. O DANFE não foi impresso numa térmica de 80 mm nem lido por um app de celular.
  Os prazos de cancelamento e de inutilização não conhecem os feriados nem as regras de cada UF. A nota rejeitada em
  contingência só aceita a correção dos códigos (uma alíquota errada vai para o contador).
- **Fiscal:** o cálculo dos tributos, as regras do R30, os perfis e os NCM dos dados de demonstração não foram
  conferidos por um contador. A tabela IBPT e o arquivo oficial do NCM não foram importados de verdade (só arquivos de
  teste no mesmo formato).
- **Balança e etiquetas:** o arquivo de carga não foi importado num MGV nem numa balança de verdade; os modelos da
  impressora térmica e a impressão direta não foram testados numa impressora; o código de barras impresso na folha A4
  não foi lido por um leitor.
- **Instalação:** o instalador MSI não foi gerado (o WiX não estava instalado); a integração contínua nunca rodou no
  GitHub; a instalação em rede não foi feita com dois computadores de verdade.
- **Testes pela pessoa:** os testes de tela automáticos acionam os componentes direto, sem teclado e mouse de verdade,
  sem a escala do Windows acima de 100% e sem um monitor de 1280×720 de verdade. Os casos de teste manuais das fases
  10 a 23 que ainda dizem "Falta a pessoa" estão em [`docs/ROTEIRO_TESTES.md`](docs/ROTEIRO_TESTES.md).
- **Telas a 130%:** em 1280×720, algumas tabelas mostram poucas linhas (as telas divididas e o crediário do cliente)
  e algumas células longas saem com "…" (a coluna pode ser alargada; o PDF e o CSV saem completos).
- **Testes de tela:** uma falha intermitente (o segundo login da varredura sem a janela principal) apareceu duas
  vezes e não se repetiu.

A lista completa, com os riscos de cada fase, está em `docs/PROGRESSO.md`, seção Pendências e riscos.

## 0.1.0 — versão do TCC (tag `tcc-entrega`)

Fases 0 a 9 da especificação (`docs/ESPECIFICACAO.md`): acesso e usuários, produtos, estoque, caixa e PDV com
comprovante, inventário, relatórios R01 a R17, painel, dados de demonstração, compras e entrada por XML da NF-e,
promoções, balança, importação por planilha, backup e auditoria, e o pacote `VareSync.exe`. É a versão apresentada à
banca, com o banco `varesync_demo` (`docs/DEMO.md`).
