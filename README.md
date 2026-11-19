# MRM Zone Player

An HD video player for Android that plays every common video format. Its look matches the **MRM Zone IDM** app: the same dark top bar, orange highlights, command bar, cards and status bar.

## Formats

| | |
|---|---|
| Video containers | MKV, MP4, AVI, MOV, WMV, FLV, WEBM, TS/M2TS/MTS, MPG/VOB, 3GP, OGV, RM/RMVB, DIVX, M4V |
| Video codecs | H.264, H.265/HEVC, AV1, VP8/VP9, MPEG-2, MPEG-4/Xvid/DivX, WMV, FLV, H.263, RealVideo |
| Audio codecs | **AC3, E-AC3, DTS, TrueHD**, AAC, MP3, MP2, FLAC, Opus, Vorbis, WMA, PCM, AMR |
| Subtitles | SRT, ASS/SSA, VTT, SUB/IDX, PGS, embedded tracks. A subtitle file with the same name as the video loads automatically |
| Streams | http(s), HLS (m3u8), RTSP, RTMP, MMS, UDP |

The engine is the open-source **VLC (libVLC 3.6)**. AC3/DTS/TrueHD audio is always decoded in software, so it plays on every phone, even phones whose own player says "audio format not supported".

## Features

**Library**
- Three tabs: FOLDERS / VIDEOS / RECENT, plus an IDM-style command bar and a status bar
- Thumbnails, length, quality (720p/1080p/4K), size, a "NEW" badge and a resume progress bar
- Length and resolution are filled in by VLC for AVI/WMV/FLV/MPG files that Android can't read itself
- Grid and list views, sort by name (natural: Ep 2 comes before Ep 10), date, size or length, and search
- Properties, Share, Delete (with Android's own confirmation)

**Player**
- Gestures: swipe the right side for volume (boost up to 200%), the left side for brightness, left/right to seek, double-tap for ±10 s (adds up with repeated taps), pinch to zoom, long-press for 2× speed
- HW / HW+ / SW decoder switch while a video plays. If HW fails, it falls back to SW automatically
- Audio track selection (shows codec and channels, e.g. "AC3 5.1"), audio delay
- Subtitle track selection, loading a subtitle file, subtitle delay, text size
- Screen lock, rotation (follows the video / landscape / portrait / sensor), aspect ratio (Fit / Stretch / Crop / 16:9 / 4:3 / 100%)
- Speed from 0.25× to 4×, repeat (one / all), automatic next video, sleep timer
- Picture-in-picture, resume ("Resumed from 12:34 · START OVER"), pause when headphones are unplugged, audio focus
- Controls stay clear of the notch and gesture bar, and the screen stays on while playing
- Opens videos from other apps (file managers, WhatsApp, Telegram, browsers)

## Build

```bash
./gradlew testDebugUnitTest assembleDebug
```

The APKs land in `app/build/outputs/apk/debug/`:
- `app-universal-debug.apk`: runs on every phone (recommended)
- `app-arm64-v8a-debug.apk`: smaller, for modern phones

GitHub Actions builds the APKs on every push (Actions → Build APK → Artifacts).

## License note
libVLC is LGPL-2.1. The app links to it dynamically, as an unmodified `.so` from Maven Central.
