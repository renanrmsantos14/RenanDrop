package dev.renandrop.protocol

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.SocketTimeoutException
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Primitivas do RenanDrop sobre o JDK: P-256 (SEC1 não comprimido), ECDH, HKDF-SHA256 e AES-256-GCM. */

const val TAG_LEN = 16

/** Tamanho máximo do texto claro de um quadro (a oferta de uma pasta grande é um quadro só). */
const val MAX_PLAIN = 32 shl 20

internal val rng = SecureRandom()

internal fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

private val HEX = "0123456789abcdef".toCharArray()

internal fun ByteArray.hex(): String {
    val c = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xff
        c[i * 2] = HEX[v ushr 4]
        c[i * 2 + 1] = HEX[v and 15]
    }
    return String(c)
}

internal fun String.unhex(): ByteArray {
    if (length % 2 != 0) throw ProtocolException("hexadecimal inválido")
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val h = Character.digit(this[i * 2], 16)
        val l = Character.digit(this[i * 2 + 1], 16)
        if (h < 0 || l < 0) throw ProtocolException("hexadecimal inválido")
        out[i] = ((h shl 4) or l).toByte()
    }
    return out
}

internal fun sha256(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    for (p in parts) md.update(p)
    return md.digest()
}

internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val m = Mac.getInstance("HmacSHA256")
    m.init(SecretKeySpec(key, "HmacSHA256"))
    return m.doFinal(data)
}

/** HKDF-SHA256 (extract + expand). */
internal fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, len: Int = 32): ByteArray {
    val prk = hmacSha256(salt, ikm)
    val out = ByteArray(len)
    var t = ByteArray(0)
    var pos = 0
    var counter = 1
    while (pos < len) {
        t = hmacSha256(prk, t + info + byteArrayOf(counter.toByte()))
        val n = minOf(t.size, len - pos)
        System.arraycopy(t, 0, out, pos, n)
        pos += n
        counter++
    }
    return out
}

/** Id do aparelho: primeiros 16 bytes de SHA-256(chave pública). */
fun idOf(publicKey: ByteArray): ByteArray = sha256(publicKey).copyOf(16)

/** Código de 6 dígitos (SAS) a partir de um hash. */
internal fun sasDigits(h: ByteArray): String {
    val n = ((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or
        ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
    return String.format("%06d", n % 1_000_000)
}

/** Curva P-256 com aritmética mínima (só para derivar a chave pública de um segredo salvo). */
internal object P256 {
    val params: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1"))
        getParameterSpec(ECParameterSpec::class.java)
    }
    private val p: BigInteger = (params.curve.field as java.security.spec.ECFieldFp).p
    private val a: BigInteger = params.curve.a
    private val b: BigInteger = params.curve.b
    val n: BigInteger = params.order
    private val g: ECPoint = params.generator
    private val keyFactory: KeyFactory = KeyFactory.getInstance("EC")

    fun onCurve(x: BigInteger, y: BigInteger): Boolean {
        if (x.signum() < 0 || y.signum() < 0 || x >= p || y >= p) return false
        return y.multiply(y).mod(p) == x.pow(3).add(a.multiply(x)).add(b).mod(p)
    }

    private fun add(p1: ECPoint, p2: ECPoint): ECPoint {
        if (p1 == ECPoint.POINT_INFINITY) return p2
        if (p2 == ECPoint.POINT_INFINITY) return p1
        val lambda: BigInteger
        if (p1.affineX == p2.affineX) {
            if (p1.affineY != p2.affineY || p1.affineY.signum() == 0) return ECPoint.POINT_INFINITY
            lambda = p1.affineX.pow(2).multiply(BigInteger.valueOf(3)).add(a)
                .multiply(p1.affineY.shiftLeft(1).modInverse(p)).mod(p)
        } else {
            lambda = p2.affineY.subtract(p1.affineY)
                .multiply(p2.affineX.subtract(p1.affineX).mod(p).modInverse(p)).mod(p)
        }
        val x3 = lambda.pow(2).subtract(p1.affineX).subtract(p2.affineX).mod(p)
        val y3 = lambda.multiply(p1.affineX.subtract(x3)).subtract(p1.affineY).mod(p)
        return ECPoint(x3, y3)
    }

    fun mulG(k: BigInteger): ECPoint {
        var r = ECPoint.POINT_INFINITY
        for (i in k.bitLength() - 1 downTo 0) {
            r = add(r, r)
            if (k.testBit(i)) r = add(r, g)
        }
        return r
    }

    fun encode(pt: ECPoint): ByteArray {
        val out = ByteArray(65)
        out[0] = 4
        fixed(pt.affineX).copyInto(out, 1)
        fixed(pt.affineY).copyInto(out, 33)
        return out
    }

    fun fixed(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        if (raw.size > 32) raw.copyInto(out, 0, raw.size - 32, raw.size) else raw.copyInto(out, 32 - raw.size)
        return out
    }

    fun publicKey(sec1: ByteArray): java.security.PublicKey {
        if (sec1.size != 65 || sec1[0] != 4.toByte()) throw ProtocolException("chave pública inválida")
        val x = BigInteger(1, sec1.copyOfRange(1, 33))
        val y = BigInteger(1, sec1.copyOfRange(33, 65))
        if (!onCurve(x, y)) throw ProtocolException("chave pública fora da curva")
        return keyFactory.generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
    }

    fun privateKey(secret: ByteArray): PrivateKey {
        val s = BigInteger(1, secret)
        if (secret.size != 32 || s.signum() == 0 || s >= n) throw IllegalArgumentException("chave privada inválida")
        return keyFactory.generatePrivate(ECPrivateKeySpec(s, params))
    }

    fun ecdh(priv: PrivateKey, peerPub: ByteArray): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(publicKey(peerPub), true)
        val out = ka.generateSecret()
        return if (out.size == 32) out else fixed(BigInteger(1, out))
    }
}

/**
 * Identidade do aparelho: par P-256 fixo. O segredo (32 bytes, escalar bruto) é o mesmo formato do
 * `identity.bin` do núcleo Rust; quem chama é responsável por guardá-lo (ver [Store]).
 */
class Identity private constructor(private val secret: ByteArray, private val priv: PrivateKey, val publicKey: ByteArray) {
    /** Id em 16 bytes. */
    val id: ByteArray = idOf(publicKey)

    /** Id em hexadecimal (32 caracteres), o mesmo exibido no mDNS. */
    val idHex: String get() = id.hex()

    /** Segredo para persistir (cópia). Trate como chave: nunca registre em log. */
    fun secretBytes(): ByteArray = secret.copyOf()

    internal fun ecdh(peerPub: ByteArray): ByteArray = P256.ecdh(priv, peerPub)

    companion object {
        fun generate(): Identity {
            val kp = pair()
            return fromSecret(P256.fixed((kp.private as ECPrivateKey).s))
        }

        fun fromSecret(secret: ByteArray): Identity {
            val priv = P256.privateKey(secret)
            val pub = P256.encode(P256.mulG(BigInteger(1, secret)))
            return Identity(secret.copyOf(), priv, pub)
        }

        internal fun pair(): java.security.KeyPair {
            val g = KeyPairGenerator.getInstance("EC")
            g.initialize(ECGenParameterSpec("secp256r1"), rng)
            return g.generateKeyPair()
        }
    }
}

/** Par efêmero de uma sessão. */
internal class Ephemeral {
    private val kp = Identity.pair()
    val publicKey: ByteArray = P256.encode((kp.public as ECPublicKey).w)
    fun ecdh(peerPub: ByteArray): ByteArray = P256.ecdh(kp.private, peerPub)
}

/** Cifra de uma direção: nonce = 4 bytes zero + contador u64 big-endian, sem dados associados. */
internal class Aead(key: ByteArray) {
    private val keySpec = SecretKeySpec(key, "AES")
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val nonce = ByteArray(12)
    private var ctr = 0L

    private fun nextNonce(): GCMParameterSpec {
        var c = ctr++
        for (i in 11 downTo 4) {
            nonce[i] = c.toByte()
            c = c ushr 8
        }
        return GCMParameterSpec(TAG_LEN * 8, nonce)
    }

    /** Cifra `src[off, off+len)` em `dst[dstOff...]` (texto cifrado + etiqueta); devolve len + 16. */
    fun seal(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int): Int {
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, nextNonce())
        return cipher.doFinal(src, off, len, dst, dstOff)
    }

    /** Decifra texto cifrado + etiqueta; devolve o tamanho do texto claro. */
    fun open(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int): Int {
        cipher.init(Cipher.DECRYPT_MODE, keySpec, nextNonce())
        try {
            return cipher.doFinal(src, off, len, dst, dstOff)
        } catch (e: java.security.GeneralSecurityException) {
            throw AuthException()
        }
    }
}

class AuthException : IOException("falha de autenticação do quadro")

/** Tempo limite sem nenhum byte de um quadro novo (a pista só está ociosa; o fluxo continua íntegro). */
class IdleTimeout : SocketTimeoutException("pista ociosa")

/** Escritor de quadros cifrados: `[u32 BE n][tipo u8][corpo][etiqueta 16]`, n = 1 + corpo + 16. */
internal class SecureWriter(private val out: OutputStream, key: ByteArray) {
    private val aead = Aead(key)
    private var plain = ByteArray(300 * 1024)
    private var frame = ByteArray(300 * 1024 + 32)

    @Synchronized
    fun send(type: Int, body: ByteArray, off: Int = 0, len: Int = body.size) {
        if (1 + len > MAX_PLAIN) throw ProtocolException("quadro grande demais")
        if (plain.size < 1 + len) {
            plain = ByteArray(1 + len)
            frame = ByteArray(1 + len + TAG_LEN + 4)
        }
        plain[0] = type.toByte()
        System.arraycopy(body, off, plain, 1, len)
        val n = aead.seal(plain, 0, 1 + len, frame, 4)
        frame[0] = (n ushr 24).toByte()
        frame[1] = (n ushr 16).toByte()
        frame[2] = (n ushr 8).toByte()
        frame[3] = n.toByte()
        out.write(frame, 0, 4 + n)
        // Quadros grandes (a oferta) não ficam retidos na memória.
        if (plain.size > 1 shl 20) {
            plain = ByteArray(300 * 1024)
            frame = ByteArray(300 * 1024 + 32)
        }
    }
}

/** Leitor de quadros cifrados. O corpo devolvido por [recv] vale até a próxima chamada. */
internal class SecureReader(private val inp: InputStream, key: ByteArray) {
    private val aead = Aead(key)
    private var cipherBuf = ByteArray(300 * 1024)
    private var plainBuf = ByteArray(300 * 1024)
    private val header = ByteArray(4)

    var buf: ByteArray = plainBuf
        private set
    var off = 0
        private set
    var len = 0
        private set

    private fun fill(b: ByteArray, o: Int, n: Int, atStart: Boolean) {
        var got = 0
        var stalledSince = 0L
        while (got < n) {
            val r = try {
                inp.read(b, o + got, n - got)
            } catch (e: SocketTimeoutException) {
                if (got == 0 && atStart) throw IdleTimeout()
                // Meio de um quadro: o fluxo não pode perder bytes; espera até 60 s sem nenhum byte.
                val now = System.nanoTime()
                if (stalledSince == 0L) stalledSince = now
                if (now - stalledSince > 60_000_000_000L) throw e
                continue
            }
            if (r < 0) throw EOFException("conexão encerrada")
            got += r
            stalledSince = 0L
        }
    }

    /** Lê um quadro e devolve o tipo; o corpo está em [buf], [off], [len]. */
    fun recv(): Int {
        fill(header, 0, 4, true)
        val total = ((header[0].toInt() and 0xff) shl 24) or ((header[1].toInt() and 0xff) shl 16) or
            ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
        if (total < 1 + TAG_LEN || total > MAX_PLAIN + TAG_LEN) throw ProtocolException("tamanho de quadro inválido")
        if (cipherBuf.size < total) {
            cipherBuf = ByteArray(total)
            plainBuf = ByteArray(total)
        }
        fill(cipherBuf, 0, total, false)
        val n = aead.open(cipherBuf, 0, total, plainBuf, 0)
        val type = plainBuf[0].toInt() and 0xff
        buf = plainBuf
        off = 1
        len = n - 1
        if (cipherBuf.size > 1 shl 20) {
            // Depois de um quadro grande (oferta), devolve a memória; o conteúdo atual segue válido.
            val keep = plainBuf
            cipherBuf = ByteArray(300 * 1024)
            plainBuf = ByteArray(300 * 1024)
            buf = keep
        }
        return type
    }
}
