package com.mrmzone.player.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.mrmzone.player.data.Prefs
import com.mrmzone.player.data.SortBy
import com.mrmzone.player.data.Video
import com.mrmzone.player.player.PlayerActivity
import com.mrmzone.player.util.Fmt
import java.text.DateFormat
import java.util.Date

object Dialogs {
    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun openUrl(a: Activity) {
        val til = TextInputLayout(a, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "Video URL"
            helperText = "http(s), m3u8, rtsp, rtmp, mms, udp"
        }
        val et = TextInputEditText(til.context).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT
            maxLines = 3
        }
        til.addView(et, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val clip = (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim()
        if (text != null && Regex("^(https?|rtsp|rtmp|mms|udp|ftp)://\\S+$", RegexOption.IGNORE_CASE).matches(text)) et.setText(text)
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 22), dp(a, 12), dp(a, 22), 0)
            addView(til, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(a)
            .setTitle("Network stream")
            .setView(box)
            .setPositiveButton("Play") { _, _ ->
                val u = et.text?.toString()?.trim().orEmpty()
                if (u.isNotEmpty()) a.startActivity(Intent(a, PlayerActivity::class.java).setData(Uri.parse(u)))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun sort(a: Activity, prefs: Prefs, done: () -> Unit) {
        val rg = RadioGroup(a).apply { setPadding(dp(a, 20), dp(a, 8), dp(a, 20), 0) }
        SortBy.entries.forEachIndexed { i, s ->
            rg.addView(RadioButton(a).apply { text = s.label; id = 100 + i; isChecked = prefs.sortBy == s; textSize = 15f })
        }
        val order = RadioGroup(a).apply { orientation = RadioGroup.HORIZONTAL; setPadding(dp(a, 20), dp(a, 8), dp(a, 20), 0) }
        order.addView(RadioButton(a).apply { text = "Ascending"; id = 1; isChecked = prefs.sortAsc })
        order.addView(RadioButton(a).apply { text = "Descending"; id = 2; isChecked = !prefs.sortAsc })
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; addView(rg); addView(order) }
        MaterialAlertDialogBuilder(a)
            .setTitle("Sort by")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                prefs.sortBy = SortBy.entries[(rg.checkedRadioButtonId - 100).coerceIn(0, SortBy.entries.size - 1)]
                prefs.sortAsc = order.checkedRadioButtonId != 2
                done()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun folderMode(a: Activity, prefs: Prefs, done: () -> Unit) {
        val items = arrayOf(
            "All folders – every folder that has videos, in one list",
            "Folder by folder – open folders one level at a time",
        )
        MaterialAlertDialogBuilder(a).setTitle("Show folders")
            .setSingleChoiceItems(items, prefs.folderMode) { d, w -> prefs.folderMode = w; d.dismiss(); done() }
            .setNegativeButton("Cancel", null).show()
    }

    fun confirm(a: Activity, title: String, msg: String, ok: String = "OK", yes: () -> Unit) {
        MaterialAlertDialogBuilder(a).setTitle(title).setMessage(msg)
            .setPositiveButton(ok) { _, _ -> yes() }
            .setNegativeButton("Cancel", null).show()
    }

    fun properties(a: Activity, v: Video, prefs: Prefs) {
        val rows = linkedMapOf(
            "File" to v.name,
            "Location" to v.folderPath,
            "Size" to Fmt.size(v.size),
            "Length" to (if (v.duration > 0) Fmt.time(v.duration) else "Unknown"),
            "Resolution" to (if (v.width > 0) "${v.width} × ${v.height} (${Fmt.quality(v.width, v.height)})" else "Unknown"),
            "Format" to listOf(v.ext, v.mime).filter { it.isNotEmpty() }.joinToString(" • "),
            "Added" to DateFormat.getDateTimeInstance().format(Date(v.dateAdded * 1000)),
        )
        val pos = prefs.position(v.key)
        if (pos > 0) rows["Last position"] = Fmt.time(pos)
        val msg = rows.entries.joinToString("\n\n") { "${it.key}\n${it.value}" }
        MaterialAlertDialogBuilder(a).setTitle("Properties").setMessage(msg).setPositiveButton("OK", null).show()
    }

    fun about(a: Activity) {
        val ver = runCatching { a.packageManager.getPackageInfo(a.packageName, 0).versionName }.getOrDefault("")
        MaterialAlertDialogBuilder(a).setTitle("MRM Zone Player $ver")
            .setMessage(
                "HD video player for every format.\n\n" +
                    "Video: MKV, MP4, AVI, MOV, WMV, FLV, WEBM, TS, M2TS, 3GP, VOB, RMVB, OGV…\n" +
                    "Audio: AC3, E-AC3, DTS, TrueHD, AAC, MP3, FLAC, Opus, Vorbis, WMA, PCM…\n" +
                    "Subtitles: SRT, ASS/SSA, VTT, SUB/IDX, PGS, embedded tracks.\n\n" +
                    "Hardware (HW / HW+) and software (SW) decoding, gestures, lock, speed, PiP.\n\n" +
                    "Powered by the open-source VLC engine (LGPL).\n© MRM Zone"
            )
            .setPositiveButton("OK", null).show()
    }
}
