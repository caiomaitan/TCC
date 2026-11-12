-- =====================================================================
-- VareSync — V22: assistente "Primeiros passos" (Fase 21, parte C: F21.4)
--   O que o sistema não consegue conferir sozinho fica em três configurações (seção 0.4 das ampliações):
--   formas de pagamento revisadas pelo supervisor ("Revisei"), o passo Usuários dispensado ("Só eu uso o sistema")
--   e o passo Balança dispensado ("Não uso balança"). Os outros passos são conferidos nos dados (loja, produtos,
--   usuários, balança ligada e backup feito).
-- =====================================================================

INSERT INTO configuracao (chave, valor, descricao) VALUES
    ('assistente.formas_revisadas',     'false', 'Primeiros passos: formas de pagamento revisadas pelo supervisor'),
    ('assistente.usuarios_dispensado',  'false', 'Primeiros passos: "Só eu uso o sistema" (dispensa o passo Usuários)'),
    ('assistente.balanca_dispensada',   'false', 'Primeiros passos: "Não uso balança" (dispensa o passo Balança)');
