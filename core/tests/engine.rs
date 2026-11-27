//! Testes de ponta a ponta do núcleo: dois motores no mesmo processo, perfis e portas separados.

use renandrop_core::crypto::Identity;
use renandrop_core::session::{client_handshake, write_preamble, KIND_SESSION};
use renandrop_core::{Engine, EngineOptions, Event, TransferView};
use sha2::{Digest, Sha256};
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering::SeqCst};
use std::sync::mpsc::Receiver;
use std::sync::Arc;
use std::time::{Duration, Instant};

struct Node {
    eng: Engine,
    rx: Receiver<Event>,
    recv: PathBuf,
    _tmp: tempfile::TempDir,
}

/// Porta própria por nó: os testes rodam em paralelo e a faixa padrão tem só 5 portas.
fn next_port() -> u16 {
    use std::sync::atomic::{AtomicU16, Ordering::Relaxed};
    static NEXT: AtomicU16 = AtomicU16::new(0);
    40_000 + (std::process::id() % 2_000) as u16 * 10 + NEXT.fetch_add(1, Relaxed)
}

fn node(name: &str) -> Node {
    let tmp = tempfile::tempdir().unwrap();
    let recv = tmp.path().join("recebidos");
    let (eng, rx) = Engine::start(EngineOptions {
        profile: tmp.path().join("perfil"),
        port: Some(next_port()),
        name: Some(name.into()),
        recv_dir: Some(recv.clone()),
        discovery: false,
    })
    .unwrap();
    Node {
        eng,
        rx,
        recv,
        _tmp: tmp,
    }
}

fn addr(n: &Node) -> SocketAddr {
    SocketAddr::from(([127, 0, 0, 1], n.eng.port()))
}

fn wait<T>(rx: &Receiver<Event>, secs: u64, mut f: impl FnMut(&Event) -> Option<T>) -> T {
    let end = Instant::now() + Duration::from_secs(secs);
    loop {
        let left = end.saturating_duration_since(Instant::now());
        match rx.recv_timeout(left) {
            Ok(ev) => {
                if let Some(v) = f(&ev) {
                    return v;
                }
            }
            Err(_) => panic!("tempo esgotado esperando evento"),
        }
    }
}

fn wait_final(rx: &Receiver<Event>, secs: u64) -> TransferView {
    wait(rx, secs, |e| match e {
        Event::Transfer(t) if t.finished => Some(t.clone()),
        _ => None,
    })
}

/// Pareia a com b pelo código curto, confirmando dos dois lados.
fn pair(a: &Node, b: &Node) {
    a.eng.pair_addr(vec![addr(b)]).unwrap();
    let pick = |e: &Event| {
        if let Event::PairPrompt { req_id, code, .. } = e {
            Some((req_id.clone(), code.clone()))
        } else {
            None
        }
    };
    let ca = wait(&a.rx, 15, pick);
    let cb = wait(&b.rx, 15, pick);
    assert_eq!(ca.1, cb.1, "o código curto deve ser o mesmo nos dois lados");
    assert_eq!(ca.1.len(), 6);
    a.eng.confirm_pair(&ca.0, true);
    b.eng.confirm_pair(&cb.0, true);
    for n in [a, b] {
        wait(&n.rx, 15, |e| {
            if let Event::PairDone { ok, error, .. } = e {
                assert!(*ok, "pareamento falhou: {error:?}");
                Some(())
            } else {
                None
            }
        });
    }
    // Garante que o cadastro foi gravado dos dois lados.
    assert!(a.eng.peers().iter().any(|p| p.id == b.eng.id() && p.paired));
    assert!(b.eng.peers().iter().any(|p| p.id == a.eng.id() && p.paired));
    a.eng.set_manual_addr(&b.eng.id(), addr(b));
    b.eng.set_manual_addr(&a.eng.id(), addr(a));
}

fn pseudo(path: &Path, size: u64, seed: u64) {
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    let mut f = std::io::BufWriter::with_capacity(1 << 20, std::fs::File::create(path).unwrap());
    let mut x = seed | 1;
    let mut left = size;
    let mut buf = vec![0u8; 1 << 20];
    while left > 0 {
        for c in buf.chunks_mut(8) {
            x ^= x << 13;
            x ^= x >> 7;
            x ^= x << 17;
            let b = x.to_le_bytes();
            c.copy_from_slice(&b[..c.len()]);
        }
        let n = left.min(buf.len() as u64) as usize;
        f.write_all(&buf[..n]).unwrap();
        left -= n as u64;
    }
    f.flush().unwrap();
}

fn sha_file(p: &Path) -> String {
    let mut f = std::fs::File::open(p).unwrap();
    let mut h = Sha256::new();
    let mut b = vec![0u8; 1 << 20];
    loop {
        let n = f.read(&mut b).unwrap();
        if n == 0 {
            break;
        }
        h.update(&b[..n]);
    }
    hex::encode(h.finalize())
}

#[test]
fn pareia_e_envia_pasta_e_arquivo() {
    let (a, b) = (node("PC-A"), node("PC-B"));
    pair(&a, &b);
    b.eng.update_settings(|s| s.auto_accept = true).unwrap();

    let src = tempfile::tempdir().unwrap();
    let dir = src.path().join("Fotos");
    pseudo(&dir.join("a.bin"), 3_000_000, 1);
    pseudo(&dir.join("sub/b.bin"), 17_000_000, 2);
    pseudo(&dir.join("sub/vazio.txt"), 0, 3);
    std::fs::create_dir_all(dir.join("pasta-vazia")).unwrap();
    let solo = src.path().join("solo.bin");
    pseudo(&solo, 9_000_001, 4);

    a.eng
        .send(&[b.eng.id()], vec![dir.clone(), solo.clone()])
        .unwrap();
    let tv = wait_final(&a.rx, 60);
    assert_eq!(tv.state, "done", "{:?}", tv.error);
    let rv = wait_final(&b.rx, 60);
    assert_eq!(rv.state, "done", "{:?}", rv.error);

    for rel in [
        "Fotos/a.bin",
        "Fotos/sub/b.bin",
        "Fotos/sub/vazio.txt",
        "solo.bin",
    ] {
        let orig = if rel == "solo.bin" {
            solo.clone()
        } else {
            src.path().join(rel)
        };
        assert_eq!(
            sha_file(&orig),
            sha_file(&b.recv.join(rel)),
            "hash diferente em {rel}"
        );
    }
    assert!(b.recv.join("Fotos/pasta-vazia").is_dir());
    assert!(
        !b.recv.join(".renandrop-partial").exists()
            || std::fs::read_dir(b.recv.join(".renandrop-partial"))
                .unwrap()
                .count()
                == 0
    );
}

#[test]
fn adiciona_aparelho_por_endereco_e_pareia_pela_rota() {
    let (a, b) = (node("PC-A"), node("PC-B"));
    // Sem mDNS e sem endereço manual de teste: só o "adicionar por IP:porta" (o mesmo caminho do Tailscale).
    assert!(
        a.eng.add_remote("127.0.0.1:1").is_err(),
        "endereço sem RenanDrop deve falhar"
    );
    let v = a
        .eng
        .add_remote(&format!("127.0.0.1:{}", b.eng.port()))
        .unwrap();
    assert_eq!(v.id, b.eng.id());
    assert_eq!(v.name, "PC-B");
    assert!(v.online && !v.paired && v.manual);
    assert_eq!(v.route.as_deref(), Some("local"));
    // Pareia e envia usando só essa rota; a transferência informa o caminho usado.
    a.eng.pair(&b.eng.id()).unwrap();
    let ca = wait(&a.rx, 15, |e| {
        if let Event::PairPrompt { req_id, .. } = e {
            Some(req_id.clone())
        } else {
            None
        }
    });
    let cb = wait(&b.rx, 15, |e| {
        if let Event::PairPrompt { req_id, .. } = e {
            Some(req_id.clone())
        } else {
            None
        }
    });
    a.eng.confirm_pair(&ca, true);
    b.eng.confirm_pair(&cb, true);
    wait(&a.rx, 15, |e| {
        if let Event::PairDone { ok: true, .. } = e {
            Some(())
        } else {
            None
        }
    });
    wait(&b.rx, 15, |e| {
        if let Event::PairDone { ok: true, .. } = e {
            Some(())
        } else {
            None
        }
    });
    let src = tempfile::tempdir().unwrap();
    pseudo(&src.path().join("r.bin"), 2_000_000, 9);
    a.eng
        .send(&[b.eng.id()], vec![src.path().join("r.bin")])
        .unwrap();
    let tv = wait_final(&a.rx, 30);
    assert_eq!(tv.state, "done", "{:?}", tv.error);
    assert_eq!(tv.route.as_deref(), Some("local"));
    let rv = wait_final(&b.rx, 30);
    assert_eq!(rv.route.as_deref(), Some("local"));
    // Persistido: some da lista ao esquecer.
    assert!(a.eng.settings().remotes.len() == 1);
    a.eng.forget_remote(&b.eng.id()).unwrap();
    assert!(a.eng.settings().remotes.is_empty());
}

#[test]
fn varredura_do_tailscale_nunca_derruba_o_app() {
    // Sem Tailscale (Ok(None)), desconectado (Err) ou ativo (Ok(Some)): a varredura só devolve resultado,
    // sem panic, e o estado exibido é um dos conhecidos.
    let _ = renandrop_core::tailnet::tailnet_nodes();
    let n = node("PC-T");
    assert!(["unknown", "ok", "missing", "disconnected", "off"]
        .contains(&n.eng.tailscale_state().as_str()));
}

#[test]
fn aparelho_nao_pareado_e_recusado() {
    let b = node("PC-B");
    // Quem não está no cadastro de B não passa do aperto de mão.
    let estranho = Identity::generate();
    let mut s = TcpStream::connect(addr(&b)).unwrap();
    s.set_read_timeout(Some(Duration::from_secs(10))).unwrap();
    write_preamble(&mut s, KIND_SESSION).unwrap();
    let r = client_handshake(s, &estranho, &b.eng_pub());
    assert!(r.is_err(), "aparelho desconhecido não pode abrir sessão");
}

#[test]
fn revogar_pareamento_bloqueia_envio() {
    let (a, b) = (node("PC-A"), node("PC-B"));
    pair(&a, &b);
    b.eng.update_settings(|s| s.auto_accept = true).unwrap();
    b.eng.unpair(&a.eng.id()).unwrap();
    let src = tempfile::tempdir().unwrap();
    pseudo(&src.path().join("x.bin"), 100_000, 5);
    a.eng
        .send(&[b.eng.id()], vec![src.path().join("x.bin")])
        .unwrap();
    let tv = wait_final(&a.rx, 30);
    assert_eq!(tv.state, "failed");
    assert!(!b.recv.join("x.bin").exists());
}

#[test]
fn recusa_manual_nao_grava_nada() {
    let (a, b) = (node("PC-A"), node("PC-B"));
    pair(&a, &b);
    b.eng.update_settings(|s| s.auto_accept = false).unwrap();
    let src = tempfile::tempdir().unwrap();
    pseudo(&src.path().join("y.bin"), 100_000, 6);
    a.eng
        .send(&[b.eng.id()], vec![src.path().join("y.bin")])
        .unwrap();
    let tid = wait(&b.rx, 20, |e| {
        if let Event::Offer { tid, .. } = e {
            Some(tid.clone())
        } else {
            None
        }
    });
    b.eng.respond_offer(&tid, false);
    let tv = wait_final(&a.rx, 20);
    assert_eq!(tv.state, "declined");
    assert!(!b.recv.join("y.bin").exists());
}

/// Proxy TCP que derruba as conexões depois de `cut` bytes (somando todas) na primeira vez.
fn proxy(target: SocketAddr, cut: u64, passed: Arc<AtomicU64>) -> SocketAddr {
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = l.local_addr().unwrap();
    std::thread::spawn(move || {
        let cut_done = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let up_total = Arc::new(AtomicU64::new(0));
        for c in l.incoming() {
            let Ok(c) = c else { continue };
            let Ok(t) = TcpStream::connect(target) else {
                continue;
            };
            let (c2, t2) = (c.try_clone().unwrap(), t.try_clone().unwrap());
            // destino -> origem (sem limite)
            std::thread::spawn(move || {
                let (mut t, mut c) = (t2, c2);
                let _ = std::io::copy(&mut t, &mut c);
                let _ = c.shutdown(std::net::Shutdown::Both);
            });
            let (cut_done, up_total, passed) = (cut_done.clone(), up_total.clone(), passed.clone());
            std::thread::spawn(move || {
                let (mut c, mut t) = (c, t);
                let mut buf = vec![0u8; 64 * 1024];
                loop {
                    let n = match c.read(&mut buf) {
                        Ok(0) | Err(_) => break,
                        Ok(n) => n,
                    };
                    passed.fetch_add(n as u64, SeqCst);
                    let tot = up_total.fetch_add(n as u64, SeqCst) + n as u64;
                    if !cut_done.load(SeqCst) && tot >= cut {
                        cut_done.store(true, SeqCst);
                        break;
                    }
                    if t.write_all(&buf[..n]).is_err() {
                        break;
                    }
                }
                let _ = t.shutdown(std::net::Shutdown::Both);
                let _ = c.shutdown(std::net::Shutdown::Both);
            });
        }
    });
    addr
}

#[test]
fn retoma_depois_de_cortar_a_conexao() {
    let (a, b) = (node("PC-A"), node("PC-B"));
    pair(&a, &b);
    b.eng.update_settings(|s| s.auto_accept = true).unwrap();
    let src = tempfile::tempdir().unwrap();
    let f = src.path().join("grande.bin");
    let size = 96 * 1024 * 1024u64;
    pseudo(&f, size, 7);

    let passed = Arc::new(AtomicU64::new(0));
    let p = proxy(addr(&b), 40 * 1024 * 1024, passed.clone());
    a.eng.set_manual_addr(&b.eng.id(), p);

    a.eng.send(&[b.eng.id()], vec![f.clone()]).unwrap();
    let tv = wait_final(&a.rx, 120);
    assert_eq!(tv.state, "done", "{:?}", tv.error);
    assert_eq!(sha_file(&f), sha_file(&b.recv.join("grande.bin")));
    let sent = passed.load(SeqCst);
    // Com retomada, o reenvio aproveita os segmentos já gravados: sobra bem menos que 2x o arquivo.
    assert!(
        sent < size + size / 2,
        "reenviou demais: {sent} bytes para um arquivo de {size}"
    );
}

/// 1 GB sintético com hash independente; mede a velocidade em loopback. Rode com `--ignored`.
#[test]
#[ignore]
fn um_gigabyte_com_hash_igual() {
    let (a, b) = (node("PC-A"), node("PC-B"));
    pair(&a, &b);
    b.eng.update_settings(|s| s.auto_accept = true).unwrap();
    let src = tempfile::tempdir().unwrap();
    let f = src.path().join("um-giga.bin");
    let size = 1024 * 1024 * 1024u64;
    pseudo(&f, size, 8);
    let t0 = Instant::now();
    a.eng.send(&[b.eng.id()], vec![f.clone()]).unwrap();
    let tv = wait_final(&a.rx, 600);
    let dt = t0.elapsed().as_secs_f64();
    assert_eq!(tv.state, "done", "{:?}", tv.error);
    let _ = wait_final(&b.rx, 60);
    println!(
        "VELOCIDADE_LOOPBACK {:.0} MB/s ({:.2} s para 1 GiB)",
        size as f64 / 1e6 / dt,
        dt
    );
    assert_eq!(sha_file(&f), sha_file(&b.recv.join("um-giga.bin")));
}

impl Node {
    fn eng_pub(&self) -> Vec<u8> {
        // Chave pública do nó (arquivo de identidade do perfil) para o aperto de mão do "estranho".
        let id = Identity::from_secret_bytes(
            &std::fs::read(self._tmp.path().join("perfil/identity.bin")).unwrap(),
        )
        .unwrap();
        id.public().to_vec()
    }
}
