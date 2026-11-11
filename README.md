# AirplayServer

An AirPlay screen-mirroring and audio receiver for Android, written almost entirely in C.

This fork (the `fire-tv-stick` branch) makes it build with current tools and work reliably on a **Fire TV Stick
running Fire OS 5 (Android 5.1, API 22)**: see [Changes in this fork](#changes-in-this-fork).
The original README follows, translated from Chinese.

## Introduction

`AirPlay 2` is a feature Apple added in `iOS 11.3` for casting video and audio over the local network.

`AirplayServer` is the receiving end: it receives data from devices that support it.

`AirplayServer` runs on `Android` devices. The code is 99% C, which makes it easy to port.

(In practice the code implements screen mirroring and AirTunes audio, the protocol iOS calls Screen Mirroring. It
does not implement the multi-room audio of AirPlay 2, nor AirPlay video casting, where an app hands the receiver a
video link to play.)

## Features

1. Publishes the service over mDNS
2. Handshake protocol
3. Receives mirroring data
4. Hardware decoding and display with MediaCodec
5. Receives audio data
6. Decodes audio with fdk-aac
7. Plays the PCM audio with AudioTrack

## Demo screenshot

The screenshot below shows a screen and music cast; the iPhone was running iOS 12.

![](https://ww1.sinaimg.cn/large/007rAy9hgy1g0l65hwvg7j30u01o0juj.jpg)

## Changes in this fork

The 2019 code worked for the first phone that connected after the app started, and then not again. On a Fire TV
Stick it also dropped frames, trailed the sound behind the picture and hung some apps. This fork:

- **Builds today.** Gradle files for Android Gradle Plugin 8.5.2 and Gradle 8.9, NDK 27 (the C code needs `string.h`
  and `stdlib.h` pulled in), no proxy settings, an optimised `release` build. Do not use the debug build: its
  native code is unoptimised and far too slow for video.
- **Handles more than one connection.** The SETUP steps were counted in one process-wide variable, so only the first
  connection got through. They are now told apart by what each request carries, and the keys are kept per
  connection so a stopped audio stream can start again.
- **Ends only the stream the phone names.** Every `TEARDOWN` used to end everything, so pausing the sound also
  killed the picture.
- **Is not choppy.** The video loop slept 50 ms after every frame and the native code re-attached to Java for every
  frame. The decoder is now drained at once, the Java lookups are cached, and per-packet logging is gone.
- **Sizes the picture to the phone's shape** (a portrait phone gets bars at the sides) instead of stretching it to
  fill the TV.
- **Keeps the sound with the picture.** The phone sends its first second of audio ahead of time; the old player
  queued all of it, so the sound trailed by nearly a second. The queue is now capped at about 65 ms.
- **Answers what it cannot do.** Requests without a `CSeq` header (AirPlay video casting) were dropped without a
  reply, so apps such as YouTube waited for ever. They now get `501 Not Implemented` and a log line, and the
  receiver no longer advertises video casting.
- Asks only for the permissions it uses (`INTERNET`, `ACCESS_WIFI_STATE`), has a TV launcher entry, keeps the screen
  on, starts listening on its own and is called "AirPlay Receiver" / "Fire Stick" instead of "My Application" / "t1".

## Build and install

You need JDK 17 or newer and the Android SDK with `ndk;27.0.12077973`, `cmake;3.22.1` and `platforms;android-34`:

```bash
sdkmanager --install "ndk;27.0.12077973" "cmake;3.22.1" "platforms;android-34"
export ANDROID_HOME=~/Library/Android/sdk                      # wherever your SDK is
sh ./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk   # debug-signed, so it installs without a key
adb shell am start -n com.fang.myapplication/.MainActivity
```

Open the app on the TV and leave it open: it starts listening by itself. On the iPhone, iPad or Mac choose
**Screen Mirroring** and pick **Fire Stick** (the name is set in `DNSNotify.java`).

## Limits

- The picture is drawn into the app's window, so it shows **only while the app is open on the TV**.
- **No AirPlay video casting.** The cast button inside apps such as YouTube does not work; use Screen Mirroring from
  Control Center and play the video on the phone.
- **No password or PIN:** anyone on the same Wi-Fi can mirror to it.
- Apps that block mirroring (Netflix, the Apple TV app and the like) show black.
- If "Fire Stick" stops appearing in the phone's list after the TV has been idle for a long time, close and reopen
  the app: its network registration can silently drop.
- It was tested with iPhones on one device: a Fire TV Stick (2nd gen, Fire OS 5.2.9.5, MediaTek MT8127). Nothing else.

`adb logcat -s raop VideoPlayer AudioPlayer` shows every request and, once a second, the frames and audio packets
received and shown.

## Licence and credits

The original repository, [KqSMea8/AirplayServer](https://github.com/KqSMea8/AirplayServer), has no licence file, so
the original author's code is theirs and all rights are reserved. This fork has not been agreed with them and adds
no licence of its own. Some bundled code carries its own licence in its folder: mDNSResponder (Apache 2.0, in
`app/src/main/cpp/mDNSResponder/LICENSE`), the Fraunhofer FDK AAC codec (`app/src/main/cpp/lib/fdk-aac/NOTICE`) and
playfair (GPL-3.0, `app/src/main/cpp/lib/playfair/LICENSE.md`). The rest of the bundled code has no licence file.
"AirPlay" is a trademark of Apple; this project is not affiliated with or endorsed by Apple.
