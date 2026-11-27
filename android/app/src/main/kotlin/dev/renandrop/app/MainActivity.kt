package dev.renandrop.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.renandrop.protocol.TransferInfo

class MainActivity : Activity(), RowActions {
    private lateinit var engine: Engine
    private lateinit var ui: Ui
    private lateinit var themeKey: ThemeMode
    private lateinit var root: FrameLayout
    private lateinit var overlay: Overlay
    private lateinit var main: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var badge: TextView
    private var insets = Rect()
    private var snap: Snapshot? = null
    private var tab = 0

    // cabeçalho
    private lateinit var status: TextView

    // enviar
    private lateinit var stageEmpty: LinearLayout
    private lateinit var stageFull: LinearLayout
    private lateinit var sendPanel: SendPanel
    private var staged: Staged? = null
    private var pickTarget: String? = null

    // pedidos pendentes
    private lateinit var pendingCard: LinearLayout
    private var pairLayer: Overlay.Layer? = null
    private var pairKey: String? = null
    private var offerLayer: Overlay.Layer? = null
    private var offerKey: String? = null
    private val dismissed = HashSet<String>()

    // abas
    private val tabViews = ArrayList<TextView>()
    private lateinit var devicesBox: LinearLayout
    private lateinit var transfersBox: LinearLayout
    private lateinit var historyBox: LinearLayout
    private lateinit var pairedList: KeyedList<PeerRow>
    private lateinit var nearbyList: KeyedList<PeerRow>
    private lateinit var transferList: KeyedList<TransferInfo>
    private lateinit var historyList: KeyedList<HistoryEntry>
    private lateinit var pairedEmpty: TextView
    private lateinit var nearbyEmpty: TextView
    private lateinit var tsText: TextView
    private lateinit var transfersEmpty: TextView
    private lateinit var historyEmpty: TextView

    // ajustes
    private var settingsLayer: Overlay.Layer? = null
    private var onFolderPicked: ((String, String) -> Unit)? = null

    private val listener: (Snapshot) -> Unit = { onSnapshot(it) }
    private val notice: (String) -> Unit = { ui.toast(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = applicationContext.engine
        val st = engine.snapshot.settings
        themeKey = st.theme
        val dark = isDarkTheme(st.theme, Edge.systemDark(this))
        ui = Ui(this, Palette(dark))
        window.setBackgroundDrawable(ColorDrawable(ui.p.bg))
        Edge.setup(window, dark)
        build()
        NodeService.start(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        if (i.hasExtra(EXTRA_TAB)) {
            selectTab(i.getIntExtra(EXTRA_TAB, 0))
            dismissed.clear()
            snap?.let { syncAsk(it) }
            i.removeExtra(EXTRA_TAB)
        }
        if (i.getBooleanExtra(EXTRA_SEND, false)) {
            i.removeExtra(EXTRA_SEND)
            root.post { pickFiles(null) }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!NodeService.active) NodeService.start(this)
        engine.onNotice = notice
        engine.addListener(listener)
    }

    override fun onStop() {
        engine.removeListener(listener)
        if (engine.onNotice === notice) engine.onNotice = null
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!overlay.back()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ───────────── Construção da tela ─────────────

    private fun build() {
        val p = ui.p
        root = FrameLayout(this)
        main = ui.vbox()
        overlay = Overlay(ui, root)

        // Cabeçalho
        val header = ui.hbox().apply { setPadding(ui.dp(16), ui.dp(12), ui.dp(8), ui.dp(8)) }
        header.addView(ui.avatar("R", 40).apply { text = "R" })
        status = ui.muted("", 12f)
        val titles = ui.vbox().apply {
            addView(ui.text("RenanDrop", 20f, bold = true))
            addView(status, ui.lp(top = 3))
        }
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(12) })
        header.addView(ui.button("Ajustes", Ui.Kind.GHOST) { openSettings() })
        main.addView(header)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        content = ui.vbox().apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(40)) }
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        main.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(main, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Pedido esperando resposta
        pendingCard = ui.card(14).apply {
            background = ui.shape(p.surface, 16f, p.accent)
            addView(ui.text("Há um pedido esperando a sua resposta", 14f, bold = true))
            addView(ui.button("Responder", Ui.Kind.PRIMARY) {
                dismissed.clear()
                snap?.let { syncAsk(it) }
            }, ui.lp(top = 10))
            visibility = View.GONE
        }
        content.addView(pendingCard, ui.lp(top = 8))

        // Enviar
        val stage = ui.card(16)
        stageEmpty = ui.vbox().apply {
            addView(ui.text("Enviar", 17f, bold = true))
            addView(ui.muted("Escolha o que enviar e para quais aparelhos.", 13f), ui.lp(top = 4))
            val row = ui.hbox()
            row.addView(ui.button("Arquivos", Ui.Kind.PRIMARY) { pickFiles(null) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ui.button("Pasta", Ui.Kind.SECONDARY) { pickFolder() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(8) })
            addView(row, ui.lp(top = 12))
        }
        sendPanel = SendPanel(ui, engine, onSent = ::sent, onPairFirst = { selectTab(0) })
        stageFull = ui.vbox().apply {
            val top = ui.hbox()
            top.addView(ui.text("Enviar", 17f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(ui.button("Cancelar", Ui.Kind.GHOST) { clearStaged() })
            addView(top)
            addView(sendPanel.view, ui.lp(top = 8))
            visibility = View.GONE
        }
        stage.addView(stageEmpty)
        stage.addView(stageFull)
        content.addView(stage, ui.lp(top = 12))

        // Abas
        val tabs = ui.hbox().apply {
            background = ui.shape(p.surface2, 14f)
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
        }
        listOf("Aparelhos", "Transferências", "Histórico").forEachIndexed { i, name ->
            val t = TextView(this).apply {
                text = name
                textSize = 13f
                gravity = Gravity.CENTER
                minHeight = ui.dp(40)
                isClickable = true
                isFocusable = true
                setOnClickListener { selectTab(i) }
            }
            tabViews.add(t)
            tabs.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        content.addView(tabs, ui.lp(top = 16))

        buildDevices()
        buildTransfers()
        buildHistory()
        content.addView(devicesBox, ui.lp(top = 8))
        content.addView(transfersBox, ui.lp(top = 8))
        content.addView(historyBox, ui.lp(top = 8))

        badge = ui.versionBadge(insets)
        root.addView(badge)
        selectTab(0)

        root.setOnApplyWindowInsetsListener { _, w ->
            insets = Edge.rect(w)
            main.setPadding(insets.left, insets.top, insets.right, 0)
            content.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(40) + insets.bottom)
            overlay.setInsets(insets)
            (badge.layoutParams as FrameLayout.LayoutParams).setMargins(0, 0, ui.dp(10) + insets.right, ui.dp(4) + insets.bottom)
            badge.requestLayout()
            w
        }
        setContentView(root)
    }

    private fun sectionTitle(s: String): TextView = ui.text(s, 13f, ui.p.muted, true).apply { setPadding(ui.dp(2), ui.dp(8), 0, ui.dp(2)) }

    private fun buildDevices() {
        devicesBox = ui.vbox()
        devicesBox.addView(sectionTitle("Seus aparelhos"))
        val paired = ui.vbox()
        pairedEmpty = ui.muted("Nenhum aparelho pareado. Abra o RenanDrop no outro aparelho e toque em Parear.", 13f)
        devicesBox.addView(pairedEmpty, ui.lp(top = 4))
        devicesBox.addView(paired)
        pairedList = KeyedList(paired, { it.id }, { PeerRowView(ui, this) })

        devicesBox.addView(sectionTitle("Encontrados na rede").apply { setPadding(ui.dp(2), ui.dp(20), 0, ui.dp(2)) })
        val near = ui.vbox()
        nearbyEmpty = ui.muted("Procurando… Os dois aparelhos precisam estar na mesma rede Wi-Fi ou no mesmo Tailscale.", 13f)
        devicesBox.addView(nearbyEmpty, ui.lp(top = 4))
        devicesBox.addView(near)
        nearbyList = KeyedList(near, { it.id }, { PeerRowView(ui, this) })
        devicesBox.addView(ui.button("Procurar de novo", Ui.Kind.SECONDARY) { engine.rescan() }, ui.lp(top = 10))

        val ts = ui.card(14)
        ts.addView(ui.text("Fora de casa", 15f, bold = true))
        tsText = ui.muted("", 13f)
        ts.addView(tsText, ui.lp(top = 4))
        ts.addView(ui.button("Adicionar por nome ou IP", Ui.Kind.SECONDARY) { openAddRemote() }, ui.lp(top = 10))
        devicesBox.addView(ts, ui.lp(top = 20))
    }

    private fun buildTransfers() {
        transfersBox = ui.vbox()
        val head = ui.hbox()
        head.addView(sectionTitle("Fila"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(ui.button("Limpar concluídas", Ui.Kind.GHOST) { engine.clearFinished() })
        transfersBox.addView(head)
        transfersEmpty = ui.muted("Nenhuma transferência. Envie algo ou aguarde um envio.", 13f)
        transfersBox.addView(transfersEmpty, ui.lp(top = 4))
        val list = ui.vbox()
        transfersBox.addView(list)
        transferList = KeyedList(list, { it.id }, { TransferRowView(ui, this) })
    }

    private fun buildHistory() {
        historyBox = ui.vbox()
        val head = ui.hbox()
        head.addView(sectionTitle("Histórico"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(ui.button("Limpar", Ui.Kind.GHOST) {
            confirm("Limpar o histórico?", "Isso apaga só a lista. Os arquivos recebidos continuam na pasta.", "Limpar", true) { engine.clearHistory() }
        })
        historyBox.addView(head)
        historyEmpty = ui.muted("O que você enviar ou receber aparece aqui.", 13f)
        historyBox.addView(historyEmpty, ui.lp(top = 4))
        val list = ui.vbox()
        historyBox.addView(list)
        historyList = KeyedList(list, { it.id }, { HistoryRowView(ui, this) })
    }

    private fun selectTab(i: Int) {
        tab = i.coerceIn(0, 2)
        devicesBox.visibility = if (tab == 0) View.VISIBLE else View.GONE
        transfersBox.visibility = if (tab == 1) View.VISIBLE else View.GONE
        historyBox.visibility = if (tab == 2) View.VISIBLE else View.GONE
        styleTabs()
    }

    private fun styleTabs() {
        val active = snap?.active ?: 0
        val names = listOf("Aparelhos", if (active > 0) "Transferências ($active)" else "Transferências", "Histórico")
        tabViews.forEachIndexed { i, t ->
            val sel = i == tab
            t.text = names[i]
            t.background = if (sel) ui.shape(ui.p.surface, 10f, ui.p.border) else null
            t.setTextColor(if (sel) ui.p.text else ui.p.muted)
            t.typeface = Typeface.create(Typeface.DEFAULT, if (sel) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    // ───────────── Atualização por snapshot ─────────────

    private fun onSnapshot(s: Snapshot) {
        if (s.settings.theme != themeKey) {
            recreate()
            return
        }
        snap = s
        val line = when {
            s.error != null -> s.error
            s.starting -> "Iniciando…"
            !s.running -> "Serviço parado"
            else -> "${s.settings.deviceName} · recebe em ${s.folder}"
        }
        ui.setIfChanged(status, line)
        status.setTextColor(if (s.error != null) ui.p.bad else ui.p.muted)
        pairedList.update(s.peers)
        nearbyList.update(s.nearby)
        ui.visible(pairedEmpty, s.peers.isEmpty())
        ui.visible(nearbyEmpty, s.nearby.isEmpty())
        ui.setIfChanged(tsText, tailscaleText(s.tailscale))
        transferList.update(s.transfers)
        ui.visible(transfersEmpty, s.transfers.isEmpty())
        historyList.update(s.history)
        ui.visible(historyEmpty, s.history.isEmpty())
        styleTabs()
        if (staged != null) sendPanel.render(s)
        syncAsk(s)
    }

    // ───────────── Enviar ─────────────

    private fun pickFiles(target: String?) {
        pickTarget = target
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            startActivityForResult(i, REQ_FILES)
        } catch (_: Exception) {
            ui.toast("Não foi possível abrir o seletor de arquivos")
        }
    }

    private fun pickFolder() {
        pickTarget = null
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE_SEND)
        } catch (_: Exception) {
            ui.toast("Não foi possível abrir o seletor de pastas")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_FILES -> {
                val uris = ArrayList<Uri>()
                data.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) }
                if (uris.isEmpty()) data.data?.let { uris.add(it) }
                if (uris.isNotEmpty()) {
                    NodeService.keepGrants(this, uris)
                    stage(Staged(uris), pickTarget)
                }
            }
            REQ_TREE_SEND -> data.data?.let {
                NodeService.keepGrants(this, listOf(it))
                stage(Staged(emptyList(), tree = it), null)
            }
            REQ_TREE_SETTINGS -> data.data?.let {
                persist(it, true)
                val name = try { SendBuilder.treeName(contentResolver, it) } catch (_: Exception) { "Pasta escolhida" }
                onFolderPicked?.invoke(it.toString(), name)
            }
        }
    }

    private fun persist(uri: Uri, write: Boolean) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0,
            )
        } catch (_: Exception) {
        }
    }

    private fun stage(s: Staged, target: String?) {
        staged = s
        sendPanel.bind(s, target)
        snap?.let { sendPanel.render(it) }
        stageEmpty.visibility = View.GONE
        stageFull.visibility = View.VISIBLE
    }

    private fun clearStaged() {
        staged = null
        stageEmpty.visibility = View.VISIBLE
        stageFull.visibility = View.GONE
    }

    private fun sent() {
        clearStaged()
        selectTab(1)
        ui.toast("Na fila de envio")
    }

    // ───────────── Ações das linhas ─────────────

    override fun pair(p: PeerRow) {
        engine.pair(p.id)
        ui.toast("Pareando com ${p.name}…")
    }

    override fun sendTo(p: PeerRow) = pickFiles(p.id)

    override fun cancel(t: TransferInfo) = engine.cancel(t.id)

    override fun retry(t: TransferInfo) = engine.retry(t.id)

    override fun open(e: HistoryEntry) {
        if (!Opener.launch(this, Opener.open(e.saved))) ui.toast("Nenhum app consegue abrir este arquivo")
    }

    override fun showFolder(e: HistoryEntry) {
        if (!Opener.launch(this, Opener.folder(e.saved), Opener.downloads())) ui.toast("Não foi possível abrir a pasta")
    }

    override fun peerMenu(p: PeerRow) {
        var layer: Overlay.Layer? = null
        val close = { layer?.let { overlay.close(it) }; Unit }
        val box = modalBox(p.name)
        box.addView(ui.muted(if (p.online) "Online · ${routeLabel(p.route)}" else "Offline", 13f))
        fun item(label: String, kind: Ui.Kind = Ui.Kind.SECONDARY, run: () -> Unit) =
            box.addView(ui.button(label, kind) { close(); run() }, ui.lp(top = 8))
        item("Enviar arquivos") { pickFiles(p.id) }
        item("Renomear") { openRename(p) }
        var auto = p.autoAccept
        val autoBtn = ui.button(autoLabel(auto), Ui.Kind.SECONDARY) {}
        autoBtn.setOnClickListener {
            auto = when (auto) { null -> true; true -> false; false -> null }
            engine.setAutoAccept(p.id, auto)
            autoBtn.text = autoLabel(auto)
        }
        box.addView(autoBtn, ui.lp(top = 8))
        if (p.manual) item("Esquecer o endereço") { engine.forgetRemote(p.id) }
        item("Revogar pareamento", Ui.Kind.DANGER) {
            confirm(
                "Revogar ${p.name}?",
                "Ele deixa de enviar e receber com este aparelho. Para voltar, é preciso parear de novo nos dois lados.",
                "Revogar", true,
            ) { engine.unpair(p.id) }
        }
        box.addView(ui.button("Fechar", Ui.Kind.GHOST) { close() }, ui.lp(top = 8))
        layer = overlay.modal(box) { close() }
    }

    private fun autoLabel(v: Boolean?) = when (v) {
        null -> "Receber deste aparelho: padrão geral"
        true -> "Receber deste aparelho: sem perguntar"
        false -> "Receber deste aparelho: sempre perguntar"
    }

    // ───────────── Modais ─────────────

    private fun modalBox(title: String): LinearLayout = ui.card(20).apply {
        addView(ui.text(title, 18f, bold = true).apply { setPadding(0, 0, 0, ui.dp(6)) })
    }

    private fun confirm(title: String, message: String, ok: String, danger: Boolean, onOk: () -> Unit) {
        var layer: Overlay.Layer? = null
        val close = { layer?.let { overlay.close(it) }; Unit }
        val box = modalBox(title)
        box.addView(ui.muted(message, 14f))
        val row = ui.hbox().apply { gravity = Gravity.END }
        row.addView(ui.button("Cancelar", Ui.Kind.GHOST) { close() })
        row.addView(ui.button(ok, if (danger) Ui.Kind.DANGER else Ui.Kind.PRIMARY) { close(); onOk() }, ui.lp(w = -2).apply { marginStart = ui.dp(8) })
        box.addView(row, ui.lp(top = 16))
        layer = overlay.modal(box) { close() }
    }

    /** "Descartar alterações?" para qualquer camada com campos editados. */
    private fun confirmDiscard(onDiscard: () -> Unit) {
        var layer: Overlay.Layer? = null
        val close = { layer?.let { overlay.close(it) }; Unit }
        val box = modalBox("Descartar alterações?")
        box.addView(ui.muted("O que você mudou ainda não foi salvo.", 14f))
        val row = ui.hbox().apply { gravity = Gravity.END }
        row.addView(ui.button("Continuar editando", Ui.Kind.GHOST) { close() })
        row.addView(ui.button("Descartar", Ui.Kind.DANGER) { close(); onDiscard() }, ui.lp(w = -2).apply { marginStart = ui.dp(8) })
        box.addView(row, ui.lp(top = 16))
        layer = overlay.modal(box) { close() }
    }

    private fun openRename(p: PeerRow) {
        var layer: Overlay.Layer? = null
        val close = { layer?.let { overlay.close(it) }; Unit }
        val box = modalBox("Renomear aparelho")
        val field = ui.input("Nome", p.name)
        box.addView(field, ui.lp(top = 8))
        val save = ui.button("Salvar", Ui.Kind.PRIMARY) {
            engine.rename(p.id, field.text.toString())
            close()
        }
        fun dirty() = field.text.toString().trim() != p.name
        fun refresh() {
            val on = dirty() && field.text.toString().isNotBlank()
            save.isEnabled = on
            save.alpha = if (on) 1f else 0.5f
        }
        field.addTextChangedListener(watcher { refresh() })
        val row = ui.hbox().apply { gravity = Gravity.END }
        row.addView(ui.button("Cancelar", Ui.Kind.GHOST) { if (dirty()) confirmDiscard { close() } else close() })
        row.addView(save, ui.lp(w = -2).apply { marginStart = ui.dp(8) })
        box.addView(row, ui.lp(top = 16))
        refresh()
        layer = overlay.modal(box) { if (dirty()) confirmDiscard { close() } else close() }
    }

    private fun openAddRemote() {
        var layer: Overlay.Layer? = null
        val close = { layer?.let { overlay.close(it) }; Unit }
        var busy = false
        var done = false
        val box = modalBox("Adicionar por nome ou IP")
        box.addView(ui.muted("Use o IP do Tailscale (100.x.x.x) ou o nome MagicDNS do outro aparelho, com o RenanDrop aberto nele.", 13f))
        val field = ui.input("Ex.: 100.101.102.103 ou meu-pc.tailnet.ts.net")
        box.addView(field, ui.lp(top = 12))
        val result = ui.text("", 13f)
        result.visibility = View.GONE
        box.addView(result, ui.lp(top = 10))
        val go = ui.button("Buscar", Ui.Kind.PRIMARY) {}
        val cancel = ui.button("Cancelar", Ui.Kind.GHOST) {}
        fun dirty() = !done && field.text.toString().isNotBlank()
        fun refresh() {
            val on = !busy && (done || field.text.toString().isNotBlank())
            go.isEnabled = on
            go.alpha = if (on) 1f else 0.5f
            go.text = when {
                busy -> "Buscando…"
                done -> "Fechar"
                else -> "Buscar"
            }
            cancel.visibility = if (done) View.GONE else View.VISIBLE
            cancel.isEnabled = !busy
        }
        fun requestClose() {
            if (busy) return
            if (dirty()) confirmDiscard { close() } else close()
        }
        go.setOnClickListener {
            if (done) return@setOnClickListener close()
            busy = true
            result.visibility = View.GONE
            refresh()
            engine.addRemote(field.text.toString()) { r ->
                busy = false
                result.visibility = View.VISIBLE
                when (r) {
                    is RemoteResult.Found -> {
                        done = true
                        result.setTextColor(ui.p.ok)
                        result.text = "Encontrado: ${r.name} · ${routeLabel(r.route)}. " +
                            if (r.paired) "Ele já está pareado e agora também é alcançado por este endereço." else "Ele aparece em \"Encontrados na rede\" para parear."
                    }
                    is RemoteResult.Failed -> {
                        result.setTextColor(ui.p.bad)
                        result.text = r.message
                    }
                }
                refresh()
            }
        }
        cancel.setOnClickListener { requestClose() }
        field.addTextChangedListener(watcher { refresh() })
        val row = ui.hbox().apply { gravity = Gravity.END }
        row.addView(cancel)
        row.addView(go, ui.lp(w = -2).apply { marginStart = ui.dp(8) })
        box.addView(row, ui.lp(top = 16))
        refresh()
        layer = overlay.modal(box) { requestClose() }
    }

    // ───────────── Pareamento e ofertas ─────────────

    private fun syncAsk(s: Snapshot) {
        val p = s.pair?.takeIf { it.reqId !in dismissed }
        if (pairLayer != null && pairKey != p?.reqId) {
            overlay.close(pairLayer!!)
            pairLayer = null
            pairKey = null
        }
        if (p != null && pairLayer == null && !isFinishing) showPair(p)

        val o = s.offers.firstOrNull { it.tid !in dismissed }
        if (offerLayer != null && offerKey != o?.tid) {
            overlay.close(offerLayer!!)
            offerLayer = null
            offerKey = null
        }
        if (o != null && offerLayer == null && !isFinishing) showOffer(o)
        val waiting = (s.pair != null && pairLayer == null) || (s.offers.isNotEmpty() && offerLayer == null)
        ui.visible(pendingCard, waiting)
    }

    private fun showPair(p: PairPromptView) {
        val box = modalBox(if (p.outgoing) "Pareando com ${p.peerName}" else "${p.peerName} quer parear")
        box.addView(ui.muted("Confira se o mesmo código aparece no outro aparelho.", 14f))
        box.addView(
            ui.text(Format.code(p.code), 40f, ui.p.accent, true).apply { gravity = Gravity.CENTER; letterSpacing = 0.1f },
            ui.lp(top = 16),
        )
        val col = ui.vbox()
        col.addView(ui.button("É o mesmo código", Ui.Kind.PRIMARY) { engine.confirmPair(p.reqId, true) })
        col.addView(ui.button("Não é o mesmo", Ui.Kind.DANGER) { engine.confirmPair(p.reqId, false) }, ui.lp(top = 8))
        box.addView(col, ui.lp(top = 16))
        pairKey = p.reqId
        pairLayer = overlay.modal(box) {
            dismissed.add(p.reqId)
            snap?.let { syncAsk(it) }
        }
    }

    private fun showOffer(o: OfferView) {
        val box = modalBox("${o.peerName} quer enviar")
        box.addView(ui.text(o.summary, 15f, bold = true))
        box.addView(ui.muted("${Format.files(o.files)} · ${Format.bytes(o.total)}", 13f), ui.lp(top = 4))
        val row = ui.hbox().apply { gravity = Gravity.END }
        row.addView(ui.button("Recusar", Ui.Kind.DANGER) { engine.respondOffer(o.tid, false) })
        row.addView(ui.button("Aceitar", Ui.Kind.PRIMARY) { engine.respondOffer(o.tid, true) }, ui.lp(w = -2).apply { marginStart = ui.dp(8) })
        box.addView(row, ui.lp(top = 16))
        offerKey = o.tid
        offerLayer = overlay.modal(box) {
            dismissed.add(o.tid)
            snap?.let { syncAsk(it) }
        }
    }

    // ───────────── Ajustes ─────────────

    private fun openSettings() {
        if (settingsLayer != null) return
        val saved = engine.snapshot.settings
        var draft = saved
        var saving = false
        val panel = ui.vbox().apply { setBackgroundColor(ui.p.surface) }
        val scroll = ScrollView(this).apply { isFillViewport = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val col = ui.vbox().apply { setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16)) }
        scroll.addView(col)
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val save = ui.button("Salvar", Ui.Kind.PRIMARY) {}
        val cancel = ui.button("Cancelar", Ui.Kind.GHOST) {}
        fun dirty() = draft != saved
        fun refresh() {
            val on = dirty() && !saving && draft.deviceName.isNotBlank()
            save.isEnabled = on
            save.alpha = if (on) 1f else 0.5f
            save.text = if (saving) "Salvando…" else "Salvar"
            cancel.isEnabled = !saving
        }
        fun closeIt() {
            settingsLayer?.let { overlay.close(it) }
            settingsLayer = null
            onFolderPicked = null
        }
        fun requestClose() {
            if (saving) return
            if (dirty()) confirmDiscard { closeIt() } else closeIt()
        }
        fun change(d: AppSettings) {
            draft = d
            refresh()
        }

        col.addView(ui.text("Ajustes", 20f, bold = true))

        col.addView(label("Nome deste aparelho"), ui.lp(top = 20))
        val name = ui.input("Nome", saved.deviceName)
        name.addTextChangedListener(watcher { change(draft.copy(deviceName = name.text.toString().trim())) })
        col.addView(name, ui.lp(top = 6))

        col.addView(label("Onde salvar o que chega"), ui.lp(top = 20))
        val folder = ui.text(folderLabel(recvTarget(saved.recvTree, true), saved.recvTreeName), 15f)
        col.addView(folder, ui.lp(top = 6))
        val folderRow = ui.hbox()
        val useDownloads = ui.button("Usar Downloads", Ui.Kind.GHOST) {}
        fun showFolder() {
            ui.setIfChanged(folder, folderLabel(recvTarget(draft.recvTree, true), draft.recvTreeName))
            useDownloads.visibility = if (draft.recvTree != null) View.VISIBLE else View.GONE
        }
        useDownloads.setOnClickListener {
            change(draft.copy(recvTree = null, recvTreeName = null))
            showFolder()
        }
        folderRow.addView(ui.button("Escolher pasta", Ui.Kind.SECONDARY) {
            onFolderPicked = { uri, n ->
                change(draft.copy(recvTree = uri, recvTreeName = n))
                showFolder()
            }
            try {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE_SETTINGS)
            } catch (_: Exception) {
                ui.toast("Não foi possível abrir o seletor de pastas")
            }
        })
        folderRow.addView(useDownloads, ui.lp(w = -2).apply { marginStart = ui.dp(8) })
        col.addView(folderRow, ui.lp(top = 8))
        showFolder()

        col.addView(label("Tema"), ui.lp(top = 20))
        val themeRow = ui.hbox()
        val themeButtons = ThemeMode.entries.map { m ->
            ui.button(m.label, Ui.Kind.SECONDARY) {}.also { b -> themeRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (m != ThemeMode.AUTO) marginStart = ui.dp(6) }) }
        }
        fun styleThemes() {
            ThemeMode.entries.forEachIndexed { i, m ->
                val sel = draft.theme == m
                themeButtons[i].background = ui.shape(if (sel) ui.p.accent else ui.p.surface2, 12f, if (sel) ui.p.accent else ui.p.border)
                themeButtons[i].setTextColor(if (sel) ui.p.onAccent else ui.p.text)
            }
        }
        ThemeMode.entries.forEachIndexed { i, m ->
            themeButtons[i].setOnClickListener {
                change(draft.copy(theme = m))
                styleThemes()
            }
        }
        styleThemes()
        col.addView(themeRow, ui.lp(top = 6))

        col.addView(ui.switchRow("Receber sem perguntar", "De aparelhos pareados. Cada aparelho pode ter a sua regra.", saved.autoAccept) { change(draft.copy(autoAccept = it)) }, ui.lp(top = 16))
        col.addView(ui.switchRow("Iniciar com o aparelho", "Deixa o RenanDrop pronto para receber depois de reiniciar.", saved.boot) { change(draft.copy(boot = it)) }, ui.lp(top = 4))
        col.addView(ui.switchRow("Usar Tailscale", tailscaleText(engine.snapshot.tailscale), saved.tailscale) { change(draft.copy(tailscale = it)) }, ui.lp(top = 4))

        col.addView(label("Conexões paralelas"), ui.lp(top = 20))
        val lanesRow = ui.hbox()
        val lanes = ui.text(saved.lanes.toString(), 18f, bold = true).apply { gravity = Gravity.CENTER; minWidth = ui.dp(40) }
        fun step(d: Int) {
            val n = (draft.lanes + d).coerceIn(1, 8)
            lanes.text = n.toString()
            change(draft.copy(lanes = n))
        }
        lanesRow.addView(ui.button("−", Ui.Kind.SECONDARY) { step(-1) })
        lanesRow.addView(lanes)
        lanesRow.addView(ui.button("+", Ui.Kind.SECONDARY) { step(1) })
        col.addView(lanesRow, ui.lp(top = 6))
        col.addView(ui.muted("Mais conexões aproveitam melhor redes rápidas. 4 funciona bem na maioria.", 12f), ui.lp(top = 6))

        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            col.addView(label("Bateria"), ui.lp(top = 20))
            col.addView(ui.muted("Sem restrição, o Android não suspende o RenanDrop em segundo plano.", 12f), ui.lp(top = 4))
            col.addView(
                ui.button("Permitir sem restrição", Ui.Kind.SECONDARY) {
                    try {
                        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                    } catch (_: Exception) {
                        try {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } catch (_: Exception) {
                            ui.toast("Não foi possível abrir as configurações de bateria")
                        }
                    }
                },
                ui.lp(top = 8),
            )
        }

        val footer = ui.hbox().apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12))
            background = ui.shape(ui.p.surface, 0f)
        }
        cancel.setOnClickListener { requestClose() }
        footer.addView(cancel)
        footer.addView(save, ui.lp(w = -2).apply { marginStart = ui.dp(8) })
        panel.addView(footer)

        fun doSave() {
            saving = true
            refresh()
            engine.applySettings(draft) {
                saving = false
                closeIt()
                ui.toast("Ajustes salvos")
            }
        }
        save.setOnClickListener {
            val active = engine.snapshot.active
            if (saved.needsNodeRestart(draft) && active > 0) {
                confirm(
                    "Reiniciar o serviço?",
                    "Há $active transferência(s) em andamento. Elas serão interrompidas; os envios podem ser retomados depois.",
                    "Salvar e reiniciar", false,
                ) { doSave() }
            } else doSave()
        }
        refresh()
        settingsLayer = overlay.drawer(panel) { requestClose() }
    }

    private fun label(s: String): TextView = ui.text(s, 13f, ui.p.muted, true)

    private fun watcher(f: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = f()
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_SEND = "send"
        private const val REQ_FILES = 11
        private const val REQ_TREE_SEND = 12
        private const val REQ_TREE_SETTINGS = 13
    }
}
