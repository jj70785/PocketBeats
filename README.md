# Pocket Beats

A music player for Android 2.3.4 Gingerbread (API 10), built for the HTC Droid Incredible.

Pure Java — no native libraries, no Jetpack, no Kotlin. Runs on ARMv6 hardware with 512MB RAM.

## Features

- Browse all music on the device via MediaStore
- Play, pause, next, previous track
- Seek bar with real-time position updates
- Album art with downsampled bitmaps (300x300 max)
- Shuffle mode (Fisher-Yates)
- Repeat modes: Off / Repeat All / Repeat One
- Background playback via bound Service
- Search and filter songs in real-time
- Sort by title, artist, or album
- Persistent status bar notification while playing
- Audio focus handling (pauses on incoming calls)
- Memory-safe — runs under 5MB PSS

## Build

Requires Android SDK with `platforms;android-16` and `build-tools;33.0.2`.

```bash
export ANDROID_HOME=~/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

cd ~/MusicPlayer
./gradlew assembleDebug
```

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.pocketbeats/.MainActivity
```

## Architecture

| Class | Role |
|---|---|
| `Song` | Data model (id, title, artist, album, albumId, path, duration) |
| `SongAdapter` | ArrayAdapter for ListView |
| `MusicService` | MediaPlayer + playback logic + Binder |
| `MainActivity` | Song library with search and sort |
| `PlayerActivity` | Now-playing UI, binds to MusicService |

`MusicService` is the single source of truth for playback state. Both activities bind to it. The service survives activity destruction for background playback.

## Constraints

This app targets hardware from 2010. The following are not available and must not be used:

- Native `.so` libraries (ARMv6 CPU — no ARMv7)
- ConstraintLayout, RecyclerView, ViewBinding, DataBinding
- Kotlin, lambdas, streams (Java 7 only)
- Google Play Services
- Any API above level 10 without a `Build.VERSION.SDK_INT` guard
