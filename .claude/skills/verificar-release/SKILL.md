---
name: verificar-release
description: Verifica a assinatura ed25519, o atualizador do app do PC e o workflow de release do RenanDrop. Use ao mexer em core/src/updater.rs, app/src/updater.rs, no bloco Atualizações da UI, em .github/workflows/release.yml, scripts/release.sh ou docs/release.md, antes de commit ou PR.
---

# Verificar release e atualizador (RenanDrop)

Rode da raiz. Nunca imprima nem copie a chave privada; nunca rode `gh release create`, `gh secret set` nem dispare o workflow. `/home` costuma estar cheio: apague `target/debug` ao terminar. Use `pkill -x renandrop`, nunca `pkill -f`.

1. **Núcleo**: `cargo test -p renandrop-core updater` (assinatura válida, adulterada, outra chave, versões, asset por plataforma, JSON da API, chave embutida parseável).
2. **CLI** (chave de TESTE em pasta temporária, não a de release): `renandrop-cli keygen <t>/k` imprime só a pública e recusa sobrescrever; `sign <arq> --key <t>/k --version 9.9.9` cria `<arq>.sig`; `verify <arq> --version 9.9.9 --pub <pública>` sai 0; outra `--version` ou arquivo alterado sai 1.
3. **App**: `cargo test -p renandrop` (10 testes: assinatura boa instala; adulterado, sem `.sig`, outra chave, `.sig` lixo, versão não maior, binário antigo com tag falsa e `file://` em modo https são recusados; troca estilo Windows e troca Linux sobre exe aberto; relançamento filtra argumentos).
4. **Real sob Xvfb** (build debug): crie `latest.json` falso (`tag_name` maior que a versão atual, `assets` com `renandrop-linux-x86_64` e `.sig` em `file://`), assine um script de teste com a chave de TESTE e abra o app com `RENANDROP_UPDATE_URL` e `RENANDROP_UPDATE_PUBKEY`. Esperado em ~20 s: "Versão X disponível", ponto na engrenagem; "Instalar e reiniciar" troca o exe (modo 755) e reabre com `--relaunch`. Adultere o binário depois de assinar: mensagem "assinatura inválida", exe intacto (md5 igual), sem reinício. Print claro e escuro em `docs/features/app-completo/prints/`.
5. **Segurança**: a chave privada não está no repositório (`git status --short`, `git ls-files | rg -i 'key|pem|jks'`, `git check-ignore -v ~/.config/RenanDrop-release/ed25519.key` deve dizer que está fora); `ls -ld`/`stat` da pasta 700 e do arquivo 600; a chave pública do código é a de `docs/release.md`.
6. **Workflow**: `python3 -c "import yaml;d=yaml.safe_load(open('.github/workflows/release.yml'));print(d[True].keys())"` deve listar só `workflow_dispatch`; `rg -n 'secrets\.' .github` só referencia `RELEASE_ED25519_KEY` e `ANDROID_*`; nenhum valor literal de segredo; `RELEASE_ED25519_KEY` só em `env:` do passo "Assinar, conferir e publicar" (nunca no nível do job). `bash -n scripts/release.sh`. Marque como "não executado" no PR.
7. **Versão**: `app/version.json`, `app/Cargo.toml`, `app/tauri.conf.json` e `Cargo.lock` iguais e maiores que a última tag.

Não provável sem ambiente: Windows real (troca do exe, `curl.exe`), API real do GitHub, workflow rodando, assinatura do APK no CI. Diga-o no PR.
