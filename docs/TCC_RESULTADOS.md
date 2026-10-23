# Resultados para o TCC

Material para o capítulo de validação: a tabela consolidada dos testes manuais (Quadro 23), as métricas da seção 9.4
da especificação (Quadro 24) e o resumo dos testes automáticos. Números conferidos em 2026-10-22 com `mvn -q verify`
(Java 21.0.12, PostgreSQL 16, Windows 11).

---

## 1. Testes manuais (Quadro 23)

Todos os 195 casos de `docs/ROTEIRO_TESTES.md`, na mesma ordem (o roteiro tem a pré-condição, os passos e o resultado
esperado de cada um). Colunas:

- **Req.**: requisito do TCC (RF/RNF) ou ampliação (Caixa, Compras, Extras), como em `docs/RASTREABILIDADE.md`.
- **Registro no desenvolvimento**: o que ficou anotado no roteiro durante as fases. "OK (conferido pela tela…)" =
  executado por um roteiro automatizado que acionou os componentes das telas reais (sem teclado nem mouse do sistema);
  "Coberto por teste automático" = a regra foi conferida por teste de integração, não pela tela; "—" = ainda não
  executado.
- **Resultado obtido** e **Data**: para você preencher ao executar o caso (OK / Falhou e o que aconteceu).

Situação do registro no desenvolvimento: 75 OK (69 pela tela e 6 por você), 28 cobertos por teste automático, 20
parciais, 4 não executados e 68 ainda sem execução. Os casos que dependem de teclado de verdade, leitor de código de
barras, balança, impressora térmica, janelas "Abrir"/"Salvar como" do Windows, Excel e leitor de PDF só podem ser
fechados pela execução manual.

| Nº | ID | Req. | Caso | Registro no desenvolvimento | Resultado obtido | Data |
|---|---|---|---|---|---|---|
| 1 | CT-F0-01 | RNF01 | Build e testes automáticos passam (mvn verify) | OK | | |
| 2 | CT-F0-02 | RNF08 | Primeira execução cria a configuração local e pede a conexão | OK | | |
| 3 | CT-F0-03 | RNF08 | Tela de conexão: testar senha errada e certa, salvar e migrar | OK | | |
| 4 | CT-F0-04 | RNF05 | Abertura direta com a configuração salva | OK | | |
| 5 | CT-F0-05 | RNF01 | Script de integridade do banco (15 cenários) | — | | |
| 6 | CT-F0-06 | RNF04 | Log sem senha (início, configuração, migrations, encerramento) | OK | | |
| 7 | CT-F0-07 | RNF08 | PostgreSQL parado: tela de conexão com o motivo e recuperação | — | | |
| 8 | CT-F0-08 | RNF02 | Esc na tela de conexão fecha o sistema | — | | |
| 9 | CT-F1-01 | RF12 | Primeiro acesso cria a loja e o supervisor | — | | |
| 10 | CT-F1-02 | RF12 | Login com senha errada, usuário inexistente e login com espaços/maiúsculas | — | | |
| 11 | CT-F1-03 | RNF05 | Tempo até a tela de login (< 5 s) | — | | |
| 12 | CT-F1-04 | RNF02 | Login só com o teclado; Esc fecha | — | | |
| 13 | CT-F1-05 | RF12 | Bloqueio por 5 minutos após 5 senhas erradas | — | | |
| 14 | CT-F1-06 | RF13 | Criar usuário com permissões; login repetido recusado | — | | |
| 15 | CT-F1-07 | RF12 | Troca obrigatória da senha provisória | — | | |
| 16 | CT-F1-08 | RF13 | Supervisor não remove o próprio acesso | — | | |
| 17 | CT-F1-09 | RF13 | Desbloquear e redefinir senha | — | | |
| 18 | CT-F1-10 | RF13 | Inativar e reativar usuário | — | | |
| 19 | CT-F1-11 | RNF04 | Auditoria de login, falhas, logout e usuários | — | | |
| 20 | CT-F1-12 | RF12 | Alterar a própria senha; confirmação de Sair | — | | |
| 21 | CT-F2-01 | RF01 | Cadastrar produto com código de barras e estoque inicial | — | | |
| 22 | CT-F2-02 | RF01 | Código de barras com dígito verificador inválido recusado | — | | |
| 23 | CT-F2-03 | RF01 | Código de barras repetido informa o produto dono | — | | |
| 24 | CT-F2-04 | RF11 | Simulador: lucro, margem, markup e preço sugerido | — | | |
| 25 | CT-F2-05 | RF01 | Preço abaixo do custo e nome repetido pedem confirmação | — | | |
| 26 | CT-F2-06 | RF01 | Validações do cadastro (nome, quantidade em UN e KG, casas) | — | | |
| 27 | CT-F2-07 | RF02 | Alteração de preço registrada no histórico com o usuário | — | | |
| 28 | CT-F2-08 | RF02 | Produto com movimento não exclui e oferece inativar | — | | |
| 29 | CT-F2-09 | RF02 | Excluir produto sem vínculos | — | | |
| 30 | CT-F2-10 | RF02 | Filtro de inativos e reativar produto | — | | |
| 31 | CT-F2-11 | RF03 | Busca sem acento, por código interno e por código de barras | — | | |
| 32 | CT-F2-12 | RF03 | Abrir produto pela lista (duplo clique, Enter, Ctrl+N) | — | | |
| 33 | CT-F2-13 | RNF04 | Usuário sem a permissão Produtos não vê o menu | — | | |
| 34 | CT-F2-14 | RF01 | Categorias: nome único, excluir só sem uso, inativar | — | | |
| 35 | CT-F2-15 | RNF01 | Migration V3 e horário de alteração pela aplicação | — | | |
| 36 | CT-F3-01 | RF09 | Consulta de estoque por nome, código de barras e código | — | | |
| 37 | CT-F3-02 | RF09 | Cabeçalho (saldo, mínimo, custo médio, valor) e kardex | — | | |
| 38 | CT-F3-03 | RF09 | Ajustar saldo para… gera a saída da diferença | — | | |
| 39 | CT-F3-04 | RF09 | Ajuste de saída com motivo e observação no kardex | — | | |
| 40 | CT-F3-05 | RF09 | Ajuste sem motivo ou com quantidade inválida recusado | — | | |
| 41 | CT-F3-06 | RF09 | Quantidade fracionada só em unidade fracionada | — | | |
| 42 | CT-F3-07 | RF09 | Perda com motivo da lista | — | | |
| 43 | CT-F3-08 | RF09 | Filtro "Só abaixo do mínimo" e destaque | — | | |
| 44 | CT-F3-09 | RF09 | Alerta de estoque mínimo no Painel | — | | |
| 45 | CT-F3-10 | RNF04 | Permissão Estoque (menu e ajuste) | — | | |
| 46 | CT-F3-11 | RNF01 | Diagnóstico do estoque e trigger contra escrita direta no saldo | — | | |
| 47 | CT-F3-12 | RF09 | Filtro de período do kardex, teclado e produto inativo | — | | |
| 48 | CT-F4-01 | Caixa | Abrir caixa com fundo de troco | — | | |
| 49 | CT-F4-02 | Caixa | Validação do fundo de troco | — | | |
| 50 | CT-F4-03 | Caixa | Outro usuário continua no caixa aberto do terminal | — | | |
| 51 | CT-F4-04 | Caixa | Suprimento e sangria (limite do dinheiro esperado, motivo) | — | | |
| 52 | CT-F4-05 | RNF04 | Usuário sem PDV não vê PDV nem Caixa | — | | |
| 53 | CT-F4-06 | Caixa | Sangria com liberação de supervisor | — | | |
| 54 | CT-F4-07 | Caixa | Fechamento às cegas e relatório R11 | — | | |
| 55 | CT-F4-08 | Caixa | Ver último fechamento e reabrir caixa | — | | |
| 56 | CT-F4-09 | Caixa | Sair avisa quando há caixa aberto | — | | |
| 57 | CT-F4-10 | RNF02 | Diálogos do caixa só com o teclado | — | | |
| 58 | CT-F4-11 | RF04 | PDV pede a abertura do caixa antes de vender | OK (conferido pela tela, sem teclado do sistema) | | |
| 59 | CT-F4-12 | RF05 | Produto não encontrado: alerta visual e sonoro | OK (conferido pela tela, sem teclado do sistema) | | |
| 60 | CT-F4-13 | RF05 | Leitura com multiplicador e produto em KG | OK (conferido pela tela, sem teclado do sistema) | | |
| 61 | CT-F4-14 | RF05 | Quantidade pedida para produto fracionado | — | | |
| 62 | CT-F4-15 | RF05 | Busca F2, quantidade F3 e consulta de preço F7 | — | | |
| 63 | CT-F4-16 | RF04 | Desconto na venda com rateio e no item acima do limite | Parcial (ver o roteiro) | | |
| 64 | CT-F4-17 | RF04 | Pagamento PIX + dinheiro com troco, comprovante e PDF | Parcial (ver o roteiro) | | |
| 65 | CT-F4-18 | RF04 | Só dinheiro passa do valor que falta | — | | |
| 66 | CT-F4-19 | RF06 | Cancelar venda com liberação devolve o estoque | OK (conferido pela tela, sem teclado do sistema) | | |
| 67 | CT-F4-20 | RF04 | Cancelar item e abandonar venda (auditoria) | OK (conferido pela tela, sem teclado do sistema) | | |
| 68 | CT-F4-21 | RNF05 | Venda de 3 itens só com teclado e leitor | — | | |
| 69 | CT-F4-22 | Caixa | Sangria pelo PDV e fechamento (F12) com R11 | Parcial (ver o roteiro) | | |
| 70 | CT-F5-01 | RF07 | Criar inventário | Parcial (ver o roteiro) | | |
| 71 | CT-F5-02 | RF07 | Incluir produtos por código e busca | Parcial (ver o roteiro) | | |
| 72 | CT-F5-03 | RF07 | Incluir por categoria e todos os ativos; remover item | — | | |
| 73 | CT-F5-04 | RF07 | Contagem cega com leitor | OK (conferido pela tela, sem teclado do sistema) | | |
| 74 | CT-F5-05 | RF07 | Contagem grava saldo, custo, quem e quando | — | | |
| 75 | CT-F5-06 | RF08 | Divergências depois de uma venda posterior à contagem | Parcial (ver o roteiro) | | |
| 76 | CT-F5-07 | RF08 | Aplicar inventário (movimentos pela diferença) | Parcial (ver o roteiro) | | |
| 77 | CT-F5-08 | RF08 | Inventário aplicado fica somente leitura | OK (conferido pela tela, sem teclado do sistema) | | |
| 78 | CT-F5-09 | RF07 | Produto em um inventário aberto só; cancelar inventário | Parcial (ver o roteiro) | | |
| 79 | CT-F5-10 | RF08 | Aplicar sem contagem recusado | — | | |
| 80 | CT-F5-11 | RNF04 | Permissão Inventário | — | | |
| 81 | CT-F5-12 | RNF02 | Inventário só com o teclado | — | | |
| 82 | CT-F6-01 | RF10 | Gerar os dados de demonstração | OK (conferido pela tela, sem teclado do sistema) | | |
| 83 | CT-F6-02 | RF10 | Segunda geração recusada | OK (conferido pela tela, sem teclado do sistema) | | |
| 84 | CT-F6-03 | RF12 | Usuário de demonstração troca a senha e vê o menu dele | — | | |
| 85 | CT-F6-04 | RF10 | R01 Vendas por período (por dia, por venda, por operador) | OK (conferido pela tela, sem teclado do sistema) | | |
| 86 | CT-F6-05 | RF11 | R05 Rentabilidade bate com o R01 | OK (conferido pela tela, sem teclado do sistema) | | |
| 87 | CT-F6-06 | RF10 | R02, R03 e R04 com filtros | OK (conferido pela tela, sem teclado do sistema) | | |
| 88 | CT-F6-07 | RF10 | Exportar CSV (abre no Excel com acentos) | OK (conferido pela tela, sem teclado do sistema) | | |
| 89 | CT-F6-08 | RF10 | Exportar PDF (cabeçalho, filtros, página x de y) | OK (conferido pela tela, sem teclado do sistema) | | |
| 90 | CT-F6-09 | RF10 | Período inválido, produto inexistente e botões durante a geração | OK (conferido pela tela, sem teclado do sistema) | | |
| 91 | CT-F6-10 | RNF04 | Operador sem Relatórios não vê o menu | — | | |
| 92 | CT-F6-11 | RNF01 | Alerta de mínimo e diagnóstico com os dados de demonstração | — | | |
| 93 | CT-F6-12 | RNF02 | Relatórios só com o teclado | — | | |
| 94 | CT-F6-13 | RF11 | Painel: cartões do dia comparados com a semana anterior | OK (conferido pela tela, sem teclado do sistema) | | |
| 95 | CT-F6-14 | RF10 | Painel: alertas | OK (conferido pela tela, sem teclado do sistema) | | |
| 96 | CT-F6-15 | RF10 | Painel: alertas levam à tela filtrada | OK (conferido pela tela, sem teclado do sistema) | | |
| 97 | CT-F6-16 | RF10 | Painel: atalhos e atualizar (F5) | OK (conferido pela tela, sem teclado do sistema) | | |
| 98 | CT-F6-17 | RNF04 | Painel sem Relatórios: só alertas e atalhos | OK (conferido pela tela, sem teclado do sistema) | | |
| 99 | CT-F6-18 | RF11 | R06 Rentabilidade por categoria bate com o R05 | OK (conferido pela tela, sem teclado do sistema) | | |
| 100 | CT-F6-19 | RF10 | R07 Curva ABC por receita e por lucro | OK (conferido pela tela, sem teclado do sistema) | | |
| 101 | CT-F6-20 | RF10 | R08 Formas de pagamento bate com o R01 | OK (conferido pela tela, sem teclado do sistema) | | |
| 102 | CT-F6-21 | RF10 | R09 Vendas por operador | OK (conferido pela tela, sem teclado do sistema) | | |
| 103 | CT-F6-22 | RF10 | R10 Vendas por hora e dia da semana | OK (conferido pela tela, sem teclado do sistema) | | |
| 104 | CT-F6-23 | RF10 | R12 Produtos sem giro | OK (conferido pela tela, sem teclado do sistema) | | |
| 105 | CT-F6-24 | RF10 | R14 Perdas e ajustes | OK (conferido pela tela, sem teclado do sistema) | | |
| 106 | CT-F6-25 | RF10 | R15 Alterações de preço e R16 Cancelamentos | OK (conferido pela tela, sem teclado do sistema) | | |
| 107 | CT-F6-26 | RF10 | Exportação CSV e PDF dos relatórios R06 a R16 | OK (conferido pela tela, sem teclado do sistema) | | |
| 108 | CT-F6-27 | RF08 | Exportação do R13 (resultado do inventário) | OK (conferido pela tela, sem teclado do sistema) | | |
| 109 | CT-F6-28 | Caixa | Exportação do R11 (fechamento de caixa) | OK (conferido pela tela, sem teclado do sistema) | | |
| 110 | CT-F7-01 | Compras | Cadastrar fornecedor com CNPJ alfanumérico | OK (conferido pela tela, sem teclado do sistema) | | |
| 111 | CT-F7-02 | Compras | CNPJ com dígito errado recusado | OK (conferido pela tela, sem teclado do sistema) | | |
| 112 | CT-F7-03 | Compras | CNPJ repetido informa o fornecedor dono | OK (conferido pela tela, sem teclado do sistema) | | |
| 113 | CT-F7-04 | Compras | Fornecedor com CPF | Coberto por teste automático | | |
| 114 | CT-F7-05 | Compras | Excluir fornecedor com entradas oferece inativar | Coberto por teste automático | | |
| 115 | CT-F7-06 | Compras | Busca de fornecedores | Coberto por teste automático | | |
| 116 | CT-F7-07 | Compras | Criar entrada de mercadoria | Parcial (ver o roteiro) | | |
| 117 | CT-F7-08 | Compras | Incluir item por código de barras e custo unitário | OK (conferido pela tela, sem teclado do sistema) | | |
| 118 | CT-F7-09 | Compras | Mesmo produto duas vezes na entrada recusado | OK (conferido pela tela, sem teclado do sistema) | | |
| 119 | CT-F7-10 | Compras | Item pelo valor total da linha | OK (conferido pela tela, sem teclado do sistema) | | |
| 120 | CT-F7-11 | Compras | Confirmar entrada: custo médio e sugestão de reajuste | OK (conferido pela tela, sem teclado do sistema) | | |
| 121 | CT-F7-12 | Compras | Nota repetida do mesmo fornecedor recusada | OK (conferido pela tela, sem teclado do sistema) | | |
| 122 | CT-F7-13 | Compras | Kardex com "Entrada nº X" e histórico de custo | Coberto por teste automático | | |
| 123 | CT-F7-14 | Compras | Entrada confirmada não pode ser alterada | Parcial (ver o roteiro) | | |
| 124 | CT-F7-15 | RF10 | R17 Compras por fornecedor | OK (conferido pela tela, sem teclado do sistema) | | |
| 125 | CT-F7-16 | RF10 | Coluna Código sem separador de milhar | Parcial (ver o roteiro) | | |
| 126 | CT-F7-17 | Compras | Gerador cria fornecedores e entradas semanais | Coberto por teste automático | | |
| 127 | CT-F7-18 | RNF04 | Permissões Fornecedores e Compras | Coberto por teste automático | | |
| 128 | CT-F8-01 | Extras | Abas de Configurações | OK (conferido pela tela, sem teclado do sistema) | | |
| 129 | CT-F8-02 | Extras | Salvar configurações com auditoria | OK (conferido pela tela, sem teclado do sistema) | | |
| 130 | CT-F8-03 | Extras | Parâmetros de venda valem no PDV | Coberto por teste automático | | |
| 131 | CT-F8-04 | Extras | Valor inválido e Desfazer | OK (conferido pela tela, sem teclado do sistema) | | |
| 132 | CT-F8-05 | Extras | Limites das configurações | Coberto por teste automático | | |
| 133 | CT-F8-06 | Extras | Exemplo da etiqueta de balança | OK (conferido pela tela, sem teclado do sistema) | | |
| 134 | CT-F8-07 | Extras | Dígitos do PLU menores que um PLU cadastrado recusados | Coberto por teste automático | | |
| 135 | CT-F8-08 | Extras | Formas de pagamento: criar e desativar | OK (conferido pela tela, sem teclado do sistema) | | |
| 136 | CT-F8-09 | Extras | Ao menos uma forma de pagamento ativa | Coberto por teste automático | | |
| 137 | CT-F8-10 | RNF04 | Configurações só para o supervisor | Coberto por teste automático | | |
| 138 | CT-F8-11 | Extras | Criar promoção | OK (conferido pela tela, sem teclado do sistema) | | |
| 139 | CT-F8-12 | Extras | Promoção sobreposta recusada | OK (conferido pela tela, sem teclado do sistema) | | |
| 140 | CT-F8-13 | Extras | Promoção futura | OK (conferido pela tela, sem teclado do sistema) | | |
| 141 | CT-F8-14 | Extras | Preço promocional no PDV e no F7 | Parcial (ver o roteiro) | | |
| 142 | CT-F8-15 | Extras | Promoção desativada não vale | Coberto por teste automático | | |
| 143 | CT-F8-16 | Extras | Excluir promoção usada recusado | Coberto por teste automático | | |
| 144 | CT-F8-17 | Extras | Filtros de promoções | Parcial (ver o roteiro) | | |
| 145 | CT-F8-18 | Extras | Etiqueta de balança por preço (A.7) | OK (conferido pela tela, sem teclado do sistema) | | |
| 146 | CT-F8-19 | Extras | Etiqueta de balança por peso (A.7) | Coberto por teste automático | | |
| 147 | CT-F8-20 | Extras | Etiqueta com PLU de 4 dígitos (A.7) | Coberto por teste automático | | |
| 148 | CT-F8-21 | Extras | Valor da etiqueta sem recálculo (A.7) | Coberto por teste automático | | |
| 149 | CT-F8-22 | Extras | PLU sem produto | OK (conferido pela tela, sem teclado do sistema) | | |
| 150 | CT-F8-23 | Extras | Etiqueta com multiplicador recusada | OK (conferido pela tela, sem teclado do sistema) | | |
| 151 | CT-F8-24 | Extras | Etiqueta com a balança desligada | Coberto por teste automático | | |
| 152 | CT-F8-25 | Extras | Venda completa com etiqueta | Coberto por teste automático | | |
| 153 | CT-F8-26 | Extras | Baixar o modelo de planilha | Parcial (ver o roteiro) | | |
| 154 | CT-F8-27 | Extras | Prévia da planilha com erros por linha e campo | OK (conferido pela tela, sem teclado do sistema) | | |
| 155 | CT-F8-28 | Extras | Importar ignorando as linhas com erro | OK (conferido pela tela, sem teclado do sistema) | | |
| 156 | CT-F8-29 | Extras | Importar planilha sem erros com categoria nova | Coberto por teste automático | | |
| 157 | CT-F8-30 | Extras | Planilha do Excel em Windows-1252 | Coberto por teste automático | | |
| 158 | CT-F8-31 | Extras | Erros de código de barras, unidade e categoria na planilha | Coberto por teste automático | | |
| 159 | CT-F8-32 | Extras | Arrastar a planilha para a lista | Não executado | | |
| 160 | CT-F8-33 | RF09 | Busca do Estoque com % e _ como texto | OK (conferido pela tela, sem teclado do sistema) | | |
| 161 | CT-F8-34 | Extras | Alerta de backup no Painel | OK (conferido pela tela, sem teclado do sistema) | | |
| 162 | CT-F8-35 | Extras | Backup automático ao sair | OK (conferido pela tela, sem teclado do sistema) | | |
| 163 | CT-F8-36 | Extras | No máximo um backup automático por dia | Coberto por teste automático | | |
| 164 | CT-F8-37 | Extras | Fazer backup agora exige salvar as alterações | OK (conferido pela tela, sem teclado do sistema) | | |
| 165 | CT-F8-38 | Extras | Backup mantém só os últimos N | OK (conferido pela tela, sem teclado do sistema) | | |
| 166 | CT-F8-39 | Extras | Alerta de backup com mais de 7 dias | Parcial (ver o roteiro) | | |
| 167 | CT-F8-40 | Extras | Validação das configurações de backup | Coberto por teste automático | | |
| 168 | CT-F8-41 | Extras | Backup sem pg_dump ou com versão antiga | Coberto por teste automático | | |
| 169 | CT-F8-42 | Extras | Restaurar backup num banco novo | Não executado | | |
| 170 | CT-F8-43 | RNF04 | Backup manual só para o supervisor | Coberto por teste automático | | |
| 171 | CT-F8-44 | RNF04 | Consulta de auditoria | OK (conferido pela tela, sem teclado do sistema) | | |
| 172 | CT-F8-45 | RNF04 | Auditoria filtrada por ação, com a descrição inteira | OK (conferido pela tela, sem teclado do sistema) | | |
| 173 | CT-F8-46 | RNF04 | Auditoria filtrada por usuário | Coberto por teste automático | | |
| 174 | CT-F8-47 | RNF02 | Data impossível recusada | OK (conferido pela tela, sem teclado do sistema) | | |
| 175 | CT-F8-48 | RNF05 | Auditoria limitada a 5.000 linhas | Coberto por teste automático | | |
| 176 | CT-F8-49 | Extras | Imprimir o comprovante | Parcial (ver o roteiro) | | |
| 177 | CT-F8-50 | Extras | Imprimir sem impressora padrão | OK (conferido pela tela, sem teclado do sistema) | | |
| 178 | CT-F8-51 | Extras | Reimprimir pelo F9 | Parcial (ver o roteiro) | | |
| 179 | CT-F8-52 | Extras | Importar XML de NF-e: conferência | OK (conferido pela tela, sem teclado do sistema) | | |
| 180 | CT-F8-53 | Extras | Vincular item sem código de barras | OK (conferido pela tela, sem teclado do sistema) | | |
| 181 | CT-F8-54 | Extras | Gerar a entrada pela NF-e | OK (conferido pela tela, sem teclado do sistema) | | |
| 182 | CT-F8-55 | Extras | Mesma NF-e recusada | OK (conferido pela tela, sem teclado do sistema) | | |
| 183 | CT-F8-56 | Extras | Arquivo que não é NF-e recusado | OK (conferido pela tela, sem teclado do sistema) | | |
| 184 | CT-F8-57 | Extras | Cadastrar produto a partir do item da NF-e | Não executado | | |
| 185 | CT-F8-58 | Extras | Confirmar a entrada gerada pela NF-e | Coberto por teste automático | | |
| 186 | CT-F9-01 | RNF07 | Gerar o pacote Windows (jpackage) | OK | | |
| 187 | CT-F9-02 | RNF07 | Instalação do zero pelo pacote | OK (conferido pela tela, sem teclado do sistema) | | |
| 188 | CT-F9-03 | RNF07 | Pacote num computador sem JDK | Parcial (ver o roteiro) | | |
| 189 | CT-F9-04 | RNF05 | Tempo de abertura pelo VareSync.exe | OK (conferido pela tela, sem teclado do sistema) | | |
| 190 | CT-F9-05 | RNF02 | Mensagem padrão para número inválido | — | | |
| 191 | CT-F9-06 | RNF05 | Venda de 3 itens só com o teclado (métrica) | Não executado | | |
| 192 | CT-F9-07 | RF12 | Senhas dos usuários de demonstração do README | Parcial (ver o roteiro) | | |
| 193 | CT-F9-08 | Extras | Restauração do backup pelo README | — | | |
| 194 | CT-F9-09 | RNF07 | Ícone próprio na janela e no VareSync.exe | Parcial (ver o roteiro) | | |
| 195 | CT-F9-10 | RNF02 | Ensaio do roteiro de demonstração (DEMO.md) | — | | |

### 1.1 Casos por requisito

| Requisito | Casos | Requisito | Casos |
|---|---|---|---|
| RF01 Cadastrar produto | 6 | RF10 Relatórios | 20 |
| RF02 Editar e excluir produto | 4 | RF11 Lucro e margem | 4 |
| RF03 Consultar produtos | 2 | RF12 Login e senha | 7 |
| RF04 Registrar vendas | 5 | RF13 Rotinas por usuário | 4 |
| RF05 Selecionar produtos na venda | 4 | RNF01 Integridade | 5 |
| RF06 Baixa de estoque na venda | 1 | RNF02/03 Interface | 8 |
| RF07 Criar inventário | 6 | RNF04 Acesso restrito | 14 |
| RF08 Aplicar divergências | 5 | RNF05 Tempo de resposta | 6 |
| RF09 Visualizar estoque | 11 | RNF07 Desktop / RNF08 Banco | 4 / 3 |
| Ampliação: Caixa | 10 | Ampliação: Compras | 15 |
| Ampliação: Extras (configurações, promoções, balança, planilha, backup, auditoria, impressão, NF-e) | 51 | **Total** | **195** |

Os casos de uso do TCC (Quadros 11 a 20) correspondem a RF01–RF13; a ligação caso a caso com classes e testes
automáticos está em `docs/RASTREABILIDADE.md`.

---

## 2. Métricas (Quadro 24 — seção 9.4 da especificação)

| Métrica | Meta | Medido | Como foi medido |
|---|---|---|---|
| Registrar um item no PDV com 5 mil produtos | < 300 ms | **5 ms** (pior de 20 leituras) | `VendaServiceIT.registrarUmItemComCincoMilProdutosLevaMenosDe300ms`, banco local com 5.002 produtos: leitura do código de barras + inclusão com a regra de estoque, sem o desenho da tela. Medições anteriores: 1 ms (Fase 4), 0 ms (Fase 9, parte 1) |
| Relatório de 60 dias com os dados de demonstração | < 3 s | **R01 32 ms · R05 47 ms**; o mais lento, R04 com todos os produtos (7.979 linhas), 73 ms | `GeradorDemoIT.relatoriosDeSessentaDiasEmMenosDeTresSegundos`, consulta no service, 60 dias (cerca de 2.000 vendas). Demais: R02 39 ms, R03 3 ms, R06 19 ms, R07 15 ms, R08 9 ms, R09 24 ms, R10 19 ms, R12 12 ms, R14 5 ms, R15 1 ms, R16 16 ms, R17 2 ms. Na tela: "gerado em 0,0 s" (captura `fig22_relatorios_r05.png`) |
| Painel | < 2 s | **13 ms** no service; **0,1 s** na tela | `GeradorDemoIT.painelEmMenosDeDoisSegundosEBateComOR01`; na tela, "Atualizado às 22:02 (0,1 s)" do início da tarefa ao desenho dos cartões, do gráfico e dos alertas (captura `fig15_principal_painel.png`) |
| Abertura do sistema até a tela de login | < 5 s | **1,73 s a 1,95 s** | `VareSync.exe` (Java embutido), banco já migrado, 3 aberturas (Fase 9, parte 1): log "Primeira tela aberta em N ms" desde o início da JVM; 1,8 s a 2,1 s pelo cronômetro externo |
| Venda de 3 itens paga em dinheiro só com teclado | sim | **Sim** | Fase 4: leitura, F10, forma, valor e Enter, sem nenhum clique. Refazer com o leitor no CT-F4-21 / CT-F9-06 |
| Defeitos encontrados por fase | — | **33 no total**, todos corrigidos | Tabela abaixo |

### 2.1 Defeitos por fase

Contados no código de produção (não entram erros nos próprios testes nem de ferramenta). Detalhe em
`docs/PROGRESSO.md`, seção Métricas.

| Fase | Defeitos | Como foram achados |
|---|---|---|
| 0 — Fundação | 1 | Ao rodar o sistema (FXML sem controller) |
| 1 — Acesso e usuários | 0 | — |
| 2 — Produtos | 1 | Revisão (estoque mínimo com separador de milhar) |
| 3 — Estoque | 2 | Capturas de tela (visuais) |
| 4 — Caixa (parte 1) | 3 | Revisão (2 textos) e captura (1 visual) |
| 4 — PDV (parte 2) | 3 | PDF do comprovante (2 de layout) e captura (1 visual) |
| 5 — Inventário | 4 | Roteiro de tela (lista, campo, rodapé, texto cortado) |
| 6 — Relatórios (parte 1) | 5 | Teste do PDF (2) e capturas (3) |
| 6 — Painel e relatórios (parte 2) | 6 | Roteiro de tela (1 funcional) e capturas (5 visuais) |
| 7 — Fornecedores e compras | 3 | Revisão (1 texto) e capturas (2 visuais) |
| 8 — Extras (parte 1) | 3 | Capturas (visuais) |
| 8 — Extras (parte 2) | 1 | Roteiro de tela (data impossível aceita) |
| 9 — Qualidade (parte 1) | 1 | Roteiro de instalação (barra de status fora da janela) |
| 9 — Material do TCC (parte 2) | 0 | 26 capturas das telas com os dados de demonstração |
| **Total** | **33** | Nenhum defeito nas regras de negócio depois que os testes de integração passaram |

---

## 3. Testes automáticos

`mvn -q verify` em 2026-10-22: **503 testes, todos verdes, nenhum pulado** — **297 unitários** (41 classes, sem
banco, JUnit 6 + AssertJ, Surefire) e **206 de integração** (25 classes, no PostgreSQL `varesync_test`, Failsafe). Os
testes de integração limpam e migram o banco antes da suíte e, ao fim de cada teste, conferem que
`vw_estoque_inconsistente` está vazia (o saldo bate com a soma dos movimentos).

### 3.1 Unitários (297)

| Área | Classes (testes) | O que cobrem |
|---|---|---|
| Números e formatos | `DinheiroTest` (37), `FormatosTest` (7), `TextosTest` (14) | Arredondamento HALF_UP e escalas; leitura de valores pt-BR (`1.234,56`, `1234.56`); moeda, quantidade e datas; busca sem acento |
| Validadores (Apêndice A) | `GtinTest` (11), `CpfCnpjTest` (6) | GTIN-8/12/13/14 (A.1); CPF (A.2); CNPJ numérico e alfanumérico (A.3) |
| Cálculos de negócio (Apêndice A) | `RateioTest` (8), `CustoMedioTest` (4), `PrecificacaoTest` (5), `CarrinhoTest` (12), `RecebimentoTest` (6), `EtiquetaBalancaTest` (8), `EntradaPdvTest` (4), `ResultadoInventarioTest` (5), `PainelCalculoTest` (5) | Rateio pelo maior resto (A.4); custo médio (A.5); margem, markup e preço sugerido (A.6); totais, limite de desconto e troco do carrinho; etiqueta de balança (A.7); `3*789…`; sobra e falta do inventário; variação do painel |
| Regras e mensagens | `SenhasTest` (7), `SessaoTest` (4), `ValidacaoProdutoTest` (7), `EstoqueMotivoTest` (4), `CaixaValorTest` (3), `TradutorErrosTest` (12), `ConfiguracoesTest` (4) | BCrypt custo 12; permissões da sessão; validações do cadastro; motivo de ajuste e perda; valores do caixa; tradução dos erros do banco (único, chave estrangeira, check, trigger); configurações com padrão |
| Modelos | `ComprovanteTest` (3), `RelatorioFechamentoTest` (4), `EntradaResumoTest` (2), `EstoqueModeloTest` (3) | Texto do comprovante, R11, entrada, saldo |
| Relatórios e exportação | `RelatoriosTest` (11), `RelatoriosParte2Test` (18), `ExportadorCsvTest` (3), `RelatorioPdfTest` (6), `JrxmlTest` (2), `FechamentoCaixaExportacaoTest` (5), `ImpressaoComprovanteTest` (2) | Colunas e totais (margem agregada, curva ABC A.9); CSV com BOM e `;`; compilação de todos os JRXML e PDF com dados fictícios; impressão |
| Importações e backup | `LeitorNfeTest` (6), `PlanilhaProdutosTest` (4), `BackupServiceTest` (6) | XML de NF-e (sem DTD, `qTrib`); planilha UTF-8/Windows-1252; localização do `pg_dump`, nome e retenção dos arquivos |
| Interface e configuração | `FxmlTest` (24), `ItemMenuTest` (9), `ConversorDataTest` (2), `IconeTest` (7), `AppConfigTest` (4), `BancoTest` (3) | Cada FXML declara o controller e todo `fx:id`/ação existe; menu por permissão; data estrita; arquivos do ícone; configuração local |

### 3.2 Integração (206, PostgreSQL)

| Área | Classes (testes) | O que cobrem |
|---|---|---|
| Banco e infraestrutura | `IntegridadeBancoIT` (1), `MigracaoIT` (2), `TransacaoIT` (7), `PermissaoIT` (1) | Os 15 cenários do script de integridade; as 3 migrations; commit, rollback e tradução de erros; permissões da V2 iguais ao enum |
| Acesso e usuários | `AcessoServiceIT` (13), `UsuarioServiceIT` (10) | Primeiro acesso, login, bloqueio de 5 min, troca de senha, liberação de supervisor, auditoria; permissões, último supervisor, inativar a si mesmo |
| Produtos e estoque | `ProdutoServiceIT` (19), `CategoriaServiceIT` (4), `EstoqueServiceIT` (14), `PromocaoServiceIT` (6) | Estoque inicial, código repetido, histórico, excluir × inativar, busca sem acento, permissão negada; kardex, ajustes, "ajustar saldo para", diagnóstico; promoções sobrepostas |
| Caixa e PDV | `CaixaServiceIT` (12), `VendaServiceIT` (14), `EtiquetaBalancaIT` (7) | Abertura, sangria com limite, fechamento às cegas (A.10); venda completa do aceite da Fase 4 (saldos, movimentos, pagamentos, rateio, troco), cancelamento com liberação, métrica de 5 mil produtos; etiqueta por preço e peso |
| Inventário | `InventarioServiceIT` (16) | Contagem cega, cenário A.8 com uma venda depois da contagem, não contados, somente leitura, produto em um inventário aberto só |
| Compras | `FornecedorServiceIT` (6), `EntradaServiceIT` (11), `ImportacaoNfeIT` (5) | CNPJ alfanumérico, custo médio A.5 com o banco, nota repetida, histórico de custo, sugestão de reajuste; entrada pela NF-e |
| Relatórios e painel | `RelatoriosIT` (20), `PainelServiceIT` (6) | R01 a R17 com dados conhecidos, R01 × R05 × R08 batendo, venda cancelada fora do faturamento; cartões do painel iguais ao R01 |
| Extras | `ConfiguracaoServiceIT` (7), `ImportacaoProdutosIT` (5), `BackupIT` (5), `ConsultaAuditoriaIT` (4) | Configurações com auditoria e limites; planilha com erro na linha 3 sem importar nada; backup real com `pg_dump` e retenção; filtros da auditoria |
| Dados de demonstração | `GeradorDemoIT` (10), `GeradorDemoReproducaoIT` (1) | 60 dias gerados pelos services; R01 e R05 batem; relatórios < 3 s e painel < 2 s; senhas do README; mesma semente → mesmo resultado |

### 3.3 Como rodar

```bash
mvn -q verify
```

Precisa da variável de ambiente `VARESYNC_DB_SENHA` (senha do usuário `varesync`) e do banco `varesync_test` (README,
seção 10). `mvn test` roda só os unitários, sem banco. Os relatórios ficam em `target/surefire-reports` e
`target/failsafe-reports`.
