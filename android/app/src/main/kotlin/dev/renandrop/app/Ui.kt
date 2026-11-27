package dev.renandrop.app

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Paleta, medidas e peças de interface (Views simples, sem AndroidX). */
class Ui(val ctx: Context, val p: Palette) {
    private val density = ctx.resources.displayMetrics.density

    fun dp(v: Int): Int = (v * density + 0.5f).toInt()
    fun dpf(v: Float): Float = v * density

    fun shape(fill: Int, radiusDp: Float, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dpf(radiusDp)
        if (stroke != 0) setStroke(maxOf(1, dp(1)), stroke)
    }

    private fun ripple(content: android.graphics.drawable.Drawable): android.graphics.drawable.Drawable =
        RippleDrawable(ColorStateList.valueOf(if (p.dark) 0x33FFFFFF else 0x22000000), content, null)

    fun text(s: CharSequence, sp: Float = 14f, color: Int = p.text, bold: Boolean = false): TextView = TextView(ctx).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        includeFontPadding = false
        setLineSpacing(dpf(2f), 1f)
    }

    fun muted(s: CharSequence, sp: Float = 13f): TextView = text(s, sp, p.muted)

    fun vbox(): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun hbox(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    fun gap(h: Int): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    fun card(padding: Int = 16): LinearLayout = vbox().apply {
        background = shape(p.surface, 16f, p.border)
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }

    enum class Kind { PRIMARY, SECONDARY, DANGER, GHOST }

    fun button(label: String, kind: Kind = Kind.SECONDARY, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        minHeight = dp(44)
        minimumWidth = dp(44)
        setPadding(dp(16), 0, dp(16), 0)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isClickable = true
        isFocusable = true
        val bg: android.graphics.drawable.Drawable = when (kind) {
            Kind.PRIMARY -> GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(p.accent, p.accent2)).apply { cornerRadius = dpf(12f) }
            Kind.SECONDARY -> shape(p.surface2, 12f, p.border)
            Kind.DANGER -> shape(p.surface2, 12f, p.border)
            Kind.GHOST -> shape(Color.TRANSPARENT, 12f)
        }
        background = ripple(bg)
        setTextColor(
            when (kind) {
                Kind.PRIMARY -> p.onAccent
                Kind.DANGER -> p.bad
                Kind.GHOST -> p.muted
                else -> p.text
            },
        )
        setOnClickListener { onClick() }
    }

    fun avatar(name: String, sizeDp: Int = 44): TextView = TextView(ctx).apply {
        text = Format.initial(name)
        textSize = sizeDp * 0.4f
        gravity = Gravity.CENTER
        setTextColor(p.onAccent)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(p.accent, p.accent2)).apply { shape = GradientDrawable.OVAL }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    fun input(hint: String, value: String = "", lines: Int = 1): EditText = EditText(ctx).apply {
        setText(value)
        this.hint = hint
        setHintTextColor(p.muted)
        setTextColor(p.text)
        textSize = 15f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        isSingleLine = lines == 1
        minHeight = dp(48)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = shape(p.surface2, 12f, p.border)
        highlightColor = p.accent
    }

    fun check(label: String, checked: Boolean, onChange: (Boolean) -> Unit): CheckBox = CheckBox(ctx).apply {
        text = label
        textSize = 15f
        setTextColor(p.text)
        isChecked = checked
        minHeight = dp(48)
        buttonTintList = ColorStateList.valueOf(p.accent)
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    fun switchRow(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit): View = hbox().apply {
        minimumHeight = dp(52)
        val col = vbox().apply {
            addView(text(label, 15f))
            if (hint != null) addView(muted(hint, 12f).apply { setPadding(0, dp(2), 0, 0) })
        }
        addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(
            Switch(ctx).apply {
                isChecked = checked
                thumbTintList = ColorStateList.valueOf(if (checked) p.accent else p.muted)
                trackTintList = ColorStateList.valueOf(p.border)
                setOnCheckedChangeListener { b, v ->
                    (b as Switch).thumbTintList = ColorStateList.valueOf(if (v) p.accent else p.muted)
                    onChange(v)
                }
            },
        )
    }

    fun toast(msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    /** Versão discreta no canto: não captura toque nem foco e não cobre o conteúdo (as telas deixam margem embaixo). */
    fun versionBadge(insets: Rect): TextView = text(Format.buildLabel(BuildConfig.VERSION_NAME, BuildConfig.BUILT_AT), 10f, p.muted).apply {
        alpha = 0.7f
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
            .apply { setMargins(0, 0, dp(10) + insets.right, dp(4) + insets.bottom) }
    }

    fun setIfChanged(t: TextView, s: CharSequence) {
        if (t.text.toString() != s.toString()) t.text = s
    }

    fun visible(v: View, show: Boolean) {
        val want = if (show) View.VISIBLE else View.GONE
        if (v.visibility != want) v.visibility = want
    }
}

/** Barra de progresso desenhada à mão (leve e com cantos redondos). */
class ProgressBarView(ctx: Context, private val ui: Ui) : View(ctx) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.p.surface2 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    var percent = 0
        set(v) {
            val n = v.coerceIn(0, 100)
            if (n != field) {
                field = n
                invalidate()
            }
        }
    var color = ui.p.accent
        set(v) {
            if (v != field) {
                field = v
                invalidate()
            }
        }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), ui.dp(6))

    override fun onDraw(c: Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(rect, r, r, track)
        if (percent > 0) {
            val w = maxOf(height.toFloat(), width * percent / 100f)
            rect.set(0f, 0f, w, height.toFloat())
            fill.shader = LinearGradient(0f, 0f, w, 0f, color, ui.p.accent2.takeIf { color == ui.p.accent } ?: color, Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, r, r, fill)
        }
    }
}

/** Linha de uma lista com chave estável: atualiza no lugar (um clique não se perde quando o progresso atualiza 4 vezes por segundo). */
abstract class RowView<T>(val view: View) {
    abstract fun bind(item: T)
}

class KeyedList<T>(private val box: LinearLayout, private val key: (T) -> String, private val make: (T) -> RowView<T>) {
    private val rows = LinkedHashMap<String, RowView<T>>()

    fun update(items: List<T>) {
        val seen = HashSet<String>()
        items.forEachIndexed { i, item ->
            val k = key(item)
            seen.add(k)
            val row = rows.getOrPut(k) { make(item) }
            row.bind(item)
            if (box.getChildAt(i) !== row.view) {
                (row.view.parent as? ViewGroup)?.removeView(row.view)
                box.addView(row.view, i)
            }
        }
        for (k in rows.keys.filter { it !in seen }) box.removeView(rows.remove(k)!!.view)
    }
}

/** Camadas por cima da tela (modal e gaveta): toque fora chama `onOutside`; o painel consome os toques dentro. */
class Overlay(private val ui: Ui, private val root: FrameLayout) {
    class Layer(val view: FrameLayout, val onOutside: () -> Unit, val drawer: Boolean)

    private val layers = ArrayList<Layer>()
    private var insets = Rect()

    fun setInsets(r: Rect) {
        insets = r
        layers.forEach { it.view.setPadding(r.left, r.top, r.right, r.bottom) }
    }

    fun modal(content: View, onOutside: () -> Unit): Layer = add(content, onOutside, false)

    fun drawer(content: View, onOutside: () -> Unit): Layer = add(content, onOutside, true)

    private fun add(content: View, onOutside: () -> Unit, drawer: Boolean): Layer {
        val layer = FrameLayout(ui.ctx).apply {
            setBackgroundColor(ui.p.scrim)
            setPadding(insets.left, insets.top, insets.right, insets.bottom)
            isClickable = true
            setOnClickListener { onOutside() }
            alpha = 0f
        }
        val metrics = ui.ctx.resources.displayMetrics
        val panelW = if (drawer) minOf(ui.dp(380), (metrics.widthPixels * 0.9f).toInt()) else minOf(ui.dp(420), metrics.widthPixels - ui.dp(32))
        content.isClickable = true
        layer.addView(
            content,
            FrameLayout.LayoutParams(panelW, if (drawer) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT, if (drawer) Gravity.END else Gravity.CENTER)
                .apply { if (!drawer) setMargins(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16)) },
        )
        root.addView(layer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val l = Layer(layer, onOutside, drawer)
        layers.add(l)
        animate(l, true)
        return l
    }

    fun close(l: Layer) {
        if (!layers.remove(l)) return
        animate(l, false) { root.removeView(l.view) }
    }

    /** Botão Voltar: fecha a camada de cima pelo mesmo caminho do toque fora (pode pedir confirmação). */
    fun back(): Boolean {
        val top = layers.lastOrNull() ?: return false
        top.onOutside()
        return true
    }

    private fun animate(l: Layer, show: Boolean, end: () -> Unit = {}) {
        val panel = l.view.getChildAt(0)
        val shift = if (l.drawer) ui.dp(48).toFloat() else ui.dp(16).toFloat()
        ValueAnimator.ofFloat(if (show) 0f else 1f, if (show) 1f else 0f).apply {
            duration = 160
            addUpdateListener {
                val v = it.animatedValue as Float
                l.view.alpha = v
                if (l.drawer) panel.translationX = (1 - v) * shift else panel.translationY = (1 - v) * shift
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) = end()
            })
            start()
        }
    }
}

/** Barras do sistema transparentes, conteúdo por baixo e ícones claros ou escuros conforme o tema. */
object Edge {
    @Suppress("DEPRECATION")
    fun setup(window: Window, dark: Boolean) {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else light, light)
        } else {
            var f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (!dark) f = f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = f
        }
    }

    @Suppress("DEPRECATION")
    fun rect(w: WindowInsets): Rect =
        if (Build.VERSION.SDK_INT >= 30) {
            val s = w.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = w.getInsets(WindowInsets.Type.ime())
            Rect(s.left, s.top, s.right, maxOf(s.bottom, ime.bottom))
        } else {
            Rect(w.systemWindowInsetLeft, w.systemWindowInsetTop, w.systemWindowInsetRight, w.systemWindowInsetBottom)
        }

    /** O sistema está no tema escuro? */
    fun systemDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}
