#!/usr/bin/env bash
# Assina e (opcionalmente) publica um release à mão. Alternativa ao workflow release.yml.
#   scripts/release.sh                  assina o binário Linux já compilado e confere; não publica
#   scripts/release.sh --publicar       idem e cria o release no GitHub (gh release create)
# Variáveis: RENANDROP_RELEASE_KEY (padrão ~/.config/RenanDrop-release/ed25519.key),
#            RENANDROP_WIN_EXE (caminho do .exe compilado no Windows, opcional),
#            RENANDROP_APK (APK já assinado, opcional).
# A versão vem de app/version.json: rode scripts/build.sh antes (sobe o PATCH e compila).
set -euo pipefail
cd "$(dirname "$0")/.."

key="${RENANDROP_RELEASE_KEY:-$HOME/.config/RenanDrop-release/ed25519.key}"
[ -f "$key" ] || { echo "chave de assinatura não encontrada em $key (veja docs/release.md)"; exit 1; }
[ "$(stat -c %a "$key")" = "600" ] || { echo "a chave deve ter permissão 600"; exit 1; }

versao=$(python3 -c "import json; print(json.load(open('app/version.json'))['version'])")
bin=target/release/renandrop
[ -f "$bin" ] || { echo "falta $bin: rode scripts/build.sh"; exit 1; }

# O binário tem de ter sido compilado depois da versão atual (senão o atualizador entra em laço).
[ "$bin" -nt app/version.json ] || { echo "$bin é mais antigo que app/version.json: rode scripts/build.sh"; exit 1; }

cargo build --release -p renandrop-core --bin renandrop-cli
cli=target/release/renandrop-cli

saida="dist/v$versao"
rm -rf "$saida"; mkdir -p "$saida"
cp "$bin" "$saida/renandrop-linux-x86_64"
if [ -n "${RENANDROP_WIN_EXE:-}" ]; then cp "$RENANDROP_WIN_EXE" "$saida/renandrop-windows-x86_64.exe"
elif [ "${1:-}" = "--publicar" ]; then echo "sem RENANDROP_WIN_EXE: os apps do Windows mostrariam erro de atualização com este release (use o workflow release.yml)"; exit 1; fi
[ -n "${RENANDROP_APK:-}" ] && cp "$RENANDROP_APK" "$saida/RenanDrop.apk"

arquivos=()
for f in "$saida"/renandrop-*; do
  "$cli" sign "$f" --key "$key" --version "$versao"
  "$cli" verify "$f" --version "$versao"
  arquivos+=("$f" "$f.sig")
done
[ -f "$saida/RenanDrop.apk" ] && arquivos+=("$saida/RenanDrop.apk")
sums=()
for f in "${arquivos[@]}"; do [[ "$f" == *.sig ]] || sums+=("$(basename "$f")"); done
(cd "$saida" && sha256sum "${sums[@]}" > SHA256SUMS)
arquivos+=("$saida/SHA256SUMS")

echo "Versão $versao assinada em $saida"
if [ "${1:-}" = "--publicar" ]; then
  # Mesmas travas do release.yml.
  [ "$(git rev-parse --abbrev-ref HEAD)" = "main" ] || { echo "release definitivo só a partir da main"; exit 1; }
  commit=$(git rev-parse HEAD)
  git fetch -q origin main
  git merge-base --is-ancestor "$commit" origin/main || { echo "o commit $commit não está no origin/main; faça push antes"; exit 1; }
  # A versão embutida tem de ser maior que a última tag, senão o atualizador entra em laço.
  ultima=$(git tag -l 'v*' --sort=-v:refname | head -n1)
  if [ -n "$ultima" ]; then
    python3 - "$versao" "${ultima#v}" <<'PY'
import sys
nova, antiga = (tuple(int(p) for p in v.split(".")) for v in sys.argv[1:3])
if nova <= antiga:
    sys.exit(f"a versão {sys.argv[1]} não é maior que a última tag v{sys.argv[2]}; rode scripts/build.sh")
PY
  fi
  if gh release view "v$versao" >/dev/null 2>&1; then echo "o release v$versao já existe"; exit 1; fi
  gh release create "v$versao" "${arquivos[@]}" --target "$commit" --title "RenanDrop $versao" --generate-notes
else
  echo "Nada publicado. Para publicar: scripts/release.sh --publicar"
fi
