package com.mrmzone.player.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.mrmzone.player.App
import com.mrmzone.player.data.Prefs
import com.mrmzone.player.player.PlayerLogic

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Prefs(this)
        val f = Form(this, "Settings")

        f.header("DECODER")
        f.radios(PlayerLogic.Decoder.entries.map {
            when (it) {
                PlayerLogic.Decoder.HW -> "HW – hardware (fast, saves battery)"
                PlayerLogic.Decoder.HW_PLUS -> "HW+ – force hardware for every codec"
                PlayerLogic.Decoder.SW -> "SW – software (plays everything, uses more CPU)"
            }
        }, p.decoder.ordinal) { p.decoder = PlayerLogic.Decoder.entries[it] }
        f.note("Audio such as AC3, E-AC3, DTS and TrueHD is always decoded in software, so it plays on every phone.")

        f.header("PLAYBACK")
        f.note("Resume playback")
        f.radios(listOf("Always resume", "Ask every time", "Always start from beginning"), p.resumeMode) { p.resumeMode = it }
        f.switch("Play next video in folder automatically", p.autoNext) { p.autoNext = it }
        f.switch("Auto rotate screen to match the video", p.autoRotate) { p.autoRotate = it }
        f.switch("Show remaining time instead of total", p.showRemaining) { p.showRemaining = it }
        f.slider("Double-tap seek", 5, 30, 5, p.doubleTapSeek, { "$it seconds" }) { p.doubleTapSeek = it }

        f.header("GESTURES")
        f.switch("Swipe right side for volume", p.gestureVolume) { p.gestureVolume = it }
        f.switch("Swipe left side for brightness", p.gestureBrightness) { p.gestureBrightness = it }
        f.switch("Swipe left / right to seek", p.gestureSeek) { p.gestureSeek = it }
        f.switch("Pinch to zoom", p.gestureZoom) { p.gestureZoom = it }
        f.switch("Volume boost up to 200%", p.volumeBoost) { p.volumeBoost = it }
        f.switch("Remember brightness", p.rememberBrightness) { p.rememberBrightness = it }

        f.header("SUBTITLES")
        f.slider("Subtitle text size", 50, 200, 10, p.subtitleScale, { "$it%" }) { p.subtitleScale = it }
        f.note("Subtitle files with the same name as the video (.srt, .ass, .ssa, .vtt, .sub) are loaded automatically.")

        f.header("APPEARANCE")
        f.radios(listOf("Light", "Dark", "Follow system"), p.theme) { p.theme = it; App.applyTheme(it) }

        f.header("HISTORY")
        f.add(MaterialButton(this).apply {
            text = "Clear playback history"
            setOnClickListener { p.clearHistory(); Toast.makeText(context, "History cleared", Toast.LENGTH_SHORT).show() }
        })
        f.show()
    }
}
