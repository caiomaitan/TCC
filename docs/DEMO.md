# Roteiro de demonstração para a banca (10 minutos)

Demonstração do VareSync sobre o **banco de demonstração** (60 dias de operação de um mercado fictício, gerados pelo
próprio sistema). O roteiro segue a história do lojista: ver como a loja está, vender, conferir o estoque, contar o
estoque, receber mercadoria e analisar o lucro. Cada passo diz **o que fazer**, **o que dizer** e **os dados exatos**.

As capturas em `docs/telas/` foram tiradas exatamente com estes dados (são as figuras do TCC).

---

## 1. Preparação (no dia, antes da banca — não conta nos 10 minutos)

Faça tudo com calma, uns 15 minutos antes. Precisa do PostgreSQL rodando e do sistema fechado.

1. **Banco de demonstração novo** (README, seção 6). Como `postgres`:

   ```sql
   DROP DATABASE IF EXISTS varesync_demo;
   CREATE DATABASE varesync_demo OWNER varesync ENCODING 'UTF8' TEMPLATE template0;
   ```

   Em `~/.varesync/config.properties`, aponte `db.url=jdbc:postgresql://localhost:5432/varesync_demo`.

2. **Primeiro acesso** (abrir o `VareSync.exe` ou `mvn javafx:run`):

   | Campo | Valor |
   |---|---|
   | Nome da loja | `Mercado Bom Preço` |
   | Seu nome | `Ana Lima` |
   | Login | `ana` |
   | Senha e confirmação | `banca2026` |

3. Entre como `ana`, **Configurações › Dados de demonstração › Gerar dados de demonstração…** › Sim (uns 15 s).
   Resultado esperado: 80 produtos, cerca de 2.040 vendas em 60 dias (terminando ontem), 3 fornecedores, 27 entradas.

4. **Senhas dos usuários de demonstração** (troca obrigatória no primeiro login). Log off e entre com cada um:

   | Login | Nome | Perfil | Senha provisória | Nova senha para a banca |
   |---|---|---|---|---|
   | `maria` | Maria Oliveira | Operadora de caixa (só PDV) | `demo1835` | `maria2026` |
   | `carlos` | Carlos Pereira | Gerente (todas as permissões, não é supervisor) | `demo6999` | `carlos2026` |
   | `jose` | José Santos | Operador de caixa | `demo9076` | (não usado) |

5. **Inventário preparado** (como `ana`). Antes, em **Estoque**, anote o saldo de cada produto abaixo. Depois,
   **Inventário › Novo inventário… (Ctrl+N)**, descrição `Conferência da mercearia e bebidas`, e inclua lendo os
   códigos de barras:

   | Produto | Código de barras | Contado (digitar) |
   |---|---|---|
   | Arroz branco tipo 1 5 kg | `7896543000019` | = saldo |
   | Feijão carioca 1 kg | `7896543000026` | = saldo |
   | Açúcar refinado 1 kg | `7896543000040` | = saldo |
   | **Café torrado e moído 500 g** | `7896543000057` | **saldo − 2** |
   | Óleo de soja 900 ml | `7896543000064` | saldo − 1 |
   | Macarrão espaguete 500 g | `7896543000071` | = saldo |
   | Molho de tomate 340 g | `7896543000118` | **não contar** |
   | Biscoito recheado chocolate 130 g | `7896543000163` | **não contar** |
   | Refrigerante cola PET 2 L | `7896543000224` | saldo + 3 |
   | Cerveja pilsen lata 350 ml | `7896543000279` | saldo − 6 |

   Na captura (`fig18_inventario_divergencias.png`) os saldos eram: café 28 → contado 26, cerveja 75 → 69. **Não
   aplique**: a aplicação é feita na banca, depois da venda (é o exemplo A.8).

6. **Backup** (para o painel não mostrar o alerta "Nenhum backup"): Configurações › Backup › Fazer backup agora.
   Opcional: deixar o alerta e falar dele no passo 2.

7. Copie `docs/demo/nfe_laticinios_vale_do_pardo.xml` para a **Área de Trabalho** (fica fácil de achar na janela
   "Abrir").

8. **Conferir os preços** que serão ditos em voz alta: no PDV, **F7** com `7896543000019` (arroz). Com o banco gerado
   em 22/10/2026 os preços eram os da tabela do item 2.4; o gerador usa semente fixa, mas as datas acompanham o dia em
   que ele roda, então confira se gerar em outro dia. Saia do PDV sem abrir caixa (Esc).

9. Faça **Log off** e deixe a tela de login aberta, com a janela no tamanho em que vai projetar (1280×720 ou maior).

---

## 2. Roteiro (10 minutos)

| Tempo | Tela | Figura |
|---|---|---|
| 0:00 – 0:45 | Login | Fig. 14 |
| 0:45 – 2:00 | Painel e alertas | Fig. 15 |
| 2:00 – 3:00 | Produtos e cadastro (simulador, histórico) | Fig. 21 e Fig. 20 |
| 3:00 – 5:30 | PDV como operadora: venda, pagamento, comprovante | Fig. 19, `pdv_*` |
| 5:30 – 6:15 | Estoque e kardex | Fig. 17 |
| 6:15 – 7:15 | Inventário: divergências e aplicação | Fig. 18 |
| 7:15 – 8:30 | Compras: XML da NF-e e custo médio | `compras_*` |
| 8:30 – 9:30 | Relatórios R01 e R05 | Fig. 22 |
| 9:30 – 10:00 | Fechamento | — |

### 2.1 Login (0:00 – 0:45) — Fig. 14

**Fazer:** Usuário `ana`, senha `banca2026`, Enter.

**Dizer:** "O VareSync é um ERP desktop para o pequeno varejo, em Java com JavaFX e PostgreSQL. Cada pessoa entra com o
próprio login; a senha fica só como hash BCrypt no banco, cinco erros seguidos bloqueiam por cinco minutos e toda
entrada fica na auditoria. O menu mostra só as rotinas que o supervisor liberou para cada usuário."

### 2.2 Painel (0:45 – 2:00) — Fig. 15

**Fazer:** mostrar os cartões, o gráfico e o top 5; clicar no alerta **"2 produtos têm preço de venda abaixo do
custo"**.

**Dizer:** "Ao entrar, o João — a persona do dono de mercado — vê o dia: faturamento, vendas, ticket médio, lucro bruto e
margem, comparados com o mesmo dia da semana passada até a mesma hora. O gráfico mostra os últimos 30 dias e ao lado
os cinco produtos que mais deram lucro no mês. Os dados são de 60 dias de operação gerados pelo próprio sistema, pelos
mesmos services da venda real. Os alertas levam direto ao problema: produtos abaixo do estoque mínimo, preço abaixo do
custo, inventário aberto."

O alerta abre **Produtos** filtrado: **Leite UHT integral 1 L** (preço R$ 4,79, custo ≈ R$ 5,01) e **Fralda descartável
G com 30** (R$ 41,90, custo ≈ R$ 45,18). "Cada venda desses dois dá prejuízo — é o tipo de coisa que a pesquisa do TCC
mostrou que o lojista não consegue ver na planilha."

### 2.3 Produtos e cadastro (2:00 – 3:00) — Fig. 21 e Fig. 20

**Fazer:** Situação **Ativos**; buscar `cafe` (sem acento) e Enter; abrir **Café torrado e moído 500 g** (código 5,
código de barras `7896543000057`) com duplo clique. Mostrar o simulador; digitar `30` em **Margem desejada %** (não
salvar). Aba **Histórico**. Fechar com **Esc**.

**Dizer:** "A busca ignora acento e maiúsculas e aceita código interno ou código de barras. No cadastro, o produto tem
vários códigos de barras, e o simulador mostra lucro, margem e markup enquanto se digita; com a margem desejada ele
sugere o preço, arredondado para cima. O estoque atual é só leitura: estoque só muda por movimento. A aba Histórico
guarda quem mudou preço e custo, e quando."

Valores do café na captura: preço R$ 18,90, custo médio R$ 14,6578, margem 22,45%, markup 28,94%.

### 2.4 PDV (3:00 – 5:30) — Fig. 19, `pdv_pagamento.png`, `pdv_comprovante.png`

**Fazer:**

1. **Log off**; entrar como `maria` / `maria2026`. "A Maria só tem a permissão de PDV: o menu dela é outro."
2. Menu **PDV** → **Abrir caixa**, fundo de troco `150,00`, Enter.
3. No campo **Código**, digitar (ou ler com o leitor) cada linha e Enter:

   | Leitura | Produto | Qtd. | Unitário | Total |
   |---|---|---|---|---|
   | `7896543000019` | Arroz branco tipo 1 5 kg | 1 UN | R$ 29,90 | R$ 29,90 |
   | `2*7896543000279` | Cerveja pilsen lata 350 ml | 2 UN | R$ 4,59 | R$ 9,18 |
   | `7896543000057` | Café torrado e moído 500 g | 1 UN | R$ 18,90 | R$ 18,90 |
   | `2000150006769` | Pão francês (etiqueta de balança) | 0,400 KG | R$ 16,90 | R$ 6,76 |
   | `7896543000316` | Leite UHT integral 1 L | 1 UN | R$ 4,79 | R$ 4,79 |
   | | | | **Total** | **R$ 69,53** |

4. **F10** → forma **PIX**, valor `30,00`, Enter → forma **Dinheiro**, valor `50,00`, Enter. Troco **R$ 10,47**.
   **Finalizar venda (F10)** → **Ver comprovante** → Esc.
5. **Esc** para sair do PDV (sem venda em andamento, sai direto).

**Dizer:** "O PDV é todo de teclado: o leitor de código de barras funciona como teclado mais Enter; `2*` multiplica; a
etiqueta da balança traz o PLU do pão e o valor — o sistema cobra o valor impresso e calcula o peso. As teclas de
função estão na barra de baixo: desconto com limite e liberação do supervisor, cancelamento de item, consulta de preço,
sangria. No pagamento, várias formas; só o dinheiro gera troco. A venda vai para o banco numa única transação: venda,
itens com o custo do momento, pagamentos e a baixa de estoque de cada item. O comprovante é não fiscal — emissão de
NFC-e ficou fora do escopo."

Etiqueta: prefixo `2`, PLU `00015` (pão francês), valor `000676` (R$ 6,76), dígito `9`. Se o preço do pão tiver mudado,
use o código interno: `0,4*54`.

### 2.5 Estoque e kardex (5:30 – 6:15) — Fig. 17

**Fazer:** **Log off**; entrar como `ana`. **Estoque** → buscar `7896543000057` → selecionar o café.

**Dizer:** "Aqui está a rastreabilidade que faltava ao lojista: cada linha do kardex é um movimento — compra, venda,
perda, ajuste, inventário — com o saldo depois dele, o custo, o usuário e a origem. A última linha é a venda que a
Maria acabou de fazer, com o número da venda. O saldo é mantido por um trigger do banco a partir desses movimentos, que
não podem ser alterados nem apagados: se alguém mexer direto no banco, o saldo continua batendo."

### 2.6 Inventário (6:15 – 7:15) — Fig. 18

**Fazer:** **Inventário** → selecionar **Conferência da mercearia e bebidas** → aba **Contagem** (rapidamente) → aba
**Divergências** → **Aplicar…** → Sim. Voltar ao **Estoque** e mostrar o café.

**Dizer:** "A contagem é cega: quem conta não vê o saldo do sistema. Na aba de divergências aparece sobra e falta em
reais e os itens não contados, que são ignorados. O café foi contado com 2 a menos **antes** da venda que acabamos de
fazer. Se o sistema igualasse o saldo ao contado, como dizia o texto original do TCC, a venda posterior sumiria do
estoque. Por isso ele aplica a **diferença** capturada na contagem: o saldo do café fica em contado menos o que foi
vendido depois." (Na captura: sistema 28, contado 26; a venda tirou 1; depois de aplicar, o saldo é 25.)

### 2.7 Compras e XML da NF-e (7:15 – 8:30) — `compras_nfe_conferencia.png`, `compras_entrada_nfe.png`

**Fazer:** **Compras** → **Importar XML da NF-e…** → `nfe_laticinios_vale_do_pardo.xml` (Área de Trabalho).

Mostrar na conferência: emitente **Laticinios Vale do Pardo Ltda (45.723.174/0001-10) — será cadastrado como
fornecedor**; nota nº **7701**, emissão 20/10/2026, **R$ 429,60**, 4 itens, todos já vinculados pelo código de barras:

| Item | Código de barras | Na nota | Quantidade | Valor |
|---|---|---|---|---|
| 1 | `7896543000316` | Leite UHT integral 1L CX C/12 | 48 UN (4 caixas, pela unidade tributável) | R$ 201,60 |
| 2 | `7896543000354` | Iogurte natural 170 g | 24 UN | R$ 44,40 |
| 3 | `7896543000361` | Manteiga com sal 200 g | 12 UN | R$ 115,20 |
| 4 | `7896543000378` | Requeijão cremoso 200 g | 12 UN | R$ 68,40 |

**Gerar entrada** → **Confirmar entrada…** → Sim.

**Dizer:** "O fornecedor manda o XML da nota; o sistema lê o emitente, cadastra o fornecedor se ele não existir e casa
os itens pelo código de barras. A caixa com 12 leites entra como 48 unidades, que é como o produto é vendido. Ao
confirmar, o custo médio ponderado é recalculado e o sistema avisa quais produtos ficaram com margem abaixo da mínima,
com o preço sugerido." O leite entra a R$ 4,20: o custo médio cai de ≈ R$ 5,01 para ≈ R$ 4,59, a margem fica abaixo de
15% e aparece a sugestão de reajuste (≈ R$ 5,40). "Nota repetida do mesmo fornecedor é recusada."

### 2.8 Relatórios R01 e R05 (8:30 – 9:30) — Fig. 22

**Fazer:** **Relatórios** → **R01 — Vendas por período** (período do dia 1º do mês até hoje, já preenchido) → **Gerar**.
Depois **R05 — Rentabilidade por produto** → **Gerar**. Apontar a linha de **Total** dos dois.

**Dizer:** "São os relatórios do caso de uso do TCC. O R01 dá, por dia, número de vendas, receita, descontos, custo da
mercadoria vendida, lucro, margem e ticket médio. O R05 dá o lucro de cada produto com a situação: negativa, baixa ou
ok. A receita e o lucro dos dois batem, porque todos os relatórios e o painel saem da mesma view do banco, com o custo
gravado no momento da venda — mudar o custo hoje não muda o lucro de ontem. A margem do total é a soma dos lucros
sobre a soma das receitas, não a média das margens. Tudo exporta em PDF e CSV, que abre no Excel com acento."

Na captura (01/10 a 22/10/2026, R05): receita R$ 39.032,34, CMV R$ 27.768,54, lucro R$ 11.263,80, margem 28,86%.

### 2.9 Fechamento (9:30 – 10:00)

**Dizer:** "Resumindo: os 13 requisitos do TCC estão atendidos, mais caixa com fechamento às cegas, compras com custo
médio, promoções, balança, importação por planilha e por XML, backup e auditoria. O código tem 297 testes unitários e
206 de integração rodando contra o PostgreSQL, e o roteiro manual tem 195 casos." Se sobrar tempo, mostrar **Usuários**
(Fig. 16: permissões por rotina, Supervisor) ou **Configurações › Auditoria** (a venda, o inventário e a entrada de
hoje aparecem lá).

---

## 3. Se algo der errado

| Problema | O que fazer |
|---|---|
| "Produto não encontrado" na leitura | Conferir o código na tabela; digitar o código interno (arroz `1`, cerveja `27`, café `5`, leite `31`) |
| Preço diferente do roteiro | O total muda; siga com o valor da tela (o troco é calculado) |
| Etiqueta do pão recusada | `0,4*54` (código interno do pão francês) |
| Caixa já aberto ou fechado | **Caixa** mostra a situação; abrir ou seguir no caixa aberto |
| "Esta nota já foi lançada" | A NF-e já foi importada: selecionar a entrada nº 28 em Compras (filtro "Mostrar confirmadas") |
| Sem tempo | Pular 2.3 e 2.7; o essencial é Painel → PDV → Estoque → Inventário → R01/R05 |
| Sistema não abre | Usar as capturas em `docs/telas/` na ordem da tabela do item 2 |

## 4. Dados de referência

- Loja: **Mercado Bom Preço**; terminal `CAIXA-01`; supervisor **Ana Lima** (`ana` / `banca2026`).
- Balança ativa: prefixo `2`, 5 dígitos de PLU, valor em preço. PLUs: banana 1, tomate 2, batata 3, cebola 4, maçã 5,
  laranja 6, mussarela 7, presunto 8, mortadela 9, alcatra 10, carne moída 11, frango 12, linguiça 13, costela 14, pão
  francês 15.
- Códigos de barras de demonstração: `7896543` + sequência de 5 dígitos + dígito verificador (produtos de unidade, na
  ordem do cadastro). Os pesáveis não têm código de barras.
- Fornecedores gerados: Distribuidora Ribeirão Alimentos (CNPJ `11.222.333/0001-81`), Bebidas e Frios Sertãozinho (CNPJ
  alfanumérico `12.ABC.345/01DE-35`), Atacado Limpa Tudo (CNPJ alfanumérico `AB.12C.D34/EFGH-83`).
- Produtos abaixo do mínimo: Queijo parmesão ralado 50 g, Saco de lixo 50 L com 10, Vela branca pacote com 8.
- Produtos sem venda (R12): Fondue de chocolate, Espumante brut, Panetone, Pá de lixo, Guarda-chuva dobrável.
