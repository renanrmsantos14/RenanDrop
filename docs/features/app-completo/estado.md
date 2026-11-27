# Estado: RenanDrop app completo

- **Nível:** feature grande (nível 2), entregue em PRs incrementais
- **Branch:** `feature/app-completo`
- **Etapa:** 4 de 4: release e autoupdate (PR #4 em rascunho, branch `feature/app-release`)
- **Próximo passo:** Renan revisa os PRs #1 a #4 em ordem, cadastra os segredos no GitHub (`docs/release.md`) e roda o primeiro release como rascunho · **com você**

## Blocos
| # | Bloco | Estado |
|---|---|---|
| 1 | Núcleo e protocolo (cripto, pareamento, transferência, retomada, mDNS, Tailscale, CLI, testes) | PR #1 aberto |
| 2 | PC: Tauri 2, bandeja, janela, aparelhos, fila, Nemo/Explorer, autostart, tema, versão | código pronto e provado no Linux; Windows não compilado (falta `llvm-rc`) |
| 3 | Android: Kotlin nativo, compartilhar, tile, serviço, APK debug | código pronto; compila, lint e testes JVM passam; nunca rodou num aparelho (branch `feature/app-android`) |
| 4 | Release: `release.yml` só `workflow_dispatch`, autoupdate ed25519 | código pronto e provado no Linux com release falso e chave de teste; workflow nunca executado |

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

## Provas do bloco 4 (release e autoupdate, Linux)
- Núcleo: módulo `updater` (ed25519 `verify_strict`, mensagem com prefixo de domínio + versão + nome do asset + SHA-256, semver, escolha do asset por plataforma, JSON de `releases/latest`); CLI `keygen`, `sign`, `verify`. 6 testes novos em `renandrop-core`.
- App: `app/src/updater.rs` (busca por `curl`, limites de tamanho, `--proto =https`, verificação, troca do executável, relançamento). 10 testes: assinatura boa instala; binário adulterado, sem `.sig`, outra chave, `.sig` lixo, versão não maior e binário antigo com tag falsa são recusados; troca estilo Windows (`.old`) e troca Linux sobre executável aberto, ambas testadas em Linux.
- Teste real sob Xvfb (build debug, `releases/latest` falso por `file://` e chave de TESTE): checagem automática mostrou "Versão 0.0.3 disponível"; "Instalar e reiniciar" trocou o executável (modo 755), reabriu com `--relaunch` e o processo antigo saiu. Com o binário adulterado depois de assinado: "Atualização não instalada: assinatura inválida", executável intacto, sem reinício.
- Prints (claro e escuro, disponível, em dia, erro de assinatura, engrenagem com aviso): `docs/features/app-completo/prints/12` a `16`.
- Chave de release gerada com o CLI em `~/.config/RenanDrop-release/` (fora do repositório, 600); pública em `docs/release.md`.
- Qualidade: 2 passadas de simplificação (4 agentes) + karpathy-coder + code-review high (5 correções: `release.sh` exige binário mais novo que `version.json` e o `.exe` ao publicar, install recusa durante verificação, caret preservado no redesenho, `Accept` da API só na consulta). Segredo `RELEASE_ED25519_KEY` só no passo de assinatura; `release.sh --publicar` com as mesmas travas do workflow.
- Prova real refeita com o código final (Xvfb, release falso, chave de TESTE): "Versão 0.0.4 disponível", instalação trocou o executável (modo 755) e reabriu; com o binário adulterado: "assinatura inválida", executável intacto.
- Workflow `.github/workflows/release.yml`: YAML validado com `yaml.safe_load`, **não executado**.
- Não provado: build e troca do executável num Windows real (incluindo `curl.exe`), chamada real à API do GitHub (TLS, limite de taxa), o `release.yml` rodando (inclusive assinatura do APK no CI), laço real de 6 h, atualização do APK (manual neste bloco).
- Desvio da especificação: a assinatura cobre versão + nome do asset + SHA-256 do binário (não só o conteúdo), então `sign` e `verify` exigem `--version`.

## Pendências
- Instalar o APK num aparelho e rodar o roteiro da skill `verificar-app-android`.
- Cadastrar no GitHub os segredos `RELEASE_ED25519_KEY` e `ANDROID_*` (ver `docs/release.md`) e fazer backup da chave privada.
- Primeiro release de verdade: rodar o workflow como rascunho antes (a primeira execução do `release.yml` ainda não aconteceu).
