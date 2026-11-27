# Decisões do RenanDrop

## 1. PC: Rust + Tauri 2
Meta de RAM parado < 50 MB e transferência no limite da rede. Núcleo em Rust com threads e I/O bloqueante (sem runtime assíncrono: menos memória, pilha simples). A janela do Tauri só existe enquanto está aberta (criada sob demanda e destruída ao fechar); em segundo plano ficam a bandeja e o núcleo.

## 2. Android: Kotlin nativo, não Tauri 2 mobile
| | Tauri 2 mobile | Kotlin nativo |
|---|---|---|
| APK / RAM | + WebView e biblioteca nativa Rust por ABI | só o app |
| Serviço em primeiro plano, tile das Configurações rápidas, alvo de Compartilhar, NSD | plugins próprios em Kotlin de qualquer jeito | direto na plataforma |
| Cripto | RustCrypto ou JDK | JDK (P-256, HKDF via HMAC, AES-GCM, SHA-256 já estão no Android API 26+) |
| Toolchain | exige NDK + alvos Rust (não existem nesta máquina) | JDK 17 + Gradle bastam |
| Entrega do mesmo protocolo | reaproveita o núcleo | reimplementa `docs/protocolo.md` (≈ 600 linhas) |

Escolha: Kotlin. As partes que fazem o app "de celular" (compartilhar, tile, serviço, notificação) são nativas de qualquer forma; o núcleo reaproveitável é pequeno e o protocolo é especificado e testado contra o núcleo Rust. Menos peso, menos bateria e dá para compilar o APK aqui.

## 3. Segurança
- Nada de segredo compartilhado: pareamento por código curto com compromisso (resistente a quem está no meio da rede); depois disso, cada conexão autentica as chaves estáticas e usa chaves efêmeras (sigilo futuro).
- Só pareados enviam. Aparelho desconhecido é recusado no aperto de mão, antes de qualquer dado. Revogar apaga a chave do outro lado da lista.
- O app não depende do Tailscale para segurança: a mesma cifra e o mesmo pareamento valem em qualquer rota.
- O repositório é público: `.gitignore` bloqueia chaves, keystores, `.env` e segredos; as chaves de assinatura (release e atualização) ficam fora do repositório.

## 4. Rede: LAN primeiro, Tailscale como alternativa
mDNS na LAN; fora dela, pares da tailnet via `tailscale status --json` (somente leitura) e endereços adicionados à mão (IP ou nome MagicDNS). A sondagem `hello` descobre quem atende. Tenta LAN primeiro, depois tailnet. Sem Tailscale instalado, o app só usa a LAN, sem erro. O app nunca instala nem faz login no Tailscale.

## 5. Desempenho
Segmentos de 8 MiB, quadros de 256 KiB, 4 pistas TCP paralelas (1 a 8), leitura e gravação posicional sem carregar o arquivo na memória, arquivo de destino pré-alocado esparso. O SHA-256 por segmento é calculado no envio e conferido no recebimento; em CPU sem SHA-NI (como a deste PC) ele é o limite por pista, por isso o paralelismo.
