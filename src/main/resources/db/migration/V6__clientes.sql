-- =====================================================================
-- VareSync — V6: clientes, parte A (Fase 12: F12.1, F12.2, F12.6 e R18)
--   1. Tabela cliente (F12.1): cadastro com CPF/CNPJ único sem máscara, contatos, endereço, consentimento de
--      marketing com a data (F12.6), anonimização (F12.6) e nome_busca gerada como a do produto.
--   2. venda.cliente_id (F12.2): cliente identificado na venda, opcional.
--   3. Permissão CLIENTES (seção 0.3 das ampliações).
--   4. vw_venda_item_rentabilidade com o cliente da venda (coluna nova no fim, 0.2): base do R18 e da tela do
--      cliente (histórico, total gasto, produtos mais comprados).
--   O crédito do cliente e o cashback (F12.3 a F12.5) entram na parte B.
-- =====================================================================

-- 1. Cliente ----------------------------------------------------------------

CREATE TABLE cliente (
    id                      BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nome                    VARCHAR(120) NOT NULL,
    nome_busca              TEXT         GENERATED ALWAYS AS (
                                lower(translate(nome,
                                    'ÁÀÂÃÄáàâãäÉÈÊËéèêëÍÌÎÏíìîïÓÒÔÕÖóòôõöÚÙÛÜúùûüÇçÑñ',
                                    'AAAAAaaaaaEEEEeeeeIIIIiiiiOOOOOoooooUUUUuuuuCcNn'))
                            ) STORED,
    cpf_cnpj                VARCHAR(14),
    telefone                VARCHAR(11),
    email                   VARCHAR(120),
    data_nascimento         DATE,
    cep                     CHAR(8),
    logradouro              VARCHAR(120),
    numero                  VARCHAR(10),
    complemento             VARCHAR(60),
    bairro                  VARCHAR(60),
    cidade                  VARCHAR(60),
    uf                      CHAR(2),
    observacao              TEXT,
    consentimento_marketing BOOLEAN      NOT NULL DEFAULT FALSE,
    consentimento_em        TIMESTAMPTZ,
    anonimizado             BOOLEAN      NOT NULL DEFAULT FALSE,
    ativo                   BOOLEAN      NOT NULL DEFAULT TRUE,
    criado_em               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    criado_por              BIGINT       NOT NULL,
    atualizado_em           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    atualizado_por          BIGINT       NOT NULL,
    CONSTRAINT fk_cliente_criado_por     FOREIGN KEY (criado_por)     REFERENCES usuario (id),
    CONSTRAINT fk_cliente_atualizado_por FOREIGN KEY (atualizado_por) REFERENCES usuario (id),
    CONSTRAINT uq_cliente_cpf_cnpj       UNIQUE (cpf_cnpj),
    CONSTRAINT ck_cliente_nome           CHECK (length(btrim(nome)) > 0),
    CONSTRAINT ck_cliente_cpf_cnpj       CHECK (cpf_cnpj ~ '^([0-9]{11}|[0-9A-Z]{12}[0-9]{2})$'),
    CONSTRAINT ck_cliente_telefone       CHECK (telefone ~ '^[0-9]{10,11}$'),
    CONSTRAINT ck_cliente_cep            CHECK (cep ~ '^[0-9]{8}$'),
    CONSTRAINT ck_cliente_uf             CHECK (uf ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_cliente_consentimento  CHECK (consentimento_marketing = (consentimento_em IS NOT NULL)),
    CONSTRAINT ck_cliente_anonimizado    CHECK (NOT anonimizado OR (
        cpf_cnpj IS NULL AND telefone IS NULL AND email IS NULL AND data_nascimento IS NULL AND cep IS NULL
        AND logradouro IS NULL AND numero IS NULL AND complemento IS NULL AND bairro IS NULL AND cidade IS NULL
        AND uf IS NULL AND observacao IS NULL AND NOT consentimento_marketing AND NOT ativo))
);
CREATE INDEX ix_cliente_nome_busca ON cliente (nome_busca text_pattern_ops);
COMMENT ON TABLE  cliente                  IS 'Clientes (F12). Excluir só sem vendas; senão inativar. Anonimizado (LGPD) mantém as vendas.';
COMMENT ON COLUMN cliente.cpf_cnpj         IS 'Sem máscara: 11 dígitos (CPF) ou 14 posições (CNPJ numérico ou alfanumérico). DV validado no Java.';
COMMENT ON COLUMN cliente.nome_busca       IS 'Nome em minúsculas e sem acento, para busca (mesma regra de produto.nome_busca).';
COMMENT ON COLUMN cliente.consentimento_em IS 'Quando o cliente consentiu em receber ofertas (LGPD); nulo sem consentimento.';
COMMENT ON COLUMN cliente.anonimizado      IS 'Dados pessoais apagados a pedido do titular (LGPD): nome trocado por "Cliente anonimizado nº id".';

CREATE TRIGGER trg_cliente_atualizado_em BEFORE UPDATE ON cliente FOR EACH ROW EXECUTE FUNCTION fn_set_atualizado_em();

-- 2. Cliente na venda ---------------------------------------------------------

ALTER TABLE venda
    ADD COLUMN cliente_id BIGINT,
    ADD CONSTRAINT fk_venda_cliente FOREIGN KEY (cliente_id) REFERENCES cliente (id);
CREATE INDEX ix_venda_cliente ON venda (cliente_id);
COMMENT ON COLUMN venda.cliente_id IS 'Cliente identificado no PDV (F11); nulo na venda sem identificação.';

-- 3. Permissão ----------------------------------------------------------------

INSERT INTO permissao (codigo, descricao) VALUES
    ('CLIENTES', 'Cadastro de clientes');

-- 4. View de rentabilidade com o cliente (colunas existentes na mesma ordem; a nova no fim) ---------------------

CREATE OR REPLACE VIEW vw_venda_item_rentabilidade AS
SELECT v.id                                   AS venda_id,
       v.data_hora,
       v.caixa_sessao_id,
       v.usuario_id,
       vi.id                                  AS venda_item_id,
       vi.sequencia,
       vi.produto_id,
       p.nome                                 AS produto_nome,
       p.categoria_id,
       c.nome                                 AS categoria_nome,
       p.unidade,
       vi.quantidade,
       vi.preco_unitario,
       vi.valor_bruto,
       vi.desconto + vi.desconto_rateado      AS desconto_total,
       vi.valor_liquido                       AS receita,
       vi.custo_total                         AS cmv,
       vi.valor_liquido - vi.custo_total      AS lucro_bruto,
       vi.promocao_id IS NOT NULL             AS em_promocao,
       v.cliente_id
  FROM venda v
  JOIN venda_item vi ON vi.venda_id = v.id
  JOIN produto p     ON p.id = vi.produto_id
  LEFT JOIN categoria c ON c.id = p.categoria_id
 WHERE v.status = 'FINALIZADA'
   AND NOT vi.cancelado;
