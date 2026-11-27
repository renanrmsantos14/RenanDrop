//! Descoberta fora da LAN: pares da tailnet (Tailscale, só leitura de `tailscale status`) e
//! endereços adicionados à mão (IP ou nome MagicDNS). O app nunca instala nem faz login no Tailscale;
//! sem o Tailscale, tudo aqui devolve vazio e o app segue só na LAN.
//!
//! A cifra e o pareamento continuam sendo do RenanDrop: a rota só decide por onde o TCP chega.

use crate::session::{read_plain, write_plain, write_preamble, KIND_HELLO};
use anyhow::{anyhow, bail, Result};
use std::io::Read;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream, ToSocketAddrs};
use std::process::{Command, Stdio};
use std::sync::mpsc::channel;
use std::time::Duration;

/// Quantas portas, a partir da padrão, são testadas em cada candidato (o app sobe na próxima se a padrão estiver ocupada).
pub const PORT_SPAN: u16 = 5;

/// Portas candidatas de um aparelho: a base e as seguintes.
pub fn port_span(base: u16) -> impl Iterator<Item = u16> {
    (0..PORT_SPAN).map(move |i| base + i)
}

/// Classifica o endereço usado numa conexão: "lan", "tailnet", "local" ou "internet".
pub fn route_of(ip: IpAddr) -> &'static str {
    match ip {
        IpAddr::V4(v4) => {
            let o = v4.octets();
            if o[0] == 100 && (64..=127).contains(&o[1]) {
                "tailnet"
            } else if v4.is_loopback() {
                "local"
            } else if v4.is_private() || v4.is_link_local() {
                "lan"
            } else {
                "internet"
            }
        }
        IpAddr::V6(v6) => {
            let s = v6.segments();
            if s[0] == 0xfd7a && s[1] == 0x115c && s[2] == 0xa1e0 {
                "tailnet"
            } else if v6.is_loopback() {
                "local"
            } else if (s[0] & 0xfe00) == 0xfc00 || (s[0] & 0xffc0) == 0xfe80 {
                "lan"
            } else {
                "internet"
            }
        }
    }
}

#[derive(Clone, Debug)]
pub struct Remote {
    pub id: String,
    pub name: String,
    pub addr: SocketAddr,
    /// Como foi encontrado: nome na tailnet ou o texto que a pessoa digitou.
    pub host: String,
}

#[derive(Debug, Clone)]
pub struct TailNode {
    pub host: String,
    pub ip: Ipv4Addr,
}

/// Comandos candidatos do Tailscale, em ordem. O primeiro que abrir é o usado.
fn tailscale_candidates() -> Vec<std::path::PathBuf> {
    let mut c: Vec<std::path::PathBuf> = vec!["tailscale".into()];
    if cfg!(windows) {
        c.push(r"C:\Program Files\Tailscale\tailscale.exe".into());
    }
    if cfg!(target_os = "macos") {
        c.push("/Applications/Tailscale.app/Contents/MacOS/Tailscale".into());
    }
    c
}

/// Pares online da tailnet (só IPv4). `Ok(None)` = Tailscale não instalado. Erro = instalado mas desconectado.
pub fn tailnet_nodes() -> Result<Option<Vec<TailNode>>> {
    let mut child = None;
    for c in tailscale_candidates() {
        let mut cmd = Command::new(&c);
        cmd.args(["status", "--json"])
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::null());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            cmd.creation_flags(0x0800_0000);
        }
        match cmd.spawn() {
            Ok(ch) => {
                child = Some(ch);
                break;
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => continue,
            Err(e) => return Err(e.into()),
        }
    }
    let Some(mut child) = child else {
        return Ok(None);
    };
    let mut out = child.stdout.take().ok_or_else(|| anyhow!("sem saída"))?;
    let (tx, rx) = channel();
    std::thread::spawn(move || {
        let mut b = Vec::new();
        let _ = out.read_to_end(&mut b);
        let _ = tx.send(b);
    });
    let bytes = match rx.recv_timeout(Duration::from_secs(5)) {
        Ok(b) => b,
        Err(_) => {
            let _ = child.kill();
            bail!("tailscale não respondeu");
        }
    };
    let _ = child.wait();
    let v: serde_json::Value =
        serde_json::from_slice(&bytes).map_err(|_| anyhow!("tailscale sem sessão ativa"))?;
    if v.get("BackendState")
        .and_then(|s| s.as_str())
        .map(|s| s != "Running")
        .unwrap_or(true)
    {
        bail!("tailscale desconectado");
    }
    let mut nodes = Vec::new();
    if let Some(peers) = v.get("Peer").and_then(|p| p.as_object()) {
        for p in peers.values() {
            if !p.get("Online").and_then(|o| o.as_bool()).unwrap_or(false) {
                continue;
            }
            let Some(ip) = p
                .get("TailscaleIPs")
                .and_then(|a| a.as_array())
                .and_then(|a| {
                    a.iter()
                        .filter_map(|x| x.as_str()?.parse::<Ipv4Addr>().ok())
                        .next()
                })
            else {
                continue;
            };
            let host = p
                .get("DNSName")
                .and_then(|s| s.as_str())
                .map(|s| s.trim_end_matches('.').to_string())
                .filter(|s| !s.is_empty())
                .or_else(|| {
                    p.get("HostName")
                        .and_then(|s| s.as_str())
                        .map(str::to_string)
                })
                .unwrap_or_else(|| ip.to_string());
            nodes.push(TailNode { host, ip });
        }
    }
    Ok(Some(nodes))
}

/// Pergunta quem atende naquele endereço (id e nome públicos; nada sensível). Sem cifra: serve só para achar o aparelho.
pub fn hello(addr: SocketAddr, timeout: Duration) -> Result<(String, String)> {
    let mut s = TcpStream::connect_timeout(&addr, timeout)?;
    s.set_read_timeout(Some(timeout))?;
    s.set_write_timeout(Some(timeout))?;
    write_preamble(&mut s, KIND_HELLO)?;
    let v: serde_json::Value = read_plain(&mut s, 512)?;
    let id = v
        .get("id")
        .and_then(|x| x.as_str())
        .filter(|x| x.len() == 32 && x.bytes().all(|c| c.is_ascii_hexdigit()))
        .ok_or_else(|| anyhow!("resposta inválida"))?;
    let name = v
        .get("name")
        .and_then(|x| x.as_str())
        .unwrap_or("Aparelho")
        .chars()
        .take(64)
        .collect();
    Ok((id.to_string(), name))
}

/// Resposta do servidor ao `hello`.
pub fn answer_hello(s: &mut TcpStream, id: &str, name: &str) -> Result<()> {
    write_plain(s, &serde_json::json!({"id": id, "name": name, "v": 1}))
}

/// Resolve "host", "host:porta", "ip" ou "ip:porta" em endereços a testar.
pub fn resolve(text: &str, default_port: u16) -> Result<Vec<SocketAddr>> {
    let t = text
        .trim()
        .trim_start_matches("http://")
        .trim_end_matches('/');
    if t.is_empty() {
        bail!("informe um nome ou IP");
    }
    if let Ok(a) = t.parse::<SocketAddr>() {
        return Ok(vec![a]);
    }
    if let Ok(ip) = t.parse::<IpAddr>() {
        return Ok(port_span(default_port)
            .map(|p| SocketAddr::new(ip, p))
            .collect());
    }
    let (host, ports): (&str, Vec<u16>) = match t
        .rsplit_once(':')
        .and_then(|(h, p)| Some((h, p.parse::<u16>().ok()?)))
    {
        Some((h, p)) => (h, vec![p]),
        None => (t, port_span(default_port).collect()),
    };
    let ips: Vec<IpAddr> = (host, 0u16)
        .to_socket_addrs()
        .map_err(|_| anyhow!("não consegui resolver \"{host}\""))?
        .map(|a| a.ip())
        .filter(|i| i.is_ipv4())
        .collect();
    if ips.is_empty() {
        bail!("\"{host}\" não tem endereço IPv4");
    }
    Ok(ips
        .iter()
        .flat_map(|ip| ports.iter().map(move |p| SocketAddr::new(*ip, *p)))
        .collect())
}

/// Testa os endereços em ordem e devolve o primeiro RenanDrop que responder.
pub fn probe(addrs: &[SocketAddr], timeout: Duration) -> Option<(String, String, SocketAddr)> {
    for a in addrs {
        if let Ok((id, name)) = hello(*a, timeout) {
            return Some((id, name, *a));
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classifica_rotas() {
        assert_eq!(route_of("100.101.102.103".parse().unwrap()), "tailnet");
        assert_eq!(route_of("100.63.0.1".parse().unwrap()), "internet");
        assert_eq!(route_of("100.128.0.1".parse().unwrap()), "internet");
        assert_eq!(route_of("192.168.1.20".parse().unwrap()), "lan");
        assert_eq!(route_of("10.0.0.5".parse().unwrap()), "lan");
        assert_eq!(route_of("172.20.1.1".parse().unwrap()), "lan");
        assert_eq!(route_of("127.0.0.1".parse().unwrap()), "local");
        assert_eq!(route_of("8.8.8.8".parse().unwrap()), "internet");
        assert_eq!(route_of("fd7a:115c:a1e0::1".parse().unwrap()), "tailnet");
    }

    #[test]
    fn resolve_ip_e_porta() {
        assert_eq!(
            resolve("100.64.0.9:5000", 47821).unwrap(),
            vec!["100.64.0.9:5000".parse().unwrap()]
        );
        assert_eq!(
            resolve("100.64.0.9", 47821).unwrap().len(),
            PORT_SPAN as usize
        );
        assert!(resolve("  ", 47821).is_err());
    }
}
