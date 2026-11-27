# Assinatura do app Android

O APK debug usa a chave debug padrão do Android (gerada pelo SDK em `~/.android/debug.keystore`). Ela serve só para testar.

Para um APK de release assinado, a chave fica **fora do repositório** (que é público). O Gradle procura, nesta ordem:

1. Variáveis de ambiente: `RENANDROP_KEYSTORE` (caminho do arquivo), `RENANDROP_KEYSTORE_PASSWORD`, `RENANDROP_KEY_ALIAS`, `RENANDROP_KEY_PASSWORD`.
2. O arquivo `~/.config/RenanDrop-android/keystore.properties`, com as chaves `storeFile`, `storePassword`, `keyAlias` e `keyPassword`.

Sem nenhum dos dois, `assembleRelease` gera `app-release-unsigned.apk`.

Como criar a chave (uma vez, fora do repositório, com o JDK):

```
mkdir -p ~/.config/RenanDrop-android && chmod 700 ~/.config/RenanDrop-android
keytool -genkeypair -v -keystore ~/.config/RenanDrop-android/release.jks -alias renandrop -keyalg RSA -keysize 4096 -validity 10000
```

Guarde o arquivo e as senhas num gerenciador de senhas e faça backup: perder a chave impede atualizar o app instalado. Nunca commite `.jks`, senhas ou `keystore.properties`; o `.gitignore` já bloqueia esses nomes.
