package dev.renandrop.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.renandrop.protocol.Node
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress

/** Aparelho visto na rede local pelo mDNS (`_renandrop._tcp`). */
class Found(val id: String, val name: String, val addrs: List<InetSocketAddress>)

/**
 * Registra este aparelho e procura os outros por `NsdManager`, com `MulticastLock` ligado enquanto roda
 * (sem ele, alguns aparelhos não recebem os pacotes mDNS). Reinicia a busca quando a rede padrão muda.
 */
class Discovery(
    private val ctx: Context,
    private val node: Node,
    private val onFound: (Found) -> Unit,
    private val onLost: (String) -> Unit,
    private val onNetwork: () -> Unit,
) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())
    private var lock: WifiManager.MulticastLock? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var running = false
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var resolveToken = 0
    private val restart = Runnable { stopNsd(); startNsd() }

    fun start() {
        if (running) return
        running = true
        lock = wifi.createMulticastLock("renandrop-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
        startNsd()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = networkChanged()
            override fun onLost(network: Network) = networkChanged()
        }
        netCallback = cb
        try {
            cm.registerDefaultNetworkCallback(cb)
        } catch (_: Exception) {
            netCallback = null
        }
    }

    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(restart)
        netCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        netCallback = null
        stopNsd()
        try {
            lock?.release()
        } catch (_: Exception) {
        }
        lock = null
    }

    /** Procura de novo (botão de atualizar). */
    fun rescan() = main.post {
        if (running) {
            stopNsd()
            startNsd()
        }
    }

    private fun networkChanged() {
        main.removeCallbacks(restart)
        main.postDelayed(restart, 1500)
        onNetwork()
    }

    private fun startNsd() {
        if (!running) return
        val info = NsdServiceInfo().apply {
            serviceName = node.id
            serviceType = TYPE
            port = node.port
            setAttribute("id", node.id)
            setAttribute("n", node.deviceName)
            setAttribute("v", "1")
        }
        val reg = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) = retryLater()
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
        }
        registration = reg
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg)
        } catch (_: Exception) {
            registration = null
        }
        val disc = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, code: Int) = retryLater()
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                if (s.serviceName == node.id) return
                main.post { enqueue(s) }
            }

            override fun onServiceLost(s: NsdServiceInfo) {
                if (s.serviceName != node.id) onLost(s.serviceName)
            }
        }
        discovery = disc
        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, disc)
        } catch (_: Exception) {
            discovery = null
        }
    }

    /** Registro ou busca falhou (limite do sistema, rede mudando): tenta de novo em 5 s, sem acumular tentativas. */
    private fun retryLater() {
        main.removeCallbacks(restart)
        main.postDelayed(restart, 5000)
    }

    private fun stopNsd() {
        main.post {
            queue.clear()
            resolving = false
        }
        registration?.let { try { nsd.unregisterService(it) } catch (_: Exception) {} }
        discovery?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        registration = null
        discovery = null
    }

    // Antes do Android 14 só uma resolução pode estar ativa por vez: fila serial.
    private fun enqueue(s: NsdServiceInfo) {
        if (queue.none { it.serviceName == s.serviceName }) queue.addLast(s)
        next()
    }

    private fun next() {
        if (resolving) return
        val s = queue.removeFirstOrNull() ?: return
        resolving = true
        // Alguns aparelhos nunca chamam o retorno da resolução: sem prazo a fila ficaria parada para sempre.
        val token = ++resolveToken
        main.postDelayed({
            if (resolving && resolveToken == token) {
                resolving = false
                next()
            }
        }, 5000)
        try {
            @Suppress("DEPRECATION")
            nsd.resolveService(
                s,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, code: Int) {
                        done()
                    }

                    override fun onServiceResolved(i: NsdServiceInfo) {
                        resolved(i)
                        done()
                    }

                    private fun done() {
                        main.post {
                            if (resolveToken == token) {
                                resolving = false
                                next()
                            }
                        }
                    }
                },
            )
        } catch (_: Exception) {
            resolving = false
        }
    }

    private fun resolved(i: NsdServiceInfo) {
        val txt = i.attributes
        fun attr(k: String) = txt?.get(k)?.toString(Charsets.UTF_8)
        val id = (attr("id") ?: i.serviceName).lowercase()
        if (id.length != 32 || id == node.id) return
        val hosts: List<InetAddress> = if (Build.VERSION.SDK_INT >= 34) i.hostAddresses else listOfNotNull(@Suppress("DEPRECATION") i.host)
        val addrs = hosts
            .filter { !(it is Inet6Address && it.isLinkLocalAddress) }
            .map { InetSocketAddress(it, i.port) }
        if (addrs.isNotEmpty()) onFound(Found(id, (attr("n") ?: "Aparelho").take(64), addrs))
    }

    private companion object {
        const val TYPE = "_renandrop._tcp"
    }
}
