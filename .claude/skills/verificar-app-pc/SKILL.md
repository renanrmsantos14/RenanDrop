---
name: verificar-app-pc
description: Verifica o app do PC do RenanDrop (Tauri: janela, bandeja, instância única, --send/menu de contexto Nemo, fila, configurações, tema, versão, RAM parado). Use ao mexer em app/ (src, ui, scripts, tauri.conf.json) antes de commit ou PR.
---

# Verificar app do PC (RenanDrop)

Rode da raiz do repositório. Sempre filtre a saída. `/home` costuma estar quase cheio: apague `target/debug` ao terminar.

1. **Compila**: `cargo build -p renandrop 2>&1 | tail -3` (sem warnings); `cargo clippy -p renandrop -- -D warnings`; `node --check app/ui/app.js`.
2. **Duas instâncias sob Xvfb** (`Xvfb :99 &`, `DISPLAY=:99`): perfis, portas e `--ctrl-port` separados e `--no-integration` (não escreve no HOME):
   `renandrop --profile <A> --port 47821 --ctrl-port 47819 --name "PC Casa" --recv <rA> --no-integration` e a B com 47831/47820/"Notebook".
   Nunca use `pkill -f renandrop` (mata o próprio shell); use `pkill -x renandrop`.
3. **Fluxo mínimo**: os dois aparecem em "Encontrados" → Parear → o mesmo código de 6 dígitos nos dois → "É o mesmo código" nos dois → ambos em "Pareados".
4. **Menu de contexto / --send**: `renandrop --profile <B> --ctrl-port 47820 --no-integration --send <arquivo> <pasta>` com a instância B viva abre os itens preparados na janela dela (não cria outra instância). Enviar → aba Transferências mostra progresso, MB/s e rota; ao concluir os itens preparados NÃO reaparecem; o arquivo chega em `<rA>` com hash igual (`sha256sum`).
5. **Configurações**: mudar o tema aplica na hora; clicar no fundo escuro com alteração abre "Descartar alterações?"; Descartar reverte o tema; salvar persiste.
6. **Versão**: canto inferior direito `v<versão> · dd/mm/aaaa HH:mm` em todas as telas, sem cobrir botões. `app/version.json`, `app/Cargo.toml`, `app/tauri.conf.json` e `Cargo.lock` com a mesma versão.
7. **RAM parado** (release, `--hidden`): `ps -o rss= -p <pid>` somando os processos filhos (`pgrep -P`); meta < 50 MB. Com janela aberta mede-se à parte e diz-se no PR.
8. **Integração**: sem `--no-integration`, em HOME temporário (`HOME=<tmp> XDG_DATA_HOME=...`), o `renandrop.nemo_action` deve existir com `Exec="…/renandrop" --send %F`. Windows: `cargo check -p renandrop --target x86_64-pc-windows-msvc` (sem toolchain, vai como "não provado").

Imprima prints com `import -window root`. Falha de pareamento ou hash: capture o log (`<perfil>/../*.log`) antes de afirmar causa.
