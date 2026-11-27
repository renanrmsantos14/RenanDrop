package dev.renandrop.protocol

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Formato de fio (docs/protocolo.md): preâmbulo, quadros em claro e mensagens de controle em JSON. */

internal val MAGIC = byteArrayOf('R'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(), 'P'.code.toByte())
internal const val VERSION = 1
internal const val KIND_SESSION = 1
internal const val KIND_PAIR = 2
internal const val KIND_HELLO = 3
internal const val T_JSON = 1
internal const val T_DATA = 2
internal const val T_FINISHED = 0xF0
internal const val ST_OK = 0
internal const val ST_NOT_PAIRED = 1

/** Porta padrão do serviço; se estiver ocupada, o nó tenta as seguintes. */
const val DEFAULT_PORT = 47821
const val PORT_SPAN = 5

internal fun preamble(kind: Int): ByteArray = MAGIC + byteArrayOf(VERSION.toByte(), kind.toByte())

/** Lê "RDRP", versão e tipo (6 bytes); devolve o tipo. */
internal fun readPreamble(inp: InputStream): Int {
    val p = ByteArray(6)
    DataInputStream(inp).readFully(p)
    if (!p.copyOfRange(0, 4).contentEquals(MAGIC)) throw ProtocolException("não é o protocolo RenanDrop")
    if (p[4].toInt() != VERSION) throw ProtocolException("versão de protocolo ${p[4]} não suportada")
    return p[5].toInt() and 0xff
}

/** Quadro JSON sem cifra (`[u32 BE n][json]`), usado antes de haver chaves: pareamento e sondagem. */
internal fun writePlain(out: OutputStream, m: Map<String, Any?>) {
    val b = Json.write(m)
    val frame = ByteArray(4 + b.size)
    frame[0] = (b.size ushr 24).toByte()
    frame[1] = (b.size ushr 16).toByte()
    frame[2] = (b.size ushr 8).toByte()
    frame[3] = b.size.toByte()
    System.arraycopy(b, 0, frame, 4, b.size)
    out.write(frame)
    out.flush()
}

internal fun readPlain(inp: InputStream, max: Int): Map<String, Any?> {
    val d = DataInputStream(inp)
    val n = d.readInt()
    if (n <= 0 || n > max) throw ProtocolException("mensagem inválida")
    val b = ByteArray(n)
    try {
        d.readFully(b)
    } catch (e: EOFException) {
        throw ProtocolException("mensagem truncada")
    }
    return Json.parse(b).obj()
}

/** Item de uma oferta: caminho relativo com `/`, incluindo a pasta de topo. */
internal data class Item(
    val i: Int,
    val path: String,
    val size: Long,
    /** ms desde a época; 0 = desconhecido. */
    val mtime: Long,
    /** Pasta (vazia) a recriar. */
    val dir: Boolean,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf("i" to i, "path" to path, "size" to size, "mtime" to mtime, "dir" to dir)

    companion object {
        fun fromJson(o: Map<String, Any?>) = Item(
            i = o.long("i").toInt(),
            path = o.str("path"),
            size = o.long("size"),
            mtime = o.longOr("mtime", 0),
            dir = o.boolOr("dir", false),
        )
    }
}

/** Faixa de segmentos já recebidos de um arquivo: [a, b). Vai no fio como `[arquivo, ini, fim]`. */
internal data class Range(val f: Int, val a: Int, val b: Int)

internal sealed interface Msg {
    data class Offer(val tid: String, val from: String, val items: List<Item>, val total: Long, val lanes: Int, val seg: Long) : Msg
    data class Accept(val have: List<Range>) : Msg
    data class Reject(val reason: String) : Msg
    data class Join(val tid: String) : Msg
    data object Joined : Msg
    data class Seg(val f: Int, val s: Int, val len: Long) : Msg
    data class SegEnd(val sha: String) : Msg
    data class SegAck(val f: Int, val s: Int) : Msg
    data class SegBad(val f: Int, val s: Int) : Msg
    data object Done : Msg
    data object Complete : Msg
    data class Fail(val reason: String) : Msg
    data object Cancel : Msg
}

internal fun Msg.encode(): ByteArray {
    val m: Map<String, Any?> = when (this) {
        is Msg.Offer -> linkedMapOf(
            "t" to "offer", "tid" to tid, "from" to from, "items" to items.map { it.toJson() },
            "total" to total, "lanes" to lanes, "seg" to seg,
        )
        is Msg.Accept -> linkedMapOf("t" to "accept", "have" to have.map { listOf(it.f, it.a, it.b) })
        is Msg.Reject -> linkedMapOf("t" to "reject", "reason" to reason)
        is Msg.Join -> linkedMapOf("t" to "join", "tid" to tid)
        Msg.Joined -> linkedMapOf("t" to "joined")
        is Msg.Seg -> linkedMapOf("t" to "seg", "f" to f, "s" to s, "len" to len)
        is Msg.SegEnd -> linkedMapOf("t" to "seg_end", "sha" to sha)
        is Msg.SegAck -> linkedMapOf("t" to "seg_ack", "f" to f, "s" to s)
        is Msg.SegBad -> linkedMapOf("t" to "seg_bad", "f" to f, "s" to s)
        Msg.Done -> linkedMapOf("t" to "done")
        Msg.Complete -> linkedMapOf("t" to "complete")
        is Msg.Fail -> linkedMapOf("t" to "fail", "reason" to reason)
        Msg.Cancel -> linkedMapOf("t" to "cancel")
    }
    return Json.write(m)
}

internal fun decodeMsg(b: ByteArray, off: Int, len: Int): Msg {
    val o = Json.parse(b, off, len).obj()
    return when (val t = o.str("t")) {
        "offer" -> Msg.Offer(
            tid = o.str("tid"),
            from = o.str("from"),
            items = o.list("items").map { Item.fromJson(it.obj()) },
            total = o.long("total"),
            lanes = o.long("lanes").toInt(),
            seg = o.long("seg"),
        )
        "accept" -> Msg.Accept(
            o.list("have").map {
                val r = it as? List<*> ?: throw ProtocolException("faixa inválida")
                if (r.size != 3) throw ProtocolException("faixa inválida")
                Range((r[0] as Number).toInt(), (r[1] as Number).toInt(), (r[2] as Number).toInt())
            },
        )
        "reject" -> Msg.Reject(o.str("reason"))
        "join" -> Msg.Join(o.str("tid"))
        "joined" -> Msg.Joined
        "seg" -> Msg.Seg(o.long("f").toInt(), o.long("s").toInt(), o.long("len"))
        "seg_end" -> Msg.SegEnd(o.str("sha"))
        "seg_ack" -> Msg.SegAck(o.long("f").toInt(), o.long("s").toInt())
        "seg_bad" -> Msg.SegBad(o.long("f").toInt(), o.long("s").toInt())
        "done" -> Msg.Done
        "complete" -> Msg.Complete
        "fail" -> Msg.Fail(o.str("reason"))
        "cancel" -> Msg.Cancel
        else -> throw ProtocolException("mensagem desconhecida: $t")
    }
}
