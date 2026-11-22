# MICA

<div align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_ms_logo.png" width="128" height="128" alt="MICA Logo" style="border-radius: 28px;" />
  <h3>ONE APP. EVERY EXPERIENCE.</h3>
  <p><strong>The Universe of Entertainment — Music • Movies • Streaming • AI Subtitles • Beyond</strong></p>
  <p>
    <a href="https://github.com/AWT-SRIRAM/MICA/releases"><img src="https://img.shields.io/badge/Release-v0.8.6-blue?style=for-the-badge" alt="Release v0.8.6" /></a>
    <img src="https://img.shields.io/badge/Platform-Android%208.0+-green?style=for-the-badge" alt="Android 8.0+" />
    <img src="https://img.shields.io/badge/Architecture-ARM64--v8a-orange?style=for-the-badge" alt="ARM64-v8a" />
    <img src="https://img.shields.io/badge/License-MIT-purple?style=for-the-badge" alt="License MIT" />
  </p>
</div>

---

## 🌟 Overview

**MICA** is a flagship, offline-first, privacy-respecting multimedia entertainment platform built natively with **Kotlin**, **Jetpack Compose**, and hardware-accelerated **LibVLC Android 3.7.7**. 

MICA unites a **Spotify-grade Lossless Music Hub**, a **4K/8K HDR Video Cinema**, a **Brave-style Ad-Shield Browser with direct stream sniffing for anime & cartoons**, an **interactive Android Home Screen Widget (Style 1 Control Center Glass)**, and **MICA JAM social listening** into one cohesive, buttery-smooth Liquid Glass interface.

- **Package ID**: `app.mica`
- **Current Version**: `0.8.6` (Build Code `34`)
- **Creators**: **SRIRAM S & ANTIGRAVITY**
- **Repository**: [github.com/AWT-SRIRAM/MICA](https://github.com/AWT-SRIRAM/MICA)
- **Target Device Profile**: iQOO Neo 10R / All Android 8.0+ (API 26–36) devices

---

## 🚀 Key Highlights & Features

### 🎵 1. Spotify-Grade Music Hub & Streaming Engine
- **Dual Lossless Backend**: Seamlessly resolves 320 kbps lossless streams from JioSaavn with high-speed fallback to YouTube Music (iOS/VR client endpoints) with zero audio compression artifacts.
- **Smart Title Deduplication**: Intelligent fuzzy deduplication engine (`normalizeTrackTitle`) that eliminates repeat suffix variations (`(From "...")`, `[From ...]`, `- Best of ...`) across trending charts and search results.
- **Adaptive Liquid Glass Dice FAB**: Floating random pick button adapting dynamically to Light and Dark Liquid Glass themes, instantly playing a high-energy trending track on tap without intrusive popups.
- **Full-Screen Modern Player**:
  - Perfectly symmetrical Material 3 Skip Previous and Skip Next controls (`32dp` touch targets).
  - Tri-state Repeat mode: **Repeat All 🔁**, **Repeat One 🔂 (1-badge)**, and **Shuffle 🔀** with non-repeating queue history.
  - Symmetrical bottom utility dock with **Cast & Audio Output**, **Live Synchronized Lyrics**, and **MICA JAM Social Listening**.
  - Clean **3-Dots Options Menu** housing the **10-Band Studio Equalizer**, **Sleep Timer with soft fade-out**, and **Codec Info**.

### 📱 2. Spotify-Style Android Home Screen Widget
- **Interactive 4x2 Home Widget** (`MicaMusicWidgetProvider`):
  - **Live Now Playing Card**: Displays real-time album artwork, bold track title, artist info, previous/next controls, and an animated circular Play/Pause button.
  - **Real-Time 950ms Timeline Sync**: Sub-second widget progress bar tracking via `partiallyUpdateAppWidget`, keeping elapsed and total time in exact lockstep with music playback.
  - **4 Interactive Quick-Pick Cards**: Curated trending recommendations (*Trending Tamil*, *Top Hits*, *Lofi Beats*, *Discover*) with real album covers and quick-play badges.
  - **Zero-Latency IPC**: Bi-directional updates between `PlaybackService` and the Android home launcher with instant playback triggers.

### 🎬 3. Cinema-Grade 4K/8K Video Player
- **LibVLC 3.7.7 Engine**: Hardware-accelerated decoding for HEVC/H.265, AV1, VP9, H.264, EAC3, TrueHD, DTS-HD, and raw network streams.
- **Single-Tap CC Vertical Drag-to-Scale**: Dedicated CC button toggles a smooth vertical drag gesture for continuous subtitle scaling with real-time HUD feedback.
- **Persistent Subtitle Scaling**: Custom subtitle sizing automatically persists across video files, streaming sessions, and full-screen toggles without resetting.
- **Unified Single-Engine Subtitle Rendering**: Streamlined subtitle rendering eliminating border doubling, delivering clean, sharp, legible font outlines across all lighting conditions.
- **Intuitive Touch Gestures**: Logarithmic volume on the right, linear brightness on the left, horizontal swipe seeking, 400% pinch-to-zoom, and temporary 4.0x fast-forward speed capsules.

### 🛡️ 4. MICA Shield Browser & Stream Sniffer
- **Brave-Style Built-in Ad-Blocker**: Multi-layer host interception blocking advertising scripts, popups, and intrusive banners with automatic cosmetic CSS element collapsing.
- **Universal Anime & Cartoon Stream Sniffer**:
  - Automatically intercepts `.m3u8`, `.mp4`, HLS, DASH, and video player elements across popular anime streaming portals (`myanimes.in`, `tamilcartoonsrare.in`) and YouTube.
  - Surfaces an animated floating glass pill: **"▶ Play in MICA Video Player"** to hand off streams directly to LibVLC hardware acceleration with subtitle controls.
  - Excludes direct video player URLs from accidental interception and forwards authentic browser headers.

### 👥 5. MICA JAM — Worldwide Social Listening
- **Global HTTPS SSE Relay (Port 443)**: Listen together anywhere in the world over standard HTTPS Server-Sent Events (`ntfy.sh`). Completely immune to carrier port blocks (works seamlessly on 4G/5G mobile networks, mobile hotspots, and firewalled Wi-Fi).
- **Sub-40ms Precision Sync Engine**:
  - **Dynamic Micro-Tempo Steering ($\pm 2\%$ to $\pm 8\%$)**: Subtly accelerates or slows playback speed to glide into exact phase sync within 1–2 seconds without audible audio pops, clicks, or pitch distortion.
  - **800ms Low-Latency Stream Caching**: Low-overhead network caching for Jam rooms, slashing startup buffering delay by over 5 seconds.
  - **Direct Start-Time Hand-Off**: Automatically starts playback at the host's exact elapsed millisecond, eliminating seek re-buffering loops.
- **Permanent `JAM - ` Prefix Badge**: Intuitive Join UI featuring a permanent monospace purple badge so users only need to type the 4-digit room code (e.g. `3213` or `8957`).
- **Live Bidirectional Peer Heartbeats**: Real-time participant counter (`1 peer connected`, `N peers`) with automatic detection of host and listeners.
- **Supervisor-Backed Session Persistence**: Jam connections survive app switching, screen rotation, background playback, and device lock without state reset.
- **Universal Local-to-Stream Resolving**: Automatically resolves online audio streams when the host plays local files so all remote friends can listen along.

### 💎 6. Liquid Glass Architecture
- **Reflective Optical Surfaces**: Built with real-time backdrop blur, light refraction, and frosted borders.
- **4 Custom Themes**:
  1. *White Liquid Glass*
  2. *Light Liquid Glass*
  3. *Dark Liquid Glass*
  4. *Amoled Pure Dark*

---

## 📥 Download & Installation

| Release File | Version | Target ABI | Download Link |
| :--- | :--- | :--- | :--- |
| **`MICA-PLAYER-0.8.6-arm64.apk`** | `v0.8.6` | ARM64 (`arm64-v8a`) | [Direct Download](https://github.com/AWT-SRIRAM/MICA/releases) |

### SHA-256 Checksum:
Verify package integrity before installation:
```bash
sha256sum MICA-PLAYER-0.8.6-arm64.apk
```

---

## 🛠️ Building From Source

### Prerequisites
1. **JDK 17** (e.g. OpenJDK 17 / Temurin)
2. **Android SDK API 36** with **Build Tools 36.0.0**
3. **Gradle 8.11.1** (included via `./gradlew`)

### Build Commands
```bash
# Clone the repository
git clone https://github.com/AWT-SRIRAM/MICA.git
cd MICA

# Assemble Debug APK
./gradlew assembleDebug

# Assemble Release Signed APK (ARM64)
./gradlew assembleRelease -PtestAbi=arm64-v8a --no-daemon
```

---

## 📄 Documentation & Architecture
For deep technical documentation, demuxing pipelines, and component graphs, see [**`MICA_DOCUMENTARY.md`**](MICA_DOCUMENTARY.md).

---

## ⚖️ License & Provenance
MICA application source code is distributed under the [MIT License](LICENSE).  
LibVLC Android binaries and headers are licensed under [LGPL-2.1](app/src/main/assets/licenses).
