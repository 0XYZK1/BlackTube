<div align="center">

<img src="assets/icon-192.png" alt="BlackTube" width="140">

# BLACKTUBE

*A T A O S Production.*
*The Art of Software.*

[![Latest Release](https://img.shields.io/github/v/release/0XYZK1/BlackTube?style=for-the-badge&labelColor=black&color=white&label=LATEST%20RELEASE)](https://github.com/0XYZK1/BlackTube/releases/latest)
[![Android](https://img.shields.io/badge/ANDROID-8.0%2B-white?style=for-the-badge&labelColor=black&logo=android&logoColor=white)](https://github.com/0XYZK1/BlackTube/releases/latest)
[![License](https://img.shields.io/badge/LICENSE-CUSTOM-white?style=for-the-badge&labelColor=black)](LICENSE)

**Your YouTube. Pure Black.**

A native Android YouTube client with no ads, no Shorts and no clutter.
Built in Kotlin, played through Media3/ExoPlayer, finished in black and white.

</div>

---

## WHY BLACKTUBE

YouTube is a good library wrapped in noise: pre-rolls, mid-rolls, banners,
recommendation bait, and an endless wall of vertical Shorts designed to keep
you scrolling instead of watching.

BlackTube removes the noise at the source.

- **No ads.** Not blocked with CSS or skip-buttons — the ad stream is never
  requested. There is no player that could show one.
- **No Shorts.** Shorts are filtered out of feeds and refused when opened.
  No vertical brainrot, no doom-scroll.
- **No clutter.** Black surfaces, white type, one clean line. The interface
  stays out of the way of the video.
- **Your account.** Sign in once and get your real subscriptions, playlists,
  likes, Watch Later and history.
- **Your files.** Save video up to 1080p as MP4, or extract the audio as a
  real MP3 — encoded locally on the phone.

BlackTube is not a YouTube downloader with a browser glued on top. It talks to
the same endpoints the web client uses, resolves the original streams, and
plays them natively.

---

## WHAT IT DOES

### Playback
- Native playback via **Media3 / ExoPlayer** — no WebView, no embedded player
- Up to **1080p**, with quality selection and an **audio-only** mode
- **Background audio** through a foreground media service
- **Picture-in-Picture** (pop-up player), including auto-PiP when leaving the app
- **Mini-player** that keeps playing while you browse
- **Google Cast** to nearby TVs and speakers
- HLS support for **live streams**
- Fullscreen with **swipe gestures**: brightness on the left, volume on the
  right, double-tap to seek ±10 s
- Custom seek bar, skip buttons, resume-after-interruption

### Account
- Google sign-in through a WebView used **only** to obtain cookies — your
  password never reaches the app
- Home feed, subscriptions, playlists, liked videos, Watch Later, history
- Channel pages and search with paging
- Comments: read and post
- Likes and dislikes (dislikes from Return YouTube Dislike)
- Bulk unsubscribe and bulk unlike
- Notifications sheet
- Guest mode: search and playback work without an account

### Downloads
- **MP4 video** up to 1080p. Separate video and audio tracks are downloaded
  and muxed locally with `MediaMuxer`.
- **MP3 audio** via a native LAME encoder — a real `.mp3`, not a renamed
  container.
- Downloads run with a progress notification and land in your Downloads folder.
- No server, no third-party converter, no upload. Everything happens on device.

### Stats
A quiet dashboard for what you actually watched: total watch time, videos
watched, and likes given. Local stats are merged with your account history when
you sign in.

### Interface
- Pure black theme, monochrome icons, no colour accents
- Home / Subscriptions / Library tabs
- Search overlay with saved search history
- Edge-to-edge layout, portrait feed, landscape player
- Opens `youtube.com` and `youtu.be` links from other apps
- Accepts shared links through the Android share sheet

---

## INSTALL

1. Download the latest **`BlackTube-2.10.0.apk`** from
   [Releases](https://github.com/0XYZK1/BlackTube/releases/latest).
2. Open the file on your phone.
3. Allow installation from unknown sources for your file manager when Android
   asks.
4. Install and open BlackTube.

**Requirements:** Android 8.0 (API 26) or newer, arm64 device recommended.
The APK is signed for direct sideloading and is not distributed through Google
Play.

> BlackTube is a third-party client. It is not affiliated with, endorsed by or
> connected to YouTube or Google. Use it with your own account and at your own
> risk.

---

## BUILD FROM SOURCE

Requirements: **JDK 17+**, **Android SDK Platform 34**, Android Studio or the
Gradle CLI.

```powershell
git clone https://github.com/0XYZK1/BlackTube.git
cd BlackTube
.\gradlew.bat assembleDebug
# APK: app\build\outputs\apk\debug\app-debug.apk
```

For a release build, create a `keystore.properties` next to `settings.gradle`:

```properties
storeFile=your-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Then:

```powershell
.\gradlew.bat assembleRelease
# APK: app\build\outputs\apk\release\app-release.apk
```

Without `keystore.properties` the release build falls back to the debug key so
the project still builds out of the box. Credentials and keystores are ignored
by Git.

The launcher icon is generated from code:

```powershell
python tools\make_icon.py
```

---

## HOW IT WORKS

```
Google sign-in (WebView, cookies only)
        │  SID / SAPISID / __Secure-3PAPISID
        ▼
SAPISIDHASH auth  ──────────────►  youtubei/v1/browse | search | next
        │                          (feeds, subscriptions, playlists,
        │                           likes, history, comments)
        ▼
NewPipeExtractor v0.27.1  ──────►  original video and audio streams
        │                          (n-sig, PO token, all clients)
        ▼
Media3 / ExoPlayer  ────────────►  playback, quality, audio-only,
                                   PiP, Cast, background audio
```

Ads are not filtered out of a page — they are never part of the data the app
asks for. The player receives a video stream and an audio stream, nothing else.

### Project structure

```
app/src/main/java/com/nexus/videoclient/
  NexusApp.kt                 App initialization
  data/Session.kt             Cookie session, SAPISIDHASH, account info
  data/InnerTube.kt           InnerTube client and parsers
  data/YtExtractor.kt         NewPipeExtractor wrapper and stream selection
  data/MediaDownloader.kt     MP4 muxing, audio download, MP3 export
  data/Mp3Transcoder.kt       Native LAME wrapper
  data/LocalStore.kt          Local watch stats, history, preferences
  data/Dislikes.kt            Return YouTube Dislike client
  playback/PlaybackSession.kt Shared player for mini-player and background
  playback/PlaybackService.kt Foreground audio service
  ui/MainActivity.kt          Home, subscriptions, library, stats
  ui/ListActivity.kt          Channels, playlists, search, history
  ui/PlayerActivity.kt        Player, gestures, comments, downloads, Cast
  ui/LoginActivity.kt         Google sign-in (cookies only)
```

---

## HONEST LIMITS

- **Google may block WebView sign-in** ("This browser or app may not be
  secure"). This affects every third-party wrapper. A current Android System
  WebView improves the odds; guest mode always works.
- **Downloads run in the foreground.** If Android kills the app mid-download,
  that download is gone. There is no resume queue.
- **Live chat is not implemented.** Live streams play; chat does not.
- **The UI is English.** Code and comments are mixed English/German.
- **No Play Store build.** The APK is signed for sideloading.

---

## LICENSE

Custom license — free for personal, non-commercial use. See [LICENSE](LICENSE).

---

<div align="center">

**BLACKTUBE**
*No ads. No Shorts. No noise.*

**T A O S** · *Design. Code. Silence.*
© 2026 XYZK

</div>
