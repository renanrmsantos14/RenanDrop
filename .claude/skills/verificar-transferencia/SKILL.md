---
name: verificar-transferencia
description: Verifica o núcleo de transferência do RenanDrop (pareamento, recusa de não pareado, envio de pasta/arquivo, retomada, 1 GB, rotas LAN/Tailscale). Use ao mexer em core/src (protocolo, cripto, transfer, engine, tailnet) antes de commit ou PR.
---

# Verificar transferência (RenanDrop)

Rode da raiz do repositório. Sempre filtre a saída.

1. **Rápido (obrigatório)**: `cargo test -p renandrop-core 2>&1 | grep -E "^test |test result|panicked"`
   Deve mostrar: pareia e envia pasta e arquivo; aparelho não pareado recusado; revogar bloqueia; recusa manual não grava; adiciona por endereço; retoma depois de cortar a conexão (hash igual e bytes reenviados < 1,5× o arquivo).
2. **1 GB com hash independente (ao mexer em `transfer.rs`/`crypto.rs`)**: `cargo test --release -p renandrop-core --test engine um_gigabyte -- --ignored --nocapture 2>&1 | grep -E "VELOCIDADE|test result"`. Precisa ~2 GB livres em /tmp. Confira a carga (`uptime`): com load alto o MB/s não vale; diga isso no PR.
3. **Duas instâncias reais (processos separados)**: `target/debug/renandrop-cli serve --profile <dirA> --port 47901 --name A --recv <rA> --no-discovery` e a mesma com `B`/47902; comandos por stdin (`pair 127.0.0.1:47902`, `yes`, `send <id> <caminho>`). Perfis e portas sempre separados; não use `~/.config/RenanDrop`.
4. **Tailscale**: `printf 'tailscale\nquit\n' | renandrop-cli serve ...` deve imprimir `state` = `ok`, `missing`, `disconnected` ou `off` e nunca erro. Não instale nem faça login no Tailscale.
5. **Windows / Android**: `cargo check -p renandrop-core --target x86_64-pc-windows-msvc` (só o núcleo; sem toolchain MSVC o resultado vai como "não provado" no PR).

Falha em retomada ou hash: nunca apresente como passou; capture o log e o `state.json` de `.renandrop-partial/`.
