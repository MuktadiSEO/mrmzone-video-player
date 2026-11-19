package com.mrmzone.player.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import com.mrmzone.player.R
import com.mrmzone.player.data.Video
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.concurrent.Executors

/** Small, fast thumbnail loader. Uses the system's cached video thumbnails (no full-frame decoding on the UI thread). */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 12).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = Collections.synchronizedSet(HashSet<String>())
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "thumbs").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())

    fun load(iv: ImageView, v: Video) {
        iv.tag = v.uri
        cache.get(v.uri)?.let { iv.setImageBitmap(it); return }
        iv.setImageResource(R.drawable.thumb_placeholder)
        if (v.uri in failed) return
        val ref = WeakReference(iv)
        val ctx = iv.context.applicationContext
        pool.execute {
            if (ref.get()?.tag != v.uri) return@execute // view was recycled, skip the work
            val bmp = decode(ctx, v)
            main.post {
                if (bmp == null) failed.add(v.uri) else cache.put(v.uri, bmp)
                val view = ref.get()
                if (bmp != null && view != null && view.tag == v.uri) view.setImageBitmap(bmp)
            }
        }
    }

    private fun decode(ctx: Context, v: Video): Bitmap? {
        val cr = ctx.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { return cr.loadThumbnail(Uri.parse(v.uri), Size(320, 180), null) }
        } else {
            @Suppress("DEPRECATION")
            runCatching { MediaStore.Video.Thumbnails.getThumbnail(cr, v.id, MediaStore.Video.Thumbnails.MINI_KIND, null)?.let { return it } }
        }
        @Suppress("DEPRECATION")
        return runCatching { ThumbnailUtils.createVideoThumbnail(v.path, MediaStore.Video.Thumbnails.MINI_KIND) }.getOrNull()
    }
}
