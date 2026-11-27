# Protocolo RenanDrop (v1)

Especificação para quem implementa outro cliente (o app Android em Kotlin segue este documento).
Referência de código: `core/src/{session,crypto,pairing,transfer,tailnet}.rs`. Tudo é TCP. Inteiros em big-endian.

## Primitivas (todas existem no JDK / Android API 26+)
| Uso | Algoritmo |
|---|---|
| Identidade do aparelho | par P-256 fixo; chave pública em SEC1 não comprimido (65 bytes) |
| `id` do aparelho | primeiros 16 bytes de `SHA-256(pública)`; em texto, hexadecimal (32 caracteres) |
| Acordo de chave | ECDH P-256 (segredo = coordenada X, 32 bytes) |
| Derivação | HKDF-SHA256 |
| Cifra | AES-256-GCM, etiqueta de 16 bytes, sem dados associados |
| Integridade de segmento | SHA-256 |

## Preâmbulo
Toda conexão começa com 6 bytes: `"RDRP"`, versão (`1`), tipo: `1` sessão, `2` pareamento, `3` sondagem (hello).

### Sondagem (tipo 3, sem cifra)
O servidor responde `[u32 tamanho][JSON {"id","name","v":1}]` e fecha. Serve só para achar o aparelho por IP ou nome (Tailscale e "adicionar por endereço"). Não revela segredo; o `id` já é público no mDNS.

## Pareamento (tipo 2): código curto de 6 dígitos
Mensagens em JSON com prefixo `[u32 tamanho]` (máximo 4096), campo `t`:
1. **A → B** `init{commit, name}`; `commit = hex(SHA-256(pubA ‖ nonceA))`, `nonceA` com 16 bytes aleatórios.
2. **B → A** `resp{public, nonce, name}` (hex; B revela a chave dele e o `nonceB`).
3. **A → B** `reveal{public, nonce}`; B confere o `commit`.
4. Os dois calculam `sas = SHA-256("RDSAS1" ‖ pubA ‖ pubB ‖ nonceA ‖ nonceB)`; o código mostrado é `(primeiros 4 bytes de sas como u32) mod 1.000.000`, com 6 dígitos e zeros à esquerda.
5. Cada pessoa compara o código nas duas telas e confirma. Cada lado envia `confirm{ok, mac}`:
   `mac = hex(HMAC-SHA256(HKDF(salt=sas, ikm=ECDH(minhaChave, chaveDoOutro), info="RenanDrop pair confirm"), papel ‖ ok ‖ sas))`, com `papel` = o byte ASCII `'A'` (0x41, quem iniciou) ou `'B'` (0x42, quem respondeu), `ok` = 1 byte (0/1) e `sas` os 32 bytes inteiros do hash. O MAC é comparado em tempo constante. Mensagens de pareamento: `public`, `nonce` e `mac` em hexadecimal.
6. Só se os dois `ok` forem verdadeiros e os MACs baterem, cada lado grava o outro como pareado.

O compromisso (`commit`) impede que quem está no meio escolha chaves que gerem o mesmo código; a chance de acertar é de 1 em 1.000.000 por tentativa e a pessoa vê o código divergir.

## Sessão (tipo 1): aperto de mão autenticado
Só aparelhos pareados passam. Chaves estáticas autenticam; chaves efêmeras dão sigilo futuro.
1. **Cliente → servidor**: preâmbulo + `hello = id(16) ‖ pubEfêmera(65) ‖ nonce(16)` (97 bytes).
2. **Servidor**: se o `id` não está pareado, envia `1` (`ST_NOT_PAIRED`) e fecha. Senão envia `0` + `srv = id(16) ‖ pubEfêmera(65) ‖ nonce(16)`.
3. `transcript = SHA-256(preâmbulo ‖ hello ‖ 0x00 ‖ srv)`. `ikm = ECDH(efêmeraA, efêmeraB) ‖ ECDH(estáticaA, estáticaB)`.
4. `c2s = HKDF(salt=transcript, ikm, info="RenanDrop c2s")`; `s2c = HKDF(salt=transcript, ikm, info="RenanDrop s2c")`.
5. Cada lado cifra com a chave da sua direção e envia um quadro `0xF0` com o `transcript`; o outro confere. Quem não tem a chave estática certa não consegue completar.

### Quadros cifrados
`[u32 n][tipo(1) ‖ corpo ‖ etiqueta(16)]`, `n` = tamanho do que vem depois. Nonce de 12 bytes = `0x00000000 ‖ contador u64` por direção (começa em 0, sobe a cada quadro). Máximo de 32 MiB de texto claro (cabe uma oferta com 200.000 itens). Limites da oferta: até 200.000 itens e 2^46 bytes por arquivo; fora disso o recebedor recusa.

Uma pista ociosa não é erro enquanto outra pista progride; sem progresso nenhum por 180 s a transferência falha (e o remetente tenta de novo com recuo 1, 2, 3, 5, 8, 13 e 20 s).
Tipos: `1` JSON, `2` dados, `0xF0` fim do aperto.

## Transferência
Mensagens JSON (tipo 1), campo `t` em snake_case:

| Mensagem | Sentido | Campos |
|---|---|---|
| `offer` | envia → recebe | `tid, from, items[{i,path,size,mtime,dir}], total, lanes, seg` (`seg` = 8.388.608) |
| `accept` | recebe → envia | `have` = faixas já recebidas `[arquivo, ini, fim)` em segmentos |
| `reject` | recebe → envia | `reason` |
| `join` / `joined` | pistas extras | `tid` |
| `seg` | envia → recebe | `f, s, len`; seguido de quadros de dados tipo 2 (≤ 256 KiB) somando `len` e de `seg_end{sha}` (SHA-256 em hex) |
| `seg_ack` / `seg_bad` | recebe → envia | `f, s` |
| `done` | envia → recebe | todos os segmentos foram confirmados |
| `complete` | recebe → envia | conferiu que não falta nada; só então o envio conta como concluído (senão `fail{reason}`) |
| `fail{reason}`, `cancel` | qualquer | encerram a transferência |

- Arquivos são cortados em **segmentos de 8 MiB**; cada segmento leva SHA-256 em `seg_end`; o receptor só confirma (`seg_ack`) se o hash bater (`seg_bad` → reenvia; na 4ª falha do mesmo segmento a transferência falha).
- **Paralelismo**: a primeira conexão faz `offer`; as demais (`lanes − 1`) abrem sessão e mandam `join{tid}`. Cada pista pede o próximo segmento livre; o receptor grava em posição (`pwrite`) num arquivo pré-alocado.
- **Retomada**: `tid` = primeiros 16 bytes (hex) de `SHA-256(idEnvia ‖ idRecebe ‖ para cada item: caminho ‖ 0x00 ‖ tamanho u64 ‖ mtime i64 ‖ dir u8)`, o mesmo para o mesmo conteúdo. O receptor guarda em `.renandrop-partial/<id8>-<tid>/` o bitmap de segmentos; ao receber de novo o mesmo `offer`, responde com `have` e o remetente só manda o que falta. Quedas de rede reconectam sozinhas (1, 2, 3, 5, 8, 13, 20 s).
- **Caminhos**: relativos, com `/`, incluindo a pasta de topo. O receptor recusa `..`, caminho absoluto, nomes reservados do Windows e caracteres inválidos (`sanitize_rel`). Conflito de nome vira `nome (2).ext`.
- **Pastas vazias** chegam como item `dir: true`.
- **Cancelamento**: `cancel` de qualquer lado; o receptor apaga o parcial.

## Rotas (só para exibir)
O app mostra por onde a conexão foi: `lan` (10/8, 172.16/12, 192.168/16, link-local), `tailnet` (100.64.0.0/10 ou fd7a:115c:a1e0::/48), `local` (127/8) ou `internet`. A ordem de tentativa é LAN → tailnet. A cifra e o pareamento são os mesmos em qualquer rota.

## mDNS
Serviço `_renandrop._tcp.local.`, instância = `id` (32 hex), host `rd-<12 primeiros hex>.local.`, TXT: `id`, `n` (nome), `v` (`1`). Porta padrão `47821` (sobe até 20 portas se estiver ocupada).
