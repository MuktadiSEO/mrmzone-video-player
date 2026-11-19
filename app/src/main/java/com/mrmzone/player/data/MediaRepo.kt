package com.mrmzone.player.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File

/** Reads every video the system media index knows about (MP4, MKV, AVI, WEBM, FLV, WMV, TS, 3GP, MOV ...). */
object MediaRepo {
    @Volatile var cache: List<Video> = emptyList(); private set

    fun scan(ctx: Context): List<Video> {
        val out = ArrayList<Video>()
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        @Suppress("DEPRECATION")
        val proj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )
        runCatching {
            ctx.contentResolver.query(collection, proj, null, null, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(proj[0]); val iName = c.getColumnIndexOrThrow(proj[1])
                val iData = c.getColumnIndexOrThrow(proj[2]); val iDur = c.getColumnIndexOrThrow(proj[3])
                val iSize = c.getColumnIndexOrThrow(proj[4]); val iW = c.getColumnIndexOrThrow(proj[5])
                val iH = c.getColumnIndexOrThrow(proj[6]); val iDate = c.getColumnIndexOrThrow(proj[7])
                val iMime = c.getColumnIndexOrThrow(proj[8]); val iBucket = c.getColumnIndexOrThrow(proj[9])
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val path = c.getString(iData) ?: ""
                    val name = c.getString(iName) ?: File(path).name
                    if (name.startsWith(".")) continue
                    val parent = File(path).parent ?: ""
                    out += Video(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id).toString(),
                        path = path, name = name,
                        folderPath = parent,
                        folderName = c.getString(iBucket) ?: File(parent).name.ifEmpty { "Internal storage" },
                        duration = c.getLong(iDur), size = c.getLong(iSize),
                        width = c.getInt(iW), height = c.getInt(iH),
                        dateAdded = c.getLong(iDate), mime = c.getString(iMime) ?: "",
                    )
                }
            }
        }.onFailure { android.util.Log.e("MediaRepo", "scan failed", it) }
        android.util.Log.i("MediaRepo", "found ${out.size} videos")
        val filled = MetaFiller.apply(ctx, out)
        cache = filled
        return filled
    }
}
