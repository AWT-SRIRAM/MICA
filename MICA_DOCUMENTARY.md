# MICA — Architectural Documentary & System Specification
**Product Name**: MICA  
**Tagline**: *One app. Every experience. — The Universe of Entertainment*  
**Application ID**: `app.mica`  
**Internal Namespace**: `app.mica`  
**Repository**: [https://github.com/AWT-SRIRAM/MICA](https://github.com/AWT-SRIRAM/MICA)  
**Authors**: **SRIRAM S & ANTIGRAVITY**  
**Target Architecture**: Android (ARM64-v8a / Android 8.0+ API 26–36)  
**Current Version**: `0.8.6` (VersionCode `34`)  

---

## 1. Executive Summary & Brand Genesis

### 1.1 The Genesis of MICA
MICA was born from the ambitious evolution of an offline-first video player into a unified, privacy-first multimedia universe. Traditional mobile media experiences are fragmented across standalone music streaming clients, ad-heavy web browsers, clunky media players, and isolated home widgets.

MICA bridges these domains into a single high-performance engine:
1. **Ultra-Low Latency Decoding**: LibVLC 3.7.7 C/C++ JNI hardware acceleration for 4K/8K HDR video playback with unified single-engine subtitle rendering.
2. **Dual-Lossless Audio Pipeline**: JioSaavn 320kbps Lossless primary engine coupled with an intelligent YouTube Music iOS/VR client stream resolver.
3. **MICA Shield Browser**: Brave-grade ad-blocking with active video stream sniffing for anime and cartoons.
4. **Interactive Home Widget**: Native 4x2 Android widget mirroring Spotify with live player controls, 950ms timeline synchronization, and interactive quick-picks.
5. **MICA JAM Worldwide**: Global low-latency social listening synchronized over HTTPS Server-Sent Events (SSE), local Wi-Fi, and mobile hotspots with sub-40ms dynamic micro-tempo steering.
6. **Optical Liquid Glass UI**: Apple iOS-inspired dynamic glass refraction, frosted dialogs, and tactile micro-interactions.

---

## 2. High-Level System Architecture

```mermaid
flowchart TD
    subgraph UI_Layer["Liquid Glass Presentation Layer (Jetpack Compose)"]
        A1["SearchHubScreen: 2x3 Media Discovery Dock"]
        A2["OnlineMusicScreen: Lossless Carousels & Liquid Glass Dice"]
        A3["MusicPlayer: Fullscreen Player & Symmetrical Controls"]
        A4["VideoPlayer: 4K LibVLC Cinema Surface & StrokeView CC"]
        A5["MicaShieldBrowser: Brave Ad-Shield & Stream Sniffer"]
        A6["MicaMusicWidgetProvider: 4x2 Home Launcher Widget"]
    end

    subgraph Service_Layer["Core Media Service & IPC (Android Service)"]
        B1["PlaybackService: Foreground Media3 Service"]
        B2["MediaSessionCompat: System Lockscreen & Notification"]
        B3["JamSyncController: MQTT & LAN Social Listening"]
        B4["FeedCacheManager: 0ms Cold-Start Cache & Title Normalizer"]
        B5["AudioDownloadManager: Offline Storage & Metadata Tagger"]
    end

    subgraph Native_Engines["Native Execution & Decoding Core"]
        C1["LibVLC 3.7.7 JNI: Hardware Video/Audio Decoder"]
        C2["10-Band Biquad Graphic Equalizer & Spatializer"]
        C3["StrokeView: Multi-pass Subtitle Outline Renderer"]
        C4["StreamResolver: YouTube, Spotify & Anime Sniffer Engine"]
    end

    subgraph Storage_Layer["Persistence & Caching"]
        D1["Room Database: PlayerDatabase.db"]
        D2["Feed Cache: feed_top_hits.json / feed_playlists.json"]
        D3["MediaStore Provider: Local Audio & Video Files"]
    end

    UI_Layer --> Service_Layer
    Service_Layer --> Native_Engines
    Service_Layer --> Storage_Layer
```

---

## 3. Subsystem Specifications

### 3.1 Lossless Music Hub & Smart Deduplication
- **Dual-Engine Stream Resolution**:
  - `JioSaavnClient`: Queries high-bitrate 320 kbps AAC/MP4 streams with complete cover art and lyrics.
  - `YtMusicClient`: Emulates iOS and Oculus Quest (ANDROID_VR) player clients to resolve direct audio endpoints without throttling.
- **Smart Title Normalization**:
  To resolve duplicate tracks in feeds and searches, `normalizeTrackTitle` purges extraneous metadata:
  $$\text{Title}_{\text{clean}} = \text{RegexReplace}(\text{Title}, \text{"(From ...)"} \mid \text{"- Best of ..."}, "") \rightarrow \text{AlphanumericLowerCase}$$
  Tracks matching normalized signatures are deduplicated before rendering in Quick Picks or queues.

### 3.2 Spotify-Style Home Screen Widget
- **Architecture**: Implemented via `MicaMusicWidgetProvider` (`AppWidgetProvider`) and `RemoteViews`.
- **Layout (`widget_mica_music.xml`)**:
  - **Top Row**: 52dp rounded album artwork, track title, artist, Previous, Play/Pause circular container (`40dp` white circle with dark icon), Next.
  - **Bottom Row**: 4 interactive Quick-Pick cards with real thumbnails and miniature green play badges.
- **IPC Protocol**:
  Tapping a quick pick dispatches a broadcast with action `ACTION_WIDGET_PLAY_PICK` containing `EXTRA_PICK_INDEX`. `PlaybackService` intercepts this and immediately begins playback without requiring the full app UI to open.

### 3.3 Cinema Video Engine & Continuous Subtitle Scaling
- **LibVLC 3.7.7**: Bypasses Android framework limitations to play 10-bit HEVC, AV1, and complex subtitle formats (ASS/SSA, PGS) directly via JNI surface buffers.
- **Single-Tap CC Vertical Drag Scaling**:
  Subtitles remain active by default. Tapping the CC button toggles a smooth vertical drag gesture allowing users to continuously scale subtitles from $50\%$ to $300\%$ with real-time HUD feedback. Subtitle scaling automatically persists across video files, streaming sessions, and full-screen toggles without resetting.
- **Unified Single-Engine Rendering**:
  Eliminates dual-outline visual artifacts and ensures sharp, legible rendering across both local storage files and network streams.

### 3.4 MICA Shield Browser & Anime Stream Sniffing
- **Brave-Style Ad-Blocker**: Intercepts requests in `shouldInterceptRequest` against `MicaAdShield` host filters, neutralizing advertising networks before network socket establishment.
- **Stream Sniffing Pipeline**:
  Evaluates JavaScript DOM video elements and network requests across anime portals (`myanimes.in`, `tamilcartoonsrare.in`):
  $$\text{SnifferPattern} = \text{.m3u8} \mid \text{.mp4} \mid \text{videoplayback} \mid \text{/stream/} \mid \text{master.m3u8}$$
  When a candidate stream is captured, an animated floating pill **"▶ Play in MICA Video Player"** appears, enabling 1-tap handoff to LibVLC. Direct video streaming domains (`googlevideo.com`) are automatically excluded from false interception with authentic client user-agent forwarding.

### 3.5 MICA JAM Worldwide Social Listening
- **Global HTTPS SSE Relay (Port 443)**: Facilitates worldwide peer synchronization via standard HTTPS Server-Sent Events (`ntfy.sh`). Operates unimpeded across carrier CGNAT, 4G/5G mobile data, mobile hotspots, and firewalled Wi-Fi without raw TCP port 1883 dependencies.
- **Sub-40ms Precision Sync Engine**:
  - **Dynamic Micro-Tempo Steering ($\pm 2\%$ to $\pm 8\%$)**: Dynamically speeds up (`1.02f`..`1.08f`) or slows down (`0.92f`..`0.98f`) playback to catch up with or wait for the host smoothly without audible audio pitch shift or rebuffering stutter.
  - **800ms Low-Latency Stream Caching**: Low-overhead network caching for Jam rooms, slashing startup buffering delay by over 5 seconds.
  - **Direct Start-Time Hand-Off**: Bypasses 0.0s stream loading by instructing LibVLC to start directly at the host's current millisecond position.
- **Permanent `JAM - ` Prefix Badge**: Join interface features a permanent purple monospace prefix badge, accepting clean 4-digit room numbers (e.g. `3213`, `8957`) with automatic sanitization.
- **Supervisor-Backed Session Persistence**: Jam connections are governed by a dedicated `SupervisorJob` lifecycle, surviving app switching, navigation, and background lockscreen playback.

---

## 4. Verification, Security & Compliance

| Domain | Specification | Verification Result |
| :--- | :--- | :--- |
| **Package ID** | `app.mica` | Compliant with Android multi-segment naming |
| **App Launcher Label** | `Mica` | Verified strictly without "Player" |
| **In-App Branding** | `MICA` (FULL CAPS) | Verified across UI, Settings, and Notifications |
| **APK Signatures** | v1 (JAR), v2 (APK Signature Scheme), v3 (Android 9+) | Verified via `apksigner verify --verbose` |
| **Minimum SDK** | Android 8.0 (API 26) | Verified across all target APIs up to API 36 |
| **Permissions** | Storage, Foreground Service, Internet, WakeLock | Minimal footprint with zero tracking SDKs |

---

## 5. Provenance & Attribution
- **Application Source**: Copyright © 2026 SRIRAM S & ANTIGRAVITY. Licensed under MIT.
- **LibVLC Android**: Licensed under LGPL-2.1 by VideoLAN and contributors.
- **Backdrop Optical Blurs**: Licensed under Apache-2.0 by Kyant0.
