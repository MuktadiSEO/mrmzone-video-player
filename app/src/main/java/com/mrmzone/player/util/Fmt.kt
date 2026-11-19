package com.mrmzone.player.util

import java.util.Locale

object Fmt {
    /** 65_000 -> "01:05", 3_725_000 -> "1:02:05" */
    fun time(ms: Long): String {
        if (ms < 0) return "--:--"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** signed delta for seek OSD: "+00:10" / "-01:05" */
    fun delta(ms: Long): String = (if (ms < 0) "-" else "+") + time(kotlin.math.abs(ms))

    fun size(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (i == 0) "${bytes} B" else String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }

    /** "1920x1080" -> "1080p", 3840x2160 -> "4K" */
    fun quality(w: Int, h: Int): String {
        if (w <= 0 || h <= 0) return ""
        val p = minOf(w, h)
        return when {
            p >= 2000 -> "4K"
            p >= 1400 -> "1440p"
            p >= 1000 -> "1080p"
            p >= 700 -> "720p"
            p >= 470 -> "480p"
            p >= 350 -> "360p"
            else -> "${p}p"
        }
    }

    /** Lets long file names wrap at _ . - instead of in the middle of a word ("subs.mk|v"). */
    fun breakable(name: String): String {
        val sb = StringBuilder(name.length + 8)
        for (c in name) { sb.append(c); if (c == '_' || c == '.' || c == '-' || c == '+' || c == ')' || c == ']') sb.append('\u200B') }
        return sb.toString()
    }

    fun percent(v: Float): String = "${(v * 100).toInt()}%"

    fun speed(rate: Float): String {
        val s = String.format(Locale.US, "%.2f", rate).trimEnd('0').trimEnd('.')
        return "${s}x"
    }

    /** VLC fourcc -> human readable codec name */
    fun codec(fourcc: String?): String {
        val c = fourcc?.trim()?.lowercase() ?: return ""
        return when (c) {
            "a52", "ac-3", "ac3" -> "AC3"
            "eac3", "ec-3" -> "E-AC3"
            "dts", "dtsh", "dtse" -> "DTS"
            "trhd", "mlp" -> "TrueHD"
            "mp4a", "aac" -> "AAC"
            "mpga", "mp3", "mp2a" -> "MP3"
            "vorb" -> "Vorbis"
            "opus" -> "Opus"
            "flac" -> "FLAC"
            "alac" -> "ALAC"
            "wma1", "wma2", "wmap", "wmal" -> "WMA"
            "araw", "s16l", "s24l", "s32l", "f32l", "lpcm", "twos", "sowt" -> "PCM"
            "amr", "samr", "sawb" -> "AMR"
            "h264", "avc1" -> "H.264"
            "hevc", "h265", "hev1", "hvc1" -> "H.265"
            "vp80" -> "VP8"
            "vp90" -> "VP9"
            "av01" -> "AV1"
            "mp4v", "xvid", "divx", "dx50" -> "MPEG-4"
            "mpgv", "mp2v" -> "MPEG-2"
            "wmv1", "wmv2", "wmv3", "wvc1" -> "WMV"
            "flv1" -> "FLV"
            "h263" -> "H.263"
            "rv40", "rv30" -> "RealVideo"
            "subt", "srt" -> "SRT"
            "ssa", "ass" -> "ASS"
            "tx3g" -> "TX3G"
            "spu", "spu ", "vobsub" -> "VobSub"
            "pgs" -> "PGS"
            else -> fromDescription(c)
        }
    }

    /** VLC often gives a description ("A52 Audio (aka AC3)", "MPEG AAC Audio") instead of a fourcc. */
    private fun fromDescription(d: String): String {
        val rules = listOf(
            listOf("e-ac3", "eac3", "e-ac-3") to "E-AC3",
            listOf("a52", "ac3", "ac-3") to "AC3",
            listOf("truehd", "mlp") to "TrueHD",
            listOf("dts") to "DTS",
            listOf("aac") to "AAC",
            listOf("layer 1/2/3", "layer 3", "mp3") to "MP3",
            listOf("layer 1/2", "layer 2", "mp2") to "MP2",
            listOf("opus") to "Opus",
            listOf("vorbis") to "Vorbis",
            listOf("flac") to "FLAC",
            listOf("windows media audio", "wma") to "WMA",
            listOf("pcm") to "PCM",
            listOf("hevc", "h265", "h.265") to "H.265",
            listOf("h264", "h.264", "avc") to "H.264",
            listOf("av1") to "AV1",
            listOf("vp9") to "VP9",
            listOf("vp8") to "VP8",
            listOf("mpeg-4", "mpeg4", "xvid", "divx") to "MPEG-4",
            listOf("mpeg-1/2", "mpeg-2", "mpeg2") to "MPEG-2",
            listOf("subrip", "srt") to "SRT",
            listOf("ssa", "ass") to "ASS",
            listOf("pgs") to "PGS",
            listOf("dvd sub", "vobsub") to "VobSub",
            listOf("text subtitle") to "Text",
        )
        for ((keys, name) in rules) if (keys.any { d.contains(it) }) return name
        if (d.length <= 4 && !d.contains(' ')) return d.uppercase() // unknown fourcc
        return d.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }
    }

    fun channels(n: Int): String = when (n) {
        0 -> ""
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "${n}ch"
    }
}
