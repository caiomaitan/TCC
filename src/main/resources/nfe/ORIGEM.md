# Schemas da NFC-e (F23.3)

Os XSD desta pasta são os do pacote **PL 010g v1.03** (leiaute 4.00, com o grupo IBSCBS da reforma tributária),
copiados do `schemas.zip` da biblioteca Java_NFe (github.com/Samuel-Oliveira/Java_NFe), no commit
`b34566fb847732df767e809ca500cc68596e3a9c` (2026-11-08), o que corresponde à versão 4.3.0 publicada no Maven Central.

- Ficou de fora só `e112120_v1.00 .xsd` (com um espaço no nome), que nenhum outro XSD usa e não é da NFC-e.
- `schemas.lst` lista os arquivos: na validação, o sistema copia cada um para `~/.varesync/schemas/PL_010g_v1.03`
  (a biblioteca e o validador leem de uma pasta, e não de dentro do jar). Um teste confere que a lista bate com a pasta.
- Para trocar de pacote: substituir os arquivos, regerar `schemas.lst` (um nome por linha) e mudar
  `ValidacaoXsd.VERSAO_PACOTE`.
