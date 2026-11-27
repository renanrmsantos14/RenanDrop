# Estado: RenanDrop app completo

- **Nível:** feature grande (nível 2), entregue em PRs incrementais
- **Branch:** `feature/app-completo`
- **Etapa:** 1 de 4: núcleo/protocolo (em revisão antes do PR)

## Blocos
| # | Bloco | Estado |
|---|---|---|
| 1 | Núcleo e protocolo (cripto, pareamento, transferência, retomada, mDNS, Tailscale, CLI, testes) | código e testes prontos; PR pendente |
| 2 | PC: Tauri 2, bandeja, janela, aparelhos, fila, Nemo/Explorer, autostart, tema, versão | não iniciado |
| 3 | Android: Kotlin nativo, compartilhar, tile, serviço, APK debug | não iniciado |
| 4 | Release: `release.yml` só `workflow_dispatch`, autoupdate ed25519, instaladores | não iniciado |

## Decisões
- Rust + Tauri 2 no PC; Android em Kotlin nativo (`docs/decisoes.md`).
- Tailscale só como rota; cifra e pareamento próprios. LAN primeiro, tailnet depois.
- Sem workflows em PR/push; sem merge nem release sem o Renan.

## Provas do bloco 1
- `cargo test -p renandrop-core`: 7 testes passam (pareamento, recusa de não pareado, revogação, recusa manual, adição por endereço/rota, sem Tailscale sem erro, retomada após corte) e 1 de 1 GB (`--ignored`).
- Velocidade (loopback, mesma máquina, i5-10400F sem SHA-NI, load 8 a 12 por outros processos): 1 GiB com hash igual a **67 MB/s** (15,9 s) e a **101 MB/s** (10,6 s) em duas rodadas; varia com a carga; o limite é CPU/disco do próprio PC, não a rede. **Gigabit real (~118 MB/s) não foi provado**: falta segundo aparelho.
- RAM parada de um nó: 3,2 MB (RSS), pico 4–5 MB durante envio (duas instâncias do CLI).
- Teste real com duas instâncias: pareamento, arquivo de 200 MB e pasta (212 MB, 3 arquivos), hash igual, rota `local`.
- Windows: só `cargo check` do núcleo, se o alvo existir. Android: ainda não.
