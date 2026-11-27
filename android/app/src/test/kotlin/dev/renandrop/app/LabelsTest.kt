package dev.renandrop.app

import dev.renandrop.protocol.Direction
import dev.renandrop.protocol.Route
import dev.renandrop.protocol.TransferInfo
import dev.renandrop.protocol.TransferState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabelsTest {
    private fun info(
        state: TransferState,
        dir: Direction = Direction.SEND,
        bytes: Long = 0,
        total: Long = 0,
        speed: Double = 0.0,
        paths: List<String> = emptyList(),
        id: String = "t1",
        summary: String = "foto.jpg",
    ) = TransferInfo(id, dir, "p1", "Pixel", state, summary, 1, bytes, total, speed, null, null, 0, Route.LAN, paths)

    @Test
    fun estadosEmPortugues() {
        assertEquals("Na fila", stateLabel(TransferState.QUEUED))
        assertEquals("Aguardando o aceite", stateLabel(TransferState.WAITING))
        assertEquals("Concluído", stateLabel(TransferState.DONE))
        assertEquals("Rede local", routeLabel(Route.LAN))
        assertEquals("Tailscale", routeLabel(Route.TAILNET))
        assertEquals("", routeLabel(null))
    }

    @Test
    fun tailscaleSemEleOAppFuncionaSoNaRedeLocal() {
        assertEquals(TailscaleStatus.OFF, tailscaleStatus(false, true, true))
        assertEquals(TailscaleStatus.OK, tailscaleStatus(true, true, true))
        assertEquals(TailscaleStatus.DISCONNECTED, tailscaleStatus(true, false, true))
        assertEquals(TailscaleStatus.MISSING, tailscaleStatus(true, false, false))
        assertTrue(tailscaleText(TailscaleStatus.MISSING).contains("só na rede local"))
    }

    @Test
    fun percentualEDetalhes() {
        assertEquals(25, percentOf(info(TransferState.SENDING, bytes = 25, total = 100)))
        assertEquals(100, percentOf(info(TransferState.DONE)))
        val parts = metaParts(info(TransferState.SENDING, bytes = 1024, total = 2048, speed = 1024.0))
        assertEquals(listOf("Enviando", "1,0 KB de 2,0 KB", "1,0 KB/s"), parts)
        assertEquals(listOf("Concluído"), metaParts(info(TransferState.DONE)))
    }

    @Test
    fun resumoDaFila() {
        assertNull(queueSummary(listOf(info(TransferState.DONE))))
        val one = assertNotNull(queueSummary(listOf(info(TransferState.SENDING, bytes = 50, total = 100, speed = 2048.0))))
        assertEquals("Enviando foto.jpg", one.title)
        assertEquals("Para Pixel · 50% · 2,0 KB/s", one.text)
        assertEquals(50, one.percent)
        val two = assertNotNull(queueSummary(listOf(info(TransferState.SENDING, id = "a"), info(TransferState.QUEUED, id = "b"))))
        assertEquals("2 transferências em andamento", two.title)
    }

    @Test
    fun soRecebimentoConcluidoPrecisaCopiar() {
        assertTrue(needsExport(info(TransferState.DONE, Direction.RECV, paths = listOf("/x/a"))))
        assertFalse(needsExport(info(TransferState.DONE, Direction.SEND, paths = listOf("/x/a"))))
        assertFalse(needsExport(info(TransferState.RECEIVING, Direction.RECV, paths = listOf("/x/a"))))
        assertFalse(needsExport(info(TransferState.DONE, Direction.RECV)))
    }
}
