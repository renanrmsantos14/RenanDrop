package dev.renandrop.protocol

/**
 * JSON mínimo (objetos, listas, strings, números inteiros, booleanos e null), o bastante para as
 * mensagens do protocolo. Evita depender de `org.json` (só existe no Android) ou de biblioteca externa.
 * Valores: Map<String, Any?>, List<Any?>, String, Long, Double, Boolean e null.
 */
internal object Json {
    fun parse(bytes: ByteArray, off: Int = 0, len: Int = bytes.size - off): Any? {
        val p = Parser(String(bytes, off, len, Charsets.UTF_8))
        val v = p.value()
        p.skipWs()
        if (p.i != p.s.length) throw ProtocolException("JSON com lixo no final")
        return v
    }

    fun write(v: Any?): ByteArray {
        val sb = StringBuilder(256)
        put(sb, v)
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun put(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> str(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    str(sb, k as String)
                    sb.append(':')
                    put(sb, x)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                for ((n, x) in v.withIndex()) {
                    if (n > 0) sb.append(',')
                    put(sb, x)
                }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("tipo não suportado em JSON: ${v::class}")
        }
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(): Any? {
            skipWs()
            if (i >= s.length) throw ProtocolException("JSON incompleto")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw ProtocolException("JSON inválido em $i")
            }
        }

        private fun lit(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw ProtocolException("JSON inválido em $i")
            i += w.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            skipWs()
            if (s[i] == '}') {
                i++
                return m
            }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') throw ProtocolException("JSON: esperava chave")
                val k = string()
                skipWs()
                if (i >= s.length || s[i] != ':') throw ProtocolException("JSON: esperava ':'")
                i++
                m[k] = value()
                skipWs()
                if (i >= s.length) throw ProtocolException("JSON incompleto")
                when (s[i++]) {
                    ',' -> {}
                    '}' -> return m
                    else -> throw ProtocolException("JSON inválido em $i")
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            skipWs()
            if (s[i] == ']') {
                i++
                return l
            }
            while (true) {
                l.add(value())
                skipWs()
                if (i >= s.length) throw ProtocolException("JSON incompleto")
                when (s[i++]) {
                    ',' -> {}
                    ']' -> return l
                    else -> throw ProtocolException("JSON inválido em $i")
                }
            }
        }

        private fun string(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw ProtocolException("JSON: string sem fim")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw ProtocolException("JSON: escape incompleto")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw ProtocolException("JSON: \\u incompleto")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw ProtocolException("JSON: escape inválido '$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && s[i] in '0'..'9') i++
            var frac = false
            if (i < s.length && (s[i] == '.' || s[i] == 'e' || s[i] == 'E')) {
                frac = true
                while (i < s.length && (s[i] in '0'..'9' || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            }
            val t = s.substring(st, i)
            return try {
                if (frac) t.toDouble() else t.toLong()
            } catch (e: NumberFormatException) {
                throw ProtocolException("JSON: número inválido '$t'")
            }
        }
    }
}

/** Violação do protocolo (mensagem malformada, limite excedido). */
class ProtocolException(message: String) : java.io.IOException(message)

@Suppress("UNCHECKED_CAST")
internal fun Any?.obj(): Map<String, Any?> = this as? Map<String, Any?> ?: throw ProtocolException("esperava objeto JSON")

internal fun Map<String, Any?>.str(k: String): String = this[k] as? String ?: throw ProtocolException("campo '$k' ausente")

internal fun Map<String, Any?>.long(k: String): Long = (this[k] as? Number)?.toLong() ?: throw ProtocolException("campo '$k' ausente")

internal fun Map<String, Any?>.longOr(k: String, d: Long): Long = (this[k] as? Number)?.toLong() ?: d

internal fun Map<String, Any?>.bool(k: String): Boolean = this[k] as? Boolean ?: throw ProtocolException("campo '$k' ausente")

internal fun Map<String, Any?>.boolOr(k: String, d: Boolean): Boolean = this[k] as? Boolean ?: d

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.list(k: String): List<Any?> = this[k] as? List<Any?> ?: throw ProtocolException("campo '$k' ausente")
