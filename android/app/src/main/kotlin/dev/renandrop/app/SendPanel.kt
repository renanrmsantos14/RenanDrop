package dev.renandrop.app

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import dev.renandrop.protocol.Route

/** Resumo do que será enviado + escolha dos aparelhos + botão Enviar. Usado na tela principal e na tela de compartilhar. */
class SendPanel(
    private val ui: Ui,
    private val engine: Engine,
    private val onSent: () -> Unit,
    private val onPairFirst: () -> Unit,
) {
    val view: LinearLayout = ui.vbox()
    private val what = ui.text("", 15f, bold = true)
    private val detail = ui.muted("Lendo…", 13f)
    private val list = ui.vbox()
    private val empty = ui.vbox()
    private val error = ui.text("", 13f, ui.p.bad)
    private val send = ui.button("Enviar", Ui.Kind.PRIMARY) { doSend() }
    private val selected = LinkedHashSet<String>()
    private var touched = false
    private var sending = false
    private var preselect: String? = null
    private var staged: Staged? = null
    private var last: Snapshot? = null
    private val rows = KeyedList<PeerRow>(list, { it.id }, { CheckRow(it) })
    private val main = Handler(Looper.getMainLooper())

    init {
        view.addView(what)
        view.addView(detail, ui.lp(top = 2))
        view.addView(ui.gap(10))
        view.addView(list)
        empty.addView(ui.muted("Nenhum aparelho pareado ainda."))
        empty.addView(ui.button("Parear um aparelho", Ui.Kind.SECONDARY) { onPairFirst() }, ui.lp(top = 8))
        view.addView(empty)
        view.addView(error, ui.lp(top = 8))
        view.addView(send, ui.lp(top = 12))
        error.visibility = View.GONE
    }

    /** Define o que será enviado e, opcionalmente, o aparelho já marcado. */
    fun bind(s: Staged, preselect: String?) {
        staged = s
        this.preselect = preselect
        selected.clear()
        touched = false
        sending = false
        error.visibility = View.GONE
        what.text = when {
            s.tree != null -> "Pasta"
            s.uris.size + (if (s.text.isNullOrEmpty()) 0 else 1) > 1 -> "${s.uris.size + (if (s.text.isNullOrEmpty()) 0 else 1)} itens"
            else -> "Arquivo"
        }
        detail.text = "Lendo…"
        val ctx = ui.ctx
        Thread {
            val d = try {
                s.describe(ctx)
            } catch (e: Exception) {
                "Não foi possível ler a seleção"
            }
            main.post { if (staged === s) detail.text = d }
        }.start()
        last?.let { render(it) }
    }

    fun render(s: Snapshot) {
        last = s
        if (!touched && selected.isEmpty()) {
            val pre = preselect?.takeIf { id -> s.peers.any { it.id == id } }
                ?: s.peers.singleOrNull { it.online }?.id
                ?: s.peers.singleOrNull()?.id
            if (pre != null) selected.add(pre)
        }
        selected.retainAll(s.peers.map { it.id }.toSet())
        rows.update(s.peers)
        ui.visible(empty, s.peers.isEmpty())
        ui.visible(list, s.peers.isNotEmpty())
        refreshButton()
    }

    private fun refreshButton() {
        val n = selected.size
        send.text = when {
            sending -> "Enviando…"
            n == 0 -> "Escolha um aparelho"
            n == 1 -> "Enviar para 1 aparelho"
            else -> "Enviar para $n aparelhos"
        }
        val on = n > 0 && !sending
        send.isEnabled = on
        send.alpha = if (on) 1f else 0.5f
    }

    private fun doSend() {
        val s = staged ?: return
        if (selected.isEmpty() || sending) return
        sending = true
        error.visibility = View.GONE
        refreshButton()
        engine.send(s, selected.toList()) { err ->
            sending = false
            if (err == null) onSent() else {
                error.text = err
                error.visibility = View.VISIBLE
                refreshButton()
            }
        }
    }

    private inner class CheckRow(item: PeerRow) : RowView<PeerRow>(ui.vbox()) {
        private var id = item.id
        private var silent = false
        private val box: CheckBox = ui.check("", false) { v ->
            if (!silent) {
                touched = true
                if (v) selected.add(id) else selected.remove(id)
                refreshButton()
            }
        }
        private val sub: TextView = ui.muted("", 12f)

        init {
            (view as LinearLayout).addView(box)
            view.addView(sub)
            sub.setPadding(ui.dp(48), 0, 0, ui.dp(4))
        }

        override fun bind(item: PeerRow) {
            id = item.id
            ui.setIfChanged(box, item.name)
            ui.setIfChanged(sub, if (item.online) "Online · ${routeLabel(item.route ?: Route.LAN)}" else "Offline: o envio espera ele voltar")
            silent = true
            box.isChecked = item.id in selected
            silent = false
        }
    }
}
