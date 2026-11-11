# CONTEXT.md

Orientation for whoever works on this fork next, human or AI assistant. The [README](README.md) says what the
project is and how to use it; this file says how it works inside, what was learned the hard way, and what to be
careful of. Written 2026-11-11 against branch `fire-tv-stick` (upstream: KqSMea8/AirplayServer, commit `5216b4d`,
"init project", February 2019).

## Where things stand

- **Target device:** Fire TV Stick 2nd gen (model AFTT "tank"), Fire OS 5.2.9.5 = Android 5.1 = API 22, 32-bit ARM
  (`armeabi-v7a`), MediaTek MT8127, about 895 MB of RAM, hardware H.264 decoder `OMX.MTK.VIDEO.DECODER.AVC`. No
  H.265. It cannot be upgraded.
- **Works:** iPhone Screen Mirroring with sound at 25 to 30 fps, repeated connections, audio stopping and starting
  while the picture stays, a phone turned sideways (the picture resizes).
- **Does not:** AirPlay video casting, any password or PIN, running while another app is in front, DRM apps
  (they show black).
- **Not tested:** iPad, Mac, other Fire TV models, other Android devices.

## Code map

Java, `app/src/main/java/com/fang/myapplication/`:

| File | Role |
|---|---|
| `MainActivity` | One screen: a `SurfaceView`, a Start/Stop button and the name label. Presses Start by itself on launch, stops the server in `onDestroy`, keeps the screen on. |
| `RaopServer` | The JNI bridge. Loads `raop_server` and `play-lib`; natives `start()`, `stop()`, `getPort()`. Native threads call back into `onRecvVideoData(byte[] nal, int nalType, long dts, long pts)` and `onRecvAudioData(short[] pcm, long pts)`. Creates both players in its constructor and hands surface changes to the video player. A session starts with the first video frame and ends when the native side calls `onVideoEnded()` (the phone stopped it, the connection died, or a new session replaced it); then it resets both players and shows the idle view (the "Ready" text that covers the last picture). Resizes the `SurfaceView` to the video's aspect ratio. `release()` stops the server and both player threads. |
| `player/VideoPlayer` | A thread around `MediaCodec` (`video/avc`, output straight to the `Surface`). Feeds packets from a queue, drains the decoder at once, reports the picture size when the output format changes, logs once a second. Recovers on its own: see "Things that bit us", number 17. |
| `player/AudioPlayer` | `AudioTrack`, 44.1 kHz stereo 16-bit, with a queue capped at 6 packets (about 65 ms). |
| `DNSNotify` | The mDNS records: `_airplay._tcp` and `_raop._tcp`, with the TXT records (`features`, `model`, `pk`, `deviceid` from the real wlan0 MAC). Registers through `dnssd/DNSSDBindable` and Apple's `com.apple.dnssd` Java API; `DNSSDBindable` touches Android's `NSD_SERVICE` so the system mDNS daemon runs. |
| `com/apple/dnssd/*` | Apple's mDNSResponder Java API, untouched. |

Native, `app/src/main/cpp/`:

| File | Role |
|---|---|
| `jni_raop_server.cpp` | The glue: starts the `raop` server and passes decoded audio and video up to Java. Attaches each native thread to the JVM once (a pthread key detaches it) and looks the Java methods up once. |
| `lib/raop.c` | The server front: `conn_init`, `conn_request`, `conn_destroy`; routes each request by method and URL; handles `TEARDOWN` and `FLUSH` inline; holds the per-connection state (`raop_conn_s`). |
| `lib/raop_handlers.h` | The handlers: `/info` (a hard-coded binary plist), `/pair-setup`, `/pair-verify`, `/fp-setup`, `OPTIONS`, `SETUP`, `GET_PARAMETER`, `SET_PARAMETER`, `/feedback`, `RECORD`. |
| `lib/httpd.c`, `http_*.c` | The small built-in HTTP/RTSP server and parser. A request that leaves `*response` unset gets no reply at all. |
| `lib/pairing.c`, `lib/curve25519`, `lib/ed25519` | Pair-setup and pair-verify. |
| `lib/fairplay_playfair.c`, `lib/playfair/` | FairPlay: decrypts the `ekey` the phone sends (GPL-3.0 code). |
| `lib/raop_rtp.c`, `lib/raop_buffer.c` | Audio: UDP data, control and timing sockets; reordering and resends; AAC-ELD decoding with fdk-aac into PCM (960 shorts per packet). |
| `lib/raop_rtp_mirror.c`, `lib/mirror_buffer.c` | Mirroring: a TCP stream, AES-CTR decrypted; the config packet becomes SPS/PPS with Annex-B start codes (`frame_type` 0), the rest are frames (`frame_type` 1). |
| `mDNSResponder/`, `lib/fdk-aac/` | Apple's mDNS (Apache 2.0) and Fraunhofer's AAC codec, built into `libjdns_sd` and `libfdk-aac`. |

## What a phone sends

One mirroring session, in order, all on one TCP connection to the advertised port (the same for both services; seen
in `logcat`):

1. `GET /info`, then `POST /pair-setup`, `POST /pair-verify` (twice), `POST /fp-setup` (twice).
2. `SETUP` with `ekey`, `eiv` and `timingPort`: decrypt the key, create the audio and mirror objects, keep the keys in
   the connection.
3. `GET_PARAMETER`, `RECORD`.
4. `SETUP` with `streams[0].type == 110` and a `streamConnectionID`: start the mirror thread; the reply carries
   `dataPort`, `timingPort` and `eventPort`.
5. `POST /feedback` about every 2 seconds, for as long as the session lasts.
6. When sound starts: `SETUP` with `streams[0].type == 96`: start the audio UDP threads; the reply carries
   `dataPort`, `controlPort` and `timingPort`. Then a few `POST /audioMode`.
7. When the sound stops: `TEARDOWN` with a plist naming `type` 96. When mirroring stops: `TEARDOWN` naming 110. A
   `TEARDOWN` with no body ends everything. The phone may set the audio up again on the same connection later.

## Things that bit us

1. **Line endings.** Most upstream Java, the JNI file, the manifest and the Gradle files use Windows line endings
   (CRLF), and the fork keeps them. Python's text-mode `open()`, `sed -i` and many editors turn every line into LF
   and make a whole-file diff. After an edit, `git diff --stat` should show small numbers; if it does not, restore
   the CRLFs.
2. **Release builds only.** The debug build runs the native code unoptimised, which is far too slow for video: it
   was choppy and the sound stuttered. `lint { checkReleaseBuilds false }` is there because `targetSdk 22` trips
   `ExpiredTargetSdkVersion`; target 22 is on purpose, because Fire OS 5 has no runtime permissions and everything
   declared is granted at install.
3. **The `SETUP` step is decided by what the request carries, not by a counter.** The original code counted `SETUP`
   calls in a process-wide `static int`, so only the first connection after the app started worked. Do not bring a
   global back. The keys (`aeskey`, `aesiv`, `ecdh_secret`, `timing_rport`) are stored in `raop_conn_s` so an audio
   stream torn down by the phone can be set up again.
4. **`TEARDOWN` names the stream** (96 audio, 110 video, none = all). Ending everything on any `TEARDOWN` killed
   the picture whenever the sound paused.
5. **`features` lives in three places and they must agree:** the `_airplay._tcp` TXT `features`, the `_raop._tcp`
   TXT `ft` (both in `DNSNotify`) and the 64-bit integer in the hard-coded `/info` plist in `raop_handlers.h`
   (the bytes `0x13 0x00 0x00 0x00 0x1e 0x5a 0x7f 0xff 0xe6`). The current value `0x5A7FFFE6,0x1E` has bit 0
   (Video) and bit 4 (video over HTTP Live Streaming) cleared, so apps do not try AirPlay video casting. With them
   set (`...F7`), the YouTube app's cast button hung. The same plist still carries a fake `deviceID` and
   `macAddress` (`aa:54:01:af:c3:c1`) and a fixed `pk`, unlike the mDNS records, which use the real MAC; that has
   not caused trouble, but it has not been proved harmless either.
6. **Both services use the one native port.** The original code advertised a different port for `_airplay._tcp`:
   a dummy Java `ServerSocket` (`AirPlayServer.java`, now deleted) that accepted one connection and never
   answered, so anything a phone sent there, such as AirPlay video casting, hung. `MainActivity.startServer` now
   registers `_airplay._tcp` and `_raop._tcp` on the native server's port, as RPiPlay does. Check:
   `dns-sd -L` of both services shows the same port, and `curl -i http://<stick>:<port>/info` is answered at once
   (with the 501 below, since `curl` sends no `CSeq`).
7. **Requests without `CSeq` get `501 Not Implemented` with `Content-Length: 0`** (`conn_request` in `raop.c`),
   logged as "Unsupported request without CSeq". `http_response_finish` writes a `Content-Length` only when there
   is a body, and an HTTP client without one waits for the connection to close, so any new HTTP-style reply (as
   opposed to an RTSP one) must add the header itself.
8. **Sound is not scheduled by timestamp.** The phone sends its first second or so of audio ahead of time; playing
   it all keeps the sound that much behind the picture. `AudioPlayer` keeps at most 6 queued packets and drops the
   oldest, and the video is shown as soon as it is decoded. That keeps them close, not locked together; the timing
   socket is answered but its clock is not used.
9. **JNI threads.** Native threads call into Java for every frame and packet. ART logs "Native thread exiting
   without having called DetachCurrentThread" at the end of a stream; the pthread-key destructor does detach, so
   it is harmless. Looking the methods up and attaching per call made the video choppy.
10. **The picture needs the activity in front.** When the surface goes away (Home pressed) `RaopServer` tells the
    video player, which drops its decoder; it starts a new one on the next surface and waits for a keyframe
    (number 17). The session itself keeps running on the phone, so the picture returns within a few seconds.
11. **mDNS can vanish.** After the stick has been idle for a long time the registration can silently drop (the
    phone no longer lists it). Close and reopen the app. Check with `dns-sd -B _airplay._tcp local.`
12. **Log level.** The native logger is set to INFO (`raop_set_log_level` in `jni_raop_server.cpp`) and logs one line
    per request. DEBUG logs every packet and costs CPU the decoder needs.
13. **The C code is 2019 C.** NDK 27's clang treats implicit function declarations as errors, so
    `app/build.gradle` passes `-include string.h -include stdlib.h` to every C file. Compiler warnings about pointer
    types in the fdk-aac and playfair calls are old and were left alone.

14. **The mirror reader must never trust the connection.** The original loops did `got += recv()` without looking
    at the result, so a connection that closed in the middle of a frame (`recv() == 0`) spun at full speed for ever,
    and stopping the session then waited for that thread, so the whole receiver hung. The decrypted frame was walked
    with `nc_len` read straight from the data: zero or negative looped for ever, too large wrote past the buffer
    (a desynchronised key after a restarted session produces exactly that). The config packet sized a stack array
    from the network. Now `raop_rtp_mirror.c` reads with `mirror_recv_all` (fails on close, error or stop), caps a
    packet at 8 MB (`MIRROR_MAX_PAYLOAD`), checks every length, drops a bad frame instead of looping, and frees what
    the unused packet kinds used to leak. Keep any new read going through the helper.
15. **Dead peers.** A phone that vanishes (Wi-Fi off, app killed, asleep) never closes its connection. TCP keepalive
    (5 s idle, then 3 probes 3 s apart) is set on the HTTP connections (`httpd.c`) and on the mirror stream, and
    the mirror stream has a 5 s receive timeout, so a dead peer is noticed within about 15 s and freed. `httpd.c`
    also used to treat `recv() == -1` (a reset) as a request of -1 bytes; it now frees the connection. Without
    both, stale connections used up the 10 client slots and nothing could connect. All three `select()` loops (the
    HTTP server, the audio thread, the video thread) treated any failure as fatal, including `EINTR`, which only
    means a signal interrupted the wait. That ended a healthy mirroring session with "Error in select" (seen on
    2026-11-11 while mirroring a Mac) and, in `httpd.c`, would have stopped the whole server. They now retry on
    `EINTR` and log `strerror(errno)` for anything else, with a tag saying which loop it was.
16. **One session at a time.** `g_active_conn` in `raop.c` is the connection that last did the first `SETUP`. When
    another phone (or the same one after a crash) sets up while an older connection is still open, the older one's
    streams are stopped first (`conn_stop_streams`) so two streams never feed the one decoder. A `SETUP` without a
    usable `eiv` or `ekey` closes the connection instead of reading a null pointer.
17. **Session lifecycle in Java.** The mirror thread calls `video_end` when it exits, however it ends
    (`jni_raop_server.cpp` to `RaopServer.onVideoEnded`). `VideoPlayer` takes session start and end as markers in
    its frame queue, so they are handled in order with the frames. It releases the decoder at both, restarts it
    when the surface changes or a call throws (a failed `MediaCodec` throws on everything after), starts a decoder
    mid-stream with the saved SPS/PPS, and then drops frames until a keyframe (scan for NAL type 5; after 3 s it
    decodes whatever arrives, so it cannot wait for ever). When more than 45 frames are queued (about 1.5 s) it
    drops to the next keyframe instead of growing without limit. `MainActivity.onDestroy` calls
    `RaopServer.release()`, because the player threads hold the hardware decoder and the audio track and a second
    launch could not get them. Any Java exception left pending on a native thread aborts the next JNI call, so
    `jni_raop_server.cpp` clears it after each callback.
18. **Testing a bad disconnect without a phone.** `tools/connection_abuse.py <host> <port>` opens silent
    connections, sends half requests and garbage, resets them (RST), opens more than the 10 allowed, and checks the
    receiver still answers. It cannot reach the mirror reader (that needs a completed pairing); that part needs a
    phone: stop mirroring, switch the phone's Wi-Fi off mid-stream, press Home on the TV and come back, and watch
    for `mirroring ended` and the "Ready" screen.

## Building and testing

Build as in the README. Versions this was built and tested with: Android Gradle Plugin 8.5.2, Gradle 8.9 (set in
`gradle/wrapper/gradle-wrapper.properties`; the wrapper jar is the old bootstrap and downloads it), `compileSdk 34`,
NDK `27.0.12077973`, CMake `3.22.1`, JDK 21, ABI `armeabi-v7a` only.

Checks that need no phone:

- `dns-sd -B _airplay._tcp local.` and `dns-sd -B _raop._tcp local.` list "Fire Stick" and `<MAC>@Fire Stick`.
  `dns-sd -L "Fire Stick" _airplay._tcp local.` shows the port and `features=0x5A7FFFE6,0x1E`.
- `adb logcat -v brief | grep -E "raop|VideoPlayer|AudioPlayer"` shows every request. A healthy video line is
  `video: fed 30/s, shown 30/s, queued 0`. Healthy audio is about 92 packets a second (44100 / 480) with `queued`
  between 0 and 3 and a small `dropped so far`.

The real test needs a person with an iPhone: Control Center, Screen Mirroring, **Fire Stick**; play a video with
sound for a minute, pause and resume it, turn the phone sideways. Nothing here can drive a mirror from a script.
`adb` to the stick drops out under load: wait, then `adb connect <ip>:5555` once.

## Open items, roughly in order

1. Use the real device name and one device id everywhere, built with libplist (already linked) instead of the
   hard-coded `/info` bytes (number 5).
2. Run the receiver as a foreground service and bring the activity to the front when a phone connects, so casting
   works from any app. The surface handling in number 10 comes with it.
3. An optional PIN. Today anyone on the Wi-Fi can mirror.
4. Presentation timestamps for real lip-sync (number 8).
5. Hide the action bar (a fullscreen theme) so the picture gets the whole screen; today it loses about 100 px.
6. `AudioPlayer` dropped 293 packets in one long session with its 6-packet cap. A smoother resync (raise the cap a
   little, or drop only when it has stayed over for a moment) might remove the occasional click.
7. AirPlay video casting (HLS): large. It needs the `/reverse` event channel (an HTTP upgrade), `/play`, `/rate`,
   `/scrub`, `/playback-info`, and for YouTube the FairPlay playlist exchange; Android's `MediaPlayer` plays HLS on
   API 22. References: UxPlay (C, GStreamer) and CastBay (Kotlin, ExoPlayer, needs API 23). Not started.

## Provenance

The upstream repository has no licence file, and the bundled playfair code is GPL-3.0, so a built APK that is
shared carries GPL obligations; none has been published. The README lists which bundled parts carry their own
licences. The same changes also exist as a patch against upstream in the owner's `fire-tv-cleanup` repository
(`airplay-receiver/airplay-receiver.patch`, built by `airplay-receiver/build.sh`).
