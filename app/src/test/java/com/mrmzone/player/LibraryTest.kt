package com.mrmzone.player

import com.mrmzone.player.data.Library
import com.mrmzone.player.data.SortBy
import com.mrmzone.player.data.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    private fun v(name: String, folder: String = "/sdcard/Movies", size: Long = 1, date: Long = 1, dur: Long = 1) =
        Video(name.hashCode().toLong(), "content://x/$name", "$folder/$name", name, folder, folder.substringAfterLast('/'), dur, size, 0, 0, date)

    @Test fun naturalSort() {
        val list = listOf(v("Episode 10.mkv"), v("Episode 2.mkv"), v("episode 1.mkv"), v("Alpha.mp4"))
        assertEquals(listOf("Alpha.mp4", "episode 1.mkv", "Episode 2.mkv", "Episode 10.mkv"),
            Library.sortVideos(list, SortBy.NAME, true).map { it.name })
        assertEquals("Episode 10.mkv", Library.sortVideos(list, SortBy.NAME, false).first().name)
    }

    @Test fun sortBySizeDateLength() {
        val a = v("a", size = 5, date = 3, dur = 1); val b = v("b", size = 9, date = 1, dur = 7); val c = v("c", size = 1, date = 2, dur = 4)
        assertEquals(listOf("b", "a", "c"), Library.sortVideos(listOf(a, b, c), SortBy.SIZE, false).map { it.name })
        assertEquals(listOf("b", "c", "a"), Library.sortVideos(listOf(a, b, c), SortBy.DATE, true).map { it.name })
        assertEquals(listOf("a", "c", "b"), Library.sortVideos(listOf(a, b, c), SortBy.LENGTH, true).map { it.name })
    }

    @Test fun groupFolders() {
        val list = listOf(v("1.mp4", "/s/Movies"), v("2.mkv", "/s/Movies"), v("3.avi", "/s/WhatsApp Video"))
        val f = Library.sortFolders(Library.group(list), SortBy.NAME, true)
        assertEquals(2, f.size)
        assertEquals("Movies", f[0].name); assertEquals(2, f[0].count)
        assertEquals("WhatsApp Video", f[1].name); assertEquals(1, f[1].count)
        assertEquals(listOf("WhatsApp Video", "Movies"), Library.sortFolders(Library.group(list), SortBy.LENGTH, true).map { it.name })
    }

    @Test fun search() {
        val list = listOf(v("Avengers Endgame 1080p.mkv"), v("Bangla Natok.mp4"), v("avengers infinity.avi"))
        assertEquals(2, Library.search(list, "avengers").size)
        assertEquals(1, Library.search(list, "  avengers  1080 ").size)
        assertEquals(3, Library.search(list, "").size)
        assertEquals(0, Library.search(list, "zzz").size)
    }

    @Test fun subtitleMatching() {
        assertTrue(Library.matchesSubtitle("Movie.mkv", "Movie.srt"))
        assertTrue(Library.matchesSubtitle("Movie.mkv", "movie.en.ass"))
        assertFalse(Library.matchesSubtitle("Movie.mkv", "Movie.mp4"))
        assertFalse(Library.matchesSubtitle("Movie.mkv", "Other.srt"))
    }

    @Test fun isNew() {
        val now = 10_000_000L
        assertTrue(Library.isNew(v("a", date = now - 3600), now, false))
        assertFalse(Library.isNew(v("a", date = now - 3600), now, true))
        assertFalse(Library.isNew(v("a", date = now - 5 * 24 * 3600), now, false))
    }
}

class FolderTreeTest {
    private fun v(path: String) = Video(path.hashCode().toLong(), "content://x$path", path, path.substringAfterLast('/'),
        path.substringBeforeLast('/'), path.substringBeforeLast('/').substringAfterLast('/'), 1, 10, 0, 0, 1)

    private val all = listOf(
        v("/storage/emulated/0/DCIM/Camera/a.mp4"),
        v("/storage/emulated/0/DCIM/Camera/b.mp4"),
        v("/storage/emulated/0/DCIM/ScreenRecorder/c.mp4"),
        v("/storage/emulated/0/Download/d.mkv"),
        v("/storage/emulated/0/Download/Telegram/e.mkv"),
        v("/storage/emulated/0/IDM/f.mp4"),
    )

    @Test fun topLevelShowsFoldersOfInternalStorage() {
        val l = Library.level(all, null)
        assertEquals(setOf("DCIM", "Download", "IDM"), l.folders.map { it.name }.toSet())
        assertTrue(l.videos.isEmpty())
        val dcim = l.folders.first { it.name == "DCIM" }
        assertEquals(3, dcim.count)       // all videos below DCIM
        assertEquals(2, dcim.subfolders)  // Camera + ScreenRecorder
        assertEquals(0, l.folders.first { it.name == "IDM" }.subfolders)
    }

    @Test fun insideAFolderShowsSubfoldersAndItsOwnVideos() {
        val l = Library.level(all, "/storage/emulated/0/Download")
        assertEquals(listOf("Telegram"), l.folders.map { it.name })
        assertEquals(listOf("d.mkv"), l.videos.map { it.name })
    }

    @Test fun sdCardAppearsAsSecondVolume() {
        val l = Library.level(all + v("/storage/1A2B-3C4D/Movies/m.mkv"), null)
        assertEquals(setOf("Internal storage", "SD card"), l.folders.map { it.name }.toSet())
    }

    @Test fun breadcrumb() {
        assertEquals("Videos  ›  DCIM  ›  Camera", Library.breadcrumb("/storage/emulated/0/DCIM/Camera", all))
        assertEquals("Videos", Library.breadcrumb("/storage/emulated/0", all))
    }
}
