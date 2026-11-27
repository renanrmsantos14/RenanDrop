package dev.renandrop.app

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/** Formatação de texto da interface (mesmos formatos do app do PC). */
object Format {
    private val SP: ZoneId = ZoneId.of("America/Sao_Paulo")
    private val FULL: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    private val SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")
    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

    fun bytes(n: Long): String {
        if (n <= 0) return "0 B"
        val i = min((ln(n.toDouble()) / ln(1024.0)).toInt(), UNITS.size - 1)
        val v = n / 1024.0.pow(i)
        val s = if (v >= 100 || i == 0) String.format(Locale.ROOT, "%.0f", v) else String.format(Locale.ROOT, "%.1f", v)
        return "${s.replace('.', ',')} ${UNITS[i]}"
    }

    fun speed(bytesPerSecond: Double): String = bytes(bytesPerSecond.toLong()) + "/s"

    fun files(n: Int): String = if (n == 1) "1 arquivo" else "$n arquivos"

    fun percent(done: Long, total: Long): Int =
        if (total <= 0) 0 else ((done.coerceAtLeast(0) * 100.0) / total).toInt().coerceIn(0, 100)

    /** `dd/MM HH:mm` em America/Sao_Paulo. */
    fun shortDate(ms: Long): String = java.time.Instant.ofEpochMilli(ms).atZone(SP).format(SHORT)

    /** Código de pareamento em dois blocos: `123456` vira `123 456`. */
    fun code(code: String): String = if (code.length == 6) code.substring(0, 3) + " " + code.substring(3) else code

    /** `v0.0.7 · 27/11/2026 14:05`, sempre em America/Sao_Paulo. */
    fun buildLabel(version: String, builtIso: String): String {
        val at = parseBuilt(builtIso) ?: return "v$version"
        return "v$version · ${at.format(FULL)}"
    }

    private fun parseBuilt(iso: String): java.time.ZonedDateTime? = try {
        if (iso.isBlank()) null
        else if (iso.length > 19 && (iso.endsWith("Z") || iso.substring(19).any { it == '+' || it == '-' })) {
            OffsetDateTime.parse(iso).atZoneSameInstant(SP)
        } else {
            LocalDateTime.parse(iso).atZone(SP)
        }
    } catch (_: Exception) {
        null
    }

    fun initial(name: String): String = name.trim().take(1).uppercase().ifEmpty { "?" }
}
