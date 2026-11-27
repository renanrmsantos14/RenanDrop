//! Instância única: o menu de contexto abre `renandrop --send <arquivos>`; se o app já está
//! rodando, o processo novo entrega os caminhos por uma porta só do loopback e sai.
//! O recado só é aceito com o token do perfil (arquivo 0600), então outro usuário da máquina
//! não consegue mandar o app abrir nada.

use anyhow::Result;
use rand_core::{OsRng, RngCore};
use serde::{Deserialize, Serialize};
use std::io::{BufRead, BufReader, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::path::Path;
use std::time::Duration;

pub const DEFAULT_PORT: u16 = 47819;

#[derive(Serialize, Deserialize)]
pub struct Msg {
    pub token: String,
    pub paths: Vec<String>,
}

pub enum Claim {
    /// Este processo é o dono: atende os recados do listener.
    First(TcpListener),
    /// Outro processo já está rodando e recebeu o recado.
    Forwarded,
    /// A porta está ocupada por outro programa: segue sem canal de controle.
    None,
}

pub fn token(profile: &Path) -> Result<String> {
    let p = profile.join("control.token");
    if let Ok(t) = std::fs::read_to_string(&p) {
        if t.trim().len() == 32 {
            return Ok(t.trim().to_string());
        }
    }
    let mut b = [0u8; 16];
    OsRng.fill_bytes(&mut b);
    let t = hex::encode(b);
    std::fs::create_dir_all(profile)?;
    let mut o = std::fs::OpenOptions::new();
    o.write(true).create(true).truncate(true);
    #[cfg(unix)]
    std::os::unix::fs::OpenOptionsExt::mode(&mut o, 0o600);
    o.open(&p)?.write_all(t.as_bytes())?;
    Ok(t)
}

pub fn claim(port: u16, msg: &Msg) -> Claim {
    let addr = SocketAddr::new(Ipv4Addr::LOCALHOST.into(), port);
    match TcpListener::bind(addr) {
        Ok(l) => Claim::First(l),
        Err(_) => match forward(addr, msg) {
            Ok(()) => Claim::Forwarded,
            Err(_) => Claim::None,
        },
    }
}

fn forward(addr: SocketAddr, msg: &Msg) -> Result<()> {
    let mut s = TcpStream::connect_timeout(&addr, Duration::from_secs(2))?;
    s.set_read_timeout(Some(Duration::from_secs(3)))?;
    writeln!(s, "{}", serde_json::to_string(msg)?)?;
    let mut ok = String::new();
    BufReader::new(s).read_line(&mut ok)?;
    anyhow::ensure!(ok.trim() == "ok", "recusado");
    Ok(())
}

pub fn serve(l: TcpListener, token: String, on_msg: impl Fn(Msg) + Send + 'static) {
    std::thread::spawn(move || {
        for s in l.incoming().flatten() {
            let _ = s.set_read_timeout(Some(Duration::from_secs(2)));
            let mut line = String::new();
            // Um recado tem de caber em 1 MiB: nada de ler sem limite de quem conecta.
            let r = std::io::Read::take(&s, 1 << 20);
            if BufReader::new(r).read_line(&mut line).is_err() {
                continue;
            }
            let Ok(m) = serde_json::from_str::<Msg>(&line) else {
                continue;
            };
            let mut s = s;
            if m.token == token {
                on_msg(m);
                let _ = writeln!(s, "ok");
            } else {
                let _ = writeln!(s, "no");
            }
        }
    });
}
