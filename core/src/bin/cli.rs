//! `renandrop-cli`: daemon sem janela, usado em testes e como cliente de linha de comando.
//!
//! Uso: renandrop-cli serve --profile DIR [--port N] [--name NOME] [--recv DIR] [--no-discovery]
//! Comandos por stdin (um por linha): peers | pair <id|ip:porta> | yes | no | accept | decline
//!   | remote <nome|ip[:porta]> | rescan | tailscale | addr <id> <ip:porta> | send <id> <caminho...> | cancel <job> | quit
//! Eventos saem em stdout, uma linha JSON por evento.
//!
//! Release (ver docs/release.md):
//!   renandrop-cli keygen <arquivo>                              grava a chave privada (hex, 0600) e imprime só a pública
//!   renandrop-cli sign <arquivo> --key <chave> --version X.Y.Z  grava <arquivo>.sig (base64, ed25519)
//!   renandrop-cli verify <arquivo> --version X.Y.Z [--pub HEX]  confere <arquivo>.sig com a chave embutida (ou --pub)

use renandrop_core::{updater, Engine, EngineOptions, Event};
use std::io::BufRead;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

/// Valor depois de `flag`, se houver.
fn flag(args: &[String], flag: &str) -> Option<String> {
    args.iter()
        .position(|a| a == flag)
        .and_then(|i| args.get(i + 1))
        .cloned()
}

fn keygen(args: &[String]) -> anyhow::Result<()> {
    use std::io::Write;
    let path = args
        .first()
        .ok_or_else(|| anyhow::anyhow!("keygen <arquivo>"))?;
    let (secret, public) = updater::generate_keypair();
    let mut o = std::fs::OpenOptions::new();
    // create_new: nunca sobrescreve uma chave que já existe.
    o.write(true).create_new(true);
    #[cfg(unix)]
    std::os::unix::fs::OpenOptionsExt::mode(&mut o, 0o600);
    let mut f = o
        .open(path)
        .map_err(|e| anyhow::anyhow!("não gravei {path}: {e}"))?;
    f.write_all(secret.as_bytes())?;
    println!("{public}");
    Ok(())
}

fn sign(args: &[String]) -> anyhow::Result<()> {
    let file = args
        .first()
        .ok_or_else(|| anyhow::anyhow!("sign <arquivo> --key <chave> --version X.Y.Z"))?;
    let key = flag(args, "--key").ok_or_else(|| anyhow::anyhow!("--key é obrigatório"))?;
    let version =
        flag(args, "--version").ok_or_else(|| anyhow::anyhow!("--version é obrigatório"))?;
    anyhow::ensure!(
        updater::parse_version(&version).is_some(),
        "versão inválida: {version}"
    );
    let secret = updater::parse_key(&std::fs::read_to_string(&key)?)?;
    let content = std::fs::read(file)?;
    let sig = updater::sign_asset(&secret, &version, &asset_name(file), &content);
    std::fs::write(format!("{file}.sig"), format!("{sig}\n"))?;
    println!("{file}.sig");
    Ok(())
}

fn verify(args: &[String]) -> anyhow::Result<()> {
    let file = args
        .first()
        .ok_or_else(|| anyhow::anyhow!("verify <arquivo> --version X.Y.Z [--pub HEX]"))?;
    let version =
        flag(args, "--version").ok_or_else(|| anyhow::anyhow!("--version é obrigatório"))?;
    let pubkey = match flag(args, "--pub") {
        Some(h) => updater::parse_key(&h)?,
        None => updater::public_key(),
    };
    let sig = std::fs::read_to_string(format!("{file}.sig"))?;
    let ok = updater::verify_asset(
        &pubkey,
        &version,
        &asset_name(file),
        &std::fs::read(file)?,
        &sig,
    );
    anyhow::ensure!(ok, "assinatura INVÁLIDA para {file}");
    println!("assinatura ok");
    Ok(())
}

/// O nome do arquivo (sem diretório) é o nome do asset no release e entra na assinatura.
fn asset_name(file: &str) -> String {
    std::path::Path::new(file)
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default()
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let release = match args.first().map(|s| s.as_str()) {
        Some("keygen") => Some(keygen(&args[1..])),
        Some("sign") => Some(sign(&args[1..])),
        Some("verify") => Some(verify(&args[1..])),
        _ => None,
    };
    if let Some(r) = release {
        if let Err(e) = r {
            eprintln!("erro: {e:#}");
            std::process::exit(1);
        }
        return;
    }
    if args.first().map(|s| s.as_str()) != Some("serve") {
        eprintln!("uso: renandrop-cli serve --profile DIR [--port N] [--name NOME] [--recv DIR] [--no-discovery]");
        eprintln!("     renandrop-cli keygen <arquivo> | sign <arquivo> --key <chave> --version X.Y.Z | verify <arquivo> --version X.Y.Z");
        std::process::exit(2);
    }
    let get = |k: &str| flag(&args, k);
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
