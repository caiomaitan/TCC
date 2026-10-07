-- =====================================================================
-- VareSync — V2: dados de referência
-- Não cria usuários: o primeiro supervisor é criado pelo assistente de
-- primeiro acesso do sistema (evita senha padrão conhecida).
-- =====================================================================

INSERT INTO permissao (codigo, descricao) VALUES
    ('PRODUTOS',              'Produtos, categorias e promoções'),
    ('FORNECEDORES',          'Cadastro de fornecedores'),
    ('COMPRAS',               'Entrada de mercadorias'),
    ('ESTOQUE',               'Consulta, ajustes e perdas de estoque'),
    ('INVENTARIO',            'Inventário'),
    ('PDV',                   'Registrar vendas e operar o caixa'),
    ('SANGRIA_SUPRIMENTO',    'Registrar sangria e suprimento'),
    ('CANCELAR_VENDA',        'Cancelar venda já finalizada'),
    ('DESCONTO_ACIMA_LIMITE', 'Conceder desconto acima do limite do operador'),
    ('RELATORIOS',            'Relatórios e indicadores financeiros (inclui o painel de lucro)');

INSERT INTO unidade_medida (sigla, descricao, fracionada) VALUES
    ('UN',  'Unidade',    FALSE),
    ('KG',  'Quilograma', TRUE),
    ('L',   'Litro',      TRUE),
    ('M',   'Metro',      TRUE),
    ('CX',  'Caixa',      FALSE),
    ('PCT', 'Pacote',     FALSE),
    ('FD',  'Fardo',      FALSE),
    ('DZ',  'Dúzia',      FALSE);

INSERT INTO forma_pagamento (descricao, tipo) VALUES
    ('Dinheiro',          'DINHEIRO'),
    ('PIX',               'PIX'),
    ('Cartão de débito',  'CARTAO_DEBITO'),
    ('Cartão de crédito', 'CARTAO_CREDITO'),
    ('Vale-alimentação',  'OUTROS');

INSERT INTO categoria (nome) VALUES
    ('Mercearia'),
    ('Bebidas'),
    ('Hortifruti'),
    ('Frios e laticínios'),
    ('Açougue'),
    ('Padaria'),
    ('Limpeza'),
    ('Higiene e perfumaria'),
    ('Outros');

INSERT INTO configuracao (chave, valor, descricao) VALUES
    ('empresa.nome',                        'Minha Loja', 'Nome exibido no sistema, relatórios e comprovante'),
    ('empresa.documento',                   '',           'CNPJ/CPF da loja (opcional)'),
    ('empresa.endereco',                    '',           'Endereço para o comprovante'),
    ('empresa.telefone',                    '',           'Telefone para o comprovante'),
    ('venda.permitir_estoque_negativo',     'true',       'true = vende sem saldo e só avisa; false = bloqueia a venda'),
    ('venda.desconto_maximo_operador_pct',  '5',          'Desconto máximo (%) sem liberação de supervisor'),
    ('venda.cancelar_item_exige_supervisor','false',      'true = cancelar item no PDV pede liberação de supervisor'),
    ('relatorio.margem_minima_pct',         '15',         'Abaixo desta margem o produto é considerado de baixa rentabilidade'),
    ('relatorio.dias_sem_giro',             '30',         'Dias sem venda para o produto entrar no relatório de produtos sem giro'),
    ('balanca.ativo',                       'false',      'Lê etiquetas de balança (EAN-13 com prefixo) no PDV'),
    ('balanca.prefixo',                     '2',          'Primeiro dígito das etiquetas de balança'),
    ('balanca.digitos_codigo',              '5',          'Quantidade de dígitos do PLU na etiqueta (4, 5 ou 6)'),
    ('balanca.tipo_valor',                  'PRECO',      'PRECO = etiqueta traz o valor total em centavos; PESO = traz o peso em gramas'),
    ('backup.pasta',                        '',           'Pasta dos backups (vazio = pasta padrão do usuário)'),
    ('backup.pg_dump',                      '',           'Caminho do pg_dump (vazio = detectar automaticamente)'),
    ('backup.manter_ultimos',               '15',         'Quantidade de backups automáticos mantidos'),
    ('comprovante.rodape',                  'Obrigado pela preferência!', 'Mensagem no fim do comprovante não fiscal');
