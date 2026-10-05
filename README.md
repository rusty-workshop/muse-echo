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
| `display.set_profile` | Muse tells the Echo its name, shown instead of "Muse" |
| `timer.set` / `alarm.set` / `timers.list` / `timers.cancel` | Timers and (repeating) alarms with an on-screen countdown, chime and spoken label |
| `tv.status` / `tv.key` / `tv.apps` / `tv.launch` / `tv.type` | The Roku TV over ECP (found by SSDP) |
| `sofa.mode` | Switch the Echo to Sofa in home/school/tv/coding/lmstudio/ambient mode |
| `laptop.shortcuts` / `laptop.run` | The Sofa laptop companion's safelisted shortcuts (needs its token in setup) |

And from the Echo: tap the mic to send Muse a voice note (or the keyboard icon
to type). Muse's reply streams in as a caption, and is spoken if a TTS engine
is installed.

## Setup

1. Build and install:

   ```sh
   ANDROID_HOME=~/Android/Sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew assembleDebug
   adb -s G6G11B06033403LD install -r app/build/outputs/apk/debug/app-debug.apk
   ```

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

## Wake word

Setup panel > Wake word: say the phrase (default "hey muse"), talk, and the
note sends when you stop. It runs offline with [Vosk](https://alphacephei.com/vosk/)
limited to just that phrase, behind a volume gate, so it uses almost no CPU in
a quiet room. The model isn't in the APK; push it once:

```sh
unzip vosk-model-small-en-us-0.15.zip
adb push vosk-model-small-en-us-0.15 /sdcard/Android/data/com.rusty.museecho/files/
adb shell mv /sdcard/Android/data/com.rusty.museecho/files/vosk-model-small-en-us-0.15 /sdcard/Android/data/com.rusty.museecho/files/vosk-model
```

## Speech

LineageOS ships no text-to-speech engine. Install one (RHVoice or eSpeak NG
from F-Droid) to hear replies; without it they're captions only. Push-to-talk
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
