package com.mrmzone.player.data

/** A video found on the device. Plain Kotlin (no Android types) so it is unit-testable. */
data class Video(
    val id: Long,
    val uri: String,
    val path: String,
    val name: String,
    val folderPath: String,
    val folderName: String,
    val duration: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val dateAdded: Long, // seconds
    val mime: String = "",
) {
    /** Stable key used to remember the playback position. */
    val key: String get() = path.ifEmpty { uri }
    val ext: String get() = name.substringAfterLast('.', "").uppercase()
}

/** [videos] = every video inside (also in sub-folders); [subfolders] = direct child folders that contain videos. */
data class Folder(val path: String, val name: String, val videos: List<Video>, val subfolders: Int = 0) {
    val count get() = videos.size
    val totalSize get() = videos.sumOf { it.size }
    val newest get() = videos.maxOfOrNull { it.dateAdded } ?: 0L
}

enum class SortBy(val label: String) { NAME("Name"), DATE("Date"), SIZE("Size"), LENGTH("Length") }

object Library {
    private val natural = Comparator<String> { a, b -> naturalCompare(a, b) }

    /** "Episode 2" sorts before "Episode 10". */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0; var j = 0
        val x = a.lowercase(); val y = b.lowercase()
        while (i < x.length && j < y.length) {
            val ca = x[i]; val cb = y[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i; while (ei < x.length && x[ei].isDigit()) ei++
                var ej = j; while (ej < y.length && y[ej].isDigit()) ej++
                val na = x.substring(i, ei).trimStart('0'); val nb = y.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb); if (c != 0) return c
                i = ei; j = ej
            } else {
                if (ca != cb) return ca - cb
                i++; j++
            }
        }
        return (x.length - i) - (y.length - j)
    }

    fun sortVideos(list: List<Video>, by: SortBy, asc: Boolean): List<Video> {
        val c: Comparator<Video> = when (by) {
            SortBy.NAME -> compareBy(natural) { it.name }
            SortBy.DATE -> compareBy { it.dateAdded }
            SortBy.SIZE -> compareBy { it.size }
            SortBy.LENGTH -> compareBy { it.duration }
        }
        return list.sortedWith(if (asc) c else c.reversed())
    }

    fun sortFolders(list: List<Folder>, by: SortBy, asc: Boolean): List<Folder> {
        val c: Comparator<Folder> = when (by) {
            SortBy.NAME -> compareBy(natural) { it.name }
            SortBy.DATE -> compareBy { it.newest }
            SortBy.SIZE -> compareBy { it.totalSize }
            SortBy.LENGTH -> compareBy { it.count }
        }
        return list.sortedWith(if (asc) c else c.reversed())
    }

    fun group(videos: List<Video>): List<Folder> =
        videos.groupBy { it.folderPath }.map { (p, v) -> Folder(p, v.first().folderName, v) }

    fun search(list: List<Video>, q: String): List<Video> {
        val t = q.trim()
        if (t.isEmpty()) return list
        val words = t.lowercase().split(Regex("\\s+"))
        return list.filter { v -> val n = v.name.lowercase(); words.all { n.contains(it) } }
    }

    /** Subtitle files next to a video: movie.mkv -> movie.srt, movie.en.srt, ... */
    val SUB_EXT = setOf("srt", "ass", "ssa", "sub", "vtt", "smi", "txt", "idx")

    fun matchesSubtitle(videoName: String, fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext !in SUB_EXT) return false
        val base = videoName.substringBeforeLast('.').lowercase()
        val sub = fileName.substringBeforeLast('.').lowercase()
        return sub == base || sub.startsWith("$base.")
    }

    // ---------------- folder by folder (tree) ----------------

    /** "/storage/emulated/0/DCIM/Camera" -> "/storage/emulated/0" ; "/storage/1A2B-3C4D/Movies" -> "/storage/1A2B-3C4D" */
    fun volumeRoot(path: String): String {
        Regex("^(/storage/emulated/\\d+|/storage/[^/]+|/sdcard|/mnt/[^/]+/[^/]+|/mnt/[^/]+)").find(path)?.let { return it.value }
        return "/" + path.trim('/').substringBefore('/')
    }

    fun volumeName(root: String): String = when {
        root.startsWith("/storage/emulated") || root == "/sdcard" -> "Internal storage"
        root.startsWith("/storage/") -> "SD card"
        else -> root.substringAfterLast('/').ifEmpty { "Storage" }
    }

    data class Level(val folders: List<Folder>, val videos: List<Video>)

    /**
     * What is directly inside [dir]: child folders that contain videos (anywhere below them) and the videos
     * that sit right in [dir]. dir == null means the top: the storage volume's own content, or the
     * volumes themselves when there is more than one (phone + SD card).
     */
    fun level(all: List<Video>, dir: String?): Level {
        if (dir == null) {
            val roots = all.map { volumeRoot(it.folderPath) }.distinct()
            if (roots.size == 1) return level(all, roots[0])
            val vols = roots.map { r ->
                val inside = all.filter { volumeRoot(it.folderPath) == r }
                Folder(r, volumeName(r), inside, level(inside, r).folders.size)
            }
            return Level(vols, emptyList())
        }
        val base = dir.trimEnd('/')
        val direct = all.filter { it.folderPath.trimEnd('/') == base }
        val below = all.filter { it.folderPath.startsWith("$base/") }
        val groups = below.groupBy { it.folderPath.removePrefix("$base/").substringBefore('/') }
        val folders = groups.map { (name, vids) ->
            val childPath = "$base/$name"
            val subs = vids.filter { it.folderPath.startsWith("$childPath/") }
                .map { it.folderPath.removePrefix("$childPath/").substringBefore('/') }.distinct().size
            Folder(childPath, name, vids, subs)
        }
        return Level(folders, direct)
    }

    /** "Videos › DCIM › Camera" for the bottom path bar. */
    fun breadcrumb(dir: String, all: List<Video>): String {
        val roots = all.map { volumeRoot(it.folderPath) }.distinct()
        val root = volumeRoot(dir)
        val rel = dir.removePrefix(root).trim('/')
        val parts = mutableListOf("Videos")
        if (roots.size > 1) parts += volumeName(root)
        if (rel.isNotEmpty()) parts += rel.split('/')
        return parts.joinToString("  ›  ")
    }

    fun isNew(v: Video, nowSec: Long, played: Boolean): Boolean = !played && nowSec - v.dateAdded < 3 * 24 * 3600
}
