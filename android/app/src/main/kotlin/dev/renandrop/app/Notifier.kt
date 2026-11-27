package dev.renandrop.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import dev.renandrop.protocol.Direction
import dev.renandrop.protocol.TransferState

/** Notificações do app: a fixa do serviço (discreta), pedidos que precisam de resposta e avisos de conclusão. */
class Notifier(private val ctx: Context) {
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private var lastService: String? = null

    init {
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Em segundo plano", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Mantém o RenanDrop pronto para receber arquivos."
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_ASK, "Pedidos", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Pedidos para receber arquivos e códigos de pareamento."
        })
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Transferências", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Avisos de envio e recebimento concluídos."
        })
    }

    private fun flags() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun openApp(tab: Int = 0): PendingIntent = PendingIntent.getActivity(
        ctx, 100 + tab,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_TAB, tab),
        flags(),
    )

    private fun builder(channel: String): Notification.Builder = Notification.Builder(ctx, channel)
        .setSmallIcon(R.drawable.ic_stat)
        .setColor(0xFF3F6BFF.toInt())

    // ───────────── Serviço ─────────────

    fun serviceNotification(s: Snapshot?): Notification {
        val q = s?.let { queueSummary(it.transfers) }
        val title: String
        val text: String
        if (q != null) {
            title = q.title
            text = q.text
        } else if (s?.error != null) {
            title = "RenanDrop não conseguiu iniciar"
            text = s.error
        } else {
            val on = s?.peers?.count { it.online } ?: 0
            title = "RenanDrop pronto"
            text = when {
                s == null || s.starting -> "Iniciando…"
                on == 0 -> "Nenhum aparelho online"
                on == 1 -> "1 aparelho online"
                else -> "$on aparelhos online"
            }
        }
        val b = builder(CH_SERVICE)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openApp(if (q != null) 1 else 0))
            .addAction(
                Notification.Action.Builder(
                    null, "Parar",
                    PendingIntent.getService(ctx, 1, Intent(ctx, NodeService::class.java).setAction(NodeService.ACTION_STOP), flags()),
                ).build(),
            )
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (q != null && q.percent > 0) b.setProgress(100, q.percent, false)
        return b.build()
    }

    /** Atualiza a notificação fixa (só com o serviço em primeiro plano e só quando o texto muda). */
    fun updateService(s: Snapshot) {
        if (!NodeService.active) return
        val q = queueSummary(s.transfers)
        val key = "${q?.title}|${q?.text}|${q?.percent}|${s.error}|${s.starting}|${s.peers.count { it.online }}"
        if (key == lastService) return
        lastService = key
        nm.notify(ID_SERVICE, serviceNotification(s))
    }

    // ───────────── Pedidos ─────────────

    fun offer(o: OfferView) {
        val n = builder(CH_ASK)
            .setContentTitle("${o.peerName} quer enviar ${Format.files(o.files)}")
            .setContentText("${o.summary} · ${Format.bytes(o.total)}")
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(openApp(1))
            .setTimeoutAfter(120_000)
            .setAutoCancel(true)
            .addAction(action("Aceitar", ActionReceiver.ACCEPT, o.tid))
            .addAction(action("Recusar", ActionReceiver.DECLINE, o.tid))
            .build()
        nm.notify(idFor(o.tid, 2000), n)
    }

    fun cancelOffer(tid: String) = nm.cancel(idFor(tid, 2000))

    fun pair(p: PairPromptView) {
        val n = builder(CH_ASK)
            .setContentTitle(if (p.outgoing) "Pareando com ${p.peerName}" else "${p.peerName} quer parear")
            .setContentText("Código ${Format.code(p.code)}. Toque para conferir.")
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(openApp(0))
            .setTimeoutAfter(120_000)
            .setAutoCancel(true)
            .build()
        nm.notify(ID_PAIR, n)
    }

    fun cancelPair() = nm.cancel(ID_PAIR)

    fun cancelAsk() {
        nm.cancel(ID_PAIR)
    }

    private fun action(label: String, what: String, tid: String): Notification.Action {
        val i = Intent(ctx, ActionReceiver::class.java).setAction(what).setData(Uri.parse("renandrop://offer/$tid")).putExtra(ActionReceiver.EXTRA_TID, tid)
        return Notification.Action.Builder(null, label, PendingIntent.getBroadcast(ctx, 0, i, flags())).build()
    }

    // ───────────── Conclusão ─────────────

    fun finished(e: HistoryEntry) {
        val id = idFor(e.id, 3000)
        when (e.state) {
            TransferState.DONE -> if (e.dir == Direction.RECV) received(id, e) else sent(id, e)
            TransferState.FAILED -> nm.notify(
                id,
                builder(CH_DONE).setContentTitle("Falhou: ${e.summary}")
                    .setContentText(e.error ?: "Não foi possível concluir")
                    .setContentIntent(openApp(1)).setAutoCancel(true).setTimeoutAfter(60_000).build(),
            )
            TransferState.DECLINED -> if (e.dir == Direction.SEND) nm.notify(
                id,
                builder(CH_DONE).setContentTitle("${e.peer} recusou o envio")
                    .setContentText(e.summary).setContentIntent(openApp(1)).setAutoCancel(true).setTimeoutAfter(60_000).build(),
            )
            else -> {}
        }
    }

    private fun received(id: Int, e: HistoryEntry) {
        val open = Opener.open(e.saved)
        val folder = Opener.folder(e.saved)
        val b = builder(CH_DONE)
            .setContentTitle("Recebido de ${e.peer}")
            .setContentText("${e.summary} · ${e.saved?.where ?: ""}".trim(' ', '·'))
            .setAutoCancel(true)
            .setContentIntent(if (open != null) PendingIntent.getActivity(ctx, id, open, flags()) else openApp(2))
        if (open != null) b.addAction(Notification.Action.Builder(null, "Abrir", PendingIntent.getActivity(ctx, id, open, flags())).build())
        val f = folder ?: Opener.downloads()
        b.addAction(Notification.Action.Builder(null, "Mostrar na pasta", PendingIntent.getActivity(ctx, id + 1, f, flags())).build())
        nm.notify(id, b.build())
    }

    private fun sent(id: Int, e: HistoryEntry) {
        nm.notify(
            id,
            builder(CH_DONE).setContentTitle("Enviado para ${e.peer}")
                .setContentText("${e.summary} · ${Format.bytes(e.bytes)}")
                .setContentIntent(openApp(2)).setAutoCancel(true).setTimeoutAfter(30_000).build(),
        )
    }

    private fun idFor(key: String, base: Int) = base + (key.hashCode() and 0x7FF)

    companion object {
        const val CH_SERVICE = "service"
        const val CH_ASK = "ask"
        const val CH_DONE = "done"
        const val ID_SERVICE = 1
        const val ID_PAIR = 2
    }
}
