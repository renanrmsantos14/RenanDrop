package dev.renandrop.app

import android.graphics.Color

enum class ThemeMode(val key: String, val label: String) {
    AUTO("auto", "Automático"), LIGHT("light", "Claro"), DARK("dark", "Escuro");

    companion object {
        fun of(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

fun isDarkTheme(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.AUTO -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** Mesma paleta azul/violeta do app do PC. */
class Palette(val dark: Boolean) {
    val bg = Color.parseColor(if (dark) "#0F121B" else "#F4F6FB")
    val surface = Color.parseColor(if (dark) "#171B27" else "#FFFFFF")
    val surface2 = Color.parseColor(if (dark) "#1F2433" else "#EEF1F8")
    val border = Color.parseColor(if (dark) "#2A3042" else "#DDE2EE")
    val text = Color.parseColor(if (dark) "#ECEFF7" else "#161A26")
    val muted = Color.parseColor(if (dark) "#98A1BA" else "#667089")
    val accent = Color.parseColor(if (dark) "#6B8BFF" else "#3F6BFF")
    val accent2 = Color.parseColor(if (dark) "#9A79FF" else "#7A4DFF")
    val ok = Color.parseColor(if (dark) "#4CD08A" else "#158A52")
    val bad = Color.parseColor(if (dark) "#FF7584" else "#C62F3E")
    val scrim = Color.argb(115, 8, 10, 18)
    val onAccent = Color.WHITE
}
