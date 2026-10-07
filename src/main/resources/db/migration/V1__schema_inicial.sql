-- =====================================================================
-- VareSync — V1: esquema inicial (PostgreSQL 14+; testado no 16)
-- =====================================================================
-- Convenções
--   * tabelas e colunas em snake_case, nomes no singular e por extenso
--   * chaves: BIGINT GENERATED ALWAYS AS IDENTITY (o Java usa RETURNING id)
--   * dinheiro NUMERIC(12,2) | custo NUMERIC(14,4) | quantidade NUMERIC(14,3)
--   * datas/horas TIMESTAMPTZ (o driver usa o fuso da JVM na sessão)
--   * restrições com nome explícito (ck_, uq_, ux_, fk_) para o Java
--     traduzir erros do banco em mensagens amigáveis
--
-- Regras garantidas pelo banco (o Java NÃO deve reimplementar nem contornar)
--   1. produto_estoque só muda via INSERT em movimento_estoque (trigger).
--      INSERT/UPDATE direto em produto_estoque é bloqueado.
--   2. movimento_estoque é imutável (kardex): sem UPDATE e sem DELETE.
--   3. Todo produto novo nasce com saldo 0 em produto_estoque (trigger).
--   4. Mudança de preço ou custo grava produto_historico_preco (trigger).
--   5. atualizado_em é preenchido automaticamente em UPDATE (trigger).
--
-- Este arquivo NÃO deve ser editado depois de aplicado: mudanças no banco
-- entram como nova migration (V3__..., V4__...).
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. Segurança e administração
-- ---------------------------------------------------------------------

CREATE TABLE usuario (
    id                BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    login             VARCHAR(30)  NOT NULL,
    nome              VARCHAR(80)  NOT NULL,
    senha_hash        VARCHAR(100) NOT NULL,
    supervisor        BOOLEAN      NOT NULL DEFAULT FALSE,
    ativo             BOOLEAN      NOT NULL DEFAULT TRUE,
    deve_trocar_senha BOOLEAN      NOT NULL DEFAULT TRUE,
    tentativas_falhas SMALLINT     NOT NULL DEFAULT 0,
    bloqueado_ate     TIMESTAMPTZ,
    ultimo_login_em   TIMESTAMPTZ,
    criado_em         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    atualizado_em     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_usuario_login      UNIQUE (login),
    CONSTRAINT ck_usuario_login      CHECK (login ~ '^[a-z0-9._-]{3,30}$'),
    CONSTRAINT ck_usuario_nome       CHECK (length(btrim(nome)) > 0),
    CONSTRAINT ck_usuario_tentativas CHECK (tentativas_falhas >= 0)
);
COMMENT ON TABLE  usuario            IS 'Usuários do sistema. Supervisor tem acesso a todas as rotinas.';
COMMENT ON COLUMN usuario.login      IS 'Sempre em minúsculas (o Java normaliza antes de gravar/consultar).';
COMMENT ON COLUMN usuario.senha_hash IS 'Hash BCrypt da senha. A senha em texto nunca é gravada.';

CREATE TABLE permissao (
    codigo    VARCHAR(30)  PRIMARY KEY,
    descricao VARCHAR(100) NOT NULL
);
COMMENT ON TABLE permissao IS 'Catálogo de rotinas/ações liberáveis por usuário (espelha o enum Permissao do Java).';

CREATE TABLE usuario_permissao (
    usuario_id       BIGINT      NOT NULL,
    permissao_codigo VARCHAR(30) NOT NULL,
    CONSTRAINT pk_usuario_permissao PRIMARY KEY (usuario_id, permissao_codigo),
    CONSTRAINT fk_up_usuario   FOREIGN KEY (usuario_id)       REFERENCES usuario (id) ON DELETE CASCADE,
    CONSTRAINT fk_up_permissao FOREIGN KEY (permissao_codigo) REFERENCES permissao (codigo)
);

CREATE TABLE configuracao (
    chave     VARCHAR(60)  PRIMARY KEY,
    valor     TEXT         NOT NULL,
    descricao VARCHAR(200)
);
COMMENT ON TABLE configuracao IS 'Parâmetros chave/valor (dados da loja, regras do PDV, relatórios, balança, backup).';

CREATE TABLE auditoria (
    id          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id  BIGINT,
    acao        VARCHAR(40) NOT NULL,
    entidade    VARCHAR(40),
    entidade_id BIGINT,
    descricao   TEXT,
    terminal    VARCHAR(30),
    criado_em   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_auditoria_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id)
);
CREATE INDEX ix_auditoria_criado_em ON auditoria (criado_em);
CREATE INDEX ix_auditoria_entidade  ON auditoria (entidade, entidade_id);
CREATE INDEX ix_auditoria_usuario   ON auditoria (usuario_id);
COMMENT ON TABLE auditoria IS 'Log de ações relevantes (login, alterações de preço, cancelamentos, ajustes, caixa...).';


-- ---------------------------------------------------------------------
-- 2. Cadastros
-- ---------------------------------------------------------------------

CREATE TABLE unidade_medida (
    sigla      VARCHAR(6)  PRIMARY KEY,
    descricao  VARCHAR(40) NOT NULL,
    fracionada BOOLEAN     NOT NULL DEFAULT FALSE
);
COMMENT ON COLUMN unidade_medida.fracionada IS 'TRUE = aceita quantidade com casas decimais (KG, L, M).';

CREATE TABLE categoria (
    id    BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nome  VARCHAR(60) NOT NULL,
    ativa BOOLEAN     NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_categoria_nome CHECK (length(btrim(nome)) > 0)
);
CREATE UNIQUE INDEX ux_categoria_nome ON categoria (lower(nome));

CREATE TABLE produto (
    id             BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nome           VARCHAR(120)  NOT NULL,
    nome_busca     TEXT          GENERATED ALWAYS AS (
                       lower(translate(nome,
                           'ÁÀÂÃÄáàâãäÉÈÊËéèêëÍÌÎÏíìîïÓÒÔÕÖóòôõöÚÙÛÜúùûüÇçÑñ',
                           'AAAAAaaaaaEEEEeeeeIIIIiiiiOOOOOoooooUUUUuuuuCcNn'))
                   ) STORED,
    categoria_id   BIGINT,
    unidade        VARCHAR(6)    NOT NULL DEFAULT 'UN',
    preco_venda    NUMERIC(12,2) NOT NULL,
    custo          NUMERIC(14,4) NOT NULL DEFAULT 0,
    ultimo_custo   NUMERIC(14,4),
    estoque_minimo NUMERIC(14,3) NOT NULL DEFAULT 0,
    plu            INTEGER,
    ativo          BOOLEAN       NOT NULL DEFAULT TRUE,
    criado_em      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    criado_por     BIGINT        NOT NULL,
    atualizado_em  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    atualizado_por BIGINT        NOT NULL,
    CONSTRAINT fk_produto_categoria      FOREIGN KEY (categoria_id)   REFERENCES categoria (id),
    CONSTRAINT fk_produto_unidade        FOREIGN KEY (unidade)        REFERENCES unidade_medida (sigla),
    CONSTRAINT fk_produto_criado_por     FOREIGN KEY (criado_por)     REFERENCES usuario (id),
    CONSTRAINT fk_produto_atualizado_por FOREIGN KEY (atualizado_por) REFERENCES usuario (id),
    CONSTRAINT uq_produto_plu            UNIQUE (plu),
    CONSTRAINT ck_produto_nome           CHECK (length(btrim(nome)) >= 2),
    CONSTRAINT ck_produto_preco          CHECK (preco_venda >= 0),
    CONSTRAINT ck_produto_custo          CHECK (custo >= 0 AND (ultimo_custo IS NULL OR ultimo_custo >= 0)),
    CONSTRAINT ck_produto_estoque_minimo CHECK (estoque_minimo >= 0),
    CONSTRAINT ck_produto_plu            CHECK (plu BETWEEN 1 AND 999999)
);
CREATE INDEX ix_produto_nome_busca ON produto (nome_busca text_pattern_ops);
CREATE INDEX ix_produto_categoria  ON produto (categoria_id);
COMMENT ON COLUMN produto.id           IS 'Código interno do produto (exibido como "Código").';
COMMENT ON COLUMN produto.nome_busca   IS 'Nome em minúsculas e sem acento, para busca (o Java normaliza o termo do mesmo jeito).';
COMMENT ON COLUMN produto.custo        IS 'Custo médio ponderado, usado no cálculo de lucro e margem.';
COMMENT ON COLUMN produto.ultimo_custo IS 'Custo unitário da última entrada de mercadoria.';
COMMENT ON COLUMN produto.plu          IS 'Código do produto na balança (etiqueta EAN-13 com prefixo 2). Só para pesáveis.';

CREATE TABLE produto_codigo_barras (
    id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    produto_id BIGINT      NOT NULL,
    codigo     VARCHAR(14) NOT NULL,
    principal  BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_pcb_produto FOREIGN KEY (produto_id) REFERENCES produto (id) ON DELETE CASCADE,
    CONSTRAINT uq_pcb_codigo  UNIQUE (codigo),
    CONSTRAINT ck_pcb_codigo  CHECK (codigo ~ '^[0-9]+$' AND length(codigo) IN (8, 12, 13, 14))
);
CREATE INDEX        ix_pcb_produto   ON produto_codigo_barras (produto_id);
CREATE UNIQUE INDEX ux_pcb_principal ON produto_codigo_barras (produto_id) WHERE principal;
COMMENT ON TABLE produto_codigo_barras IS 'Códigos de barras (GTIN-8/12/13/14) do produto; um produto pode ter vários.';

CREATE TABLE produto_historico_preco (
    id             BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    produto_id     BIGINT        NOT NULL,
    preco_anterior NUMERIC(12,2),
    preco_novo     NUMERIC(12,2) NOT NULL,
    custo_anterior NUMERIC(14,4),
    custo_novo     NUMERIC(14,4) NOT NULL,
    usuario_id     BIGINT        NOT NULL,
    criado_em      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_php_produto FOREIGN KEY (produto_id) REFERENCES produto (id) ON DELETE CASCADE,
    CONSTRAINT fk_php_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id)
);
CREATE INDEX ix_php_produto ON produto_historico_preco (produto_id, criado_em);
COMMENT ON TABLE produto_historico_preco IS 'Preenchida por trigger: cadastro do produto e toda alteração de preço/custo.';

CREATE TABLE produto_estoque (
    produto_id    BIGINT        PRIMARY KEY,
    quantidade    NUMERIC(14,3) NOT NULL DEFAULT 0,
    atualizado_em TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_pe_produto FOREIGN KEY (produto_id) REFERENCES produto (id) ON DELETE CASCADE
);
COMMENT ON TABLE produto_estoque IS 'Saldo atual por produto. Atualizado SOMENTE pelo trigger de movimento_estoque. Pode ficar negativo se a configuração permitir.';

CREATE TABLE promocao (
    id                BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    produto_id        BIGINT        NOT NULL,
    preco_promocional NUMERIC(12,2) NOT NULL,
    data_inicio       DATE          NOT NULL,
    data_fim          DATE          NOT NULL,
    ativa             BOOLEAN       NOT NULL DEFAULT TRUE,
    criado_por        BIGINT        NOT NULL,
    criado_em         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_promocao_produto    FOREIGN KEY (produto_id) REFERENCES produto (id) ON DELETE CASCADE,
    CONSTRAINT fk_promocao_criado_por FOREIGN KEY (criado_por) REFERENCES usuario (id),
    CONSTRAINT ck_promocao_preco      CHECK (preco_promocional > 0),
    CONSTRAINT ck_promocao_periodo    CHECK (data_fim >= data_inicio)
);
CREATE INDEX ix_promocao_produto ON promocao (produto_id, data_inicio, data_fim);
COMMENT ON TABLE promocao IS 'Preço promocional por período (inclusive). Sobreposição de promoções ativas do mesmo produto é barrada no Java.';

CREATE TABLE fornecedor (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    razao_social  VARCHAR(120) NOT NULL,
    nome_fantasia VARCHAR(120),
    cpf_cnpj      VARCHAR(14),
    telefone      VARCHAR(20),
    email         VARCHAR(120),
    contato       VARCHAR(60),
    cidade        VARCHAR(60),
    uf            CHAR(2),
    observacao    TEXT,
    ativo         BOOLEAN      NOT NULL DEFAULT TRUE,
    criado_em     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    atualizado_em TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_fornecedor_cpf_cnpj UNIQUE (cpf_cnpj),
    CONSTRAINT ck_fornecedor_razao    CHECK (length(btrim(razao_social)) > 0),
    CONSTRAINT ck_fornecedor_cpf_cnpj CHECK (cpf_cnpj ~ '^([0-9]{11}|[0-9A-Z]{12}[0-9]{2})$'),
    CONSTRAINT ck_fornecedor_uf       CHECK (uf ~ '^[A-Z]{2}$')
);
COMMENT ON COLUMN fornecedor.cpf_cnpj IS 'Sem máscara: 11 dígitos (CPF) ou 14 posições (CNPJ numérico ou alfanumérico). DV validado no Java.';


-- ---------------------------------------------------------------------
-- 3. Caixa e vendas (PDV)
-- ---------------------------------------------------------------------

CREATE TABLE forma_pagamento (
    id        SMALLINT    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    descricao VARCHAR(40) NOT NULL,
    tipo      VARCHAR(20) NOT NULL,
    ativa     BOOLEAN     NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_forma_pagamento_descricao UNIQUE (descricao),
    CONSTRAINT ck_forma_pagamento_tipo CHECK (tipo IN ('DINHEIRO', 'PIX', 'CARTAO_DEBITO', 'CARTAO_CREDITO', 'OUTROS'))
);
COMMENT ON COLUMN forma_pagamento.tipo IS 'Só o tipo DINHEIRO aceita troco e entra no saldo físico da gaveta.';

CREATE TABLE caixa_sessao (
    id             BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    terminal       VARCHAR(30)   NOT NULL,
    usuario_id     BIGINT        NOT NULL,
    aberto_em      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    valor_abertura NUMERIC(12,2) NOT NULL DEFAULT 0,
    status         VARCHAR(10)   NOT NULL DEFAULT 'ABERTO',
    fechado_em     TIMESTAMPTZ,
    fechado_por    BIGINT,
    valor_esperado NUMERIC(12,2),
    valor_contado  NUMERIC(12,2),
    diferenca      NUMERIC(12,2) GENERATED ALWAYS AS (valor_contado - valor_esperado) STORED,
    observacao     TEXT,
    CONSTRAINT fk_caixa_usuario     FOREIGN KEY (usuario_id)  REFERENCES usuario (id),
    CONSTRAINT fk_caixa_fechado_por FOREIGN KEY (fechado_por) REFERENCES usuario (id),
    CONSTRAINT ck_caixa_status      CHECK (status IN ('ABERTO', 'FECHADO')),
    CONSTRAINT ck_caixa_valores     CHECK (valor_abertura >= 0 AND (valor_contado IS NULL OR valor_contado >= 0)),
    CONSTRAINT ck_caixa_fechamento  CHECK (
        (status = 'ABERTO'  AND fechado_em IS NULL AND fechado_por IS NULL AND valor_contado IS NULL)
     OR (status = 'FECHADO' AND fechado_em IS NOT NULL AND fechado_por IS NOT NULL
                            AND valor_esperado IS NOT NULL AND valor_contado IS NOT NULL
                            AND fechado_em >= aberto_em))
);
CREATE UNIQUE INDEX ux_caixa_aberto_por_terminal ON caixa_sessao (terminal) WHERE status = 'ABERTO';
CREATE INDEX ix_caixa_aberto_em ON caixa_sessao (aberto_em);
COMMENT ON TABLE  caixa_sessao IS 'Abertura/fechamento de caixa por terminal. Só pode haver uma sessão ABERTA por terminal.';
COMMENT ON COLUMN caixa_sessao.valor_esperado IS 'Dinheiro esperado na gaveta (calculado no fechamento a partir de vw_caixa_resumo).';
COMMENT ON COLUMN caixa_sessao.valor_contado  IS 'Dinheiro contado pelo operador no fechamento (fechamento às cegas).';

CREATE TABLE caixa_movimento (
    id              BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    caixa_sessao_id BIGINT        NOT NULL,
    tipo            VARCHAR(10)   NOT NULL,
    valor           NUMERIC(12,2) NOT NULL,
    motivo          VARCHAR(200),
    usuario_id      BIGINT        NOT NULL,
    criado_em       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_cm_sessao  FOREIGN KEY (caixa_sessao_id) REFERENCES caixa_sessao (id),
    CONSTRAINT fk_cm_usuario FOREIGN KEY (usuario_id)      REFERENCES usuario (id),
    CONSTRAINT ck_cm_tipo    CHECK (tipo IN ('SANGRIA', 'SUPRIMENTO')),
    CONSTRAINT ck_cm_valor   CHECK (valor > 0)
);
CREATE INDEX ix_cm_sessao ON caixa_movimento (caixa_sessao_id);
COMMENT ON TABLE caixa_movimento IS 'Sangria (retirada) e suprimento (reforço de troco) da gaveta.';

CREATE TABLE venda (
    id                  BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    caixa_sessao_id     BIGINT        NOT NULL,
    usuario_id          BIGINT        NOT NULL,
    data_hora           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    status              VARCHAR(12)   NOT NULL DEFAULT 'FINALIZADA',
    valor_bruto         NUMERIC(12,2) NOT NULL,
    desconto_itens      NUMERIC(12,2) NOT NULL DEFAULT 0,
    desconto_venda      NUMERIC(12,2) NOT NULL DEFAULT 0,
    valor_total         NUMERIC(12,2) NOT NULL,
    troco               NUMERIC(12,2) NOT NULL DEFAULT 0,
    cancelada_em        TIMESTAMPTZ,
    cancelada_por       BIGINT,
    motivo_cancelamento VARCHAR(200),
    CONSTRAINT fk_venda_sessao        FOREIGN KEY (caixa_sessao_id) REFERENCES caixa_sessao (id),
    CONSTRAINT fk_venda_usuario       FOREIGN KEY (usuario_id)      REFERENCES usuario (id),
    CONSTRAINT fk_venda_cancelada_por FOREIGN KEY (cancelada_por)   REFERENCES usuario (id),
    CONSTRAINT ck_venda_status        CHECK (status IN ('FINALIZADA', 'CANCELADA')),
    CONSTRAINT ck_venda_valores       CHECK (valor_bruto >= 0 AND desconto_itens >= 0 AND desconto_venda >= 0
                                             AND valor_total >= 0 AND troco >= 0),
    CONSTRAINT ck_venda_total         CHECK (valor_total = valor_bruto - desconto_itens - desconto_venda),
    CONSTRAINT ck_venda_cancelamento  CHECK (
        (status = 'FINALIZADA' AND cancelada_em IS NULL AND cancelada_por IS NULL AND motivo_cancelamento IS NULL)
     OR (status = 'CANCELADA'  AND cancelada_em IS NOT NULL AND cancelada_por IS NOT NULL
                               AND motivo_cancelamento IS NOT NULL AND btrim(motivo_cancelamento) <> ''))
);
CREATE INDEX ix_venda_data_hora ON venda (data_hora);
CREATE INDEX ix_venda_sessao    ON venda (caixa_sessao_id);
CREATE INDEX ix_venda_usuario   ON venda (usuario_id);
COMMENT ON TABLE  venda             IS 'Venda gravada na finalização (uma transação). Não é documento fiscal; o id é o número da venda.';
COMMENT ON COLUMN venda.valor_bruto IS 'Soma de valor_bruto dos itens não cancelados.';
COMMENT ON COLUMN venda.desconto_venda IS 'Desconto no total; é rateado entre os itens em venda_item.desconto_rateado.';
COMMENT ON COLUMN venda.troco       IS 'Troco devolvido (só existe com pagamento em dinheiro).';

CREATE TABLE venda_item (
    id               BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venda_id         BIGINT        NOT NULL,
    sequencia        SMALLINT      NOT NULL,
    produto_id       BIGINT        NOT NULL,
    quantidade       NUMERIC(14,3) NOT NULL,
    preco_unitario   NUMERIC(12,2) NOT NULL,
    valor_bruto      NUMERIC(12,2) NOT NULL,
    desconto         NUMERIC(12,2) NOT NULL DEFAULT 0,
    desconto_rateado NUMERIC(12,2) NOT NULL DEFAULT 0,
    valor_liquido    NUMERIC(12,2) NOT NULL,
    custo_unitario   NUMERIC(14,4) NOT NULL,
    custo_total      NUMERIC(12,2) NOT NULL,
    promocao_id      BIGINT,
    cancelado        BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_vi_venda     FOREIGN KEY (venda_id)    REFERENCES venda (id) ON DELETE CASCADE,
    CONSTRAINT fk_vi_produto   FOREIGN KEY (produto_id)  REFERENCES produto (id),
    CONSTRAINT fk_vi_promocao  FOREIGN KEY (promocao_id) REFERENCES promocao (id),
    CONSTRAINT uq_vi_sequencia UNIQUE (venda_id, sequencia),
    CONSTRAINT ck_vi_sequencia CHECK (sequencia > 0),
    CONSTRAINT ck_vi_valores   CHECK (quantidade > 0 AND preco_unitario >= 0 AND valor_bruto >= 0
                                      AND desconto >= 0 AND desconto_rateado >= 0 AND valor_liquido >= 0
                                      AND custo_unitario >= 0 AND custo_total >= 0),
    CONSTRAINT ck_vi_liquido   CHECK (valor_liquido = valor_bruto - desconto - desconto_rateado),
    CONSTRAINT ck_vi_cancelado CHECK (NOT cancelado OR desconto_rateado = 0)
);
CREATE INDEX ix_vi_venda   ON venda_item (venda_id);
CREATE INDEX ix_vi_produto ON venda_item (produto_id);
COMMENT ON COLUMN venda_item.valor_bruto    IS 'quantidade x preco_unitario arredondado (HALF_UP, 2 casas); em item de balança é o valor impresso na etiqueta.';
COMMENT ON COLUMN venda_item.custo_unitario IS 'Custo do produto NO MOMENTO da venda. Garante que o lucro de vendas passadas não muda quando o custo muda.';
COMMENT ON COLUMN venda_item.cancelado      IS 'Item cancelado durante a venda: fica registrado para auditoria, mas não soma nem baixa estoque.';

CREATE TABLE venda_pagamento (
    id                 BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venda_id           BIGINT        NOT NULL,
    forma_pagamento_id SMALLINT      NOT NULL,
    valor              NUMERIC(12,2) NOT NULL,
    CONSTRAINT fk_vp_venda FOREIGN KEY (venda_id)           REFERENCES venda (id) ON DELETE CASCADE,
    CONSTRAINT fk_vp_forma FOREIGN KEY (forma_pagamento_id) REFERENCES forma_pagamento (id),
    CONSTRAINT ck_vp_valor CHECK (valor > 0)
);
CREATE INDEX ix_vp_venda ON venda_pagamento (venda_id);
COMMENT ON COLUMN venda_pagamento.valor IS 'Valor abatido da venda (líquido de troco). A soma dos pagamentos é igual a venda.valor_total.';


-- ---------------------------------------------------------------------
-- 4. Inventário
-- ---------------------------------------------------------------------

CREATE TABLE inventario (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    descricao    VARCHAR(80) NOT NULL,
    status       VARCHAR(10) NOT NULL DEFAULT 'ABERTO',
    observacao   TEXT,
    criado_por   BIGINT      NOT NULL,
    criado_em    TIMESTAMPTZ NOT NULL DEFAULT now(),
    aplicado_por BIGINT,
    aplicado_em  TIMESTAMPTZ,
    CONSTRAINT fk_inventario_criado_por   FOREIGN KEY (criado_por)   REFERENCES usuario (id),
    CONSTRAINT fk_inventario_aplicado_por FOREIGN KEY (aplicado_por) REFERENCES usuario (id),
    CONSTRAINT ck_inventario_descricao    CHECK (length(btrim(descricao)) > 0),
    CONSTRAINT ck_inventario_status       CHECK (status IN ('ABERTO', 'APLICADO', 'CANCELADO')),
    CONSTRAINT ck_inventario_aplicado     CHECK (status <> 'APLICADO' OR (aplicado_por IS NOT NULL AND aplicado_em IS NOT NULL))
);
CREATE INDEX ix_inventario_status ON inventario (status);

CREATE TABLE inventario_item (
    id                 BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    inventario_id      BIGINT        NOT NULL,
    produto_id         BIGINT        NOT NULL,
    quantidade_contada NUMERIC(14,3),
    estoque_sistema    NUMERIC(14,3),
    custo_unitario     NUMERIC(14,4),
    contado_em         TIMESTAMPTZ,
    contado_por        BIGINT,
    diferenca          NUMERIC(14,3) GENERATED ALWAYS AS (quantidade_contada - estoque_sistema) STORED,
    CONSTRAINT fk_ii_inventario  FOREIGN KEY (inventario_id) REFERENCES inventario (id) ON DELETE CASCADE,
    CONSTRAINT fk_ii_produto     FOREIGN KEY (produto_id)    REFERENCES produto (id),
    CONSTRAINT fk_ii_contado_por FOREIGN KEY (contado_por)   REFERENCES usuario (id),
    CONSTRAINT uq_ii_produto     UNIQUE (inventario_id, produto_id),
    CONSTRAINT ck_ii_quantidade  CHECK (quantidade_contada >= 0),
    CONSTRAINT ck_ii_contagem    CHECK (
        (quantidade_contada IS NULL AND estoque_sistema IS NULL AND custo_unitario IS NULL
                                    AND contado_em IS NULL AND contado_por IS NULL)
     OR (quantidade_contada IS NOT NULL AND estoque_sistema IS NOT NULL AND custo_unitario IS NOT NULL
                                        AND contado_em IS NOT NULL AND contado_por IS NOT NULL))
);
CREATE INDEX ix_ii_produto ON inventario_item (produto_id);
COMMENT ON COLUMN inventario_item.estoque_sistema IS 'Saldo do sistema capturado no momento em que a contagem foi digitada.';
COMMENT ON COLUMN inventario_item.diferenca       IS 'contado - sistema. Ao aplicar, gera movimento INVENTARIO com esta quantidade (não "iguala" ao contado).';


-- ---------------------------------------------------------------------
-- 5. Compras (entrada de mercadorias)
-- ---------------------------------------------------------------------

CREATE TABLE entrada_mercadoria (
    id               BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fornecedor_id    BIGINT        NOT NULL,
    numero_documento VARCHAR(20),
    data_emissao     DATE,
    valor_total_nota NUMERIC(12,2),
    status           VARCHAR(10)   NOT NULL DEFAULT 'ABERTA',
    observacao       TEXT,
    criado_por       BIGINT        NOT NULL,
    criado_em        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    confirmado_por   BIGINT,
    confirmado_em    TIMESTAMPTZ,
    CONSTRAINT fk_em_fornecedor     FOREIGN KEY (fornecedor_id)  REFERENCES fornecedor (id),
    CONSTRAINT fk_em_criado_por     FOREIGN KEY (criado_por)     REFERENCES usuario (id),
    CONSTRAINT fk_em_confirmado_por FOREIGN KEY (confirmado_por) REFERENCES usuario (id),
    CONSTRAINT ck_em_status         CHECK (status IN ('ABERTA', 'CONFIRMADA')),
    CONSTRAINT ck_em_valor_nota     CHECK (valor_total_nota >= 0),
    CONSTRAINT ck_em_confirmada     CHECK (status <> 'CONFIRMADA' OR (confirmado_por IS NOT NULL AND confirmado_em IS NOT NULL))
);
CREATE UNIQUE INDEX ux_em_documento ON entrada_mercadoria (fornecedor_id, numero_documento) WHERE numero_documento IS NOT NULL;
CREATE INDEX ix_em_criado_em ON entrada_mercadoria (criado_em);
COMMENT ON TABLE entrada_mercadoria IS 'Nota de compra. ABERTA = em digitação (pode ser excluída); CONFIRMADA = gerou estoque e custo médio (imutável).';

CREATE TABLE entrada_mercadoria_item (
    id             BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entrada_id     BIGINT        NOT NULL,
    produto_id     BIGINT        NOT NULL,
    quantidade     NUMERIC(14,3) NOT NULL,
    custo_unitario NUMERIC(14,4) NOT NULL,
    valor_total    NUMERIC(12,2) NOT NULL,
    CONSTRAINT fk_emi_entrada  FOREIGN KEY (entrada_id) REFERENCES entrada_mercadoria (id) ON DELETE CASCADE,
    CONSTRAINT fk_emi_produto  FOREIGN KEY (produto_id) REFERENCES produto (id),
    CONSTRAINT ck_emi_valores  CHECK (quantidade > 0 AND custo_unitario >= 0 AND valor_total >= 0)
);
CREATE INDEX ix_emi_entrada ON entrada_mercadoria_item (entrada_id);
CREATE INDEX ix_emi_produto ON entrada_mercadoria_item (produto_id);


-- ---------------------------------------------------------------------
-- 6. Movimentação de estoque (kardex)
-- ---------------------------------------------------------------------

CREATE TABLE movimento_estoque (
    id                 BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    produto_id         BIGINT        NOT NULL,
    tipo               VARCHAR(20)   NOT NULL,
    quantidade         NUMERIC(14,3) NOT NULL,
    saldo_anterior     NUMERIC(14,3) NOT NULL,
    saldo_posterior    NUMERIC(14,3) NOT NULL,
    custo_unitario     NUMERIC(14,4) NOT NULL,
    venda_item_id      BIGINT,
    inventario_item_id BIGINT,
    entrada_item_id    BIGINT,
    motivo             VARCHAR(200),
    usuario_id         BIGINT        NOT NULL,
    criado_em          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_me_produto         FOREIGN KEY (produto_id)         REFERENCES produto (id),
    CONSTRAINT fk_me_venda_item      FOREIGN KEY (venda_item_id)      REFERENCES venda_item (id),
    CONSTRAINT fk_me_inventario_item FOREIGN KEY (inventario_item_id) REFERENCES inventario_item (id),
    CONSTRAINT fk_me_entrada_item    FOREIGN KEY (entrada_item_id)    REFERENCES entrada_mercadoria_item (id),
    CONSTRAINT fk_me_usuario         FOREIGN KEY (usuario_id)         REFERENCES usuario (id),
    CONSTRAINT ck_me_tipo  CHECK (tipo IN ('ENTRADA_COMPRA', 'VENDA', 'CANCELAMENTO_VENDA',
                                           'AJUSTE_ENTRADA', 'AJUSTE_SAIDA', 'PERDA', 'INVENTARIO')),
    CONSTRAINT ck_me_sinal CHECK (
        (tipo IN ('ENTRADA_COMPRA', 'CANCELAMENTO_VENDA', 'AJUSTE_ENTRADA') AND quantidade > 0)
     OR (tipo IN ('VENDA', 'AJUSTE_SAIDA', 'PERDA')                        AND quantidade < 0)
     OR (tipo = 'INVENTARIO'                                                AND quantidade <> 0)),
    CONSTRAINT ck_me_origem CHECK (
        (tipo IN ('VENDA', 'CANCELAMENTO_VENDA')
            AND venda_item_id IS NOT NULL AND inventario_item_id IS NULL AND entrada_item_id IS NULL)
     OR (tipo = 'INVENTARIO'
            AND inventario_item_id IS NOT NULL AND venda_item_id IS NULL AND entrada_item_id IS NULL)
     OR (tipo = 'ENTRADA_COMPRA'
            AND entrada_item_id IS NOT NULL AND venda_item_id IS NULL AND inventario_item_id IS NULL)
     OR (tipo IN ('AJUSTE_ENTRADA', 'AJUSTE_SAIDA', 'PERDA')
            AND motivo IS NOT NULL AND btrim(motivo) <> ''
            AND venda_item_id IS NULL AND inventario_item_id IS NULL AND entrada_item_id IS NULL)),
    CONSTRAINT ck_me_saldo CHECK (saldo_posterior = saldo_anterior + quantidade),
    CONSTRAINT ck_me_custo CHECK (custo_unitario >= 0)
);
CREATE INDEX        ix_me_produto_data   ON movimento_estoque (produto_id, criado_em);
CREATE INDEX        ix_me_criado_em      ON movimento_estoque (criado_em);
CREATE INDEX        ix_me_usuario        ON movimento_estoque (usuario_id);
-- impedem baixar/estornar o mesmo item duas vezes (idempotência)
CREATE UNIQUE INDEX ux_me_venda_item     ON movimento_estoque (venda_item_id, tipo) WHERE venda_item_id IS NOT NULL;
CREATE UNIQUE INDEX ux_me_inventario_item ON movimento_estoque (inventario_item_id) WHERE inventario_item_id IS NOT NULL;
CREATE UNIQUE INDEX ux_me_entrada_item   ON movimento_estoque (entrada_item_id)    WHERE entrada_item_id IS NOT NULL;
COMMENT ON TABLE  movimento_estoque                IS 'Kardex: toda alteração de estoque. Imutável. O INSERT atualiza produto_estoque via trigger.';
COMMENT ON COLUMN movimento_estoque.quantidade      IS 'Com sinal: positivo entra, negativo sai.';
COMMENT ON COLUMN movimento_estoque.saldo_anterior  IS 'Preenchido pelo trigger (não informar no INSERT).';
COMMENT ON COLUMN movimento_estoque.saldo_posterior IS 'Preenchido pelo trigger (não informar no INSERT).';
COMMENT ON COLUMN movimento_estoque.custo_unitario  IS 'Se vier NULL, o trigger usa produto.custo. Na entrada de compra é o custo da nota.';


-- ---------------------------------------------------------------------
-- 7. Funções e triggers
-- ---------------------------------------------------------------------

-- 7.1 atualizado_em automático
CREATE FUNCTION fn_set_atualizado_em() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.atualizado_em := now();
    RETURN NEW;
END $$;

CREATE TRIGGER trg_usuario_atualizado_em    BEFORE UPDATE ON usuario    FOR EACH ROW EXECUTE FUNCTION fn_set_atualizado_em();
CREATE TRIGGER trg_produto_atualizado_em    BEFORE UPDATE ON produto    FOR EACH ROW EXECUTE FUNCTION fn_set_atualizado_em();
CREATE TRIGGER trg_fornecedor_atualizado_em BEFORE UPDATE ON fornecedor FOR EACH ROW EXECUTE FUNCTION fn_set_atualizado_em();

-- 7.2 produto_estoque protegido: só o trigger de movimento escreve nele
CREATE FUNCTION fn_produto_estoque_protegido() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.quantidade <> 0 THEN
            RAISE EXCEPTION 'Não é permitido apagar o estoque do produto % com saldo %.', OLD.produto_id, OLD.quantidade
                USING HINT = 'Produtos com movimentação não podem ser excluídos; inative o produto.';
        END IF;
        RETURN OLD;
    END IF;
    IF coalesce(current_setting('varesync.via_movimento', true), '') <> 'on' THEN
        RAISE EXCEPTION 'O estoque só pode ser alterado registrando um movimento de estoque.'
            USING HINT = 'Insira em movimento_estoque; o saldo de produto_estoque é atualizado automaticamente.';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_produto_estoque_protegido
    BEFORE INSERT OR UPDATE OR DELETE ON produto_estoque
    FOR EACH ROW EXECUTE FUNCTION fn_produto_estoque_protegido();

-- 7.3 produto novo nasce com saldo zero
CREATE FUNCTION fn_produto_cria_estoque() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM set_config('varesync.via_movimento', 'on', true);
    INSERT INTO produto_estoque (produto_id, quantidade) VALUES (NEW.id, 0);
    PERFORM set_config('varesync.via_movimento', 'off', true);
    RETURN NULL;
END $$;

CREATE TRIGGER trg_produto_cria_estoque
    AFTER INSERT ON produto
    FOR EACH ROW EXECUTE FUNCTION fn_produto_cria_estoque();

-- 7.4 movimento aplica o saldo (com bloqueio da linha do produto)
CREATE FUNCTION fn_movimento_aplica_saldo() RETURNS trigger
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
           atualizado_em = now()
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

CREATE TRIGGER trg_movimento_aplica_saldo
    BEFORE INSERT ON movimento_estoque
    FOR EACH ROW EXECUTE FUNCTION fn_movimento_aplica_saldo();

-- 7.5 kardex imutável
CREATE FUNCTION fn_movimento_imutavel() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Movimentos de estoque não podem ser alterados nem excluídos.'
        USING HINT = 'Para corrigir, registre um novo movimento de ajuste.';
END $$;

CREATE TRIGGER trg_movimento_imutavel
    BEFORE UPDATE OR DELETE ON movimento_estoque
    FOR EACH ROW EXECUTE FUNCTION fn_movimento_imutavel();

-- 7.6 histórico de preço e custo
CREATE FUNCTION fn_produto_historico_preco() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO produto_historico_preco
               (produto_id, preco_anterior, preco_novo, custo_anterior, custo_novo, usuario_id, criado_em)
        VALUES (NEW.id, NULL, NEW.preco_venda, NULL, NEW.custo, NEW.criado_por, NEW.criado_em);
    ELSIF NEW.preco_venda IS DISTINCT FROM OLD.preco_venda OR NEW.custo IS DISTINCT FROM OLD.custo THEN
        INSERT INTO produto_historico_preco
               (produto_id, preco_anterior, preco_novo, custo_anterior, custo_novo, usuario_id, criado_em)
        VALUES (NEW.id, OLD.preco_venda, NEW.preco_venda, OLD.custo, NEW.custo, NEW.atualizado_por, NEW.atualizado_em);
    END IF;
    RETURN NULL;
END $$;

CREATE TRIGGER trg_produto_historico_preco
    AFTER INSERT OR UPDATE OF preco_venda, custo ON produto
    FOR EACH ROW EXECUTE FUNCTION fn_produto_historico_preco();


-- ---------------------------------------------------------------------
-- 8. Views (base única para relatórios e conferências)
-- ---------------------------------------------------------------------

-- Itens vendidos que contam para faturamento e lucro:
-- só vendas FINALIZADAS e itens NÃO cancelados.
CREATE VIEW vw_venda_item_rentabilidade AS
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
       vi.promocao_id IS NOT NULL             AS em_promocao
  FROM venda v
  JOIN venda_item vi ON vi.venda_id = v.id
  JOIN produto p     ON p.id = vi.produto_id
  LEFT JOIN categoria c ON c.id = p.categoria_id
 WHERE v.status = 'FINALIZADA'
   AND NOT vi.cancelado;
COMMENT ON VIEW vw_venda_item_rentabilidade IS
    'Base de todos os relatórios de venda e lucro. Margem agregada = sum(lucro_bruto)/sum(receita)*100 (nunca média de margens).';

CREATE VIEW vw_estoque_atual AS
SELECT p.id                               AS produto_id,
       p.nome,
       p.categoria_id,
       c.nome                             AS categoria_nome,
       p.unidade,
       p.ativo,
       pe.quantidade                      AS saldo,
       p.estoque_minimo,
       p.custo,
       p.preco_venda,
       round(pe.quantidade * p.custo, 2)  AS valor_em_estoque,
       pe.quantidade < p.estoque_minimo   AS abaixo_do_minimo,
       pe.atualizado_em                   AS ultima_movimentacao
  FROM produto p
  JOIN produto_estoque pe ON pe.produto_id = p.id
  LEFT JOIN categoria c   ON c.id = p.categoria_id;

-- Deve retornar ZERO linhas. Usada em teste automatizado e na tela de diagnóstico.
CREATE VIEW vw_estoque_inconsistente AS
SELECT pe.produto_id,
       pe.quantidade                     AS saldo_registrado,
       coalesce(sum(m.quantidade), 0)    AS saldo_pelos_movimentos
  FROM produto_estoque pe
  LEFT JOIN movimento_estoque m ON m.produto_id = pe.produto_id
 GROUP BY pe.produto_id, pe.quantidade
HAVING pe.quantidade <> coalesce(sum(m.quantidade), 0);

-- Resumo financeiro de cada sessão de caixa (base do fechamento).
CREATE VIEW vw_caixa_resumo AS
SELECT s.id                                    AS caixa_sessao_id,
       s.terminal,
       s.status,
       s.usuario_id,
       s.aberto_em,
       s.fechado_em,
       s.valor_abertura,
       coalesce(cm.suprimentos, 0)             AS suprimentos,
       coalesce(cm.sangrias, 0)                AS sangrias,
       coalesce(vd.vendas_dinheiro, 0)         AS vendas_dinheiro,
       coalesce(vt.qtd_vendas, 0)              AS qtd_vendas,
       coalesce(vt.total_vendido, 0)           AS total_vendido,
       coalesce(vt.qtd_canceladas, 0)          AS qtd_canceladas,
       s.valor_abertura + coalesce(cm.suprimentos, 0) - coalesce(cm.sangrias, 0)
                        + coalesce(vd.vendas_dinheiro, 0) AS dinheiro_esperado
  FROM caixa_sessao s
  LEFT JOIN (SELECT caixa_sessao_id,
                    sum(valor) FILTER (WHERE tipo = 'SUPRIMENTO') AS suprimentos,
                    sum(valor) FILTER (WHERE tipo = 'SANGRIA')    AS sangrias
               FROM caixa_movimento
              GROUP BY caixa_sessao_id) cm ON cm.caixa_sessao_id = s.id
  LEFT JOIN (SELECT v.caixa_sessao_id, sum(vp.valor) AS vendas_dinheiro
               FROM venda v
               JOIN venda_pagamento vp ON vp.venda_id = v.id
               JOIN forma_pagamento fp ON fp.id = vp.forma_pagamento_id
              WHERE v.status = 'FINALIZADA' AND fp.tipo = 'DINHEIRO'
              GROUP BY v.caixa_sessao_id) vd ON vd.caixa_sessao_id = s.id
  LEFT JOIN (SELECT caixa_sessao_id,
                    count(*) FILTER (WHERE status = 'FINALIZADA')                  AS qtd_vendas,
                    coalesce(sum(valor_total) FILTER (WHERE status = 'FINALIZADA'), 0) AS total_vendido,
                    count(*) FILTER (WHERE status = 'CANCELADA')                   AS qtd_canceladas
               FROM venda
              GROUP BY caixa_sessao_id) vt ON vt.caixa_sessao_id = s.id;
