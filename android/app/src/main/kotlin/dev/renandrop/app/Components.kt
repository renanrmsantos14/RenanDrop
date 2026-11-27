package dev.renandrop.app

import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class RenanDropApp : Application() {
    lateinit var engine: Engine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = Engine(this)
    }
}

val Context.engine: Engine get() = (applicationContext as RenanDropApp).engine

/** Mantém o nó vivo em segundo plano (tipo `connectedDevice`: sem limite de horas e permitido na inicialização). */
class NodeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = applicationContext.engine
        if (intent?.action == ACTION_STOP) {
            active = false
            engine.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val n = engine.notifier.serviceNotification(engine.snapshot)
        try {
            startForeground(Notifier.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        active = true
        engine.start()
        return START_STICKY
    }

    override fun onDestroy() {
        active = false
        applicationContext.engine.stop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "dev.renandrop.app.STOP"

        /**
         * O acesso a arquivos escolhidos ou compartilhados vale só enquanto a tela existe. Repassar os URIs ao serviço
         * (que vive durante os envios) estende a permissão pelo tempo de vida dele.
         */
        fun keepGrants(ctx: Context, uris: List<android.net.Uri>) {
            if (uris.isEmpty()) return
            try {
                val clip = android.content.ClipData.newRawUri("RenanDrop", uris.first())
                uris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
                ctx.startForegroundService(
                    Intent(ctx, NodeService::class.java).apply {
                        clipData = clip
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                )
            } catch (_: Exception) {
            }
        }

        @Volatile
        var active = false

        /** Liga o serviço; falha em silêncio se o sistema não permitir agora (o app tenta de novo ao abrir). */
        fun start(ctx: Context): Boolean = try {
            ctx.startForegroundService(Intent(ctx, NodeService::class.java))
            true
        } catch (_: Exception) {
            false
        }
    }
}

/** Liga o serviço quando o aparelho liga ou o app é atualizado, se a opção estiver ativa. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (SettingsStore(context).load().boot) NodeService.start(context)
    }
}

/** Botões Aceitar e Recusar das notificações de pedido. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tid = intent.getStringExtra(EXTRA_TID) ?: return
        when (intent.action) {
            ACCEPT -> context.engine.respondOffer(tid, true)
            DECLINE -> context.engine.respondOffer(tid, false)
        }
    }

    companion object {
        const val ACCEPT = "dev.renandrop.app.ACCEPT"
        const val DECLINE = "dev.renandrop.app.DECLINE"
        const val EXTRA_TID = "tid"
    }
}

/** Bloco "RenanDrop" das Configurações rápidas: abre a escolha de arquivos; aceso quando há aparelho online. */
class QuickTile : TileService() {
    private val listener: (Snapshot) -> Unit = { render(it) }

    override fun onStartListening() {
        super.onStartListening()
        applicationContext.engine.addListener(listener)
    }

    override fun onStopListening() {
        applicationContext.engine.removeListener(listener)
        super.onStopListening()
    }

    private fun render(s: Snapshot) {
        val t = qsTile ?: return
        val on = s.anyOnline
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "RenanDrop"
        t.subtitle = when {
            on -> "${s.peers.count { it.online }} online"
            !s.running -> "Parado"
            else -> "Nenhum online"
        }
        t.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        NodeService.start(applicationContext)
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_SEND, true)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 7, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
