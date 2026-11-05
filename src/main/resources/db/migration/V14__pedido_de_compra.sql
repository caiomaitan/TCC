-- =====================================================================
-- VareSync — V14: compras, parte B (Fase 16: F16.3)
--   1. Pedido de compra (pedido_compra): fornecedor, situação (RASCUNHO, ENVIADO, PARCIAL, RECEBIDO ou CANCELADO),
--      previsão de entrega, observação, quem criou, enviou e cancelou.
--   2. Itens do pedido (pedido_compra_item): um produto por pedido, quantidade na unidade de venda, custo previsto e
--      quantidade recebida (somada pela confirmação das entradas vinculadas).
--   3. Entrada vinculada a pedido: entrada_mercadoria.pedido_id (nula nas entradas antigas e nas sem pedido).
-- =====================================================================

-- 1. Pedido de compra -----------------------------------------------------------------

CREATE TABLE pedido_compra (
    id               BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fornecedor_id    BIGINT      NOT NULL,
    status           VARCHAR(10) NOT NULL DEFAULT 'RASCUNHO',
    previsao_entrega DATE,
    observacao       TEXT,
    criado_em        TIMESTAMPTZ NOT NULL DEFAULT now(),
    criado_por       BIGINT      NOT NULL,
    enviado_em       TIMESTAMPTZ,
    enviado_por      BIGINT,
    cancelado_em     TIMESTAMPTZ,
    cancelado_por    BIGINT,
    CONSTRAINT fk_pc_fornecedor    FOREIGN KEY (fornecedor_id) REFERENCES fornecedor (id),
    CONSTRAINT fk_pc_criado_por    FOREIGN KEY (criado_por)    REFERENCES usuario (id),
    CONSTRAINT fk_pc_enviado_por   FOREIGN KEY (enviado_por)   REFERENCES usuario (id),
    CONSTRAINT fk_pc_cancelado_por FOREIGN KEY (cancelado_por) REFERENCES usuario (id),
    CONSTRAINT ck_pc_status    CHECK (status IN ('RASCUNHO', 'ENVIADO', 'PARCIAL', 'RECEBIDO', 'CANCELADO')),
    CONSTRAINT ck_pc_enviado   CHECK (status NOT IN ('ENVIADO', 'PARCIAL', 'RECEBIDO')
                                      OR (enviado_em IS NOT NULL AND enviado_por IS NOT NULL)),
    CONSTRAINT ck_pc_cancelado CHECK (status <> 'CANCELADO' OR (cancelado_em IS NOT NULL AND cancelado_por IS NOT NULL)),
    CONSTRAINT ck_pc_observacao CHECK (observacao IS NULL OR btrim(observacao) <> '')
);
CREATE INDEX ix_pc_fornecedor ON pedido_compra (fornecedor_id);
CREATE INDEX ix_pc_status     ON pedido_compra (status);
COMMENT ON TABLE  pedido_compra        IS 'Pedido de compra ao fornecedor (F16.3). Só o RASCUNHO é editado; ENVIADO e PARCIAL contam como pendente.';
COMMENT ON COLUMN pedido_compra.status IS 'RASCUNHO → ENVIADO → PARCIAL / RECEBIDO pelas entradas vinculadas; CANCELADO a partir de RASCUNHO, ENVIADO ou PARCIAL.';

-- 2. Itens do pedido ------------------------------------------------------------------

CREATE TABLE pedido_compra_item (
    id                  BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pedido_id           BIGINT        NOT NULL,
    produto_id          BIGINT        NOT NULL,
    quantidade          NUMERIC(14,3) NOT NULL,
    custo_previsto      NUMERIC(14,4) NOT NULL,
    quantidade_recebida NUMERIC(14,3) NOT NULL DEFAULT 0,
    CONSTRAINT fk_pci_pedido  FOREIGN KEY (pedido_id)  REFERENCES pedido_compra (id) ON DELETE CASCADE,
    CONSTRAINT fk_pci_produto FOREIGN KEY (produto_id) REFERENCES produto (id),
    CONSTRAINT uq_pci_produto UNIQUE (pedido_id, produto_id),
    CONSTRAINT ck_pci_quantidade CHECK (quantidade > 0),
    CONSTRAINT ck_pci_custo      CHECK (custo_previsto >= 0),
    CONSTRAINT ck_pci_recebida   CHECK (quantidade_recebida >= 0)
);
CREATE INDEX ix_pci_produto ON pedido_compra_item (produto_id);
COMMENT ON TABLE  pedido_compra_item                     IS 'Itens do pedido de compra, na unidade de venda (F16.3).';
COMMENT ON COLUMN pedido_compra_item.quantidade_recebida IS 'Soma das entradas confirmadas vinculadas ao pedido; pode passar da pedida.';

-- 3. Entrada vinculada a pedido -------------------------------------------------------

ALTER TABLE entrada_mercadoria ADD COLUMN pedido_id BIGINT,
    ADD CONSTRAINT fk_em_pedido FOREIGN KEY (pedido_id) REFERENCES pedido_compra (id);
CREATE INDEX ix_em_pedido ON entrada_mercadoria (pedido_id);
COMMENT ON COLUMN entrada_mercadoria.pedido_id IS 'Pedido de compra atendido por esta entrada (ENVIADO ou PARCIAL ao vincular e ao confirmar).';
