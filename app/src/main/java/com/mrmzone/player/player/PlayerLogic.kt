package com.mrmzone.player.player

/** Pure, testable rules used by the player screen. */
object PlayerLogic {
    enum class Repeat { OFF, ONE, ALL }

    /** Index to play after [current] finished, or -1 to stop. */
    fun nextIndex(current: Int, size: Int, repeat: Repeat, autoNext: Boolean): Int = when {
        size <= 0 -> -1
        repeat == Repeat.ONE -> current
        current + 1 < size && (autoNext || repeat == Repeat.ALL) -> current + 1
        repeat == Repeat.ALL -> 0
        else -> -1
    }

    /** Should we offer to resume? Skip tiny positions and the last 3% / 5 seconds. */
    fun shouldResume(pos: Long, length: Long): Boolean {
        if (pos < 5_000) return false
        if (length <= 0) return true
        return pos < length - maxOf(5_000L, length * 3 / 100)
    }

    /**
     * Volume model with boost: level 0..2*max. Up to max it is the system volume,
     * above it the VLC software gain goes 100%..200%.
     * returns pair(systemVolume, vlcVolumePercent)
     */
    fun volume(level: Int, max: Int): Pair<Int, Int> {
        val l = level.coerceIn(0, max * 2)
        return if (l <= max) l to 100 else max to (100 + (l - max) * 100 / max)
    }

    fun volumePercent(level: Int, max: Int): Int = (level.coerceIn(0, max * 2) * 100) / max

    /** Horizontal swipe: full screen width = 90 seconds (MX-like fine control). */
    fun seekDelta(dx: Float, width: Int): Long = if (width <= 0) 0 else (dx / width * 90_000).toLong()

    fun clampSeek(target: Long, length: Long): Long = if (length > 0) target.coerceIn(0, length - 500) else maxOf(0, target)

    val SPEEDS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)

    enum class Aspect(val label: String) {
        FIT("Fit to screen"), FILL("Stretch"), CROP("Crop"), R16_9("16:9"), R4_3("4:3"), ORIGINAL("100%")
    }

    fun nextAspect(a: Aspect): Aspect = Aspect.entries[(a.ordinal + 1) % Aspect.entries.size]

    enum class Decoder(val label: String, val short: String) { HW("HW decoder", "HW"), HW_PLUS("HW+ decoder", "HW+"), SW("SW decoder", "SW") }
}
