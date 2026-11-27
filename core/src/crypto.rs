//! Primitivas criptográficas do RenanDrop: identidade P-256, ECDH, HKDF-SHA256 e canal AES-256-GCM.
//! Tudo existe também no JDK/Android (API 26+), o que mantém o protocolo implementável em Kotlin.

use aes_gcm::aead::{AeadInPlace, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce};
use anyhow::{anyhow, bail, Result};
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use p256::elliptic_curve::sec1::ToEncodedPoint;
use p256::{PublicKey, SecretKey};
use rand_core::OsRng;
use sha2::{Digest, Sha256};
use std::io::{Read, Write};

pub const TAG_LEN: usize = 16;
/// Tamanho máximo do texto claro de um quadro: a oferta de uma pasta grande é um quadro só (os de dados têm até 256 KiB).
pub const MAX_PLAIN: usize = 32 << 20;

pub struct Identity {
    secret: SecretKey,
    pub_bytes: [u8; 65],
}

impl Identity {
    pub fn generate() -> Self {
        Self::from_secret(SecretKey::random(&mut OsRng))
    }

    pub fn from_secret_bytes(b: &[u8]) -> Result<Self> {
        Ok(Self::from_secret(
            SecretKey::from_slice(b).map_err(|_| anyhow!("chave privada inválida"))?,
        ))
    }

    fn from_secret(secret: SecretKey) -> Self {
        let pub_bytes = encode_pub(&secret.public_key());
        Identity { secret, pub_bytes }
    }

    pub fn secret_bytes(&self) -> Vec<u8> {
        self.secret.to_bytes().to_vec()
    }
    pub fn public(&self) -> &[u8; 65] {
        &self.pub_bytes
    }
    pub fn id(&self) -> [u8; 16] {
        id_of(&self.pub_bytes)
    }
    pub fn id_hex(&self) -> String {
        hex::encode(self.id())
    }
    pub fn ecdh(&self, peer_pub: &[u8]) -> Result<[u8; 32]> {
        ecdh(&self.secret, peer_pub)
    }
}

fn encode_pub(pk: &PublicKey) -> [u8; 65] {
    let ep = pk.to_encoded_point(false);
    let mut out = [0u8; 65];
    out.copy_from_slice(ep.as_bytes());
    out
}

pub fn id_of(pub_bytes: &[u8]) -> [u8; 16] {
    let h = Sha256::digest(pub_bytes);
    let mut id = [0u8; 16];
    id.copy_from_slice(&h[..16]);
    id
}

pub fn ecdh(secret: &SecretKey, peer_pub: &[u8]) -> Result<[u8; 32]> {
    let pk = PublicKey::from_sec1_bytes(peer_pub).map_err(|_| anyhow!("chave pública inválida"))?;
    let shared = p256::ecdh::diffie_hellman(secret.to_nonzero_scalar(), pk.as_affine());
    let mut out = [0u8; 32];
    out.copy_from_slice(shared.raw_secret_bytes());
    Ok(out)
}

/// Par efêmero: (segredo, público).
pub struct Ephemeral {
    secret: SecretKey,
    pub public: [u8; 65],
}

impl Ephemeral {
    pub fn new() -> Self {
        let secret = SecretKey::random(&mut OsRng);
        let public = encode_pub(&secret.public_key());
        Ephemeral { secret, public }
    }
    pub fn ecdh(&self, peer_pub: &[u8]) -> Result<[u8; 32]> {
        ecdh(&self.secret, peer_pub)
    }
}

impl Default for Ephemeral {
    fn default() -> Self {
        Self::new()
    }
}

pub fn random_bytes<const N: usize>() -> [u8; N] {
    use rand_core::RngCore;
    let mut b = [0u8; N];
    OsRng.fill_bytes(&mut b);
    b
}

pub fn sha256(parts: &[&[u8]]) -> [u8; 32] {
    let mut h = Sha256::new();
    for p in parts {
        h.update(p);
    }
    h.finalize().into()
}

pub fn hmac_sha256(key: &[u8], data: &[u8]) -> [u8; 32] {
    let mut m = <Hmac<Sha256> as Mac>::new_from_slice(key).expect("hmac aceita qualquer tamanho");
    m.update(data);
    m.finalize().into_bytes().into()
}

/// HKDF-SHA256 (extract + expand) para 32 bytes.
pub fn hkdf32(salt: &[u8], ikm: &[u8], info: &[u8]) -> [u8; 32] {
    let hk = Hkdf::<Sha256>::new(Some(salt), ikm);
    let mut out = [0u8; 32];
    hk.expand(info, &mut out)
        .expect("32 bytes é um tamanho válido");
    out
}

/// Estado de cifra de uma direção: nonce = 4 bytes zero + contador u64 big-endian.
pub struct Cipher {
    aead: Aes256Gcm,
    ctr: u64,
}

impl Cipher {
    pub fn new(key: &[u8; 32]) -> Self {
        Cipher {
            aead: Aes256Gcm::new(key.into()),
            ctr: 0,
        }
    }
    #[allow(deprecated)]
    fn nonce(&mut self) -> Nonce<<Aes256Gcm as aes_gcm::aead::AeadCore>::NonceSize> {
        let mut n = [0u8; 12];
        n[4..].copy_from_slice(&self.ctr.to_be_bytes());
        self.ctr += 1;
        *Nonce::from_slice(&n)
    }
    /// Cifra `buf` no lugar e devolve a etiqueta.
    pub fn seal(&mut self, buf: &mut [u8]) -> [u8; TAG_LEN] {
        let n = self.nonce();
        let tag = self
            .aead
            .encrypt_in_place_detached(&n, b"", buf)
            .expect("cifrar não falha");
        tag.into()
    }
    pub fn open(&mut self, buf: &mut [u8], tag: &[u8]) -> Result<()> {
        let n = self.nonce();
        self.aead
            .decrypt_in_place_detached(&n, b"", buf, tag.into())
            .map_err(|_| anyhow!("falha de autenticação do quadro"))
    }
}

/// Escritor de quadros cifrados: `[u32 BE n][tipo u8][corpo][etiqueta 16]`, n = 1 + corpo + 16.
pub struct SecureWriter<W: Write> {
    w: W,
    cipher: Cipher,
    buf: Vec<u8>,
}

impl<W: Write> SecureWriter<W> {
    pub fn new(w: W, key: &[u8; 32]) -> Self {
        SecureWriter {
            w,
            cipher: Cipher::new(key),
            buf: Vec::with_capacity(300 * 1024),
        }
    }
    pub fn send(&mut self, typ: u8, body: &[u8]) -> Result<()> {
        if 1 + body.len() > MAX_PLAIN {
            bail!("quadro grande demais");
        }
        self.buf.clear();
        self.buf.extend_from_slice(&[0u8; 4]);
        self.buf.push(typ);
        self.buf.extend_from_slice(body);
        let tag = self.cipher.seal(&mut self.buf[4..]);
        self.buf.extend_from_slice(&tag);
        let total = (self.buf.len() - 4) as u32;
        self.buf[..4].copy_from_slice(&total.to_be_bytes());
        self.w.write_all(&self.buf)?;
        Ok(())
    }
}

pub struct SecureReader<R: Read> {
    r: R,
    cipher: Cipher,
    buf: Vec<u8>,
}

impl<R: Read> SecureReader<R> {
    pub fn new(r: R, key: &[u8; 32]) -> Self {
        SecureReader {
            r,
            cipher: Cipher::new(key),
            buf: Vec::with_capacity(300 * 1024),
        }
    }
    /// Lê um quadro; devolve (tipo, corpo). O corpo vale até a próxima chamada.
    pub fn recv(&mut self) -> Result<(u8, &[u8])> {
        let mut len = [0u8; 4];
        self.r.read_exact(&mut len)?;
        let total = u32::from_be_bytes(len) as usize;
        if !(1 + TAG_LEN..=MAX_PLAIN + TAG_LEN).contains(&total) {
            bail!("tamanho de quadro inválido");
        }
        self.buf.resize(total, 0);
        self.r.read_exact(&mut self.buf)?;
        let (ct, tag) = self.buf.split_at_mut(total - TAG_LEN);
        self.cipher.open(ct, tag)?;
        Ok((self.buf[0], &self.buf[1..total - TAG_LEN]))
    }
}

/// Número de 6 dígitos (SAS) a partir de um hash.
pub fn sas_digits(h: &[u8; 32]) -> String {
    let n = u32::from_be_bytes([h[0], h[1], h[2], h[3]]) % 1_000_000;
    format!("{n:06}")
}
