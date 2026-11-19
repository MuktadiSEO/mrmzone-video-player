package com.mrmzone.player

import com.mrmzone.player.util.Fmt
import org.junit.Assert.assertEquals
import org.junit.Test

class FmtTest {
    @Test fun time() {
        assertEquals("00:00", Fmt.time(0))
        assertEquals("01:05", Fmt.time(65_000))
        assertEquals("59:59", Fmt.time(3_599_000))
        assertEquals("1:02:05", Fmt.time(3_725_000))
        assertEquals("--:--", Fmt.time(-1))
        assertEquals("+00:10", Fmt.delta(10_000))
        assertEquals("-01:05", Fmt.delta(-65_000))
    }

    @Test fun size() {
        assertEquals("0 B", Fmt.size(0))
        assertEquals("512 B", Fmt.size(512))
        assertEquals("1.5 KB", Fmt.size(1536))
        assertEquals("700 MB", Fmt.size(700L * 1024 * 1024))
        assertEquals("1.4 GB", Fmt.size(1_500_000_000))
    }

    @Test fun quality() {
        assertEquals("1080p", Fmt.quality(1920, 1080))
        assertEquals("1080p", Fmt.quality(1080, 1920)) // portrait phone video
        assertEquals("720p", Fmt.quality(1280, 720))
        assertEquals("4K", Fmt.quality(3840, 2160))
        assertEquals("480p", Fmt.quality(854, 480))
        assertEquals("", Fmt.quality(0, 0))
    }

    @Test fun codecs() {
        assertEquals("AC3", Fmt.codec("a52 "))
        assertEquals("E-AC3", Fmt.codec("eac3"))
        assertEquals("DTS", Fmt.codec("dts "))
        assertEquals("TrueHD", Fmt.codec("trhd"))
        assertEquals("AAC", Fmt.codec("mp4a"))
        assertEquals("H.265", Fmt.codec("hevc"))
        assertEquals("H.264", Fmt.codec("h264"))
        assertEquals("XYZ", Fmt.codec("xyz"))
        assertEquals("5.1", Fmt.channels(6))
        assertEquals("Stereo", Fmt.channels(2))
    }

    @Test fun speed() {
        assertEquals("1x", Fmt.speed(1f))
        assertEquals("1.5x", Fmt.speed(1.5f))
        assertEquals("0.25x", Fmt.speed(0.25f))
    }
}

class BreakableTest {
    @Test fun insertsBreakOpportunities() {
        val s = com.mrmzone.player.util.Fmt.breakable("01_h264.mkv")
        org.junit.Assert.assertEquals("01_​h264.​mkv", s)
        org.junit.Assert.assertEquals("01_h264.mkv", s.replace("​", ""))
    }
}

class CodecDescriptionTest {
    @Test fun vlcDescriptions() {
        val f = com.mrmzone.player.util.Fmt
        org.junit.Assert.assertEquals("AC3", f.codec("A52 Audio (aka AC3)"))
        org.junit.Assert.assertEquals("E-AC3", f.codec("A52 Audio (aka E-AC3)".replace("A52 Audio (aka E-AC3)", "E-AC3 Audio")))
        org.junit.Assert.assertEquals("AAC", f.codec("MPEG AAC Audio"))
        org.junit.Assert.assertEquals("MP3", f.codec("MPEG Audio layer 1/2/3"))
        org.junit.Assert.assertEquals("DTS", f.codec("DTS Audio"))
        org.junit.Assert.assertEquals("H.264", f.codec("H264 - MPEG-4 AVC (part 10)"))
        org.junit.Assert.assertEquals("Text", f.codec("Text subtitles with various tags"))
        org.junit.Assert.assertEquals("Some Codec", f.codec("some codec"))
    }
}
