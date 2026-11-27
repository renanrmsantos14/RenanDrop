//! Atualização do app: lê `releases/latest` do GitHub, baixa o binário da plataforma e a `.sig`,
//! confere a assinatura ed25519 com a chave embutida e só então troca o executável.
//!
//! Rede pelo `curl` do sistema (Linux e Windows 10+ têm): o app não carrega biblioteca TLS.
//! Sem curl ou sem internet a verificação só falha com uma mensagem; nada mais acontece.
//! Assinatura inválida ou ausente, ou versão que não é maior: descarta e nunca instala.

use anyhow::{anyhow, ensure, Context, Result};
use renandrop_core::updater::{self as core, Update};
use serde::Serialize;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

pub const RELEASES_URL: &str =
    "https://api.github.com/repos/renanrmsantos14/RenanDrop/releases/latest";

const MAX_JSON: usize = 2 << 20;
const MAX_SIG: usize = 4 << 10;
const MAX_BINARY: usize = 200 << 20;
/// Segundos: a consulta e a `.sig` são pequenas; só o binário pode demorar.
const TIME_SMALL: u32 = 20;
const TIME_BINARY: u32 = 600;

#[derive(Clone)]
pub struct Cfg {
    pub url: String,
    pub pubkey: [u8; 32],
    /// Versão instalada (sem `v`).
    pub current: String,
    /// Nome do binário no release para esta plataforma.
    pub asset: String,
    /// Protocolos que o curl pode usar (`--proto`). Em release, só https.
    pub proto: String,
}

impl Cfg {
    /// `None` em plataforma sem binário no release (só Linux e Windows x86_64).
    pub fn production(current: &str) -> Option<Cfg> {
        #[allow(unused_mut)]
        let mut c = Cfg {
            url: RELEASES_URL.into(),
            pubkey: core::public_key(),
            current: current.trim_start_matches('v').into(),
            asset: core::current_platform_asset()?.into(),
            proto: "=https".into(),
        };
        // Só em build de depuração: aponta para um release falso (arquivo local) com chave de teste.
        // Em release estas variáveis são ignoradas, não há brecha para trocar a fonte.
        #[cfg(debug_assertions)]
        {
            if let Ok(u) = std::env::var("RENANDROP_UPDATE_URL") {
                c.url = u;
                c.proto = "=https,file".into();
            }
            if let Some(k) = std::env::var("RENANDROP_UPDATE_PUBKEY")
                .ok()
                .and_then(|k| core::parse_key(&k).ok())
            {
                c.pubkey = k;
            }
        }
        Some(c)
    }
}

/// Estado mostrado na tela de configurações.
#[derive(Serialize, Clone, Debug, Default, PartialEq)]
#[serde(tag = "state", rename_all = "snake_case")]
pub enum State {
    #[default]
    Idle,
    Checking,
    UpToDate,
    Available {
        version: String,
    },
    Installing,
    Error {
        message: String,
    },
}

/// Baixa `url` pelo curl para a memória, recusando mais que `max` bytes.
fn fetch(cfg: &Cfg, url: &str, max: usize, max_time: u32) -> Result<Vec<u8>> {
    let mut c = Command::new("curl");
    c.args(["-fsSL", "--proto", &cfg.proto, "--proto-redir", "=https"])
        .args([
            "--connect-timeout",
            "15",
            "--max-time",
            &max_time.to_string(),
        ])
        .args(["--max-filesize", &max.to_string()])
        .args(["-A", &format!("renandrop/{}", cfg.current)])
        .arg(url)
        .stdin(Stdio::null())
        .stderr(Stdio::null());
    // O cabeçalho é da API; os downloads (CDN do GitHub) não o recebem.
    if url == cfg.url {
        c.args(["-H", "Accept: application/vnd.github+json"]);
    }
    let out = crate::integration::sem_janela(&mut c)
        .output()
        .map_err(|e| match e.kind() {
            std::io::ErrorKind::NotFound => anyhow!("o programa curl não foi encontrado"),
            _ => anyhow!("não consegui rodar o curl: {e}"),
        })?;
    ensure!(
        out.status.success(),
        "sem conexão ou servidor indisponível (curl {})",
        out.status.code().map_or("?".into(), |c| c.to_string())
    );
    ensure!(out.stdout.len() <= max, "resposta grande demais");
    Ok(out.stdout)
}

/// Consulta o release mais novo. `None` = já está na versão mais recente.
pub fn check(cfg: &Cfg) -> Result<Option<Update>> {
    let json = fetch(cfg, &cfg.url, MAX_JSON, TIME_SMALL)?;
    let rel =
        core::parse_release(std::str::from_utf8(&json).context("resposta do release ilegível")?)?;
    core::find_update(&rel, &cfg.current, &cfg.asset)
}

/// Baixa o binário e a assinatura e confere. Só devolve bytes de um binário assinado e mais novo.
pub fn download_verified(cfg: &Cfg, u: &Update) -> Result<Vec<u8>> {
    ensure!(
        core::is_newer(&u.version, &cfg.current),
        "a versão {} não é mais nova que a instalada",
        u.version
    );
    // A assinatura primeiro: se faltar, não gasta banda com o binário.
    let sig = fetch(cfg, &u.sig_url, MAX_SIG, TIME_SMALL)?;
    let sig = std::str::from_utf8(&sig).unwrap_or_default();
    let bin = fetch(cfg, &u.url, MAX_BINARY, TIME_BINARY)?;
    ensure!(
        !bin.is_empty() && core::verify_asset(&cfg.pubkey, &u.version, &u.asset, &bin, sig),
        "assinatura inválida: a atualização foi descartada"
    );
    Ok(bin)
}

/// Baixa, confere e troca `target` pelo binário novo.
pub fn install(cfg: &Cfg, u: &Update, target: &Path) -> Result<()> {
    let bin = download_verified(cfg, u)?;
    replace_exe(target, &bin)
}

fn sibling(exe: &Path, suffix: &str) -> PathBuf {
    let mut n = exe.as_os_str().to_owned();
    n.push(suffix);
    n.into()
}

fn write_exe(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    let mut f = std::fs::File::create(path)?;
    f.write_all(bytes)?;
    f.sync_all()?;
    drop(f);
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o755))?;
    }
    Ok(())
}

pub fn replace_exe(target: &Path, bytes: &[u8]) -> Result<()> {
    let r = if cfg!(windows) {
        replace_aside(target, bytes)
    } else {
        replace_by_rename(target, bytes)
    };
    r.map_err(|e| {
        anyhow!(
            "não consegui gravar {} ({e}); sem permissão nessa pasta?",
            target.display()
        )
    })
}

/// Linux e afins: grava ao lado e `rename` por cima (atômico; o processo em execução segue no inode antigo).
fn replace_by_rename(target: &Path, bytes: &[u8]) -> std::io::Result<()> {
    let tmp = sibling(target, ".new");
    let _ = std::fs::remove_file(&tmp);
    let r = write_exe(&tmp, bytes).and_then(|_| std::fs::rename(&tmp, target));
    if r.is_err() {
        let _ = std::fs::remove_file(&tmp);
    }
    r
}

/// Windows: um .exe em uso não pode ser sobrescrito, mas pode ser renomeado. O atual vira `.old`
/// (apagado no próximo início) e o novo ocupa o lugar; se a gravação falha, o antigo volta.
fn replace_aside(target: &Path, bytes: &[u8]) -> std::io::Result<()> {
    let old = sibling(target, ".old");
    let _ = std::fs::remove_file(&old);
    std::fs::rename(target, &old)?;
    if let Err(e) = write_exe(target, bytes) {
        let _ = std::fs::remove_file(target);
        let _ = std::fs::rename(&old, target);
        return Err(e);
    }
    Ok(())
}

/// Apaga o `.old` deixado por uma atualização anterior (chamado ao iniciar).
pub fn cleanup_old(exe: &Path) {
    let _ = std::fs::remove_file(sibling(exe, ".old"));
}

/// Abre o executável novo com os mesmos argumentos do atual (menos `--send`, `--hidden` e `--relaunch`).
/// `--relaunch` faz o novo processo esperar este sair antes de assumir a instância única.
pub fn relaunch(exe: &Path, original: &[String], hidden: bool) -> std::io::Result<()> {
    let mut args: Vec<&str> = Vec::new();
    for a in original {
        match a.as_str() {
            "--send" => break,
            "--hidden" | "--relaunch" => {}
            a => args.push(a),
        }
    }
    args.push("--relaunch");
    if hidden {
        args.push("--hidden");
    }
    Command::new(exe)
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map(|_| ())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    const ASSET: &str = "renandrop-linux-x86_64";

    struct Fake {
        dir: tempfile::TempDir,
        cfg: Cfg,
        sk: [u8; 32],
    }

    impl Fake {
        /// Release falso em disco (`latest.json` + binário + `.sig`), servido por file://.
        fn new(version: &str, current: &str, conteudo: &[u8]) -> Fake {
            let dir = tempfile::tempdir().unwrap();
            let (sk, pk) = core::generate_keypair();
            let (sk, pk) = (core::parse_key(&sk).unwrap(), core::parse_key(&pk).unwrap());
            let f = Fake {
                cfg: Cfg {
                    url: format!("file://{}/latest.json", dir.path().display()),
                    pubkey: pk,
                    current: current.into(),
                    asset: ASSET.into(),
                    proto: "=file".into(),
                },
                dir,
                sk,
            };
            f.publish(version, conteudo, true);
            f
        }
        fn url(&self, n: &str) -> String {
            format!("file://{}/{n}", self.dir.path().display())
        }
        fn publish(&self, version: &str, conteudo: &[u8], with_sig_asset: bool) {
            let p = self.dir.path();
            fs::write(p.join(ASSET), conteudo).unwrap();
            fs::write(
                p.join(format!("{ASSET}.sig")),
                core::sign_asset(&self.sk, version, ASSET, conteudo),
            )
            .unwrap();
            let mut assets = vec![format!(
                r#"{{"name":"{ASSET}","browser_download_url":"{}"}}"#,
                self.url(ASSET)
            )];
            if with_sig_asset {
                assets.push(format!(
                    r#"{{"name":"{ASSET}.sig","browser_download_url":"{}"}}"#,
                    self.url(&format!("{ASSET}.sig"))
                ));
            }
            fs::write(
                p.join("latest.json"),
                format!(
                    r#"{{"tag_name":"v{version}","assets":[{}]}}"#,
                    assets.join(",")
                ),
            )
            .unwrap();
        }
        fn target(&self, antigo: &[u8]) -> PathBuf {
            let t = self.dir.path().join("renandrop-instalado");
            fs::write(&t, antigo).unwrap();
            t
        }
    }

    fn sem_curl() -> bool {
        Command::new("curl").arg("--version").output().is_err()
    }

    #[test]
    fn assinatura_boa_instala() {
        if sem_curl() {
            return;
        }
        let f = Fake::new("0.0.3", "0.0.2", b"#!/bin/sh\necho novo\n");
        let t = f.target(b"antigo");
        let u = check(&f.cfg).unwrap().expect("há atualização");
        assert_eq!(u.version, "0.0.3");
        install(&f.cfg, &u, &t).unwrap();
        assert_eq!(fs::read(&t).unwrap(), b"#!/bin/sh\necho novo\n");
        assert!(!sibling(&t, ".new").exists(), "não deixa o temporário");
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                fs::metadata(&t).unwrap().permissions().mode() & 0o777,
                0o755
            );
        }
    }

    #[test]
    fn binario_adulterado_e_recusado() {
        if sem_curl() {
            return;
        }
        let f = Fake::new("0.0.3", "0.0.2", b"original");
        let t = f.target(b"antigo");
        let u = check(&f.cfg).unwrap().unwrap();
        // Alguém troca o binário depois de assinado; a .sig continua a do original.
        fs::write(f.dir.path().join(ASSET), b"original + malware").unwrap();
        let e = install(&f.cfg, &u, &t).unwrap_err();
        assert!(e.to_string().contains("assinatura inválida"), "{e}");
        assert_eq!(fs::read(&t).unwrap(), b"antigo");
        assert!(!sibling(&t, ".new").exists());
    }

    #[test]
    fn assinatura_ausente_ou_de_outra_chave_e_recusada() {
        if sem_curl() {
            return;
        }
        // .sig nem aparece nos assets do release: nem chega a baixar.
        let f = Fake::new("0.0.3", "0.0.2", b"novo");
        f.publish("0.0.3", b"novo", false);
        assert!(check(&f.cfg).unwrap_err().to_string().contains(".sig"));

        // .sig listada mas o arquivo não existe (404).
        let f = Fake::new("0.0.3", "0.0.2", b"novo");
        let t = f.target(b"antigo");
        let u = check(&f.cfg).unwrap().unwrap();
        fs::remove_file(f.dir.path().join(format!("{ASSET}.sig"))).unwrap();
        assert!(install(&f.cfg, &u, &t).is_err());
        assert_eq!(fs::read(&t).unwrap(), b"antigo");

        // Assinado por outra chave: a do app não reconhece.
        let f = Fake::new("0.0.3", "0.0.2", b"novo");
        let t = f.target(b"antigo");
        let (_, outra) = core::generate_keypair();
        let cfg = Cfg {
            pubkey: core::parse_key(&outra).unwrap(),
            ..f.cfg.clone()
        };
        let u = check(&cfg).unwrap().unwrap();
        assert!(install(&cfg, &u, &t).is_err());
        assert_eq!(fs::read(&t).unwrap(), b"antigo");

        // .sig vazia ou lixo.
        let f = Fake::new("0.0.3", "0.0.2", b"novo");
        let t = f.target(b"antigo");
        let u = check(&f.cfg).unwrap().unwrap();
        fs::write(f.dir.path().join(format!("{ASSET}.sig")), "lixo").unwrap();
        assert!(install(&f.cfg, &u, &t).is_err());
        assert_eq!(fs::read(&t).unwrap(), b"antigo");
    }

    #[test]
    fn versao_que_nao_e_maior_nao_instala() {
        if sem_curl() {
            return;
        }
        let f = Fake::new("0.0.2", "0.0.2", b"novo");
        assert!(check(&f.cfg).unwrap().is_none());
        let f = Fake::new("0.0.1", "0.0.2", b"novo");
        assert!(check(&f.cfg).unwrap().is_none());
        // Mesmo forçando a instalação de um Update, a versão é conferida de novo.
        let t = f.target(b"antigo");
        let u = Update {
            version: "0.0.1".into(),
            asset: ASSET.into(),
            url: f.url(ASSET),
            sig_url: f.url(&format!("{ASSET}.sig")),
        };
        assert!(install(&f.cfg, &u, &t).is_err());
        assert_eq!(fs::read(&t).unwrap(), b"antigo");
    }

    #[test]
    fn binario_antigo_assinado_com_tag_falsa_e_recusado() {
        if sem_curl() {
            return;
        }
        // O release anuncia 0.0.9, mas o binário/assinatura são os da 0.0.3 (replay de versão antiga).
        let f = Fake::new("0.0.3", "0.0.2", b"antigo vulneravel");
        let json = fs::read_to_string(f.dir.path().join("latest.json")).unwrap();
        fs::write(
            f.dir.path().join("latest.json"),
            json.replace("v0.0.3", "v0.0.9"),
        )
        .unwrap();
        let t = f.target(b"atual");
        let u = check(&f.cfg).unwrap().unwrap();
        assert_eq!(u.version, "0.0.9");
        assert!(install(&f.cfg, &u, &t).is_err());
        assert_eq!(fs::read(&t).unwrap(), b"atual");
    }

    #[test]
    fn sem_release_ou_json_ruim_so_da_erro() {
        if sem_curl() {
            return;
        }
        let f = Fake::new("0.0.3", "0.0.2", b"x");
        fs::write(f.dir.path().join("latest.json"), "<html>").unwrap();
        assert!(check(&f.cfg).is_err());
        fs::remove_file(f.dir.path().join("latest.json")).unwrap();
        assert!(check(&f.cfg).is_err());
        // file:// só vale se o protocolo foi liberado (em release é só https).
        let f = Fake::new("0.0.3", "0.0.2", b"x");
        let cfg = Cfg {
            proto: "=https".into(),
            ..f.cfg.clone()
        };
        assert!(check(&cfg).is_err());
    }

    #[test]
    fn troca_no_estilo_windows_guarda_o_antigo_e_limpa() {
        let d = tempfile::tempdir().unwrap();
        let t = d.path().join("renandrop.exe");
        fs::write(&t, b"v1").unwrap();
        replace_aside(&t, b"v2").unwrap();
        assert_eq!(fs::read(&t).unwrap(), b"v2");
        assert_eq!(fs::read(sibling(&t, ".old")).unwrap(), b"v1");
        cleanup_old(&t);
        assert!(!sibling(&t, ".old").exists());
        // Sem o executável atual (nada a renomear): erro e nada criado.
        let n = d.path().join("outro.exe");
        assert!(replace_aside(&n, b"x").is_err());
        assert!(!n.exists());
    }

    #[test]
    fn troca_no_estilo_linux_sobre_executavel_aberto() {
        let d = tempfile::tempdir().unwrap();
        let t = d.path().join("renandrop");
        fs::write(&t, b"v1").unwrap();
        let aberto = fs::File::open(&t).unwrap(); // como um processo em execução
        replace_by_rename(&t, b"v2").unwrap();
        assert_eq!(fs::read(&t).unwrap(), b"v2");
        drop(aberto);
        // Pasta sem permissão de escrita: erro claro e o original intacto.
        assert!(replace_exe(&d.path().join("nao/existe/renandrop"), b"x").is_err());
    }

    #[test]
    fn relancar_filtra_os_argumentos() {
        // Só confere a montagem: --send e o que vem depois saem; --hidden é decidido por quem chama.
        let d = tempfile::tempdir().unwrap();
        let sh = d.path().join("eco");
        let saida = d.path().join("args");
        fs::write(
            &sh,
            format!("#!/bin/sh\necho \"$@\" > {}\n", saida.display()),
        )
        .unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&sh, fs::Permissions::from_mode(0o755)).unwrap();
            let orig: Vec<String> = [
                "--profile",
                "/p",
                "--hidden",
                "--port",
                "1",
                "--send",
                "a",
                "b",
            ]
            .map(String::from)
            .to_vec();
            relaunch(&sh, &orig, true).unwrap();
            for _ in 0..50 {
                if saida.exists() {
                    break;
                }
                std::thread::sleep(std::time::Duration::from_millis(50));
            }
            std::thread::sleep(std::time::Duration::from_millis(100));
            assert_eq!(
                fs::read_to_string(&saida).unwrap().trim(),
                "--profile /p --port 1 --relaunch --hidden"
            );
        }
    }

    #[test]
    fn config_de_producao_usa_https_e_chave_embutida() {
        let c = Cfg::production("v0.0.2");
        if let Some(c) = c {
            assert_eq!(c.current, "0.0.2");
            assert_eq!(c.pubkey, core::public_key());
            // Sem as variáveis de teste, vale a URL oficial e só https.
            if std::env::var("RENANDROP_UPDATE_URL").is_err() {
                assert_eq!(c.url, RELEASES_URL);
                assert_eq!(c.proto, "=https");
            }
        }
    }
}
