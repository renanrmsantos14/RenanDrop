package dev.renandrop.app

import dev.renandrop.protocol.DEFAULT_PORT
import dev.renandrop.protocol.PORT_SPAN
import dev.renandrop.protocol.Route
import java.net.InetSocketAddress

private fun rank(r: Route): Int = when (r) {
    Route.LAN -> 0
    Route.LOCAL -> 1
    Route.TAILNET -> 2
    Route.INTERNET -> 3
}

/**
 * Ordem de tentativa dos endereços de um aparelho: rede local primeiro, tailnet depois.
 * `reachable` (último endereço que respondeu) vai à frente para não esperar o tempo de conexão de endereços velhos.
 * Sem Tailscale ligado nas configurações, endereços da tailnet são descartados.
 */
fun orderAddresses(addrs: List<InetSocketAddress>, tailscale: Boolean, reachable: InetSocketAddress? = null): List<InetSocketAddress> {
    val kept = addrs.distinct().filter { tailscale || Route.of(it.address) != Route.TAILNET }
    val sorted = kept.sortedBy { rank(Route.of(it.address)) }
    return if (reachable != null && reachable in sorted) listOf(reachable) + sorted.filter { it != reachable } else sorted
}

/** Destino digitado em "adicionar por nome ou IP". `port == null` quando não informada. */
data class Target(val host: String, val port: Int?)

fun parseTarget(text: String): Target? {
    val t = text.trim()
    if (t.isEmpty() || t.any { it.isWhitespace() } || t.contains("://") || t.contains('/')) return null
    val host: String
    var port: Int? = null
    if (t.startsWith("[")) {
        val end = t.indexOf(']')
        if (end < 2) return null
        host = t.substring(1, end)
        val rest = t.substring(end + 1)
        if (rest.isNotEmpty()) {
            if (!rest.startsWith(":")) return null
            port = rest.substring(1).toIntOrNull() ?: return null
        }
    } else if (t.count { it == ':' } == 1) {
        host = t.substringBefore(':')
        port = t.substringAfter(':').toIntOrNull() ?: return null
    } else {
        host = t
    }
    if (host.isEmpty() || !host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' || it == '_' }) return null
    if (port != null && port !in 1..65535) return null
    return Target(host.trimEnd('.'), port)
}

/** Portas a sondar: a informada, ou as 5 em que o serviço pode estar escutando. */
fun probePorts(port: Int?): List<Int> = if (port != null) listOf(port) else (DEFAULT_PORT until DEFAULT_PORT + PORT_SPAN).toList()
