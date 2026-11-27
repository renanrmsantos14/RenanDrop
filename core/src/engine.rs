//! Motor do RenanDrop: servidor, descoberta, pareamento, fila de envios e recebimentos.
//! A interface (Tauri, CLI ou testes) conversa só com `Engine` e recebe `Event`s.

use crate::crypto::*;
use crate::discovery::Discovery;
use crate::fsx;
use crate::pairing;
use crate::session::*;
use crate::store::*;
use crate::tailnet::{self, route_of, Remote};
use crate::transfer::*;
use anyhow::{anyhow, bail, Result};
use serde::Serialize;
use std::collections::{HashMap, VecDeque};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering::*};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

pub const DEFAULT_PORT: u16 = 47821;

#[derive(Clone, Debug, Serialize)]
pub struct PeerView {
    pub id: String,
    pub name: String,
    pub online: bool,
    pub paired: bool,
    pub auto_accept: Option<bool>,
    pub addr: Option<String>,
    /// "lan", "tailnet", "local" ou "internet" (pela rota do endereço atual).
    pub route: Option<String>,
    /// Aparelho adicionado à mão (pode ser removido da lista).
    pub manual: bool,
}

#[derive(Clone, Debug, Serialize)]
pub struct TransferView {
    pub id: String,
    /// "send" ou "recv"
    pub dir: String,
    pub peer_id: String,
    pub peer_name: String,
    /// queued, connecting, waiting, sending, receiving, retrying, done, failed, cancelled, declined
    pub state: String,
    pub summary: String,
    pub files: u32,
    pub bytes: u64,
    pub total: u64,
    pub speed: f64,
    pub error: Option<String>,
    pub path: Option<String>,
    pub started: i64,
    pub finished: bool,
    /// Rota da conexão: "lan", "tailnet", "local" ou "internet".
    pub route: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Event {
    Peers,
    PairPrompt {
        req_id: String,
        peer_name: String,
        code: String,
        outgoing: bool,
    },
    PairDone {
        ok: bool,
        peer_name: String,
        error: Option<String>,
    },
    Offer {
        tid: String,
        peer_id: String,
        peer_name: String,
        files: u32,
        total: u64,
        summary: String,
    },
    Transfer(TransferView),
    Settings,
    Notice {
        message: String,
    },
}

struct Job {
    id: String,
    dir: &'static str,
    peer_id: String,
    peer_name: String,
    prog: Mutex<Arc<Progress>>,
    st: Mutex<JobState>,
}

struct JobState {
    state: &'static str,
    summary: String,
    files: u32,
    error: Option<String>,
    path: Option<String>,
    started: i64,
    last_bytes: u64,
    last_tick: Instant,
    speed: f64,
    paths: Vec<PathBuf>,
    route: Option<String>,
}

impl Job {
    #[allow(clippy::too_many_arguments)]
    fn new(
        id: String,
        dir: &'static str,
        peer_id: String,
        peer_name: String,
        total: u64,
        summary: String,
        files: u32,
        paths: Vec<PathBuf>,
        state: &'static str,
    ) -> Arc<Job> {
        Arc::new(Job {
            id,
            dir,
            peer_id,
            peer_name,
            prog: Mutex::new(Progress::new(total)),
            st: Mutex::new(JobState {
                state,
                summary,
                files,
                error: None,
                path: None,
                started: now_ms(),
                last_bytes: 0,
                last_tick: Instant::now(),
                speed: 0.0,
                paths,
                route: None,
            }),
        })
    }
    fn view(&self) -> TransferView {
        let s = self.st.lock().unwrap();
        let p = self.prog.lock().unwrap().clone();
        TransferView {
            id: self.id.clone(),
            dir: self.dir.into(),
            peer_id: self.peer_id.clone(),
            peer_name: self.peer_name.clone(),
            state: s.state.into(),
            summary: s.summary.clone(),
            files: s.files,
            bytes: p.bytes.load(Relaxed).min(p.total),
            total: p.total,
            speed: s.speed,
            error: s.error.clone(),
            path: s.path.clone(),
            started: s.started,
            finished: is_final(s.state),
            route: s.route.clone().or_else(|| {
                p.route
                    .lock()
                    .unwrap()
                    .map(|a| route_of(a.ip()).to_string())
            }),
        }
    }
    fn cancelled(&self) -> bool {
        self.prog.lock().unwrap().cancel.load(SeqCst)
    }
}

fn is_final(s: &str) -> bool {
    matches!(s, "done" | "failed" | "cancelled" | "declined")
}

#[derive(Default)]
struct PeerQueue {
    q: VecDeque<Arc<Job>>,
    running: bool,
}

pub struct EngineOptions {
    pub profile: PathBuf,
    pub port: Option<u16>,
    pub name: Option<String>,
    pub recv_dir: Option<PathBuf>,
    pub discovery: bool,
}

struct Inner {
    store: Store,
    port: u16,
    disc: Mutex<Option<Discovery>>,
    manual: Mutex<HashMap<String, Vec<SocketAddr>>>,
    remote: Mutex<HashMap<String, Remote>>,
    rescan: (Mutex<bool>, Condvar),
    ts_state: Mutex<&'static str>,
    tx: Mutex<Sender<Event>>,
    jobs: Mutex<Vec<Arc<Job>>>,
    recv_reg: Mutex<HashMap<String, Arc<RecvTransfer>>>,
    offers: Mutex<HashMap<String, Sender<bool>>>,
    pairs: Mutex<HashMap<String, Sender<bool>>>,
    pair_busy: AtomicBool,
    queues: Mutex<HashMap<String, PeerQueue>>,
    conns: AtomicUsize,
    stop: AtomicBool,
    seq: AtomicUsize,
}

#[derive(Clone)]
pub struct Engine {
    inner: Arc<Inner>,
}

/// Totais de uma oferta: arquivos (sem pastas vazias), bytes e o resumo curto ("pasta e mais 2").
struct Summary {
    files: u32,
    total: u64,
    text: String,
}

fn summarize<'a>(items: impl Iterator<Item = &'a Item>) -> Summary {
    let mut tops = std::collections::BTreeSet::new();
    let mut first = "";
    let (mut files, mut total) = (0, 0);
    for it in items {
        let top = it.path.split('/').next().unwrap_or_default();
        if tops.is_empty() {
            first = top;
        }
        tops.insert(top);
        files += !it.dir as u32;
        total += it.size;
    }
    let text = match tops.len() {
        0 | 1 => first.to_string(),
        n => format!("{first} e mais {}", n - 1),
    };
    Summary { files, total, text }
}

impl Engine {
    pub fn start(opts: EngineOptions) -> Result<(Engine, Receiver<Event>)> {
        let store = Store::open(&opts.profile)?;
        if opts.name.is_some() || opts.recv_dir.is_some() || opts.port.is_some() {
            store.update_settings(|s| {
                if let Some(n) = &opts.name {
                    s.device_name = n.clone();
                }
                if let Some(d) = &opts.recv_dir {
                    s.recv_dir = d.clone();
                }
                if let Some(p) = opts.port {
                    s.port = p;
                }
            })?;
        }
        let want = store.settings().port;
        let (listener, port) =
            bind_listener(if want == 0 { DEFAULT_PORT } else { want }, want != 0)?;
        let (tx, rx) = channel();
        let inner = Arc::new(Inner {
            store,
            port,
            disc: Mutex::new(None),
            manual: Mutex::new(HashMap::new()),
            remote: Mutex::new(HashMap::new()),
            rescan: (Mutex::new(false), Condvar::new()),
            ts_state: Mutex::new("unknown"),
            tx: Mutex::new(tx),
            jobs: Mutex::new(Vec::new()),
            recv_reg: Mutex::new(HashMap::new()),
            offers: Mutex::new(HashMap::new()),
            pairs: Mutex::new(HashMap::new()),
            pair_busy: AtomicBool::new(false),
            queues: Mutex::new(HashMap::new()),
            conns: AtomicUsize::new(0),
            stop: AtomicBool::new(false),
            seq: AtomicUsize::new(0),
        });
        let eng = Engine { inner };
        eng.spawn_acceptor(listener);
        eng.spawn_ticker();
        if opts.discovery {
            let e2 = eng.clone();
            let cb: Arc<dyn Fn() + Send + Sync> = Arc::new(move || e2.emit(Event::Peers));
            let s = eng.inner.store.settings();
            match Discovery::start(&eng.inner.store.identity.id_hex(), &s.device_name, port, cb) {
                Ok(d) => *eng.inner.disc.lock().unwrap() = Some(d),
                Err(e) => log::warn!("descoberta indisponível: {e}"),
            }
            eng.spawn_remote_scanner();
        }
        Ok((eng, rx))
    }

    fn emit(&self, e: Event) {
        let _ = self.inner.tx.lock().unwrap().send(e);
    }

    pub fn id(&self) -> String {
        self.inner.store.identity.id_hex()
    }
    pub fn port(&self) -> u16 {
        self.inner.port
    }
    pub fn settings(&self) -> Settings {
        self.inner.store.settings()
    }
    pub fn update_settings(&self, f: impl FnOnce(&mut Settings)) -> Result<Settings> {
        let old = self.settings().device_name;
        let s = self.inner.store.update_settings(f)?;
        if s.device_name != old {
            if let Some(d) = self.inner.disc.lock().unwrap().as_ref() {
                let _ = d.rename(&s.device_name);
            }
        }
        self.emit(Event::Settings);
        Ok(s)
    }
    pub fn history(&self) -> Vec<HistoryEntry> {
        self.inner.store.history()
    }
    pub fn clear_history(&self) {
        self.inner.store.clear_history()
    }

    /// Para testes e conexão manual: endereço fixo de um aparelho.
    pub fn set_manual_addr(&self, id: &str, addr: SocketAddr) {
        self.inner
            .manual
            .lock()
            .unwrap()
            .insert(id.to_string(), vec![addr]);
        self.emit(Event::Peers);
    }

    pub fn peers(&self) -> Vec<PeerView> {
        let st = &self.inner.store;
        let seen = self
            .inner
            .disc
            .lock()
            .unwrap()
            .as_ref()
            .map(|d| d.seen())
            .unwrap_or_default();
        let remotes = self.inner.remote.lock().unwrap().clone();
        let mine = self.inner.store.settings().remotes;
        let mut out: Vec<PeerView> = Vec::new();
        let view = |id: &str, name: &str, paired: bool, auto: Option<bool>| {
            let a = self.addrs_for(id).first().copied();
            let manual = remotes
                .get(id)
                .map(|r| mine.contains(&r.host))
                .unwrap_or(false);
            PeerView {
                id: id.into(),
                name: name.into(),
                online: a.is_some(),
                paired,
                auto_accept: auto,
                addr: a.map(|a| a.to_string()),
                route: a.map(|a| route_of(a.ip()).to_string()),
                manual,
            }
        };
        for p in st.peers() {
            out.push(view(&p.id, &p.name, true, p.auto_accept));
        }
        for s in &seen {
            if !out.iter().any(|p| p.id == s.id) {
                out.push(view(&s.id, &s.name, false, None));
            }
        }
        for r in remotes.values() {
            if !out.iter().any(|p| p.id == r.id) {
                out.push(view(&r.id, &r.name, false, None));
            }
        }
        out.sort_by(|a, b| {
            (!a.online, !a.paired, a.name.to_lowercase()).cmp(&(
                !b.online,
                !b.paired,
                b.name.to_lowercase(),
            ))
        });
        out
    }

    /// Endereços candidatos do aparelho: manual, mDNS e tailnet, sem repetir, LAN antes da tailnet.
    fn addrs_for(&self, id: &str) -> Vec<SocketAddr> {
        let mut v = self
            .inner
            .manual
            .lock()
            .unwrap()
            .get(id)
            .cloned()
            .unwrap_or_default();
        let found = self
            .inner
            .disc
            .lock()
            .unwrap()
            .as_ref()
            .and_then(|d| d.get(id))
            .map(|s| s.socket_addrs())
            .unwrap_or_default();
        let remote = self.inner.remote.lock().unwrap().get(id).map(|r| r.addr);
        for a in found.into_iter().chain(remote) {
            if !v.contains(&a) {
                v.push(a);
            }
        }
        v.sort_by_key(|a| match route_of(a.ip()) {
            "lan" | "local" => 0,
            "tailnet" => 1,
            _ => 2,
        });
        v
    }

    /// Estado do Tailscale: "unknown" (ainda não verificado), "ok", "missing" (não instalado), "disconnected" ou "off" (desligado nas opções).
    pub fn tailscale_state(&self) -> String {
        if !self.settings().tailscale {
            return "off".into();
        }
        self.inner.ts_state.lock().unwrap().to_string()
    }

    /// Pede uma nova varredura imediata da tailnet e dos endereços manuais.
    pub fn rescan(&self) {
        *self.inner.rescan.0.lock().unwrap() = true;
        self.inner.rescan.1.notify_all();
    }

    /// Adiciona um aparelho por nome (MagicDNS) ou IP. Confirma que o RenanDrop responde antes de guardar.
    pub fn add_remote(&self, text: &str) -> Result<PeerView> {
        let text = text.trim().to_string();
        let addrs = tailnet::resolve(&text, DEFAULT_PORT)?;
        let (id, name, addr) = tailnet::probe(&addrs, Duration::from_millis(1500)).ok_or_else(|| anyhow!("não encontrei o RenanDrop em \"{text}\" (o app está aberto lá? o Tailscale está ligado?)"))?;
        if id == self.id() {
            bail!("esse endereço é este mesmo aparelho");
        }
        self.inner.remote.lock().unwrap().insert(
            id.clone(),
            Remote {
                id: id.clone(),
                name,
                addr,
                host: text.clone(),
            },
        );
        self.update_settings(|s| {
            if !s.remotes.contains(&text) {
                s.remotes.push(text.clone());
            }
        })?;
        self.emit(Event::Peers);
        self.peers()
            .into_iter()
            .find(|p| p.id == id)
            .ok_or_else(|| anyhow!("aparelho não apareceu"))
    }

    /// Remove um aparelho adicionado à mão.
    pub fn forget_remote(&self, id: &str) -> Result<()> {
        let r = self.inner.remote.lock().unwrap().remove(id);
        if let Some(r) = r {
            self.update_settings(|s| s.remotes.retain(|h| *h != r.host))?;
        }
        self.emit(Event::Peers);
        Ok(())
    }

    fn spawn_remote_scanner(&self) {
        let eng = self.clone();
        let _ = std::thread::Builder::new()
            .name("remote-scan".into())
            .spawn(move || {
                while !eng.inner.stop.load(SeqCst) {
                    eng.scan_remotes();
                    let (m, cv) = &eng.inner.rescan;
                    let g = m.lock().unwrap();
                    let (mut g, _) = cv
                        .wait_timeout_while(g, Duration::from_secs(30), |r| {
                            !*r && !eng.inner.stop.load(SeqCst)
                        })
                        .unwrap();
                    *g = false;
                }
            });
    }

    fn scan_remotes(&self) {
        let s = self.settings();
        let me = self.id();
        let mut cands: Vec<(String, Vec<SocketAddr>)> = Vec::new();
        let mut state = "off";
        if s.tailscale {
            match tailnet::tailnet_nodes() {
                Ok(Some(nodes)) => {
                    state = "ok";
                    for n in nodes {
                        let addrs = tailnet::port_span(DEFAULT_PORT)
                            .map(|p| SocketAddr::new(n.ip.into(), p))
                            .collect();
                        cands.push((n.host, addrs));
                    }
                }
                Ok(None) => state = "missing",
                Err(_) => state = "disconnected",
            }
        }
        *self.inner.ts_state.lock().unwrap() = state;
        for h in &s.remotes {
            if let Ok(a) = tailnet::resolve(h, DEFAULT_PORT) {
                cands.push((h.clone(), a));
            }
        }
        let found: Vec<Remote> = std::thread::scope(|sc| {
            let hs: Vec<_> = cands
                .iter()
                .map(|(host, addrs)| {
                    sc.spawn(move || {
                        tailnet::probe(addrs, Duration::from_millis(900)).map(|(id, name, addr)| {
                            Remote {
                                id,
                                name,
                                addr,
                                host: host.clone(),
                            }
                        })
                    })
                })
                .collect();
            hs.into_iter()
                .filter_map(|h| h.join().ok().flatten())
                .collect()
        });
        let mut map: HashMap<String, Remote> = HashMap::new();
        for r in found {
            if r.id != me {
                map.entry(r.id.clone()).or_insert(r);
            }
        }
        let changed = {
            let mut cur = self.inner.remote.lock().unwrap();
            let key = |m: &HashMap<String, Remote>| {
                let mut v: Vec<(String, String)> = m
                    .values()
                    .map(|r| (r.id.clone(), r.addr.to_string()))
                    .collect();
                v.sort();
                v
            };
            let c = key(&cur) != key(&map);
            *cur = map;
            c
        };
        if changed {
            self.emit(Event::Peers);
        }
    }

    pub fn rename_peer(&self, id: &str, name: &str) -> Result<()> {
        self.inner
            .store
            .update_peer(id, |p| p.name = name.trim().to_string())?;
        self.emit(Event::Peers);
        Ok(())
    }
    pub fn set_peer_auto_accept(&self, id: &str, v: Option<bool>) -> Result<()> {
        self.inner.store.update_peer(id, |p| p.auto_accept = v)?;
        self.emit(Event::Peers);
        Ok(())
    }
    pub fn unpair(&self, id: &str) -> Result<()> {
        self.inner.store.remove_peer(id)?;
        self.emit(Event::Peers);
        Ok(())
    }

    // ───────────── Pareamento ─────────────

    fn decide(&self, pp: pairing::PairPrompt) -> bool {
        let req_id = format!("p{}", self.inner.seq.fetch_add(1, SeqCst));
        let (tx, rx) = channel();
        self.inner.pairs.lock().unwrap().insert(req_id.clone(), tx);
        self.emit(Event::PairPrompt {
            req_id: req_id.clone(),
            peer_name: pp.peer_name,
            code: pp.code,
            outgoing: pp.outgoing,
        });
        let r = rx.recv_timeout(Duration::from_secs(120)).unwrap_or(false);
        self.inner.pairs.lock().unwrap().remove(&req_id);
        r
    }

    pub fn confirm_pair(&self, req_id: &str, ok: bool) {
        if let Some(tx) = self.inner.pairs.lock().unwrap().get(req_id) {
            let _ = tx.send(ok);
        }
    }

    /// Inicia o pareamento com um aparelho descoberto (ou com endereço manual registrado).
    pub fn pair(&self, peer_id: &str) -> Result<()> {
        let addrs = self.addrs_for(peer_id);
        if addrs.is_empty() {
            bail!("aparelho não encontrado na rede");
        }
        self.pair_addr(addrs)
    }

    pub fn pair_addr(&self, addrs: Vec<SocketAddr>) -> Result<()> {
        let eng = self.clone();
        std::thread::Builder::new()
            .name("pair-out".into())
            .spawn(move || {
                let st = &eng.inner.store;
                let mut used: Option<SocketAddr> = None;
                let res = connect(&addrs).and_then(|s| {
                    used = s.peer_addr().ok();
                    let e2 = eng.clone();
                    pairing::initiate(s, &st.identity, &st.settings().device_name, &move |pp| {
                        e2.decide(pp)
                    })
                });
                // Pareou por endereço: lembra esse endereço para os próximos envios.
                if let (Ok(Some(p)), Some(a)) = (&res, used) {
                    eng.inner
                        .manual
                        .lock()
                        .unwrap()
                        .insert(hex::encode(p.id), vec![a]);
                }
                eng.finish_pair(res);
            })?;
        Ok(())
    }

    fn finish_pair(&self, res: Result<Option<pairing::Paired>>) {
        match res {
            Ok(Some(p)) => {
                let _ = self.inner.store.add_peer(Peer {
                    id: hex::encode(p.id),
                    name: p.name.clone(),
                    pubkey: hex::encode(&p.public),
                    paired_at: now_ms(),
                    auto_accept: None,
                });
                self.emit(Event::PairDone {
                    ok: true,
                    peer_name: p.name,
                    error: None,
                });
                self.emit(Event::Peers);
            }
            other => self.emit(Event::PairDone {
                ok: false,
                peer_name: String::new(),
                error: Some(match other {
                    Err(e) => e.to_string(),
                    _ => "Pareamento recusado".into(),
                }),
            }),
        }
    }

    // ───────────── Envio ─────────────

    /// Coloca na fila um envio para cada aparelho; devolve os ids dos envios.
    pub fn send(&self, peer_ids: &[String], paths: Vec<PathBuf>) -> Result<Vec<String>> {
        if paths.is_empty() {
            bail!("nenhum arquivo escolhido");
        }
        let items = fsx::scan(&paths)?;
        let sum = summarize(items.iter().map(|(i, _)| i));
        let mut ids = Vec::new();
        for pid in peer_ids {
            let peer = self
                .inner
                .store
                .peer(pid)
                .ok_or_else(|| anyhow!("aparelho não pareado"))?;
            let job = Job::new(
                format!("s{}", self.inner.seq.fetch_add(1, SeqCst)),
                "send",
                pid.clone(),
                peer.name.clone(),
                sum.total,
                sum.text.clone(),
                sum.files,
                paths.clone(),
                "queued",
            );
            self.inner.jobs.lock().unwrap().push(job.clone());
            ids.push(job.id.clone());
            self.emit(Event::Transfer(job.view()));
            self.enqueue(job);
        }
        Ok(ids)
    }

    fn enqueue(&self, job: Arc<Job>) {
        let pid = job.peer_id.clone();
        let spawn = {
            let mut qs = self.inner.queues.lock().unwrap();
            let q = qs.entry(pid.clone()).or_default();
            q.q.push_back(job);
            let spawn = !q.running;
            q.running = true;
            spawn
        };
        if spawn {
            let eng = self.clone();
            let _ = std::thread::Builder::new()
                .name("send-queue".into())
                .spawn(move || loop {
                    let next = {
                        let mut qs = eng.inner.queues.lock().unwrap();
                        let q = qs.get_mut(&pid).unwrap();
                        match q.q.pop_front() {
                            Some(j) => j,
                            None => {
                                q.running = false;
                                return;
                            }
                        }
                    };
                    eng.run_send(&next);
                });
        }
    }

    fn set_state(&self, job: &Job, state: &'static str, err: Option<String>) {
        {
            let mut s = job.st.lock().unwrap();
            s.state = state;
            s.error = err;
        }
        self.emit(Event::Transfer(job.view()));
    }

    fn run_send(&self, job: &Arc<Job>) {
        let st = &self.inner.store;
        if job.cancelled() {
            self.finish_job(job, "cancelled", None);
            return;
        }
        let Some(peer) = st.peer(&job.peer_id) else {
            self.finish_job(job, "failed", Some("aparelho não está mais pareado".into()));
            return;
        };
        let paths = job.st.lock().unwrap().paths.clone();
        let items = match fsx::scan(&paths) {
            Ok(i) => i,
            Err(e) => {
                self.finish_job(job, "failed", Some(e.to_string()));
                return;
            }
        };
        let peer_pub = hex::decode(&peer.pubkey).unwrap_or_default();
        let tid = transfer_id(
            &st.identity.id(),
            &id_of(&peer_pub),
            items.iter().map(|(i, _)| i),
        );
        let total = total_size(items.iter().map(|(i, _)| i));
        let backoff = [1u64, 2, 3, 5, 8, 13, 20];
        let mut attempt = 0usize;
        let mut best = 0u64;
        loop {
            let prog = Progress::new(total);
            let seen = prog.clone();
            if job.cancelled() {
                prog.cancel.store(true, SeqCst);
            }
            *job.prog.lock().unwrap() = prog.clone();
            let addrs = self.addrs_for(&job.peer_id);
            let eng = self.clone();
            let jb = job.clone();
            let settings = st.settings();
            let params = SendParams {
                me: st.identity.clone(),
                peer_pub: peer_pub.clone(),
                addrs: addrs.clone(),
                from_name: settings.device_name,
                lanes: settings.lanes,
                prog,
                on_stage: Box::new(move |stg| {
                    let s = match stg {
                        Stage::Connecting => "connecting",
                        Stage::WaitingAccept => "waiting",
                        Stage::Sending => "sending",
                    };
                    eng.set_state(&jb, s, None);
                }),
            };
            let res = if addrs.is_empty() {
                Err(SendError::Io("aparelho fora da rede".into()))
            } else {
                send_once(&params, &items, &tid)
            };
            match res {
                Ok(()) => return self.finish_job(job, "done", None),
                Err(SendError::Cancelled) => return self.finish_job(job, "cancelled", None),
                Err(SendError::Declined(r)) => return self.finish_job(job, "declined", Some(r)),
                Err(e) if !e.retriable() => {
                    return self.finish_job(job, "failed", Some(e.to_string()))
                }
                Err(e) => {
                    // Quem avançou desde a última queda ganha novas tentativas (transferência grande em rede ruim).
                    let now = seen.bytes.load(Relaxed);
                    if now > best {
                        best = now;
                        attempt = 0;
                    }
                    if attempt >= backoff.len() {
                        return self.finish_job(job, "failed", Some(e.to_string()));
                    }
                    self.set_state(job, "retrying", Some(e.to_string()));
                    let wait = backoff[attempt];
                    attempt += 1;
                    let t0 = Instant::now();
                    while t0.elapsed() < Duration::from_secs(wait) && !job.cancelled() {
                        std::thread::sleep(Duration::from_millis(100));
                    }
                    if job.cancelled() {
                        return self.finish_job(job, "cancelled", None);
                    }
                }
            }
        }
    }

    fn finish_job(&self, job: &Arc<Job>, state: &'static str, err: Option<String>) {
        {
            let mut s = job.st.lock().unwrap();
            s.state = state;
            s.error = err;
            s.speed = 0.0;
        }
        let v = job.view();
        self.inner.store.push_history(&HistoryEntry {
            ts: now_ms(),
            dir: job.dir.into(),
            peer: job.peer_name.clone(),
            summary: v.summary.clone(),
            files: v.files,
            bytes: if state == "done" { v.total } else { v.bytes },
            status: state.into(),
            path: v.path.clone(),
        });
        self.emit(Event::Transfer(v));
    }

    pub fn cancel(&self, job_id: &str) {
        let jobs = self.inner.jobs.lock().unwrap().clone();
        if let Some(j) = jobs.iter().find(|j| j.id == job_id) {
            j.prog.lock().unwrap().cancel.store(true, SeqCst);
            let queued = j.st.lock().unwrap().state == "queued";
            if queued {
                if let Some(q) = self.inner.queues.lock().unwrap().get_mut(&j.peer_id) {
                    q.q.retain(|x| x.id != j.id);
                }
                self.finish_job(j, "cancelled", None);
            }
        }
    }

    /// Repete um envio que falhou (retoma de onde parou).
    pub fn retry(&self, job_id: &str) -> Result<()> {
        let jobs = self.inner.jobs.lock().unwrap().clone();
        let j = jobs
            .iter()
            .find(|j| j.id == job_id && j.dir == "send")
            .ok_or_else(|| anyhow!("envio não encontrado"))?;
        if !is_final(j.st.lock().unwrap().state) {
            bail!("o envio ainda está em andamento");
        }
        let paths = j.st.lock().unwrap().paths.clone();
        // Enfileira o novo antes de tirar o antigo: se falhar, o histórico fica.
        self.send(std::slice::from_ref(&j.peer_id), paths)?;
        self.inner.jobs.lock().unwrap().retain(|x| x.id != job_id);
        Ok(())
    }

    pub fn transfers(&self) -> Vec<TransferView> {
        self.inner
            .jobs
            .lock()
            .unwrap()
            .iter()
            .map(|j| j.view())
            .collect()
    }

    pub fn clear_finished(&self) {
        self.inner
            .jobs
            .lock()
            .unwrap()
            .retain(|j| !is_final(j.st.lock().unwrap().state));
    }

    fn spawn_ticker(&self) {
        let eng = self.clone();
        let _ = std::thread::Builder::new()
            .name("ticker".into())
            .spawn(move || {
                while !eng.inner.stop.load(SeqCst) {
                    let jobs = eng.inner.jobs.lock().unwrap().clone();
                    let mut active = false;
                    for j in jobs {
                        let live = matches!(j.st.lock().unwrap().state, "sending" | "receiving");
                        if !live {
                            continue;
                        }
                        active = true;
                        {
                            let b = j.prog.lock().unwrap().bytes.load(Relaxed);
                            let mut s = j.st.lock().unwrap();
                            let dt = s.last_tick.elapsed().as_secs_f64().max(0.001);
                            let inst = b.saturating_sub(s.last_bytes) as f64 / dt;
                            s.speed = if s.speed == 0.0 {
                                inst
                            } else {
                                s.speed * 0.6 + inst * 0.4
                            };
                            s.last_bytes = b;
                            s.last_tick = Instant::now();
                        }
                        eng.emit(Event::Transfer(j.view()));
                    }
                    std::thread::sleep(Duration::from_millis(if active { 250 } else { 1000 }));
                }
            });
    }

    // ───────────── Recebimento ─────────────

    pub fn respond_offer(&self, tid: &str, accept: bool) {
        if let Some(tx) = self.inner.offers.lock().unwrap().get(tid) {
            let _ = tx.send(accept);
        }
    }

    fn spawn_acceptor(&self, listener: TcpListener) {
        let eng = self.clone();
        let _ = std::thread::Builder::new()
            .name("acceptor".into())
            .spawn(move || {
                for conn in listener.incoming() {
                    if eng.inner.stop.load(SeqCst) {
                        return;
                    }
                    let Ok(s) = conn else { continue };
                    if eng.inner.conns.load(SeqCst) >= 64 {
                        continue;
                    }
                    let e2 = eng.clone();
                    let _ = std::thread::Builder::new()
                        .name("conn".into())
                        .spawn(move || {
                            e2.inner.conns.fetch_add(1, SeqCst);
                            if let Err(e) = e2.handle_conn(s) {
                                log::debug!("conexão encerrada: {e}");
                            }
                            e2.inner.conns.fetch_sub(1, SeqCst);
                        });
                }
            });
    }

    fn handle_conn(&self, mut s: TcpStream) -> Result<()> {
        s.set_nodelay(true)?;
        s.set_read_timeout(Some(Duration::from_secs(20)))?;
        s.set_write_timeout(Some(Duration::from_secs(60)))?;
        let kind = read_preamble(&mut s)?;
        let st = &self.inner.store;
        let from = s.peer_addr().ok();
        match kind {
            KIND_HELLO => {
                tailnet::answer_hello(&mut s, &st.identity.id_hex(), &st.settings().device_name)
            }
            KIND_PAIR => {
                if self.inner.pair_busy.swap(true, SeqCst) {
                    bail!("pareamento já em andamento");
                }
                let e2 = self.clone();
                let res =
                    pairing::respond(s, &st.identity, &st.settings().device_name, &move |pp| {
                        e2.decide(pp)
                    });
                self.inner.pair_busy.store(false, SeqCst);
                self.finish_pair(res);
                Ok(())
            }
            KIND_SESSION => {
                let (pid, mut r, mut w) = server_handshake(s, &st.identity, |id| st.peer_pub(id))?;
                let peer_id = hex::encode(pid);
                match recv_msg(&mut r)? {
                    Msg::Offer {
                        tid, items, seg, ..
                    } => {
                        if seg != SEG {
                            send_reject(&mut w, "tamanho de segmento não suportado")?;
                            bail!("segmento incompatível");
                        }
                        self.serve_offer(&peer_id, tid, items, &mut r, &mut w, from)
                    }
                    Msg::Join { tid } => {
                        let t = self.inner.recv_reg.lock().unwrap().get(&tid).cloned();
                        match t {
                            Some(t) if t.peer_id == peer_id => {
                                send_msg(&mut w, &Msg::Joined)?;
                                t.lane_loop(&mut r, &mut w).map(|_| ())
                            }
                            _ => {
                                send_reject(&mut w, "transferência desconhecida")?;
                                bail!("join sem transferência");
                            }
                        }
                    }
                    _ => bail!("mensagem inicial inesperada"),
                }
            }
            _ => bail!("tipo de conexão desconhecido"),
        }
    }

    fn serve_offer(
        &self,
        peer_id: &str,
        tid: String,
        items: Vec<Item>,
        r: &mut Reader,
        w: &mut Writer,
        from: Option<SocketAddr>,
    ) -> Result<()> {
        let st = &self.inner.store;
        let peer = st
            .peer(peer_id)
            .ok_or_else(|| anyhow!("aparelho não pareado"))?;
        let settings = st.settings();
        let Summary {
            files,
            total,
            text: summary,
        } = summarize(items.iter());
        let auto = peer.auto_accept.unwrap_or(settings.auto_accept)
            || has_saved_state(&settings.recv_dir, peer_id, &tid);

        let job = Job::new(
            tid.clone(),
            "recv",
            peer_id.to_string(),
            peer.name.clone(),
            total,
            summary.clone(),
            files,
            vec![],
            "waiting",
        );
        job.st.lock().unwrap().route = from.map(|a| route_of(a.ip()).to_string());
        {
            let mut js = self.inner.jobs.lock().unwrap();
            js.retain(|j| !(j.id == tid && j.dir == "recv"));
            js.push(job.clone());
        }
        self.emit(Event::Transfer(job.view()));
        if !auto {
            let (tx, rx) = channel();
            self.inner.offers.lock().unwrap().insert(tid.clone(), tx);
            self.emit(Event::Offer {
                tid: tid.clone(),
                peer_id: peer_id.to_string(),
                peer_name: peer.name.clone(),
                files,
                total,
                summary: summary.clone(),
            });
            let ok = rx.recv_timeout(Duration::from_secs(120)).unwrap_or(false);
            self.inner.offers.lock().unwrap().remove(&tid);
            if !ok {
                let _ = send_reject(w, "recusado pelo usuário");
                self.finish_job(&job, "declined", Some("recusado".into()));
                return Ok(());
            }
        }
        let t = match RecvTransfer::prepare(&settings.recv_dir, peer_id, &tid, items) {
            Ok(t) => t,
            Err(e) => {
                let _ = send_reject(w, e.to_string());
                self.finish_job(&job, "failed", Some(e.to_string()));
                return Err(e);
            }
        };
        // Reconexão do mesmo envio: a conexão antiga perde.
        if let Some(old) = self
            .inner
            .recv_reg
            .lock()
            .unwrap()
            .insert(tid.clone(), t.clone())
        {
            old.superseded.store(true, SeqCst);
        }
        *job.prog.lock().unwrap() = t.prog.clone();
        job.st.lock().unwrap().path = t
            .top_paths(&settings.recv_dir)
            .first()
            .map(|p| p.to_string_lossy().into_owned());
        if let Err(e) = send_msg(w, &Msg::Accept { have: t.have() }) {
            // O remetente saiu enquanto a pessoa decidia: não deixa a linha "aguardando" para sempre.
            self.unregister_recv(&tid, &t);
            self.finish_job(
                &job,
                "failed",
                Some("o remetente desconectou antes do início".into()),
            );
            return Err(e);
        }
        self.set_state(&job, "receiving", None);
        let res = t.lane_loop(r, w);
        self.unregister_recv(&tid, &t);
        match res {
            Ok(LaneEnd::Complete) => self.finish_job(&job, "done", None),
            Ok(LaneEnd::PeerCancelled) => {
                t.cleanup();
                self.finish_job(&job, "cancelled", Some("cancelado pelo remetente".into()));
            }
            Ok(LaneEnd::LocalCancelled) => {
                t.cleanup();
                self.finish_job(&job, "cancelled", None);
            }
            Ok(LaneEnd::Superseded) => {}
            Err(e) => {
                t.save();
                if !t.superseded.load(SeqCst) {
                    self.finish_job(
                        &job,
                        "failed",
                        Some(format!(
                            "{e} (a parte recebida fica guardada; reenvie para retomar)"
                        )),
                    );
                }
                return Err(e);
            }
        }
        Ok(())
    }

    /// Tira o recebimento do registro, a não ser que uma reconexão já o tenha substituído.
    fn unregister_recv(&self, tid: &str, t: &Arc<RecvTransfer>) {
        let mut reg = self.inner.recv_reg.lock().unwrap();
        if reg.get(tid).is_some_and(|x| Arc::ptr_eq(x, t)) {
            reg.remove(tid);
        }
    }

    pub fn shutdown(&self) {
        self.inner.stop.store(true, SeqCst);
        if let Some(d) = self.inner.disc.lock().unwrap().take() {
            d.stop();
        }
        // Destrava o accept com uma conexão vazia.
        let _ = TcpStream::connect_timeout(
            &SocketAddr::new(Ipv4Addr::LOCALHOST.into(), self.inner.port),
            Duration::from_millis(200),
        );
    }
}

fn bind_listener(first: u16, exact: bool) -> Result<(TcpListener, u16)> {
    let tries = if exact { 1 } else { tailnet::PORT_SPAN };
    let mut last = None;
    for p in first..first.saturating_add(tries) {
        match TcpListener::bind(("0.0.0.0", p)) {
            Ok(l) => {
                let port = l.local_addr()?.port();
                return Ok((l, port));
            }
            Err(e) => last = Some(e),
        }
    }
    Err(anyhow!(
        "porta {first} indisponível: {}",
        last.map(|e| e.to_string()).unwrap_or_default()
    ))
}
