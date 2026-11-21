package com.mrmzone.player.data

import android.content.Context
import com.mrmzone.player.player.PlayerLogic
import org.json.JSONArray
import org.json.JSONObject

class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val pos = ctx.applicationContext.getSharedPreferences("positions", Context.MODE_PRIVATE)

    var theme: Int get() = p.getInt("theme", 2); set(v) = p.edit().putInt("theme", v).apply() // 0 light 1 dark 2 system
    var grid: Boolean get() = p.getBoolean("grid", false); set(v) = p.edit().putBoolean("grid", v).apply()
    var sortBy: SortBy get() = SortBy.entries[p.getInt("sortBy", SortBy.NAME.ordinal).coerceIn(0, SortBy.entries.size - 1)]
        set(v) = p.edit().putInt("sortBy", v.ordinal).apply()
    var sortAsc: Boolean get() = p.getBoolean("sortAsc", true); set(v) = p.edit().putBoolean("sortAsc", v).apply()
    var folderMode: Int get() = p.getInt("folderMode", 0); set(v) = p.edit().putInt("folderMode", v).apply() // 0 all folders, 1 folder by folder
    var tab: Int get() = p.getInt("tab", 0); set(v) = p.edit().putInt("tab", v).apply()

    var decoder: PlayerLogic.Decoder get() = PlayerLogic.Decoder.entries[p.getInt("decoder", 0).coerceIn(0, 2)]
        set(v) = p.edit().putInt("decoder", v.ordinal).apply()
    var resumeMode: Int get() = p.getInt("resume", 0); set(v) = p.edit().putInt("resume", v).apply() // 0 always, 1 ask, 2 never
    var autoNext: Boolean get() = p.getBoolean("autoNext", true); set(v) = p.edit().putBoolean("autoNext", v).apply()
    var doubleTapSeek: Int get() = p.getInt("dtSeek", 10); set(v) = p.edit().putInt("dtSeek", v).apply()
    var gestureVolume: Boolean get() = p.getBoolean("gVol", true); set(v) = p.edit().putBoolean("gVol", v).apply()
    var gestureBrightness: Boolean get() = p.getBoolean("gBri", true); set(v) = p.edit().putBoolean("gBri", v).apply()
    var gestureSeek: Boolean get() = p.getBoolean("gSeek", true); set(v) = p.edit().putBoolean("gSeek", v).apply()
    var gestureZoom: Boolean get() = p.getBoolean("gZoom", true); set(v) = p.edit().putBoolean("gZoom", v).apply()
    var volumeBoost: Boolean get() = p.getBoolean("boost", true); set(v) = p.edit().putBoolean("boost", v).apply()
    var autoRotate: Boolean get() = p.getBoolean("autoRotate", true); set(v) = p.edit().putBoolean("autoRotate", v).apply()
    var subtitleScale: Int get() = p.getInt("subScale", 100); set(v) = p.edit().putInt("subScale", v).apply()
    var rememberBrightness: Boolean get() = p.getBoolean("remBri", true); set(v) = p.edit().putBoolean("remBri", v).apply()
    /** Player brightness 0..100 %, -1 = follow the phone. */
    var brightnessPct: Int get() = p.getInt("briPct", -1); set(v) = p.edit().putInt("briPct", v).apply()
    var aspect: Int get() = p.getInt("screenMode", 1); set(v) = p.edit().putInt("screenMode", v).apply() // 1 = Fit to screen
    var showRemaining: Boolean get() = p.getBoolean("remaining", false); set(v) = p.edit().putBoolean("remaining", v).apply()

    // ----- playback positions + history -----
    fun position(key: String): Long = pos.getLong("p:$key", 0L)
    fun length(key: String): Long = pos.getLong("l:$key", 0L)
    fun played(key: String): Boolean = pos.contains("p:$key")

    fun savePosition(key: String, position: Long, length: Long) {
        pos.edit().putLong("p:$key", position).putLong("l:$key", length).apply()
    }

    fun addHistory(e: HistoryEntry) {
        val list = history().filter { it.key != e.key }.toMutableList()
        list.add(0, e)
        val arr = JSONArray()
        list.take(100).forEach { arr.put(it.toJson()) }
        pos.edit().putString("history", arr.toString()).apply()
    }

    fun removeHistory(key: String) {
        val arr = JSONArray()
        history().filter { it.key != key }.forEach { arr.put(it.toJson()) }
        pos.edit().putString("history", arr.toString()).apply()
    }

    fun clearHistory() { pos.edit().clear().apply() }

    fun history(): List<HistoryEntry> = runCatching {
        val arr = JSONArray(pos.getString("history", "[]"))
        (0 until arr.length()).map { HistoryEntry.from(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())
}

data class HistoryEntry(val key: String, val uri: String, val title: String, val time: Long) {
    fun toJson() = JSONObject().put("k", key).put("u", uri).put("t", title).put("w", time)
    companion object {
        fun from(o: JSONObject) = HistoryEntry(o.getString("k"), o.getString("u"), o.optString("t"), o.optLong("w"))
    }
}
