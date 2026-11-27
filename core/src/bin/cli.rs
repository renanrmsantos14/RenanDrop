//! `renandrop-cli`: daemon sem janela, usado em testes e como cliente de linha de comando.
//!
//! Uso: renandrop-cli serve --profile DIR [--port N] [--name NOME] [--recv DIR] [--no-discovery]
//! Comandos por stdin (um por linha): peers | pair <id|ip:porta> | yes | no | accept | decline
//!   | remote <nome|ip[:porta]> | rescan | tailscale | addr <id> <ip:porta> | send <id> <caminho...> | cancel <job> | quit
//! Eventos saem em stdout, uma linha JSON por evento.

use renandrop_core::{Engine, EngineOptions, Event};
use std::io::BufRead;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.first().map(|s| s.as_str()) != Some("serve") {
        eprintln!("uso: renandrop-cli serve --profile DIR [--port N] [--name NOME] [--recv DIR] [--no-discovery]");
        std::process::exit(2);
    }
    let get = |k: &str| {
        args.iter()
            .position(|a| a == k)
            .and_then(|i| args.get(i + 1))
            .cloned()
    };
    let Some(profile) = get("--profile") else {
        eprintln!("--profile é obrigatório");
        std::process::exit(2);
    };
    let opts = EngineOptions {
        profile: PathBuf::from(profile),
        port: get("--port").and_then(|p| p.parse().ok()),
        name: get("--name"),
        recv_dir: get("--recv").map(PathBuf::from),
        discovery: !args.iter().any(|a| a == "--no-discovery"),
    };
    let (eng, rx) = match Engine::start(opts) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("erro: {e:#}");
            std::process::exit(1);
        }
    };
    println!(
        "{}",
        serde_json::json!({"type":"ready","id":eng.id(),"port":eng.port()})
    );

    // Último pedido pendente, para os comandos curtos yes/no/accept/decline.
    let last_pair: Arc<Mutex<Option<String>>> = Arc::new(Mutex::new(None));
    let last_offer: Arc<Mutex<Option<String>>> = Arc::new(Mutex::new(None));
    {
        let (lp, lo) = (last_pair.clone(), last_offer.clone());
        std::thread::spawn(move || {
            for ev in rx {
                match &ev {
                    Event::PairPrompt { req_id, .. } => *lp.lock().unwrap() = Some(req_id.clone()),
                    Event::Offer { tid, .. } => *lo.lock().unwrap() = Some(tid.clone()),
                    _ => {}
                }
                println!("{}", serde_json::to_string(&ev).unwrap());
            }
        });
    }

    for line in std::io::stdin().lock().lines() {
        let Ok(line) = line else { break };
        let p: Vec<&str> = line.split_whitespace().collect();
        let Some(cmd) = p.first() else { continue };
        let r: anyhow::Result<()> = (|| {
            match *cmd {
                "peers" => println!("{}", serde_json::to_string(&eng.peers())?),
                "pair" => {
                    let t = p
                        .get(1)
                        .ok_or_else(|| anyhow::anyhow!("pair <id|ip:porta>"))?;
                    match t.parse::<SocketAddr>() {
                        Ok(a) => eng.pair_addr(vec![a])?,
                        Err(_) => eng.pair(t)?,
                    }
                }
                "yes" | "no" => {
                    if let Some(id) = last_pair.lock().unwrap().take() {
                        eng.confirm_pair(&id, *cmd == "yes");
                    }
                }
                "accept" | "decline" => {
                    if let Some(id) = last_offer.lock().unwrap().take() {
                        eng.respond_offer(&id, *cmd == "accept");
                    }
                }
                "remote" => {
                    let v = eng.add_remote(
                        p.get(1)
                            .ok_or_else(|| anyhow::anyhow!("remote <nome|ip[:porta]>"))?,
                    )?;
                    println!("{}", serde_json::to_string(&v)?);
                }
                "rescan" => eng.rescan(),
                "tailscale" => println!(
                    "{}",
                    serde_json::json!({"type":"tailscale","state":eng.tailscale_state()})
                ),
                "addr" => {
                    let a: SocketAddr = p
                        .get(2)
                        .ok_or_else(|| anyhow::anyhow!("addr <id> <ip:porta>"))?
                        .parse()?;
                    eng.set_manual_addr(p[1], a);
                }
                "send" => {
                    let paths = p[2..].iter().map(PathBuf::from).collect();
                    let ids = eng.send(&[p[1].to_string()], paths)?;
                    println!("{}", serde_json::json!({"type":"queued","jobs":ids}));
                }
                "cancel" => eng.cancel(p.get(1).copied().unwrap_or("")),
                "quit" => {
                    eng.shutdown();
                    std::process::exit(0);
                }
                other => anyhow::bail!("comando desconhecido: {other}"),
            }
            Ok(())
        })();
        if let Err(e) = r {
            println!(
                "{}",
                serde_json::json!({"type":"error","message":e.to_string()})
            );
        }
    }
    eng.shutdown();
}
