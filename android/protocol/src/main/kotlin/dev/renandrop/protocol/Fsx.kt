package dev.renandrop.protocol

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Leitura posicional de uma fonte de bytes (as pistas paralelas leem trechos diferentes ao mesmo tempo). */
interface RandomReader : Closeable {
    /** Lê exatamente `len` bytes a partir de `pos`; falha com [EOFException] se a fonte acabar antes. */
    fun readFully(pos: Long, buf: ByteArray, off: Int, len: Int)
}

/** Origem dos bytes de um item enviado. No Android pode ser um `ParcelFileDescriptor`/`ContentResolver`. */
fun interface ByteSource {
    /** Abre um leitor independente (cada pista abre o seu). */
    fun open(): RandomReader
}

/**
 * Item de um envio: arquivo ou pasta vazia. `path` é relativo, com `/`, incluindo a pasta de topo
 * (`Fotos/2024/a.jpg`). Pasta vazia: `isDir = true`, `size = 0`, `source = null`.
 */
class SendItem(
    val path: String,
    val size: Long,
    val mtime: Long = 0,
    val isDir: Boolean = false,
    val source: ByteSource? = null,
) {
    init {
        require(isDir || source != null || size == 0L) { "arquivo sem fonte de bytes: $path" }
    }
}

private class ChannelReader(private val ch: FileChannel) : RandomReader {
    override fun readFully(pos: Long, buf: ByteArray, off: Int, len: Int) {
        var p = pos
        var o = off
        var left = len
        while (left > 0) {
            val n = ch.read(ByteBuffer.wrap(buf, o, left), p)
            if (n < 0) throw EOFException("arquivo menor que o esperado")
            p += n
            o += n
            left -= n
        }
    }

    override fun close() = ch.close()
}

/** Fonte de bytes de um arquivo local. */
fun fileSource(f: File): ByteSource = ByteSource { ChannelReader(FileChannel.open(f.toPath(), StandardOpenOption.READ)) }

/**
 * Varre arquivos e pastas locais e devolve os itens do envio (caminho relativo com a pasta de topo,
 * pastas vazias como item de pasta), na mesma ordem do núcleo Rust (por caminho).
 */
fun scanFiles(paths: List<File>): List<SendItem> {
    val out = ArrayList<SendItem>()
    for (p in paths) {
        if (!p.exists()) throw IOException("${p.path}: não existe")
        val name = p.name.ifEmpty { "pasta" }
        if (p.isDirectory) walk(p, name, out) else out.add(SendItem(name, p.length(), p.lastModified(), false, fileSource(p)))
    }
    if (out.isEmpty()) throw IOException("nada para enviar")
    out.sortBy { it.path }
    return out
}

private fun walk(dir: File, rel: String, out: MutableList<SendItem>) {
    val before = out.size
    val kids = dir.listFiles() ?: throw IOException("${dir.path}: não foi possível listar")
    for (k in kids) {
        val relChild = "$rel/${k.name}"
        if (k.isDirectory) walk(k, relChild, out) else if (k.isFile) out.add(SendItem(relChild, k.length(), k.lastModified(), false, fileSource(k)))
    }
    if (out.size == before) out.add(SendItem(rel, 0, 0, true, null))
}

private fun cleanComponent(c: String): String {
    val sb = StringBuilder()
    for (ch in c) sb.append(if (Character.isISOControl(ch) || "<>:\"|?*\\".indexOf(ch) >= 0) '_' else ch)
    var s = sb.toString().trimEnd('.', ' ')
    val stem = s.substringBefore('.').uppercase()
    val reserved = stem in setOf("CON", "PRN", "AUX", "NUL") ||
        (stem.length == 4 && (stem.startsWith("COM") || stem.startsWith("LPT")) && stem[3].isDigit() && stem[3].code < 128)
    if (reserved) s = "_$s"
    if (s.toByteArray(Charsets.UTF_8).size > 200) {
        var cut = s.length
        while (s.substring(0, cut).toByteArray(Charsets.UTF_8).size > 200) cut--
        if (cut > 0 && Character.isHighSurrogate(s[cut - 1])) cut--
        s = s.substring(0, cut)
    }
    return s
}

/** Valida e limpa um caminho relativo recebido da rede. `null` = caminho malicioso. */
internal fun sanitizeRel(path: String): List<String>? {
    if (path.isEmpty() || path.startsWith("/") || path.indexOf('\u0000') >= 0) return null
    val parts = ArrayList<String>()
    for (c in path.split('/')) {
        if (c.isEmpty() || c == "." || c == "..") return null
        val cleaned = cleanComponent(c)
        if (cleaned.isEmpty()) return null
        parts.add(cleaned)
    }
    return parts
}

/** Gera "nome (1).ext" até achar um nome livre. */
internal fun uniquePath(p: File): File {
    if (!p.exists()) return p
    val name = p.name
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    val parent = p.parentFile ?: File(".")
    for (n in 1 until 10_000) {
        val c = File(parent, "$stem ($n)$ext")
        if (!c.exists()) return c
    }
    return p
}

/** Por onde a conexão foi (só para exibir). */
enum class Route {
    LAN, TAILNET, LOCAL, INTERNET;

    companion object {
        fun of(a: InetAddress): Route {
            if (a is Inet4Address) {
                val o = a.address
                val b0 = o[0].toInt() and 0xff
                val b1 = o[1].toInt() and 0xff
                return when {
                    b0 == 100 && b1 in 64..127 -> TAILNET
                    a.isLoopbackAddress -> LOCAL
                    a.isSiteLocalAddress || a.isLinkLocalAddress -> LAN
                    else -> INTERNET
                }
            }
            if (a is Inet6Address) {
                val o = a.address
                val s0 = ((o[0].toInt() and 0xff) shl 8) or (o[1].toInt() and 0xff)
                val s1 = ((o[2].toInt() and 0xff) shl 8) or (o[3].toInt() and 0xff)
                val s2 = ((o[4].toInt() and 0xff) shl 8) or (o[5].toInt() and 0xff)
                return when {
                    s0 == 0xfd7a && s1 == 0x115c && s2 == 0xa1e0 -> TAILNET
                    a.isLoopbackAddress -> LOCAL
                    (s0 and 0xfe00) == 0xfc00 || (s0 and 0xffc0) == 0xfe80 -> LAN
                    else -> INTERNET
                }
            }
            return INTERNET
        }
    }
}
