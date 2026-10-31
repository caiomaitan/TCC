-- =====================================================================
-- VareSync — V11: abatimentos por parcela (Fase 14: F14.4)
--   1. devolucao_titulo: quanto cada devolução abateu de cada parcela; imutável, como a devolução.
--   2. Linhas das devoluções anteriores a esta migration: da última parcela para a primeira, na ordem das devoluções.
--      O resultado é exato, porque até aqui não existia recebimento.
--   3. vw_abatimento_inconsistente: valor_abatido do título e da devolução contra a soma das linhas (fica vazia).
-- =====================================================================

-- 1. Tabela ---------------------------------------------------------------------------

CREATE TABLE devolucao_titulo (
    id           BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    devolucao_id BIGINT        NOT NULL,
    titulo_id    BIGINT        NOT NULL,
    valor        NUMERIC(12,2) NOT NULL,
    CONSTRAINT fk_dt_devolucao FOREIGN KEY (devolucao_id) REFERENCES devolucao (id),
    CONSTRAINT fk_dt_titulo    FOREIGN KEY (titulo_id)    REFERENCES titulo_receber (id),
    CONSTRAINT uq_dt_titulo    UNIQUE (devolucao_id, titulo_id),
    CONSTRAINT ck_dt_valor     CHECK (valor > 0)
);
CREATE INDEX ix_dt_titulo ON devolucao_titulo (titulo_id);
COMMENT ON TABLE devolucao_titulo IS 'Quanto uma devolução abateu de cada parcela da venda (F14.4).';

CREATE TRIGGER trg_devolucao_titulo_imutavel
    BEFORE UPDATE OR DELETE ON devolucao_titulo
    FOR EACH ROW EXECUTE FUNCTION fn_devolucao_imutavel();

-- 2. Devoluções já feitas ---------------------------------------------------------------

DO $$
DECLARE
    d     RECORD;
    t     RECORD;
    resta NUMERIC(12,2);
    parte NUMERIC(12,2);
BEGIN
    FOR d IN SELECT id, venda_id, valor_abatido FROM devolucao WHERE valor_abatido > 0 ORDER BY id LOOP
        resta := d.valor_abatido;
        FOR t IN SELECT tr.id,
                        tr.valor - coalesce((SELECT sum(dt.valor) FROM devolucao_titulo dt WHERE dt.titulo_id = tr.id), 0)
                            AS livre
                   FROM titulo_receber tr
                  WHERE tr.venda_id = d.venda_id
                  ORDER BY tr.parcela DESC LOOP
            EXIT WHEN resta <= 0;
            parte := least(resta, t.livre);
            IF parte > 0 THEN
                INSERT INTO devolucao_titulo (devolucao_id, titulo_id, valor) VALUES (d.id, t.id, parte);
                resta := resta - parte;
            END IF;
        END LOOP;
    END LOOP;
END;
$$;

-- 3. Conferência -----------------------------------------------------------------------

CREATE VIEW vw_abatimento_inconsistente AS
SELECT 'TITULO' AS origem, t.id, t.valor_abatido, coalesce(sum(dt.valor), 0) AS soma_linhas
  FROM titulo_receber t
  LEFT JOIN devolucao_titulo dt ON dt.titulo_id = t.id
 GROUP BY t.id, t.valor_abatido
HAVING t.valor_abatido <> coalesce(sum(dt.valor), 0)
UNION ALL
SELECT 'DEVOLUCAO' AS origem, d.id, d.valor_abatido, coalesce(sum(dt.valor), 0) AS soma_linhas
  FROM devolucao d
  LEFT JOIN devolucao_titulo dt ON dt.devolucao_id = d.id
 GROUP BY d.id, d.valor_abatido
HAVING d.valor_abatido <> coalesce(sum(dt.valor), 0);
COMMENT ON VIEW vw_abatimento_inconsistente IS 'Precisa ficar vazia: o abatido de cada título e de cada devolução é a soma das linhas de devolucao_titulo.';
