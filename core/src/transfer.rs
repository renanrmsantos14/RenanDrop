//! Plano de dados: envio e recebimento em segmentos paralelos, com retomada e hash por segmento.

use crate::crypto::*;
use crate::fsx::*;
use crate::session::*;
use anyhow::{anyhow, bail, Result};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, VecDeque};
use std::fs::{self, File, OpenOptions};
use std::net::{Shutdown, SocketAddr, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering::*};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

pub const SEG: u64 = 8 << 20;
pub const FRAME: usize = 256 << 10;
/// Itens por envio e tamanho máximo por arquivo aceitos (a oferta cabe em um quadro).
pub const MAX_ITEMS: usize = 200_000;
pub const MAX_FILE: u64 = 1 << 46;

pub fn nsegs(size: u64) -> u32 {
    size.div_ceil(SEG) as u32
}
pub fn seg_len(size: u64, s: u32) -> u64 {
    SEG.min(size - s as u64 * SEG)
}

pub fn total_size<'a>(items: impl IntoIterator<Item = &'a Item>) -> u64 {
    items.into_iter().map(|i| i.size).sum()
}

/// Uma linha por arquivo, um flag por segmento; `have` marca os segmentos já recebidos.
fn done_map(sizes: impl Iterator<Item = u64>, have: &[Range]) -> Vec<Vec<bool>> {
    let mut done: Vec<Vec<bool>> = sizes.map(|n| vec![false; nsegs(n) as usize]).collect();
    for Range(f, a, b) in have {
        if let Some(v) = done.get_mut(*f as usize) {
            for s in *a..(*b).min(v.len() as u32) {
                v[s as usize] = true;
            }
        }
    }
    done
}

/// Bytes dos segmentos marcados de um arquivo.
fn done_bytes(size: u64, segs: &[bool]) -> u64 {
    (0..segs.len() as u32)
        .filter(|&s| segs[s as usize])
        .map(|s| seg_len(size, s))
        .sum()
}

/// Contadores compartilhados com a interface.
pub struct Progress {
    pub bytes: AtomicU64,
    pub total: u64,
    pub cancel: AtomicBool,
    /// Endereço usado na conexão principal (mostra a rota: LAN, tailnet...).
    pub route: Mutex<Option<SocketAddr>>,
}

impl Progress {
    pub fn new(total: u64) -> Arc<Progress> {
        Arc::new(Progress {
            bytes: AtomicU64::new(0),
            total,
            cancel: AtomicBool::new(false),
            route: Mutex::new(None),
        })
    }
}

/// Identificador estável: o mesmo conteúdo para o mesmo par de aparelhos gera o mesmo id (permite retomar).
pub fn transfer_id<'a>(
    from: &[u8; 16],
    to: &[u8; 16],
    items: impl IntoIterator<Item = &'a Item>,
) -> String {
    let mut h = Sha256::new();
    h.update(from);
    h.update(to);
    for it in items {
        h.update(it.path.as_bytes());
        h.update([0]);
        h.update(it.size.to_be_bytes());
        h.update(it.mtime.to_be_bytes());
        h.update([it.dir as u8]);
    }
    hex::encode(&h.finalize()[..16])
}

// ───────────────────────────── Envio ─────────────────────────────

#[derive(Debug, Clone)]
pub enum SendError {
    NotPaired,
    Declined(String),
    Cancelled,
    /// Erro informado pelo destino (disco cheio etc.): não adianta repetir.
    Remote(String),
    /// Falha de rede: vale tentar de novo.
    Io(String),
}

impl SendError {
    pub fn retriable(&self) -> bool {
        matches!(self, SendError::Io(_))
    }
}

impl std::fmt::Display for SendError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SendError::NotPaired => {
                write!(f, "o outro aparelho não reconhece este (pareie de novo)")
            }
            SendError::Declined(r) => write!(f, "recusado: {r}"),
            SendError::Cancelled => write!(f, "cancelado"),
            SendError::Remote(r) => write!(f, "erro no destino: {r}"),
            SendError::Io(r) => write!(f, "falha de rede: {r}"),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Stage {
    Connecting,
    WaitingAccept,
    Sending,
}

pub struct SendParams {
    pub me: Arc<Identity>,
    pub peer_pub: Vec<u8>,
    pub addrs: Vec<SocketAddr>,
    pub from_name: String,
    pub lanes: u32,
    pub prog: Arc<Progress>,
    pub on_stage: Box<dyn Fn(Stage) + Send + Sync>,
}

pub fn connect(addrs: &[SocketAddr]) -> Result<TcpStream> {
    let mut last = anyhow!("sem endereço para o aparelho");
    for a in addrs {
        match TcpStream::connect_timeout(a, Duration::from_secs(4)) {
            Ok(s) => {
                s.set_nodelay(true)?;
                return Ok(s);
            }
            Err(e) => last = anyhow!("{a}: {e}"),
        }
    }
    Err(last)
}

struct Shared {
    queue: Mutex<VecDeque<(u32, u32)>>,
    remaining: AtomicUsize,
    failed: Mutex<Option<SendError>>,
    is_failed: AtomicBool,
    retries: Mutex<HashMap<(u32, u32), u8>>,
    cv: Condvar,
    cvm: Mutex<()>,
    complete: AtomicBool,
    finished: AtomicBool,
    socks: Mutex<Vec<TcpStream>>,
}

impl Shared {
    fn fail(&self, e: SendError) {
        let mut g = self.failed.lock().unwrap();
        if g.is_none() {
            *g = Some(e);
        }
        self.is_failed.store(true, SeqCst);
        for s in self.socks.lock().unwrap().iter() {
            let _ = s.shutdown(Shutdown::Both);
        }
        self.notify();
    }
    /// Erro registrado por qualquer pista, se houver.
    fn failure(&self) -> Option<SendError> {
        if !self.is_failed.load(SeqCst) {
            return None;
        }
        Some(
            self.failed
                .lock()
                .unwrap()
                .clone()
                .unwrap_or(SendError::Io("falha".into())),
        )
    }
    fn notify(&self) {
        let _g = self.cvm.lock().unwrap();
        self.cv.notify_all();
    }
    fn wait(&self, ms: u64) {
        let g = self.cvm.lock().unwrap();
        let _ = self.cv.wait_timeout(g, Duration::from_millis(ms)).unwrap();
    }
}

fn map_hs_err(e: anyhow::Error) -> SendError {
    if e.downcast_ref::<NotPaired>().is_some() {
        SendError::NotPaired
    } else {
        SendError::Io(e.to_string())
    }
}

fn io_err(e: impl std::fmt::Display) -> SendError {
    SendError::Io(e.to_string())
}

/// Uma tentativa de envio. Para retomar, basta chamar de novo com os mesmos itens.
pub fn send_once(p: &SendParams, items: &[(Item, PathBuf)], tid: &str) -> Result<(), SendError> {
    (p.on_stage)(Stage::Connecting);
    let stream = connect(&p.addrs).map_err(io_err)?;
    let used = stream.peer_addr().ok();
    *p.prog.route.lock().unwrap() = used;
    let ctl = stream.try_clone().map_err(io_err)?;
    let (mut r0, mut w0) = client_handshake(stream, &p.me, &p.peer_pub).map_err(map_hs_err)?;
    if items.len() > MAX_ITEMS {
        return Err(SendError::Remote(format!(
            "arquivos demais num envio (máximo {MAX_ITEMS}); envie em partes"
        )));
    }
    let list: Vec<Item> = items.iter().map(|(i, _)| i.clone()).collect();
    let total = total_size(&list);
    send_msg(
        &mut w0,
        &Msg::Offer {
            tid: tid.into(),
            from: p.from_name.clone(),
            items: list,
            total,
            lanes: p.lanes,
            seg: SEG,
        },
    )
    .map_err(io_err)?;
    (p.on_stage)(Stage::WaitingAccept);
    ctl.set_read_timeout(Some(Duration::from_secs(150))).ok();
    // A leitura do aceite bloqueia; um observador fecha o socket se o envio for cancelado.
    let watcher_stop = Arc::new(AtomicBool::new(false));
    let wctl = ctl.try_clone().map_err(io_err)?;
    let wprog = p.prog.clone();
    let wstop = watcher_stop.clone();
    let watcher = std::thread::spawn(move || {
        while !wstop.load(SeqCst) {
            if wprog.cancel.load(SeqCst) {
                let _ = wctl.shutdown(Shutdown::Both);
                return;
            }
            std::thread::sleep(Duration::from_millis(150));
        }
    });
    let reply = recv_msg(&mut r0);
    let cancelled = p.prog.cancel.load(SeqCst);
    let have = match reply {
        Ok(Msg::Accept { have }) => have,
        other => {
            watcher_stop.store(true, SeqCst);
            let _ = watcher.join();
            return Err(match other {
                Ok(Msg::Reject { .. }) | Err(_) if cancelled => SendError::Cancelled,
                Ok(Msg::Reject { reason }) => SendError::Declined(reason),
                Ok(_) => SendError::Io("resposta inesperada".into()),
                Err(e) => io_err(e),
            });
        }
    };
    ctl.set_read_timeout(Some(Duration::from_secs(60))).ok();
    ctl.set_write_timeout(Some(Duration::from_secs(60))).ok();
    (p.on_stage)(Stage::Sending);

    // Segmentos que faltam.
    let done = done_map(items.iter().map(|(i, _)| i.size), &have);
    let mut queue = VecDeque::new();
    let mut already = 0u64;
    for (f, v) in done.iter().enumerate() {
        already += done_bytes(items[f].0.size, v);
        for (s, d) in v.iter().enumerate() {
            if !*d {
                queue.push_back((f as u32, s as u32));
            }
        }
    }
    p.prog.bytes.store(already, SeqCst);
    let todo = queue.len();
    let nlanes = (p.lanes.max(1) as usize).min(todo.max(1));
    let sh = Shared {
        queue: Mutex::new(queue),
        remaining: AtomicUsize::new(todo),
        failed: Mutex::new(None),
        is_failed: AtomicBool::new(false),
        retries: Mutex::new(HashMap::new()),
        cv: Condvar::new(),
        cvm: Mutex::new(()),
        complete: AtomicBool::new(false),
        finished: AtomicBool::new(false),
        socks: Mutex::new(vec![ctl.try_clone().map_err(io_err)?]),
    };
    // O observador do aceite passa o cancelamento para `cancel_watch`, que vale durante o envio.
    let result = std::thread::scope(|sc| -> Result<(), SendError> {
        let sh = &sh;
        let prog = &p.prog;
        let cancel_watch = sc.spawn(move || {
            while !sh.finished.load(SeqCst) && !sh.is_failed.load(SeqCst) {
                if prog.cancel.load(SeqCst) {
                    sh.fail(SendError::Cancelled);
                    return;
                }
                sh.wait(100);
            }
        });
        watcher_stop.store(true, SeqCst);

        let reader0 = sc.spawn(move || lane_reader(sh, r0, items, prog));
        let writer0 = sc.spawn(move || lane_writer(sh, w0, items, prog));
        let mut readers = vec![reader0];
        for _ in 1..nlanes {
            // As demais pistas seguem a rota que funcionou na primeira conexão.
            let mut addrs = p.addrs.clone();
            if let Some(u) = used {
                addrs.retain(|a| *a != u);
                addrs.insert(0, u);
            }
            let me = p.me.clone();
            let pp = p.peer_pub.clone();
            let tid = tid.to_string();
            let h = sc.spawn(move || {
                let setup = (|| -> Result<(Reader, Writer), SendError> {
                    let s = connect(&addrs).map_err(io_err)?;
                    let c = s.try_clone().map_err(io_err)?;
                    c.set_read_timeout(Some(Duration::from_secs(60))).ok();
                    c.set_write_timeout(Some(Duration::from_secs(60))).ok();
                    sh.socks.lock().unwrap().push(c);
                    let (mut r, mut w) = client_handshake(s, &me, &pp).map_err(map_hs_err)?;
                    send_msg(&mut w, &Msg::Join { tid }).map_err(io_err)?;
                    match recv_msg(&mut r) {
                        Ok(Msg::Joined) => Ok((r, w)),
                        Ok(Msg::Reject { reason }) => Err(SendError::Remote(reason)),
                        Ok(_) => Err(SendError::Io("resposta inesperada".into())),
                        Err(e) => Err(io_err(e)),
                    }
                })();
                match setup {
                    Ok((r, w)) => {
                        let rd = sc.spawn(move || lane_reader(sh, r, items, prog));
                        let _ = lane_writer(sh, w, items, prog);
                        let _ = rd.join();
                    }
                    // Uma pista extra que falha não derruba o envio: as outras assumem a fila.
                    Err(SendError::Io(_)) => {}
                    Err(e) => sh.fail(e),
                }
            });
            readers.push(h);
        }

        let w0 = writer0
            .join()
            .map_err(|_| SendError::Io("pânico no envio".into()))?;
        let outcome = (|| -> Result<(), SendError> {
            if let Some(e) = sh.failure() {
                return Err(e);
            }
            let mut w0 = w0.ok_or_else(|| SendError::Io("pista principal encerrada".into()))?;
            // Espera as confirmações de todos os segmentos.
            while sh.remaining.load(SeqCst) > 0 && !sh.is_failed.load(SeqCst) {
                sh.wait(100);
            }
            if let Some(e) = sh.failure() {
                return Err(e);
            }
            send_msg(&mut w0, &Msg::Done).map_err(io_err)?;
            let t0 = Instant::now();
            while !sh.complete.load(SeqCst) && !sh.is_failed.load(SeqCst) {
                if t0.elapsed() > Duration::from_secs(120) {
                    return Err(SendError::Io("o destino não confirmou o fim".into()));
                }
                sh.wait(100);
            }
            if let Some(e) = sh.failure() {
                return Err(e);
            }
            Ok(())
        })();
        sh.finished.store(true, SeqCst);
        for s in sh.socks.lock().unwrap().iter() {
            let _ = s.shutdown(Shutdown::Both);
        }
        sh.notify();
        let _ = cancel_watch.join();
        for h in readers {
            let _ = h.join();
        }
        outcome
    });
    let _ = watcher.join();
    result
}

fn is_timeout(e: &anyhow::Error) -> bool {
    e.downcast_ref::<std::io::Error>().is_some_and(|e| {
        matches!(
            e.kind(),
            std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
        )
    })
}

/// Espera a próxima mensagem numa pista que pode ficar ociosa (as outras pistas ainda trabalham).
/// O tempo limite do socket só vale como erro se a transferência inteira ficar parada por 3 minutos.
fn recv_msg_patient(r: &mut Reader, prog: &Progress) -> Result<Msg> {
    let mut seen = (prog.bytes.load(Relaxed), Instant::now());
    loop {
        match recv_msg(r) {
            Err(e) if is_timeout(&e) && !prog.cancel.load(SeqCst) => {
                let now = prog.bytes.load(Relaxed);
                if now != seen.0 {
                    seen = (now, Instant::now());
                } else if seen.1.elapsed() > Duration::from_secs(180) {
                    return Err(e);
                }
            }
            other => return other,
        }
    }
}

fn lane_reader(sh: &Shared, mut r: Reader, items: &[(Item, PathBuf)], prog: &Progress) {
    loop {
        match recv_msg_patient(&mut r, prog) {
            Ok(Msg::SegAck { .. }) => {
                if sh
                    .remaining
                    .fetch_update(SeqCst, SeqCst, |n| n.checked_sub(1))
                    .is_err()
                {
                    sh.fail(SendError::Remote("confirmação de segmento a mais".into()));
                    return;
                }
                sh.notify();
            }
            Ok(Msg::SegBad { f, s }) => {
                if items
                    .get(f as usize)
                    .is_none_or(|(it, _)| it.dir || s >= nsegs(it.size))
                {
                    sh.fail(SendError::Remote(
                        "o destino pediu um segmento inexistente".into(),
                    ));
                    return;
                }
                let n = {
                    let mut g = sh.retries.lock().unwrap();
                    let e = g.entry((f, s)).or_insert(0);
                    *e += 1;
                    *e
                };
                if n > 3 {
                    sh.fail(SendError::Remote("hash não confere repetidamente".into()));
                    return;
                }
                if let Some((it, _)) = items.get(f as usize) {
                    prog.bytes.fetch_sub(seg_len(it.size, s), SeqCst);
                }
                sh.queue.lock().unwrap().push_back((f, s));
                sh.notify();
            }
            Ok(Msg::Complete) => {
                sh.complete.store(true, SeqCst);
                sh.notify();
                return;
            }
            Ok(Msg::Cancel) => {
                sh.fail(SendError::Declined("cancelado no destino".into()));
                return;
            }
            Ok(Msg::Fail { reason }) => {
                sh.fail(SendError::Remote(reason));
                return;
            }
            Ok(_) => {}
            Err(e) => {
                if !sh.finished.load(SeqCst) && !sh.is_failed.load(SeqCst) {
                    sh.fail(io_err(e));
                }
                return;
            }
        }
    }
}

/// Envia segmentos da fila até acabar; devolve o escritor (a pista 0 ainda precisa mandar `Done`).
fn lane_writer(
    sh: &Shared,
    mut w: Writer,
    items: &[(Item, PathBuf)],
    prog: &Progress,
) -> Option<Writer> {
    let mut buf = vec![0u8; FRAME];
    let mut cur: Option<(u32, File)> = None;
    loop {
        if sh.is_failed.load(SeqCst) {
            return None;
        }
        let next = sh.queue.lock().unwrap().pop_front();
        match next {
            Some((f, s)) => {
                let (it, path) = &items[f as usize];
                if cur.as_ref().map(|c| c.0) != Some(f) {
                    match File::open(path) {
                        Ok(file) => cur = Some((f, file)),
                        Err(e) => {
                            sh.fail(SendError::Io(format!("{}: {e}", path.display())));
                            return None;
                        }
                    }
                }
                let file = &cur.as_ref().unwrap().1;
                if let Err(e) = send_seg(&mut w, file, f, s, it.size, &mut buf, prog) {
                    if !sh.is_failed.load(SeqCst) {
                        sh.fail(io_err(e));
                    }
                    return None;
                }
            }
            None => {
                if sh.remaining.load(SeqCst) == 0 {
                    return Some(w);
                }
                sh.wait(50);
            }
        }
    }
}

fn send_seg(
    w: &mut Writer,
    file: &File,
    f: u32,
    s: u32,
    size: u64,
    buf: &mut [u8],
    prog: &Progress,
) -> Result<()> {
    let len = seg_len(size, s);
    send_msg(w, &Msg::Seg { f, s, len })?;
    let mut hasher = Sha256::new();
    let base = s as u64 * SEG;
    let mut off = 0u64;
    while off < len {
        let n = (len - off).min(FRAME as u64) as usize;
        read_exact_at(file, &mut buf[..n], base + off)
            .map_err(|e| anyhow!("lendo o arquivo: {e}"))?;
        hasher.update(&buf[..n]);
        w.send(T_DATA, &buf[..n])?;
        prog.bytes.fetch_add(n as u64, Relaxed);
        off += n as u64;
    }
    send_msg(
        w,
        &Msg::SegEnd {
            sha: hex::encode(hasher.finalize()),
        },
    )
}

// ───────────────────────────── Recebimento ─────────────────────────────

#[derive(Serialize, Deserialize)]
struct State {
    tid: String,
    items: Vec<Item>,
    names: Vec<String>,
    ranges: Vec<Range>,
    finished: Vec<bool>,
}

struct RInner {
    done: Vec<Vec<bool>>,
    left: Vec<u32>,
    finished: Vec<bool>,
    open: HashMap<u32, Arc<File>>,
    last_save: Instant,
}

pub struct RecvTransfer {
    pub tid: String,
    pub peer_id: String,
    pub items: Vec<Item>,
    pub prog: Arc<Progress>,
    pub superseded: AtomicBool,
    names: Vec<PathBuf>,
    part_dir: PathBuf,
    inner: Mutex<RInner>,
}

#[derive(Debug, PartialEq)]
pub enum LaneEnd {
    Complete,
    PeerCancelled,
    LocalCancelled,
    Superseded,
}

fn ranges_of(done: &[Vec<bool>]) -> Vec<Range> {
    let mut out = Vec::new();
    for (f, v) in done.iter().enumerate() {
        let mut i = 0;
        while i < v.len() {
            if v[i] {
                let a = i;
                while i < v.len() && v[i] {
                    i += 1;
                }
                out.push(Range(f as u32, a as u32, i as u32));
            } else {
                i += 1;
            }
        }
    }
    out
}

const STATE_FILE: &str = "state.json";

/// Já existe um recebimento parcial desse envio (a retomada dispensa nova confirmação).
pub fn has_saved_state(recv_dir: &Path, peer_id: &str, tid: &str) -> bool {
    partial_dir_for(recv_dir, peer_id, tid)
        .join(STATE_FILE)
        .exists()
}

fn partial_dir_for(recv_dir: &Path, peer_id: &str, tid: &str) -> PathBuf {
    recv_dir.join(".renandrop-partial").join(format!(
        "{}-{}",
        &peer_id[..8.min(peer_id.len())],
        tid
    ))
}

impl RecvTransfer {
    /// Cria (ou retoma) o estado de um recebimento já aceito.
    pub fn prepare(
        recv_dir: &Path,
        peer_id: &str,
        tid: &str,
        items: Vec<Item>,
    ) -> Result<Arc<RecvTransfer>> {
        if items.is_empty() || items.len() > MAX_ITEMS {
            bail!("lista de arquivos inválida");
        }
        if items.iter().any(|it| it.size > MAX_FILE) {
            bail!("tamanho de arquivo inválido na oferta");
        }
        for (k, it) in items.iter().enumerate() {
            if it.i as usize != k || sanitize_rel(&it.path).is_none() {
                bail!("caminho inválido na oferta: {}", it.path);
            }
        }
        if tid.is_empty() || !tid.chars().all(|c| c.is_ascii_hexdigit()) {
            bail!("identificador inválido");
        }
        fs::create_dir_all(recv_dir)?;
        let part_dir = partial_dir_for(recv_dir, peer_id, tid);
        fs::create_dir_all(&part_dir)?;
        let saved: Option<State> = fs::read(part_dir.join(STATE_FILE))
            .ok()
            .and_then(|b| serde_json::from_slice::<State>(&b).ok())
            .filter(|s| {
                s.tid == tid
                    && s.items == items
                    && s.names.len() == items.len()
                    && s.finished.len() == items.len()
            });
        let sizes = || items.iter().map(|i| i.size);
        let (names, mut done, mut finished): (Vec<PathBuf>, Vec<Vec<bool>>, Vec<bool>) = match saved
        {
            Some(s) => (
                s.names.iter().map(PathBuf::from).collect(),
                done_map(sizes(), &s.ranges),
                s.finished,
            ),
            None => {
                let mut tops: HashMap<String, PathBuf> = HashMap::new();
                let mut names = Vec::new();
                for it in &items {
                    let parts = sanitize_rel(&it.path).unwrap();
                    let top = tops
                        .entry(parts[0].clone())
                        .or_insert_with(|| unique_path(&recv_dir.join(&parts[0])))
                        .clone();
                    let mut p = top;
                    for c in &parts[1..] {
                        p.push(c);
                    }
                    names.push(p);
                }
                (names, done_map(sizes(), &[]), vec![false; items.len()])
            }
        };
        // Confere as parciais existentes.
        for (k, it) in items.iter().enumerate() {
            if finished[k] || it.dir || it.size == 0 {
                continue;
            }
            let part = part_dir.join(format!("{k}.part"));
            let ok = fs::metadata(&part)
                .map(|m| m.len() == it.size)
                .unwrap_or(false);
            if !ok {
                done[k].iter_mut().for_each(|d| *d = false);
            }
        }
        for (k, it) in items.iter().enumerate() {
            if it.dir {
                fs::create_dir_all(&names[k])?;
                finished[k] = true;
            } else if it.size == 0 && !finished[k] {
                if let Some(parent) = names[k].parent() {
                    fs::create_dir_all(parent)?;
                }
                let f = File::create(&names[k])?;
                set_mtime(&f, it.mtime);
                finished[k] = true;
            }
        }
        let left: Vec<u32> = done
            .iter()
            .zip(&finished)
            .map(|(d, &fin)| {
                if fin {
                    0
                } else {
                    d.iter().filter(|d| !**d).count() as u32
                }
            })
            .collect();
        let total = total_size(&items);
        let have_bytes: u64 = items
            .iter()
            .zip(&done)
            .zip(&finished)
            .map(|((it, d), &fin)| if fin { it.size } else { done_bytes(it.size, d) })
            .sum();
        let prog = Progress::new(total);
        prog.bytes.store(have_bytes, SeqCst);
        let t = Arc::new(RecvTransfer {
            tid: tid.into(),
            peer_id: peer_id.into(),
            items,
            prog,
            superseded: AtomicBool::new(false),
            names,
            part_dir,
            inner: Mutex::new(RInner {
                done,
                left,
                finished,
                open: HashMap::new(),
                last_save: Instant::now(),
            }),
        });
        t.save_state(&t.inner.lock().unwrap());
        Ok(t)
    }

    pub fn have(&self) -> Vec<Range> {
        let g = self.inner.lock().unwrap();
        let mut r = ranges_of(&g.done);
        // Arquivos já finalizados contam como totalmente recebidos.
        for (k, it) in self.items.iter().enumerate() {
            if g.finished[k] && nsegs(it.size) > 0 {
                r.push(Range(k as u32, 0, nsegs(it.size)));
            }
        }
        r
    }

    /// Caminho de topo de cada entrada enviada (arquivo ou pasta), para "mostrar na pasta".
    pub fn top_paths(&self, recv_dir: &Path) -> Vec<PathBuf> {
        let mut out: Vec<PathBuf> = Vec::new();
        for (it, n) in self.items.iter().zip(&self.names) {
            let depth = it.path.split('/').count();
            let mut p = n.clone();
            for _ in 1..depth {
                p.pop();
            }
            if p.starts_with(recv_dir) && !out.contains(&p) {
                out.push(p);
            }
        }
        out
    }

    fn save_state(&self, g: &RInner) {
        let st = State {
            tid: self.tid.clone(),
            items: self.items.clone(),
            names: self
                .names
                .iter()
                .map(|p| p.to_string_lossy().into_owned())
                .collect(),
            ranges: ranges_of(&g.done),
            finished: g.finished.clone(),
        };
        if let Ok(b) = serde_json::to_vec(&st) {
            let tmp = self.part_dir.join("state.tmp");
            if fs::write(&tmp, b).is_ok() {
                let _ = fs::rename(&tmp, self.part_dir.join(STATE_FILE));
            }
        }
    }

    pub fn save(&self) {
        self.save_state(&self.inner.lock().unwrap());
    }

    pub fn cleanup(&self) {
        let _ = fs::remove_dir_all(&self.part_dir);
    }

    fn file_for(&self, f: u32) -> Result<Arc<File>> {
        let mut g = self.inner.lock().unwrap();
        if let Some(h) = g.open.get(&f) {
            return Ok(h.clone());
        }
        let it = &self.items[f as usize];
        let path = self.part_dir.join(format!("{f}.part"));
        let h = OpenOptions::new()
            .create(true)
            .read(true)
            .write(true)
            .truncate(false)
            .open(path)?;
        if h.metadata()?.len() != it.size {
            h.set_len(it.size)?;
        }
        let h = Arc::new(h);
        g.open.insert(f, h.clone());
        Ok(h)
    }

    /// Marca o segmento como recebido; finaliza o arquivo quando completo.
    fn seg_done(&self, f: u32, s: u32) -> Result<()> {
        let mut g = self.inner.lock().unwrap();
        if g.done[f as usize][s as usize] {
            return Ok(());
        }
        g.done[f as usize][s as usize] = true;
        g.left[f as usize] -= 1;
        if g.left[f as usize] == 0 {
            let h = g.open.remove(&f);
            let it = &self.items[f as usize];
            let part = self.part_dir.join(format!("{f}.part"));
            if let Some(h) = h {
                set_mtime(&h, it.mtime);
            }
            let mut dest = self.names[f as usize].clone();
            if let Some(parent) = dest.parent() {
                fs::create_dir_all(parent)?;
            }
            if dest.exists() {
                dest = unique_path(&dest);
            }
            fs::rename(&part, &dest)?;
            g.finished[f as usize] = true;
            self.save_state(&g);
            g.last_save = Instant::now();
        } else if g.last_save.elapsed() > Duration::from_secs(1) {
            self.save_state(&g);
            g.last_save = Instant::now();
        }
        Ok(())
    }

    fn is_complete(&self) -> bool {
        self.inner.lock().unwrap().finished.iter().all(|&f| f)
    }

    /// Atende uma conexão (pista) até o fim da transferência.
    pub fn lane_loop(&self, r: &mut Reader, w: &mut Writer) -> Result<LaneEnd> {
        loop {
            let msg = recv_msg_patient(r, &self.prog)?;
            match msg {
                Msg::Seg { f, s, len } => {
                    let Some(it) = self.items.get(f as usize) else {
                        bail!("arquivo fora da lista")
                    };
                    if it.dir || s >= nsegs(it.size) || len != seg_len(it.size, s) {
                        bail!("segmento inválido");
                    }
                    let already = {
                        let g = self.inner.lock().unwrap();
                        g.finished[f as usize] || g.done[f as usize][s as usize]
                    };
                    let file = if already {
                        None
                    } else {
                        Some(self.file_for(f)?)
                    };
                    let mut hasher = Sha256::new();
                    let mut got = 0u64;
                    let base = s as u64 * SEG;
                    while got < len {
                        let (t, body) = r.recv()?;
                        if t != T_DATA || got + body.len() as u64 > len {
                            bail!("dados fora do esperado");
                        }
                        hasher.update(body);
                        if let Some(file) = &file {
                            write_all_at(file, body, base + got)
                                .map_err(|e| anyhow!("gravando: {e}"))?;
                        }
                        got += body.len() as u64;
                        self.prog.bytes.fetch_add(body.len() as u64, Relaxed);
                        if self.superseded.load(SeqCst) {
                            return Ok(LaneEnd::Superseded);
                        }
                        if self.prog.cancel.load(SeqCst) {
                            let _ = send_msg(w, &Msg::Cancel);
                            return Ok(LaneEnd::LocalCancelled);
                        }
                    }
                    let sha = match recv_msg(r)? {
                        Msg::SegEnd { sha } => sha,
                        _ => bail!("fim de segmento ausente"),
                    };
                    if already {
                        // Reenvio de algo que já temos (ex.: após retomada): confirma sem regravar.
                        self.prog.bytes.fetch_sub(len, Relaxed);
                        send_msg(w, &Msg::SegAck { f, s })?;
                    } else if hex::encode(hasher.finalize()) == sha {
                        match self.seg_done(f, s) {
                            Ok(()) => send_msg(w, &Msg::SegAck { f, s })?,
                            Err(e) => {
                                let _ = send_msg(
                                    w,
                                    &Msg::Fail {
                                        reason: e.to_string(),
                                    },
                                );
                                return Err(e);
                            }
                        }
                    } else {
                        self.prog.bytes.fetch_sub(len, Relaxed);
                        send_msg(w, &Msg::SegBad { f, s })?;
                    }
                }
                Msg::Done => {
                    if self.is_complete() {
                        self.cleanup();
                        send_msg(w, &Msg::Complete)?;
                        return Ok(LaneEnd::Complete);
                    }
                    send_msg(
                        w,
                        &Msg::Fail {
                            reason: "faltam segmentos".into(),
                        },
                    )?;
                    bail!("o remetente encerrou com segmentos faltando");
                }
                Msg::Cancel => return Ok(LaneEnd::PeerCancelled),
                _ => bail!("mensagem inesperada"),
            }
        }
    }
}

fn set_mtime(f: &File, ms: i64) {
    if ms > 0 {
        let _ = f.set_modified(std::time::UNIX_EPOCH + Duration::from_millis(ms as u64));
    }
}
