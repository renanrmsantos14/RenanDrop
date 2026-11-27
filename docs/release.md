# Release e atualização automática

O app do PC se atualiza sozinho a partir do último release do GitHub, e só instala o que estiver assinado com a chave do Renan. O repositório é público: a chave **pública** está no código; a **privada** nunca entra no repositório.

## Chave pública (embutida em `core/src/updater.rs`)

```
d79d8471bbe6e7793a0113f33fb630dcf09022fa0faa386b5f820b9fb359f2e7
```

Chave privada: `~/.config/RenanDrop-release/ed25519.key` (pasta 700, arquivo 600, hex). Faça backup num cofre (gerenciador de senhas): sem ela não há como assinar um release que os apps já instalados aceitem.

## O que o app verifica

1. Consulta `https://api.github.com/repos/renanrmsantos14/RenanDrop/releases/latest` via `curl` (ao abrir, a cada 6 h e pelo botão "Procurar agora"). Ignora rascunho e pré-lançamento.
2. A tag (`vX.Y.Z`) tem de ser **maior** que a versão em execução. Igual ou menor é descartada.
3. Baixa o binário da plataforma (`renandrop-linux-x86_64` ou `renandrop-windows-x86_64.exe`) e o `<nome>.sig`.
4. A assinatura ed25519 (`verify_strict`) é conferida contra a chave embutida. A mensagem assinada é
   `"renandrop-update-v1\n" + versão + "\n" + nome-do-asset + "\n" + SHA-256(binário)`.
   Por isso um binário antigo assinado não passa por "versão nova", e o binário do Windows não vale no Linux.
5. Assinatura ausente, inválida ou de outra chave: o arquivo é descartado, nada é instalado e a tela de Atualizações mostra o aviso.
6. Instalação: Linux grava `<exe>.new` e renomeia sobre o executável (atômico, modo 755). Windows renomeia o atual para `.old`, grava o novo e, se falhar, desfaz; o `.old` é apagado na próxima abertura. Depois o app reabre (`--relaunch`, espera a instância antiga liberar a porta de controle).
7. Há transferências em andamento: o app pergunta ("Esperar" ou "Instalar mesmo assim").

Configurações > "Atualizações": versão atual, estado, "Procurar agora", "Instalar e reiniciar" e a opção "Procurar atualizações automaticamente" (padrão ligada). Desligada, só o botão procura.

Em builds de desenvolvimento (`debug_assertions`) dá para trocar a origem e a chave com `RENANDROP_UPDATE_URL` (aceita `file://`) e `RENANDROP_UPDATE_PUBKEY`. Em release essas variáveis são ignoradas e só `https` é aceito.

## Gerar a chave (uma vez)

```bash
mkdir -p -m 700 ~/.config/RenanDrop-release
cargo run -p renandrop-core --bin renandrop-cli -- keygen ~/.config/RenanDrop-release/ed25519.key
```

`keygen` nunca sobrescreve, grava a privada com modo 600 e imprime só a pública. Cole a pública em `PUBLIC_KEY_HEX` (`core/src/updater.rs`) e neste arquivo.

## Assinar e conferir

```bash
renandrop-cli sign   <arquivo> --key ~/.config/RenanDrop-release/ed25519.key --version X.Y.Z   # cria <arquivo>.sig
renandrop-cli verify <arquivo> --version X.Y.Z                                                # usa a chave embutida
```

O nome do arquivo precisa ser o nome do asset (`renandrop-linux-x86_64`, `renandrop-windows-x86_64.exe`), porque ele entra na mensagem assinada.

## Publicar

A versão do app é a de `app/version.json` (sobe com `scripts/build.sh`; o `release.yml` recusa versão que não seja maior que a última tag).

**A. Pelo workflow** (Actions > Release > Run workflow; só dispara à mão, nunca em push ou PR). Compila Linux (ubuntu-22.04) e Windows, assina com o segredo, confere com a chave embutida e cria o release com `renandrop-linux-x86_64`, `.sig`, `renandrop-windows-x86_64.exe`, `.sig`, `RenanDrop.apk` (se houver keystore) e `SHA256SUMS`. A opção "rascunho" cria o release sem torná-lo visível para o atualizador; fora da `main` só rascunho é aceito.

**B. À mão** (assina e confere só o Linux; para `--publicar` é obrigatório informar `RENANDROP_WIN_EXE`, senão os apps do Windows mostram erro de atualização; o APK é opcional):

```bash
scripts/build.sh              # sobe o PATCH e compila
scripts/release.sh            # assina e confere em dist/vX.Y.Z (não publica)
scripts/release.sh --publicar # também roda gh release create
```

## O que o Renan faz uma vez (na interface do GitHub; a IA não cadastra segredos)

Settings > Secrets and variables > Actions > New repository secret:

| Segredo | Valor |
|---|---|
| `RELEASE_ED25519_KEY` | conteúdo de `~/.config/RenanDrop-release/ed25519.key` (hex, 64 caracteres) |
| `ANDROID_KEYSTORE_B64` | `base64 -w0 <keystore>.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | senha da keystore |
| `ANDROID_KEY_ALIAS` | alias da chave |
| `ANDROID_KEY_PASSWORD` | senha da chave |

Sem os `ANDROID_*` o workflow avisa e publica sem o APK. Sem `RELEASE_ED25519_KEY` ele falha antes de publicar.

## Android

O APK sai como asset `RenanDrop.apk`, assinado com uma keystore guardada **fora** do repositório (ver `docs/android-assinatura.md`). Neste bloco a atualização do APK é **manual**: baixar o `RenanDrop.apk` do release e instalar por cima (a mesma keystore é obrigatória, senão o Android recusa). O app Android não procura atualizações.

## Rotação da chave

1. Gere um novo par (`keygen` em outro arquivo) e publique um release **assinado com a chave antiga** que já embute a chave nova em `PUBLIC_KEY_HEX`. Quem atualiza passa a confiar na nova.
2. Só depois troque o segredo `RELEASE_ED25519_KEY` e os próximos releases com a chave nova.
3. Se a privada vazar: a rotação acima não é segura (o atacante assina também). Gere outra, publique um release manual avisando e peça a reinstalação manual do app; revogue o segredo antigo.

## Limites conhecidos

- `releases/latest` sem autenticação tem limite de 60 consultas por hora por IP; a cada 6 h não chega perto.
- Quem tiver a privada controla todos os apps instalados: trate-a como senha.
- Assinatura prova origem e integridade, não protege contra um release legítimo com defeito: o app não faz rollback automático.
