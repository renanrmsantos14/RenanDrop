package dev.renandrop.protocol

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MIB = 1024L * 1024L

/**
 * Interoperabilidade com o núcleo Rust (`target/release/renandrop-cli`), por TCP em loopback.
 * Não há `@Ignore` nem pulo: sem o binário o teste falha.
 */
class InteropTest {
    private class Fx(val k: Node, val ke: KEvents, val r: RustCli)

    private fun kotlinNode(dir: File, name: String, ev: KEvents, lanes: Int = 4, store: Store = MemoryStore(), auto: Boolean = true) =
        Node.start(store, NodeConfig(name, File(dir, "k-recv"), port = 0, lanes = lanes, autoAccept = auto), ev.listener)

    /** Kotlin inicia o pareamento com o Rust; confere que os dois mostram o mesmo código de 6 dígitos. */
    private fun pairFromKotlin(k: Node, ke: KEvents, r: RustCli): String {
        k.pair(listOf(r.addr))
        val kp = ke.await<NodeEvent.PairPrompt>()
        val rp = r.await(20_000, "pair_prompt") { it["type"] == "pair_prompt" }
        assertEquals(kp.code, rp["code"], "os dois lados devem mostrar o mesmo código")
        assertTrue(Regex("\\d{6}").matches(kp.code))
        assertTrue(kp.outgoing)
        assertEquals(false, rp["outgoing"])
        assertEquals("Kotlin", rp["peer_name"])
        k.confirmPair(kp.reqId, true)
        r.cmd("yes")
        assertTrue(ke.await<NodeEvent.PairDone>().ok)
        assertEquals(true, r.await(20_000, "pair_done") { it["type"] == "pair_done" }["ok"])
        k.setAddresses(r.id, listOf(r.addr))
        r.cmd("addr ${k.id} $LOOPBACK:${k.port}")
        return r.id
    }

    private fun fixture(tmp: File, lanes: Int = 4, store: Store = MemoryStore(), auto: Boolean = true): Fx {
        val ke = KEvents()
        val k = kotlinNode(tmp, "Kotlin", ke, lanes, store, auto)
        val r = RustCli("Rust", File(tmp, "rust-profile"), File(tmp, "rust-recv"))
        pairFromKotlin(k, ke, r)
        return Fx(k, ke, r)
    }

    private fun pairedLeaves(f: Fx, body: () -> Unit) {
        try {
            body()
        } finally {
            f.k.close()
            f.r.close()
        }
    }

    private fun makeSource(tmp: File): File {
        val src = File(tmp, "src")
        writeRandom(File(src, "grande.bin"), 50 * MIB, 11)
        writeRandom(File(src, "Fotos/a.jpg"), 3 * MIB + 17, 12)
        writeRandom(File(src, "Fotos/2024/b.bin"), 9 * MIB + 5, 13)
        writeRandom(File(src, "Fotos/2024/minúscula ç.txt"), 33, 14)
        writeRandom(File(src, "Fotos/zero.dat"), 0, 15)
        File(src, "Fotos/pasta vazia").mkdirs()
        return src
    }

    @Test
    fun a_pareamentoComMesmoCodigo() = withTmp("pair") { tmp ->
        // Kotlin inicia
        pairedLeaves(fixture(tmp)) {}
        // Rust inicia
        val ke = KEvents()
        val k = kotlinNode(File(tmp, "2"), "Kotlin", ke)
        val r = RustCli("Rust2", File(tmp, "rust2-profile"), File(tmp, "rust2-recv"))
        try {
            r.cmd("pair $LOOPBACK:${k.port}")
            val kp = ke.await<NodeEvent.PairPrompt>()
            val rp = r.await(20_000, "pair_prompt") { it["type"] == "pair_prompt" }
            assertEquals(kp.code, rp["code"])
            assertTrue(!kp.outgoing)
            assertEquals(true, rp["outgoing"])
            assertEquals("Rust2", kp.peerName)
            k.confirmPair(kp.reqId, true)
            r.cmd("yes")
            assertTrue(ke.await<NodeEvent.PairDone>().ok)
            assertEquals(true, r.await(20_000, "pair_done") { it["type"] == "pair_done" }["ok"])
            assertEquals(r.id, k.peers().single().id)
            assertEquals("Rust2", k.peers().single().name)
        } finally {
            k.close()
            r.close()
        }
        // Recusa de um dos lados: ninguém fica pareado.
        val ke3 = KEvents()
        val k3 = kotlinNode(File(tmp, "3"), "Kotlin", ke3)
        val r3 = RustCli("Rust3", File(tmp, "rust3-profile"), File(tmp, "rust3-recv"))
        try {
            k3.pair(listOf(r3.addr))
            val kp = ke3.await<NodeEvent.PairPrompt>()
            r3.await(20_000, "pair_prompt") { it["type"] == "pair_prompt" }
            k3.confirmPair(kp.reqId, true)
            r3.cmd("no")
            assertTrue(!ke3.await<NodeEvent.PairDone>().ok)
            assertTrue(k3.peers().isEmpty())
        } finally {
            k3.close()
            r3.close()
        }
    }

    @Test
    fun b_kotlinEnviaParaRust() = withTmp("k2r") { tmp ->
        val f = fixture(tmp)
        pairedLeaves(f) {
            val src = makeSource(tmp)
            val jid = f.k.sendFiles(f.r.id, listOf(File(src, "grande.bin"), File(src, "Fotos")))
            val sent = f.ke.awaitTransfer(120_000) { it.id == jid && it.finished }
            assertEquals(TransferState.DONE, sent.state, sent.error)
            val got = f.r.awaitTransfer(60_000, "recepção concluída") { it["dir"] == "recv" && it["finished"] == true }
            assertEquals("done", got["state"], got["error"]?.toString())
            val dst = File(tmp, "rust-recv")
            assertEquals(sha256File(File(src, "grande.bin")), sha256File(File(dst, "grande.bin")))
            assertEquals(treeHashes(File(src, "Fotos")), treeHashes(File(dst, "Fotos")))
            assertTrue(File(dst, "Fotos/pasta vazia").isDirectory)
            assertEquals(50 * MIB, File(dst, "grande.bin").length())
            assertEquals(Route.LOCAL, sent.route)
        }
    }

    @Test
    fun c_rustEnviaParaKotlin() = withTmp("r2k") { tmp ->
        val f = fixture(tmp)
        pairedLeaves(f) {
            val src = makeSource(tmp)
            f.r.cmd("send ${f.k.id} ${File(src, "grande.bin").path} ${File(src, "Fotos").path}")
            val got = f.ke.awaitTransfer(120_000) { it.direction == Direction.RECV && it.finished }
            assertEquals(TransferState.DONE, got.state, got.error)
            f.r.awaitTransfer(60_000, "envio concluído") { it["dir"] == "send" && it["finished"] == true }
            val dst = File(tmp, "k-recv")
            assertEquals(sha256File(File(src, "grande.bin")), sha256File(File(dst, "grande.bin")))
            assertEquals(treeHashes(File(src, "Fotos")), treeHashes(File(dst, "Fotos")))
            assertTrue(File(dst, "Fotos/pasta vazia").isDirectory)
            assertTrue(!File(dst, ".renandrop-partial").exists(), "sobras de recebimento parcial")
            assertEquals("Rust", got.peerName)
        }
    }

    @Test
    fun d1_rustNaoPareadoEhRecusadoPeloKotlin() = withTmp("unp1") { tmp ->
        val f = fixture(tmp)
        pairedLeaves(f) {
            f.k.unpair(f.r.id)
            val src = File(tmp, "src")
            writeRandom(File(src, "x.bin"), 1 * MIB, 21)
            f.r.cmd("send ${f.k.id} ${File(src, "x.bin").path}")
            val end = f.r.awaitTransfer(40_000, "falha") { it["dir"] == "send" && it["finished"] == true }
            assertEquals("failed", end["state"], end.toString())
            assertTrue(end["error"].toString().contains("reconhece"), "mensagem: ${end["error"]}")
            assertTrue(f.ke.all.filterIsInstance<NodeEvent.Offer>().isEmpty())
            assertTrue(!File(tmp, "k-recv/x.bin").exists())
        }
    }

    @Test
    fun d2_kotlinNaoPareadoEhRecusadoPeloRust() = withTmp("unp2") { tmp ->
        val f = fixture(tmp)
        val ke = f.ke
        val k = f.k
        var r = f.r
        try {
            val port = r.port
            val rid = r.id
            r.quit()
            File(tmp, "rust-profile/peers.json").delete()
            r = RustCli("Rust", File(tmp, "rust-profile"), File(tmp, "rust-recv"), port)
            assertEquals(rid, r.id, "a identidade do Rust deve sobreviver ao reinício")
            val src = File(tmp, "src")
            writeRandom(File(src, "x.bin"), 1 * MIB, 22)
            val jid = k.sendFiles(rid, listOf(File(src, "x.bin")))
            val end = ke.awaitTransfer(40_000) { it.id == jid && it.finished }
            assertEquals(TransferState.FAILED, end.state)
            assertTrue(end.error.orEmpty().contains("pare", ignoreCase = true), "mensagem: ${end.error}")
            assertTrue(TransferState.RETRYING !in ke.states(jid), "recusa de pareamento não deve ser repetida")
            assertTrue(!File(tmp, "rust-recv/x.bin").exists())
        } finally {
            k.close()
            r.close()
        }
    }

    private fun resumeKotlinToRust(tmp: File, lanes: Int) {
        val f = fixture(tmp, lanes)
        val proxy = CutProxy(f.r.addr)
        pairedLeaves(f) {
            proxy.use {
                val size = 100 * MIB
                val src = File(tmp, "src")
                writeRandom(File(src, "retoma.bin"), size, 31)
                f.k.setAddresses(f.r.id, listOf(proxy.addr))
                proxy.cutAfter = size * 70 / 100
                val jid = f.k.sendFiles(f.r.id, listOf(File(src, "retoma.bin")))
                val end = f.ke.awaitTransfer(180_000) { it.id == jid && it.finished }
                assertEquals(TransferState.DONE, end.state, end.error)
                assertEquals(1, proxy.cuts.get(), "a conexão deveria ter sido cortada uma vez")
                assertTrue(TransferState.RETRYING in f.ke.states(jid))
                val second = proxy.c2s.get()
                println("RETOMADA Kotlin->Rust (pistas=$lanes): 2a tentativa enviou ${second / MIB} MiB de ${size / MIB} MiB")
                assertTrue(second < size * 75 / 100, "a retomada reenviou demais: $second de $size")
                assertEquals(sha256File(File(src, "retoma.bin")), sha256File(File(tmp, "rust-recv/retoma.bin")))
            }
        }
    }

    @Test
    fun e1_retomadaKotlinParaRust() = withTmp("res1") { tmp -> resumeKotlinToRust(tmp, 2) }

    @Test
    fun e2_retomadaRustParaKotlin() = withTmp("res2") { tmp ->
        val f = fixture(tmp)
        val proxy = CutProxy(f.k.let { java.net.InetSocketAddress(LOOPBACK, it.port) })
        pairedLeaves(f) {
            proxy.use {
                val size = 100 * MIB
                val src = File(tmp, "src")
                writeRandom(File(src, "retoma.bin"), size, 32)
                f.r.cmd("addr ${f.k.id} $LOOPBACK:${proxy.port}")
                proxy.cutAfter = size * 70 / 100
                f.r.cmd("send ${f.k.id} ${File(src, "retoma.bin").path}")
                val got = f.ke.awaitTransfer(180_000) { it.direction == Direction.RECV && it.state == TransferState.DONE }
                assertEquals(TransferState.DONE, got.state, got.error)
                assertEquals(1, proxy.cuts.get(), "a conexão deveria ter sido cortada uma vez")
                val second = proxy.c2s.get()
                println("RETOMADA Rust->Kotlin: 2a tentativa enviou ${second / MIB} MiB de ${size / MIB} MiB")
                assertTrue(second < size * 75 / 100, "a retomada reenviou demais: $second de $size")
                assertEquals(sha256File(File(src, "retoma.bin")), sha256File(File(tmp, "k-recv/retoma.bin")))
                assertTrue(!File(tmp, "k-recv/.renandrop-partial").exists())
            }
        }
    }

    @Test
    fun e3_cancelarEnvioKotlin() = withTmp("cancel") { tmp ->
        val f = fixture(tmp)
        pairedLeaves(f) {
            val src = File(tmp, "src")
            writeRandom(File(src, "c.bin"), 200 * MIB, 41)
            val jid = f.k.sendFiles(f.r.id, listOf(File(src, "c.bin")))
            f.ke.awaitTransfer(60_000) { it.id == jid && it.state == TransferState.SENDING && it.bytes > 5 * MIB }
            f.k.cancel(jid)
            val end = f.ke.awaitTransfer(30_000) { it.id == jid && it.finished }
            assertEquals(TransferState.CANCELLED, end.state, end.error)
            f.r.awaitTransfer(30_000, "fim no Rust") { it["dir"] == "recv" && it["finished"] == true }
        }
    }

    @Test
    fun f_ofertaManualAceitaERecusada() = withTmp("manual") { tmp ->
        val f = fixture(tmp, auto = false)
        pairedLeaves(f) {
            val src = File(tmp, "src")
            writeRandom(File(src, "m.bin"), 3 * MIB, 61)
            // Recusa
            f.r.cmd("send ${f.k.id} ${File(src, "m.bin").path}")
            val o1 = f.ke.await<NodeEvent.Offer>()
            assertEquals(1, o1.files)
            assertEquals(3 * MIB, o1.total)
            assertEquals("m.bin", o1.summary)
            f.k.respondOffer(o1.tid, false)
            val r1 = f.r.awaitTransfer(30_000, "recusa") { it["dir"] == "send" && it["finished"] == true }
            assertEquals("declined", r1["state"], r1.toString())
            assertTrue(!File(tmp, "k-recv/m.bin").exists())
            // Aceite (o mesmo conteúdo gera a mesma oferta; outra oferta, outro id de evento)
            val before = f.ke.all.size
            f.r.cmd("send ${f.k.id} ${File(src, "m.bin").path}")
            val o2 = waitOffer(f.ke, before)
            f.k.respondOffer(o2.tid, true)
            val got = f.ke.awaitTransfer(60_000) { it.direction == Direction.RECV && it.state == TransferState.DONE }
            assertEquals(sha256File(File(src, "m.bin")), sha256File(File(tmp, "k-recv/m.bin")))
            assertEquals(got.id, o2.tid)
        }
    }

    private fun waitOffer(ke: KEvents, after: Int): NodeEvent.Offer {
        var o: NodeEvent.Offer? = null
        waitUntil(30_000, "nova oferta") { o = ke.all.drop(after).filterIsInstance<NodeEvent.Offer>().firstOrNull(); o != null }
        return o!!
    }

    private fun speed(tmp: File, kotlinSends: Boolean): Double {
        val f = fixture(tmp)
        var mbps = 0.0
        pairedLeaves(f) {
            val size = 300 * MIB
            val src = File(tmp, "src")
            writeRandom(File(src, "vel.bin"), size, 51)
            
            if (kotlinSends) {
                val jid = f.k.sendFiles(f.r.id, listOf(File(src, "vel.bin")))
                val t1 = System.nanoTime()
                val end = f.ke.awaitTransfer(300_000) { it.id == jid && it.finished }
                assertEquals(TransferState.DONE, end.state, end.error)
                f.r.awaitTransfer(60_000, "recepção concluída") { it["dir"] == "recv" && it["finished"] == true }
                mbps = mbps(size, System.nanoTime() - t1)
                assertEquals(sha256File(File(src, "vel.bin")), sha256File(File(tmp, "rust-recv/vel.bin")))
            } else {
                f.r.cmd("send ${f.k.id} ${File(src, "vel.bin").path}")
                val first = f.ke.awaitTransfer(60_000) { it.direction == Direction.RECV && it.state == TransferState.RECEIVING }
                val t1 = System.nanoTime()
                val got = f.ke.awaitTransfer(300_000) { it.direction == Direction.RECV && it.finished }
                assertEquals(TransferState.DONE, got.state, got.error)
                mbps = mbps(size, System.nanoTime() - t1)
                assertEquals(sha256File(File(src, "vel.bin")), sha256File(File(tmp, "k-recv/vel.bin")))
                assertTrue(first.total == size)
            }
        }
        return mbps
    }

    @Test
    fun z_velocidade300MB() {
        var k2r = 0.0
        var r2k = 0.0
        withTmp("spd1") { k2r = speed(it, true) }
        withTmp("spd2") { r2k = speed(it, false) }
        val line = "Kotlin->Rust: %.0f MB/s | Rust->Kotlin: %.0f MB/s (300 MiB, loopback, 4 pistas, SHA-256 por segmento de 8 MiB)".format(k2r, r2k)
        println("VELOCIDADE $line")
        File(reportsDir(), "velocidade.txt").writeText(line + "\n")
        assertTrue(k2r > 5 && r2k > 5, line)
    }
}
