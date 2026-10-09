# Muse Echo

Turns a LineageOS Echo Show 5 into a [Muse Gadget](https://github.com/facebookincubator/muse-gadget-sdk):
a community device that pairs with the Muse app and stays connected to your
Muse, which can then use the screen and speaker, while you talk to Muse from
the Echo.

It's a Kotlin port of Meta's Linux Device SDK (Meta only ships ESP32 and Linux
SDKs), plus the push-to-talk chat flow from their ESP32 screen boards.

## What Muse can do with it

| Command | What it does |
|---|---|
| `display.draw_url` | Download an image and show it full screen (wakes the screen, comes to the front) |
| `display.show_text` | Show a big text card: a note, a list, a reminder |
| `display.show_animation` | Back to the idle face and clock |
| `display.brightness` | Get or set screen brightness |
| `voice.say` | Speak text aloud (needs a TTS engine) and caption it |
| `voice.configure` | Get or set speaker volume |
| `audio.play_url` / `audio.stop` | Stream audio or internet radio |
| `device.health` | Uptime, memory, storage, Wi-Fi signal, volume, screen state |
| `notes.get` / `notes.set` / `notes.add` / `notes.remove` / `notes.clear` | The pinned note on the home screen (e.g. a shopping list); tap a line to tick it off |
| `glance.set` / `glance.clear` | A line under the clock (next event, reminder), next to the Echo's own weather |
| `agenda.set` / `agenda.clear` | The agenda page (swipe left from the clock): today's events, filled in with the morning brief |
| `frame.add` / `frame.list` / `frame.remove` / `frame.show` | The photo frame: an idle slideshow with the clock (starts after 5 idle minutes by default; a tap ends it). Photos can also be pushed to `/sdcard/Android/data/com.rusty.museecho/files/frame/` |
| `quick_actions.get` / `quick_actions.set` | The home screen's quick-action buttons (up to 4), each sending a preset request to Muse |
| `media.control` | Play/pause/next/previous/stop for whatever plays on the Echo |
| `display.set_profile` | Muse tells the Echo its name, shown instead of "Muse" |
| `timer.set` / `alarm.set` / `timers.list` / `timers.cancel` | Timers and (repeating) alarms with an on-screen countdown, chime and spoken label |
| `tv.status` / `tv.key` / `tv.apps` / `tv.launch` / `tv.type` | The Roku TV over ECP (found by SSDP) |
| `sofa.mode` | Switch the Echo to Sofa in home/school/tv/coding/lmstudio/ambient mode |
| `laptop.shortcuts` / `laptop.run` | The Sofa laptop companion's safelisted shortcuts (needs its token in setup) |

And from the Echo: tap the mic to send Muse a voice note (or the keyboard icon
to type). Muse's reply streams in as a caption, and is spoken if a TTS engine
is installed.

## Setup

1. Build and install: `tools/deploy.sh <adb serial or ip:port>`.

2. Optional grants, so Muse can bring the app to the front and change brightness:

   ```sh
   adb -s G6G11B06033403LD shell appops set com.rusty.museecho SYSTEM_ALERT_WINDOW allow
   adb -s G6G11B06033403LD shell appops set com.rusty.museecho WRITE_SETTINGS allow
   ```

3. Get an SDK token at gadgets.muse.ai (Account > SDK tokens). Paste it in the
   app's setup panel (gear icon), or push it:

   ```sh
   echo -n 'mgst_…' > sdk_token
   adb push sdk_token /sdcard/Android/data/com.rusty.museecho/files/
   ```

4. Setup panel > **Start pairing**. In the Muse app: Settings > Devices > turn
   on Developer mode, Add device (+), pick `MuseGadgetXXXXXX`, accept the
   community-device warning, and choose the network shown (no password).

### If the Echo's Bluetooth can't advertise

The setup panel says so ("can't advertise as an LE peripheral"). Pair from a
Linux machine with Bluetooth instead, using Meta's own SDK, and hand the
result to the Echo:

```sh
tools/pair-via-linux.sh mgst_…
```

Bluetooth is only used for that one-time setup; after it, everything is a
normal internet connection.

## Beyond your own questions

The app keeps Muse's reply stream open all the time, so the Echo also shows
(and speaks) messages that didn't start on it, each toggleable in setup:

- **Messages from Muse**: things Muse sends on its own, like reminders.
- **Gadget alerts**: messages your other gadgets post (e.g. `musegadget
  send-user-msg "Backup failed"` on the server) and Muse's answer.
- Conversations on your phone never appear on the Echo.

Replies render basic Markdown and show any pictures Muse attaches (tap one
for full screen). With **follow-up listening** on, the Echo listens for a
few seconds after Muse answers, so you can reply without the wake word.

Say **"stop"** (or tap the screen) to cut Muse off mid-sentence. The **mic
switch** in the top bar turns the wake word and voice notes off, shown in red.
The **sun** asks for the morning brief (weather, your day, server status),
which the first "hey muse" after quiet hours also gets. **History** shows past
exchanges on the Echo.

**Quiet hours** (default 22:00 to 07:00): no wake word, no speaking, a dim
screen and soft timer beeps; alarms still ring. After a few idle minutes the
screen switches off completely; a tap, alarm or alert brings it back.

## Photo frame

`tools/frame-pick.sh` opens a file picker (multi-select, starting in
`~/Pictures/wallpapers`), shrinks each picture to a 1280 px JPEG and copies
it to the Echo; `--list` and `--clear` manage what's there. Muse can also add
pictures by URL (`frame.add`).

## Offline

When Muse can't be reached, the Echo still handles the basics itself: voice
notes are transcribed on the device (Vosk) and timers, alarms, the time and
date, and the last weather are answered locally, marked "Offline".

## The Latitude's echo-hub

[latitude-echo-hub](../latitude-echo-hub) serves homelab health from Uptime
Kuma (the status light by the connection chip; tap for each monitor) and the
newest build for updates. Publish with `tools/release.sh "what changed"`; the
Echo checks every 6 hours (setup > Updates > Check now) and offers it, and
Android asks to confirm the install. The version code is the commit count.

## Keeping it healthy

`device.health` includes app uptime, whether the reply feed and wake word are
running, quiet-hours state and recent errors (crashes included), so you can
ask Muse "is the Echo OK?". A watchdog restarts the wake word and kicks the
connection if it's been down five minutes, and a 15-minute alarm restarts the
service if Android ever kills it.

Deploy from the laptop in one step (builds, tests, installs, waits for Muse):

```sh
tools/deploy.sh 192.168.1.85:35395
```

## Wake word

Setup panel > Wake word: say the phrase (default "hey muse"), talk, and the
note sends by itself about a second after you stop (only after the wake word
or a follow-up; the mic button still sends on a tap). End of speech is
measured against the room's background noise, learned while listening, so
it works across the room and with a TV on. It runs offline with [Vosk](https://alphacephei.com/vosk/)
limited to just that phrase, behind a volume gate, so it uses almost no CPU in
a quiet room. The model isn't in the APK; push it once:

```sh
unzip vosk-model-small-en-us-0.15.zip
adb push vosk-model-small-en-us-0.15 /sdcard/Android/data/com.rusty.museecho/files/
adb shell mv /sdcard/Android/data/com.rusty.museecho/files/vosk-model-small-en-us-0.15 /sdcard/Android/data/com.rusty.museecho/files/vosk-model
```

## Speech

Setup > **Voice quality** picks the voice: the system TTS engine, or Piper
"lessac" neural voices that run inside the app (sherpa-onnx), Low, Medium or
High, each downloaded once (63/64/110 MB). Generation overlaps playback and
the first clause is spoken as soon as it's ready. Measured on the Echo Show 5
("Test speed" runs silently): Low 1.0x real time, Medium 0.7x, High 0.1x
(too slow). Full-precision models: the int8 builds ran 3x slower on its
32-bit ARM userspace. The sherpa-onnx AAR is fetched at build time
(pinned SHA-256) into `app/libs/`.

Setup > **Wake word sensitivity**: Low (strict, close range), Medium (across
the room, full phrase), High (quiet/distant speech; the last word alone
counts, most false wake-ups).

### System voice

The app prefers the neural Piper voice from the sherpa-onnx TTS engine
(`com.k2fsa.sherpa.onnx.tts.engine`, e.g. the en_US lessac-medium build from
huggingface.co/csukuangfj/sherpa-onnx-apk, armeabi-v7a) and falls back to
whatever engine is installed (RHVoice from F-Droid works). With none, replies
are captions only. Push-to-talk
records on the Echo and Muse transcribes it server-side, so no speech
recognizer is needed on the device.

## How it works

- `protocol/Pairing.kt`, `SetupController.kt`, `BleFraming.kt`: BLE pairing v5
  (P-256 ECDH, HKDF-SHA256, AES-256-GCM records) and the setup commands,
  served by `ble/BleSetupServer.kt` as an Android GATT peripheral.
- `noise/`: Noise_XX_25519_AESGCM_SHA256 and the HTTP-over-Noise protobuf
  envelopes.
- `protocol/LinkSession.kt`: the `/v1/noise` WebSocket, `link.register`,
  `link.invoke`/`link.result` on `/link-control`, and extra request streams.
- `gadget/GadgetRunner.kt`: reconnect loop, VM lookup, token rotation (port of
  `service.py`). `gadget/ChatClient.kt` + `protocol/ReplyTracker.kt`:
  push-to-talk turns over `/chat/stream` and `/chat/subscribe`.

The tests check the port against Meta's published pairing vectors and against
byte-exact vectors generated from their Python Noise implementation
(`tools/gen_noise_vectors.py`), and run the link and chat flows against an
in-process fake VM.

```sh
ANDROID_HOME=~/Android/Sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew testDebugUnitTest
```

## License

AGPL-3.0-or-later, with portions under Apache-2.0 from Meta's SDK. See
[NOTICE](NOTICE).
