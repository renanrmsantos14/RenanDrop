# RenanDrop

App pessoal leve da família Renan* (RenanTalk, RenanSkip): envia arquivos e pastas entre os seus PCs (Linux e Windows) e o celular Android, pela rede local ou pelo Tailscale. Só aparelhos pareados enviam; tudo trafega cifrado.

Versão `v0.0.0` (o PATCH sobe a cada build de publicação). O app do PC procura atualizações no último release do GitHub e só instala binário com assinatura válida (`docs/release.md`).

## Estrutura
| Pasta | O que é |
|---|---|
| `core/` | Núcleo em Rust: protocolo, cripto, pareamento, transferência, descoberta (mDNS + Tailscale), `renandrop-cli` |
| `docs/protocolo.md` | Especificação do protocolo (base do app Android) |
| `docs/release.md` | Release assinado (ed25519), atualização automática do app do PC, workflow `release.yml` (só manual), segredos e rotação de chave |
| `docs/decisoes.md` | Decisões (stack, Android, segurança, rede, desempenho) |
| `docs/features/app-completo/estado.md` | Estado da entrega por blocos (PRs) |

## Testes
```bash
cargo test -p renandrop-core                                   # pareamento, recusa, pasta/arquivo, retomada, rotas
cargo test --release -p renandrop-core --test engine um_gigabyte -- --ignored --nocapture   # 1 GB + MB/s
```

## Segurança do repositório
Público. Chaves, keystores, `.env` e segredos nunca entram (ver `.gitignore`). As chaves de assinatura ficam fora do repositório.
