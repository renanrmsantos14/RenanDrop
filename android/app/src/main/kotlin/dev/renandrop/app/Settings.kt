package dev.renandrop.app

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

data class AppSettings(
    val deviceName: String,
    /** URI da pasta escolhida (seletor de arquivos); `null` usa Downloads/RenanDrop. */
    val recvTree: String? = null,
    val recvTreeName: String? = null,
    val theme: ThemeMode = ThemeMode.AUTO,
    val autoAccept: Boolean = false,
    val boot: Boolean = true,
    val tailscale: Boolean = true,
    val lanes: Int = 4,
) {
    /** Mudanças que exigem reiniciar o nó (a configuração dele não muda com ele rodando). */
    fun needsNodeRestart(o: AppSettings): Boolean = deviceName != o.deviceName || autoAccept != o.autoAccept || lanes != o.lanes
}

/** Aparelho adicionado por nome ou IP (Tailscale, MagicDNS). */
data class ManualPeer(val id: String, val name: String, val host: String, val port: Int)

class SettingsStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        deviceName = sp.getString("device_name", null)?.takeIf { it.isNotBlank() } ?: defaultName(),
        recvTree = sp.getString("recv_tree", null),
        recvTreeName = sp.getString("recv_tree_name", null),
        theme = ThemeMode.of(sp.getString("theme", null)),
        autoAccept = sp.getBoolean("auto_accept", false),
        boot = sp.getBoolean("boot", true),
        tailscale = sp.getBoolean("tailscale", true),
        lanes = sp.getInt("lanes", 4).coerceIn(1, 8),
    )

    fun save(s: AppSettings) {
        sp.edit()
            .putString("device_name", s.deviceName.trim().take(64))
            .putString("recv_tree", s.recvTree)
            .putString("recv_tree_name", s.recvTreeName)
            .putString("theme", s.theme.key)
            .putBoolean("auto_accept", s.autoAccept)
            .putBoolean("boot", s.boot)
            .putBoolean("tailscale", s.tailscale)
            .putInt("lanes", s.lanes.coerceIn(1, 8))
            .apply()
    }

    fun loadManual(): List<ManualPeer> = try {
        val arr = JSONArray(sp.getString("manual", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ManualPeer(o.getString("id"), o.getString("name"), o.getString("host"), o.getInt("port"))
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun saveManual(list: List<ManualPeer>) {
        val arr = JSONArray()
        for (m in list) arr.put(JSONObject().put("id", m.id).put("name", m.name).put("host", m.host).put("port", m.port))
        sp.edit().putString("manual", arr.toString()).apply()
    }

    private fun defaultName(): String {
        val m = Build.MODEL?.trim().orEmpty()
        return if (m.isEmpty()) "Android" else m.take(64)
    }
}
