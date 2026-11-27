package dev.renandrop.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import dev.renandrop.protocol.Direction
import dev.renandrop.protocol.Node
import dev.renandrop.protocol.NodeConfig
import dev.renandrop.protocol.NodeEvent
import dev.renandrop.protocol.Route
import dev.renandrop.protocol.TransferInfo
import dev.renandrop.protocol.TransferState
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Aparelho na tela: pareado, ou encontrado e ainda não pareado. */
data class PeerRow(
    val id: String,
    val name: String,
    val paired: Boolean,
    val online: Boolean,
    val route: Route?,
    /** `true` aceita sem perguntar, `false` sempre pergunta, `null` segue o padrão geral. */
    val autoAccept: Boolean?,
    val manual: Boolean,
    val address: String?,
)

data class PairPromptView(val reqId: String, val peerName: String, val code: String, val outgoing: Boolean)

data class OfferView(val tid: String, val peerId: String, val peerName: String, val files: Int, val total: Long, val summary: String)

/** Foto do estado para a interface; sempre publicada na thread principal. */
private val MOVING = setOf(TransferState.CONNECTING, TransferState.WAITING, TransferState.SENDING, TransferState.RECEIVING)

data class Snapshot(
    val running: Boolean,
    val starting: Boolean,
    val error: String?,
    val settings: AppSettings,
    val folder: String,
    val peers: List<PeerRow>,
    val nearby: List<PeerRow>,
    val transfers: List<TransferInfo>,
    val history: List<HistoryEntry>,
    val pair: PairPromptView?,
    val offers: List<OfferView>,
    val tailscale: TailscaleStatus,
) {
    val anyOnline: Boolean get() = peers.any { it.online }
    val active: Int get() = transfers.count { !it.finished }

    /** Transferências que de fato usam rede agora (fila e espera entre tentativas não seguram CPU nem Wi-Fi). */
    val moving: Int get() = transfers.count { it.state in MOVING }
}

/** Resultado de "adicionar por nome ou IP". */
sealed interface RemoteResult {
    data class Found(val name: String, val route: Route, val paired: Boolean) : RemoteResult
    data class Failed(val message: String) : RemoteResult
}

/**
 * Cérebro do app: mantém o nó do protocolo, a descoberta, os aparelhos, a fila e o histórico, e publica
 * [Snapshot]s para a interface. O trabalho pesado roda numa thread própria; só a publicação vai à principal.
 */
class Engine(private val app: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { Thread(it, "rd-engine").apply { isDaemon = true } }
    private val probes = Executors.newFixedThreadPool(4) { Thread(it, "rd-probe").apply { isDaemon = true } }
    val settingsStore = SettingsStore(app)
    val notifier = Notifier(app)
    private val history = HistoryStore(File(app.filesDir, "history.json"))
    private val exporter = Exporter(app)
    private val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val lock = Any()
    private var node: Node? = null
    private var discovery: Discovery? = null
    private var settings = settingsStore.load()
    private var manual = settingsStore.loadManual().toMutableList()
    private val found = HashMap<String, Found>()
    private val verified = HashMap<String, Long>()
    private val reach = HashMap<String, InetSocketAddress>()
    private val addrBook = HashMap<String, List<InetSocketAddress>>()
    private var pair: PairPromptView? = null
    private val offers = LinkedHashMap<String, OfferView>()
    private var error: String? = null
    private var starting = false
    private var tailscale = TailscaleStatus.OFF
    private val recorded = HashSet<String>()
    private val pending = AtomicBoolean(false)
    private val listeners = ArrayList<(Snapshot) -> Unit>()
    private var verifierOn = false
    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    var snapshot: Snapshot = buildSnapshot()
        private set

    /** Avisos curtos (pareou, falhou...). Quem estiver na tela define; fora da thread principal não é chamado. */
    @Volatile
    var onNotice: ((String) -> Unit)? = null

    // ───────────── Ciclo de vida ─────────────

    fun start() {
        io.execute { startNode() }
    }

    fun stop() {
        io.execute { stopNode() }
    }

    private fun startNode() {
        if (synchronized(lock) { node != null }) return
        synchronized(lock) {
            starting = true
            error = null
        }
        publishSoon()
        try {
            val dir = File(app.filesDir, "node").apply { mkdirs() }
            val cfg = NodeConfig(settings.deviceName, File(app.filesDir, "recv"), lanes = settings.lanes, autoAccept = settings.autoAccept)
            val n = Node.start(KeystoreStore(dir), cfg) { onEvent(it) }
            synchronized(lock) { node = n }
            val d = Discovery(app, n, ::onFound, ::onLost, ::onNetwork)
            discovery = d
            d.start()
            refreshTailscale()
            startVerifier()
        } catch (e: Exception) {
            synchronized(lock) { error = e.message ?: e.javaClass.simpleName }
        }
        synchronized(lock) { starting = false }
        publishSoon()
    }

    private fun stopNode() {
        val n: Node?
        synchronized(lock) {
            n = node
            node = null
            verifierOn = false
            found.clear()
            verified.clear()
            reach.clear()
            addrBook.clear()
            offers.clear()
            pair = null
        }
        discovery?.stop()
        discovery = null
        try {
            n?.close()
        } catch (_: Exception) {
        }
        notifier.cancelPair()
        publishSoon()
    }

    // ───────────── Eventos do nó ─────────────

    private fun onEvent(e: NodeEvent) {
        when (e) {
            is NodeEvent.PairPrompt -> {
                val v = PairPromptView(e.reqId, e.peerName, e.code, e.outgoing)
                synchronized(lock) { pair = v }
                notifier.pair(v)
            }
            is NodeEvent.PairDone -> {
                synchronized(lock) { pair = null }
                notifier.cancelPair()
                if (e.ok) {
                    notice("Pareado com ${e.peerName}")
                    io.execute { verifyAll() }
                } else {
                    notice("Pareamento não concluído: ${e.error ?: "recusado"}")
                }
            }
            is NodeEvent.Offer -> {
                val v = OfferView(e.tid, e.peerId, e.peerName, e.files, e.total, e.summary)
                synchronized(lock) { offers[e.tid] = v }
                notifier.offer(v)
            }
            is NodeEvent.Transfer -> onTransfer(e.info)
            NodeEvent.PeersChanged -> {}
        }
        publishSoon()
    }

    private fun onTransfer(t: TransferInfo) {
        if (t.direction == Direction.RECV && t.state != TransferState.WAITING) {
            val gone = synchronized(lock) { offers.remove(t.id) != null }
            if (gone) notifier.cancelOffer(t.id)
        }
        if (t.finished) io.execute { finish(t) }
    }

    /** Fim de uma transferência: leva o recebido ao destino final, grava o histórico e avisa (uma vez só). */
    private fun finish(t: TransferInfo) {
        if (!synchronized(lock) { recorded.add("${t.id}-${t.startedMs}:${t.state}") }) return
        var saved: Saved? = null
        var err = t.error
        var state = t.state
        if (needsExport(t)) {
            try {
                saved = exportAll(t.paths.map { File(it) })
            } catch (e: Exception) {
                state = TransferState.FAILED
                err = "Recebido, mas não foi possível salvar: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        val entry = HistoryEntry(
            "${t.id}-${t.startedMs}", System.currentTimeMillis(), t.direction, t.peerName, t.summary, t.files, t.total, state, err, saved,
        )
        history.add(entry)
        notifier.finished(entry)
        publishSoon()
    }

    private fun exportAll(files: List<File>): Saved {
        val tree = settings.recvTree
        val granted = tree != null && app.contentResolver.persistedUriPermissions.any { it.uri.toString() == tree && it.isWritePermission }
        val target = recvTarget(tree, granted)
        return try {
            exporter.export(files.filter { it.exists() }, target)
        } catch (e: Exception) {
            if (target !is RecvTarget.Tree) throw e
            val s = exporter.export(files.filter { it.exists() }, RecvTarget.Downloads)
            s.copy(where = "${s.where} (a pasta escolhida não está disponível)")
        }
    }

    private fun notice(text: String) {
        val l = onNotice ?: return
        main.post { l(text) }
    }

    // ───────────── Descoberta e verificação ─────────────

    private fun onFound(f: Found) {
        synchronized(lock) { found[f.id] = f }
        io.execute { verify(f.id) }
    }

    private fun onLost(id: String) {
        synchronized(lock) {
            found.remove(id)
            if (manual.none { it.id == id }) verified.remove(id)
        }
        publishSoon()
    }

    private fun onNetwork() {
        io.execute {
            refreshTailscale()
            verifyAll()
        }
    }

    private fun startVerifier() {
        synchronized(lock) {
            if (verifierOn) return
            verifierOn = true
        }
        Thread({
            while (synchronized(lock) { verifierOn }) {
                try {
                    Thread.sleep(VERIFY_EVERY)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (synchronized(lock) { verifierOn }) io.execute { verifyAll() }
            }
        }, "rd-verify").apply { isDaemon = true }.start()
    }

    private fun candidates(id: String): List<InetSocketAddress> {
        val list = ArrayList<InetSocketAddress>()
        synchronized(lock) { found[id]?.addrs?.let { list.addAll(it) } }
        val m = synchronized(lock) { manual.firstOrNull { it.id == id } }
        if (m != null) {
            try {
                InetAddress.getAllByName(m.host).forEach { list.add(InetSocketAddress(it, m.port)) }
            } catch (_: Exception) {
            }
        }
        synchronized(lock) { reach[id]?.let { list.add(it) } }
        return orderAddresses(list, settings.tailscale, synchronized(lock) { reach[id] }).take(4)
    }

    private fun verify(id: String) {
        val n = synchronized(lock) { node } ?: return
        val ordered = candidates(id)
        if (ordered.isEmpty()) return
        synchronized(lock) { addrBook[id] = ordered }
        n.setAddresses(id, ordered)
        for (a in ordered) {
            val r = n.probe(a, 1500)
            if (r != null && r.id == id) {
                synchronized(lock) {
                    verified[id] = System.currentTimeMillis()
                    reach[id] = a
                }
                val rest = orderAddresses(ordered, settings.tailscale, a)
                synchronized(lock) { addrBook[id] = rest }
                n.setAddresses(id, rest)
                break
            }
        }
        publishSoon()
    }

    private fun verifyAll() {
        val n = synchronized(lock) { node } ?: return
        val ids = LinkedHashSet<String>()
        synchronized(lock) {
            ids.addAll(found.keys)
            ids.addAll(manual.map { it.id })
        }
        ids.addAll(n.peers().map { it.id })
        // Sem esperar: a fila `io` fica livre para aceitar/recusar/cancelar; cada verify já publica o próprio resultado.
        ids.forEach { id ->
            probes.execute {
                try {
                    verify(id)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun refreshTailscale() {
        val installed = try {
            app.packageManager.getPackageInfo("com.tailscale.ipn", 0)
            true
        } catch (_: Exception) {
            false
        }
        val has = try {
            @Suppress("DEPRECATION")
            cm.allNetworks.any { net -> cm.getLinkProperties(net)?.linkAddresses?.any { Route.of(it.address) == Route.TAILNET } == true }
        } catch (_: Exception) {
            false
        }
        tailscale = tailscaleStatus(settings.tailscale, has, installed)
    }

    fun rescan() {
        discovery?.rescan()
        io.execute {
            refreshTailscale()
            verifyAll()
        }
    }

    // ───────────── Ações da interface ─────────────

    fun pair(id: String) = io.execute {
        val n = synchronized(lock) { node }
        if (n == null) notice("O serviço ainda não está pronto") else {
            verify(id)
            n.pair(id)
        }
    }

    fun confirmPair(reqId: String, ok: Boolean) {
        synchronized(lock) { pair = null }
        notifier.cancelPair()
        io.execute { synchronized(lock) { node }?.confirmPair(reqId, ok) }
        publishSoon()
    }

    fun respondOffer(tid: String, ok: Boolean) {
        synchronized(lock) { offers.remove(tid) }
        notifier.cancelOffer(tid)
        io.execute { synchronized(lock) { node }?.respondOffer(tid, ok) }
        publishSoon()
    }

    fun cancel(id: String) = io.execute { synchronized(lock) { node }?.cancel(id) }

    fun retry(id: String) = io.execute {
        try {
            synchronized(lock) { node }?.retry(id)
        } catch (e: Exception) {
            notice(e.message ?: "Não foi possível tentar de novo")
        }
        publishSoon()
    }

    fun clearFinished() = io.execute {
        synchronized(lock) { node }?.clearFinished()
        publishSoon()
    }

    fun clearHistory() = io.execute {
        history.clear()
        publishSoon()
    }

    fun unpair(id: String) = io.execute {
        synchronized(lock) { node }?.unpair(id)
        publishSoon()
    }

    fun setAutoAccept(id: String, value: Boolean?) = io.execute {
        synchronized(lock) { node }?.setAutoAccept(id, value)
        publishSoon()
    }

    fun rename(id: String, name: String) = io.execute {
        synchronized(lock) { node }?.rename(id, name)
        publishSoon()
    }

    fun forgetRemote(id: String) = io.execute {
        synchronized(lock) {
            manual.removeAll { it.id == id }
            settingsStore.saveManual(manual)
            if (found[id] == null) verified.remove(id)
        }
        publishSoon()
    }

    /** Procura um aparelho por nome ou IP (Tailscale, MagicDNS). O resultado volta na thread principal. */
    fun addRemote(text: String, cb: (RemoteResult) -> Unit) = io.execute {
        fun done(r: RemoteResult) {
            main.post { cb(r) }
        }
        val t = parseTarget(text) ?: return@execute done(RemoteResult.Failed("Endereço inválido. Use um IP (100.101.102.103), um nome (meu-pc.tailnet.ts.net) ou nome:porta."))
        val n = synchronized(lock) { node } ?: return@execute done(RemoteResult.Failed("O serviço ainda não está pronto. Tente de novo em instantes."))
        val ips = try {
            InetAddress.getAllByName(t.host).toList()
        } catch (_: Exception) {
            return@execute done(RemoteResult.Failed("Não encontrei \"${t.host}\". Confira o nome ou o IP."))
        }
        val cands = ips.take(3).flatMap { ip -> probePorts(t.port).map { InetSocketAddress(ip, it) } }
        val hit = cands.map { a -> probes.submit<dev.renandrop.protocol.ProbeResult?> { n.probe(a, 2500) } }
            .mapNotNull { try { it.get() } catch (_: Exception) { null } }.firstOrNull()
            ?: return@execute done(RemoteResult.Failed("Nenhum RenanDrop respondeu em ${t.host}. Confira se o app está aberto no outro aparelho e, fora de casa, se o Tailscale está ligado nos dois."))
        if (hit.id == n.id) return@execute done(RemoteResult.Failed("Este é o próprio aparelho."))
        val route = Route.of(hit.address.address)
        if (route == Route.TAILNET && !settings.tailscale) {
            return@execute done(RemoteResult.Failed("O endereço é do Tailscale, mas o uso do Tailscale está desligado nas configurações."))
        }
        synchronized(lock) {
            manual.removeAll { it.id == hit.id }
            manual.add(ManualPeer(hit.id, hit.name, t.host, hit.address.port))
            settingsStore.saveManual(manual)
            verified[hit.id] = System.currentTimeMillis()
            reach[hit.id] = hit.address
            addrBook[hit.id] = listOf(hit.address)
        }
        n.setAddresses(hit.id, listOf(hit.address))
        val paired = n.peers().any { it.id == hit.id }
        publishSoon()
        done(RemoteResult.Found(hit.name, route, paired))
    }

    /** Envia para um ou mais aparelhos pareados. `cb(null)` quando entrou na fila; senão a mensagem do erro. */
    fun send(staged: Staged, peerIds: List<String>, cb: (String?) -> Unit) = probes.execute {
        fun done(msg: String?) {
            main.post { cb(msg) }
        }
        // Ler o provedor de conteúdo (pasta grande, cópia de tamanho desconhecido) não pode travar o motor.
        val items = try {
            staged.build(app)
        } catch (e: Exception) {
            return@execute done(e.message ?: "Não foi possível ler os arquivos")
        }
        io.execute { enqueueSend(items, peerIds, ::done) }
    }

    private fun enqueueSend(items: List<dev.renandrop.protocol.SendItem>, peerIds: List<String>, done: (String?) -> Unit) {
        try {
            if (synchronized(lock) { node } == null) startNode()
            val n = synchronized(lock) { node } ?: return done(error ?: "O serviço não está pronto")
            for (id in peerIds) n.send(id, { synchronized(lock) { addrBook[id] }.orEmpty() }, items)
            done(null)
        } catch (e: Exception) {
            done(e.message ?: "Não foi possível enviar")
        }
        publishSoon()
    }

    fun applySettings(s: AppSettings, done: () -> Unit) = io.execute {
        val old = settings
        settings = s
        settingsStore.save(s)
        if (old.needsNodeRestart(s) && synchronized(lock) { node } != null) {
            stopNode()
            startNode()
        } else if (old.tailscale != s.tailscale) {
            refreshTailscale()
            verifyAll()
        }
        publishSoon()
        main.post(done)
    }

    // ───────────── Publicação ─────────────

    fun addListener(l: (Snapshot) -> Unit) {
        listeners.add(l)
        l(snapshot)
    }

    fun removeListener(l: (Snapshot) -> Unit) {
        listeners.remove(l)
    }

    fun publishSoon() {
        if (pending.compareAndSet(false, true)) {
            main.postDelayed({
                pending.set(false)
                val s = buildSnapshot()
                snapshot = s
                for (l in listeners.toList()) l(s)
                notifier.updateService(s)
                updateLocks(s.moving > 0)
            }, 150)
        }
    }

    private fun buildSnapshot(): Snapshot {
        val now = System.currentTimeMillis()
        val n = synchronized(lock) { node }
        val paired = n?.peers().orEmpty()
        val pairedIds = paired.map { it.id }.toSet()
        synchronized(lock) {
            fun online(id: String) = (verified[id] ?: 0) > now - ONLINE_MS
            fun row(id: String, name: String, p: Boolean, auto: Boolean?) = PeerRow(
                id, name, p, online(id), if (online(id)) reach[id]?.address?.let { Route.of(it) } else null, auto,
                manual.any { it.id == id }, reach[id]?.let { "${it.hostString}:${it.port}" },
            )
            val peers = paired.map { row(it.id, it.name, true, it.autoAccept) }
                .sortedWith(compareByDescending<PeerRow> { it.online }.thenBy { it.name.lowercase() })
            val nearby = (found.values.map { it.id to it.name } + manual.map { it.id to it.name })
                .distinctBy { it.first }
                .filter { it.first !in pairedIds && online(it.first) }
                .map { row(it.first, it.second, false, null) }
                .sortedBy { it.name.lowercase() }
            val transfers = n?.transfers().orEmpty().sortedByDescending { it.startedMs }
            val rt = recvTarget(settings.recvTree, true)
            return Snapshot(
                running = n != null, starting = starting, error = error, settings = settings,
                folder = folderLabel(rt, settings.recvTreeName), peers = peers, nearby = nearby, transfers = transfers,
                history = history.all(), pair = pair, offers = offers.values.toList(), tailscale = tailscale,
            )
        }
    }

    private fun updateLocks(active: Boolean) {
        if (active) {
            if (cpuLock == null) {
                val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "renandrop:transfer").apply { setReferenceCounted(false) }
            }
            if (wifiLock == null) {
                val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "renandrop:transfer").apply { setReferenceCounted(false) }
            }
            // Com prazo, para nunca ficar preso se algo falhar; renovado a cada publicação enquanto houver transferência.
            cpuLock?.acquire(30 * 60 * 1000L)
            if (wifiLock?.isHeld == false) wifiLock?.acquire()
        } else {
            if (cpuLock?.isHeld == true) cpuLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
    }

    private companion object {
        const val VERIFY_EVERY = 30_000L
        const val ONLINE_MS = 75_000L
    }
}
