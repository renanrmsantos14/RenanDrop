package dev.renandrop.app

import dev.renandrop.protocol.Direction
import dev.renandrop.protocol.TransferState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Onde o recebimento foi parar. `openUri`: o arquivo (quando é um só); `folderUri`: a pasta que contém
 * (documento do provedor de armazenamento, para "Mostrar na pasta"); `where`: nome legível do destino.
 */
data class Saved(val openUri: String?, val openMime: String?, val folderUri: String?, val where: String)

data class HistoryEntry(
    val id: String,
    val ts: Long,
    val dir: Direction,
    val peer: String,
    val summary: String,
    val files: Int,
    val bytes: Long,
    val state: TransferState,
    val error: String?,
    val saved: Saved?,
)

/** Histórico persistente (últimas 100 transferências). Acesso sincronizado. */
class HistoryStore(private val file: File) {
    private var list: List<HistoryEntry>? = null

    @Synchronized
    fun all(): List<HistoryEntry> = list ?: read().also { list = it }

    @Synchronized
    fun add(e: HistoryEntry) {
        val next = (listOf(e) + all().filter { it.id != e.id }).take(MAX)
        list = next
        write(next)
    }

    @Synchronized
    fun clear() {
        list = emptyList()
        write(emptyList())
    }

    private fun read(): List<HistoryEntry> = try {
        val arr = JSONArray(file.takeIf { it.exists() }?.readText() ?: "[]")
        (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun write(l: List<HistoryEntry>) {
        try {
            val arr = JSONArray()
            l.forEach { arr.put(toJson(it)) }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
        }
    }

    private fun toJson(e: HistoryEntry) = JSONObject()
        .put("id", e.id).put("ts", e.ts).put("dir", e.dir.name).put("peer", e.peer).put("summary", e.summary)
        .put("files", e.files).put("bytes", e.bytes).put("state", e.state.name).put("error", e.error)
        .put(
            "saved",
            e.saved?.let {
                JSONObject().put("open", it.openUri).put("mime", it.openMime).put("folder", it.folderUri).put("where", it.where)
            },
        )

    private fun fromJson(o: JSONObject): HistoryEntry {
        val s = o.optJSONObject("saved")
        return HistoryEntry(
            id = o.getString("id"), ts = o.getLong("ts"), dir = Direction.valueOf(o.getString("dir")),
            peer = o.getString("peer"), summary = o.getString("summary"), files = o.getInt("files"), bytes = o.getLong("bytes"),
            state = TransferState.valueOf(o.getString("state")), error = o.optString("error").takeIf { it.isNotEmpty() && it != "null" },
            saved = s?.let {
                Saved(
                    it.optString("open").takeIf { v -> v.isNotEmpty() && v != "null" },
                    it.optString("mime").takeIf { v -> v.isNotEmpty() && v != "null" },
                    it.optString("folder").takeIf { v -> v.isNotEmpty() && v != "null" },
                    it.optString("where"),
                )
            },
        )
    }

    private companion object {
        const val MAX = 100
    }
}
