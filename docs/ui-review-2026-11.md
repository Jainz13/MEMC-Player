# UI review, November 2026

Source: Gemini (Google AI Studio) built a clickable phone + Google TV prototype of MEMC Player,
then read this repository's Compose code and compared the two. Its findings were checked
against the code before acting on them.

Prototype: AI Studio project "MEMC Player" (owner's account).

## Done in 0.1.34

- Full-row toggles in Settings (the label was not tappable), min height 52 dp; quality chips wrap.
- Home hero 520 -> 400 dp (TV 330 dp); movie header 440 -> 340 dp; show header 420 -> 330 dp,
  so the Play button and first shelf show without scrolling.
- Buttons over artwork: 48 dp touch target, dark fill and hairline border (Home, detail pages).
- TV focus: amber ring plus a slight lift, now also on landscape cards, episode rows, library rows,
  share cards, settings toggles, filter chips, player buttons and "see all" headers.
- Movies / TV Shows: 4K, HDR, Dolby Vision filters next to Unwatched; sort shown as a labelled
  chip; "No matching ..." state with Reset filters (was a blank grid).
- Tech badges wrap instead of running off the screen; filled style, amber only for HDR/DV.
- Movie detail: Mark watched / Download / Export 60 fps / Delete as equal tiles.
- Network shares as cards with Connected / Unreachable status and an inline Retry.
- Player: labelled size pill (Fit / Crop / Original), amber play/pause, HDR / Dolby Vision badge
  moved to the top right for 3.5 s, side panel about 40 % of the width (320-420 dp).
- TV: navigation rail on the left with Settings at the bottom, instead of the bottom bar.
- "TV Shows" tab label shortened to "Shows" so six tabs fit.

## Not done yet

- Episode options as a bottom sheet on phone / dialog on TV (still a dropdown menu).
- Versions picker above the overview, driving Play and Media info.
- Media info and download / export rows as cards; cancel buttons beside the title.
- Shimmer loading skeletons; Search screen content before typing.
- TV: two-pane Settings, larger type scale, D-pad steps for sliders, focus restore on the rail.
- 90 fps target (the prototype assumed it; the app offers 60 and 120).
