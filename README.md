# MEMC Player

A video player for Android with real-time 60 fps frame interpolation (MEMC). Infuse-style
library, Nova-style features. Developed on a Galaxy S24 Ultra (Snapdragon 8 Gen 3); runs on any
Android 10+ phone, with the AI mode on Snapdragon phones.

## Features

- **60 fps playback (MEMC)** – in-between frames are computed on the GPU while the video
  plays, turning 24/25/30 fps video into 60 fps. Four quality levels, including an AI mode that
  runs the RIFE motion model on the Snapdragon NPU. The engine renders a few frames ahead and
  eases its workload by itself if the phone can't keep up.
- **60 fps export** – renders a new 60 fps copy of a video in the background, so it can be
  watched later without any interpolation work.
- **Picture** – HDR10 / HDR10+ / HLG passed straight to the display, HDR boost for brighter
  highlights, crop / fit / stretch and aspect-ratio options, optional SDR → HDR conversion
  (experimental).
- **Library** – scans videos on the phone and on SMB network shares, recognises movies and TV
  episodes from file and folder names, and downloads posters, backdrops, plots, cast and
  episode stills from TMDB. Shows codec details (4K, HDR10+, Dolby Vision, Atmos, DTS…).
- **Network shares** – SMB2/SMB3 with guest or username/password; servers on the Wi-Fi are
  found automatically. Play straight from the share or download to the phone. Passwords are
  encrypted with the phone's hardware keystore.
- **Player** – hardware decoding of HEVC, AV1, VP9 and H.264 (8/10-bit), DTS / DTS-HD / TrueHD /
  AC-3 audio via FFmpeg, MKV/MP4/TS/M2TS/AVI, embedded and side-by-side subtitles (SRT, ASS,
  VTT, PGS), resume, next episode, picture-in-picture.
- **Gestures** – swipe left/right to seek, left edge up/down for brightness, right edge for
  volume, double-tap sides to skip, double-tap centre to pause, pinch to crop/fit, lock.
- **Stats overlay** – optional live CPU, GPU and NPU load, temperature and frame rate while
  playing.
- **Explore** – trending movies and shows from TMDB, with a search across torrent indexes
  that hands magnet links to a torrent app of your choice. Only download what you have the
  right to.

## TV version

Each release also has a `MEMC-Player-TV` APK for Android TV, Google TV and Fire TV boxes running
Android 7 or newer (Google TV Streamer, Chromecast with Google TV, Nvidia Shield, Mi Box, Fire TV
Stick, ARM and x86 boxes). It is the library and player only (no 60 fps engine, export or Explore) and is
driven with the remote: OK pauses and shows the controls, left/right seek, up/down show the
controls, Back hides them.

## Requirements

- **Phones:** Android 10 or newer, 64-bit ARM. The 60 fps engine runs on any phone's GPU.
  The AI (NPU) mode needs a Snapdragon chip (the app carries Qualcomm's NPU runtime for every
  NPU generation it supports, Snapdragon 888 onwards); it is tuned on the 8 Gen 3, and on slower NPUs
  it falls back to the GPU engine by itself. Exynos, Tensor and MediaTek phones use the GPU engine.
- **TV boxes:** Android 7 or newer, ARM or x86.

## Getting the APK

Every push to `main` is built by GitHub Actions. Open the repository's **Releases** page on the
phone, download the newest `MEMC-Player-*.apk` and open it.

## Posters

Get a free API key at themoviedb.org → Settings → API and paste it into the app's Settings.

This product uses the TMDB API but is not endorsed or certified by TMDB.
