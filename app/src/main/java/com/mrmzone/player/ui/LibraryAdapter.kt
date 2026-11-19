package com.mrmzone.player.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mrmzone.player.R
import com.mrmzone.player.data.Folder
import com.mrmzone.player.data.Library
import com.mrmzone.player.data.Prefs
import com.mrmzone.player.data.Video
import com.mrmzone.player.util.Fmt

sealed class Row {
    data class F(val folder: Folder) : Row()
    data class V(val video: Video) : Row()
}

class LibraryAdapter(
    private val prefs: Prefs,
    private val onFolder: (Folder) -> Unit,
    private val onVideo: (Video) -> Unit,
    private val onVideoMore: (Video, View) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var grid = false
    private var rows: List<Row> = emptyList()

    fun submit(list: List<Row>, grid: Boolean) {
        this.rows = list; this.grid = grid
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.F -> if (grid) 1 else 0
        is Row.V -> if (grid) 3 else 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> FolderVH(inf.inflate(R.layout.item_folder, parent, false))
            1 -> FolderVH(inf.inflate(R.layout.item_folder_grid, parent, false))
            2 -> VideoVH(inf.inflate(R.layout.item_video, parent, false))
            else -> VideoVH(inf.inflate(R.layout.item_video_grid, parent, false))
        }
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        when (val r = rows[position]) {
            is Row.F -> (h as FolderVH).bind(r.folder)
            is Row.V -> (h as VideoVH).bind(r.video)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is VideoVH) holder.thumb.tag = null
    }

    inner class FolderVH(v: View) : RecyclerView.ViewHolder(v) {
        private val name = v.findViewById<TextView>(R.id.name)
        private val info = v.findViewById<TextView>(R.id.info)
        private val count = v.findViewById<TextView>(R.id.count)
        fun bind(f: Folder) {
            name.text = Fmt.breakable(f.name)
            count.text = f.count.toString()
            info.text = if (grid) Fmt.size(f.totalSize)
            else "${f.count} video${if (f.count == 1) "" else "s"} • ${Fmt.size(f.totalSize)}"
            itemView.setOnClickListener { onFolder(f) }
        }
    }

    inner class VideoVH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        private val name = v.findViewById<TextView>(R.id.name)
        private val info = v.findViewById<TextView>(R.id.info)
        private val duration = v.findViewById<TextView>(R.id.duration)
        private val badge = v.findViewById<TextView>(R.id.badgeNew)
        private val resume = v.findViewById<ProgressBar>(R.id.resume)
        private val more = v.findViewById<View>(R.id.more)

        fun bind(vd: Video) {
            name.text = Fmt.breakable(vd.name)
            val parts = mutableListOf<String>()
            Fmt.quality(vd.width, vd.height).takeIf { it.isNotEmpty() }?.let { parts += it }
            parts += Fmt.size(vd.size)
            if (!grid && vd.ext.isNotEmpty()) parts += vd.ext
            info.text = parts.joinToString(" • ")
            duration.text = if (vd.duration > 0) Fmt.time(vd.duration) else vd.ext.ifEmpty { "VIDEO" }

            val pos = prefs.position(vd.key)
            val len = prefs.length(vd.key).takeIf { it > 0 } ?: vd.duration
            val played = prefs.played(vd.key)
            badge.visibility = if (Library.isNew(vd, System.currentTimeMillis() / 1000, played)) View.VISIBLE else View.GONE
            if (pos > 0 && len > 0) {
                resume.visibility = View.VISIBLE
                resume.progress = ((pos * 1000) / len).toInt().coerceIn(0, 1000)
            } else resume.visibility = View.GONE

            Thumbs.load(thumb, vd)

            itemView.setOnClickListener { onVideo(vd) }
            more.setOnClickListener { onVideoMore(vd, it) }
            itemView.setOnLongClickListener { onVideoMore(vd, more); true }
        }
    }
}
