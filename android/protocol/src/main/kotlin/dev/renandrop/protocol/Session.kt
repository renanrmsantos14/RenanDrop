package dev.renandrop.protocol

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.Socket

/** O outro aparelho não reconhece este (ou este não reconhece o outro): é preciso parear de novo. */
class NotPairedException : IOException("aparelho não pareado")

/** Canal cifrado de uma conexão: leitor, escritor e o soquete. */
internal class Channel(val socket: Socket, val reader: SecureReader, val writer: SecureWriter) : Closeable {
    fun send(m: Msg) {
        val b = m.encode()
        writer.send(T_JSON, b, 0, b.size)
    }

    fun recv(): Msg {
        val t = reader.recv()
        if (t != T_JSON) throw ProtocolException("esperava mensagem de controle")
        return decodeMsg(reader.buf, reader.off, reader.len)
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }
}

private fun keys(dhEe: ByteArray, dhSs: ByteArray, transcript: ByteArray): Pair<ByteArray, ByteArray> {
    val ikm = dhEe + dhSs
    return hkdf(transcript, ikm, "RenanDrop c2s".toByteArray()) to hkdf(transcript, ikm, "RenanDrop s2c".toByteArray())
}

/**
 * Cliente: o preâmbulo (tipo 1) ainda não foi enviado.
 * hello = id(16) | efêmera(65) | nonce(16); transcript = SHA-256(preâmbulo | hello | 0x00 | srv).
 */
internal fun clientHandshake(s: Socket, me: Identity, peerPub: ByteArray): Channel {
    val eph = Ephemeral()
    val hello = me.id + eph.publicKey + randomBytes(16)
    val pre = preamble(KIND_SESSION)
    val out = s.getOutputStream()
    out.write(pre + hello)
    out.flush()

    val inp = DataInputStream(s.getInputStream())
    val status = inp.read()
    if (status < 0) throw IOException("conexão encerrada no aperto de mão")
    if (status == ST_NOT_PAIRED) throw NotPairedException()
    if (status != ST_OK) throw IOException("o aparelho recusou a conexão (código $status)")
    val srv = ByteArray(97)
    inp.readFully(srv)
    if (!srv.copyOfRange(0, 16).contentEquals(idOf(peerPub))) throw IOException("identidade inesperada do aparelho")
    val transcript = sha256(pre, hello, byteArrayOf(ST_OK.toByte()), srv)
    val dhEe = eph.ecdh(srv.copyOfRange(16, 81))
    val dhSs = me.ecdh(peerPub)
    val (c2s, s2c) = keys(dhEe, dhSs, transcript)
    val r = SecureReader(s.getInputStream(), s2c)
    val w = SecureWriter(out, c2s)
    w.send(T_FINISHED, transcript)
    val t = try {
        r.recv()
    } catch (e: IOException) {
        throw IOException("o aparelho não confirmou a chave (não pareado?)", e)
    }
    if (t != T_FINISHED || !r.buf.copyOfRange(r.off, r.off + r.len).contentEquals(transcript)) {
        throw ProtocolException("confirmação de chave inválida")
    }
    return Channel(s, r, w)
}

/** Servidor: o preâmbulo já foi lido. `lookup` devolve a chave pública de um aparelho pareado. */
internal fun serverHandshake(s: Socket, me: Identity, lookup: (ByteArray) -> ByteArray?): Pair<ByteArray, Channel> {
    val inp = DataInputStream(s.getInputStream())
    val out = s.getOutputStream()
    val hello = ByteArray(97)
    inp.readFully(hello)
    val cid = hello.copyOfRange(0, 16)
    val peerPub = lookup(cid)
    if (peerPub == null) {
        try {
            out.write(ST_NOT_PAIRED)
            out.flush()
        } catch (_: IOException) {
        }
        throw NotPairedException()
    }
    val eph = Ephemeral()
    val srv = me.id + eph.publicKey + randomBytes(16)
    out.write(byteArrayOf(ST_OK.toByte()) + srv)
    out.flush()
    val transcript = sha256(preamble(KIND_SESSION), hello, byteArrayOf(ST_OK.toByte()), srv)
    val dhEe = eph.ecdh(hello.copyOfRange(16, 81))
    val dhSs = me.ecdh(peerPub)
    val (c2s, s2c) = keys(dhEe, dhSs, transcript)
    val r = SecureReader(s.getInputStream(), c2s)
    val w = SecureWriter(out, s2c)
    val t = try {
        r.recv()
    } catch (e: IOException) {
        throw IOException("confirmação de chave inválida (aparelho não pareado?)", e)
    }
    if (t != T_FINISHED || !r.buf.copyOfRange(r.off, r.off + r.len).contentEquals(transcript)) {
        throw ProtocolException("confirmação de chave inválida")
    }
    w.send(T_FINISHED, transcript)
    return cid to Channel(s, r, w)
}
