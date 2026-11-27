# Estado: RenanDrop app completo

- **Nível:** feature grande (nível 2), entregue em PRs incrementais
- **Branch:** `feature/app-completo`
- **Etapa:** 3 de 4: Android (PR 3 em rascunho; PR 1 e PR 2 abertos)
- **Próximo passo:** bloco 4 (release, autoupdate ed25519) · **sozinho**

## Blocos
| # | Bloco | Estado |
|---|---|---|
| 1 | Núcleo e protocolo (cripto, pareamento, transferência, retomada, mDNS, Tailscale, CLI, testes) | PR #1 aberto |
| 2 | PC: Tauri 2, bandeja, janela, aparelhos, fila, Nemo/Explorer, autostart, tema, versão | código pronto e provado no Linux; Windows não compilado (falta `llvm-rc`) |
| 3 | Android: Kotlin nativo, compartilhar, tile, serviço, APK debug | código pronto; compila, lint e testes JVM passam; nunca rodou num aparelho (branch `feature/app-android`) |
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

## Provas do bloco 2 (Linux, duas instâncias sob Xvfb)
- Pareamento pela UI (código de 6 dígitos), envio por `--send` e por seleção, 302 MB com hash igual a 91–114 MB/s (loopback, release, máquina carregada; gigabit real não provado).
- RAM parada (janela fechada, só motor e bandeja): PSS ~37–52 MB, USS ~24 MB. RSS ~91 MB sob Xvfb com Mesa llvmpipe; a meta de 50 MB só vale em PSS/USS.
- Ação do Nemo e `.desktop` de autostart gerados. Versão exibida `vX.Y.Z · dd/mm/aaaa HH:mm` em todas as telas.
- Qualidade: 2 passadas de simplificação + karpathy-coder + code-review high (9 correções aplicadas).
- Não provado: build do app no Windows (toolchain sem `llvm-rc`), Explorer real, Nemo em sessão real, "Mostrar na pasta" real.

## Provas do bloco 3 (Android, sem aparelho)
- `./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :protocol:test` passa (JDK 17, AGP 8.7.3, Kotlin 2.0.21): 21 testes no `app` (formatação, rótulos, ordem de endereços LAN antes de tailnet, destino digitado, pasta de recebimento, tema, versão) e 21 no `protocol` (inclui interoperabilidade com o binário Rust, retomada e recusa de não pareado).
- APK debug `v0.0.1` (`scripts/build.sh android`): 4,6 MB, assinado com a chave debug; `assembleRelease` com R8 fecha (unsigned, 0,27 MB).
- Interop Kotlin↔Rust (JVM no desktop, loopback, 300 MiB, 4 pistas, hash igual): **Kotlin→Rust 147 MB/s, Rust→Kotlin 115 MB/s**. Velocidade em Wi-Fi no celular não provada.
- Qualidade: 2 passadas (simplify com 4 agentes + karpathy-coder), sem skill de framework para Kotlin/Android, code-review high com 3 correções importantes (limite de quadro antes de autenticar, só `content://` no compartilhar, chave de deduplicação do histórico) e 2 menores.
- Módulo `android/protocol` ganhou `TransferInfo.paths` e `Node.rename` (para copiar ao destino final e renomear aparelho).
- Assinatura de release fora do repositório: `docs/android-assinatura.md`. Verificação: skill `verificar-app-android`.
- Não provado (precisa de aparelho): descoberta NSD real, serviço em segundo plano, tile, compartilhar vindo de outro app, notificações, cópia para `Downloads/RenanDrop` (MediaStore) e pasta escolhida (SAF), início no boot, Tailscale, velocidade em Wi-Fi.

## Pendências
- Instalar o APK num aparelho e rodar o roteiro da skill `verificar-app-android`.
- Bloco 4 (release, autoupdate) segue não iniciado.
