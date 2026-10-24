-- =====================================================================
-- VareSync — teste de integridade do banco
-- Rode num banco já migrado (V1 a V4). Tudo é desfeito no ROLLBACK final.
--   psql:    psql -d varesync -v ON_ERROR_STOP=1 -f scripts/teste_integridade_banco.sql
--   DBeaver: abrir o arquivo e "Executar script" (Alt+X); ver a aba Output.
-- Cada cenário imprime "OK n"; qualquer falha interrompe com a mensagem do ASSERT.
-- =====================================================================
BEGIN;

DO $$
DECLARE
    v_user     BIGINT;
    v_cat      BIGINT;
    v_prod     BIGINT;
    v_prod2    BIGINT;
    v_sessao   BIGINT;
    v_venda    BIGINT;
    v_item1    BIGINT;
    v_inv      BIGINT;
    v_inv_item BIGINT;
    v_forn     BIGINT;
    v_entrada  BIGINT;
    v_ent_item BIGINT;
    v_dinheiro SMALLINT;
    v_pix      SMALLINT;
    v_saldo    NUMERIC;
    v_n        BIGINT;
    v_ok       BOOLEAN;
    v_rec      RECORD;
BEGIN
    -- 1. Cadastro --------------------------------------------------------
    INSERT INTO usuario (login, nome, senha_hash, supervisor)
    VALUES ('teste.sup', 'Supervisor de Teste', '$2a$10$hash.apenas.para.teste', TRUE)
    RETURNING id INTO v_user;

    v_ok := FALSE;
    BEGIN
        INSERT INTO usuario (login, nome, senha_hash) VALUES ('Maria Silva', 'Maria', 'x');
    EXCEPTION WHEN check_violation THEN v_ok := TRUE;
    END;
    ASSERT v_ok, 'login com maiúscula/espaço deveria ser rejeitado';

    SELECT id INTO v_cat FROM categoria WHERE nome = 'Mercearia';
    INSERT INTO produto (nome, categoria_id, unidade, preco_venda, custo, criado_por, atualizado_por)
    VALUES ('Açúcar Cristal 1kg', v_cat, 'UN', 5.49, 3.2000, v_user, v_user)
    RETURNING id INTO v_prod;

    SELECT quantidade INTO v_saldo FROM produto_estoque WHERE produto_id = v_prod;
    ASSERT v_saldo = 0, 'produto novo deveria nascer com saldo 0';
    SELECT count(*) INTO v_n FROM produto_historico_preco WHERE produto_id = v_prod;
    ASSERT v_n = 1, 'cadastro deveria gerar 1 linha de histórico de preço';
    ASSERT (SELECT nome_busca FROM produto WHERE id = v_prod) = 'acucar cristal 1kg',
           'nome_busca deveria ficar minúsculo e sem acento';
    RAISE NOTICE 'OK 1  cadastro: saldo inicial 0, histórico de preço, nome_busca, login validado';

    -- 2. Escrita direta no saldo é bloqueada -----------------------------
    v_ok := FALSE;
    BEGIN
        UPDATE produto_estoque SET quantidade = 99 WHERE produto_id = v_prod;
    EXCEPTION WHEN raise_exception THEN v_ok := TRUE;
    END;
    ASSERT v_ok, 'UPDATE direto em produto_estoque deveria falhar';
    RAISE NOTICE 'OK 2  UPDATE direto em produto_estoque bloqueado';

    -- 3. Movimento atualiza o saldo --------------------------------------
    INSERT INTO movimento_estoque (produto_id, tipo, quantidade, motivo, usuario_id)
    VALUES (v_prod, 'AJUSTE_ENTRADA', 10, 'Estoque inicial', v_user);
    SELECT saldo_anterior, saldo_posterior, custo_unitario INTO v_rec
      FROM movimento_estoque WHERE produto_id = v_prod ORDER BY id DESC LIMIT 1;
    ASSERT v_rec.saldo_anterior = 0 AND v_rec.saldo_posterior = 10, 'saldo anterior/posterior incorretos';
    ASSERT v_rec.custo_unitario = 3.2000, 'custo do movimento deveria vir do produto';
    ASSERT (SELECT quantidade FROM produto_estoque WHERE produto_id = v_prod) = 10, 'saldo deveria ser 10';
    RAISE NOTICE 'OK 3  movimento atualiza o saldo (0 -> 10) e herda o custo do produto';

    -- 4. Sinal e origem do movimento -------------------------------------
    v_ok := FALSE;
    BEGIN
        INSERT INTO movimento_estoque (produto_id, tipo, quantidade, usuario_id)
        VALUES (v_prod, 'AJUSTE_SAIDA', -1, v_user);
    EXCEPTION WHEN check_violation THEN v_ok := TRUE;
    END;
    ASSERT v_ok, 'ajuste sem motivo deveria falhar';

    v_ok := FALSE;
    BEGIN
        INSERT INTO movimento_estoque (produto_id, tipo, quantidade, motivo, usuario_id)
        VALUES (v_prod, 'PERDA', 2, 'Avaria', v_user);
    EXCEPTION WHEN check_violation THEN v_ok := TRUE;
    END;
    ASSERT v_ok, 'perda com quantidade positiva deveria falhar';
    ASSERT (SELECT quantidade FROM produto_estoque WHERE produto_id = v_prod) = 10,
           'movimento rejeitado não pode alterar o saldo';
    RAISE NOTICE 'OK 4  ajuste sem motivo e perda com sinal errado rejeitados (saldo intacto)';

    -- 5. Kardex imutável -------------------------------------------------
    v_ok := FALSE;
    BEGIN
        UPDATE movimento_estoque SET motivo = 'alterado' WHERE produto_id = v_prod;
    EXCEPTION WHEN raise_exception THEN v_ok := TRUE;
    END;
    ASSERT v_ok, 'UPDATE em movimento_estoque deveria falhar';

    v_ok := FALSE;
    BEGIN
        DELETE FROM movimento_estoque WHERE produto_id = v_prod;
    EXCEPTION WHEN raise_exception THEN v_ok := TRUE;
    END;
    ASSERT v_ok, 'DELETE em movimento_estoque deveria falhar';
    RAISE NOTICE 'OK 5  movimentos não podem ser alterados nem excluídos';

    -- 6. Uma sessão de caixa aberta por terminal -------------------------
    INSERT INTO caixa_sessao (terminal, usuario_id, valor_abertura)
    VALUES ('CAIXA-TESTE', v_user, 100.00)
    RETURNING id INTO v_sessao;

    v_ok := FALSE;
    BEGIN
        INSERT INTO caixa_sessao (terminal, usuario_id) VALUES ('CAIXA-TESTE', v_user);
    EXCEPTION WHEN unique_violation THEN v_ok := TRUE;
    END;
    ASSERT v_ok, 'segunda sessão aberta no mesmo terminal deveria falhar';
    RAISE NOTICE 'OK 6  apenas uma sessão de caixa aberta por terminal';

    -- 7. Venda -----------------------------------------------------------
    -- item 1: 3 x 5,49 = 16,47 | item 2 cancelado durante a venda
    -- desconto no total 0,47 (rateado no item 1) | total 16,00
    -- pagamento: 6,00 em dinheiro (cliente deu 10,00, troco 4,00) + 10,00 no PIX
    SELECT id INTO v_dinheiro FROM forma_pagamento WHERE tipo = 'DINHEIRO';
    SELECT id INTO v_pix      FROM forma_pagamento WHERE tipo = 'PIX';

    INSERT INTO venda (caixa_sessao_id, usuario_id, valor_bruto, desconto_itens, desconto_venda, valor_total, troco)
    VALUES (v_sessao, v_user, 16.47, 0, 0.47, 16.00, 4.00)
    RETURNING id INTO v_venda;

    INSERT INTO venda_item (venda_id, sequencia, produto_id, quantidade, preco_unitario, valor_bruto,
                            desconto, desconto_rateado, valor_liquido, custo_unitario, custo_total)
    VALUES (v_venda, 1, v_prod, 3, 5.49, 16.47, 0, 0.47, 16.00, 3.2000, 9.60)
    RETURNING id INTO v_item1;

    INSERT INTO venda_item (venda_id, sequencia, produto_id, quantidade, preco_unitario, valor_bruto,
                            desconto, desconto_rateado, valor_liquido, custo_unitario, custo_total, cancelado,
                            cancelado_em, cancelado_por)
    VALUES (v_venda, 2, v_prod, 1, 5.49, 5.49, 0, 0, 5.49, 3.2000, 3.20, TRUE, now(), v_user);

    INSERT INTO venda_pagamento (venda_id, forma_pagamento_id, valor)
    VALUES (v_venda, v_dinheiro, 6.00), (v_venda, v_pix, 10.00);

    INSERT INTO movimento_estoque (produto_id, tipo, quantidade, venda_item_id, usuario_id)
    VALUES (v_prod, 'VENDA', -3, v_item1, v_user);

    ASSERT (SELECT quantidade FROM produto_estoque WHERE produto_id = v_prod) = 7, 'venda de 3 deveria deixar saldo 7';

    SELECT count(*) AS itens, sum(receita) AS receita, sum(cmv) AS cmv, sum(lucro_bruto) AS lucro INTO v_rec
      FROM vw_venda_item_rentabilidade WHERE venda_id = v_venda;
    ASSERT v_rec.itens = 1 AND v_rec.receita = 16.00 AND v_rec.cmv = 9.60 AND v_rec.lucro = 6.40,
           'rentabilidade deveria considerar só o item não cancelado (receita 16,00, CMV 9,60, lucro 6,40)';
    RAISE NOTICE 'OK 7  venda: baixa de estoque, desconto rateado, item cancelado fora da rentabilidade';

    -- 8. Proteções da venda ----------------------------------------------
    v_ok := FALSE;
    BEGIN
        INSERT INTO movimento_estoque (produto_id, tipo, quantidade, venda_item_id, usuario_id)
        VALUES (v_prod, 'VENDA', -3, v_item1, v_user);
    EXCEPTION WHEN unique_violation THEN v_ok := TRUE;
    END;
    ASSERT v_ok, 'baixa duplicada do mesmo item deveria falhar';

    v_ok := FALSE;
    BEGIN
        INSERT INTO venda (caixa_sessao_id, usuario_id, valor_bruto, desconto_itens, desconto_venda, valor_total)
        VALUES (v_sessao, v_user, 10.00, 0, 1.00, 10.00);
    EXCEPTION WHEN check_violation THEN v_ok := TRUE;
    END;
    ASSERT v_ok, 'venda com total diferente de bruto - descontos deveria falhar';
    RAISE NOTICE 'OK 8  baixa duplicada e total inconsistente rejeitados';

    -- 9. Resumo do caixa -------------------------------------------------
    INSERT INTO caixa_movimento (caixa_sessao_id, tipo, valor, motivo, usuario_id)
    VALUES (v_sessao, 'SANGRIA', 50.00, 'Depósito no cofre', v_user);
    SELECT dinheiro_esperado, qtd_vendas, total_vendido INTO v_rec
      FROM vw_caixa_resumo WHERE caixa_sessao_id = v_sessao;
    ASSERT v_rec.dinheiro_esperado = 56.00,
           format('dinheiro esperado deveria ser 56,00 e foi %s', v_rec.dinheiro_esperado);
    ASSERT v_rec.qtd_vendas = 1 AND v_rec.total_vendido = 16.00, 'resumo de vendas da sessão incorreto';
    RAISE NOTICE 'OK 9  dinheiro esperado no caixa: 100,00 + 6,00 - 50,00 = 56,00';

    -- 10. Cancelamento da venda ------------------------------------------
    v_ok := FALSE;
    BEGIN
        UPDATE venda SET status = 'CANCELADA', cancelada_em = now(), cancelada_por = v_user,
                         cancelamento_operador_id = v_user, motivo_cancelamento = ' '
         WHERE id = v_venda;
    EXCEPTION WHEN check_violation THEN v_ok := TRUE;
    END;
    ASSERT v_ok, 'cancelamento com motivo em branco deveria falhar';

    v_ok := FALSE;
    BEGIN
        UPDATE venda SET status = 'CANCELADA', cancelada_em = now(), cancelada_por = v_user,
                         cancelamento_operador_id = v_user WHERE id = v_venda;
    EXCEPTION WHEN check_violation THEN v_ok := TRUE;
    END;
    ASSERT v_ok, 'cancelamento sem motivo deveria falhar';

    UPDATE venda SET status = 'CANCELADA', cancelada_em = now(), cancelada_por = v_user,
                     cancelamento_operador_id = v_user, motivo_cancelamento = 'Cliente desistiu'
     WHERE id = v_venda;
    INSERT INTO movimento_estoque (produto_id, tipo, quantidade, venda_item_id, usuario_id)
    VALUES (v_prod, 'CANCELAMENTO_VENDA', 3, v_item1, v_user);

    ASSERT (SELECT quantidade FROM produto_estoque WHERE produto_id = v_prod) = 10,
           'cancelamento deveria devolver o saldo para 10';
    ASSERT NOT EXISTS (SELECT 1 FROM vw_venda_item_rentabilidade WHERE venda_id = v_venda),
           'venda cancelada não pode aparecer na rentabilidade';
    SELECT dinheiro_esperado INTO v_saldo FROM vw_caixa_resumo WHERE caixa_sessao_id = v_sessao;
    ASSERT v_saldo = 50.00, 'venda cancelada não entra no dinheiro esperado';
    RAISE NOTICE 'OK 10 cancelamento: motivo obrigatório, estoque devolvido, fora da rentabilidade e do caixa';

    -- 11. Inventário aplica a DIFERENÇA capturada na contagem ------------
    INSERT INTO inventario (descricao, criado_por) VALUES ('Inventário de teste', v_user) RETURNING id INTO v_inv;
    INSERT INTO inventario_item (inventario_id, produto_id) VALUES (v_inv, v_prod) RETURNING id INTO v_inv_item;

    -- contagem digitada: sistema = 10, contado = 8
    UPDATE inventario_item
       SET quantidade_contada = 8,
           estoque_sistema    = (SELECT quantidade FROM produto_estoque WHERE produto_id = v_prod),
           custo_unitario     = (SELECT custo FROM produto WHERE id = v_prod),
           contado_em         = now(),
           contado_por        = v_user
     WHERE id = v_inv_item;
    ASSERT (SELECT diferenca FROM inventario_item WHERE id = v_inv_item) = -2, 'diferença deveria ser -2';

    -- depois da contagem sai mais 1 unidade (sistema 9; físico agora 7)
    INSERT INTO movimento_estoque (produto_id, tipo, quantidade, motivo, usuario_id)
    VALUES (v_prod, 'PERDA', -1, 'Avaria', v_user);

    -- aplicação: lança a diferença (-2) em vez de igualar ao contado (8)
    INSERT INTO movimento_estoque (produto_id, tipo, quantidade, inventario_item_id, custo_unitario, usuario_id)
    SELECT produto_id, 'INVENTARIO', diferenca, id, custo_unitario, v_user
      FROM inventario_item WHERE id = v_inv_item AND diferenca <> 0;
    UPDATE inventario SET status = 'APLICADO', aplicado_por = v_user, aplicado_em = now() WHERE id = v_inv;

    ASSERT (SELECT quantidade FROM produto_estoque WHERE produto_id = v_prod) = 7,
           'saldo após o inventário deveria ser 7 (o físico real)';
    RAISE NOTICE 'OK 11 inventário: aplica a diferença da contagem e preserva o que saiu depois (saldo 7)';

    -- 12. Entrada de mercadoria ------------------------------------------
    INSERT INTO fornecedor (razao_social, cpf_cnpj) VALUES ('Distribuidora Teste Ltda', '12ABC34501DE35')
    RETURNING id INTO v_forn;
    INSERT INTO entrada_mercadoria (fornecedor_id, numero_documento, criado_por) VALUES (v_forn, '1234', v_user)
    RETURNING id INTO v_entrada;
    INSERT INTO entrada_mercadoria_item (entrada_id, produto_id, quantidade, custo_unitario, valor_total)
    VALUES (v_entrada, v_prod, 12, 3.5000, 42.00)
    RETURNING id INTO v_ent_item;
    INSERT INTO movimento_estoque (produto_id, tipo, quantidade, entrada_item_id, custo_unitario, usuario_id)
    VALUES (v_prod, 'ENTRADA_COMPRA', 12, v_ent_item, 3.5000, v_user);
    UPDATE entrada_mercadoria SET status = 'CONFIRMADA', confirmado_por = v_user, confirmado_em = now()
     WHERE id = v_entrada;
    ASSERT (SELECT quantidade FROM produto_estoque WHERE produto_id = v_prod) = 19,
           'entrada de 12 deveria levar o saldo a 19';

    v_ok := FALSE;
    BEGIN
        INSERT INTO entrada_mercadoria (fornecedor_id, numero_documento, criado_por) VALUES (v_forn, '1234', v_user);
    EXCEPTION WHEN unique_violation THEN v_ok := TRUE;
    END;
    ASSERT v_ok, 'mesma nota do mesmo fornecedor deveria ser rejeitada';

    v_ok := FALSE;
    BEGIN
        INSERT INTO fornecedor (razao_social, cpf_cnpj) VALUES ('Formato inválido', '12ABC34501DE3X');
    EXCEPTION WHEN check_violation THEN v_ok := TRUE;
    END;
    ASSERT v_ok, 'CNPJ com letra nos dígitos verificadores deveria ser rejeitado';
    RAISE NOTICE 'OK 12 entrada: saldo 19, nota duplicada barrada, CNPJ alfanumérico aceito';

    -- 13. Histórico de preço ---------------------------------------------
    UPDATE produto SET preco_venda = 5.99, atualizado_por = v_user WHERE id = v_prod;
    SELECT preco_anterior, preco_novo INTO v_rec
      FROM produto_historico_preco WHERE produto_id = v_prod ORDER BY id DESC LIMIT 1;
    ASSERT v_rec.preco_anterior = 5.49 AND v_rec.preco_novo = 5.99, 'histórico não registrou a alteração de preço';
    RAISE NOTICE 'OK 13 alteração de preço registrada no histórico';

    -- 14. Exclusão de produto --------------------------------------------
    v_ok := FALSE;
    BEGIN
        DELETE FROM produto WHERE id = v_prod;
    EXCEPTION WHEN foreign_key_violation OR raise_exception THEN v_ok := TRUE;
    END;
    ASSERT v_ok, 'produto com movimentação não pode ser excluído';

    INSERT INTO produto (nome, unidade, preco_venda, criado_por, atualizado_por)
    VALUES ('Produto sem uso', 'UN', 1.00, v_user, v_user)
    RETURNING id INTO v_prod2;
    DELETE FROM produto WHERE id = v_prod2;
    ASSERT NOT EXISTS (SELECT 1 FROM produto_estoque WHERE produto_id = v_prod2),
           'o saldo zerado deveria ser apagado junto com o produto';
    RAISE NOTICE 'OK 14 produto movimentado não pode ser excluído; produto sem uso pode';

    -- 15. Saldo = soma dos movimentos ------------------------------------
    ASSERT NOT EXISTS (SELECT 1 FROM vw_estoque_inconsistente), 'vw_estoque_inconsistente deveria estar vazia';
    RAISE NOTICE 'OK 15 saldo igual à soma dos movimentos (vw_estoque_inconsistente vazia)';

    RAISE NOTICE 'Todos os 15 cenários passaram.';
END $$;

ROLLBACK;
