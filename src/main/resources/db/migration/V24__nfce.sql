-- =====================================================================
-- VareSync — V24: NFC-e em homologação (Fase 23, parte A: F23.1)
-- Cria de uma vez todas as tabelas e colunas da fase (as partes B, C e D só as usam):
--   1. nfce_numeracao: o próximo número de cada série, reservado com FOR UPDATE (F23.3).
--   2. nfce: uma NFC-e por número, com a situação, a resposta da SEFAZ, o XML (assinado e, depois da autorização, o
--      nfeProc), o QR Code e a trava da transmissão (F23.5). Só homologação: CHECK ambiente = 2.
--   3. nfce_evento: cancelamento (110111) e cancelamento por substituição (110112).
--   4. nfce_inutilizacao: faixas de números inutilizados.
--   5. venda.consumidor_documento: o CPF ou o CNPJ na nota, só dígitos.
--   6. vw_nfce_inconsistente: NFC-e autorizada de venda cancelada e NFC-e cancelada de venda finalizada (precisa
--      ficar vazia).
--   7. Configurações empresa.* (endereço do emitente) e nfce.* da seção 0.4 das ampliações.
-- O certificado digital NÃO fica no banco: ele é do terminal, no ~/.varesync/config.properties (F23.2).
-- Os XML ficam no banco e entram no backup: a lei manda guardar as notas por 5 anos.
-- =====================================================================

-- 1. Numeração ------------------------------------------------------------------------------

CREATE TABLE nfce_numeracao (
    serie          SMALLINT PRIMARY KEY,
    proximo_numero INTEGER  NOT NULL DEFAULT 1,
    CONSTRAINT ck_nfce_numeracao_serie  CHECK (serie BETWEEN 0 AND 889),
    CONSTRAINT ck_nfce_numeracao_numero CHECK (proximo_numero BETWEEN 1 AND 999999999)
);
COMMENT ON TABLE nfce_numeracao IS 'Próximo número de NFC-e de cada série; a reserva trava a linha (FOR UPDATE), devolve o número e soma 1. A série é criada no primeiro uso.';

-- 2. NFC-e ----------------------------------------------------------------------------------

CREATE TABLE nfce (
    id               BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venda_id         BIGINT       NOT NULL,
    serie            SMALLINT     NOT NULL,
    numero           INTEGER      NOT NULL,
    codigo_numerico  CHAR(8)      NOT NULL,
    chave            CHAR(44)     NOT NULL,
    tipo_emissao     SMALLINT     NOT NULL,
    ambiente         SMALLINT     NOT NULL DEFAULT 2,
    emitida_em       TIMESTAMPTZ  NOT NULL,
    contingencia_em  TIMESTAMPTZ,
    status           VARCHAR(12)  NOT NULL DEFAULT 'PENDENTE',
    cstat            VARCHAR(3),
    motivo           TEXT,
    protocolo        VARCHAR(20),
    autorizada_em    TIMESTAMPTZ,
    xml              TEXT,
    qrcode           TEXT,
    substituta_id    BIGINT,
    transmitindo_ate TIMESTAMPTZ,
    transmitindo_por VARCHAR(50),
    tentativas       INTEGER      NOT NULL DEFAULT 0,
    criado_em        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    atualizado_em    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_nfce_venda       FOREIGN KEY (venda_id)      REFERENCES venda (id),
    CONSTRAINT fk_nfce_substituta  FOREIGN KEY (substituta_id) REFERENCES nfce (id),
    CONSTRAINT uq_nfce_chave       UNIQUE (chave),
    CONSTRAINT uq_nfce_numero      UNIQUE (serie, numero),
    CONSTRAINT ck_nfce_serie       CHECK (serie BETWEEN 0 AND 889),
    CONSTRAINT ck_nfce_numero      CHECK (numero BETWEEN 1 AND 999999999),
    CONSTRAINT ck_nfce_codigo      CHECK (codigo_numerico ~ '^[0-9]{8}$'),
    CONSTRAINT ck_nfce_chave       CHECK (chave ~ '^[0-9]{44}$'),
    CONSTRAINT ck_nfce_tipo_emissao CHECK (tipo_emissao IN (1, 9)),
    CONSTRAINT ck_nfce_ambiente    CHECK (ambiente = 2),
    CONSTRAINT ck_nfce_status      CHECK (status IN ('PENDENTE', 'AUTORIZADA', 'REJEITADA', 'DENEGADA', 'SEM_RESPOSTA',
                                                     'CONTINGENCIA', 'CANCELADA', 'SUBSTITUIDA', 'INUTILIZADA')),
    CONSTRAINT ck_nfce_contingencia CHECK ((tipo_emissao = 9) = (contingencia_em IS NOT NULL)),
    CONSTRAINT ck_nfce_autorizada  CHECK (status NOT IN ('AUTORIZADA', 'CANCELADA', 'SUBSTITUIDA')
                                          OR (protocolo IS NOT NULL AND autorizada_em IS NOT NULL)),
    CONSTRAINT ck_nfce_tentativas  CHECK (tentativas >= 0)
);
-- Por venda, no máximo uma NFC-e que vale (as SEM_RESPOSTA, SUBSTITUIDA, INUTILIZADA, CANCELADA e DENEGADA ficam de fora).
CREATE UNIQUE INDEX ux_nfce_venda_vigente ON nfce (venda_id)
    WHERE status IN ('PENDENTE', 'AUTORIZADA', 'CONTINGENCIA', 'REJEITADA');
CREATE INDEX ix_nfce_status ON nfce (status, criado_em);
CREATE TRIGGER trg_nfce_atualizado_em BEFORE UPDATE ON nfce FOR EACH ROW EXECUTE FUNCTION fn_set_atualizado_em();
COMMENT ON TABLE nfce IS 'NFC-e (modelo 65) emitidas só em homologação (F23). O XML é o assinado e, depois da autorização, o nfeProc.';
COMMENT ON COLUMN nfce.emitida_em IS 'dhEmi da nota (no XML, com o fuso).';
COMMENT ON COLUMN nfce.contingencia_em IS 'dhCont: início da contingência off-line (só no tpEmis 9).';
COMMENT ON COLUMN nfce.substituta_id IS 'A NFC-e de contingência que tomou o lugar desta (SEM_RESPOSTA → SUBSTITUIDA ou INUTILIZADA).';
COMMENT ON COLUMN nfce.transmitindo_ate IS 'Trava da transmissão (F23.5): quem pegou a nota a envia até este horário.';

-- 3. Eventos --------------------------------------------------------------------------------

CREATE TABLE nfce_evento (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nfce_id       BIGINT       NOT NULL,
    tipo          VARCHAR(30)  NOT NULL,
    justificativa VARCHAR(255) NOT NULL,
    cstat         VARCHAR(3),
    motivo        TEXT,
    protocolo     VARCHAR(20),
    xml           TEXT,
    registrado_em TIMESTAMPTZ  NOT NULL DEFAULT now(),
    usuario_id    BIGINT,
    CONSTRAINT fk_nfce_evento_nfce    FOREIGN KEY (nfce_id)    REFERENCES nfce (id),
    CONSTRAINT fk_nfce_evento_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT ck_nfce_evento_tipo    CHECK (tipo IN ('CANCELAMENTO', 'CANCELAMENTO_SUBSTITUICAO')),
    CONSTRAINT ck_nfce_evento_justificativa CHECK (length(btrim(justificativa)) BETWEEN 15 AND 255)
);
CREATE INDEX ix_nfce_evento_nfce ON nfce_evento (nfce_id);
COMMENT ON TABLE nfce_evento IS 'Eventos da NFC-e: 110111 (CANCELAMENTO) e 110112 (CANCELAMENTO_SUBSTITUICAO). usuario_id nulo = o transmissor.';

-- 4. Inutilização ---------------------------------------------------------------------------

CREATE TABLE nfce_inutilizacao (
    id             BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    serie          SMALLINT     NOT NULL,
    numero_inicial INTEGER      NOT NULL,
    numero_final   INTEGER      NOT NULL,
    ano            SMALLINT     NOT NULL,
    justificativa  VARCHAR(255) NOT NULL,
    cstat          VARCHAR(3),
    motivo         TEXT,
    protocolo      VARCHAR(20),
    xml            TEXT,
    registrada_em  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    usuario_id     BIGINT,
    CONSTRAINT fk_nfce_inut_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT ck_nfce_inut_serie   CHECK (serie BETWEEN 0 AND 889),
    CONSTRAINT ck_nfce_inut_faixa   CHECK (numero_inicial BETWEEN 1 AND 999999999
                                           AND numero_final BETWEEN numero_inicial AND 999999999),
    CONSTRAINT ck_nfce_inut_ano     CHECK (ano BETWEEN 0 AND 99),
    CONSTRAINT ck_nfce_inut_justificativa CHECK (length(btrim(justificativa)) BETWEEN 15 AND 255)
);
COMMENT ON TABLE nfce_inutilizacao IS 'Faixas de números de NFC-e inutilizados (ano com 2 dígitos). usuario_id nulo = o transmissor.';

-- 5. Documento do consumidor na venda -------------------------------------------------------

ALTER TABLE venda
    ADD COLUMN consumidor_documento VARCHAR(14),
    ADD CONSTRAINT ck_venda_consumidor_documento CHECK (consumidor_documento IS NULL
                                                        OR consumidor_documento ~ '^([0-9]{11}|[0-9]{14})$');
COMMENT ON COLUMN venda.consumidor_documento IS 'CPF ou CNPJ na nota (NFC-e), só dígitos; nulo = consumidor não identificado.';

-- 6. Inconsistências ------------------------------------------------------------------------

CREATE VIEW vw_nfce_inconsistente AS
SELECT n.id AS nfce_id, n.venda_id, n.serie, n.numero, n.status AS status_nfce, v.status AS status_venda,
       CASE WHEN n.status = 'AUTORIZADA' THEN 'NFC-e autorizada de venda cancelada'
            ELSE 'NFC-e cancelada de venda finalizada' END AS problema
  FROM nfce n
  JOIN venda v ON v.id = n.venda_id
 WHERE (n.status = 'AUTORIZADA' AND v.status = 'CANCELADA')
    OR (n.status = 'CANCELADA' AND v.status = 'FINALIZADA');
COMMENT ON VIEW vw_nfce_inconsistente IS 'Precisa ficar vazia (conferida no Diagnóstico e no fim dos testes de integração).';

-- 7. Configurações --------------------------------------------------------------------------

INSERT INTO configuracao (chave, valor, descricao) VALUES
    ('empresa.razao_social',            '',       'Razão social do emitente (NFC-e)'),
    ('empresa.logradouro',              '',       'Logradouro do emitente (NFC-e)'),
    ('empresa.numero',                  '',       'Número do endereço do emitente (NFC-e)'),
    ('empresa.complemento',             '',       'Complemento do endereço do emitente (NFC-e)'),
    ('empresa.bairro',                  '',       'Bairro do emitente (NFC-e)'),
    ('empresa.cep',                     '',       'CEP do emitente, 8 dígitos (NFC-e)'),
    ('empresa.municipio',               '',       'Município do emitente (NFC-e)'),
    ('empresa.codigo_municipio',        '',       'Código IBGE do município do emitente, 7 dígitos (NFC-e)'),
    ('nfce.ativa',                      'false',  'NFC-e ligada (parte C da F23)'),
    ('nfce.serie',                      '1',      'Série da NFC-e (0 a 889)'),
    ('nfce.prazo_cancelamento_minutos', '30',     'Prazo para cancelar a NFC-e, em minutos da autorização'),
    ('nfce.timeout_segundos',           '10',     'Tempo de espera da resposta da SEFAZ, em segundos'),
    ('nfce.retransmitir_minutos',       '5',      'Intervalo do transmissor das NFC-e pendentes, em minutos'),
    ('nfce.valor_identificacao',        '10000',  'Valor a partir do qual o CPF ou CNPJ na nota é obrigatório (R$)'),
    ('nfce.valor_maximo',               '200000', 'Valor a partir do qual a venda exige NF-e e não fecha (R$)'),
    ('nfce.via_estabelecimento',        'true',   'Imprimir a via do estabelecimento em contingência'),
    ('nfce.alerta_certificado_dias',    '30',     'Dias antes do vencimento do certificado em que o painel avisa'),
    ('nfce.url_qrcode',                 '',       'Endereço do QR Code (vazio = o da biblioteca para a UF)'),
    ('nfce.url_consulta',               '',       'Endereço da consulta pela chave (vazio = o da biblioteca para a UF)'),
    ('nfce.resp_tecnico_cnpj',          '',       'CNPJ do responsável técnico (vazio = grupo omitido)'),
    ('nfce.resp_tecnico_contato',       '',       'Contato do responsável técnico'),
    ('nfce.resp_tecnico_email',         '',       'E-mail do responsável técnico'),
    ('nfce.resp_tecnico_fone',          '',       'Telefone do responsável técnico');
