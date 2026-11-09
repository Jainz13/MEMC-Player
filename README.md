# MEMC Player

A video player for Android, tuned for the Galaxy S24 Ultra. Infuse-style library on top of
Nova-style features, with real-time 60 fps frame interpolation (MEMC) coming next.

## What works now (stage 1)

- **Library** – scans videos on the phone and on SMB network shares, recognises movies and
  TV episodes from file and folder names, and downloads posters, backdrops, plots, cast and
  episode stills from TMDB.
- **Network shares** – SMB2/SMB3 with guest or username/password; servers that announce
  themselves on Wi-Fi are listed automatically. Browse any folder live, or add a share to the
  library. Passwords are encrypted with the phone's hardware keystore.
- **Player** – hardware decoding of HEVC, AV1, VP9 and H.264 (8/10-bit), HDR10 / HDR10+ / HLG
  passed straight to the display, DTS / DTS-HD / TrueHD / AC-3 audio via FFmpeg, MKV/MP4/TS/
  M2TS/AVI, embedded and side-by-side subtitles (SRT, ASS, VTT, PGS), resume, next episode,
  picture-in-picture.
- **Gestures** – swipe left/right to seek, left edge up/down for brightness, right edge for
  volume, double-tap sides to skip, double-tap centre to pause, pinch to crop/fit, lock.

## Roadmap

1. ~~Library, SMB, scraping, player~~
2. MEMC engine: optical flow on the Snapdragon NPU (Qualcomm QNN via LiteRT), frame warping on
   the GPU, 24/30 → 60 fps in real time.
3. 60 fps export: render a video to a new 60 fps file overnight while charging.

## Getting the APK

Every push to `main` is built by GitHub Actions. Open the repository's **Releases** page on the
phone, download the newest `MEMC-Player-*.apk` and open it.

## Posters

Get a free API key at themoviedb.org → Settings → API and paste it into the app's Settings.

This product uses the TMDB API but is not endorsed or certified by TMDB.
