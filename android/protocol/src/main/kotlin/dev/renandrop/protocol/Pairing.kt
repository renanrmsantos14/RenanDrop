package dev.renandrop.protocol

import java.net.Socket
import java.security.MessageDigest

/**
 * Pareamento por código curto (SAS de 6 dígitos com compromisso prévio), sem segredo compartilhado.
 * A envia hash(pubA|nonceA); B responde pubB|nonceB; A revela. O código vem de
 * SHA-256("RDSAS1" | pubA | pubB | nonceA | nonceB). A confirmação final leva um MAC derivado do ECDH
 * entre as chaves estáticas.
 */

/** O que a interface mostra para a pessoa conferir nas duas telas. */
data class PairPrompt(val peerName: String, val code: String, val outgoing: Boolean)

/** Outro aparelho confirmado no pareamento. */
internal class Paired(val id: ByteArray, val name: String, val publicKey: ByteArray)

private const val ROLE_A = 'A'.code
private const val ROLE_B = 'B'.code

private fun sasHash(pubA: ByteArray, pubB: ByteArray, na: ByteArray, nb: ByteArray): ByteArray =
    sha256("RDSAS1".toByteArray(), pubA, pubB, na, nb)

private fun confirmMac(me: Identity, peerPub: ByteArray, sas: ByteArray, role: Int, ok: Boolean): String {
    val k = hkdf(sas, me.ecdh(peerPub), "RenanDrop pair confirm".toByteArray())
    val data = byteArrayOf(role.toByte(), if (ok) 1 else 0) + sas
    return hmacSha256(k, data).hex()
}

private fun finish(s: Socket, me: Identity, peerPub: ByteArray, sas: ByteArray, myRole: Int, ok: Boolean): Boolean {
    val peerRole = if (myRole == ROLE_A) ROLE_B else ROLE_A
    writePlain(
        s.getOutputStream(),
        linkedMapOf("t" to "confirm", "ok" to ok, "mac" to confirmMac(me, peerPub, sas, myRole, ok)),
    )
    s.soTimeout = 150_000
    val m = readPlain(s.getInputStream(), 4096)
    if (m.str("t") != "confirm") throw ProtocolException("resposta inesperada no pareamento")
    val peerOk = m.bool("ok")
    val expect = confirmMac(me, peerPub, sas, peerRole, peerOk)
    if (!MessageDigest.isEqual(expect.toByteArray(), m.str("mac").toByteArray())) {
        throw ProtocolException("confirmação de pareamento inválida (possível interceptação)")
    }
    return ok && peerOk
}

private fun conclude(
    s: Socket, me: Identity, peerPub: ByteArray, peerName: String, sas: ByteArray, myRole: Int,
    decide: (PairPrompt) -> Boolean,
): Paired? {
    val ok = decide(PairPrompt(peerName, sasDigits(sas), myRole == ROLE_A))
    val both = finish(s, me, peerPub, sas, myRole, ok)
    return if (both) Paired(idOf(peerPub), peerName, peerPub) else null
}

/** Lado que inicia (o preâmbulo ainda não foi enviado). `decide` bloqueia até a pessoa confirmar o código. */
internal fun pairInitiate(s: Socket, me: Identity, myName: String, decide: (PairPrompt) -> Boolean): Paired? {
    s.soTimeout = 20_000
    val out = s.getOutputStream()
    out.write(preamble(KIND_PAIR))
    val na = randomBytes(16)
    writePlain(out, linkedMapOf("t" to "init", "commit" to sha256(me.publicKey, na).hex(), "name" to myName))
    val resp = readPlain(s.getInputStream(), 4096)
    if (resp.str("t") != "resp") throw ProtocolException("resposta inesperada no pareamento")
    val pubB = resp.str("public").unhex()
    val nb = resp.str("nonce").unhex()
    val nameB = resp.str("name")
    if (pubB.size != 65 || nb.size != 16) throw ProtocolException("dados de pareamento inválidos")
    P256.publicKey(pubB) // valida a chave antes de gastar a atenção da pessoa
    writePlain(out, linkedMapOf("t" to "reveal", "public" to me.publicKey.hex(), "nonce" to na.hex()))
    val sas = sasHash(me.publicKey, pubB, na, nb)
    return conclude(s, me, pubB, nameB, sas, ROLE_A, decide)
}

/** Lado que responde (o preâmbulo já foi lido). */
internal fun pairRespond(s: Socket, me: Identity, myName: String, decide: (PairPrompt) -> Boolean): Paired? {
    s.soTimeout = 20_000
    val init = readPlain(s.getInputStream(), 4096)
    if (init.str("t") != "init") throw ProtocolException("mensagem inicial inesperada")
    val commit = init.str("commit")
    val nameA = init.str("name")
    val nb = randomBytes(16)
    writePlain(s.getOutputStream(), linkedMapOf("t" to "resp", "public" to me.publicKey.hex(), "nonce" to nb.hex(), "name" to myName))
    val reveal = readPlain(s.getInputStream(), 4096)
    if (reveal.str("t") != "reveal") throw ProtocolException("revelação ausente")
    val pubA = reveal.str("public").unhex()
    val na = reveal.str("nonce").unhex()
    if (pubA.size != 65 || na.size != 16 || sha256(pubA, na).hex() != commit) {
        throw ProtocolException("compromisso de pareamento não confere")
    }
    P256.publicKey(pubA)
    val sas = sasHash(pubA, me.publicKey, na, nb)
    return conclude(s, me, pubA, nameA, sas, ROLE_B, decide)
}
