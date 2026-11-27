//! Pareamento por código curto (SAS de 6 dígitos com compromisso prévio), sem segredo compartilhado.
//!
//! A envia hash(pubA‖nonceA); B responde pubB‖nonceB; A revela. O código = 6 dígitos de
//! SHA-256(pubA‖pubB‖nonceA‖nonceB). Um atacante no meio não consegue escolher chaves que
//! gerem o mesmo código nos dois lados (chance de 1 em 1.000.000 por tentativa, visível ao usuário).
//! A confirmação final leva um MAC derivado do ECDH entre as chaves estáticas.

use crate::crypto::*;
use crate::session::*;
use anyhow::{bail, Result};
use serde::{Deserialize, Serialize};
use std::io::Read;
use std::net::TcpStream;
use std::time::Duration;

#[derive(Serialize, Deserialize, Debug)]
#[serde(tag = "t", rename_all = "snake_case")]
enum PairMsg {
    Init {
        commit: String,
        name: String,
    },
    Resp {
        public: String,
        nonce: String,
        name: String,
    },
    Reveal {
        public: String,
        nonce: String,
    },
    Confirm {
        ok: bool,
        mac: String,
    },
}

fn read_pair(s: &mut impl Read) -> Result<PairMsg> {
    read_plain(s, 4096)
}

/// Resultado do pareamento: dados do outro aparelho.
#[derive(Debug, Clone)]
pub struct Paired {
    pub id: [u8; 16],
    pub name: String,
    pub public: Vec<u8>,
}

/// O que a interface mostra ao usuário para decidir.
#[derive(Debug, Clone)]
pub struct PairPrompt {
    pub peer_name: String,
    pub code: String,
    pub outgoing: bool,
}

fn sas_hash(pub_a: &[u8], pub_b: &[u8], na: &[u8], nb: &[u8]) -> [u8; 32] {
    sha256(&[b"RDSAS1", pub_a, pub_b, na, nb])
}

fn confirm_mac(
    me: &Identity,
    peer_pub: &[u8],
    sas: &[u8; 32],
    role: u8,
    ok: bool,
) -> Result<String> {
    let k = hkdf32(sas, &me.ecdh(peer_pub)?, b"RenanDrop pair confirm");
    let mut data = vec![role, ok as u8];
    data.extend_from_slice(sas);
    Ok(hex::encode(hmac_sha256(&k, &data)))
}

fn finish(
    s: &mut TcpStream,
    me: &Identity,
    peer_pub: &[u8],
    sas: &[u8; 32],
    my_role: u8,
    ok: bool,
) -> Result<bool> {
    let peer_role = if my_role == b'A' { b'B' } else { b'A' };
    write_plain(
        s,
        &PairMsg::Confirm {
            ok,
            mac: confirm_mac(me, peer_pub, sas, my_role, ok)?,
        },
    )?;
    s.set_read_timeout(Some(Duration::from_secs(150)))?;
    match read_pair(s)? {
        PairMsg::Confirm { ok: peer_ok, mac } => {
            let expect = confirm_mac(me, peer_pub, sas, peer_role, peer_ok)?;
            let good: bool =
                subtle::ConstantTimeEq::ct_eq(expect.as_bytes(), mac.as_bytes()).into();
            if !good {
                bail!("confirmação de pareamento inválida (possível interceptação)");
            }
            Ok(ok && peer_ok)
        }
        _ => bail!("resposta inesperada no pareamento"),
    }
}

/// Mostra o código ao usuário, troca as confirmações e devolve o outro aparelho se os dois aceitaram.
fn conclude(
    s: &mut TcpStream,
    me: &Identity,
    peer_pub: Vec<u8>,
    peer_name: String,
    sas: &[u8; 32],
    my_role: u8,
    decide: &dyn Fn(PairPrompt) -> bool,
) -> Result<Option<Paired>> {
    let ok = decide(PairPrompt {
        peer_name: peer_name.clone(),
        code: sas_digits(sas),
        outgoing: my_role == b'A',
    });
    let both = finish(s, me, &peer_pub, sas, my_role, ok)?;
    Ok(both.then(|| Paired {
        id: id_of(&peer_pub),
        name: peer_name,
        public: peer_pub,
    }))
}

/// Lado que inicia. `decide` bloqueia até o usuário confirmar o código (true) ou recusar.
pub fn initiate(
    mut s: TcpStream,
    me: &Identity,
    my_name: &str,
    decide: &dyn Fn(PairPrompt) -> bool,
) -> Result<Option<Paired>> {
    s.set_read_timeout(Some(Duration::from_secs(20)))?;
    write_preamble(&mut s, KIND_PAIR)?;
    let na: [u8; 16] = random_bytes();
    let commit = hex::encode(sha256(&[me.public(), &na]));
    write_plain(
        &mut s,
        &PairMsg::Init {
            commit,
            name: my_name.to_string(),
        },
    )?;
    let (pub_b, nb, name_b) = match read_pair(&mut s)? {
        PairMsg::Resp {
            public,
            nonce,
            name,
        } => (hex::decode(public)?, hex::decode(nonce)?, name),
        _ => bail!("resposta inesperada no pareamento"),
    };
    if pub_b.len() != 65 || nb.len() != 16 {
        bail!("dados de pareamento inválidos");
    }
    write_plain(
        &mut s,
        &PairMsg::Reveal {
            public: hex::encode(me.public()),
            nonce: hex::encode(na),
        },
    )?;
    let sas = sas_hash(me.public(), &pub_b, &na, &nb);
    conclude(&mut s, me, pub_b, name_b, &sas, b'A', decide)
}

/// Lado que responde (o preâmbulo já foi lido).
pub fn respond(
    mut s: TcpStream,
    me: &Identity,
    my_name: &str,
    decide: &dyn Fn(PairPrompt) -> bool,
) -> Result<Option<Paired>> {
    s.set_read_timeout(Some(Duration::from_secs(20)))?;
    let (commit, name_a) = match read_pair(&mut s)? {
        PairMsg::Init { commit, name } => (commit, name),
        _ => bail!("mensagem inicial inesperada"),
    };
    let nb: [u8; 16] = random_bytes();
    write_plain(
        &mut s,
        &PairMsg::Resp {
            public: hex::encode(me.public()),
            nonce: hex::encode(nb),
            name: my_name.to_string(),
        },
    )?;
    let (pub_a, na) = match read_pair(&mut s)? {
        PairMsg::Reveal { public, nonce } => (hex::decode(public)?, hex::decode(nonce)?),
        _ => bail!("revelação ausente"),
    };
    if pub_a.len() != 65 || na.len() != 16 || hex::encode(sha256(&[&pub_a, &na])) != commit {
        bail!("compromisso de pareamento não confere");
    }
    let sas = sas_hash(&pub_a, me.public(), &na, &nb);
    conclude(&mut s, me, pub_a, name_a, &sas, b'B', decide)
}
