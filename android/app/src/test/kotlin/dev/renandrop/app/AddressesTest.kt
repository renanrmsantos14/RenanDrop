package dev.renandrop.app

import dev.renandrop.protocol.DEFAULT_PORT
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AddressesTest {
    private fun a(ip: String, port: Int = 47821) = InetSocketAddress(InetAddress.getByName(ip), port)

    @Test
    fun redeLocalAntesDaTailnet() {
        val out = orderAddresses(listOf(a("100.101.102.103"), a("192.168.0.20")), tailscale = true)
        assertEquals(listOf(a("192.168.0.20"), a("100.101.102.103")), out)
    }

    @Test
    fun semTailscaleDescartaEnderecosDaTailnet() {
        val out = orderAddresses(listOf(a("100.101.102.103"), a("192.168.0.20")), tailscale = false)
        assertEquals(listOf(a("192.168.0.20")), out)
    }

    @Test
    fun ultimoEnderecoQueRespondeuVaiNaFrente() {
        val out = orderAddresses(listOf(a("192.168.0.20"), a("192.168.0.30"), a("100.1.2.3")), true, reachable = a("192.168.0.30"))
        assertEquals(a("192.168.0.30"), out.first())
        assertEquals(3, out.size)
    }

    @Test
    fun enderecosRepetidosSaemUmaVez() {
        assertEquals(1, orderAddresses(listOf(a("192.168.0.20"), a("192.168.0.20")), true).size)
    }

    @Test
    fun destinoDigitado() {
        assertEquals(Target("100.101.102.103", null), parseTarget(" 100.101.102.103 "))
        assertEquals(Target("meu-pc.tailnet.ts.net", 47822), parseTarget("meu-pc.tailnet.ts.net:47822"))
        assertEquals(Target("fd7a::1", 47821), parseTarget("[fd7a::1]:47821"))
        assertEquals(Target("fd7a::1", null), parseTarget("fd7a::1"))
        assertNull(parseTarget(""))
        assertNull(parseTarget("http://x"))
        assertNull(parseTarget("a b"))
        assertNull(parseTarget("host:99999"))
        assertNull(parseTarget("host:abc"))
    }

    @Test
    fun portasASondar() {
        assertEquals(listOf(5000), probePorts(5000))
        assertEquals(5, probePorts(null).size)
        assertEquals(DEFAULT_PORT, probePorts(null).first())
    }
}
