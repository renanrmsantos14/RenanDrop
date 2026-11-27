//! Handshake autenticado (sessão) e mensagens do protocolo. Especificação: docs/protocolo.md.

use crate::crypto::*;
use anyhow::{anyhow, bail, Result};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use std::io::{Read, Write};
use std::net::TcpStream;

pub const MAGIC: &[u8; 4] = b"RDRP";
pub const VERSION: u8 = 1;
pub const KIND_SESSION: u8 = 1;
pub const KIND_PAIR: u8 = 2;
/// Sondagem: "quem atende aqui?" (id e nome públicos, sem cifra).
pub const KIND_HELLO: u8 = 3;
pub const T_JSON: u8 = 1;
pub const T_DATA: u8 = 2;
const T_FINISHED: u8 = 0xF0;

pub const ST_OK: u8 = 0;
pub const ST_NOT_PAIRED: u8 = 1;

pub type Reader = SecureReader<TcpStream>;
pub type Writer = SecureWriter<TcpStream>;

#[derive(Debug)]
pub struct NotPaired;
impl std::fmt::Display for NotPaired {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "aparelho não pareado")
    }
}
impl std::error::Error for NotPaired {}

pub fn write_preamble(s: &mut impl Write, kind: u8) -> Result<()> {
    s.write_all(MAGIC)?;
    s.write_all(&[VERSION, kind])?;
    Ok(())
}

/// Lê "RDRP", versão e tipo (6 bytes).
pub fn read_preamble(s: &mut impl Read) -> Result<u8> {
    let mut p = [0u8; 6];
    s.read_exact(&mut p)?;
    if &p[..4] != MAGIC {
        bail!("não é o protocolo RenanDrop");
    }
    if p[4] != VERSION {
        bail!("versão de protocolo {} não suportada", p[4]);
    }
    Ok(p[5])
}

/// Quadro JSON sem cifra (`[u32 BE n][json]`), usado antes de haver chaves: pareamento e sondagem.
pub fn write_plain<T: Serialize>(s: &mut impl Write, m: &T) -> Result<()> {
    let b = serde_json::to_vec(m)?;
    let mut frame = Vec::with_capacity(4 + b.len());
    frame.extend_from_slice(&(b.len() as u32).to_be_bytes());
    frame.extend_from_slice(&b);
    s.write_all(&frame)?;
    s.flush()?;
    Ok(())
}

/// Lê um quadro de `write_plain`; `max` limita o tamanho aceito.
pub fn read_plain<T: DeserializeOwned>(s: &mut impl Read, max: usize) -> Result<T> {
    let mut l = [0u8; 4];
    s.read_exact(&mut l)?;
    let n = u32::from_be_bytes(l) as usize;
    if n == 0 || n > max {
        bail!("mensagem inválida");
    }
    let mut b = vec![0u8; n];
    s.read_exact(&mut b)?;
    Ok(serde_json::from_slice(&b)?)
}

fn keys(dh_ee: &[u8; 32], dh_ss: &[u8; 32], transcript: &[u8; 32]) -> ([u8; 32], [u8; 32]) {
    let mut ikm = [0u8; 64];
    ikm[..32].copy_from_slice(dh_ee);
    ikm[32..].copy_from_slice(dh_ss);
    (
        hkdf32(transcript, &ikm, b"RenanDrop c2s"),
        hkdf32(transcript, &ikm, b"RenanDrop s2c"),
    )
}

fn split(s: TcpStream, k_read: &[u8; 32], k_write: &[u8; 32]) -> Result<(Reader, Writer)> {
    let r = s.try_clone()?;
    Ok((SecureReader::new(r, k_read), SecureWriter::new(s, k_write)))
}

/// Cliente: o preâmbulo (kind=1) ainda não foi enviado. Devolve (leitor, escritor).
pub fn client_handshake(
    mut s: TcpStream,
    me: &Identity,
    peer_pub: &[u8],
) -> Result<(Reader, Writer)> {
    let eph = Ephemeral::new();
    let nonce: [u8; 16] = random_bytes();
    let mut hello = Vec::with_capacity(97);
    hello.extend_from_slice(&me.id());
    hello.extend_from_slice(&eph.public);
    hello.extend_from_slice(&nonce);
    let mut pre = Vec::new();
    write_preamble(&mut pre, KIND_SESSION)?;
    s.write_all(&pre)?;
    s.write_all(&hello)?;
    s.flush()?;

    let mut status = [0u8; 1];
    s.read_exact(&mut status)?;
    if status[0] == ST_NOT_PAIRED {
        return Err(NotPaired.into());
    }
    if status[0] != ST_OK {
        bail!("o aparelho recusou a conexão (código {})", status[0]);
    }
    let mut srv = [0u8; 97];
    s.read_exact(&mut srv)?;
    if srv[..16] != id_of(peer_pub) {
        bail!("identidade inesperada do aparelho");
    }
    let transcript = sha256(&[&pre, &hello, &[ST_OK], &srv]);
    let dh_ee = eph.ecdh(&srv[16..81])?;
    let dh_ss = me.ecdh(peer_pub)?;
    let (c2s, s2c) = keys(&dh_ee, &dh_ss, &transcript);
    let (mut r, mut w) = split(s, &s2c, &c2s)?;
    w.send(T_FINISHED, &transcript)?;
    let (t, body) = r
        .recv()
        .map_err(|_| anyhow!("o aparelho não confirmou a chave (não pareado?)"))?;
    if t != T_FINISHED || body != transcript {
        bail!("confirmação de chave inválida");
    }
    Ok((r, w))
}

/// Servidor: o preâmbulo já foi lido. `lookup` devolve a chave pública de um aparelho pareado.
pub fn server_handshake(
    mut s: TcpStream,
    me: &Identity,
    lookup: impl Fn(&[u8; 16]) -> Option<Vec<u8>>,
) -> Result<([u8; 16], Reader, Writer)> {
    let mut hello = [0u8; 97];
    s.read_exact(&mut hello)?;
    let mut cid = [0u8; 16];
    cid.copy_from_slice(&hello[..16]);
    let peer_pub = match lookup(&cid) {
        Some(p) => p,
        None => {
            let _ = s.write_all(&[ST_NOT_PAIRED]);
            return Err(NotPaired.into());
        }
    };
    let eph = Ephemeral::new();
    let nonce: [u8; 16] = random_bytes();
    let mut srv = Vec::with_capacity(97);
    srv.extend_from_slice(&me.id());
    srv.extend_from_slice(&eph.public);
    srv.extend_from_slice(&nonce);
    s.write_all(&[ST_OK])?;
    s.write_all(&srv)?;
    s.flush()?;
    let mut pre = Vec::new();
    write_preamble(&mut pre, KIND_SESSION)?;
    let transcript = sha256(&[&pre, &hello, &[ST_OK], &srv]);
    let dh_ee = eph.ecdh(&hello[16..81])?;
    let dh_ss = me.ecdh(&peer_pub)?;
    let (c2s, s2c) = keys(&dh_ee, &dh_ss, &transcript);
    let (mut r, mut w) = split(s, &c2s, &s2c)?;
    let (t, body) = r
        .recv()
        .map_err(|_| anyhow!("confirmação de chave inválida (aparelho não pareado?)"))?;
    if t != T_FINISHED || body != transcript {
        bail!("confirmação de chave inválida");
    }
    w.send(T_FINISHED, &transcript)?;
    Ok((cid, r, w))
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct Item {
    pub i: u32,
    /// Caminho relativo com `/`, incluindo a pasta de topo.
    pub path: String,
    pub size: u64,
    /// mtime em ms desde a época (0 = desconhecido).
    #[serde(default)]
    pub mtime: i64,
    /// Pasta (vazia) a recriar.
    #[serde(default)]
    pub dir: bool,
}

/// Faixa de segmentos já recebidos de um arquivo: [a, b).
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct Range(pub u32, pub u32, pub u32); // arquivo, início, fim

#[derive(Serialize, Deserialize, Debug)]
#[serde(tag = "t", rename_all = "snake_case")]
pub enum Msg {
    Offer {
        tid: String,
        from: String,
        items: Vec<Item>,
        total: u64,
        lanes: u32,
        seg: u64,
    },
    Accept {
        have: Vec<Range>,
    },
    Reject {
        reason: String,
    },
    Join {
        tid: String,
    },
    Joined,
    Seg {
        f: u32,
        s: u32,
        len: u64,
    },
    SegEnd {
        sha: String,
    },
    SegAck {
        f: u32,
        s: u32,
    },
    SegBad {
        f: u32,
        s: u32,
    },
    Done,
    Complete,
    Fail {
        reason: String,
    },
    Cancel,
}

pub fn send_msg(w: &mut Writer, m: &Msg) -> Result<()> {
    let b = serde_json::to_vec(m)?;
    w.send(T_JSON, &b)
}

pub fn send_reject(w: &mut Writer, reason: impl Into<String>) -> Result<()> {
    send_msg(
        w,
        &Msg::Reject {
            reason: reason.into(),
        },
    )
}

/// Lê a próxima mensagem de controle (quadro JSON).
pub fn recv_msg(r: &mut Reader) -> Result<Msg> {
    let (t, body) = r.recv()?;
    if t != T_JSON {
        bail!("esperava mensagem de controle");
    }
    Ok(serde_json::from_slice(body)?)
}
