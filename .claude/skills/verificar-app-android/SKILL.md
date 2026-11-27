---
name: verificar-app-android
description: Verifica o app Android do RenanDrop (Kotlin nativo, módulo android/app: serviço, descoberta NSD, compartilhar, tile, notificações, MediaStore, versão, APK). Use ao mexer em android/ (app, protocol, gradle) ou em scripts/bump-version.py e scripts/build.sh, antes de commit ou PR.
---

# Verificar app Android (RenanDrop)

Rode de `android/`. Sempre filtre a saída. `/home` costuma estar quase cheio: apague `android/app/build` e `android/protocol/build` ao terminar.

## Ambiente
- `export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2` (JDK 17; o Gradle 8.9 do wrapper baixa em `~/.gradle`).
- SDK em `~/Android/Sdk`, apontado por `android/local.properties` (`sdk.dir=...`, fora do Git). Nada é instalado fora do worktree e de `~/.gradle`.
- A primeira execução precisa de rede (dependências); depois use `--offline`.

## Provas que rodam aqui (sem aparelho)
1. **Compila, lint e testes**:
   `./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :protocol:test 2>&1 | grep -E "^e: |FAIL|BUILD|Lint found|What went"`
   O lint tem `abortOnError`: qualquer erro derruba o build. Relatório em `app/build/reports/lint-results-debug.html`.
2. **Testes**: `app` (formatação, rótulos de estado/rota, ordem de endereços, destino digitado, pasta de recebimento, tema, versão) e `protocol` (interoperabilidade com o binário Rust, retomada, recusa de não pareado). Contagem em `app/build/test-results` e `protocol/build/test-results`.
3. **R8 do release**: `./gradlew :app:assembleRelease` gera `app-release-unsigned.apk` sem chave (só prova que o R8 fecha).
4. **APK**: `app/build/outputs/apk/debug/app-debug.apk` (assinado com a chave debug padrão). Tamanho: `ls -l`. Manifesto: `~/Android/Sdk/build-tools/<v>/aapt2 dump badging <apk>` (pacote `dev.renandrop.app`, `versionName`, permissões).
5. **Versão**: `scripts/build.sh android` roda `scripts/bump-version.py --android` (sobe o PATCH de `android/version.properties` com data em America/Sao_Paulo; não mexe na versão do PC) e depois `assembleDebug`. `versionName` do APK tem de bater com o arquivo. A tela mostra `v0.0.N · dd/mm/aaaa HH:mm` (BuildConfig).

## Release assinado
Chave fora do repositório, por variáveis (`RENANDROP_KEYSTORE`, `RENANDROP_KEYSTORE_PASSWORD`, `RENANDROP_KEY_ALIAS`, `RENANDROP_KEY_PASSWORD`) ou `~/.config/RenanDrop-android/keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Sem isso o release sai sem assinatura. Nunca commitar `.jks`, senha ou `keystore.properties` (o `.gitignore` já bloqueia; confira `git status`).

## O que NÃO se prova sem aparelho
Descoberta NSD real e MulticastLock, serviço em primeiro plano em segundo plano (Doze, bateria), tile na barra de configurações, folha de compartilhar vinda de outro app, notificações (Aceitar/Recusar, Abrir, Mostrar na pasta), cópia para `Downloads/RenanDrop` via MediaStore e pasta escolhida (SAF), início no boot, Tailscale instalado, velocidade em Wi-Fi real. Diga "não provado" no PR.

## Com aparelho (adb)
- `adb devices`; `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- Abrir: `adb shell am start -n dev.renandrop.app/.MainActivity`. Compartilhar: `adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "teste" -n dev.renandrop.app/.ShareActivity`.
- Log sem segredos: `adb logcat -d | grep -i renandrop | tail -50`. Serviço vivo: `adb shell dumpsys activity services dev.renandrop.app | head`.
- Fluxo mínimo: o aparelho aparece em "Encontrados" no PC e vice-versa → Parear → mesmo código de 6 dígitos nos dois → "É o mesmo código" → enviar um arquivo do PC (hash igual com `sha256sum` e `adb shell sha256sum /sdcard/Download/RenanDrop/<arquivo>`) e do celular pela folha de compartilhar.
- Tile: puxe a barra de configurações, adicione "RenanDrop", toque com um aparelho online. Boot: ative "Iniciar com o aparelho", reinicie e confira a notificação do serviço.
- Tela sem aparelho online: sem Tailscale o app não mostra erro (só rede local). Teste "Adicionar por nome ou IP" com `100.x.x.x`.
- Prints: `adb exec-out screencap -p > /tmp/x.png`.
