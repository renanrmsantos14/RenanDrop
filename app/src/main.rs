// Sem console no Windows.
#![cfg_attr(all(not(debug_assertions), windows), windows_subsystem = "windows")]

mod control;
mod integration;
mod updater;

use renandrop_core::engine::Event;
use renandrop_core::store::{default_profile_dir, HistoryEntry, Settings};
use renandrop_core::{Engine, EngineOptions, PeerView, TransferView};
use serde::Serialize;
use serde_json::Value;
use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{
    AppHandle, DragDropEvent, Emitter, Manager, RunEvent, State, WebviewUrl, WebviewWindowBuilder,
    WindowEvent,
};
use tauri_plugin_dialog::DialogExt;

const VERSION_JSON: &str = include_str!("../version.json");

fn version() -> Value {
    static V: std::sync::OnceLock<Value> = std::sync::OnceLock::new();
    V.get_or_init(|| serde_json::from_str(VERSION_JSON).unwrap_or_default())
        .clone()
}

struct App {
    engine: Engine,
    /// Pedidos de pareamento e ofertas que ainda esperam resposta da pessoa.
    pending: Mutex<Vec<Event>>,
    /// Arquivos vindos do menu de contexto, esperando a escolha dos destinos.
    staged: Mutex<Vec<String>>,
    notified: Mutex<HashSet<String>>,
    /// Último estado de arrastar enviado à janela; evita um evento por movimento do mouse.
    dragging: AtomicBool,
    exe: PathBuf,
    /// Argumentos com que este processo foi aberto (para reabrir igual depois de atualizar).
    argv: Vec<String>,
    /// `None` em plataforma sem binário no release.
    update_cfg: Option<updater::Cfg>,
    update: Mutex<UpdateSt>,
}

#[derive(Default)]
struct UpdateSt {
    state: updater::State,
    found: Option<renandrop_core::updater::Update>,
}

#[derive(Default)]
struct Args {
    profile: Option<PathBuf>,
    port: Option<u16>,
    name: Option<String>,
    recv: Option<PathBuf>,
    ctrl_port: Option<u16>,
    discovery_off: bool,
    hidden: bool,
    relaunch: bool,
    no_integration: bool,
    send: Vec<String>,
}

fn parse_args() -> Args {
    let mut a = Args::default();
    let mut it = std::env::args().skip(1);
    while let Some(k) = it.next() {
        match k.as_str() {
            "--profile" => a.profile = it.next().map(PathBuf::from),
            "--port" => a.port = it.next().and_then(|v| v.parse().ok()),
            "--name" => a.name = it.next(),
            "--recv" => a.recv = it.next().map(PathBuf::from),
            "--ctrl-port" => a.ctrl_port = it.next().and_then(|v| v.parse().ok()),
            "--no-discovery" => a.discovery_off = true,
            "--hidden" => a.hidden = true,
            "--relaunch" => a.relaunch = true,
            "--no-integration" => a.no_integration = true,
            // Tudo depois de --send são caminhos (o Nemo entrega vários de uma vez).
            "--send" => {
                a.send = it.by_ref().collect();
            }
            _ => {}
        }
    }
    a
}

#[derive(Serialize)]
struct Snapshot {
    id: String,
    port: u16,
    settings: Settings,
    peers: Vec<PeerView>,
    transfers: Vec<TransferView>,
    history: Vec<HistoryEntry>,
    tailscale: String,
    pending: Vec<Event>,
    version: Value,
    update: updater::State,
}

type St<'a> = State<'a, App>;
type Res<T> = Result<T, String>;
fn err<E: std::fmt::Display>(e: E) -> String {
    e.to_string()
}

#[tauri::command]
fn snapshot(st: St) -> Snapshot {
    let e = &st.engine;
    Snapshot {
        id: e.id(),
        port: e.port(),
        settings: e.settings(),
        peers: e.peers(),
        transfers: e.transfers(),
        history: e.history(),
        tailscale: e.tailscale_state(),
        pending: st.pending.lock().unwrap().clone(),
        version: version(),
        update: st.update.lock().unwrap().state.clone(),
    }
}

/// Itens vindos de `--send` ou do menu de contexto; entregues uma única vez à janela.
#[tauri::command]
fn take_staged(st: St) -> Vec<String> {
    std::mem::take(&mut *st.staged.lock().unwrap())
}

#[tauri::command(async)]
fn send(st: St, peer_ids: Vec<String>, paths: Vec<String>) -> Res<Vec<String>> {
    let paths = paths.into_iter().map(PathBuf::from).collect();
    st.engine.send(&peer_ids, paths).map_err(err)
}

#[tauri::command]
fn pair(st: St, peer_id: String) -> Res<()> {
    st.engine.pair(&peer_id).map_err(err)
}

#[tauri::command(async)]
fn add_remote(st: St, text: String) -> Res<PeerView> {
    st.engine.add_remote(&text).map_err(err)
}

#[tauri::command]
fn forget_remote(st: St, id: String) -> Res<()> {
    st.engine.forget_remote(&id).map_err(err)
}

#[tauri::command]
fn rename_peer(st: St, id: String, name: String) -> Res<()> {
    st.engine.rename_peer(&id, &name).map_err(err)
}

#[tauri::command]
fn set_peer_auto_accept(st: St, id: String, value: Option<bool>) -> Res<()> {
    st.engine.set_peer_auto_accept(&id, value).map_err(err)
}

#[tauri::command]
fn unpair(st: St, id: String) -> Res<()> {
    st.engine.unpair(&id).map_err(err)
}

#[tauri::command]
fn confirm_pair(st: St, req_id: String, ok: bool) {
    st.pending
        .lock()
        .unwrap()
        .retain(|e| !matches!(e, Event::PairPrompt { req_id: r, .. } if *r == req_id));
    st.engine.confirm_pair(&req_id, ok);
}

#[tauri::command]
fn respond_offer(st: St, tid: String, accept: bool) {
    st.pending
        .lock()
        .unwrap()
        .retain(|e| !matches!(e, Event::Offer { tid: t, .. } if *t == tid));
    st.engine.respond_offer(&tid, accept);
}

#[tauri::command]
fn cancel(st: St, id: String) {
    st.engine.cancel(&id);
}

#[tauri::command(async)]
fn retry(st: St, id: String) -> Res<()> {
    st.engine.retry(&id).map_err(err)
}

#[tauri::command]
fn clear_finished(st: St) {
    st.engine.clear_finished();
}

#[tauri::command]
fn clear_history(st: St) {
    st.engine.clear_history();
}

#[tauri::command]
fn rescan(st: St) {
    st.engine.rescan();
}

#[tauri::command(async)]
fn update_settings(st: St, patch: Value) -> Res<Settings> {
    let old = st.engine.settings().autostart;
    // Aplica no sistema antes de gravar: se falhar, a configuração não fica dizendo o contrário.
    if let Some(v) = patch.get("autostart").and_then(Value::as_bool) {
        if v != old {
            integration::set_autostart(&st.exe, v).map_err(err)?;
        }
    }
    let text = |k: &str| {
        patch
            .get(k)
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(String::from)
    };
    let flag = |k: &str| patch.get(k).and_then(Value::as_bool);
    let s = st
        .engine
        .update_settings(|s| {
            if let Some(v) = text("device_name") {
                s.device_name = v;
            }
            if let Some(v) = text("recv_dir") {
                s.recv_dir = v.into();
            }
            if let Some(v) =
                text("theme").filter(|t| matches!(t.as_str(), "auto" | "light" | "dark"))
            {
                s.theme = v;
            }
            if let Some(v) = patch.get("lanes").and_then(Value::as_u64) {
                s.lanes = v.clamp(1, 8) as u32;
            }
            if let Some(v) = flag("auto_accept") {
                s.auto_accept = v;
            }
            if let Some(v) = flag("autostart") {
                s.autostart = v;
            }
            if let Some(v) = flag("notifications") {
                s.notifications = v;
            }
            if let Some(v) = flag("tailscale") {
                s.tailscale = v;
            }
            if let Some(v) = flag("auto_update") {
                s.auto_update = v;
            }
        })
        .map_err(err)?;
    Ok(s)
}

fn current_version() -> String {
    version()["version"].as_str().unwrap_or("0.0.0").to_string()
}

fn set_update_state(
    app: &AppHandle,
    state: updater::State,
    found: Option<renandrop_core::updater::Update>,
) {
    let st = app.state::<App>();
    {
        let mut u = st.update.lock().unwrap();
        u.state = state.clone();
        u.found = found;
    }
    if app.get_webview_window("main").is_some() {
        let _ = app.emit("rd", serde_json::json!({"type":"update","update":state}));
    }
}

/// Registra a falha na tela de configurações e devolve a mensagem.
fn update_failed(app: &AppHandle, message: String) -> String {
    set_update_state(
        app,
        updater::State::Error {
            message: message.clone(),
        },
        None,
    );
    message
}

/// Consulta o GitHub e guarda o resultado. Não mexe se uma instalação está em andamento.
fn run_update_check(app: &AppHandle) -> updater::State {
    let st = app.state::<App>();
    let Some(cfg) = st.update_cfg.clone() else {
        let message = "Atualização automática indisponível nesta plataforma.".to_string();
        return updater::State::Error {
            message: update_failed(app, message),
        };
    };
    {
        let mut u = st.update.lock().unwrap();
        if matches!(
            u.state,
            updater::State::Installing | updater::State::Checking
        ) {
            return u.state.clone();
        }
        // Dentro do lock: duas verificações não passam juntas.
        u.state = updater::State::Checking;
    }
    set_update_state(app, updater::State::Checking, None);
    let (state, found) = match updater::check(&cfg) {
        Ok(Some(u)) => (
            updater::State::Available {
                version: u.version.clone(),
            },
            Some(u),
        ),
        Ok(None) => (updater::State::UpToDate, None),
        Err(e) => {
            log::warn!("atualização: {e:#}");
            (
                updater::State::Error {
                    message: format!("Não foi possível verificar: {e}"),
                },
                None,
            )
        }
    };
    set_update_state(app, state.clone(), found);
    state
}

#[tauri::command(async)]
fn check_update(app: AppHandle) -> updater::State {
    run_update_check(&app)
}

/// Baixa, confere a assinatura, troca o executável e reabre o app. Em qualquer falha nada é instalado.
#[tauri::command(async)]
fn install_update(app: AppHandle) -> Res<()> {
    let st = app.state::<App>();
    let (cfg, found) = {
        let mut u = st.update.lock().unwrap();
        let Some(found) = u.found.clone() else {
            return Err("Nenhuma atualização para instalar. Procure de novo.".into());
        };
        if matches!(u.state, updater::State::Installing) {
            return Err("A instalação já está em andamento.".into());
        }
        if matches!(u.state, updater::State::Checking) {
            return Err("Procurando atualização; tente de novo em instantes.".into());
        }
        u.state = updater::State::Installing;
        (
            st.update_cfg.clone().ok_or("Atualização indisponível.")?,
            found,
        )
    };
    set_update_state(&app, updater::State::Installing, Some(found.clone()));
    if let Err(e) = updater::install(&cfg, &found, &st.exe) {
        log::warn!("atualização descartada: {e:#}");
        return Err(update_failed(
            &app,
            format!("Atualização não instalada: {e}"),
        ));
    }
    let hidden = app.get_webview_window("main").is_none();
    if let Err(e) = updater::relaunch(&st.exe, &st.argv, hidden) {
        return Err(update_failed(
            &app,
            format!(
                "Atualizado para a versão {}; abra o RenanDrop de novo ({e}).",
                found.version
            ),
        ));
    }
    quit_app(&app);
    Ok(())
}

/// Procura ao iniciar (com o app já de pé) e a cada 6 h, se a opção estiver ligada.
fn spawn_update_checker(app: AppHandle) {
    std::thread::spawn(move || {
        std::thread::sleep(std::time::Duration::from_secs(20));
        loop {
            if app.state::<App>().engine.settings().auto_update {
                run_update_check(&app);
            }
            std::thread::sleep(std::time::Duration::from_secs(6 * 3600));
        }
    });
}

#[tauri::command]
async fn pick_paths(app: AppHandle, folder: bool) -> Vec<String> {
    let d = app.dialog().file();
    let picked = if folder {
        d.blocking_pick_folder().map(|p| vec![p])
    } else {
        d.blocking_pick_files()
    };
    picked
        .unwrap_or_default()
        .into_iter()
        .filter_map(|p| p.into_path().ok())
        .map(|p| p.display().to_string())
        .collect()
}

#[tauri::command]
async fn pick_recv_dir(app: AppHandle) -> Option<String> {
    app.dialog()
        .file()
        .blocking_pick_folder()
        .and_then(|p| p.into_path().ok())
        .map(|p| p.display().to_string())
}

#[tauri::command]
fn open_path(path: String) {
    integration::open(&PathBuf::from(path));
}

#[tauri::command]
fn reveal_path(path: String) {
    integration::reveal(&PathBuf::from(path));
}

fn quit_app(app: &AppHandle) {
    app.state::<App>().engine.shutdown();
    app.exit(0);
}

#[tauri::command]
fn quit(app: AppHandle) {
    quit_app(&app);
}

/// Abre a janela (ou traz a que já existe). A janela é criada sob demanda e destruída ao fechar:
/// com ela fechada o app fica só com o motor e a bandeja, e a memória do WebView é devolvida.
fn show_window(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.unminimize();
        let _ = w.show();
        let _ = w.set_focus();
        return;
    }
    let built = WebviewWindowBuilder::new(app, "main", WebviewUrl::App("index.html".into()))
        .title("RenanDrop")
        .inner_size(480.0, 760.0)
        .min_inner_size(380.0, 520.0)
        .center()
        .build();
    match built {
        Ok(w) => {
            let a = app.clone();
            w.on_window_event(move |e| {
                if let WindowEvent::DragDrop(d) = e {
                    let (over, ev) = match d {
                        DragDropEvent::Enter { .. } | DragDropEvent::Over { .. } => {
                            (true, serde_json::json!({"type":"drag","over":true}))
                        }
                        DragDropEvent::Leave => {
                            (false, serde_json::json!({"type":"drag","over":false}))
                        }
                        DragDropEvent::Drop { paths, .. } => {
                            (false, serde_json::json!({"type":"drop","paths":paths}))
                        }
                        _ => return,
                    };
                    let is_drop = matches!(d, DragDropEvent::Drop { .. });
                    if a.state::<App>().dragging.swap(over, Ordering::Relaxed) != over || is_drop {
                        let _ = a.emit("rd", ev);
                    }
                }
            });
        }
        Err(e) => log::error!("não consegui abrir a janela: {e}"),
    }
}

fn stage(app: &AppHandle, paths: Vec<String>) {
    if !paths.is_empty() {
        app.state::<App>().staged.lock().unwrap().extend(paths);
        let _ = app.emit("rd", serde_json::json!({"type":"staged"}));
    }
    show_window(app);
}

fn notify(app: &AppHandle, t: &TransferView) {
    let st = app.state::<App>();
    let s = st.engine.settings();
    if !s.notifications
        || !t.finished
        || !st
            .notified
            .lock()
            .unwrap()
            .insert(format!("{}:{}", t.id, t.state))
    {
        return;
    }
    let recv = t.dir == "recv";
    let (title, ok) = match (t.state.as_str(), recv) {
        ("done", true) => (format!("Recebido de {}", t.peer_name), true),
        ("done", false) => (format!("Enviado para {}", t.peer_name), true),
        ("failed", _) => (format!("Falha na transferência com {}", t.peer_name), false),
        _ => return,
    };
    let body = if ok {
        t.summary.clone()
    } else {
        t.error.clone().unwrap_or_else(|| t.summary.clone())
    };
    let target = t
        .path
        .clone()
        .map(PathBuf::from)
        .unwrap_or_else(|| s.recv_dir.clone());
    std::thread::spawn(move || {
        let mut n = notify_rust::Notification::new();
        n.summary(&title)
            .body(&body)
            .icon("document-send")
            .appname("RenanDrop");
        if recv && ok {
            n.action("open", "Abrir")
                .action("folder", "Mostrar na pasta");
        }
        let Ok(h) = n.show() else { return };
        #[cfg(all(unix, not(target_os = "macos")))]
        if recv && ok {
            h.wait_for_action(|a| match a {
                "open" => integration::open(&target),
                "folder" => integration::reveal(&target),
                _ => {}
            });
        }
        #[cfg(not(all(unix, not(target_os = "macos"))))]
        let _ = (h, target);
    });
}

fn on_event(app: &AppHandle, ev: Event) {
    let st = app.state::<App>();
    match &ev {
        Event::PairPrompt { .. } | Event::Offer { .. } => {
            st.pending.lock().unwrap().push(ev.clone());
            show_window(app);
        }
        Event::PairDone { .. } => st
            .pending
            .lock()
            .unwrap()
            .retain(|e| !matches!(e, Event::PairPrompt { .. })),
        Event::Transfer(t) => {
            // A oferta de recebimento perdeu o sentido (aceita, recusada, expirou ou o remetente saiu).
            if t.dir == "recv" && t.state != "waiting" {
                st.pending
                    .lock()
                    .unwrap()
                    .retain(|e| !matches!(e, Event::Offer { tid, .. } if *tid == t.id));
            }
            if t.finished {
                notify(app, t);
            }
        }
        _ => {}
    }
    // Com a janela fechada (estado normal do app) ninguém escuta: não serializa à toa.
    if app.get_webview_window("main").is_some() {
        let _ = app.emit("rd", &ev);
    }
}

fn main() {
    let args = parse_args();
    let profile = args.profile.clone().unwrap_or_else(default_profile_dir);
    let exe = std::env::current_exe().unwrap_or_default();
    let ctrl_port = args.ctrl_port.unwrap_or(control::DEFAULT_PORT);
    updater::cleanup_old(&exe);
    if args.relaunch {
        // Veio de uma atualização: o processo anterior ainda está saindo e segura a porta.
        control::wait_free(ctrl_port, std::time::Duration::from_secs(15));
    }

    // Sem token gravado o canal de controle fica desligado: token vazio aceitaria qualquer processo.
    let token = control::token(&profile).ok();
    let listener = match &token {
        Some(t) => match control::claim(
            ctrl_port,
            &control::Msg {
                token: t.clone(),
                paths: args.send.clone(),
            },
        ) {
            control::Claim::Forwarded => return,
            control::Claim::First(l) => Some(l),
            control::Claim::None => None,
        },
        None => None,
    };

    let (engine, rx) = match Engine::start(EngineOptions {
        profile,
        port: args.port,
        name: args.name,
        recv_dir: args.recv,
        discovery: !args.discovery_off,
    }) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("RenanDrop não conseguiu iniciar: {e:#}");
            std::process::exit(1);
        }
    };

    let autostart = engine.settings().autostart;
    if !args.no_integration {
        let exe = exe.clone();
        std::thread::spawn(move || {
            if let Err(e) = integration::install_context_menu(&exe) {
                log::warn!("menu de contexto: {e}");
            }
            if let Err(e) = integration::set_autostart(&exe, autostart) {
                log::warn!("início com o sistema: {e}");
            }
        });
    }

    let start_paths = args.send;
    let hidden = args.hidden && start_paths.is_empty();
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .manage(App {
            engine,
            pending: Mutex::new(Vec::new()),
            staged: Mutex::new(Vec::new()),
            notified: Mutex::new(HashSet::new()),
            dragging: AtomicBool::new(false),
            exe,
            argv: std::env::args().skip(1).collect(),
            update_cfg: updater::Cfg::production(&current_version()),
            update: Mutex::new(UpdateSt::default()),
        })
        .invoke_handler(tauri::generate_handler![
            snapshot,
            take_staged,
            send,
            pair,
            add_remote,
            forget_remote,
            rename_peer,
            set_peer_auto_accept,
            unpair,
            confirm_pair,
            respond_offer,
            cancel,
            retry,
            clear_finished,
            clear_history,
            rescan,
            update_settings,
            check_update,
            install_update,
            pick_paths,
            pick_recv_dir,
            open_path,
            reveal_path,
            quit,
        ])
        .setup(move |app| {
            let h = app.handle().clone();
            std::thread::spawn({
                let h = h.clone();
                move || {
                    for ev in rx {
                        on_event(&h, ev);
                    }
                }
            });
            if let (Some(l), Some(token)) = (listener, token) {
                let h = h.clone();
                control::serve(l, token, move |m| stage(&h, m.paths));
            }
            spawn_update_checker(h.clone());
            if let Err(e) = build_tray(app.handle()) {
                log::warn!("bandeja indisponível: {e}");
            }
            if !hidden {
                stage(&h, start_paths);
            }
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("erro ao iniciar o RenanDrop");

    app.run(|_, e| {
        // Fechar a janela não encerra o app: ele segue na bandeja recebendo arquivos.
        if let RunEvent::ExitRequested { api, code, .. } = e {
            if code.is_none() {
                api.prevent_exit();
            }
        }
    });
}

fn build_tray(app: &AppHandle) -> tauri::Result<()> {
    let open = MenuItem::with_id(app, "open", "Abrir RenanDrop", true, None::<&str>)?;
    let folder = MenuItem::with_id(
        app,
        "folder",
        "Abrir pasta de recebidos",
        true,
        None::<&str>,
    )?;
    let quit = MenuItem::with_id(app, "quit", "Sair", true, None::<&str>)?;
    let sep = PredefinedMenuItem::separator(app)?;
    let menu = Menu::with_items(app, &[&open, &folder, &sep, &quit])?;
    TrayIconBuilder::with_id("main")
        .icon(tauri::image::Image::from_bytes(include_bytes!(
            "../icons/tray.png"
        ))?)
        .tooltip("RenanDrop")
        .menu(&menu)
        .show_menu_on_left_click(true)
        .on_menu_event(|app, e| match e.id().as_ref() {
            "open" => show_window(app),
            "folder" => {
                let dir = app.state::<App>().engine.settings().recv_dir;
                let _ = std::fs::create_dir_all(&dir);
                integration::open(&dir);
            }
            "quit" => quit_app(app),
            _ => {}
        })
        .build(app)?;
    Ok(())
}
