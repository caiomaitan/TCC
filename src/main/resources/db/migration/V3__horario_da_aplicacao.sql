-- =====================================================================
-- VareSync — V3: horários vêm da aplicação
-- O Java grava as datas pelo Clock (testes com data fixa, dados de demonstração no passado).
-- No V1, três triggers usavam now() e sobrescreviam o horário enviado:
--   1. fn_set_atualizado_em: só usa now() quando o UPDATE não informou atualizado_em
--      (o valor ficou igual ao anterior); assim, alteração feita fora do sistema (DBeaver) continua datada.
--   2. fn_movimento_aplica_saldo: produto_estoque.atualizado_em = horário do movimento (NEW.criado_em).
--   3. fn_produto_cria_estoque: saldo inicial com atualizado_em = horário de cadastro do produto.
-- Como o histórico de preço (fn_produto_historico_preco) usa NEW.atualizado_em, ele passa a ter o horário
-- da aplicação. Os triggers do V1 continuam os mesmos: só as funções são substituídas.
-- =====================================================================

CREATE OR REPLACE FUNCTION fn_set_atualizado_em() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.atualizado_em IS NOT DISTINCT FROM OLD.atualizado_em THEN
        NEW.atualizado_em := now();
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION fn_movimento_aplica_saldo() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_saldo NUMERIC(14,3);
BEGIN
    IF NEW.custo_unitario IS NULL THEN
        SELECT p.custo INTO NEW.custo_unitario FROM produto p WHERE p.id = NEW.produto_id;
    END IF;

    PERFORM set_config('varesync.via_movimento', 'on', true);
    UPDATE produto_estoque
       SET quantidade    = quantidade + NEW.quantidade,
           atualizado_em = NEW.criado_em
     WHERE produto_id = NEW.produto_id
    RETURNING quantidade INTO v_saldo;
    PERFORM set_config('varesync.via_movimento', 'off', true);

    IF v_saldo IS NULL THEN
        RAISE EXCEPTION 'Produto % não encontrado no estoque.', NEW.produto_id;
    END IF;

    NEW.saldo_posterior := v_saldo;
    NEW.saldo_anterior  := v_saldo - NEW.quantidade;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION fn_produto_cria_estoque() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM set_config('varesync.via_movimento', 'on', true);
    INSERT INTO produto_estoque (produto_id, quantidade, atualizado_em) VALUES (NEW.id, 0, NEW.criado_em);
    PERFORM set_config('varesync.via_movimento', 'off', true);
    RETURN NULL;
END $$;
