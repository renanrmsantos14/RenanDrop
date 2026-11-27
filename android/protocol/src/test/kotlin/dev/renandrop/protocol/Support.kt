package dev.renandrop.protocol

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

val LOOPBACK = "127.0.0.1"

fun freePort(): Int = ServerSocket(0).use { it.localPort }

/** Pasta temporária de um teste, dentro de build/interop; apagada ao final. */
fun <T> withTmp(name: String, block: (File) -> T): T {
    val root = File(System.getProperty("renandrop.tmp") ?: error("renandrop.tmp ausente"))
    val dir = File(root, "$name-${System.nanoTime()}")
    dir.mkdirs()
    try {
        return block(dir)
    } finally {
        dir.deleteRecursively()
    }
}

fun reportsDir(): File = File(System.getProperty("renandrop.reports") ?: error("renandrop.reports ausente")).also { it.mkdirs() }

/** Escreve `bytes` de dados pseudoaleatórios determinísticos (xorshift), sem alocar o arquivo inteiro. */
fun writeRandom(f: File, bytes: Long, seed: Long) {
    f.parentFile?.mkdirs()
    val buf = ByteArray(1 shl 20)
    var x = seed * 0x9E3779B97F4A7C15uL.toLong() + 0x1234567L
    if (x == 0L) x = 88172645463325252L
    f.outputStream().buffered(1 shl 20).use { out ->
        var left = bytes
        while (left > 0) {
            var i = 0
            while (i + 8 <= buf.size) {
                x = x xor (x shl 13)
                x = x xor (x ushr 7)
                x = x xor (x shl 17)
                for (k in 0 until 8) buf[i + k] = (x ushr (8 * k)).toByte()
                i += 8
            }
            val n = minOf(left, buf.size.toLong()).toInt()
            out.write(buf, 0, n)
            left -= n
        }
    }
}

fun sha256File(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(1 shl 20)
    f.inputStream().use { i ->
        while (true) {
            val n = i.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Mapa caminho relativo -> hash do conteúdo (arquivos) ou "<dir>" (pastas), para comparar árvores. */
fun treeHashes(root: File): Map<String, String> {
    val out = sortedMapOf<String, String>()
    fun walk(d: File, rel: String) {
        val kids = d.listFiles() ?: return
        if (kids.isEmpty() && rel.isNotEmpty()) out[rel] = "<dir>"
        for (k in kids) {
            if (k.name == ".renandrop-partial") continue
            val r = if (rel.isEmpty()) k.name else "$rel/${k.name}"
            if (k.isDirectory) walk(k, r) else out[r] = sha256File(k)
        }
    }
    walk(root, "")
    return out
}

fun waitUntil(timeoutMs: Long, what: String, cond: () -> Boolean) {
    val t0 = System.nanoTime()
    while (!cond()) {
        if ((System.nanoTime() - t0) / 1_000_000 > timeoutMs) throw AssertionError("tempo esgotado esperando: $what")
        Thread.sleep(20)
    }
}

/** Coleta os eventos de um [Node] Kotlin. */
class KEvents {
    val all = CopyOnWriteArrayList<NodeEvent>()
    val listener: (NodeEvent) -> Unit = { all.add(it) }

    inline fun <reified T : NodeEvent> await(timeoutMs: Long = 20_000, noinline pred: (T) -> Boolean = { true }): T {
        var found: T? = null
        waitUntil(timeoutMs, T::class.simpleName ?: "evento") {
            found = all.filterIsInstance<T>().firstOrNull(pred)
            found != null
        }
        return found!!
    }

    fun awaitTransfer(timeoutMs: Long, pred: (TransferInfo) -> Boolean): TransferInfo =
        await<NodeEvent.Transfer>(timeoutMs) { pred(it.info) }.info

    fun states(id: String): List<TransferState> =
        all.filterIsInstance<NodeEvent.Transfer>().filter { it.info.id == id }.map { it.info.state }
}

/** Processo `renandrop-cli` (núcleo Rust) controlado por stdin/stdout. */
class RustCli(val name: String, val profile: File, val recv: File, val port: Int = freePort()) : Closeable {
    val events = CopyOnWriteArrayList<Map<String, Any?>>()
    private val proc: Process
    val id: String

    init {
        val cli = System.getProperty("renandrop.cli") ?: error("renandrop.cli ausente")
        check(File(cli).canExecute()) { "binário do núcleo Rust não encontrado: $cli (cargo build --release -p renandrop-core --bin renandrop-cli)" }
        profile.mkdirs()
        recv.mkdirs()
        val pb = ProcessBuilder(
            cli, "serve", "--profile", profile.path, "--port", port.toString(), "--name", name,
            "--recv", recv.path, "--no-discovery",
        )
        pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        proc = pb.start()
        val t = Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    try {
                        @Suppress("UNCHECKED_CAST")
                        events.add(Json.parse(line.toByteArray()) as Map<String, Any?>)
                    } catch (_: Exception) {
                    }
                }
            } catch (_: IOException) {
            }
        }
        t.isDaemon = true
        t.start()
        waitUntil(15_000, "Rust pronto") { events.any { it["type"] == "ready" } }
        id = events.first { it["type"] == "ready" }["id"] as String
    }

    val addr: InetSocketAddress get() = InetSocketAddress(LOOPBACK, port)

    fun cmd(line: String) {
        proc.outputStream.write((line + "\n").toByteArray())
        proc.outputStream.flush()
    }

    fun await(timeoutMs: Long = 20_000, what: String, pred: (Map<String, Any?>) -> Boolean): Map<String, Any?> {
        var found: Map<String, Any?>? = null
        waitUntil(timeoutMs, "Rust: $what") {
            found = events.firstOrNull(pred)
            found != null
        }
        return found!!
    }

    fun awaitTransfer(timeoutMs: Long, what: String, pred: (Map<String, Any?>) -> Boolean): Map<String, Any?> =
        await(timeoutMs, what) { it["type"] == "transfer" && pred(it) }

    fun states(id: String): List<String> = events.filter { it["type"] == "transfer" && it["id"] == id }.map { it["state"] as String }

    fun quit() {
        try {
            cmd("quit")
        } catch (_: IOException) {
        }
        if (!proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) proc.destroyForcibly()
    }

    override fun close() {
        if (proc.isAlive) quit()
    }
}

/** Proxy TCP que derruba todas as conexões depois de `cutAfter` bytes cliente->servidor (uma vez). */
class CutProxy(private val target: InetSocketAddress) : Closeable {
    private val server = ServerSocket(0, 50, java.net.InetAddress.getByName(LOOPBACK))
    val port: Int = server.localPort
    private val socks = CopyOnWriteArrayList<Socket>()
    private val generation = AtomicInteger()

    /** Bytes cliente->servidor da geração atual (zera quando a conexão é cortada). */
    val c2s = AtomicLong()

    @Volatile
    var cutAfter: Long = Long.MAX_VALUE
    val cuts = AtomicInteger()
    val addr: InetSocketAddress get() = InetSocketAddress(LOOPBACK, port)

    init {
        thread("proxy-accept") {
            while (!server.isClosed) {
                val a = try {
                    server.accept()
                } catch (_: IOException) {
                    return@thread
                }
                val b = try {
                    Socket().also { it.connect(target, 3000) }
                } catch (_: IOException) {
                    a.close()
                    continue
                }
                a.tcpNoDelay = true
                b.tcpNoDelay = true
                socks.add(a)
                socks.add(b)
                val gen = generation.get()
                thread("proxy-c2s") { pump(a, b, true, gen) }
                thread("proxy-s2c") { pump(b, a, false, gen) }
            }
        }
    }

    private fun thread(name: String, f: () -> Unit) = Thread(f, name).also {
        it.isDaemon = true
        it.start()
    }

    private fun pump(from: Socket, to: Socket, up: Boolean, gen: Int) {
        val buf = ByteArray(64 * 1024)
        try {
            val inp = from.getInputStream()
            val out = to.getOutputStream()
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                if (gen != generation.get()) break
                out.write(buf, 0, n)
                if (up) {
                    val total = c2s.addAndGet(n.toLong())
                    if (total >= cutAfter) cut()
                }
            }
        } catch (_: IOException) {
        } finally {
            try {
                from.close()
                to.close()
            } catch (_: IOException) {
            }
        }
    }

    @Synchronized
    private fun cut() {
        if (cutAfter == Long.MAX_VALUE) return
        cutAfter = Long.MAX_VALUE
        generation.incrementAndGet()
        c2s.set(0)
        cuts.incrementAndGet()
        for (s in socks) {
            try {
                s.close()
            } catch (_: IOException) {
            }
        }
        socks.clear()
    }

    override fun close() {
        try {
            server.close()
        } catch (_: IOException) {
        }
        for (s in socks) {
            try {
                s.close()
            } catch (_: IOException) {
            }
        }
    }
}

fun mbps(bytes: Long, nanos: Long): Double = bytes / 1e6 / (nanos / 1e9)
