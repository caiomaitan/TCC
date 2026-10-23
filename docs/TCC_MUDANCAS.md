# O que mudou em relação ao texto do TCC

Guia para atualizar os capítulos do TCC com o sistema entregue. Cada seção diz **o que o texto do TCC I diz**, **o que
o sistema faz agora** e **por quê**, com a referência ao documento do projeto que detalha a decisão
(`docs/ESPECIFICACAO.md`, seção; `docs/PROGRESSO.md`, Decisões). As figuras novas estão em `docs/telas/` e os
diagramas em `docs/diagramas/`.

Resumo para a introdução dos resultados: **os 13 requisitos funcionais (RF01 a RF13) e os 8 não funcionais do TCC
foram atendidos**; o sistema foi ampliado de MVP para um ERP de loja (caixa, compras, promoções, balança, importações,
backup, auditoria); o banco do TCC foi revisto em 19 pontos; quatro regras do texto foram trocadas por motivos de
integridade ou de operação de loja.

---

## 1. Requisitos

### 1.1 Requisitos do TCC (todos atendidos)

| Código | Requisito (TCC) | Como ficou | Fase |
|---|---|---|---|
| RF01 | Cadastrar produto (nome, custo, preço de venda) | Mais categoria, unidade de medida (UN, KG…), vários códigos de barras com dígito verificador, estoque mínimo, PLU de balança, estoque inicial e simulador de margem/markup/preço sugerido | 2 |
| RF02 | Editar e excluir produto | Excluir só sem vínculos (vendas, movimentos, inventários, entradas, promoções); senão o sistema oferece **inativar**. Histórico de preço e custo com usuário e data | 2 |
| RF03 | Consultar produtos | Busca sem acento nem maiúsculas, por código interno ou código de barras exato; filtros de categoria e situação (inclusive "preço abaixo do custo") | 2 |
| RF04 | Registrar vendas no PDV | PDV em janela própria, todo por teclado (F1–F12), com caixa aberto; várias formas de pagamento, troco, descontos, cancelamentos, comprovante | 4 |
| RF05 | Selecionar produtos durante a venda | Leitor de código de barras, código interno, `quantidade*código`, busca F2, etiqueta de balança | 4 / 8 |
| RF06 | Atualizar o estoque após a venda | Um movimento `VENDA` por item, na mesma transação da venda; cancelamento devolve com `CANCELAMENTO_VENDA` | 4 |
| RF07 | Criar inventário | Inclusão por código, busca, categoria ou todos os ativos; contagem cega com leitor | 5 |
| RF08 | Atualizar o estoque pelas divergências | Aplica a **diferença** capturada na contagem (ver 4.1) | 5 |
| RF09 | Visualizar a quantidade em estoque | Tela Estoque com saldo, mínimo, custo médio, valor em estoque e kardex com saldo após cada movimento | 3 |
| RF10 | Relatórios de vendas e desempenho | 17 relatórios (R01–R17) com PDF e CSV, mais o painel | 6 / 7 |
| RF11 | Calcular lucro e margem | Lucro com o custo gravado na venda; margem agregada = Σ lucro ÷ Σ receita | 6 |
| RF12 | Login e senha | Senha BCrypt, bloqueio após 5 erros por 5 min, troca obrigatória da senha provisória, primeiro acesso sem senha padrão | 1 |
| RF13 | Supervisor define as rotinas | 10 permissões por usuário + perfil supervisor; liberação de supervisor por senha para ações pontuais | 1 |
| RNF01 | Integridade dos dados | Triggers, constraints nomeadas, transação única por operação, kardex imutável, auditoria | todas |
| RNF02/03 | Interface simples, informação clara | Linguagem do lojista, mensagens junto do campo, atalhos visíveis, tema claro, PDV com total ≥ 40 px | todas |
| RNF04 | Acesso restrito | Permissão conferida no service (não só escondida na tela) | 1 |
| RNF05 | Tempo de resposta | Metas medidas (ver `docs/TCC_RESULTADOS.md`, seção 2) | 9 |
| RNF06 | Modular | Camadas controller → service → dao, composition root (`AppContext`) | 0 |
| RNF07 | Desktop | JavaFX 21; pacote Windows com Java embutido (`VareSync.exe`) | 0 / 9 |
| RNF08 | Banco relacional | PostgreSQL 16, migrations Flyway | 0 |

**Ajuste de texto sugerido**: no RF13 a Fig. 16 do TCC tinha o checkbox "Inventário" mas o banco não tinha essa
permissão; agora as permissões são: Produtos, Fornecedores, Compras, Estoque, Inventário, PDV, Sangria e suprimento,
Cancelar venda, Desconto acima do limite e Relatórios. Usuários, configurações, backup, auditoria e dados de
demonstração são exclusivos do supervisor.

### 1.2 Ampliações (não estavam no MVP do TCC)

| Ampliação | O que faz | Seção da especificação |
|---|---|---|
| Caixa | Abertura com fundo de troco, sangria, suprimento, **fechamento às cegas** e relatório de fechamento (R11) | 6.5 |
| PDV completo | Várias formas de pagamento, troco, desconto no item e na venda com limite e **rateio**, cancelamento de item e de venda com liberação de supervisor, comprovante não fiscal (tela, PDF e impressora) | 6.6 |
| Cadastro | Categorias, unidades, vários códigos de barras, estoque mínimo, histórico de preço e custo, simulador | 6.3 |
| Estoque | Kardex, perdas com motivo, ajuste "Ajustar saldo para…", alerta de mínimo, diagnóstico | 6.4 |
| Relatórios | Painel com gráfico e alertas; 12 relatórios além dos 5 do TCC; PDF e CSV; gerador de dados de demonstração | 7 |
| Compras | Fornecedores (CPF ou **CNPJ alfanumérico**), entrada de mercadoria com **custo médio ponderado**, nota repetida recusada, sugestão de reajuste. No TCC I (seção 3.4) compras estava fora do MVP | 6.10 |
| Extras | Promoções com vigência, etiqueta de balança (preço ou peso), importação de produtos por planilha CSV, importação de XML de NF-e, backup (manual e automático), consulta de auditoria, impressão do comprovante | 6.8, 6.9, 6.11–6.13 |
| Entrega | Pacote Windows com Java embutido (jpackage), ícone próprio | 9 |

### 1.3 Continua fora do escopo (trabalhos futuros)

Emissão fiscal (NFC-e) e integração com a SEFAZ — por isso o comprovante traz "NÃO É DOCUMENTO FISCAL"; TEF
(maquininha); financeiro (contas a pagar e receber, fluxo de caixa); várias lojas; versão web ou mobile; nuvem.

---

## 2. Tecnologias (atualizar o capítulo de ferramentas)

| Item | No sistema |
|---|---|
| Linguagem | Java 21 (LTS) |
| Interface | JavaFX 21.0.12, FXML + CSS próprio (sem AtlantaFX nem Ikonli) |
| Banco | PostgreSQL 16 (funciona do 14 em diante), driver JDBC 42.7.13 |
| Acesso a dados | JDBC puro com `PreparedStatement` + HikariCP 7.1.0 (sem JPA/Hibernate) |
| Migrations | Flyway 13.9.0 (`flyway-core` + `flyway-database-postgresql`) |
| Senhas | BCrypt (`at.favre.lib:bcrypt` 0.10.2), custo 12 |
| Relatórios PDF | JasperReports 7.0.8 (+ `-pdf`, `-fonts` com DejaVu Sans, `-jdt`) |
| Log | SLF4J 2.0.20 + Logback 1.6.5 |
| Testes | JUnit 6.1.3 (Jupiter) + AssertJ 3.27.7; Surefire (unitários) e Failsafe (integração) |
| Build e pacote | Maven; `jpackage` do JDK (app-image com `VareSync.exe` e Java embutido) |

---

## 3. Banco de dados (atualizar o DER, o MER e o dicionário de dados)

O DER novo está em `docs/diagramas/der.mmd` (24 tabelas). O banco tem também **4 views** e **6 funções de trigger**.
Migrations: `V1__schema_inicial.sql` (esquema), `V2__dados_iniciais.sql` (permissões, unidades, categorias, formas de
pagamento, configurações) e `V3__horario_da_aplicacao.sql` (os horários de gravação vêm da aplicação, para testes e
dados de demonstração com data no passado).

### 3.1 Correções no modelo do TCC (ESPECIFICACAO 5.1)

| # | No TCC | Agora | Por quê |
|---|---|---|---|
| 1 | `USUARIO.senha VARCHAR(15)` em texto puro | `usuario.senha_hash` (BCrypt) | Senha não pode ficar legível no banco (RNF04) |
| 2 | Permissões como colunas `pdv/produtos/estoque/relatorios`; sem `inventario` | Tabelas `permissao` + `usuario_permissao`; `supervisor` como acesso total | Nova rotina sem `ALTER TABLE`; corrige a permissão de inventário que faltava |
| 3 | `USUARIO.nome VARCHAR(15)` servia de login | `login` (único, minúsculo) + `nome` (80) | Nome de exibição ≠ credencial |
| 4 | `PRODUTOS.nome VARCHAR(20)` | `produto.nome VARCHAR(120)` + `nome_busca` (gerada, sem acento) | "REFRIGERANTE COLA PET 2L" já tem 24 caracteres |
| 5 | Produto sem código de barras, unidade, categoria e mínimo | `produto_codigo_barras`, `unidade_medida`, `categoria`, `estoque_minimo` | Leitor no PDV, quantidade em KG, relatórios por categoria, alerta |
| 6 | `custo DECIMAL(10,2)` | `custo NUMERIC(14,4)` (custo médio) + `ultimo_custo` | Custo unitário tem mais de 2 casas (12 por R$ 10,00 = 0,8333) |
| 7 | `inativo`, `data_alt`, `codigo_usuario` | `ativo`, `criado_em/por`, `atualizado_em/por` + `produto_historico_preco` (trigger) | Saber quem mudou preço ou custo, e quando |
| 8 | `VENDA_ITEM` sem custo | `custo_unitario` e `custo_total` gravados na venda | Sem isso, mudar o custo muda o lucro de vendas passadas |
| 9 | PK de `VENDA_ITEM` = (`id_cupom`, `cod_produto`) | `id` próprio + `sequencia` | O mesmo produto em duas linhas; o movimento aponta para o item |
| 10 | `VENDA.data` e `VENDA_ITEM.data` como `DATE` | só `venda.data_hora TIMESTAMPTZ` | Hora da venda (relatório por hora, fechamento de caixa) |
| 11 | `VENDA` só com `valor`; `numero_cupom` | Bruto, descontos, total, troco, status, dados do cancelamento; `venda_pagamento`; o id é o número da venda | Descontos, várias formas, cancelamento auditável; sem emissão fiscal não há "cupom" |
| 12 | Sem controle de caixa | `caixa_sessao` + `caixa_movimento` + `vw_caixa_resumo` | Abertura, sangria, suprimento, conferência |
| 13 | `MOVIMENTO_ESTOQUE.cod_produto` aceitava NULL | `produto_id NOT NULL` | Todo movimento é de um produto |
| 14 | Movimento apontava para a venda; `tipo VARCHAR(1)`; DER com `saldo` e MER sem | `venda_item_id` / `inventario_item_id` / `entrada_item_id` com CHECK por tipo; 7 tipos com sinal validado; `saldo_anterior`, `saldo_posterior`, `custo_unitario`, `usuario_id`, `motivo` | Kardex rastreável e valorizado em R$ |
| 15 | `PROD_ESTOQUE` atualizado pela aplicação | Atualizado só por trigger a partir do movimento; movimentos imutáveis | Saldo sempre igual à soma dos movimentos, até com edição direta no banco |
| 16 | `INVENTARIO.atualizado BOOLEAN`; `ITENS_INV.diferenca` gravada | `status` (ABERTO/APLICADO/CANCELADO), quem e quando aplicou; `diferenca` gerada; `estoque_sistema` capturado na contagem | Ciclo de vida claro; diferença nunca inconsistente |
| 17 | Ajuste "conforme a quantidade contada" | Ajuste pela diferença capturada na contagem | Ver 4.1 |
| 18 | Nomes mistos (`PRODUTOS` × `VENDA`, `ITENS_INV`, `cod_inv`) e DER/MER divergentes | snake_case, singular, nomes por extenso, um modelo só | Consistência |
| 19 | — | `fornecedor`, `entrada_mercadoria`, `entrada_mercadoria_item`, `promocao`, `configuracao`, `auditoria` | Ampliações |

### 3.2 Regras que o próprio banco garante (texto para o capítulo de integridade)

- Saldo muda só por movimento, com bloqueio da linha (seguro com vários caixas): trigger `trg_movimento_aplica_saldo`.
- Kardex imutável (`trg_movimento_imutavel`): correção é um novo movimento.
- Produto nasce com saldo zero (`trg_produto_cria_estoque`); histórico de preço e custo por trigger.
- Sinal × tipo, origem × tipo e motivo obrigatório em ajuste e perda (`ck_me_sinal`, `ck_me_origem`).
- Um caixa aberto por terminal (`ux_caixa_aberto_por_terminal`); item de venda não baixa duas vezes (`ux_me_venda_item`).
- Total = bruto − descontos (`ck_venda_total`, `ck_vi_liquido`); cancelamento exige quem, quando e motivo.
- Mesma nota do mesmo fornecedor recusada (`ux_em_documento`).
- `vw_estoque_inconsistente` (saldo × soma dos movimentos) fica sempre vazia; é conferida ao fim de cada teste de
  integração e na tela de diagnóstico.

---

## 4. Regras de negócio que mudaram

### 4.1 Inventário aplica a diferença (Apêndice C do TCC)

O texto do TCC iguala o saldo à quantidade contada. O sistema aplica a **diferença capturada no momento da contagem**
(contado − saldo naquele instante). Exemplo (ESPECIFICACAO A.8): sistema 10, contagem 8 (diferença −2); depois da
contagem sai 1 unidade numa venda (sistema 9, físico 7). Aplicar −2 dá **7** (certo); igualar ao contado daria 8 e a
venda posterior sumiria do estoque. A contagem é **cega** (quem conta não vê o saldo) e itens não contados são
ignorados.

### 4.2 "Produto não disponível" no PDV

No TCC, produto sem saldo bloqueava a venda. Agora é configurável (**Configurações › Venda**), com padrão **permite e
avisa**: bloquear porque o estoque do sistema está errado trava o caixa com o cliente na fila. Produto inativo ou com
preço zero continua bloqueado.

### 4.3 "Estoque Atual" não é editável no cadastro (Fig. 20)

Estoque só muda por movimento. O cadastro novo tem **estoque inicial** (gera um `AJUSTE_ENTRADA` "Estoque inicial");
depois disso, ajustes pela tela Estoque (entrada, saída, perda, "ajustar saldo para…"), sempre com motivo.

### 4.4 Lucro com o custo da venda

O lucro usa o custo gravado no item no momento da venda, não o custo atual do produto. Reajustar o custo hoje não muda
o lucro de ontem. Todos os relatórios e o painel saem da mesma view (`vw_venda_item_rentabilidade`) e por isso batem.

### 4.5 Exclusão de produto

Só sem nenhum vínculo; senão o sistema oferece inativar (já previsto no caso de uso "Excluir produto"). Inativo não
aparece no PDV nem na busca padrão, mas continua nos relatórios.

### 4.6 Regras novas que valem citar (não estavam no TCC)

| Regra | Como funciona | Referência |
|---|---|---|
| Margem agregada | Σ lucro ÷ Σ receita × 100, nunca média de margens (A.6: 13,64% e não 30%) | 7.1 |
| Desconto com limite | (descontos ÷ bruto) ≤ limite do operador (padrão 5%); acima exige permissão ou liberação do supervisor, registrada na auditoria | 6.6 |
| Rateio do desconto da venda | Proporcional entre os itens pelo maior resto em centavos (A.4); a soma é exatamente o desconto | 6.6 |
| Cancelamento de venda | Status `CANCELADA` + movimentos de devolução; a venda nunca é apagada; motivo e quem liberou | 6.6 |
| Fechamento às cegas | O operador informa o dinheiro contado sem ver o esperado; só então aparece a sobra ou falta | 6.5 |
| Custo médio ponderado | (saldo × custo atual + qtd × custo da entrada) ÷ (saldo + qtd), 4 casas; saldo ≤ 0 usa o custo da entrada (A.5) | 6.10 |
| Preço sugerido | custo ÷ (1 − margem), arredondado **para cima** no centavo (A.6) | 6.3 |
| Curva ABC | % acumulado **antes** do item < 80% → A; < 95% → B; senão C (A.9) | 7.1 |
| Etiqueta de balança | EAN-13 com prefixo e PLU; no modo preço vale o valor impresso e a quantidade é calculada (A.7) | 6.9 |
| Liberação de supervisor | Login e senha de quem tem a permissão; vale só para aquela ação | 6.2 |
| Horários pela aplicação | Toda gravação usa o relógio da aplicação (`Clock`), não o `now()` do banco (V3) | 3.5 |

---

## 5. Telas (atualizar as figuras da seção 8.2 do TCC)

### 5.1 Figuras do protótipo e as capturas que as substituem

| Figura do TCC | Captura nova (`docs/telas/`) | O que mudou em relação ao protótipo |
|---|---|---|
| Fig. 14 — Login | `fig14_login.png` | Campo "Usuário" é o login; Enter entra; mensagem de erro abaixo dos campos, sempre genérica ("Usuário ou senha inválidos") |
| Fig. 15 — Principal | `fig15_principal_painel.png` | Painel com cartões do dia (comparação com a semana anterior), gráfico de 30 dias, top 5 do mês por lucro e alertas clicáveis; menu lateral só com o permitido; barra de status (usuário, terminal, caixa, banco, data e hora); "Log off" e "Sair" mantidos |
| Fig. 16 — Usuários | `fig16_usuarios.png` | Lista + formulário; permissões do catálogo (inclui Inventário) e Supervisor; redefinir senha, desbloquear, inativar |
| Fig. 17 — Estoque | `fig17_estoque_kardex.png` | Busca por código, código de barras ou nome; cabeçalho com saldo, mínimo, custo médio e valor; kardex com saldo após e origem; ajustes F4/F5 |
| Fig. 18 — Inventário | `fig18_inventario_contagem.png`, `fig18_inventario_divergencias.png` | Abas Contagem (cega) e Divergências (R13: sobra, falta e não contados em R$); botões Aplicar e Cancelar inventário |
| Fig. 19 — PDV | `fig19_pdv_venda_em_andamento.png` | Janela própria; total em destaque; último item; subtotal e descontos; operador, terminal e nº do caixa; barra de atalhos F1–F12 |
| Fig. 20 — Produto | `fig20_produto_cadastro.png`, `fig20_produto_historico.png` | Estoque Atual somente leitura (estoque inicial só no cadastro novo); vários códigos de barras; simulador; PLU; aba Histórico |
| Fig. 21 — Busca | `fig21_produtos_busca.png` | Filtros de categoria e situação; colunas código, nome, categoria, preço, estoque e última alteração |
| Fig. 22 — Relatórios | `fig22_relatorios_r01.png`, `fig22_relatorios_r05.png` | Escolha do relatório (17), só os filtros dele, prévia na tela com linha de totais, exportar PDF e CSV |

### 5.2 Telas novas (sugestão: figuras adicionais no capítulo de resultados)

| Tela | Captura | Para que serve |
|---|---|---|
| Pagamento | `pdv_pagamento.png` | Várias formas na mesma venda; total, pago, falta e troco |
| Venda finalizada | `pdv_venda_finalizada.png` | Troco em destaque; ver comprovante ou próxima venda |
| Comprovante | `pdv_comprovante.png` | Comprovante não fiscal; imprimir (F5) ou salvar PDF |
| Caixa | `caixa_aberto.png` | Situação do caixa do terminal, sangrias e suprimentos |
| Fechamento às cegas | `caixa_fechamento_dialogo.png` | Só o dinheiro contado é pedido |
| Fechamento de caixa (R11) | `caixa_fechamento_r11.png` | Esperado × contado, vendas por forma, sangrias; exportar PDF e CSV |
| Fornecedores | `fornecedores.png` | Cadastro com CPF/CNPJ (inclusive alfanumérico) |
| Compras | `compras_entrada_confirmada.png` | Entrada de mercadoria com itens, custo e preço atuais |
| Importar NF-e | `compras_nfe_conferencia.png`, `compras_entrada_nfe.png` | Conferência do XML e a entrada gerada |
| Configurações | `configuracoes_loja.png`, `configuracoes_balanca.png`, `configuracoes_auditoria.png` | Dados da loja, parâmetros, balança, formas de pagamento, backup, auditoria, diagnóstico e dados de demonstração |

Outras telas existentes sem captura: Conexão com o banco, Primeiro acesso, Troca de senha, Categorias, Promoções,
Importar planilha, Vendas da sessão (F9), Busca de produto (F2).

### 5.3 Diagramas

| Diagrama do TCC | Arquivo novo | Observação |
|---|---|---|
| DER / MER | `docs/diagramas/der.mmd` | Banco real, 24 tabelas, com tipos, chaves e relacionamentos |
| Diagrama de classes | `docs/diagramas/classes_camadas.mmd`, `classes_model.mmd`, `classes_service.mmd`, `classes_dao.mmd` | Visão das camadas e um diagrama por camada |
| Casos de uso | (sem mudança de atores) | Acrescentar, se quiser, os casos das ampliações: abrir/fechar caixa, sangria, entrada de mercadoria, importar NF-e |

Para importar no draw.io: **Organizar › Inserir › Avançado › Mermaid**, colar o conteúdo do arquivo `.mmd`.

---

## 6. Testes e métricas (capítulo de validação)

- Quadro 23 (testes manuais): `docs/TCC_RESULTADOS.md`, seção 1 — 195 casos com a coluna de resultado para preencher.
- Quadro 24 (métricas): `docs/TCC_RESULTADOS.md`, seção 2 — todas as metas atendidas.
- Testes automáticos: `docs/TCC_RESULTADOS.md`, seção 3.
- Rastreabilidade requisito → telas → testes → casos: `docs/RASTREABILIDADE.md`.
