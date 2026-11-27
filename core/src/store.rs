//! Perfil em disco: identidade, configurações, aparelhos pareados e histórico.

use crate::crypto::Identity;
use anyhow::{anyhow, Context, Result};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default)]
pub struct Settings {
    pub device_name: String,
    /// 0 = padrão (47821); em uso, tenta a próxima.
    pub port: u16,
    pub recv_dir: PathBuf,
    /// Aceitar sem perguntar os envios de aparelhos pareados.
    pub auto_accept: bool,
    /// Conexões paralelas por envio (1 a 8).
    pub lanes: u32,
    pub autostart: bool,
    pub notifications: bool,
    /// "auto", "light" ou "dark".
    pub theme: String,
    /// Procurar aparelhos também pela tailnet do Tailscale (se estiver instalado).
    pub tailscale: bool,
    /// Endereços adicionados à mão (IP ou nome MagicDNS), sondados em segundo plano.
    pub remotes: Vec<String>,
}

impl Default for Settings {
    fn default() -> Self {
        Settings {
            device_name: default_device_name(),
            port: 0,
            recv_dir: default_recv_dir(),
            auto_accept: true,
            lanes: 4,
            autostart: true,
            notifications: true,
            theme: "auto".into(),
            tailscale: true,
            remotes: Vec::new(),
        }
    }
}

pub fn default_device_name() -> String {
    std::env::var("HOSTNAME")
        .ok()
        .or_else(|| std::env::var("COMPUTERNAME").ok())
        .or_else(|| fs::read_to_string("/etc/hostname").ok())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "Meu PC".into())
}

pub fn home_dir() -> PathBuf {
    std::env::var_os("HOME")
        .or_else(|| std::env::var_os("USERPROFILE"))
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."))
}

pub fn default_recv_dir() -> PathBuf {
    home_dir().join("Downloads").join("RenanDrop")
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Peer {
    /// 16 bytes em hexadecimal.
    pub id: String,
    pub name: String,
    pub pubkey: String,
    pub paired_at: i64,
    /// `None` segue a configuração global.
    #[serde(default)]
    pub auto_accept: Option<bool>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct HistoryEntry {
    pub ts: i64,
    /// "send" ou "recv"
    pub dir: String,
    pub peer: String,
    pub summary: String,
    pub files: u32,
    pub bytes: u64,
    /// "done", "failed", "cancelled", "declined"
    pub status: String,
    #[serde(default)]
    pub path: Option<String>,
}

pub struct Store {
    pub dir: PathBuf,
    pub identity: Arc<Identity>,
    settings: Mutex<Settings>,
    peers: Mutex<Vec<Peer>>,
    hist: Mutex<()>,
}

pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn write_atomic(path: &Path, data: &[u8], secret: bool) -> Result<()> {
    let tmp = path.with_extension("tmp");
    {
        let mut opts = fs::OpenOptions::new();
        opts.write(true).create(true).truncate(true);
        #[cfg(unix)]
        if secret {
            use std::os::unix::fs::OpenOptionsExt;
            // Já nasce 0600: a chave nunca fica legível para outros usuários, nem por um instante.
            opts.mode(0o600);
        }
        #[cfg(not(unix))]
        let _ = secret;
        let mut f = opts.open(&tmp)?;
        f.write_all(data)?;
        f.sync_all()?;
    }
    fs::rename(&tmp, path)?;
    Ok(())
}

fn load_json<T: DeserializeOwned + Default>(path: &Path) -> T {
    fs::read(path)
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default()
}

fn save_json<T: Serialize>(path: &Path, v: &T) -> Result<()> {
    write_atomic(path, &serde_json::to_vec_pretty(v)?, false)
}

impl Store {
    pub fn open(dir: &Path) -> Result<Store> {
        fs::create_dir_all(dir).with_context(|| format!("criando {}", dir.display()))?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = fs::set_permissions(dir, fs::Permissions::from_mode(0o700));
        }
        let idp = dir.join("identity.bin");
        // Só cria uma identidade nova se o arquivo não existe. Qualquer outra falha (arquivo travado,
        // sem permissão, corrompido) vira erro: trocar a chave perderia todos os pareamentos.
        let identity = match fs::read(&idp) {
            Ok(b) => Identity::from_secret_bytes(&b).map_err(|_| {
                anyhow!(
                    "{} está corrompido; apague-o só se aceitar parear de novo",
                    idp.display()
                )
            })?,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                let i = Identity::generate();
                write_atomic(&idp, &i.secret_bytes(), true)?;
                i
            }
            Err(e) => return Err(anyhow!("lendo {}: {e}", idp.display())),
        };
        let st = Store {
            dir: dir.to_path_buf(),
            identity: Arc::new(identity),
            settings: Mutex::new(load_json(&dir.join("config.json"))),
            peers: Mutex::new(load_json(&dir.join("peers.json"))),
            hist: Mutex::new(()),
        };
        st.save_settings()?;
        Ok(st)
    }

    pub fn settings(&self) -> Settings {
        self.settings.lock().unwrap().clone()
    }
    pub fn update_settings(&self, f: impl FnOnce(&mut Settings)) -> Result<Settings> {
        let s = {
            let mut g = self.settings.lock().unwrap();
            f(&mut g);
            g.lanes = g.lanes.clamp(1, 8);
            g.clone()
        };
        self.save_settings()?;
        Ok(s)
    }
    fn save_settings(&self) -> Result<()> {
        save_json(
            &self.dir.join("config.json"),
            &*self.settings.lock().unwrap(),
        )
    }

    pub fn peers(&self) -> Vec<Peer> {
        self.peers.lock().unwrap().clone()
    }
    pub fn peer(&self, id: &str) -> Option<Peer> {
        self.peers
            .lock()
            .unwrap()
            .iter()
            .find(|p| p.id == id)
            .cloned()
    }
    pub fn peer_pub(&self, id: &[u8; 16]) -> Option<Vec<u8>> {
        let id = hex::encode(id);
        self.peer(&id).and_then(|p| hex::decode(p.pubkey).ok())
    }
    pub fn add_peer(&self, p: Peer) -> Result<()> {
        {
            let mut g = self.peers.lock().unwrap();
            g.retain(|x| x.id != p.id);
            g.push(p);
        }
        self.save_peers()
    }
    pub fn remove_peer(&self, id: &str) -> Result<()> {
        self.peers.lock().unwrap().retain(|x| x.id != id);
        self.save_peers()
    }
    pub fn update_peer(&self, id: &str, f: impl FnOnce(&mut Peer)) -> Result<()> {
        {
            let mut g = self.peers.lock().unwrap();
            if let Some(p) = g.iter_mut().find(|p| p.id == id) {
                f(p);
            }
        }
        self.save_peers()
    }
    fn save_peers(&self) -> Result<()> {
        save_json(&self.dir.join("peers.json"), &*self.peers.lock().unwrap())
    }

    fn history_path(&self) -> PathBuf {
        self.dir.join("history.jsonl")
    }
    pub fn push_history(&self, e: &HistoryEntry) {
        let _g = self.hist.lock().unwrap();
        let p = self.history_path();
        if let Ok(mut f) = fs::OpenOptions::new().create(true).append(true).open(&p) {
            let _ = writeln!(f, "{}", serde_json::to_string(e).unwrap_or_default());
        }
        // Mantém só as últimas 500 entradas.
        if let Ok(meta) = fs::metadata(&p) {
            if meta.len() > 256 * 1024 {
                let all = self.read_history_locked();
                let keep = &all[all.len().saturating_sub(500)..];
                let text: String = keep
                    .iter()
                    .map(|e| serde_json::to_string(e).unwrap_or_default() + "\n")
                    .collect();
                let _ = write_atomic(&p, text.as_bytes(), false);
            }
        }
    }
    fn read_history_locked(&self) -> Vec<HistoryEntry> {
        fs::read_to_string(self.history_path())
            .unwrap_or_default()
            .lines()
            .filter_map(|l| serde_json::from_str(l).ok())
            .collect()
    }
    /// Mais recentes primeiro.
    pub fn history(&self) -> Vec<HistoryEntry> {
        let _g = self.hist.lock().unwrap();
        let mut v = self.read_history_locked();
        v.reverse();
        v
    }
    pub fn clear_history(&self) {
        let _g = self.hist.lock().unwrap();
        let _ = fs::remove_file(self.history_path());
    }
}

/// Diretório de perfil padrão do aplicativo.
pub fn default_profile_dir() -> PathBuf {
    if cfg!(windows) {
        std::env::var_os("APPDATA")
            .map(PathBuf::from)
            .unwrap_or_else(home_dir)
            .join("RenanDrop")
    } else {
        std::env::var_os("XDG_CONFIG_HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|| home_dir().join(".config"))
            .join("RenanDrop")
    }
}
