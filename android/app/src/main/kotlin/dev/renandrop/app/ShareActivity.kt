package dev.renandrop.app

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/** Destino do "Compartilhar": uma folha na parte de baixo para escolher os aparelhos e enviar. */
class ShareActivity : Activity() {
    private lateinit var engine: Engine
    private lateinit var ui: Ui
    private lateinit var panel: SendPanel
    private val listener: (Snapshot) -> Unit = { panel.render(it) }
    private var ready = false
    private val notice: (String) -> Unit = { ui.toast(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = applicationContext.engine
        val dark = isDarkTheme(engine.snapshot.settings.theme, Edge.systemDark(this))
        ui = Ui(this, Palette(dark))
        window.setBackgroundDrawable(ColorDrawable(0))
        Edge.setup(window, dark)

        val staged = stagedFrom(intent)
        if (staged == null || staged.isEmpty) {
            Ui(this, Palette(dark)).toast("Nada para enviar")
            finish()
            return
        }
        NodeService.start(this)
        NodeService.keepGrants(this, staged.uris)

        val root = FrameLayout(this).apply {
            setBackgroundColor(ui.p.scrim)
            setOnClickListener { finish() }
        }
        panel = SendPanel(ui, engine, onSent = {
            ui.toast("Na fila de envio")
            finish()
        }, onPairFirst = {
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, 0))
            finish()
        })
        val sheet = ui.vbox().apply {
            background = ui.shape(ui.p.surface, 20f, ui.p.border)
            isClickable = true
            setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16))
        }
        sheet.addView(ui.text("Enviar com o RenanDrop", 17f, bold = true))
        sheet.addView(panel.view, ui.lp(top = 12))
        val scroll = ScrollView(this).apply { overScrollMode = ScrollView.OVER_SCROLL_NEVER; addView(sheet) }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        root.addView(scroll, lp)

        val badge = ui.versionBadge(Rect())
        root.addView(badge)
        root.setOnApplyWindowInsetsListener { _, w ->
            val r = Edge.rect(w)
            root.setPadding(r.left, r.top, r.right, 0)
            sheet.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16) + r.bottom)
            (badge.layoutParams as FrameLayout.LayoutParams).setMargins(0, 0, ui.dp(10) + r.right, ui.dp(4) + r.bottom)
            badge.requestLayout()
            w
        }
        setContentView(root)
        panel.bind(staged, null)
        ready = true
    }

    /** Segundo compartilhamento com a folha ainda aberta: troca a seleção em vez de enviar a anterior. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val s = stagedFrom(intent)
        if (s == null || s.isEmpty || !ready) return
        NodeService.keepGrants(this, s.uris)
        panel.bind(s, null)
    }

    override fun onStart() {
        super.onStart()
        if (ready) {
            engine.addListener(listener)
            engine.onNotice = notice
        }
    }

    override fun onStop() {
        if (ready) {
            engine.removeListener(listener)
            if (engine.onNotice === notice) engine.onNotice = null
        }
        super.onStop()
    }

    @Suppress("DEPRECATION")
    private fun stagedFrom(i: Intent?): Staged? {
        i ?: return null
        val uris = LinkedHashSet<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> {
                val u: Uri? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)
                u?.let { uris.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val l: List<Uri>? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                l?.let { uris.addAll(it) }
            }
            else -> return null
        }
        i.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let { uris.add(it) } }
        val text = if (uris.isEmpty()) i.getStringExtra(Intent.EXTRA_TEXT) else null
        return Staged(uris.toList(), null, text)
    }
}
