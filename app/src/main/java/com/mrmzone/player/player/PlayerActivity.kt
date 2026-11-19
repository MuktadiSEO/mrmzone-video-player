package com.mrmzone.player.player

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.mrmzone.player.R
import com.mrmzone.player.data.HistoryEntry
import com.mrmzone.player.data.Prefs
import com.mrmzone.player.data.Video
import com.mrmzone.player.player.PlayerLogic.Aspect
import com.mrmzone.player.player.PlayerLogic.Decoder
import com.mrmzone.player.player.PlayerLogic.Repeat
import com.mrmzone.player.util.Fmt
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.util.Locale
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {

    data class Item(val uri: String, val path: String, val title: String) {
        val key get() = path.ifEmpty { uri }
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_INDEX = "index"
        const val EXTRA_FROM_START = "fromStart"
        const val EXTRA_USE_HOLDER = "useHolder"

        /** Playlist handed over from the library (kept in memory: a folder can hold thousands of videos). */
        @Volatile private var holder: List<Item> = emptyList()

        fun start(ctx: Context, videos: List<Video>, index: Int, fromStart: Boolean) {
            holder = videos.map { Item(it.uri, it.path, it.name) }
            val v = videos[index]
            ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java)
                    .setData(Uri.parse(v.uri))
                    .putExtra(EXTRA_TITLE, v.name)
                    .putExtra(EXTRA_INDEX, index)
                    .putExtra(EXTRA_FROM_START, fromStart)
                    .putExtra(EXTRA_USE_HOLDER, true)
            )
        }
    }

    private enum class Gesture { NONE, SEEK, VOLUME, BRIGHTNESS }
    private enum class Orient(val label: String) { VIDEO("Auto (match video)"), LANDSCAPE("Landscape"), PORTRAIT("Portrait"), SENSOR("Auto rotate") }

    private lateinit var prefs: Prefs
    private val debugLog by lazy { (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
    private lateinit var libVLC: LibVLC
    private lateinit var mp: MediaPlayer
    private lateinit var audio: AudioManager
    private val ui = Handler(Looper.getMainLooper())

    // views
    private lateinit var root: View
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var touch: View
    private lateinit var controls: View
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var titleView: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnDecoder: TextView
    private lateinit var btnRepeat: ImageButton
    private lateinit var btnUnlock: ImageButton
    private lateinit var seek: SeekBar
    private lateinit var posText: TextView
    private lateinit var durText: TextView
    private lateinit var osd: View
    private lateinit var osdIcon: ImageView
    private lateinit var osdText: TextView
    private lateinit var osdBar: ProgressBar
    private lateinit var tapLeft: TextView
    private lateinit var tapRight: TextView
    private lateinit var loading: ProgressBar
    private lateinit var resumeBar: View

    // state
    private var items: List<Item> = emptyList()
    private var index = 0
    private var pfd: ParcelFileDescriptor? = null
    private var decoder = Decoder.HW
    private var triedSwFallback = false
    private var subAutoDone = false
    private var aspect = Aspect.FIT
    private var orient = Orient.VIDEO
    private var repeat = Repeat.OFF
    private var locked = false
    private var userSeeking = false
    private var wasPlaying = true
    private var length = 0L
    private var started = false
    private var videoW = 0
    private var videoH = 0
    private var zoom = 1f
    private var sleepAt = 0L
    private var sleepEndOfVideo = false
    private var pausedByFocusLoss = false
    private var focusRequest: AudioFocusRequest? = null
    private var holdingFast = false
    private var rateBeforeHold = 1f

    // gesture state
    private var gesture = Gesture.NONE
    private var downX = 0f
    private var downY = 0f
    private var startTime = 0L
    private var seekTarget = 0L
    private var lastLiveSeek = 0L
    private var startVolume = 0f
    private var startBrightness = 0.5f
    private var scaling = false
    private var tapAccum = 0
    private var lastTapSide = 0
    private var lastTapTime = 0L

    private val hideControls = Runnable { showControls(false) }
    private val hideOsd = Runnable { osd.visibility = View.GONE }
    private val hideTaps = Runnable { tapLeft.visibility = View.GONE; tapRight.visibility = View.GONE; tapAccum = 0 }
    private val hideUnlock = Runnable { if (locked) btnUnlock.visibility = View.GONE }
    private val hideResume = Runnable { resumeBar.visibility = View.GONE }
    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            if (debugLog && mp.isPlaying) android.util.Log.i("MRMPlayer", "tick file=${items.getOrNull(index)?.title} time=${mp.time} len=${mp.length} audio=${mp.audioTrack} spu=${mp.spuTrack}")
            if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) { sleepAt = 0; mp.pause(); toast("Sleep timer: paused") }
            ui.postDelayed(this, if (controls.visibility == View.VISIBLE) 250 else 1000)
        }
    }

    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { loadSubtitle(it) } }

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (mp.isPlaying) { mp.pause(); toast("Headphones disconnected – paused") } }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (mp.isPlaying) { pausedByFocusLoss = true; mp.pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mp.volume = 40
            AudioManager.AUDIOFOCUS_GAIN -> {
                mp.volume = 100
                if (pausedByFocusLoss) { pausedByFocusLoss = false; mp.play() }
            }
        }
    }

    // =====================================================================
    // lifecycle
    // =====================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        prefs = Prefs(this)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        volumeControlStream = AudioManager.STREAM_MUSIC
        decoder = prefs.decoder
        aspect = Aspect.entries.getOrElse(prefs.aspect) { Aspect.FIT }
        bindViews()
        setupImmersive()
        if (prefs.rememberBrightness && prefs.brightness > 0) setBrightness(prefs.brightness)

        libVLC = LibVLC(this, vlcOptions())
        mp = MediaPlayer(libVLC)
        mp.setEventListener(::onVlcEvent)

        setupControls()
        setupGestures()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (locked) { showUnlockButton(); toast("Screen is locked – tap the lock to unlock") }
                else finish()
            }
        })
        registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        loadIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        savePosition()
        loadIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (!mp.vlcVout.areViewsAttached()) mp.attachViews(videoLayout, null, true, false)
        applyAspect()
        if (started) {
            if (wasPlaying) mp.play() else if (mp.isSeekable) mp.time = mp.time // redraw a frame while paused
        }
        ui.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        if (!isInPip()) {
            wasPlaying = mp.isPlaying
            mp.pause()
            mp.detachViews()
        } else if (isFinishing) mp.stop()
        ui.removeCallbacks(ticker)
        if (prefs.rememberBrightness) window.attributes.screenBrightness.takeIf { it > 0 }?.let { prefs.brightness = it }
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(noisy) }
        abandonFocus()
        mp.setEventListener(null)
        runCatching { mp.stop() }
        if (mp.vlcVout.areViewsAttached()) mp.detachViews()
        mp.release()
        libVLC.release()
        runCatching { pfd?.close() }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            showControls(false)
            osd.visibility = View.GONE; resumeBar.visibility = View.GONE; btnUnlock.visibility = View.GONE
            touch.visibility = View.GONE
        } else {
            touch.visibility = View.VISIBLE
            if (lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED) finish() // PiP window closed
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // VLC re-computes the surface size on layout; keep zoom & aspect
        ui.post { applyAspect(); videoLayout.scaleX = zoom; videoLayout.scaleY = zoom }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_HEADSETHOOK -> { togglePlay(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { mp.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { mp.pause(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { playNext(true); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { playPrev(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(10_000); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10_000); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // =====================================================================
    // VLC
    // =====================================================================
    private fun vlcOptions(): ArrayList<String> {
        val debug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return arrayListOf(
            "--audio-time-stretch",            // keep voice pitch when speed changes
            "--avcodec-skiploopfilter", "1",   // faster SW decoding of HD H.264 (no visible loss)
            "--avcodec-skip-frame", "0",
            "--avcodec-skip-idct", "0",
            "--avcodec-threads", "0",          // all CPU cores
            "--sub-text-scale", prefs.subtitleScale.toString(),
            "--network-caching", "1500",
            "--http-reconnect",
            "--no-stats",
            if (debug) "-vv" else "-q",
        )
    }

    private fun loadIntent(i: Intent) {
        val fromHolder = i.getBooleanExtra(EXTRA_USE_HOLDER, false) && holder.isNotEmpty()
        val data = i.data
        if (fromHolder) {
            items = holder
            index = i.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, items.size - 1)
            i.removeExtra(EXTRA_USE_HOLDER) // after process death we fall back to the single uri
        } else if (data != null) {
            items = listOf(itemFromUri(data, i.getStringExtra(EXTRA_TITLE)))
            index = 0
        } else { toast("Nothing to play"); finish(); return }
        playItem(index, i.getBooleanExtra(EXTRA_FROM_START, false))
    }

    /** Build a playlist item for a uri coming from another app / file manager / browser. */
    private fun itemFromUri(uri: Uri, title: String?): Item {
        var path = ""
        var name = title
        when (uri.scheme?.lowercase()) {
            "file" -> { path = uri.path.orEmpty(); if (name == null) name = File(path).name }
            "content" -> {
                runCatching {
                    @Suppress("DEPRECATION")
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val iName = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val iData = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                            if (name == null && iName >= 0) name = c.getString(iName)
                            if (iData >= 0) c.getString(iData)?.let { p -> if (File(p).canRead()) path = p }
                        }
                    }
                }
                if (name == null) runCatching {
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) name = c.getString(0)
                    }
                }
            }
        }
        if (name.isNullOrBlank()) name = Uri.decode(uri.lastPathSegment ?: uri.toString()).substringAfterLast('/').ifBlank { uri.toString() }
        return Item(uri.toString(), path, name!!)
    }

    private fun buildMedia(item: Item, startMs: Long): Media {
        runCatching { pfd?.close() }; pfd = null
        val uri = Uri.parse(item.uri)
        val media = when {
            item.path.isNotEmpty() && File(item.path).canRead() -> Media(libVLC, item.path)
            uri.scheme.equals("content", true) -> {
                pfd = contentResolver.openFileDescriptor(uri, "r")
                Media(libVLC, pfd!!.fileDescriptor)
            }
            uri.scheme.equals("file", true) -> Media(libVLC, uri.path!!)
            else -> Media(libVLC, uri)
        }
        when (decoder) {
            Decoder.HW -> media.setHWDecoderEnabled(true, false)
            Decoder.HW_PLUS -> media.setHWDecoderEnabled(true, true)
            Decoder.SW -> media.setHWDecoderEnabled(false, false)
        }
        if (startMs > 0) media.addOption(String.format(Locale.US, ":start-time=%.3f", startMs / 1000.0))
        return media
    }

    private fun playItem(i: Int, fromStart: Boolean) {
        if (i !in items.indices) return
        index = i
        val item = items[i]
        titleView.text = item.title
        updateNavButtons()
        triedSwFallback = false
        val saved = prefs.position(item.key)
        val savedLen = prefs.length(item.key)
        val canResume = !fromStart && PlayerLogic.shouldResume(saved, savedLen)
        when {
            !canResume || prefs.resumeMode == 2 -> startPlayback(0, false)
            prefs.resumeMode == 0 -> startPlayback(saved, true)
            else -> MaterialAlertDialogBuilder(this)
                .setTitle("Resume playback?")
                .setMessage("Continue “${item.title}” from ${Fmt.time(saved)}?")
                .setPositiveButton("Resume") { _, _ -> startPlayback(saved, false) }
                .setNegativeButton("Start over") { _, _ -> startPlayback(0, false) }
                .setOnCancelListener { startPlayback(saved, false) }
                .show()
        }
    }

    private fun startPlayback(startMs: Long, showResumeBar: Boolean) {
        val item = items[index]
        val media = try { buildMedia(item, startMs) } catch (e: Exception) {
            showError("Can't open this file.\n${e.message ?: ""}"); return
        }
        length = 0; videoW = 0; videoH = 0
        subAutoDone = false
        mp.media = media
        media.release()
        mp.rate = 1f
        loading.visibility = View.VISIBLE
        requestFocus()
        mp.play()
        started = true
        wasPlaying = true
        btnDecoder.text = decoder.short
        prefs.addHistory(HistoryEntry(item.key, item.uri, item.title, System.currentTimeMillis()))
        if (showResumeBar) {
            findViewById<TextView>(R.id.resumeText).text = "Resumed from ${Fmt.time(startMs)}"
            resumeBar.visibility = View.VISIBLE
            ui.removeCallbacks(hideResume); ui.postDelayed(hideResume, 6000)
        } else resumeBar.visibility = View.GONE
        showControls(true)
    }

    private fun onVlcEvent(e: MediaPlayer.Event) {
        when (e.type) {
            MediaPlayer.Event.Playing, MediaPlayer.Event.EndReached, MediaPlayer.Event.EncounteredError, MediaPlayer.Event.Vout ->
                if (debugLog) android.util.Log.i("MRMPlayer", "event=${e.type} file=${items.getOrNull(index)?.title} time=${mp.time} vout=${e.voutCount}")
        }
        when (e.type) {
            MediaPlayer.Event.Opening -> loading.visibility = View.VISIBLE
            MediaPlayer.Event.Playing -> { loading.visibility = View.GONE; updatePlayIcon(); keepScreenOn(true); autoSelectSubtitle() }
            MediaPlayer.Event.Paused -> { updatePlayIcon(); keepScreenOn(false); savePosition() }
            MediaPlayer.Event.Stopped -> { updatePlayIcon(); keepScreenOn(false) }
            MediaPlayer.Event.Buffering -> loading.visibility = if (e.buffering < 100f && !mp.isPlaying) View.VISIBLE else View.GONE
            MediaPlayer.Event.LengthChanged -> { length = e.lengthChanged; updateProgress() }
            MediaPlayer.Event.Vout -> if (e.voutCount > 0) { loading.visibility = View.GONE; onVideoSize() }
            MediaPlayer.Event.ESAdded -> when (e.esChangedType) {
                IMedia.Track.Type.Video -> onVideoSize()
                IMedia.Track.Type.Text -> autoSelectSubtitle()
            }
            MediaPlayer.Event.EndReached -> onEnd()
            MediaPlayer.Event.EncounteredError -> onPlaybackError()
        }
    }

    /** Like MX: show the first subtitle track automatically (once per video, so "Disable" sticks). */
    private fun autoSelectSubtitle() {
        if (subAutoDone) return
        if (mp.spuTrack >= 0) { subAutoDone = true; return }
        val first = mp.spuTracks?.firstOrNull { it.id >= 0 } ?: return
        subAutoDone = true
        mp.setSpuTrack(first.id)
    }

    private fun onVideoSize() {
        val t = mp.currentVideoTrack ?: return
        var w = t.width; var h = t.height
        if (t.sarNum > 0 && t.sarDen > 0) w = w * t.sarNum / t.sarDen
        if (t.orientation == IMedia.VideoTrack.Orientation.LeftTop || t.orientation == IMedia.VideoTrack.Orientation.RightBottom ||
            t.orientation == IMedia.VideoTrack.Orientation.RightTop || t.orientation == IMedia.VideoTrack.Orientation.LeftBottom) { val x = w; w = h; h = x }
        if (w <= 0 || h <= 0) return
        videoW = w; videoH = h
        applyOrientation()
        applyAspect()
        if (Build.VERSION.SDK_INT >= 26 && isInPip()) runCatching { setPictureInPictureParams(pipParams()) }
    }

    private fun onEnd() {
        prefs.savePosition(items[index].key, 0, length)
        if (sleepEndOfVideo) { sleepEndOfVideo = false; keepScreenOn(false); updatePlayIcon(); toast("Sleep timer: stopped"); return }
        val next = PlayerLogic.nextIndex(index, items.size, repeat, prefs.autoNext)
        if (next >= 0) playItem(next, true) else finish()
    }

    private fun onPlaybackError() {
        loading.visibility = View.GONE
        if (decoder != Decoder.SW && !triedSwFallback) {
            triedSwFallback = true
            val t = maxOf(0L, mp.time)
            decoder = Decoder.SW
            toast("HW decoder failed – switched to SW decoder")
            startPlayback(t, false)
            triedSwFallback = true
            return
        }
        showError("This video can't be played. The file may be damaged or incomplete.")
    }

    private fun showError(msg: String) {
        if (isFinishing) return
        MaterialAlertDialogBuilder(this).setTitle("Can't play").setMessage(msg)
            .setPositiveButton(if (index + 1 < items.size) "Next video" else "Close") { _, _ ->
                if (index + 1 < items.size) playItem(index + 1, true) else finish()
            }
            .setNegativeButton("Close") { _, _ -> finish() }
            .setCancelable(false).show()
    }

    private fun savePosition() {
        if (!started || items.isEmpty()) return
        val t = mp.time
        val len = if (length > 0) length else mp.length
        if (t > 0) prefs.savePosition(items[index].key, if (len > 0 && t >= len - 2000) 0 else t, len)
    }

    // =====================================================================
    // views & controls
    // =====================================================================
    private fun bindViews() {
        root = findViewById(R.id.root)
        videoLayout = findViewById(R.id.videoLayout)
        touch = findViewById(R.id.touch)
        controls = findViewById(R.id.controls)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        titleView = findViewById(R.id.title)
        btnPlay = findViewById(R.id.btnPlay)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnDecoder = findViewById(R.id.btnDecoder)
        btnRepeat = findViewById(R.id.btnRepeat)
        btnUnlock = findViewById(R.id.btnUnlock)
        seek = findViewById(R.id.seek)
        posText = findViewById(R.id.posText)
        durText = findViewById(R.id.durText)
        osd = findViewById(R.id.osd)
        osdIcon = findViewById(R.id.osdIcon)
        osdText = findViewById(R.id.osdText)
        osdBar = findViewById(R.id.osdBar)
        tapLeft = findViewById(R.id.tapLeft)
        tapRight = findViewById(R.id.tapRight)
        loading = findViewById(R.id.loading)
        resumeBar = findViewById(R.id.resumeBar)
    }

    private fun setupImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val tbBase = intArrayOf(topBar.paddingLeft, topBar.paddingTop, topBar.paddingRight, topBar.paddingBottom)
        val bbBase = intArrayOf(bottomBar.paddingLeft, bottomBar.paddingTop, bottomBar.paddingRight, bottomBar.paddingBottom)
        val unlockLp = btnUnlock.layoutParams as android.widget.FrameLayout.LayoutParams
        val unlockBase = unlockLp.marginStart
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            val s = insets.getInsetsIgnoringVisibility(types)
            val c = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val l = maxOf(s.left, c.left); val r = maxOf(s.right, c.right)
            val t = maxOf(s.top, c.top); val b = maxOf(s.bottom, c.bottom)
            topBar.setPadding(tbBase[0] + l, tbBase[1] + t, tbBase[2] + r, tbBase[3])
            bottomBar.setPadding(bbBase[0] + l, bbBase[1], bbBase[2] + r, bbBase[3] + b)
            unlockLp.marginStart = unlockBase + l
            btnUnlock.layoutParams = unlockLp
            (resumeBar.layoutParams as android.widget.FrameLayout.LayoutParams).let { it.marginStart = dp(16) + l; it.bottomMargin = dp(120) + b; resumeBar.layoutParams = it }
            insets
        }
        hideSystemBars()
    }

    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun setupControls() {
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnPlay.setOnClickListener { togglePlay(); scheduleHide() }
        btnNext.setOnClickListener { playNext(true); scheduleHide() }
        btnPrev.setOnClickListener { playPrev(); scheduleHide() }
        findViewById<View>(R.id.btnLock).setOnClickListener { lock(true) }
        btnUnlock.setOnClickListener { lock(false) }
        findViewById<View>(R.id.btnRotate).setOnClickListener { cycleOrientation(); scheduleHide() }
        findViewById<View>(R.id.btnAspect).setOnClickListener { cycleAspect(); scheduleHide() }
        findViewById<View>(R.id.btnSpeed).setOnClickListener { speedDialog() }
        btnRepeat.setOnClickListener { cycleRepeat(); scheduleHide() }
        btnDecoder.setOnClickListener { decoderDialog() }
        findViewById<View>(R.id.btnAudio).setOnClickListener { audioDialog() }
        findViewById<View>(R.id.btnSubs).setOnClickListener { subtitleDialog() }
        findViewById<View>(R.id.btnMore).setOnClickListener { moreMenu(it) }
        findViewById<View>(R.id.resumeStartOver).setOnClickListener {
            resumeBar.visibility = View.GONE
            if (mp.isSeekable) mp.time = 0 else startPlayback(0, false)
        }
        val pip = findViewById<View>(R.id.btnPip)
        if (Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            pip.setOnClickListener { enterPip() }
        } else pip.visibility = View.GONE
        durText.setOnClickListener { prefs.showRemaining = !prefs.showRemaining; updateProgress() }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                posText.text = Fmt.time(p.toLong())
                val now = System.currentTimeMillis()
                if (now - lastLiveSeek > 150) { lastLiveSeek = now; mp.time = p.toLong() }
            }
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true; ui.removeCallbacks(hideControls) }
            override fun onStopTrackingTouch(sb: SeekBar) { userSeeking = false; mp.time = sb.progress.toLong(); scheduleHide() }
        })
        btnDecoder.text = decoder.short
        updateRepeatIcon()
    }

    private fun showControls(show: Boolean) {
        if (locked && show) { showUnlockButton(); return }
        controls.visibility = if (show) View.VISIBLE else View.GONE
        if (show) { updateProgress(); scheduleHide() } else ui.removeCallbacks(hideControls)
    }

    private fun scheduleHide() {
        ui.removeCallbacks(hideControls)
        if (mp.isPlaying || !started) ui.postDelayed(hideControls, 3500)
    }

    private fun togglePlay() {
        if (mp.isPlaying) mp.pause()
        else {
            if (mp.playerState == IMedia.State.Ended || mp.playerState == IMedia.State.Stopped) startPlayback(0, false)
            else { requestFocus(); mp.play() }
        }
        updatePlayIcon()
    }

    private fun updatePlayIcon() {
        btnPlay.setImageResource(if (mp.isPlaying) R.drawable.ic_pause_w else R.drawable.ic_play_w)
        if (!mp.isPlaying) ui.removeCallbacks(hideControls) else if (controls.visibility == View.VISIBLE) scheduleHide()
    }

    private fun updateNavButtons() {
        btnPrev.alpha = if (index > 0) 1f else 0.4f
        btnNext.alpha = if (index + 1 < items.size) 1f else 0.4f
    }

    private fun updateProgress() {
        if (!::mp.isInitialized) return
        val len = if (length > 0) length else mp.length
        val t = maxOf(0L, mp.time)
        if (len > 0) {
            seek.isEnabled = true
            if (seek.max != len.toInt()) seek.max = len.toInt()
            if (!userSeeking && gesture != Gesture.SEEK) { seek.progress = t.toInt(); posText.text = Fmt.time(t) }
            durText.text = if (prefs.showRemaining) "-" + Fmt.time(maxOf(0, len - t)) else Fmt.time(len)
        } else {
            seek.isEnabled = false
            posText.text = Fmt.time(t)
            durText.text = if (started && mp.isPlaying) "LIVE" else "--:--"
        }
    }

    private fun playNext(user: Boolean) {
        if (index + 1 < items.size) { savePosition(); playItem(index + 1, true) }
        else if (user) toast("This is the last video")
    }

    private fun playPrev() {
        if (mp.time > 5000 || index == 0) { mp.time = 0; return }
        savePosition(); playItem(index - 1, true)
    }

    private fun seekBy(ms: Long) {
        if (!mp.isSeekable) return
        val len = if (length > 0) length else mp.length
        val t = PlayerLogic.clampSeek(mp.time + ms, len)
        mp.time = t
        updateProgress()
    }

    private fun lock(on: Boolean) {
        locked = on
        if (on) {
            showControls(false)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            showUnlockButton()
            toast("Screen locked")
        } else {
            btnUnlock.visibility = View.GONE
            applyOrientation()
            showControls(true)
        }
    }

    private fun showUnlockButton() {
        btnUnlock.visibility = View.VISIBLE
        ui.removeCallbacks(hideUnlock); ui.postDelayed(hideUnlock, 3000)
    }

    // ---------- orientation / aspect / zoom ----------
    private fun applyOrientation() {
        if (locked) return
        requestedOrientation = when (orient) {
            Orient.VIDEO -> when {
                !prefs.autoRotate || videoW == 0 -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                videoW >= videoH -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            Orient.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            Orient.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            Orient.SENSOR -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }

    private fun cycleOrientation() {
        val landscapeNow = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        orient = when (orient) {
            Orient.VIDEO -> if (landscapeNow) Orient.PORTRAIT else Orient.LANDSCAPE
            Orient.LANDSCAPE -> Orient.PORTRAIT
            Orient.PORTRAIT -> Orient.SENSOR
            Orient.SENSOR -> Orient.VIDEO
        }
        applyOrientation()
        flash(R.drawable.ic_rotate, orient.label)
    }

    private fun applyAspect() {
        mp.videoScale = when (aspect) {
            Aspect.FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
            Aspect.FILL -> MediaPlayer.ScaleType.SURFACE_FILL
            Aspect.CROP -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            Aspect.R16_9 -> MediaPlayer.ScaleType.SURFACE_16_9
            Aspect.R4_3 -> MediaPlayer.ScaleType.SURFACE_4_3
            Aspect.ORIGINAL -> MediaPlayer.ScaleType.SURFACE_ORIGINAL
        }
    }

    private fun cycleAspect() {
        aspect = PlayerLogic.nextAspect(aspect)
        prefs.aspect = aspect.ordinal
        setZoom(1f)
        applyAspect()
        flash(R.drawable.ic_aspect, aspect.label)
    }

    private fun setZoom(z: Float) {
        zoom = z.coerceIn(0.5f, 4f)
        videoLayout.scaleX = zoom; videoLayout.scaleY = zoom
        if (zoom == 1f) { videoLayout.translationX = 0f; videoLayout.translationY = 0f }
    }

    private fun cycleRepeat() {
        repeat = Repeat.entries[(repeat.ordinal + 1) % Repeat.entries.size]
        updateRepeatIcon()
        flash(if (repeat == Repeat.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
            when (repeat) { Repeat.OFF -> "Repeat off"; Repeat.ONE -> "Repeat this video"; Repeat.ALL -> "Repeat all" })
    }

    private fun updateRepeatIcon() {
        btnRepeat.setImageResource(if (repeat == Repeat.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        btnRepeat.alpha = if (repeat == Repeat.OFF) 0.5f else 1f
    }

    // ---------- PiP ----------
    private fun isInPip() = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode

    @SuppressLint("NewApi")
    private fun pipParams(): PictureInPictureParams {
        val r = if (videoW > 0 && videoH > 0) {
            val ratio = videoW.toFloat() / videoH
            when { ratio > 2.39f -> Rational(239, 100); ratio < 1 / 2.39f -> Rational(100, 239); else -> Rational(videoW, videoH) }
        } else Rational(16, 9)
        return PictureInPictureParams.Builder().setAspectRatio(r).build()
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) return
        runCatching { enterPictureInPictureMode(pipParams()) }.onFailure { toast("Picture-in-picture is turned off for this app") }
    }

    // ---------- audio focus ----------
    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setOnAudioFocusChangeListener(focusListener).build().also { focusRequest = it }
            audio.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        else @Suppress("DEPRECATION") audio.abandonAudioFocus(focusListener)
    }

    private fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // =====================================================================
    // gestures
    // =====================================================================
    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop * 1.5f
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                showControls(controls.visibility != View.VISIBLE); return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val w = touch.width
                when {
                    e.x < w * 0.35f -> doubleTapSeek(-1)
                    e.x > w * 0.65f -> doubleTapSeek(1)
                    else -> { togglePlay(); flash(if (mp.isPlaying) R.drawable.ic_play_w else R.drawable.ic_pause_w, if (mp.isPlaying) "Play" else "Pause") }
                }
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                if (gesture != Gesture.NONE || scaling || !mp.isPlaying) return
                holdingFast = true; rateBeforeHold = mp.rate
                mp.rate = 2f
                osdIcon.setImageResource(R.drawable.ic_forward); osdText.text = "2x speed"; osdBar.visibility = View.GONE
                osd.visibility = View.VISIBLE; ui.removeCallbacks(hideOsd)
            }
        })
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { if (!prefs.gestureZoom) return false; scaling = true; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                setZoom(zoom * d.scaleFactor)
                osdIcon.setImageResource(R.drawable.ic_crop); osdText.text = "Zoom ${(zoom * 100).toInt()}%"
                osdBar.visibility = View.GONE; osd.visibility = View.VISIBLE
                ui.removeCallbacks(hideOsd); ui.postDelayed(hideOsd, 800)
                return true
            }
        })

        touch.setOnTouchListener { _, ev ->
            if (locked) {
                if (ev.actionMasked == MotionEvent.ACTION_UP) showUnlockButton()
                return@setOnTouchListener true
            }
            scale.onTouchEvent(ev)
            if (ev.pointerCount > 1 || scale.isInProgress) { gesture = Gesture.NONE; return@setOnTouchListener true }
            if (!scaling) detector.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    scaling = false
                    gesture = Gesture.NONE
                    downX = ev.x; downY = ev.y
                    startTime = maxOf(0L, mp.time)
                    startVolume = currentVolumeLevel()
                    startBrightness = currentBrightness()
                }
                MotionEvent.ACTION_MOVE -> if (!scaling && !holdingFast) {
                    val dx = ev.x - downX; val dy = ev.y - downY
                    val h = touch.height; val w = touch.width
                    if (gesture == Gesture.NONE) {
                        val edge = h * 0.06f
                        if (downY < edge || downY > h - edge) return@setOnTouchListener true // system gesture zones
                        if (abs(dx) > slop && abs(dx) > abs(dy) * 1.2f && prefs.gestureSeek && mp.isSeekable) gesture = Gesture.SEEK
                        else if (abs(dy) > slop && abs(dy) > abs(dx)) {
                            gesture = if (downX < w / 2f) { if (prefs.gestureBrightness) Gesture.BRIGHTNESS else Gesture.NONE }
                            else { if (prefs.gestureVolume) Gesture.VOLUME else Gesture.NONE }
                        }
                        if (gesture != Gesture.NONE) { downX = ev.x; downY = ev.y }
                    } else {
                        when (gesture) {
                            Gesture.SEEK -> gestureSeek(dx, w)
                            Gesture.VOLUME -> gestureVolume(-dy / (h * 0.6f))
                            Gesture.BRIGHTNESS -> gestureBrightness(-dy / (h * 0.6f))
                            Gesture.NONE -> {}
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (holdingFast) { holdingFast = false; mp.rate = rateBeforeHold; ui.postDelayed(hideOsd, 300) }
                    if (gesture == Gesture.SEEK) { mp.time = seekTarget; ui.postDelayed(hideOsd, 500); updateProgress() }
                    else if (gesture != Gesture.NONE) ui.postDelayed(hideOsd, 700)
                    gesture = Gesture.NONE
                    if (ev.actionMasked == MotionEvent.ACTION_UP && scaling) ui.postDelayed({ scaling = false }, 100)
                }
            }
            true
        }
    }

    private fun doubleTapSeek(dir: Int) {
        val now = System.currentTimeMillis()
        if (dir != lastTapSide || now - lastTapTime > 1200) tapAccum = 0
        lastTapSide = dir; lastTapTime = now
        val step = prefs.doubleTapSeek * 1000L
        tapAccum += prefs.doubleTapSeek
        seekBy(dir * step)
        val tv = if (dir < 0) tapLeft else tapRight
        (if (dir < 0) tapRight else tapLeft).visibility = View.GONE
        tv.text = "${if (dir < 0) "-" else "+"}$tapAccum s"
        tv.visibility = View.VISIBLE
        ui.removeCallbacks(hideTaps); ui.postDelayed(hideTaps, 900)
    }

    private fun gestureSeek(dx: Float, w: Int) {
        val len = if (length > 0) length else mp.length
        seekTarget = PlayerLogic.clampSeek(startTime + PlayerLogic.seekDelta(dx, w), len)
        val d = seekTarget - startTime
        osdIcon.setImageResource(if (d < 0) R.drawable.ic_rewind else R.drawable.ic_forward)
        osdText.text = "${Fmt.time(seekTarget)}\n[${Fmt.delta(d)}]"
        if (len > 0) { osdBar.visibility = View.VISIBLE; osdBar.progress = (seekTarget * 100 / len).toInt() } else osdBar.visibility = View.GONE
        osd.visibility = View.VISIBLE; ui.removeCallbacks(hideOsd)
        posText.text = Fmt.time(seekTarget); seek.progress = seekTarget.toInt()
        val now = System.currentTimeMillis()
        if (now - lastLiveSeek > 120) { lastLiveSeek = now; mp.time = seekTarget }
    }

    private fun maxVolume() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    /** 0..max = system volume, max..2*max = VLC boost (if enabled). */
    private fun currentVolumeLevel(): Float {
        val max = maxVolume()
        val sys = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val v = mp.volume
        return if (sys >= max && v > 100) max + (v - 100) / 100f * max else sys.toFloat()
    }

    private fun gestureVolume(frac: Float) {
        val max = maxVolume()
        val top = if (prefs.volumeBoost) max * 2 else max
        val level = (startVolume + frac * max).coerceIn(0f, top.toFloat())
        val (sys, vlc) = PlayerLogic.volume(level.toInt(), max)
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != sys) runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, sys, 0) }
        if (mp.volume != vlc) mp.volume = vlc
        val pct = PlayerLogic.volumePercent(level.toInt(), max)
        osdIcon.setImageResource(if (pct == 0) R.drawable.ic_volume_off else R.drawable.ic_volume)
        osdText.text = "Volume $pct%"
        osdBar.visibility = View.VISIBLE; osdBar.progress = if (prefs.volumeBoost) pct / 2 else pct
        osd.visibility = View.VISIBLE; ui.removeCallbacks(hideOsd)
    }

    private fun currentBrightness(): Float {
        val b = window.attributes.screenBrightness
        if (b >= 0) return b
        return runCatching { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
    }

    private fun setBrightness(v: Float) {
        window.attributes = window.attributes.apply { screenBrightness = v.coerceIn(0.01f, 1f) }
    }

    private fun gestureBrightness(frac: Float) {
        val b = (startBrightness + frac).coerceIn(0.01f, 1f)
        setBrightness(b)
        osdIcon.setImageResource(R.drawable.ic_brightness)
        osdText.text = "Brightness ${(b * 100).toInt()}%"
        osdBar.visibility = View.VISIBLE; osdBar.progress = (b * 100).toInt()
        osd.visibility = View.VISIBLE; ui.removeCallbacks(hideOsd)
    }

    private fun flash(icon: Int, text: String) {
        osdIcon.setImageResource(icon); osdText.text = text; osdBar.visibility = View.GONE
        osd.visibility = View.VISIBLE
        ui.removeCallbacks(hideOsd); ui.postDelayed(hideOsd, 1200)
    }

    // =====================================================================
    // dialogs: tracks, speed, decoder, delays, sleep, info
    // =====================================================================
    private fun trackInfo(type: Int): Map<Int, String> {
        val out = HashMap<Int, String>()
        val m = mp.media ?: return out
        runCatching {
            for (i in 0 until m.trackCount) {
                val t = m.getTrack(i) ?: continue
                if (t.type != type) continue
                val parts = mutableListOf(Fmt.codec(t.codec))
                if (t is IMedia.AudioTrack) Fmt.channels(t.channels).takeIf { it.isNotEmpty() }?.let { parts += it }
                if (t is IMedia.VideoTrack && t.width > 0) parts += "${t.width}×${t.height}"
                out[t.id] = parts.filter { it.isNotEmpty() }.joinToString(" ")
            }
        }
        m.release()
        return out
    }

    private fun audioDialog() {
        val tracks = mp.audioTracks
        if (tracks.isNullOrEmpty()) { toast("No audio tracks"); return }
        val info = trackInfo(IMedia.Track.Type.Audio)
        val labels = tracks.map { t -> if (t.id < 0) "Disable audio" else listOfNotNull(t.name, info[t.id]?.takeIf { it.isNotEmpty() }).joinToString("  •  ") }
        val cur = tracks.indexOfFirst { it.id == mp.audioTrack }
        MaterialAlertDialogBuilder(this).setTitle("Audio track")
            .setSingleChoiceItems(labels.toTypedArray(), cur) { d, w -> mp.setAudioTrack(tracks[w].id); d.dismiss(); flash(R.drawable.ic_audiotrack, labels[w]) }
            .setNeutralButton("Audio delay") { _, _ -> delayDialog(true) }
            .setNegativeButton("Close", null).show()
    }

    private fun subtitleDialog() {
        val tracks = mp.spuTracks ?: emptyArray()
        val info = trackInfo(IMedia.Track.Type.Text)
        val labels = tracks.map { t -> if (t.id < 0) "Disable subtitles" else listOfNotNull(t.name, info[t.id]?.takeIf { it.isNotEmpty() }).joinToString("  •  ") }.toMutableList()
        val cur = tracks.indexOfFirst { it.id == mp.spuTrack }
        if (tracks.isEmpty()) labels += "No subtitles in this video"
        MaterialAlertDialogBuilder(this).setTitle("Subtitles")
            .setSingleChoiceItems(labels.toTypedArray(), if (tracks.isEmpty()) -1 else cur) { d, w ->
                if (w < tracks.size) { mp.setSpuTrack(tracks[w].id); flash(R.drawable.ic_subtitles, labels[w]) }
                d.dismiss()
            }
            .setPositiveButton("Open file…") { _, _ -> pickSubtitle.launch(arrayOf("*/*")) }
            .setNeutralButton("Sync / delay") { _, _ -> delayDialog(false) }
            .setNegativeButton("Close", null).show()
    }

    private fun loadSubtitle(uri: Uri) {
        try {
            var name = "subtitle.srt"
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name }
            val dir = File(cacheDir, "subs").apply { mkdirs() }
            val f = File(dir, name.replace(Regex("[^\\w.\\- ]"), "_"))
            contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            if (mp.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(f), true)) flash(R.drawable.ic_subtitles, "Subtitle loaded")
            else toast("Could not load subtitle")
        } catch (e: Exception) { toast("Could not load subtitle: ${e.message}") }
    }

    private fun speedDialog() {
        val speeds = PlayerLogic.SPEEDS
        val labels = speeds.map { Fmt.speed(it) + if (it == 1f) "  (Normal)" else "" }.toTypedArray()
        val cur = speeds.indexOfFirst { abs(it - mp.rate) < 0.01f }
        MaterialAlertDialogBuilder(this).setTitle("Playback speed")
            .setSingleChoiceItems(labels, cur) { d, w -> mp.rate = speeds[w]; d.dismiss(); flash(R.drawable.ic_speed, "Speed ${Fmt.speed(speeds[w])}") }
            .setNegativeButton("Close", null).show()
    }

    private fun decoderDialog() {
        val labels = arrayOf(
            "HW decoder – hardware, fastest",
            "HW+ decoder – force hardware",
            "SW decoder – software, plays everything",
        )
        MaterialAlertDialogBuilder(this).setTitle("Decoder")
            .setSingleChoiceItems(labels, decoder.ordinal) { d, w ->
                d.dismiss()
                val nd = Decoder.entries[w]
                if (nd != decoder) {
                    decoder = nd
                    val t = maxOf(0L, mp.time)
                    val keepRate = mp.rate
                    startPlayback(t, false)
                    mp.rate = keepRate
                    flash(R.drawable.ic_decoder, nd.label)
                }
            }
            .setNegativeButton("Close", null).show()
    }

    private fun delayDialog(audioDelay: Boolean) {
        val cur = ((if (audioDelay) mp.audioDelay else mp.spuDelay) / 1000L).toInt().coerceIn(-10_000, 10_000)
        val label = TextView(this).apply { textSize = 16f; setPadding(dp(24), dp(12), dp(24), 0) }
        fun fmt(ms: Int) = String.format(Locale.US, "%+.2f s", ms / 1000f)
        label.text = fmt(cur)
        val slider = Slider(this).apply {
            valueFrom = -10_000f; valueTo = 10_000f; stepSize = 50f; value = (cur / 50 * 50).toFloat()
            setLabelFormatter { fmt(it.toInt()) }
            addOnChangeListener { _, v, _ -> label.text = fmt(v.toInt()) }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), 0)
            addView(label); addView(slider)
        }
        MaterialAlertDialogBuilder(this).setTitle(if (audioDelay) "Audio delay" else "Subtitle delay")
            .setView(box)
            .setPositiveButton("Apply") { _, _ ->
                val us = slider.value.toLong() * 1000
                if (audioDelay) mp.setAudioDelay(us) else mp.setSpuDelay(us)
            }
            .setNeutralButton("Reset") { _, _ -> if (audioDelay) mp.setAudioDelay(0) else mp.setSpuDelay(0) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun sleepDialog() {
        val mins = intArrayOf(0, 15, 30, 45, 60, 90, 120)
        val labels = mins.map { if (it == 0) "Off" else "$it minutes" }.toMutableList().apply { add("End of this video") }
        MaterialAlertDialogBuilder(this).setTitle("Sleep timer")
            .setItems(labels.toTypedArray()) { _, w ->
                sleepEndOfVideo = false; sleepAt = 0
                when {
                    w == mins.size -> { sleepEndOfVideo = true; toast("Will stop at the end of this video") }
                    mins[w] == 0 -> toast("Sleep timer off")
                    else -> { sleepAt = System.currentTimeMillis() + mins[w] * 60_000L; toast("Will pause in ${mins[w]} minutes") }
                }
            }.show()
    }

    private fun infoDialog() {
        val item = items[index]
        val rows = linkedMapOf<String, String>()
        rows["File"] = item.title
        rows["Location"] = item.path.ifEmpty { item.uri }
        val len = if (length > 0) length else mp.length
        if (len > 0) rows["Length"] = Fmt.time(len)
        mp.currentVideoTrack?.let { t ->
            rows["Video"] = listOf(
                Fmt.codec(t.codec), "${t.width}×${t.height}",
                if (t.frameRateDen > 0) String.format(Locale.US, "%.2f fps", t.frameRateNum.toFloat() / t.frameRateDen) else "",
            ).filter { it.isNotEmpty() }.joinToString(" • ")
        }
        val audioTracks = trackInfo(IMedia.Track.Type.Audio).values.filter { it.isNotEmpty() }
        if (audioTracks.isNotEmpty()) rows["Audio"] = audioTracks.joinToString("\n")
        val subs = (mp.spuTracks?.count { it.id >= 0 } ?: 0)
        rows["Subtitles"] = if (subs == 0) "None" else "$subs track(s)"
        rows["Decoder"] = decoder.label
        rows["Speed"] = Fmt.speed(mp.rate)
        MaterialAlertDialogBuilder(this).setTitle("Media info")
            .setMessage(rows.entries.joinToString("\n\n") { "${it.key}\n${it.value}" })
            .setPositiveButton("OK", null).show()
    }

    private fun moreMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val m = pm.menu
        m.add(0, 1, 0, "Playback speed")
        m.add(0, 2, 1, "Audio track")
        m.add(0, 3, 2, "Audio delay")
        m.add(0, 4, 3, "Subtitles")
        m.add(0, 5, 4, "Subtitle delay")
        m.add(0, 6, 5, "Load subtitle file…")
        m.add(0, 7, 6, "Decoder (HW / HW+ / SW)")
        m.add(0, 8, 7, "Sleep timer")
        m.add(0, 9, 8, if (mp.volume == 0) "Unmute" else "Mute")
        m.add(0, 10, 9, "Media info")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> speedDialog()
                2 -> audioDialog()
                3 -> delayDialog(true)
                4 -> subtitleDialog()
                5 -> delayDialog(false)
                6 -> pickSubtitle.launch(arrayOf("*/*"))
                7 -> decoderDialog()
                8 -> sleepDialog()
                9 -> { mp.volume = if (mp.volume == 0) 100 else 0; flash(if (mp.volume == 0) R.drawable.ic_volume_off else R.drawable.ic_volume, if (mp.volume == 0) "Muted" else "Sound on") }
                10 -> infoDialog()
            }
            true
        }
        ui.removeCallbacks(hideControls)
        pm.setOnDismissListener { scheduleHide() }
        pm.show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
