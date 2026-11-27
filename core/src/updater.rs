//! Atualização assinada: assinatura ed25519, comparação de versões, escolha do arquivo por
//! plataforma e leitura do JSON de `releases/latest` do GitHub. Sem rede aqui: quem baixa é o app.
//!
//! O que se assina não é só o arquivo: a mensagem amarra versão e nome do arquivo ao conteúdo
//! (`signed_message`). Assim um binário antigo (e legitimamente assinado) não serve como
//! "atualização" nem um arquivo de outra plataforma passa por este.

use anyhow::{anyhow, bail, Context, Result};
use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine as _;
use ed25519_dalek::{Signature, Signer, SigningKey, VerifyingKey};
use serde::Deserialize;

use crate::crypto;

/// Chave pública de verificação das atualizações (32 bytes em hex). A privada nunca entra no
/// repositório; ver `docs/release.md`.
pub const PUBLIC_KEY_HEX: &str = "d79d8471bbe6e7793a0113f33fb630dcf09022fa0faa386b5f820b9fb359f2e7";

const DOMAIN: &[u8] = b"renandrop-update-v1\n";

pub fn parse_key(hex_text: &str) -> Result<[u8; 32]> {
    let b = hex::decode(hex_text.trim()).context("chave não está em hex")?;
    b.try_into()
        .map_err(|_| anyhow!("a chave deve ter 32 bytes"))
}

pub fn public_key() -> [u8; 32] {
    parse_key(PUBLIC_KEY_HEX).expect("PUBLIC_KEY_HEX inválida")
}

/// Par novo: (privada, pública), ambas em hex.
pub fn generate_keypair() -> (String, String) {
    let sk = SigningKey::from_bytes(&crypto::random_bytes::<32>());
    (
        hex::encode(sk.to_bytes()),
        hex::encode(sk.verifying_key().to_bytes()),
    )
}

/// Mensagem que a assinatura cobre: domínio, versão (sem `v`), nome do arquivo e SHA-256 do conteúdo.
pub fn signed_message(version: &str, asset: &str, content: &[u8]) -> Vec<u8> {
    let mut m = DOMAIN.to_vec();
    m.extend_from_slice(version.trim_start_matches('v').as_bytes());
    m.push(b'\n');
    m.extend_from_slice(asset.as_bytes());
    m.push(b'\n');
    m.extend_from_slice(&crypto::sha256(&[content]));
    m
}

/// Assina a mensagem de `signed_message`; devolve os 64 bytes da assinatura em base64.
pub fn sign_asset(secret: &[u8; 32], version: &str, asset: &str, content: &[u8]) -> String {
    let m = signed_message(version, asset, content);
    B64.encode(SigningKey::from_bytes(secret).sign(&m).to_bytes())
}

/// `true` só com assinatura base64 de 64 bytes válida para esta versão, arquivo e conteúdo.
pub fn verify_asset(
    pubkey: &[u8; 32],
    version: &str,
    asset: &str,
    content: &[u8],
    sig_b64: &str,
) -> bool {
    let Ok(raw) = B64.decode(sig_b64.trim()) else {
        return false;
    };
    let Ok(raw) = <[u8; 64]>::try_from(raw.as_slice()) else {
        return false;
    };
    let Ok(vk) = VerifyingKey::from_bytes(pubkey) else {
        return false;
    };
    // `verify_strict` recusa assinaturas maleáveis e chaves de ordem pequena.
    vk.verify_strict(
        &signed_message(version, asset, content),
        &Signature::from_bytes(&raw),
    )
    .is_ok()
}

/// `1.2.3` ou `v1.2.3`; qualquer outra forma (pré-lançamento, partes a mais) não é versão válida.
pub fn parse_version(v: &str) -> Option<(u64, u64, u64)> {
    let mut it = v.trim().trim_start_matches('v').split('.');
    let mut n = || {
        let p = it.next()?;
        (!p.is_empty() && p.bytes().all(|b| b.is_ascii_digit()))
            .then(|| p.parse().ok())
            .flatten()
    };
    let r = (n()?, n()?, n()?);
    it.next().is_none().then_some(r)
}

/// `candidate` é estritamente maior que `current`. Versão ilegível nunca é "mais nova".
pub fn is_newer(candidate: &str, current: &str) -> bool {
    match (parse_version(candidate), parse_version(current)) {
        (Some(a), Some(b)) => a > b,
        _ => false,
    }
}

/// Nome do arquivo do release para a plataforma (`std::env::consts`).
pub fn platform_asset(os: &str, arch: &str) -> Option<&'static str> {
    match (os, arch) {
        ("linux", "x86_64") => Some("renandrop-linux-x86_64"),
        ("windows", "x86_64") => Some("renandrop-windows-x86_64.exe"),
        _ => None,
    }
}

pub fn current_platform_asset() -> Option<&'static str> {
    platform_asset(std::env::consts::OS, std::env::consts::ARCH)
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct Asset {
    pub name: String,
    #[serde(rename = "browser_download_url")]
    pub url: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Release {
    /// Sem o `v` inicial.
    pub version: String,
    pub assets: Vec<Asset>,
}

/// Atualização pronta para baixar: o binário e a assinatura dele.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Update {
    pub version: String,
    pub asset: String,
    pub url: String,
    pub sig_url: String,
}

#[derive(Deserialize)]
struct RawRelease {
    tag_name: String,
    #[serde(default)]
    draft: bool,
    #[serde(default)]
    prerelease: bool,
    #[serde(default)]
    assets: Vec<Asset>,
}

pub fn parse_release(json: &str) -> Result<Release> {
    let r: RawRelease = serde_json::from_str(json).context("resposta do release ilegível")?;
    if r.draft || r.prerelease {
        bail!("o último release não é definitivo");
    }
    if parse_version(&r.tag_name).is_none() {
        bail!("versão do release inválida: {}", r.tag_name);
    }
    Ok(Release {
        version: r.tag_name.trim_start_matches('v').to_string(),
        assets: r.assets,
    })
}

/// `None` se o release não é mais novo que `current`. Erro se é mais novo mas falta o binário
/// da plataforma ou a assinatura dele (nesse caso nada deve ser instalado).
pub fn find_update(rel: &Release, current: &str, asset: &str) -> Result<Option<Update>> {
    if !is_newer(&rel.version, current) {
        return Ok(None);
    }
    let find = |n: &str| rel.assets.iter().find(|a| a.name == n);
    let bin = find(asset).ok_or_else(|| anyhow!("o release {} não tem {asset}", rel.version))?;
    let sig_name = format!("{asset}.sig");
    let sig = find(&sig_name)
        .ok_or_else(|| anyhow!("o release {} não tem a assinatura {sig_name}", rel.version))?;
    Ok(Some(Update {
        version: rel.version.clone(),
        asset: asset.to_string(),
        url: bin.url.clone(),
        sig_url: sig.url.clone(),
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn par() -> ([u8; 32], [u8; 32]) {
        let (s, p) = generate_keypair();
        (parse_key(&s).unwrap(), parse_key(&p).unwrap())
    }

    #[test]
    fn assinatura_valida_invalida_e_adulterada() {
        let (sk, pk) = par();
        let conteudo = b"binario novo".to_vec();
        let sig = sign_asset(&sk, "0.0.3", "renandrop-linux-x86_64", &conteudo);
        assert!(verify_asset(
            &pk,
            "0.0.3",
            "renandrop-linux-x86_64",
            &conteudo,
            &sig
        ));
        // v inicial na versão não muda o que foi assinado.
        assert!(verify_asset(
            &pk,
            "v0.0.3",
            "renandrop-linux-x86_64",
            &conteudo,
            &sig
        ));
        // Conteúdo adulterado.
        let mut ruim = conteudo.clone();
        ruim[0] ^= 1;
        assert!(!verify_asset(
            &pk,
            "0.0.3",
            "renandrop-linux-x86_64",
            &ruim,
            &sig
        ));
        // Outra versão (binário antigo reaproveitado), outro arquivo (outra plataforma).
        assert!(!verify_asset(
            &pk,
            "0.0.4",
            "renandrop-linux-x86_64",
            &conteudo,
            &sig
        ));
        assert!(!verify_asset(
            &pk,
            "0.0.3",
            "renandrop-windows-x86_64.exe",
            &conteudo,
            &sig
        ));
        // Outra chave.
        let (_, outra) = par();
        assert!(!verify_asset(
            &outra,
            "0.0.3",
            "renandrop-linux-x86_64",
            &conteudo,
            &sig
        ));
        // Lixo no lugar da assinatura.
        for s in [
            "",
            "não é base64!",
            "AAAA",
            &B64.encode([0u8; 64]),
            &B64.encode([7u8; 63]),
        ] {
            assert!(!verify_asset(
                &pk,
                "0.0.3",
                "renandrop-linux-x86_64",
                &conteudo,
                s
            ));
        }
        // Assinatura com um bit trocado.
        let mut raw = B64.decode(&sig).unwrap();
        raw[10] ^= 0x80;
        assert!(!verify_asset(
            &pk,
            "0.0.3",
            "renandrop-linux-x86_64",
            &conteudo,
            &B64.encode(raw)
        ));
        // Espaço/quebra de linha no fim do arquivo .sig é tolerado.
        assert!(verify_asset(
            &pk,
            "0.0.3",
            "renandrop-linux-x86_64",
            &conteudo,
            &format!("{sig}\n")
        ));
    }

    #[test]
    fn chave_publica_embutida_e_valida() {
        let pk = public_key();
        assert!(VerifyingKey::from_bytes(&pk).is_ok());
    }

    #[test]
    fn versoes() {
        assert!(is_newer("v0.0.3", "0.0.2"));
        assert!(is_newer("0.1.0", "0.0.9"));
        assert!(is_newer("1.0.0", "0.99.99"));
        assert!(is_newer("0.0.10", "0.0.9")); // numérico, não por texto
        assert!(!is_newer("0.0.2", "0.0.2"));
        assert!(!is_newer("0.0.1", "0.0.2"));
        assert!(!is_newer("lixo", "0.0.2"));
        assert!(!is_newer("0.0.3-rc1", "0.0.2"));
        assert!(!is_newer("0.0.3", "dev"));
        assert_eq!(parse_version("v1.2.3"), Some((1, 2, 3)));
        assert_eq!(parse_version("1.2"), None);
        assert_eq!(parse_version("1.2.3.4"), None);
        assert_eq!(parse_version("1..3"), None);
        assert_eq!(parse_version("+1.2.3"), None);
    }

    #[test]
    fn asset_por_plataforma() {
        assert_eq!(
            platform_asset("linux", "x86_64"),
            Some("renandrop-linux-x86_64")
        );
        assert_eq!(
            platform_asset("windows", "x86_64"),
            Some("renandrop-windows-x86_64.exe")
        );
        assert_eq!(platform_asset("macos", "aarch64"), None);
        assert_eq!(platform_asset("linux", "aarch64"), None);
    }

    const JSON: &str = r#"{
      "tag_name": "v0.0.3", "draft": false, "prerelease": false, "name": "RenanDrop 0.0.3",
      "assets": [
        {"name": "renandrop-linux-x86_64", "browser_download_url": "https://x/l", "size": 1},
        {"name": "renandrop-linux-x86_64.sig", "browser_download_url": "https://x/l.sig"},
        {"name": "renandrop-windows-x86_64.exe", "browser_download_url": "https://x/w"},
        {"name": "RenanDrop.apk", "browser_download_url": "https://x/a"}
      ]}"#;

    #[test]
    fn json_do_release_e_escolha() {
        let r = parse_release(JSON).unwrap();
        assert_eq!(r.version, "0.0.3");
        assert_eq!(r.assets.len(), 4);
        let u = find_update(&r, "0.0.2", "renandrop-linux-x86_64")
            .unwrap()
            .unwrap();
        assert_eq!(u.url, "https://x/l");
        assert_eq!(u.sig_url, "https://x/l.sig");
        // Mesma versão ou mais nova instalada: nada a fazer.
        assert!(find_update(&r, "0.0.3", "renandrop-linux-x86_64")
            .unwrap()
            .is_none());
        assert!(find_update(&r, "0.1.0", "renandrop-linux-x86_64")
            .unwrap()
            .is_none());
        // Windows existe mas não tem .sig: erro, nunca instala sem assinatura.
        let e = find_update(&r, "0.0.2", "renandrop-windows-x86_64.exe").unwrap_err();
        assert!(e.to_string().contains(".sig"), "{e}");
        // Plataforma sem binário.
        assert!(find_update(&r, "0.0.2", "renandrop-linux-aarch64").is_err());
    }

    #[test]
    fn json_invalido_ou_nao_definitivo() {
        assert!(parse_release("{}").is_err());
        assert!(parse_release("não é json").is_err());
        assert!(parse_release(r#"{"tag_name":"v1.0","assets":[]}"#).is_err());
        assert!(parse_release(r#"{"tag_name":"v1.0.0","prerelease":true}"#).is_err());
        assert!(parse_release(r#"{"tag_name":"v1.0.0","draft":true}"#).is_err());
        // Sem assets: parse ok, mas só falha se for mais nova.
        let r = parse_release(r#"{"tag_name":"v1.0.0"}"#).unwrap();
        assert!(find_update(&r, "1.0.0", "x").unwrap().is_none());
        assert!(find_update(&r, "0.9.0", "x").is_err());
    }
}
