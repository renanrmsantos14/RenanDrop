package dev.renandrop.protocol

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Plano de dados: segmentos de 8 MiB com SHA-256, pistas paralelas, retomada por bitmap. */

internal const val SEG = 8L shl 20
internal const val FRAME = 256 shl 10

/** Itens por envio e tamanho máximo por arquivo aceitos (a oferta cabe em um quadro). */
internal const val MAX_ITEMS = 200_000
internal const val MAX_FILE = 1L shl 46

internal fun nsegs(size: Long): Int = ((size + SEG - 1) / SEG).toInt()
internal fun segLen(size: Long, s: Int): Long = minOf(SEG, size - s.toLong() * SEG)

private fun pack(f: Int, s: Int): Long = (f.toLong() shl 32) or (s.toLong() and 0xffffffffL)

/** Uma linha por arquivo, um flag por segmento; `have` marca os segmentos já recebidos. */
private fun doneMap(sizes: List<Long>, have: List<Range>): Array<BooleanArray> {
    val done = Array(sizes.size) { BooleanArray(nsegs(sizes[it])) }
    for (r in have) {
        val v = done.getOrNull(r.f) ?: continue
        for (s in maxOf(r.a, 0) until minOf(r.b, v.size)) v[s] = true
    }
    return done
}

private fun doneBytes(size: Long, segs: BooleanArray): Long {
    var n = 0L
    for (s in segs.indices) if (segs[s]) n += segLen(size, s)
    return n
}

/** Contadores de uma tentativa de transferência, compartilhados com a interface. */
internal class Progress(val total: Long) {
    val bytes = AtomicLong()

    @Volatile
    var cancelled = false
        private set

    /** Endereço usado na conexão principal (mostra a rota: LAN, tailnet...). */
    @Volatile
    var route: InetSocketAddress? = null

    private val socks = ArrayList<Socket>()

    /** Registra um soquete para ser fechado no cancelamento; se já foi cancelado, fecha na hora. */
    fun register(s: Socket) {
        val close = synchronized(socks) {
            socks.add(s)
            cancelled
        }
        if (close) closeQuietly(s)
    }

    fun cancel() {
        cancelled = true
        closeSockets()
    }

    fun closeSockets() {
        val all = synchronized(socks) { socks.toList() }
        for (s in all) closeQuietly(s)
    }
}

private fun closeQuietly(s: Socket) {
    try {
        s.close()
    } catch (_: IOException) {
    }
}

/** Identificador estável: o mesmo conteúdo para o mesmo par de aparelhos gera o mesmo id (permite retomar). */
internal fun transferId(from: ByteArray, to: ByteArray, items: List<Item>): String {
    val h = MessageDigest.getInstance("SHA-256")
    h.update(from)
    h.update(to)
    val num = ByteArray(8)
    fun be(v: Long) {
        for (i in 0 until 8) num[i] = (v ushr (56 - 8 * i)).toByte()
        h.update(num)
    }
    for (it in items) {
        h.update(it.path.toByteArray(Charsets.UTF_8))
        h.update(0)
        be(it.size)
        be(it.mtime)
        h.update(if (it.dir) 1 else 0)
    }
    return h.digest().copyOf(16).hex()
}

// ───────────────────────────── Envio ─────────────────────────────

internal class SendException(val kind: Kind, message: String) : IOException(message) {
    enum class Kind { NOT_PAIRED, DECLINED, CANCELLED, REMOTE, IO }

    /** Só falha de rede vale uma nova tentativa. */
    val retriable: Boolean get() = kind == Kind.IO

    companion object {
        fun io(m: String?) = SendException(Kind.IO, "falha de rede: ${m ?: "erro"}")
        fun remote(m: String) = SendException(Kind.REMOTE, "erro no destino: $m")
        fun declined(m: String) = SendException(Kind.DECLINED, "recusado: $m")
        fun cancelled() = SendException(Kind.CANCELLED, "cancelado")
        fun notPaired() = SendException(Kind.NOT_PAIRED, "o outro aparelho não reconhece este (pareie de novo)")
    }
}

internal enum class Stage { CONNECTING, WAITING_ACCEPT, SENDING }

internal class SendParams(
    val me: Identity,
    val peerPub: ByteArray,
    val addrs: List<InetSocketAddress>,
    val fromName: String,
    val lanes: Int,
    val prog: Progress,
    val onStage: (Stage) -> Unit,
)

internal fun connectAny(addrs: List<InetSocketAddress>, prog: Progress, timeoutMs: Int = 4000): Socket {
    var last: String? = null
    for (a in addrs) {
        if (prog.cancelled) throw SendException.cancelled()
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(a, timeoutMs)
            prog.register(s)
            return s
        } catch (e: IOException) {
            closeQuietly(s)
            last = "$a: ${e.message}"
        }
    }
    throw SendException.io(last ?: "sem endereço para o aparelho")
}

/** Executa E/S de rede traduzindo falhas para [SendException] (cancelamento tem prioridade). */
private inline fun <T> net(prog: Progress, block: () -> T): T = try {
    block()
} catch (e: SendException) {
    throw e
} catch (e: NotPairedException) {
    throw SendException.notPaired()
} catch (e: IOException) {
    throw if (prog.cancelled) SendException.cancelled() else SendException.io(e.message ?: e.javaClass.simpleName)
}

private class SendShared(val prog: Progress) {
    private val queue = ArrayDeque<Long>()
    val remaining = AtomicInteger()
    private val retries = HashMap<Long, Int>()
    private val lock = Object()

    @Volatile
    var failure: SendException? = null
        private set

    @Volatile
    var complete = false

    @Volatile
    var finished = false

    fun fail(e: SendException) {
        synchronized(this) { if (failure == null) failure = e }
        prog.closeSockets()
        wake()
    }

    fun push(v: Long) = synchronized(queue) { queue.addLast(v) }
    fun pop(): Long? = synchronized(queue) { queue.removeFirstOrNull() }
    fun retry(v: Long): Int = synchronized(retries) { retries.merge(v, 1, Int::plus)!! }

    fun await(ms: Long) = synchronized(lock) { lock.wait(ms) }
    fun wake() = synchronized(lock) { lock.notifyAll() }
}

private fun spawn(name: String, block: () -> Unit): Thread = Thread(block, name).also {
    it.isDaemon = true
    it.start()
}

/** Uma tentativa de envio. Para retomar, basta chamar de novo com os mesmos itens. */
internal fun sendOnce(p: SendParams, items: List<SendItem>, tid: String) {
    val prog = p.prog
    try {
        sendAttempt(p, items, tid)
    } finally {
        prog.closeSockets()
    }
}

private fun sendAttempt(p: SendParams, items: List<SendItem>, tid: String) {
    val prog = p.prog
    if (items.size > MAX_ITEMS) throw SendException.remote("arquivos demais num envio (máximo $MAX_ITEMS); envie em partes")
    p.onStage(Stage.CONNECTING)
    val sock = connectAny(p.addrs, prog)
    val used = sock.remoteSocketAddress as? InetSocketAddress
    prog.route = used
    val ch0 = net(prog) { clientHandshake(sock, p.me, p.peerPub) }
    val list = items.mapIndexed { i, it -> Item(i, it.path, it.size, it.mtime, it.isDir) }
    val total = list.sumOf { it.size }
    net(prog) { ch0.send(Msg.Offer(tid, p.fromName, list, total, p.lanes, SEG)) }
    p.onStage(Stage.WAITING_ACCEPT)
    sock.soTimeout = 150_000
    val have = net(prog) {
        when (val m = ch0.recv()) {
            is Msg.Accept -> m.have
            is Msg.Reject -> throw if (prog.cancelled) SendException.cancelled() else SendException.declined(m.reason)
            else -> throw SendException(SendException.Kind.IO, "falha de rede: resposta inesperada")
        }
    }
    sock.soTimeout = 60_000
    p.onStage(Stage.SENDING)

    // Segmentos que faltam.
    val done = doneMap(items.map { it.size }, have)
    val sh = SendShared(prog)
    var already = 0L
    var todo = 0
    for (f in done.indices) {
        already += doneBytes(items[f].size, done[f])
        for (s in done[f].indices) if (!done[f][s]) {
            sh.push(pack(f, s))
            todo++
        }
    }
    prog.bytes.set(already)
    sh.remaining.set(todo)
    val nlanes = minOf(maxOf(p.lanes, 1), maxOf(todo, 1))

    val watch = spawn("rd-cancel-watch") {
        var seen = prog.bytes.get()
        var seenAt = System.nanoTime()
        while (!sh.finished && sh.failure == null) {
            if (prog.cancelled) {
                sh.fail(SendException.cancelled())
                return@spawn
            }
            val now = prog.bytes.get()
            if (now != seen || sh.remaining.get() == 0) {
                seen = now
                seenAt = System.nanoTime()
            } else if (System.nanoTime() - seenAt > 120_000_000_000L) {
                sh.fail(SendException.io("sem progresso por 120 s"))
                return@spawn
            }
            sh.await(100)
        }
    }
    val reader0 = spawn("rd-lane0-r") { laneReader(sh, ch0, items, prog) }
    var writer0Ok = false
    val writer0 = spawn("rd-lane0-w") { writer0Ok = laneWriter(sh, ch0, items, prog) }
    val extras = ArrayList<Thread>()
    for (i in 1 until nlanes) {
        // As demais pistas seguem a rota que funcionou na primeira conexão.
        val addrs = if (used != null) listOf(used) + p.addrs.filter { it != used } else p.addrs
        extras.add(spawn("rd-lane$i") { extraLane(sh, p, addrs, tid, items) })
    }

    writer0.join()
    var error: SendException? = null
    try {
        sh.failure?.let { throw it }
        if (!writer0Ok) throw SendException.io("pista principal encerrada")
        // Espera as confirmações de todos os segmentos.
        while (sh.remaining.get() > 0 && sh.failure == null) sh.await(100)
        sh.failure?.let { throw it }
        net(prog) { ch0.send(Msg.Done) }
        val t0 = System.nanoTime()
        while (!sh.complete && sh.failure == null) {
            if (System.nanoTime() - t0 > 120_000_000_000L) throw SendException.io("o destino não confirmou o fim")
            sh.await(100)
        }
        sh.failure?.let { throw it }
    } catch (e: SendException) {
        error = e
    }
    sh.finished = true
    prog.closeSockets()
    sh.wake()
    watch.join()
    reader0.join()
    for (t in extras) t.join()
    if (error != null) throw if (prog.cancelled) SendException.cancelled() else error
}

private fun extraLane(sh: SendShared, p: SendParams, addrs: List<InetSocketAddress>, tid: String, items: List<SendItem>) {
    val prog = p.prog
    val ch: Channel
    try {
        val s = connectAny(addrs, prog)
        s.soTimeout = 60_000
        ch = net(prog) { clientHandshake(s, p.me, p.peerPub) }
        net(prog) {
            ch.send(Msg.Join(tid))
            when (val m = ch.recv()) {
                Msg.Joined -> Unit
                is Msg.Reject -> throw SendException.remote(m.reason)
                else -> throw SendException.io("resposta inesperada")
            }
        }
    } catch (e: SendException) {
        // Uma pista extra que falha por rede não derruba o envio: as outras assumem a fila.
        if (e.kind != SendException.Kind.IO) sh.fail(e)
        return
    }
    val rd = spawn("rd-lane-r") { laneReader(sh, ch, items, prog) }
    laneWriter(sh, ch, items, prog)
    rd.join()
}

/** Espera o próximo quadro numa pista que pode ficar ociosa (as outras pistas ainda trabalham). */
internal fun patientFrame(ch: Channel, prog: Progress): Int {
    var seen = prog.bytes.get()
    var seenAt = System.nanoTime()
    while (true) {
        try {
            return ch.reader.recv()
        } catch (e: IdleTimeout) {
            if (prog.cancelled) throw CancelledIo()
            val now = prog.bytes.get()
            if (now != seen) {
                seen = now
                seenAt = System.nanoTime()
            } else if (System.nanoTime() - seenAt > 180_000_000_000L) {
                throw e
            }
        }
    }
}

internal fun patientMsg(ch: Channel, prog: Progress): Msg {
    if (patientFrame(ch, prog) != T_JSON) throw ProtocolException("esperava mensagem de controle")
    return decodeMsg(ch.reader.buf, ch.reader.off, ch.reader.len)
}

internal class CancelledIo : IOException("cancelado")

private fun laneReader(sh: SendShared, ch: Channel, items: List<SendItem>, prog: Progress) {
    while (true) {
        val m = try {
            patientMsg(ch, prog)
        } catch (e: IOException) {
            if (!sh.finished && sh.failure == null) sh.fail(SendException.io(e.message ?: e.javaClass.simpleName))
            return
        }
        when (m) {
            is Msg.SegAck -> {
                var ok = false
                while (true) {
                    val n = sh.remaining.get()
                    if (n == 0) break
                    if (sh.remaining.compareAndSet(n, n - 1)) {
                        ok = true
                        break
                    }
                }
                if (!ok) {
                    sh.fail(SendException.remote("confirmação de segmento a mais"))
                    return
                }
                sh.wake()
            }
            is Msg.SegBad -> {
                val it = items.getOrNull(m.f)
                if (it == null || it.isDir || m.s < 0 || m.s >= nsegs(it.size)) {
                    sh.fail(SendException.remote("o destino pediu um segmento inexistente"))
                    return
                }
                val key = pack(m.f, m.s)
                if (sh.retry(key) > 3) {
                    sh.fail(SendException.remote("hash não confere repetidamente"))
                    return
                }
                prog.bytes.addAndGet(-segLen(it.size, m.s))
                sh.push(key)
                sh.wake()
            }
            Msg.Complete -> {
                sh.complete = true
                sh.wake()
                return
            }
            Msg.Cancel -> {
                sh.fail(SendException.declined("cancelado no destino"))
                return
            }
            is Msg.Fail -> {
                sh.fail(SendException.remote(m.reason))
                return
            }
            else -> {}
        }
    }
}

/** Envia segmentos da fila até acabar; `true` se terminou sem erro (a pista 0 ainda precisa mandar `Done`). */
private fun laneWriter(sh: SendShared, ch: Channel, items: List<SendItem>, prog: Progress): Boolean {
    val buf = ByteArray(FRAME)
    var curF = -1
    var reader: RandomReader? = null
    try {
        while (true) {
            if (sh.failure != null) return false
            val next = sh.pop()
            if (next == null) {
                if (sh.remaining.get() == 0) return true
                sh.await(50)
                continue
            }
            val f = (next ushr 32).toInt()
            val s = next.toInt()
            val it = items[f]
            if (curF != f) {
                reader?.close()
                reader = null
                try {
                    reader = it.source!!.open()
                } catch (e: IOException) {
                    sh.fail(SendException.io("${it.path}: ${e.message}"))
                    return false
                }
                curF = f
            }
            try {
                sendSeg(ch, reader!!, f, s, it.size, buf, prog)
            } catch (e: IOException) {
                if (sh.failure == null) sh.fail(SendException.io(e.message ?: e.javaClass.simpleName))
                return false
            }
        }
    } finally {
        try {
            reader?.close()
        } catch (_: IOException) {
        }
    }
}

private fun sendSeg(ch: Channel, rd: RandomReader, f: Int, s: Int, size: Long, buf: ByteArray, prog: Progress) {
    val len = segLen(size, s)
    ch.send(Msg.Seg(f, s, len))
    val md = MessageDigest.getInstance("SHA-256")
    val base = s.toLong() * SEG
    var off = 0L
    while (off < len) {
        val n = minOf(len - off, FRAME.toLong()).toInt()
        try {
            rd.readFully(base + off, buf, 0, n)
        } catch (e: IOException) {
            throw IOException("lendo o arquivo: ${e.message}", e)
        }
        md.update(buf, 0, n)
        ch.writer.send(T_DATA, buf, 0, n)
        prog.bytes.addAndGet(n.toLong())
        off += n
    }
    ch.send(Msg.SegEnd(md.digest().hex()))
}

// ───────────────────────────── Recebimento ─────────────────────────────

internal enum class LaneEnd { COMPLETE, PEER_CANCELLED, LOCAL_CANCELLED, SUPERSEDED }

private fun rangesOf(done: Array<BooleanArray>): List<Range> {
    val out = ArrayList<Range>()
    for (f in done.indices) {
        val v = done[f]
        var i = 0
        while (i < v.size) {
            if (v[i]) {
                val a = i
                while (i < v.size && v[i]) i++
                out.add(Range(f, a, i))
            } else {
                i++
            }
        }
    }
    return out
}

private const val STATE_FILE = "state.json"

internal class RecvTransfer private constructor(
    val tid: String,
    val peerId: String,
    val items: List<Item>,
    val prog: Progress,
    private val names: List<File>,
    private val partDir: File,
    private val done: Array<BooleanArray>,
    private val left: IntArray,
    private val finished: BooleanArray,
) {
    /** Uma reconexão do mesmo envio assumiu o lugar desta. */
    @Volatile
    var superseded = false

    private val lock = Any()
    private val open = HashMap<Int, RandomAccessFile>()
    private var lastSave = System.nanoTime()

    companion object {
        fun partialDirFor(recvDir: File, peerId: String, tid: String): File =
            File(File(recvDir, ".renandrop-partial"), "${peerId.take(8)}-$tid")

        /** Já existe um recebimento parcial desse envio (a retomada dispensa nova confirmação). */
        fun hasSavedState(recvDir: File, peerId: String, tid: String): Boolean =
            File(partialDirFor(recvDir, peerId, tid), STATE_FILE).exists()

        /** Cria (ou retoma) o estado de um recebimento já aceito. */
        fun prepare(recvDir: File, peerId: String, tid: String, items: List<Item>): RecvTransfer {
            if (items.isEmpty() || items.size > MAX_ITEMS) throw ProtocolException("lista de arquivos inválida")
            if (items.any { it.size < 0 || it.size > MAX_FILE }) throw ProtocolException("tamanho de arquivo inválido na oferta")
            for ((k, it) in items.withIndex()) {
                if (it.i != k || sanitizeRel(it.path) == null) throw ProtocolException("caminho inválido na oferta: ${it.path}")
            }
            if (tid.isEmpty() || !tid.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                throw ProtocolException("identificador inválido")
            }
            recvDir.mkdirs()
            val partDir = partialDirFor(recvDir, peerId, tid)
            partDir.mkdirs()
            val sizes = items.map { it.size }
            val saved = loadState(partDir, tid, items)
            val names: List<File>
            val done: Array<BooleanArray>
            val finished: BooleanArray
            if (saved != null) {
                names = saved.names
                done = doneMap(sizes, saved.ranges)
                finished = saved.finished
            } else {
                val tops = HashMap<String, File>()
                names = items.map { it0 ->
                    val parts = sanitizeRel(it0.path)!!
                    var p = tops.getOrPut(parts[0]) { uniquePath(File(recvDir, parts[0])) }
                    for (c in parts.drop(1)) p = File(p, c)
                    p
                }
                done = doneMap(sizes, emptyList())
                finished = BooleanArray(items.size)
            }
            // Confere as parciais existentes.
            for ((k, it) in items.withIndex()) {
                if (finished[k] || it.dir || it.size == 0L) continue
                val part = File(partDir, "$k.part")
                if (!part.isFile || part.length() != it.size) done[k].fill(false)
            }
            for ((k, it) in items.withIndex()) {
                if (it.dir) {
                    names[k].mkdirs()
                    finished[k] = true
                } else if (it.size == 0L && !finished[k]) {
                    names[k].parentFile?.mkdirs()
                    names[k].createNewFileOrTruncate()
                    if (it.mtime > 0) names[k].setLastModified(it.mtime)
                    finished[k] = true
                }
            }
            val left = IntArray(items.size) { k -> if (finished[k]) 0 else done[k].count { !it } }
            var have = 0L
            for ((k, it) in items.withIndex()) have += if (finished[k]) it.size else doneBytes(it.size, done[k])
            val prog = Progress(items.sumOf { it.size })
            prog.bytes.set(have)
            val t = RecvTransfer(tid, peerId, items, prog, names, partDir, done, left, finished)
            t.save()
            return t
        }

        private class Saved(val names: List<File>, val ranges: List<Range>, val finished: BooleanArray)

        private fun loadState(partDir: File, tid: String, items: List<Item>): Saved? = try {
            val o = Json.parse(File(partDir, STATE_FILE).readBytes()).obj()
            val savedItems = o.list("items").map { Item.fromJson(it.obj()) }
            val names = o.list("names").map { File(it as String) }
            val fin = o.list("finished").map { it as Boolean }
            if (o.str("tid") != tid || savedItems != items || names.size != items.size || fin.size != items.size) {
                null
            } else {
                val ranges = o.list("ranges").map {
                    val r = it as List<*>
                    Range((r[0] as Number).toInt(), (r[1] as Number).toInt(), (r[2] as Number).toInt())
                }
                Saved(names, ranges, fin.toBooleanArray())
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Segmentos já recebidos (arquivos finalizados contam como completos). */
    fun have(): List<Range> = synchronized(lock) {
        val r = rangesOf(done).toMutableList()
        for ((k, it) in items.withIndex()) {
            if (finished[k] && nsegs(it.size) > 0) r.add(Range(k, 0, nsegs(it.size)))
        }
        r
    }

    /** Caminho de topo de cada entrada enviada (arquivo ou pasta), para "mostrar na pasta". */
    fun topPaths(recvDir: File): List<File> {
        val out = ArrayList<File>()
        for ((it, n) in items.zip(names)) {
            val depth = it.path.split('/').size
            var p: File? = n
            repeat(depth - 1) { p = p?.parentFile }
            val top = p ?: continue
            if (top.path.startsWith(recvDir.path) && top !in out) out.add(top)
        }
        return out
    }

    private fun saveLocked() {
        val st = linkedMapOf<String, Any?>(
            "tid" to tid,
            "items" to items.map { it.toJson() },
            "names" to names.map { it.path },
            "ranges" to rangesOf(done).map { listOf(it.f, it.a, it.b) },
            "finished" to finished.toList(),
        )
        try {
            val tmp = File(partDir, "state.tmp")
            tmp.writeBytes(Json.write(st))
            Files.move(tmp.toPath(), File(partDir, STATE_FILE).toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: IOException) {
        }
        lastSave = System.nanoTime()
    }

    fun save() = synchronized(lock) { saveLocked() }

    fun cleanup() {
        synchronized(lock) {
            for (h in open.values) try {
                h.close()
            } catch (_: IOException) {
            }
            open.clear()
        }
        partDir.deleteRecursively()
        partDir.parentFile?.delete() // só some se estiver vazia
    }

    private fun fileFor(f: Int): RandomAccessFile = synchronized(lock) {
        open[f]?.let { return it }
        val it = items[f]
        val h = RandomAccessFile(File(partDir, "$f.part"), "rw")
        if (h.length() != it.size) h.setLength(it.size)
        open[f] = h
        h
    }

    /** Marca o segmento como recebido; finaliza o arquivo quando completo. */
    private fun segDone(f: Int, s: Int) = synchronized(lock) {
        if (done[f][s]) return@synchronized
        done[f][s] = true
        left[f]--
        if (left[f] == 0) {
            val it = items[f]
            open.remove(f)?.close()
            val part = File(partDir, "$f.part")
            if (it.mtime > 0) part.setLastModified(it.mtime)
            var dest = names[f]
            dest.parentFile?.mkdirs()
            if (dest.exists()) dest = uniquePath(dest)
            Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            finished[f] = true
            saveLocked()
        } else if (System.nanoTime() - lastSave > 1_000_000_000L) {
            saveLocked()
        }
    }

    private fun isComplete(): Boolean = synchronized(lock) { finished.all { it } }

    /** Atende uma conexão (pista) até o fim da transferência. */
    fun laneLoop(ch: Channel): LaneEnd {
        val md = MessageDigest.getInstance("SHA-256")
        try {
            while (true) {
                when (val msg = patientMsg(ch, prog)) {
                    is Msg.Seg -> {
                        val end = recvSegment(ch, msg, md)
                        if (end != null) return end
                    }
                    Msg.Done -> {
                        if (isComplete()) {
                            cleanup()
                            ch.send(Msg.Complete)
                            return LaneEnd.COMPLETE
                        }
                        ch.send(Msg.Fail("faltam segmentos"))
                        throw ProtocolException("o remetente encerrou com segmentos faltando")
                    }
                    Msg.Cancel -> return LaneEnd.PEER_CANCELLED
                    else -> throw ProtocolException("mensagem inesperada")
                }
            }
        } catch (e: CancelledIo) {
            try {
                ch.send(Msg.Cancel)
            } catch (_: IOException) {
            }
            return LaneEnd.LOCAL_CANCELLED
        }
    }

    /** Recebe um segmento; devolve o fim da pista se ela deve parar, ou `null` para continuar. */
    private fun recvSegment(ch: Channel, seg: Msg.Seg, md: MessageDigest): LaneEnd? {
        val f = seg.f
        val s = seg.s
        val it = items.getOrNull(f) ?: throw ProtocolException("arquivo fora da lista")
        if (it.dir || s < 0 || s >= nsegs(it.size) || seg.len != segLen(it.size, s)) throw ProtocolException("segmento inválido")
        val already = synchronized(lock) { finished[f] || done[f][s] }
        val file = if (already) null else fileFor(f)
        md.reset()
        var got = 0L
        val base = s.toLong() * SEG
        val rd = ch.reader
        while (got < seg.len) {
            val t = patientFrame(ch, prog)
            if (t != T_DATA || got + rd.len > seg.len) throw ProtocolException("dados fora do esperado")
            md.update(rd.buf, rd.off, rd.len)
            if (file != null) writeFully(file, rd.buf, rd.off, rd.len, base + got)
            got += rd.len
            prog.bytes.addAndGet(rd.len.toLong())
            if (superseded) return LaneEnd.SUPERSEDED
            if (prog.cancelled) {
                try {
                    ch.send(Msg.Cancel)
                } catch (_: IOException) {
                }
                return LaneEnd.LOCAL_CANCELLED
            }
        }
        val end = patientMsg(ch, prog) as? Msg.SegEnd ?: throw ProtocolException("fim de segmento ausente")
        if (already) {
            // Reenvio de algo que já temos (ex.: após retomada): confirma sem regravar.
            prog.bytes.addAndGet(-seg.len)
            ch.send(Msg.SegAck(f, s))
        } else if (md.digest().hex() == end.sha) {
            try {
                segDone(f, s)
            } catch (e: IOException) {
                try {
                    ch.send(Msg.Fail(e.message ?: "erro ao gravar"))
                } catch (_: IOException) {
                }
                throw e
            }
            ch.send(Msg.SegAck(f, s))
        } else {
            prog.bytes.addAndGet(-seg.len)
            ch.send(Msg.SegBad(f, s))
        }
        return null
    }

    private fun writeFully(f: RandomAccessFile, b: ByteArray, off: Int, len: Int, pos: Long) {
        val ch = f.channel
        val bb = ByteBuffer.wrap(b, off, len)
        var p = pos
        while (bb.hasRemaining()) p += ch.write(bb, p)
    }
}

private fun File.createNewFileOrTruncate() {
    java.io.FileOutputStream(this).close()
}
