package dev.renandrop.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnitTest {
    @Test
    fun hkdfRfc5869Caso1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = hkdf(salt, ikm, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.hex(),
        )
    }

    @Test
    fun chavePublicaDerivadaIgualAoJdk() {
        repeat(20) {
            val kp = Identity.pair()
            val d = P256.fixed((kp.private as ECPrivateKey).s)
            val w = (kp.public as ECPublicKey).w
            val expected = byteArrayOf(4) + P256.fixed(w.affineX) + P256.fixed(w.affineY)
            assertContentEquals(expected, Identity.fromSecret(d).publicKey)
        }
    }

    @Test
    fun ecdhSimetrico() {
        val a = Identity.generate()
        val b = Identity.generate()
        assertContentEquals(a.ecdh(b.publicKey), b.ecdh(a.publicKey))
        assertEquals(32, a.ecdh(b.publicKey).size)
        assertEquals(32, a.idHex.length)
    }

    @Test
    fun codigoSas() {
        assertEquals("003906", sasDigits(byteArrayOf(0, 0, 0x0f, 0x42, 9, 9)))
        assertEquals("967295", sasDigits(byteArrayOf(-1, -1, -1, -1)))
    }

    @Test
    fun jsonIdaEVolta() {
        val v = linkedMapOf(
            "s" to "a\"b\\c\n\tç😀",
            "n" to 12345678901234L,
            "neg" to -5L,
            "b" to true,
            "z" to null,
            "l" to listOf(1L, listOf(2L, 3L), linkedMapOf("k" to "v")),
        )
        val back = Json.parse(Json.write(v)).obj()
        assertEquals("a\"b\\c\n\tç😀", back.str("s"))
        assertEquals(12345678901234L, back.long("n"))
        assertEquals(-5L, back.long("neg"))
        assertEquals(true, back.bool("b"))
        assertNull(back["z"])
        assertEquals(3, back.list("l").size)
        assertEquals("😀", Json.parse("\"\\ud83d\\ude00\"".toByteArray()))
        assertFailsWith<ProtocolException> { Json.parse("{\"a\":".toByteArray()) }
    }

    @Test
    fun caminhosRelativos() {
        assertEquals(listOf("a", "b.txt"), sanitizeRel("a/b.txt"))
        assertNull(sanitizeRel("../x"))
        assertNull(sanitizeRel("a/../x"))
        assertNull(sanitizeRel("/x"))
        assertNull(sanitizeRel("a//b"))
        assertNull(sanitizeRel(""))
        assertNull(sanitizeRel("a\u0000b"))
        assertEquals(listOf("_CON"), sanitizeRel("CON"))
        assertEquals(listOf("a_b"), sanitizeRel("a:b"))
        assertEquals(listOf("foto"), sanitizeRel("foto. "))
        val long = "x".repeat(300)
        assertTrue(sanitizeRel(long)!![0].toByteArray().size <= 200)
    }

    @Test
    fun rotas() {
        fun r(s: String) = Route.of(java.net.InetAddress.getByName(s))
        assertEquals(Route.LOCAL, r("127.0.0.1"))
        assertEquals(Route.LAN, r("192.168.1.5"))
        assertEquals(Route.LAN, r("10.0.0.2"))
        assertEquals(Route.TAILNET, r("100.101.2.3"))
        assertEquals(Route.INTERNET, r("8.8.8.8"))
        assertEquals(Route.TAILNET, r("fd7a:115c:a1e0::1"))
    }

    @Test
    fun quadrosCifradosIdaEVolta() {
        val key = randomBytes(32)
        val out = ByteArrayOutputStream()
        val w = SecureWriter(out, key)
        val big = ByteArray(200_000) { (it * 7).toByte() }
        w.send(T_DATA, big)
        w.send(T_JSON, "oi".toByteArray())
        val bytes = out.toByteArray()
        val r = SecureReader(ByteArrayInputStream(bytes), key).also { it.maxTotal = MAX_PLAIN + TAG_LEN }
        assertEquals(T_DATA, r.recv())
        assertContentEquals(big, r.buf.copyOfRange(r.off, r.off + r.len))
        assertEquals(T_JSON, r.recv())
        assertEquals("oi", String(r.buf, r.off, r.len))
        // Adulterar um byte do corpo falha na autenticação.
        val bad = bytes.copyOf()
        bad[100] = (bad[100].toInt() xor 1).toByte()
        assertFailsWith<AuthException> { SecureReader(ByteArrayInputStream(bad), key).also { it.maxTotal = MAX_PLAIN + TAG_LEN }.recv() }
        // Chave errada também.
        assertFailsWith<AuthException> { SecureReader(ByteArrayInputStream(bytes), randomBytes(32)).also { it.maxTotal = MAX_PLAIN + TAG_LEN }.recv() }
    }

    @Test
    fun idDeTransferenciaEstavel() {
        val items = listOf(Item(0, "a/x", 5, 0, false), Item(1, "a/y", 6, 0, false))
        val a = Identity.generate().id
        val b = Identity.generate().id
        assertEquals(transferId(a, b, items), transferId(a, b, items))
        assertTrue(transferId(a, b, items) != transferId(b, a, items))
    }

    @Test
    fun mensagensIdaEVolta() {
        val msgs = listOf(
            Msg.Accept(listOf(Range(0, 0, 3))),
            Msg.SegEnd("ab".repeat(32)),
            Msg.Complete,
            Msg.Fail("x"),
        )
        for (m in msgs) {
            val b = m.encode()
            assertEquals(m.encode().toList(), decodeMsg(b, 0, b.size).encode().toList())
        }
    }

    /** Dois nós Kotlin conversam por loopback: pareamento, envio de arquivo e pasta, recusa sem pareamento. */
    @Test
    fun loopbackKotlinComKotlin() = withTmp("kk") { tmp ->
        val ea = KEvents()
        val eb = KEvents()
        val a = Node.start(MemoryStore(), NodeConfig("A", java.io.File(tmp, "ra"), port = 0, lanes = 3), ea.listener)
        val b = Node.start(MemoryStore(), NodeConfig("B", java.io.File(tmp, "rb"), port = 0, autoAccept = true), eb.listener)
        try {
            // Sondagem sem pareamento.
            val probe = assertNotNull(a.probe(InetSocketAddress(LOOPBACK, b.port)))
            assertEquals(b.id, probe.id)
            assertEquals("B", probe.name)

            a.pair(listOf(InetSocketAddress(LOOPBACK, b.port)))
            val pa = ea.await<NodeEvent.PairPrompt>()
            val pb = eb.await<NodeEvent.PairPrompt>()
            assertEquals(pa.code, pb.code)
            assertEquals(6, pa.code.length)
            assertTrue(pa.outgoing && !pb.outgoing)
            a.confirmPair(pa.reqId, true)
            b.confirmPair(pb.reqId, true)
            assertTrue(ea.await<NodeEvent.PairDone>().ok)
            assertTrue(eb.await<NodeEvent.PairDone>().ok)
            assertEquals(1, a.peers().size)
            assertEquals(1, b.peers().size)

            val src = java.io.File(tmp, "src")
            writeRandom(java.io.File(src, "Pasta/um.bin"), 9_000_000, 1)
            writeRandom(java.io.File(src, "Pasta/sub/dois.bin"), 17_000_001, 2)
            java.io.File(src, "Pasta/vazia").mkdirs()
            writeRandom(java.io.File(src, "solto.txt"), 12345, 3)
            a.setAddresses(b.id, listOf(InetSocketAddress(LOOPBACK, b.port)))
            val jid = a.sendFiles(b.id, listOf(java.io.File(src, "Pasta"), java.io.File(src, "solto.txt")))
            val done = ea.awaitTransfer(60_000) { it.id == jid && it.finished }
            assertEquals(TransferState.DONE, done.state, done.error)
            val got = eb.awaitTransfer(30_000) { it.direction == Direction.RECV && it.finished }
            assertEquals(TransferState.DONE, got.state, got.error)
            val rb = java.io.File(tmp, "rb")
            assertEquals(treeHashes(java.io.File(src, "Pasta")), treeHashes(java.io.File(rb, "Pasta")))
            assertEquals(sha256File(java.io.File(src, "solto.txt")), sha256File(java.io.File(rb, "solto.txt")))
            assertTrue(java.io.File(rb, "Pasta/vazia").isDirectory)
            assertTrue(!java.io.File(rb, ".renandrop-partial").exists())

            // Depois de desfazer o pareamento de um lado, o envio é recusado sem nova tentativa.
            b.unpair(a.id)
            val j2 = a.sendFiles(b.id, listOf(java.io.File(src, "solto.txt")))
            val r = ea.awaitTransfer(30_000) { it.id == j2 && it.finished }
            assertEquals(TransferState.FAILED, r.state)
            assertTrue(TransferState.RETRYING !in ea.states(j2))
        } finally {
            a.close()
            b.close()
        }
    }
}
