"use strict";
const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

const ROUTES = { lan: "Rede local", tailnet: "Tailscale", local: "Neste PC", internet: "Internet" };
const STATES = {
  queued: "Na fila", connecting: "Conectando", waiting: "Aguardando o aceite", sending: "Enviando",
  receiving: "Recebendo", retrying: "Tentando de novo", done: "Concluído", failed: "Falhou",
  cancelled: "Cancelado", declined: "Recusado",
};
const TAILSCALE = {
  ok: "Tailscale conectado: aparelhos fora de casa aparecem aqui.",
  missing: "Tailscale não encontrado: o app funciona só na rede local.",
  disconnected: "Tailscale desconectado: só a rede local por enquanto.",
  off: "Tailscale desligado nas configurações.",
};

const S = {
  snap: null,
  tab: "peers",
  staged: [],
  targets: new Set(),
  menu: null,
  drawer: null, // { draft, saving }
  modals: [], // { key, node, onOutside }
};

/* ---------- utilidades ---------- */
function h(tag, props = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v == null || v === false) continue;
    if (k === "class") el.className = v;
    else if (k === "text") el.textContent = v;
    else if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else if (k === "checked" || k === "value" || k === "disabled" || k === "hidden") el[k] = v;
    else el.setAttribute(k, v === true ? "" : v);
  }
  for (const kid of kids.flat()) if (kid != null && kid !== false) el.append(kid);
  return el;
}
const $ = (id) => document.getElementById(id);
const fill = (el, ...kids) => el.replaceChildren(...kids.flat(Infinity).filter((k) => k != null && k !== false));
const base = (p) => p.split(/[\\/]/).filter(Boolean).pop() || p;
const initial = (n) => (n || "?").trim().charAt(0).toUpperCase();

function fmtBytes(n) {
  if (!n) return "0 B";
  const u = ["B", "KB", "MB", "GB", "TB"];
  const i = Math.min(Math.floor(Math.log(n) / Math.log(1024)), u.length - 1);
  const v = n / 1024 ** i;
  return `${v >= 100 || i === 0 ? v.toFixed(0) : v.toFixed(1)} ${u[i]}`.replace(".", ",");
}
const SP = "America/Sao_Paulo";
function fmtDate(d) {
  return new Intl.DateTimeFormat("pt-BR", { timeZone: SP, day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit", hour12: false }).format(d).replace(",", "");
}
function fmtShort(ms) {
  return new Intl.DateTimeFormat("pt-BR", { timeZone: SP, day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date(ms)).replace(",", "");
}

function toast(msg, bad = false) {
  const t = h("div", { class: "toast" + (bad ? " bad" : ""), text: msg });
  $("toasts").append(t);
  setTimeout(() => t.remove(), bad ? 6000 : 3200);
}
async function call(cmd, args) {
  try {
    return await invoke(cmd, args);
  } catch (e) {
    toast(String(e), true);
    return undefined;
  }
}
/** Reconstrói um container mantendo o foco no mesmo controle (por posição). */
function keepFocus(box, redraw) {
  const items = () => [...box.querySelectorAll("input, button, select")];
  const idx = box.contains(document.activeElement) ? items().indexOf(document.activeElement) : -1;
  redraw();
  if (idx >= 0) items()[idx]?.focus();
}
const raf = (() => {
  let q = new Set(), on = false;
  return (fn) => {
    q.add(fn);
    if (on) return;
    on = true;
    requestAnimationFrame(() => { const l = [...q]; q = new Set(); on = false; l.forEach((f) => f()); });
  };
})();

function applyTheme(t) {
  const el = document.documentElement;
  if (t === "light" || t === "dark") el.dataset.theme = t;
  else delete el.dataset.theme;
}

/* ---------- carga e eventos ---------- */
async function load() {
  const snap = await call("snapshot");
  if (!snap) return;
  S.snap = snap;
  applyTheme(snap.settings.theme);
  markUpdate();
  const v = snap.version || {};
  $("build-version").textContent = `v${v.version || "0.0.0"} · ${v.built ? fmtDate(new Date(v.built)) : ""}`;
  for (const ev of snap.pending) openPrompt(ev);
  await pullStaged();
  renderAll();
}
async function pullStaged() {
  const paths = await call("take_staged");
  if (paths?.length) { paths.forEach(addStaged); raf(renderStage); }
}
async function refreshPeers() {
  const s = await call("snapshot");
  if (!s || !S.snap) return;
  S.snap.peers = s.peers;
  S.snap.tailscale = s.tailscale;
  raf(renderPeers);
  raf(renderStage);
}
async function refreshHistory() {
  const s = await call("snapshot");
  if (!s || !S.snap) return;
  S.snap.history = s.history;
  S.snap.settings = s.settings;
  raf(renderHistory);
}

function upsertTransfer(t) {
  const list = S.snap.transfers;
  const i = list.findIndex((x) => x.id === t.id);
  if (i >= 0) list[i] = t; else list.push(t);
}

listen("rd", ({ payload: ev }) => {
  if (!S.snap) return;
  switch (ev.type) {
    case "peers": refreshPeers(); break;
    case "transfer": {
      const wasFinished = S.snap.transfers.find((x) => x.id === ev.id)?.finished;
      upsertTransfer(ev);
      if (ev.dir === "recv" && ev.state !== "waiting") closeModal("offer-" + ev.id);
      raf(renderQueue);
      if (ev.finished && !wasFinished) refreshHistory();
      break;
    }
    case "settings": refreshHistory(); break;
    case "update": S.snap.update = ev.update; markUpdate(); if (S.drawer) drawDrawer(); break;
    case "pair_prompt": case "offer": openPrompt(ev); break;
    case "pair_done":
      closeModal("pair");
      toast(ev.ok ? `Pareado com ${ev.peer_name}` : `Pareamento não concluído${ev.error ? `: ${ev.error}` : ""}`, !ev.ok);
      refreshPeers();
      break;
    case "notice": toast(ev.message); break;
    case "drag": $("drop").classList.toggle("over", ev.over); break;
    case "drop": $("drop").classList.remove("over"); ev.paths.forEach(addStaged); renderStage(); break;
    case "staged": pullStaged(); break;
  }
});

/* ---------- itens preparados e envio ---------- */
function addStaged(path) {
  if (!S.staged.includes(path)) S.staged.push(path);
  const online = pairedPeers().filter((p) => p.online);
  if (online.length === 1) S.targets.add(online[0].id);
}
const pairedPeers = () => (S.snap?.peers || []).filter((p) => p.paired);

async function sendTo(ids, paths) {
  if (!ids.length || !paths.length) return false;
  const r = await call("send", { peerIds: ids, paths });
  if (!r) return false;
  toast(ids.length > 1 ? `Na fila para ${ids.length} aparelhos` : "Na fila");
  setTab("queue");
  return true;
}
async function pick(folder) {
  const paths = await call("pick_paths", { folder });
  (paths || []).forEach((p) => addStaged(p));
  renderStage();
}

/* ---------- renderização ---------- */
function renderAll() {
  const s = S.snap;
  $("me").textContent = `${s.settings.device_name} · porta ${s.port}`;
  renderStage(); renderPeers(); renderQueue(); renderHistory();
}

function renderStage() {
  const box = $("stage");
  keepFocus(box, () => drawStage(box));
}
function drawStage(box) {
  box.replaceChildren();
  box.hidden = !S.staged.length;
  if (!S.staged.length) return;
  const peers = pairedPeers();
  for (const id of [...S.targets]) if (!peers.some((p) => p.id === id)) S.targets.delete(id);
  const n = S.targets.size;
  box.append(
    h("div", { class: "stage-head" },
      h("h2", { text: `${S.staged.length} ${S.staged.length === 1 ? "item pronto" : "itens prontos"} para enviar` }),
      h("button", { class: "btn small", text: "Limpar", onclick: () => { S.staged = []; renderStage(); } })),
    h("div", { class: "chips" }, S.staged.map((p) =>
      h("div", { class: "chip", title: p },
        h("span", { text: base(p) }),
        h("button", { "aria-label": `Remover ${base(p)}`, text: "×", onclick: () => { S.staged = S.staged.filter((x) => x !== p); renderStage(); } })))),
    peers.length
      ? h("div", { class: "targets", role: "group", "aria-label": "Destinos" }, peers.map((p) =>
          h("label", { class: "target" },
            h("input", { type: "checkbox", checked: S.targets.has(p.id), disabled: !p.online, onchange: (e) => { e.target.checked ? S.targets.add(p.id) : S.targets.delete(p.id); renderStage(); } }),
            h("span", { class: "dot" + (p.online ? " on" : "") }),
            h("span", { class: "grow name", text: p.name }),
            h("span", { class: "sub", text: p.online ? ROUTES[p.route] || "" : "Offline" }))))
      : h("p", { class: "hint", text: "Pareie um aparelho na aba Aparelhos para poder enviar." }),
    h("button", {
      class: "btn primary", disabled: !n,
      text: n ? `Enviar para ${n} ${n === 1 ? "aparelho" : "aparelhos"}` : "Escolha ao menos um aparelho",
      onclick: async () => { if (await sendTo([...S.targets], S.staged)) { S.staged = []; renderStage(); } },
    }));
}

function peerCard(p) {
  const menuOpen = S.menu === p.id;
  const sub = p.online ? [ROUTES[p.route], p.addr].filter(Boolean).join(" · ") : p.paired ? "Offline" : "";
  const actions = [];
  if (p.paired) {
    actions.push(h("button", { class: "btn small", text: "Enviar…", disabled: !p.online, title: p.online ? "" : "Aparelho offline", onclick: async () => { const ps = await call("pick_paths", { folder: false }); if (ps?.length) sendTo([p.id], ps); } }));
  } else {
    actions.push(h("button", { class: "btn small primary", text: "Parear", disabled: !p.online, onclick: () => call("pair", { peerId: p.id }) }));
  }
  const menu = menuOpen ? h("div", { class: "menu", role: "menu" }, peerMenu(p)) : null;
  actions.push(h("div", { class: "menu-wrap" },
    h("button", { class: "icon-btn", "aria-label": `Mais opções de ${p.name}`, "aria-haspopup": "menu", "aria-expanded": String(menuOpen), onclick: (e) => { e.stopPropagation(); S.menu = menuOpen ? null : p.id; renderPeers(); } },
      h("span", { text: "⋯" })),
    menu));
  return h("div", { class: "card" },
    h("div", { class: "row" },
      h("div", { class: "avatar", text: initial(p.name) }),
      h("div", { class: "grow" },
        h("div", { class: "name", text: p.name }),
        h("div", { class: "sub row" }, h("span", { class: "dot" + (p.online ? " on" : "") }), h("span", { text: sub || (p.online ? "Online" : "Offline") }))),
      actions));
}

function peerMenu(p) {
  const items = [h("button", { role: "menuitem", text: "Renomear…", onclick: () => renameDialog(p) })];
  if (p.paired) {
    const mark = (v) => (p.auto_accept === v ? "✓ " : "   ");
    items.push(
      h("hr"),
      h("button", { role: "menuitem", text: mark(null) + "Seguir a configuração geral", onclick: () => setAuto(p, null) }),
      h("button", { role: "menuitem", text: mark(true) + "Aceitar envios sem perguntar", onclick: () => setAuto(p, true) }),
      h("button", { role: "menuitem", text: mark(false) + "Sempre perguntar", onclick: () => setAuto(p, false) }),
      h("hr"),
      h("button", { class: "danger", role: "menuitem", text: "Revogar pareamento…", onclick: () => revokeDialog(p) }));
  }
  if (p.manual) items.push(h("button", { role: "menuitem", text: "Remover da lista", onclick: async () => { S.menu = null; await call("forget_remote", { id: p.id }); refreshPeers(); } }));
  return items;
}
async function setAuto(p, value) {
  S.menu = null;
  await call("set_peer_auto_accept", { id: p.id, value });
  refreshPeers();
}

function renderPeers() {
  const box = $("panel-peers");
  const s = S.snap;
  const paired = s.peers.filter((p) => p.paired);
  const found = s.peers.filter((p) => !p.paired);
  const typed = $("add-input");
  const keep = typed ? { value: typed.value, focus: document.activeElement === typed } : null;
  const input = h("input", { class: "input", id: "add-input", placeholder: "Nome ou IP (ex.: casa-pc ou 100.64.0.5)", "aria-label": "Nome ou IP do aparelho", autocomplete: "off", spellcheck: "false" });
  const add = async () => {
    const text = input.value.trim();
    if (!text) return;
    const p = await call("add_remote", { text });
    if (p) { input.value = ""; toast(`${p.name} encontrado`); refreshPeers(); }
  };
  input.addEventListener("keydown", (e) => { if (e.key === "Enter") add(); });
  if (keep) input.value = keep.value;
  fill(box,
    h("p", { class: "hint", text: TAILSCALE[s.tailscale] || "" }),
    h("div", { class: "section-title" }, h("span", { text: "Pareados" })),
    paired.length ? paired.map(peerCard) : h("div", { class: "empty", text: "Nenhum aparelho pareado ainda." }),
    found.length ? [h("div", { class: "section-title" }, h("span", { text: "Encontrados" })), found.map(peerCard)] : null,
    h("div", { class: "section-title" }, h("span", { text: "Adicionar por nome ou IP" })),
    h("div", { class: "add" }, input, h("button", { class: "btn", text: "Adicionar", onclick: add })),
    h("p", { class: "hint", text: "Para aparelhos fora da rede local, use o nome do Tailscale ou o IP 100.x." }));
  if (keep?.focus) input.focus();
}

function transferRow(t) {
  const pct = t.total ? Math.min(100, (t.bytes / t.total) * 100) : t.finished && t.state === "done" ? 100 : 0;
  const active = !t.finished;
  const dir = t.dir === "send" ? `Para ${t.peer_name}` : `De ${t.peer_name}`;
  const meta = [
    h("span", { text: STATES[t.state] || t.state }),
    t.total ? h("span", { text: `${fmtBytes(t.bytes)} de ${fmtBytes(t.total)}` }) : null,
    active && t.speed > 0 ? h("span", { text: `${fmtBytes(t.speed)}/s` }) : null,
    t.route ? h("span", { class: "pill " + t.route, text: ROUTES[t.route] || t.route }) : null,
  ];
  const btns = [];
  if (active) btns.push(h("button", { class: "btn small", text: "Cancelar", onclick: () => call("cancel", { id: t.id }) }));
  if (t.finished && t.dir === "send" && t.state !== "done") btns.push(h("button", { class: "btn small", text: "Tentar de novo", onclick: async () => { if ((await call("retry", { id: t.id })) !== undefined) { S.snap.transfers = S.snap.transfers.filter((x) => x.id !== t.id); renderQueue(); } } }));
  if (t.finished && t.dir === "recv" && t.state === "done" && t.path) {
    btns.push(h("button", { class: "btn small", text: "Abrir", onclick: () => call("open_path", { path: t.path }) }),
      h("button", { class: "btn small", text: "Mostrar na pasta", onclick: () => call("reveal_path", { path: t.path }) }));
  }
  return h("div", { class: "card", "data-id": t.id },
    h("div", { class: "row" },
      h("div", { class: "grow" }, h("div", { class: "name", text: t.summary || "Transferência" }), h("div", { class: "sub", text: `${dir} · ${t.files} ${t.files === 1 ? "arquivo" : "arquivos"}` }))),
    h("div", { class: "bar" + (t.state === "done" ? " done" : t.state === "failed" ? " bad" : ""), role: "progressbar", "aria-valuenow": String(Math.round(pct)), "aria-valuemin": "0", "aria-valuemax": "100" }, h("i", { style: `width:${pct}%` })),
    h("div", { class: "t-meta" }, meta),
    t.error ? h("div", { class: "err", text: t.error }) : null,
    btns.length ? h("div", { class: "t-actions" }, btns) : null);
}

// Com o botão do mouse apertado sobre a fila, adia o redesenho: trocar o botão entre o
// mousedown e o mouseup faria o clique se perder (o progresso redesenha a cada 250 ms).
let queueHeld = false, queueLate = false;
document.addEventListener("mousedown", (e) => { queueHeld = !!e.target.closest?.("#panel-queue"); }, true);
document.addEventListener("mouseup", () => {
  queueHeld = false;
  if (queueLate) { queueLate = false; setTimeout(() => S.snap && renderQueue(), 0); }
}, true);

function renderQueue() {
  if (queueHeld) { queueLate = true; return; }
  const list = [...S.snap.transfers].sort((a, b) => Number(a.finished) - Number(b.finished) || b.started - a.started);
  const active = list.filter((t) => !t.finished).length;
  const badge = $("queue-badge");
  badge.hidden = !active;
  badge.textContent = active;
  const box = $("panel-queue");
  fill(box,
    list.some((t) => t.finished) ? h("div", { class: "section-title" }, h("span", { text: "Fila e concluídos" }), h("button", { class: "btn small", text: "Limpar concluídos", onclick: async () => { await call("clear_finished"); S.snap.transfers = S.snap.transfers.filter((t) => !t.finished); renderQueue(); } })) : null,
    list.length ? list.map(transferRow) : h("div", { class: "empty", text: "Nenhuma transferência por enquanto." }));
}

function renderHistory() {
  const hist = S.snap.history;
  fill($("panel-history"),
    hist.length ? h("div", { class: "section-title" }, h("span", { text: "Últimas transferências" }), h("button", { class: "btn small", text: "Limpar histórico", onclick: async () => { await call("clear_history"); S.snap.history = []; renderHistory(); } })) : null,
    hist.length
      ? hist.map((e) => h("div", { class: "card" },
          h("div", { class: "row" },
            h("div", { class: "grow" },
              h("div", { class: "name", text: e.summary }),
              h("div", { class: "sub", text: `${e.dir === "send" ? "Para" : "De"} ${e.peer} · ${fmtBytes(e.bytes)} · ${fmtShort(e.ts)}` })),
            h("span", { class: "pill " + (e.status === "done" ? "lan" : "warn"), text: STATES[e.status] || e.status }),
            e.dir === "recv" && e.status === "done" && e.path ? h("button", { class: "btn small", text: "Mostrar", onclick: () => call("reveal_path", { path: e.path }) }) : null)))
      : h("div", { class: "empty", text: "O histórico aparece aqui." }));
}

function setTab(tab) {
  S.tab = tab;
  for (const b of document.querySelectorAll(".tabs button")) b.setAttribute("aria-selected", String(b.dataset.tab === tab));
  for (const k of ["peers", "queue", "history"]) $("panel-" + k).hidden = k !== tab;
}

/* ---------- janelas (modais) ---------- */
function openModal(key, node, onOutside) {
  closeModal(key);
  S.modals.push({ key, node, onOutside });
  drawModals();
  node.querySelector("button.primary, input, button")?.focus();
}
function closeModal(key) {
  S.modals = S.modals.filter((m) => m.key !== key);
  drawModals();
}
function drawModals() {
  const host = $("modal-host");
  const top = S.modals[S.modals.length - 1];
  host.hidden = !top;
  host.replaceChildren(...(top ? [top.node] : []));
}
$("modal-host").addEventListener("mousedown", (e) => {
  if (e.target === e.currentTarget) S.modals[S.modals.length - 1]?.onOutside?.();
});

function modal(title, body, actions) {
  return h("div", { class: "modal", role: "dialog", "aria-modal": "true", "aria-label": title },
    h("h2", { text: title }), body, h("div", { class: "modal-actions" }, actions));
}

function openPrompt(ev) {
  if (ev.type === "pair_prompt") {
    const answer = (ok) => { closeModal("pair"); S.snap.pending = S.snap.pending.filter((e) => e.req_id !== ev.req_id); call("confirm_pair", { reqId: ev.req_id, ok }); };
    const code = ev.code.replace(/(\d{3})(\d{3})/, "$1 $2");
    openModal("pair",
      modal(`Parear com ${ev.peer_name}`,
        h("div", {}, h("div", { class: "code", text: code }), h("p", { class: "hint", text: "Confirme que este mesmo código aparece no outro aparelho. Se for diferente, cancele." })),
        [h("button", { class: "btn", text: "Cancelar", onclick: () => answer(false) }), h("button", { class: "btn primary", text: "É o mesmo código", onclick: () => answer(true) })]),
      () => answer(false));
  } else if (ev.type === "offer") {
    const answer = (accept) => { closeModal("offer-" + ev.tid); call("respond_offer", { tid: ev.tid, accept }); };
    openModal("offer-" + ev.tid,
      modal(`${ev.peer_name} quer enviar`,
        h("p", { text: `${ev.summary} · ${ev.files} ${ev.files === 1 ? "arquivo" : "arquivos"} · ${fmtBytes(ev.total)}` }),
        [h("button", { class: "btn", text: "Recusar", onclick: () => answer(false) }), h("button", { class: "btn primary", text: "Aceitar", onclick: () => answer(true) })]),
      () => answer(false));
  }
}

function renameDialog(p) {
  S.menu = null; renderPeers();
  const input = h("input", { class: "input", value: p.name, "aria-label": "Novo nome" });
  const save = async () => { const v = input.value.trim(); closeModal("rename"); if (v && v !== p.name) { await call("rename_peer", { id: p.id, name: v }); refreshPeers(); } };
  input.addEventListener("keydown", (e) => { if (e.key === "Enter") save(); });
  openModal("rename", modal("Renomear aparelho", input, [h("button", { class: "btn", text: "Cancelar", onclick: () => closeModal("rename") }), h("button", { class: "btn primary", text: "Salvar", onclick: save })]), () => closeModal("rename"));
  input.select();
}

function revokeDialog(p) {
  S.menu = null; renderPeers();
  const go = async () => { closeModal("revoke"); await call("unpair", { id: p.id }); toast(`${p.name} não está mais pareado`); refreshPeers(); };
  openModal("revoke",
    modal(`Revogar ${p.name}?`, h("p", { text: "Esse aparelho deixa de poder enviar para este. Para voltar, é preciso parear de novo." }),
      [h("button", { class: "btn", text: "Cancelar", onclick: () => closeModal("revoke") }), h("button", { class: "btn danger", text: "Revogar", onclick: go })]),
    () => closeModal("revoke"));
}

/* ---------- configurações ---------- */
const dirty = () => S.drawer && JSON.stringify(S.drawer.draft) !== JSON.stringify(S.snap.settings);

function openSettings() {
  if (S.drawer) return;
  S.drawer = { draft: { ...S.snap.settings }, saving: false };
  drawDrawer();
  $("drawer").querySelector("input")?.focus();
}
const upd = () => S.snap?.update || { state: "idle" };
const installing = () => upd().state === "installing";
/** Ponto na engrenagem quando há versão nova esperando. */
function markUpdate() {
  $("btn-settings").classList.toggle("has-update", upd().state === "available");
}
async function checkNow() {
  if (["checking", "installing"].includes(upd().state)) return;
  S.snap.update = { state: "checking" };
  drawDrawer();
  S.snap.update = (await call("check_update")) || { state: "idle" };
  markUpdate();
  if (S.drawer) drawDrawer();
}
function installNow() {
  if (installing()) return;
  const go = async () => {
    closeModal("install");
    S.snap.update = { state: "installing" };
    drawDrawer();
    // Sucesso: o app reinicia. Falha: o estado "error" chega pelo evento e aparece no bloco.
    try { await invoke("install_update"); } catch (e) { if (installing()) { S.snap.update = { state: "error", message: String(e) }; drawDrawer(); } }
  };
  const busy = S.snap.transfers.some((t) => !t.finished);
  if (!busy) return go();
  openModal("install",
    modal("Há transferências em andamento", h("p", { text: "Instalar agora reinicia o RenanDrop e interrompe o que está sendo enviado ou recebido." }),
      [h("button", { class: "btn primary", text: "Esperar", onclick: () => closeModal("install") }), h("button", { class: "btn danger", text: "Instalar mesmo assim", onclick: go })]),
    () => closeModal("install"));
}
function updateBlock() {
  const u = upd();
  const v = S.snap.version || {};
  const line = {
    idle: "Ainda não verificado.",
    checking: "Procurando…",
    up_to_date: "Você está na versão mais recente.",
    available: `Versão ${u.version} disponível.`,
    installing: "Baixando e conferindo a assinatura… o app reinicia sozinho.",
    error: u.message,
  }[u.state] || "";
  return h("div", { class: "field", id: "updates" }, h("span", { class: "label", text: "Atualizações" }),
    h("p", { class: "hint", text: `Versão atual: v${v.version || "0.0.0"}` }),
    h("p", { class: "upd-line" + (u.state === "error" ? " err" : ""), id: "upd-line", role: "status", text: line }),
    h("div", { class: "upd-actions" },
      h("button", { class: "btn", id: "upd-check", text: u.state === "checking" ? "Procurando…" : "Procurar agora", "aria-disabled": String(u.state === "checking" || installing()), onclick: checkNow }),
      u.state === "available" ? h("button", { class: "btn primary", id: "upd-install", text: "Instalar e reiniciar", onclick: installNow }) : null),
    h("p", { class: "hint", text: "Só instala o que estiver assinado pelo autor do RenanDrop." }));
}
function requestCloseSettings() {
  if (!S.drawer || S.drawer.saving || installing()) return;
  if (!dirty()) return closeSettings();
  openModal("discard",
    modal("Descartar alterações?", h("p", { text: "As mudanças nas configurações ainda não foram salvas." }),
      [h("button", { class: "btn primary", text: "Continuar editando", onclick: () => closeModal("discard") }), h("button", { class: "btn danger", text: "Descartar", onclick: () => { closeModal("discard"); closeSettings(); } })]),
    () => closeModal("discard"));
}
function closeSettings() {
  S.drawer = null;
  applyTheme(S.snap.settings.theme);
  $("drawer").hidden = true;
  $("overlay").hidden = true;
}
async function saveSettings() {
  const d = S.drawer;
  d.saving = true;
  drawDrawer();
  const saved = await call("update_settings", { patch: d.draft });
  if (saved) {
    S.snap.settings = saved;
    $("me").textContent = `${saved.device_name} · porta ${S.snap.port}`;
    toast("Configurações salvas");
    closeSettings();
  } else {
    d.saving = false;
    drawDrawer();
  }
}

function drawDrawer() {
  const d = S.drawer;
  const dr = $("drawer");
  $("overlay").hidden = dr.hidden = !d;
  if (!d) return;
  const typing = (k) => (e) => { d.draft[k] = k === "lanes" ? +e.target.value : e.target.value; $("save-btn").disabled = !dirty(); };
  const set = (k, v) => { d.draft[k] = v; if (k === "theme") applyTheme(v); drawDrawer(); };
  const sw = (k, label, hint) => h("label", { class: "switch" },
    h("input", { type: "checkbox", checked: !!d.draft[k], disabled: d.saving, onchange: (e) => set(k, e.target.checked) }),
    h("span", {}, h("span", { text: label }), hint ? h("div", { class: "hint", text: hint }) : null));
  const items = () => [...dr.querySelectorAll("input, button")];
  const focusIdx = dr.contains(document.activeElement) ? items().indexOf(document.activeElement) : -1;
  const caret = focusIdx >= 0 && document.activeElement.type === "text" ? [document.activeElement.selectionStart, document.activeElement.selectionEnd] : null;
  const scroll = dr.querySelector(".drawer-body")?.scrollTop || 0; // redesenhar não pode devolver a gaveta ao topo
  dr.replaceChildren(
    h("div", { class: "drawer-head" }, h("h2", { id: "drawer-title", text: "Configurações" }),
      h("button", { class: "icon-btn", "aria-label": "Fechar", text: "✕", disabled: installing(), onclick: requestCloseSettings })),
    h("div", { class: "drawer-body" },
      h("div", { class: "field" }, h("label", { for: "f-name", text: "Nome deste aparelho" }),
        h("input", { class: "input", id: "f-name", value: d.draft.device_name, disabled: d.saving, oninput: typing("device_name") })),
      h("div", { class: "field" }, h("label", { for: "f-dir", text: "Pasta de recebimento" }),
        h("div", { class: "path-row" },
          h("input", { class: "input", id: "f-dir", value: d.draft.recv_dir, disabled: d.saving, oninput: typing("recv_dir") }),
          h("button", { class: "btn", text: "Escolher", disabled: d.saving, onclick: async () => { const p = await call("pick_recv_dir"); if (p) set("recv_dir", p); } }))),
      h("div", { class: "field" }, h("span", { class: "label", text: "Tema" }),
        h("div", { class: "seg", role: "group", "aria-label": "Tema" }, [["auto", "Automático"], ["light", "Claro"], ["dark", "Escuro"]].map(([v, l]) =>
          h("button", { "aria-pressed": String(d.draft.theme === v), text: l, onclick: () => set("theme", v) })))),
      sw("auto_accept", "Aceitar envios de aparelhos pareados sem perguntar", "Cada aparelho ainda pode ter sua própria regra."),
      sw("notifications", "Mostrar notificações"),
      sw("autostart", "Iniciar com o sistema", "Fica na bandeja, pronto para receber."),
      sw("tailscale", "Procurar aparelhos pelo Tailscale", "Só usado se o Tailscale estiver instalado e conectado."),
      sw("auto_update", "Procurar atualizações automaticamente", "Ao iniciar e a cada 6 horas; você decide quando instalar."),
      h("div", { class: "field" }, h("label", { id: "lanes-label", for: "f-lanes", text: `Conexões paralelas por envio: ${d.draft.lanes}` }),
        h("input", { id: "f-lanes", type: "range", min: "1", max: "8", value: String(d.draft.lanes), disabled: d.saving, oninput: (e) => { typing("lanes")(e); $("lanes-label").textContent = `Conexões paralelas por envio: ${d.draft.lanes}`; } }),
        h("p", { class: "hint", text: "Mais conexões aproveitam melhor a rede; 4 serve para a maioria." })),
      updateBlock(),
      h("div", { class: "field" }, h("span", { class: "label", text: "Sair" }),
        h("button", { class: "btn danger", text: "Encerrar o RenanDrop", onclick: () => call("quit") }),
        h("p", { class: "hint", text: "Fechar a janela só a esconde; o app continua recebendo pela bandeja." }))),
    h("div", { class: "drawer-foot" },
      h("button", { class: "btn", text: "Cancelar", disabled: d.saving || installing(), onclick: requestCloseSettings }),
      h("button", { class: "btn primary", id: "save-btn", text: d.saving ? "Salvando…" : "Salvar", disabled: d.saving || installing() || !dirty(), onclick: saveSettings })));
  dr.querySelector(".drawer-body").scrollTop = scroll;
  if (focusIdx >= 0) items()[focusIdx]?.focus({ preventScroll: true });
  if (caret) try { document.activeElement.setSelectionRange(...caret); } catch {}
}
$("overlay").addEventListener("mousedown", requestCloseSettings);

/* ---------- ligações fixas ---------- */
$("btn-settings").addEventListener("click", openSettings);
$("btn-rescan").addEventListener("click", (e) => {
  const b = e.currentTarget;
  b.classList.remove("spin"); void b.offsetWidth; b.classList.add("spin");
  call("rescan");
  setTimeout(refreshPeers, 1200);
});
$("pick-files").addEventListener("click", () => pick(false));
$("pick-folder").addEventListener("click", () => pick(true));
document.querySelectorAll(".tabs button").forEach((b) => b.addEventListener("click", () => setTab(b.dataset.tab)));
document.addEventListener("click", () => { if (S.menu) { S.menu = null; renderPeers(); } });
document.addEventListener("keydown", (e) => {
  if (e.key !== "Escape") return;
  if (S.modals.length) S.modals[S.modals.length - 1].onOutside?.();
  else if (S.menu) { S.menu = null; renderPeers(); }
  else if (S.drawer) requestCloseSettings();
});
document.addEventListener("contextmenu", (e) => e.preventDefault());
setInterval(() => { if (S.snap && S.tab === "peers" && !S.menu && !S.drawer) refreshPeers(); }, 10000);

load();
