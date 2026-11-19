package com.mrmzone.player.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.interfaces.IMedia
import java.io.File
import java.util.concurrent.Executors

/**
 * Android's media index can't read the length / resolution of many formats (AVI, WMV, FLV, MPG, RM ...).
 * We read them once with the VLC engine in the background and cache the result.
 */
object MetaFiller {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "meta").apply { priority = Thread.MIN_PRIORITY } }
    private var vlc: LibVLC? = null
    @Volatile private var running = false

    private fun store(ctx: Context) = ctx.applicationContext.getSharedPreferences("meta", Context.MODE_PRIVATE)
    private fun key(v: Video) = "${v.path}|${v.size}"

    /** Apply cached values to a fresh scan. */
    fun apply(ctx: Context, list: List<Video>): List<Video> {
        val s = store(ctx)
        return list.map { v ->
            if (v.duration > 0 && v.width > 0) v
            else s.getString(key(v), null)?.split(',')?.takeIf { it.size == 3 }?.let { p ->
                v.copy(
                    duration = if (v.duration > 0) v.duration else p[0].toLongOrNull() ?: 0,
                    width = if (v.width > 0) v.width else p[1].toIntOrNull() ?: 0,
                    height = if (v.height > 0) v.height else p[2].toIntOrNull() ?: 0,
                )
            } ?: v
        }
    }

    /** Fills the missing values in the background, then calls [done] on the main thread if anything changed. */
    fun fill(ctx: Context, list: List<Video>, done: () -> Unit) {
        val s = store(ctx)
        val todo = list.filter { (it.duration <= 0 || it.width <= 0) && !s.contains(key(it)) && File(it.path).canRead() }
        if (todo.isEmpty() || running) return
        running = true
        val app = ctx.applicationContext
        io.execute {
            var changed = false
            try {
                val lib = vlc ?: LibVLC(app, arrayListOf("-q", "--no-audio", "--no-spu")).also { vlc = it }
                for (v in todo) {
                    var dur = 0L; var w = 0; var h = 0
                    runCatching {
                        val m = Media(lib, v.path)
                        if (m.parse(IMedia.Parse.ParseLocal)) {
                            dur = m.duration
                            for (i in 0 until m.trackCount) {
                                val t = m.getTrack(i)
                                if (t is IMedia.VideoTrack && t.width > 0) { w = t.width; h = t.height; break }
                            }
                        }
                        m.release()
                    }
                    s.edit().putString(key(v), "$dur,$w,$h").apply()
                    if (dur > 0 || w > 0) changed = true
                }
            } finally {
                running = false
            }
            if (changed) Handler(Looper.getMainLooper()).post(done)
        }
    }
}
