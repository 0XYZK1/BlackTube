# Changelog

## 2.11.1 - 2026-11-22

- Fixed audio focus: starting a video now pauses Spotify and other music apps.
- Added a real media session + media notification, so the lock screen / notification shade shows the running BlackTube video and play/pause controls.
- Restored Picture-in-Picture when leaving the app (manual PiP + auto-enter fallback).
- Smoother swipe-to-mini-player: the full-screen surface is released before the mini player attaches.
- Mini player is no longer hidden by lifecycle ordering.

## 2.11.0 - 2026-11-22

- Futuristic black/white redesign with sharp cards, pills, new comment sheet and profile dashboard.
- Comment bottom sheet with real likes/dislikes, composer and comment preview directly under the player.
- Audio track switcher (original / auto-dub) with all tracks kept available.
- SponsorBlock auto-skip and sleep timer.
- Real video likes/dislikes through InnerTube, direct playlist add and playlist creation.
- Profile shows recently watched videos with resume progress; bulk tools moved into one Tools & Preferences sheet.
- Fixed stream 403s via per-client stream headers and refreshed visitor data / SAPISID hashes.
- Fixed PiP freezes and duplicate player surfaces (singleTop + surface rebinding).
- Resume playback position, watch progress bars and stats sync throttle.
- New history chip layout and refreshed player/profile layouts.

## 2.10.1 — 2026-11-19

- New icon: the eye mark with a play button as the iris.
- Cleaned up comments and build notes across the source.

## 2.10.0 — 2026-11-19

- New monochrome launcher icon: white ring, white play mark, black background.
- Native LAME MP3 export for audio downloads.
- Google Cast support (nearby TVs and speakers).
- Watch statistics: watch time, videos watched, likes given.
- MP4 video downloads up to 1080p with local muxing.
- Picture-in-Picture, mini-player and background audio.
- Bulk unsubscribe and bulk unlike.
- Notifications sheet, saved search history.
