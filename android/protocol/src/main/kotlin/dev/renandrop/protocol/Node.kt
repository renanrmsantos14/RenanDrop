package dev.renandrop.protocol

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Configuração de um nó. */
class NodeConfig(
    /** Nome mostrado ao outro aparelho. */
    val deviceName: String,
    /** Pasta onde os arquivos recebidos são gravados. */
    val recvDir: File,
    /** Porta do serviço; se ocupada, tenta as 4 seguintes. `0` escolhe qualquer porta livre. */
    val port: Int = DEFAULT_PORT,
    /** Conexões paralelas por envio (1 a 8). */
    val lanes: Int = 4,
    /** Aceita ofertas de aparelhos pareados sem perguntar (cada aparelho pode sobrescrever). */
    val autoAccept: Boolean = false,
)

enum class Direction { SEND, RECV }

enum class TransferState {
    QUEUED, CONNECTING, WAITING, SENDING, RECEIVING, RETRYING, DONE, FAILED, CANCELLED, DECLINED;

    val isFinal: Boolean get() = this == DONE || this == FAILED || this == CANCELLED || this == DECLINED
}

/** Foto de uma transferência para a interface. */
data class TransferInfo(
    val id: String,
    val direction: Direction,
    val peerId: String,
    val peerName: String,
    val state: TransferState,
    /** Nome do primeiro item e quantos mais ("Fotos e mais 2"). */
    val summary: String,
    val files: Int,
    val bytes: Long,
    val total: Long,
    /** Bytes por segundo (média suavizada). */
    val speed: Double,
    val error: String?,
    /** Recebimento: caminho de topo do primeiro item gravado, para "mostrar na pasta". */
    val path: String?,
    val startedMs: Long,
    val route: Route?,
    /** Recebimento: caminho de topo de cada item gravado (arquivo ou pasta), para quem move os arquivos ao destino final. */
    val paths: List<String> = emptyList(),
) {
    val finished: Boolean get() = state.isFinal
}

/** Resposta de um aparelho RenanDrop a uma sondagem por endereço. */
data class ProbeResult(val id: String, val name: String, val address: InetSocketAddress)

sealed interface NodeEvent {
    /** Mostre o código nas duas telas; responda com [Node.confirmPair]. */
    data class PairPrompt(val reqId: String, val peerName: String, val code: String, val outgoing: Boolean) : NodeEvent

    data class PairDone(val ok: Boolean, val peerName: String, val error: String?) : NodeEvent

    /** Oferta de arquivos de um aparelho pareado; responda com [Node.respondOffer] (120 s). */
    data class Offer(val tid: String, val peerId: String, val peerName: String, val files: Int, val total: Long, val summary: String) : NodeEvent

    /** Mudança de estado ou de progresso de uma transferência. */
    data class Transfer(val info: TransferInfo) : NodeEvent

    /** A lista de aparelhos pareados mudou. */
    data object PeersChanged : NodeEvent
}

private class Job(
    val id: String,
    val direction: Direction,
    val peerId: String,
    val peerName: String,
    val total: Long,
    val summary: String,
    val files: Int,
    state: TransferState,
    val items: List<SendItem> = emptyList(),
    val addrs: () -> List<InetSocketAddress> = { emptyList() },
) {
    @Volatile
    var prog = Progress(total)

    @Volatile
    var cancelRequested = false
    private val lock = Any()
    var state = state
        private set
    var error: String? = null
        private set
    var path: String? = null
    var paths: List<String> = emptyList()
    var route: Route? = null
    var speed = 0.0
    var lastBytes = 0L
    var lastTick = System.nanoTime()
    val startedMs = System.currentTimeMillis()

    fun set(s: TransferState, err: String?) = synchronized(lock) {
        state = s
        error = err
        if (s.isFinal) speed = 0.0
    }

    fun info(): TransferInfo = synchronized(lock) {
        val p = prog
        TransferInfo(
            id, direction, peerId, peerName, state, summary, files, minOf(p.bytes.get(), total), total, speed, error, path,
            startedMs, route ?: p.route?.address?.let { Route.of(it) }, paths,
        )
    }

    fun tickSpeed() = synchronized(lock) {
        val b = prog.bytes.get()
        val now = System.nanoTime()
        val dt = maxOf((now - lastTick) / 1e9, 0.001)
        val inst = maxOf(b - lastBytes, 0L) / dt
        speed = if (speed == 0.0) inst else speed * 0.6 + inst * 0.4
        lastBytes = b
        lastTick = now
    }

    val live: Boolean get() = synchronized(lock) { state == TransferState.SENDING || state == TransferState.RECEIVING }
}

private class PeerQueue {
    val q = ArrayDeque<Job>()
    var running = false
}

private val BACKOFF = longArrayOf(1, 2, 3, 5, 8, 13, 20)

private fun summarize(items: List<Item>): Triple<Int, Long, String> {
    val tops = LinkedHashSet<String>()
    var files = 0
    var total = 0L
    for (it in items) {
        tops.add(it.path.substringBefore('/'))
        if (!it.dir) files++
        total += it.size
    }
    val first = tops.firstOrNull() ?: ""
    val text = if (tops.size <= 1) first else "$first e mais ${tops.size - 1}"
    return Triple(files, total, text)
}

/**
 * Nó RenanDrop: escuta conexões, pareia e transfere. Descoberta de aparelhos (mDNS etc.) fica com o
 * chamador, que informa endereços por [setAddresses] ou direto em [pair] e [send].
 *
 * Eventos: [events] (Flow) e, opcionalmente, o `listener` de [start] (chamado nas threads do nó:
 * não bloqueie nele). Todos os métodos podem ser chamados de qualquer thread.
 */
class Node private constructor(
    private val store: Store,
    private val config: NodeConfig,
    private val identity: Identity,
    private val server: ServerSocket,
    private val listener: ((NodeEvent) -> Unit)?,
) : Closeable {
    private val flow = MutableSharedFlow<NodeEvent>(extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Eventos do nó. Transferências em andamento emitem [NodeEvent.Transfer] a cada ~250 ms. */
    val events: SharedFlow<NodeEvent> = flow.asSharedFlow()

    /** Id deste aparelho (hex, 32 caracteres). */
    val id: String = identity.idHex

    /** Porta em que o nó escuta. */
    val port: Int = server.localPort

    /** Nome do aparelho, como o outro lado o vê. */
    val deviceName: String get() = config.deviceName

    private val stop = AtomicBoolean(false)
    private val seq = AtomicLong(1)
    private val conns = AtomicInteger()
    private val liveSockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private val peersLock = Any()
    private var peerList: List<PairedPeer> = store.loadPeers()
    private val book = ConcurrentHashMap<String, List<InetSocketAddress>>()
    private val jobs = ArrayList<Job>()
    private val queues = HashMap<String, PeerQueue>()
    private val pairs = ConcurrentHashMap<String, ArrayBlockingQueue<Boolean>>()
    private val offers = ConcurrentHashMap<String, ArrayBlockingQueue<Boolean>>()
    private val recvReg = ConcurrentHashMap<String, RecvTransfer>()
    private val pairBusy = AtomicBoolean(false)

    private fun emit(e: NodeEvent) {
        flow.tryEmit(e)
        try {
            listener?.invoke(e)
        } catch (_: Exception) {
        }
    }

    private fun spawn(name: String, block: () -> Unit): Thread = Thread(block, name).also {
        it.isDaemon = true
        it.start()
    }

    // ───────────── Aparelhos ─────────────

    /** Aparelhos pareados. */
    fun peers(): List<PairedPeer> = synchronized(peersLock) { peerList }

    private fun peer(id: String): PairedPeer? = synchronized(peersLock) { peerList.firstOrNull { it.id == id } }

    private fun mutatePeers(f: (List<PairedPeer>) -> List<PairedPeer>) {
        synchronized(peersLock) {
            val next = f(peerList)
            store.savePeers(next)
            peerList = next
        }
        emit(NodeEvent.PeersChanged)
    }

    /** Remove o pareamento (o outro aparelho também precisa remover o seu). */
    fun unpair(peerId: String) = mutatePeers { l -> l.filter { it.id != peerId } }

    /** Troca o nome mostrado de um aparelho pareado (só neste aparelho). */
    fun rename(peerId: String, name: String) = mutatePeers { l ->
        l.map { if (it.id == peerId) PairedPeer(it.id, name.trim().take(64).ifEmpty { it.name }, it.publicKey, it.pairedAt, it.autoAccept) else it }
    }

    /** `true` aceita sem perguntar, `false` sempre pergunta, `null` segue [NodeConfig.autoAccept]. */
    fun setAutoAccept(peerId: String, value: Boolean?) = mutatePeers { l ->
        l.map { if (it.id == peerId) PairedPeer(it.id, it.name, it.publicKey, it.pairedAt, value) else it }
    }

    /** Informa onde um aparelho (pareado ou não) pode ser alcançado, em ordem de preferência. */
    fun setAddresses(peerId: String, addrs: List<InetSocketAddress>) {
        if (addrs.isEmpty()) book.remove(peerId) else book[peerId] = addrs.toList()
    }

    /** Pergunta "quem é você?" a um endereço (não precisa de pareamento). `null` se não responder. */
    fun probe(addr: InetSocketAddress, timeoutMs: Int = 1500): ProbeResult? {
        val s = Socket()
        return try {
            s.connect(addr, timeoutMs)
            s.soTimeout = timeoutMs
            s.getOutputStream().write(preamble(KIND_HELLO))
            s.getOutputStream().flush()
            val m = readPlain(s.getInputStream(), 512)
            val id = m.str("id")
            if (id.length != 32 || !id.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            ProbeResult(id.lowercase(), (m["name"] as? String ?: "Aparelho").take(64), addr)
        } catch (_: IOException) {
            null
        } finally {
            try {
                s.close()
            } catch (_: IOException) {
            }
        }
    }

    // ───────────── Pareamento ─────────────

    private fun decide(p: PairPrompt): Boolean {
        val reqId = "p${seq.getAndIncrement()}"
        val q = ArrayBlockingQueue<Boolean>(1)
        pairs[reqId] = q
        emit(NodeEvent.PairPrompt(reqId, p.peerName, p.code, p.outgoing))
        val r = q.poll(120, TimeUnit.SECONDS) ?: false
        pairs.remove(reqId)
        return r
    }

    /** Resposta da pessoa ao código mostrado em [NodeEvent.PairPrompt]. */
    fun confirmPair(reqId: String, ok: Boolean) {
        pairs[reqId]?.offer(ok)
    }

    /** Pareia com um aparelho cujos endereços já são conhecidos ([setAddresses]). O resultado vem em eventos. */
    fun pair(peerId: String) {
        val a = book[peerId]
        if (a.isNullOrEmpty()) {
            emit(NodeEvent.PairDone(false, "", "aparelho não encontrado na rede"))
            return
        }
        pair(a)
    }

    /** Pareia com o aparelho que atender num destes endereços. O resultado vem em eventos. */
    fun pair(addrs: List<InetSocketAddress>) {
        spawn("rd-pair-out") {
            var used: InetSocketAddress? = null
            val res = try {
                val s = connectAny(addrs, Progress(0))
                used = s.remoteSocketAddress as? InetSocketAddress
                try {
                    pairInitiate(s, identity, config.deviceName) { decide(it) }
                } finally {
                    try {
                        s.close()
                    } catch (_: IOException) {
                    }
                }
            } catch (e: Exception) {
                finishPair(null, e.message ?: e.javaClass.simpleName)
                return@spawn
            }
            if (res != null && used != null) book[res.id.hex()] = listOf(used)
            finishPair(res, null)
        }
    }

    private fun finishPair(p: Paired?, err: String?) {
        if (p == null) {
            emit(NodeEvent.PairDone(false, "", err ?: "Pareamento recusado"))
            return
        }
        val id = p.id.hex()
        mutatePeers { l ->
            val old = l.firstOrNull { it.id == id }
            l.filter { it.id != id } + PairedPeer(id, p.name, p.publicKey, System.currentTimeMillis(), old?.autoAccept)
        }
        emit(NodeEvent.PairDone(true, p.name, null))
    }

    // ───────────── Envio ─────────────

    /** Envia arquivos e pastas locais para um aparelho pareado, usando os endereços de [setAddresses]. */
    fun sendFiles(peerId: String, files: List<File>): String = send(peerId, scanFiles(files))

    /** Envia itens (arquivos ou fontes de bytes) para um aparelho pareado. Devolve o id da transferência. */
    fun send(peerId: String, items: List<SendItem>): String = send(peerId, { book[peerId] ?: emptyList() }, items)

    /**
     * Como [send], mas com os endereços resolvidos de novo a cada tentativa (útil quando o aparelho
     * muda de IP durante as quedas de rede).
     */
    fun send(peerId: String, addrs: () -> List<InetSocketAddress>, items: List<SendItem>): String {
        require(items.isNotEmpty()) { "nenhum arquivo escolhido" }
        val peer = peer(peerId) ?: throw IllegalArgumentException("aparelho não pareado")
        val (files, total, text) = summarize(items.map { Item(0, it.path, it.size, it.mtime, it.isDir) })
        val job = Job("s${seq.getAndIncrement()}", Direction.SEND, peerId, peer.name, total, text, files, TransferState.QUEUED, items, addrs)
        synchronized(jobs) { jobs.add(job) }
        emit(NodeEvent.Transfer(job.info()))
        enqueue(job)
        return job.id
    }

    private fun enqueue(job: Job) {
        val start = synchronized(queues) {
            val q = queues.getOrPut(job.peerId) { PeerQueue() }
            q.q.addLast(job)
            val s = !q.running
            q.running = true
            s
        }
        if (start) {
            spawn("rd-send-queue") {
                while (true) {
                    val next = synchronized(queues) {
                        val q = queues[job.peerId]!!
                        val n = q.q.removeFirstOrNull()
                        if (n == null) q.running = false
                        n
                    } ?: return@spawn
                    runSend(next)
                }
            }
        }
    }

    private fun setState(job: Job, s: TransferState, err: String? = null) {
        job.set(s, err)
        emit(NodeEvent.Transfer(job.info()))
    }

    private fun runSend(job: Job) {
        if (job.cancelRequested) return finishJob(job, TransferState.CANCELLED, null)
        val peer = peer(job.peerId) ?: return finishJob(job, TransferState.FAILED, "aparelho não está mais pareado")
        val list = job.items.mapIndexed { i, it -> Item(i, it.path, it.size, it.mtime, it.isDir) }
        val tid = transferId(identity.id, idOf(peer.publicKey), list)
        var attempt = 0
        var best = 0L
        while (true) {
            val prog = Progress(job.total)
            if (job.cancelRequested) prog.cancel()
            job.prog = prog
            val addrs = job.addrs()
            val params = SendParams(identity, peer.publicKey, addrs, config.deviceName, config.lanes.coerceIn(1, 8), prog) { stg ->
                setState(
                    job,
                    when (stg) {
                        Stage.CONNECTING -> TransferState.CONNECTING
                        Stage.WAITING_ACCEPT -> TransferState.WAITING
                        Stage.SENDING -> TransferState.SENDING
                    },
                )
            }
            val failure: SendException? = try {
                if (addrs.isEmpty()) throw SendException.io("aparelho fora da rede")
                sendOnce(params, job.items, tid)
                null
            } catch (e: SendException) {
                e
            } catch (e: Exception) {
                SendException.io(e.message ?: e.javaClass.simpleName)
            }
            when {
                failure == null -> return finishJob(job, TransferState.DONE, null)
                failure.kind == SendException.Kind.CANCELLED -> return finishJob(job, TransferState.CANCELLED, null)
                failure.kind == SendException.Kind.DECLINED -> return finishJob(job, TransferState.DECLINED, failure.message?.removePrefix("recusado: "))
                !failure.retriable -> return finishJob(job, TransferState.FAILED, failure.message)
            }
            // Quem avançou desde a última queda ganha novas tentativas (transferência grande em rede ruim).
            val now = prog.bytes.get()
            if (now > best) {
                best = now
                attempt = 0
            }
            if (attempt >= BACKOFF.size) return finishJob(job, TransferState.FAILED, failure!!.message)
            setState(job, TransferState.RETRYING, failure!!.message)
            val wait = BACKOFF[attempt++]
            val t0 = System.nanoTime()
            while (System.nanoTime() - t0 < wait * 1_000_000_000L && !job.cancelRequested && !stop.get()) Thread.sleep(100)
            if (job.cancelRequested || stop.get()) return finishJob(job, TransferState.CANCELLED, null)
        }
    }

    private fun finishJob(job: Job, s: TransferState, err: String?) {
        job.set(s, err)
        emit(NodeEvent.Transfer(job.info()))
    }

    /** Cancela uma transferência (envio ou recebimento). */
    fun cancel(transferId: String) {
        val j = synchronized(jobs) { jobs.firstOrNull { it.id == transferId } } ?: return
        j.cancelRequested = true
        j.prog.cancel()
        val queued = synchronized(queues) { queues[j.peerId]?.q?.remove(j) == true }
        if (queued) finishJob(j, TransferState.CANCELLED, null)
    }

    /** Repete um envio que falhou ou foi cancelado (retoma de onde parou). Devolve o id do novo envio. */
    fun retry(transferId: String): String {
        val j = synchronized(jobs) { jobs.firstOrNull { it.id == transferId && it.direction == Direction.SEND } }
            ?: throw IllegalArgumentException("envio não encontrado")
        require(j.info().finished) { "o envio ainda está em andamento" }
        val id = send(j.peerId, j.addrs, j.items)
        synchronized(jobs) { jobs.remove(j) }
        return id
    }

    fun transfers(): List<TransferInfo> = synchronized(jobs) { jobs.map { it.info() } }

    /** Tira da lista as transferências terminadas. */
    fun clearFinished() {
        synchronized(jobs) { jobs.removeAll { it.info().finished } }
    }

    private fun startTicker() = spawn("rd-ticker") {
        while (!stop.get()) {
            val live = synchronized(jobs) { jobs.filter { it.live } }
            for (j in live) {
                j.tickSpeed()
                emit(NodeEvent.Transfer(j.info()))
            }
            try {
                Thread.sleep(if (live.isEmpty()) 1000 else 250)
            } catch (_: InterruptedException) {
                return@spawn
            }
        }
    }

    // ───────────── Recebimento ─────────────

    /** Resposta da pessoa a [NodeEvent.Offer]. */
    fun respondOffer(tid: String, accept: Boolean) {
        offers[tid]?.offer(accept)
    }

    private fun startAcceptor() = spawn("rd-acceptor") {
        while (!stop.get()) {
            val s = try {
                server.accept()
            } catch (_: IOException) {
                if (stop.get() || server.isClosed) return@spawn
                continue
            }
            if (stop.get()) {
                s.close()
                return@spawn
            }
            if (conns.get() >= 64) {
                s.close()
                continue
            }
            spawn("rd-conn") {
                conns.incrementAndGet()
                liveSockets.add(s)
                try {
                    handleConn(s)
                } catch (_: Exception) {
                } finally {
                    liveSockets.remove(s)
                    conns.decrementAndGet()
                    try {
                        s.close()
                    } catch (_: IOException) {
                    }
                }
            }
        }
    }

    private fun handleConn(s: Socket) {
        s.tcpNoDelay = true
        s.soTimeout = 20_000
        val from = s.remoteSocketAddress as? InetSocketAddress
        when (readPreamble(s.getInputStream())) {
            KIND_HELLO -> writePlain(s.getOutputStream(), linkedMapOf("id" to identity.idHex, "name" to config.deviceName, "v" to 1))
            KIND_PAIR -> {
                if (!pairBusy.compareAndSet(false, true)) throw IOException("pareamento já em andamento")
                try {
                    val res = try {
                        pairRespond(s, identity, config.deviceName) { decide(it) }
                    } catch (e: Exception) {
                        finishPair(null, e.message ?: e.javaClass.simpleName)
                        return
                    }
                    finishPair(res, null)
                } finally {
                    pairBusy.set(false)
                }
            }
            KIND_SESSION -> {
                val (cid, ch) = serverHandshake(s, identity) { cid -> peer(cid.hex())?.publicKey }
                val peerId = cid.hex()
                when (val m = ch.recv()) {
                    is Msg.Offer -> {
                        if (m.seg != SEG) {
                            try {
                                ch.send(Msg.Reject("tamanho de segmento não suportado"))
                            } catch (_: IOException) {
                            }
                            throw ProtocolException("segmento incompatível")
                        }
                        serveOffer(peerId, m.tid, m.items, ch, from)
                    }
                    is Msg.Join -> {
                        val t = recvReg[m.tid]
                        if (t != null && t.peerId == peerId) {
                            ch.send(Msg.Joined)
                            s.soTimeout = 2000
                            t.laneLoop(ch)
                        } else {
                            try {
                                ch.send(Msg.Reject("transferência desconhecida"))
                            } catch (_: IOException) {
                            }
                            throw ProtocolException("join sem transferência")
                        }
                    }
                    else -> throw ProtocolException("mensagem inicial inesperada")
                }
            }
            else -> throw ProtocolException("tipo de conexão desconhecido")
        }
    }

    private fun serveOffer(peerId: String, tid: String, items: List<Item>, ch: Channel, from: InetSocketAddress?) {
        val peer = peer(peerId) ?: throw IOException("aparelho não pareado")
        val (files, total, summary) = summarize(items)
        val auto = (peer.autoAccept ?: config.autoAccept) || RecvTransfer.hasSavedState(config.recvDir, peerId, tid)
        val job = Job(tid, Direction.RECV, peerId, peer.name, total, summary, files, TransferState.WAITING)
        job.route = from?.address?.let { Route.of(it) }
        synchronized(jobs) {
            jobs.removeAll { it.id == tid && it.direction == Direction.RECV }
            jobs.add(job)
        }
        emit(NodeEvent.Transfer(job.info()))
        if (!auto) {
            val q = ArrayBlockingQueue<Boolean>(1)
            offers[tid] = q
            emit(NodeEvent.Offer(tid, peerId, peer.name, files, total, summary))
            val ok = q.poll(120, TimeUnit.SECONDS) ?: false
            offers.remove(tid)
            if (!ok) {
                try {
                    ch.send(Msg.Reject("recusado pelo usuário"))
                } catch (_: IOException) {
                }
                return finishJob(job, TransferState.DECLINED, "recusado")
            }
        }
        val t = try {
            RecvTransfer.prepare(config.recvDir, peerId, tid, items)
        } catch (e: IOException) {
            try {
                ch.send(Msg.Reject(e.message ?: "oferta inválida"))
            } catch (_: IOException) {
            }
            finishJob(job, TransferState.FAILED, e.message)
            throw e
        }
        // Reconexão do mesmo envio: a conexão antiga perde.
        recvReg.put(tid, t)?.superseded = true
        job.prog = t.prog
        val tops = t.topPaths(config.recvDir).map { it.path }
        job.path = tops.firstOrNull()
        job.paths = tops
        try {
            ch.send(Msg.Accept(t.have()))
        } catch (e: IOException) {
            unregisterRecv(tid, t)
            finishJob(job, TransferState.FAILED, "o remetente desconectou antes do início")
            throw e
        }
        setState(job, TransferState.RECEIVING)
        ch.socket.soTimeout = 2000
        val end = try {
            t.laneLoop(ch)
        } catch (e: Exception) {
            unregisterRecv(tid, t)
            t.save()
            if (!t.superseded) {
                finishJob(job, TransferState.FAILED, "${e.message ?: e.javaClass.simpleName} (a parte recebida fica guardada; reenvie para retomar)")
            }
            throw e
        }
        unregisterRecv(tid, t)
        when (end) {
            LaneEnd.COMPLETE -> finishJob(job, TransferState.DONE, null)
            LaneEnd.PEER_CANCELLED -> {
                t.cleanup()
                finishJob(job, TransferState.CANCELLED, "cancelado pelo remetente")
            }
            LaneEnd.LOCAL_CANCELLED -> {
                t.cleanup()
                finishJob(job, TransferState.CANCELLED, null)
            }
            LaneEnd.SUPERSEDED -> {}
        }
    }

    /** Tira o recebimento do registro, a não ser que uma reconexão já o tenha substituído. */
    private fun unregisterRecv(tid: String, t: RecvTransfer) {
        recvReg.remove(tid, t)
    }

    /** Encerra o nó: cancela transferências, fecha a porta e as conexões. */
    override fun close() {
        if (!stop.compareAndSet(false, true)) return
        val all = synchronized(jobs) { jobs.toList() }
        for (j in all) if (!j.info().finished) {
            j.cancelRequested = true
            j.prog.cancel()
        }
        try {
            server.close()
        } catch (_: IOException) {
        }
        for (s in liveSockets.toList()) {
            try {
                s.close()
            } catch (_: IOException) {
            }
        }
    }

    companion object {
        /**
         * Sobe um nó. Cria a identidade na primeira vez (gravada via [Store.saveIdentity]); se a
         * identidade existir mas estiver ilegível, falha em vez de trocá-la (trocar perderia os pareamentos).
         */
        fun start(store: Store, config: NodeConfig, listener: ((NodeEvent) -> Unit)? = null): Node {
            val secret = store.loadIdentity()
            val identity = if (secret != null) {
                try {
                    Identity.fromSecret(secret)
                } catch (e: IllegalArgumentException) {
                    throw IOException("identidade corrompida; apague-a só se aceitar parear de novo", e)
                }
            } else {
                Identity.generate().also { store.saveIdentity(it.secretBytes()) }
            }
            config.recvDir.mkdirs()
            val server = bind(config.port)
            val node = Node(store, config, identity, server, listener)
            node.startAcceptor()
            node.startTicker()
            return node
        }

        private fun bind(first: Int): ServerSocket {
            val tries = if (first == 0) 1 else PORT_SPAN
            var last: IOException? = null
            for (p in first until first + tries) {
                val s = ServerSocket()
                try {
                    s.reuseAddress = true
                    s.bind(InetSocketAddress(p))
                    return s
                } catch (e: IOException) {
                    last = e
                    s.close()
                }
            }
            throw IOException("porta $first indisponível: ${last?.message}")
        }
    }
}
