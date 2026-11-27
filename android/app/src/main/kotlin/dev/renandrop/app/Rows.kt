package dev.renandrop.app

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.renandrop.protocol.Direction
import dev.renandrop.protocol.TransferInfo
import dev.renandrop.protocol.TransferState

interface RowActions {
    fun pair(p: PeerRow)
    fun peerMenu(p: PeerRow)
    fun sendTo(p: PeerRow)
    fun cancel(t: TransferInfo)
    fun retry(t: TransferInfo)
    fun open(e: HistoryEntry)
    fun showFolder(e: HistoryEntry)
}

private fun Ui.rowCard(): LinearLayout = card(14).apply {
    layoutParams = lp(top = 8)
}

/** Aparelho pareado ou encontrado (com "Parear"). */
class PeerRowView(private val ui: Ui, private val act: RowActions) : RowView<PeerRow>(ui.rowCard()) {
    private val name = ui.text("", 16f, bold = true)
    private val status = ui.text("", 12f)
    private val tagText = ui.muted("", 12f)
    private val avatar = ui.avatar("?")
    private val actions = ui.hbox()
    private var item: PeerRow? = null

    init {
        val top = ui.hbox()
        top.addView(avatar)
        val col = ui.vbox().apply {
            addView(name)
            addView(status, ui.lp(top = 3))
            addView(tagText, ui.lp(top = 2))
        }
        top.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(12) })
        (view as LinearLayout).addView(top)
        view.addView(actions, ui.lp(top = 10))
        actions.gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }

    override fun bind(item: PeerRow) {
        val first = this.item == null || this.item?.paired != item.paired
        this.item = item
        ui.setIfChanged(name, item.name)
        avatar.text = Format.initial(item.name)
        status.setTextColor(if (item.online) ui.p.ok else ui.p.muted)
        ui.setIfChanged(status, if (item.online) "● Online · ${routeLabel(item.route)}" else "○ Offline")
        val t = buildList {
            when (item.autoAccept) {
                true -> add("Recebe sem perguntar")
                false -> add("Sempre pergunta")
                null -> {}
            }
            if (item.manual) add("Adicionado por endereço")
        }.joinToString(" · ")
        ui.setIfChanged(tagText, t)
        ui.visible(tagText, t.isNotEmpty())
        if (first) {
            actions.removeAllViews()
            if (item.paired) {
                actions.addView(ui.button("Mais", Ui.Kind.GHOST) { this.item?.let { act.peerMenu(it) } })
                actions.addView(ui.button("Enviar", Ui.Kind.SECONDARY) { this.item?.let { act.sendTo(it) } })
            } else {
                actions.addView(ui.button("Parear", Ui.Kind.PRIMARY) { this.item?.let { act.pair(it) } })
            }
        }
    }
}

/** Transferência da fila, com progresso, velocidade, rota e ações. */
class TransferRowView(private val ui: Ui, private val act: RowActions) : RowView<TransferInfo>(ui.rowCard()) {
    private val title = ui.text("", 15f, bold = true)
    private val line = ui.muted("", 12f)
    private val bar = ProgressBarView(ui.ctx, ui)
    private val meta = ui.muted("", 12f)
    private val error = ui.text("", 12f, ui.p.bad)
    private val actions = ui.hbox().apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
    private var item: TransferInfo? = null
    private var shown: Kind? = null

    private enum class Kind { CANCEL, RETRY, NONE }

    init {
        val box = view as LinearLayout
        box.addView(title)
        box.addView(line, ui.lp(top = 3))
        box.addView(bar, ui.lp(top = 10))
        box.addView(meta, ui.lp(top = 8))
        box.addView(error, ui.lp(top = 6))
        box.addView(actions, ui.lp(top = 8))
    }

    override fun bind(item: TransferInfo) {
        this.item = item
        ui.setIfChanged(title, item.summary)
        ui.setIfChanged(line, directionLine(item))
        bar.percent = percentOf(item)
        bar.color = when (item.state) {
            TransferState.DONE -> ui.p.ok
            TransferState.FAILED, TransferState.CANCELLED, TransferState.DECLINED -> ui.p.bad
            else -> ui.p.accent
        }
        val route = routeLabel(item.route)
        ui.setIfChanged(meta, (metaParts(item) + listOfNotNull(route.takeIf { it.isNotEmpty() })).joinToString(" · "))
        ui.setIfChanged(error, item.error ?: "")
        ui.visible(error, !item.error.isNullOrEmpty() && item.state != TransferState.DONE)
        val want = when {
            !item.finished -> Kind.CANCEL
            item.direction == Direction.SEND && item.state != TransferState.DONE -> Kind.RETRY
            else -> Kind.NONE
        }
        if (want != shown) {
            shown = want
            actions.removeAllViews()
            when (want) {
                Kind.CANCEL -> actions.addView(ui.button("Cancelar", Ui.Kind.DANGER) { this.item?.let { act.cancel(it) } })
                Kind.RETRY -> actions.addView(ui.button("Tentar de novo", Ui.Kind.SECONDARY) { this.item?.let { act.retry(it) } })
                Kind.NONE -> {}
            }
            ui.visible(actions, want != Kind.NONE)
        }
    }
}

/** Entrada do histórico, com "Abrir" e "Mostrar na pasta" para o que foi recebido. */
class HistoryRowView(private val ui: Ui, private val act: RowActions) : RowView<HistoryEntry>(ui.rowCard()) {
    private val title = ui.text("", 15f, bold = true)
    private val line = ui.muted("", 12f)
    private val meta = ui.muted("", 12f)
    private val error = ui.text("", 12f, ui.p.bad)
    private val actions = ui.hbox().apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
    private var item: HistoryEntry? = null
    private var built = false

    init {
        val box = view as LinearLayout
        box.addView(title)
        box.addView(line, ui.lp(top = 3))
        box.addView(meta, ui.lp(top = 3))
        box.addView(error, ui.lp(top = 6))
        box.addView(actions, ui.lp(top = 8))
    }

    override fun bind(item: HistoryEntry) {
        this.item = item
        ui.setIfChanged(title, item.summary)
        ui.setIfChanged(line, "${if (item.dir == Direction.SEND) "Para" else "De"} ${item.peer} · ${Format.files(item.files)} · ${Format.bytes(item.bytes)}")
        val where = item.saved?.where?.let { " · $it" } ?: ""
        ui.setIfChanged(meta, "${Format.shortDate(item.ts)} · ${stateLabel(item.state)}$where")
        (meta as TextView).setTextColor(if (item.state == TransferState.DONE) ui.p.ok else ui.p.muted)
        ui.setIfChanged(error, item.error ?: "")
        ui.visible(error, !item.error.isNullOrEmpty())
        if (!built) {
            built = true
            val received = item.dir == Direction.RECV && item.state == TransferState.DONE && item.saved != null
            if (received) {
                if (item.saved?.openUri != null) actions.addView(ui.button("Abrir", Ui.Kind.SECONDARY) { this.item?.let { act.open(it) } })
                actions.addView(ui.button("Mostrar na pasta", Ui.Kind.SECONDARY) { this.item?.let { act.showFolder(it) } }, ui.lp(w = -2).apply { marginStart = ui.dp(8) })
            }
            ui.visible(actions, received)
        }
    }
}

