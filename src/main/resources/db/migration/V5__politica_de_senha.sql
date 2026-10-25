-- =====================================================================
-- VareSync — V5: robustez e segurança, parte B (Fase 10: F10.6)
--   Configurações da política de senha (seção 0.4 das ampliações). Valem para senhas novas e trocadas; as senhas
--   já gravadas continuam valendo até a próxima troca. Os padrões repetem a regra de antes (6.1): ao menos 6
--   caracteres, sem exigir letras e números.
--   As unidades de medida (F10.5) e as demais entregas da parte B não mudam o banco.
-- =====================================================================

INSERT INTO configuracao (chave, valor, descricao) VALUES
    ('seguranca.senha_tamanho_minimo', '6', 'Tamanho mínimo das senhas novas e trocadas (6 a 50)'),
    ('seguranca.senha_letra_e_numero', 'false', 'Senhas novas e trocadas precisam ter letras e números');
