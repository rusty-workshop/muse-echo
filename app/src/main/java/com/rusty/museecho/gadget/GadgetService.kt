package com.rusty.museecho.gadget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.rusty.museecho.BuildConfig
import com.rusty.museecho.MainActivity
import com.rusty.museecho.R
import com.rusty.museecho.ble.BleSetupServer
import com.rusty.museecho.protocol.ChatFeed
import com.rusty.museecho.protocol.Identity
import com.rusty.museecho.protocol.MuseApi
import com.rusty.museecho.protocol.OkHttpWsConnector
import com.rusty.museecho.protocol.PairingRecord
import com.rusty.museecho.protocol.PairingSession
import com.rusty.museecho.protocol.SetupController
import com.rusty.museecho.protocol.Store
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Keeps the Echo Show connected to Muse as a gadget, in the background, from
 * boot. Also owns the BLE pairing window and push-to-talk turns, so the
 * activity is just a view onto [GadgetState].
 */
class GadgetService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: Store
    private lateinit var identity: Identity
    private lateinit var api: MuseApi
    private lateinit var runner: GadgetRunner
    private lateinit var commands: EchoCommands
    private lateinit var speaker: Speaker
    lateinit var neural: NeuralVoice
        private set
    private lateinit var chat: ChatClient
    private var runnerJob: Job? = null
    private var pairingJob: Job? = null
    private var ble: BleSetupServer? = null
    private var setup: SetupController? = null
    private var turnJob: Job? = null
    lateinit var timers: com.rusty.museecho.integrations.Timers
        private set
    lateinit var laptop: com.rusty.museecho.integrations.Laptop
        private set
    lateinit var roku: com.rusty.museecho.integrations.Roku
        private set
    private val recorder = VoiceRecorder()
    private val imageHttp = okhttp3.OkHttpClient.Builder().callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
    lateinit var errors: ErrorLog
        private set
    lateinit var notes: com.rusty.museecho.integrations.Notes
        private set
    lateinit var history: com.rusty.museecho.integrations.History
        private set
    lateinit var glance: com.rusty.museecho.integrations.Glance
        private set
    lateinit var frame: com.rusty.museecho.integrations.PhotoFrame
        private set
    lateinit var agenda: com.rusty.museecho.integrations.Agenda
        private set
    lateinit var quickActions: com.rusty.museecho.integrations.QuickActions
        private set
    private val weather = com.rusty.museecho.integrations.Weather()
    lateinit var hub: com.rusty.museecho.integrations.Hub
        private set

    /** Set when quiet hours end; the next "hey muse" gets the morning brief. */
    @Volatile
    private var briefPending = false
    private val startedAt = System.currentTimeMillis()
    private var disconnectedSince = 0L
    private lateinit var wakeWord: WakeWord
    private var chime: android.media.Ringtone? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        errors = ErrorLog(java.io.File(filesDir, "errors.json")).also { it.installCrashHandler(this) }
        store = Store(filesDir)
        importDroppedFiles()
        identity = store.loadOrCreateIdentity()
        val ua = "musegadget/${BuildConfig.VERSION_NAME} (Echo Show 5; Android ${Build.VERSION.RELEASE}) museecho"
        api = MuseApi(ua, log = GadgetState::log)
        neural = NeuralVoice(java.io.File(filesDir, "tts")) { GadgetState.log(it) }
        speaker = Speaker(this, neural)
        timers = com.rusty.museecho.integrations.Timers(java.io.File(filesDir, "timers.json"))
        laptop = com.rusty.museecho.integrations.Laptop(this)
        roku = com.rusty.museecho.integrations.Roku(this)
        importDroppedSettings()
        GadgetState.setMuseName(Prefs(this).museName)
        notes = com.rusty.museecho.integrations.Notes(java.io.File(filesDir, "notes.json"))
        history = com.rusty.museecho.integrations.History(java.io.File(filesDir, "history.json"))
        glance = com.rusty.museecho.integrations.Glance(java.io.File(filesDir, "glance.json"))
        GadgetState.setMicOff(Prefs(this).micOff)
        GadgetState.setServerPill(Prefs(this).showServerPill)
        hub = com.rusty.museecho.integrations.Hub(this)
        agenda = com.rusty.museecho.integrations.Agenda(java.io.File(filesDir, "agenda.json"))
        frame = com.rusty.museecho.integrations.PhotoFrame(java.io.File(getExternalFilesDir(null), "frame"))
        quickActions = com.rusty.museecho.integrations.QuickActions(java.io.File(filesDir, "quick_actions.json"))
        val home = HomeCommands(this, roku, timers, laptop, ::bringToFront, notes, glance, frame, quickActions,
            showFrame = {
                GadgetState.showFrame(true)
                bringToFront(force = true)
            },
            agenda = agenda)
        commands = EchoCommands(this, speaker, ::bringToFront, home, ::isQuietNow, { healthExtras() })
        runner = GadgetRunner(
            store = store,
            identity = identity,
            api = api,
            connector = OkHttpWsConnector(ua),
            displayName = Prefs(this).displayName,
            version = BuildConfig.VERSION_NAME,
            commands = commands.specs,
            runCommand = commands::run,
            onRegistered = { chat.runChat { runner.chatSession() } },
        )
        chat = ChatClient(identity.nodeId)
        scope.launch { chat.updates.collect { runCatching { onFeedUpdate(it) } } }
        startInForeground()
        runnerJob = scope.launch { runner.run() }
        scope.launch { tickTimers() }
        scope.launch { watchdog() }
        scope.launch { refreshWeather() }
        scope.launch { watchServer() }
        scope.launch {
            while (true) {
                checkForUpdate()
                delay(6 * 3600_000L)
            }
        }
        scope.launch { applyVoiceLevel() }
        com.rusty.museecho.BootReceiver.scheduleKeepAlive(this)
        wakeWord = WakeWord(
            modelDir = java.io.File(getExternalFilesDir(null), WakeWord.MODEL_DIR_NAME),
            onWake = { scope.launch { onWake() } },
            log = GadgetState::log,
            onStopWord = { scope.launch { onStopWord() } },
            strict = { GadgetState.speaking.value },
        )
        applyWakeWord()
        scope.launch {
            GadgetState.link.collectLatest { updateNotification() }
        }
        GadgetState.log("gadget ${identity.nodeId} started")
        // The process died (crash, native abort) while the app was on screen: bring it back.
        if (getSharedPreferences("museecho", MODE_PRIVATE).getBoolean("ui_in_front", false) && !MainActivity.visible) {
            GadgetState.log("reopening after an unexpected exit")
            ErrorLog.scheduleRelaunch(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAIR -> startPairing()
            ACTION_STOP_PAIR -> stopPairing("Pairing cancelled")
            ACTION_RECONNECT -> runner.poke()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopPairing(null)
        stopChime()
        recorder.cancel()
        wakeWord.release()
        scope.cancel()
        commands.release()
        speaker.shutdown()
        instance = null
        super.onDestroy()
    }

    // -- Files dropped in with adb --------------------------------------------------

    /**
     * `adb push`ed files land in the app's external files dir, which adb can
     * write without root. Move them into private storage on start:
     * `sdk_token`, and `identity.json` + `pairing.json` from a Linux pairing.
     */
    private fun importDroppedFiles() {
        val drop = getExternalFilesDir(null) ?: return
        for (name in listOf(Store.SDK_TOKEN_FILE, Store.IDENTITY_FILE, Store.PAIRING_FILE)) {
            val file = java.io.File(drop, name)
            if (!file.isFile) continue
            runCatching {
                file.copyTo(java.io.File(filesDir, name), overwrite = true)
                file.delete()
                GadgetState.log("imported $name")
            }.onFailure { GadgetState.log("couldn't import $name: ${it.message}") }
        }
    }

    // -- Self-healing --------------------------------------------------------------------

    /**
     * Once a minute: apply quiet hours, restart the wake word if it died, and
     * kick the connection if it's been down a while (the runner's own backoff
     * can sit at a minute; a poke retries now).
     */
    private suspend fun watchdog() {
        while (true) {
            val prefs = Prefs(this)
            val quiet = QuietHours.isQuiet(prefs)
            if (quiet != GadgetState.quiet.value) {
                GadgetState.setQuiet(quiet)
                GadgetState.log(if (quiet) "quiet hours started" else "quiet hours ended")
                if (!quiet) briefPending = true
                applyWakeWord()
            } else if (prefs.wakeWordEnabled && !quiet && !wakeWord.listening && micAllowed() &&
                GadgetState.talk.value != Talk.LISTENING
            ) {
                errors.add("wake word had stopped; restarted")
                applyWakeWord()
            }
            if (GadgetState.link.value == Link.CONNECTED || store.loadPairing() == null) {
                disconnectedSince = 0L
            } else {
                val now = System.currentTimeMillis()
                if (disconnectedSince == 0L) disconnectedSince = now
                if (now - disconnectedSince > RECONNECT_KICK_MS) {
                    errors.add("disconnected for ${(now - disconnectedSince) / 60_000} min; reconnecting")
                    disconnectedSince = now
                    runner.poke()
                }
            }
            delay(60_000)
        }
    }

    /** Extra fields for device.health: how the app itself is doing. */
    fun healthExtras() = kotlinx.serialization.json.buildJsonObject {
        put("app_uptime_s", (System.currentTimeMillis() - startedAt) / 1000)
        put("connected", GadgetState.link.value == Link.CONNECTED)
        put("reply_feed_open", chat.feedOpen)
        put("wake_word", when {
            !Prefs(this@GadgetService).wakeWordEnabled -> "off"
            GadgetState.quiet.value -> "paused for quiet hours"
            wakeWord.listening -> "listening"
            else -> "not running"
        })
        put("quiet_hours", GadgetState.quiet.value)
        put("recent_errors", kotlinx.serialization.json.JsonArray(errors.read().takeLast(10).map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }

    fun isQuietNow() = QuietHours.isQuiet(Prefs(this))

    /** Load the chosen neural voice (downloading it the first time), or go back to the system voice. */
    suspend fun applyVoiceLevel(): String? {
        val choice = Prefs(this).voiceLevel
        val level = NeuralVoice.Level.entries.firstOrNull { it.name == choice }
        if (level == null) {
            neural.release()
            return null
        }
        return neural.use(level)
    }

    fun setVoiceLevel(choice: String) {
        Prefs(this).voiceLevel = choice
        scope.launch { applyVoiceLevel() }
    }

    /** Can the recogniser spot [phrase]? Synthesizes it silently and listens to the result. */
    suspend fun testWakePhrase(phrase: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        if (!neural.ready) return@withContext "Pick a neural voice (Voice quality) to run this test"
        val target = phrase.lowercase().split(Regex("[^a-z']+")).filter { it.isNotBlank() }.joinToString(" ")
        val pcm = neural.synthesize16k(target) ?: return@withContext "Couldn't synthesize the phrase"
        val heard = runCatching { wakeWord.recognise(target, pcm) }.getOrElse { return@withContext "Test failed: ${it.message}" }
        GadgetState.log("wake phrase test \"$target\": heard \"$heard\"")
        if (heard.contains(target)) "Recognised \"$target\" ✓" else
            "Heard \"${heard.ifEmpty { "nothing" }}\" instead of \"$target\": a word may not be in the speech model's vocabulary"
    }

    fun setWakeSensitivity(s: WakeWord.Sensitivity) {
        Prefs(this).wakeSensitivity = s
        applyWakeWord()
    }

    /** Silent speed check of the current neural voice: "1.8x real time" etc. */
    suspend fun voiceSpeedTest(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        if (!neural.ready) return@withContext "Pick a neural voice first"
        val x = neural.speedTest("Good morning! It's seventy five degrees and overcast, with a high of eighty five today.")
            ?: return@withContext "Speed test failed"
        val verdict = when {
            x >= 1.3 -> "fast: starts quickly, no gaps"
            x >= 0.9 -> "keeps up: a short wait before it starts"
            x >= 0.6 -> "a bit slow: longer wait, the odd pause"
            else -> "too slow for this Echo: long waits and gaps"
        }
        "%.1fx real time (%s)".format(x, verdict).also { GadgetState.log("voice speed: $it") }
    }

    /** Ask Muse for the morning brief, as a turn on the Echo. */
    fun morningBrief() {
        talk(BRIEF_PROMPT, null, label = "Morning brief")
    }

    private fun onStopWord() {
        // Only while speaking, and not when the Echo itself is saying "stop".
        if (!GadgetState.speaking.value) return
        if (Regex("\\bstop\\b", RegexOption.IGNORE_CASE).containsMatchIn(speaker.current)) return
        GadgetState.log("heard \"stop\"")
        speaker.stop()
        GadgetState.setFollowUp(false)
    }

    /** Mic switch on the screen: off means no wake word and no voice notes. */
    fun setMicOff(off: Boolean) {
        Prefs(this).micOff = off
        GadgetState.setMicOff(off)
        if (off && GadgetState.talk.value == Talk.LISTENING) {
            recorder.cancel()
            GadgetState.setFollowUp(false)
            GadgetState.setTalk(Talk.IDLE)
        }
        applyWakeWord()
    }

    private suspend fun refreshWeather() {
        while (true) {
            val prefs = Prefs(this)
            weather.fetch(prefs.weatherLat, prefs.weatherLon)?.let { GadgetState.setWeather(it) }
            delay(if (GadgetState.weather.value == null) 2 * 60_000L else 30 * 60_000L)
        }
    }

    /** Poll the Latitude's health once a minute; announce when it goes down. */
    private suspend fun watchServer() {
        var lastOverall: String? = null
        while (true) {
            val status = hub.status()
            GadgetState.setServer(status)
            if (status != null && lastOverall != null && status.overall != lastOverall) {
                GadgetState.log("server is ${status.overall}")
            }
            lastOverall = status?.overall
            delay(60_000)
        }
    }

    /** Look for a newer build on the echo-hub; returns a line for the setup panel. */
    suspend fun checkForUpdate(): String {
        val latest = hub.latest() ?: run {
            GadgetState.setUpdate(null)
            return "Couldn't reach the echo-hub at ${hub.host}"
        }
        return if (latest.versionCode > BuildConfig.VERSION_CODE) {
            GadgetState.setUpdate(latest)
            GadgetState.log("update available: ${latest.versionName}")
            "Version ${latest.versionName} is available: ${latest.notes}"
        } else {
            GadgetState.setUpdate(null)
            "Up to date (${BuildConfig.VERSION_NAME})"
        }
    }

    /** Download the waiting update and hand it to Android's installer. */
    fun installUpdate(onResult: (String) -> Unit) {
        val release = GadgetState.update.value ?: return onResult("No update waiting")
        scope.launch {
            val msg = try {
                val apk = hub.download(release)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { hub.install(apk) }
                "Installing ${release.versionName}… confirm on screen"
            } catch (e: Exception) {
                errors.add("update failed: ${e.message}")
                "Update failed: ${e.message}"
            }
            GadgetState.log(msg)
            onResult(msg)
        }
    }

    fun reloadWeather() {
        scope.launch {
            val prefs = Prefs(this@GadgetService)
            weather.fetch(prefs.weatherLat, prefs.weatherLon)?.let { GadgetState.setWeather(it) }
        }
    }

    /** Apply a quiet-hours settings change now rather than at the next watchdog tick. */
    fun refreshQuiet() {
        GadgetState.setQuiet(isQuietNow())
        applyWakeWord()
    }

    // -- Timers -----------------------------------------------------------------------

    private suspend fun tickTimers() {
        while (true) {
            val rang = timers.tick()
            if (rang.isNotEmpty()) {
                GadgetState.log("ringing: ${rang.joinToString { it.label }}")
                bringToFront(force = true)
                val quiet = QuietHours.isQuiet(Prefs(this))
                if (quiet && rang.none { it.isAlarm }) {
                    // Overnight, timers get a soft beep; alarms still ring properly.
                    softChime()
                } else {
                    startChime()
                    // Let the chime get going, then say what it's for.
                    delay(1_500)
                    if (!quiet) speaker.speak(rang.joinToString(". ") { if (it.isAlarm) it.label else "${it.label} is done" })
                }
                scope.launch {
                    delay(RING_MAX_MS)
                    if (timers.ringing.value.isNotEmpty()) timers.dismiss()
                }
            }
            if (timers.ringing.value.isEmpty()) stopChime()
            GadgetState.setRinging(timers.ringing.value.isNotEmpty())
            delay(1_000)
        }
    }

    private fun startChime() {
        if (chime?.isPlaying == true) return
        val uri = android.media.RingtoneManager.getActualDefaultRingtoneUri(this, android.media.RingtoneManager.TYPE_ALARM)
            ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
        chime = android.media.RingtoneManager.getRingtone(this, uri)?.apply {
            audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
            play()
        }
    }

    private fun stopChime() {
        chime?.stop()
        chime = null
    }

    fun dismissRinging() {
        timers.dismiss()
        stopChime()
        speaker.stop()
    }

    /** `laptop_token` / `laptop_host` pushed with adb, like `sdk_token`. */
    private fun importDroppedSettings() {
        val drop = getExternalFilesDir(null) ?: return
        java.io.File(drop, "laptop_token").takeIf { it.isFile }?.let {
            laptop.token = it.readText().trim()
            it.delete()
            GadgetState.log("imported laptop_token")
        }
        java.io.File(drop, "laptop_host").takeIf { it.isFile }?.let {
            laptop.host = it.readText().trim()
            it.delete()
        }
    }

    // -- Public actions (called by the activity) ----------------------------------

    fun identity() = identity

    fun saveSdkToken(token: String): String? = runCatching { store.saveSdkToken(token); null }
        .getOrElse { it.message ?: "invalid token" }

    fun hasSdkToken(): Boolean = runCatching { store.sdkToken() != null }.getOrDefault(false)

    fun isPaired(): Boolean = store.loadPairing() != null

    fun unpair() {
        store.deletePairing()
        runner.poke()
        GadgetState.log("pairing removed on this device")
    }

    fun reconnect() = runner.poke()

    /** Send a typed message ([text]) or a voice note ([wav]); progress shows in [GadgetState]. */
    private val offline by lazy {
        OfflineAssistant(timers, { GadgetState.weather.value }, { GadgetState.museName.value })
    }

    /** Answer on the Echo itself when Muse is unreachable (timers, alarms, time, weather). */
    private suspend fun offlineTurn(text: String?, wav: ByteArray?) {
        GadgetState.setReplySource("Offline")
        val heard = text?.takeIf { it.isNotBlank() } ?: wav?.let {
            GadgetState.setTalk(Talk.WAITING)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { wakeWord.transcribe(it.copyOfRange(44, it.size)) }.getOrDefault("")
            }
        }.orEmpty()
        GadgetState.setHeard(heard)
        val answer = offline.answer(heard)
        GadgetState.log("offline: answered locally")
        history.add(com.rusty.museecho.integrations.Exchange(System.currentTimeMillis(), heard, answer, "Offline"))
        deliver(ChatFeed.Reply("offline-${System.currentTimeMillis()}", ChatFeed.Origin.THIS_DEVICE, null, answer,
            emptyList(), true, null, null), source = "Offline", wakeScreen = false)
    }

    fun talk(text: String?, wav: ByteArray?, label: String? = null) {
        turnJob?.cancel()
        turnJob = scope.launch {
            // Muse unreachable: answer what the Echo can on its own (not for preset requests).
            if (label == null && (GadgetState.link.value != Link.CONNECTED || !chat.feedOpen)) {
                GadgetState.setTalk(Talk.SENDING)
                GadgetState.setReply("")
                GadgetState.setReplyImages(emptyList())
                offlineTurn(text, wav)
                return@launch
            }
            GadgetState.setTalk(Talk.SENDING)
            GadgetState.setReplySource(label)
            GadgetState.setHeard(if (label != null) "" else text ?: "")
            GadgetState.setReply("")
            GadgetState.setReplyImages(emptyList())
            try {
                var heardText = if (label != null) label else text.orEmpty()
                val reply = chat.turn(text, wav) { heard, partial ->
                    if (heard.isNotEmpty() && label == null) {
                        heardText = heard
                        GadgetState.setHeard(heard)
                    }
                    GadgetState.setTalk(if (partial == null || partial.text.isEmpty()) Talk.WAITING else Talk.REPLIED)
                    partial?.let { GadgetState.setReply(it.text) }
                }
                if (reply != null) {
                    history.add(com.rusty.museecho.integrations.Exchange(System.currentTimeMillis(), heardText, reply.text, label))
                    deliver(reply, source = label, wakeScreen = false)
                } else {
                    GadgetState.setTalk(Talk.REPLIED)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                errors.add("chat failed: ${e.message}")
                GadgetState.log("chat failed: ${e.message}")
                if (label == null && (text != null || wav != null)) {
                    // Lost Muse mid-turn: fall back to what the Echo can do itself.
                    offlineTurn(text, wav)
                } else {
                    GadgetState.setReply(e.message ?: "Something went wrong")
                    GadgetState.setTalk(Talk.ERROR)
                }
            }
        }
    }

    /**
     * Show a finished reply: caption, pictures, speech (unless quiet), then
     * an optional follow-up listen. [source] labels replies that didn't come
     * from an Echo turn.
     */
    private suspend fun deliver(reply: ChatFeed.Reply, source: String?, wakeScreen: Boolean) {
        val prefs = Prefs(this)
        val quiet = QuietHours.isQuiet(prefs)
        GadgetState.setReplySource(source)
        GadgetState.setReply(reply.text)
        GadgetState.setTalk(Talk.REPLIED)
        // A new reply counts as activity: it wakes a sleeping screen for the usual idle time.
        GadgetState.lastTouch = System.currentTimeMillis()
        GadgetState.setAsleep(false)
        if (wakeScreen && !quiet) bringToFront(force = false)
        scope.launch { GadgetState.setReplyImages(downloadImages(reply.images)) }
        if (!prefs.speakReplies || quiet || reply.text.isBlank()) return
        val spoken = Markdown.toSpeech(reply.text)
        if (spoken.isBlank()) return
        // Hold the mic for the follow-up rather than letting the wake word take it.
        if (speaker.speakAndWait(spoken) && prefs.followUp && GadgetState.talk.value == Talk.REPLIED &&
            !QuietHours.isQuiet(prefs) && GadgetState.link.value == Link.CONNECTED
        ) {
            GadgetState.setFollowUp(true)
            if (!startTalking(handsFree = true)) GadgetState.setFollowUp(false)
        }
    }

    private suspend fun downloadImages(urls: List<String>): List<android.graphics.Bitmap> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            urls.take(4).mapNotNull { url ->
                runCatching {
                    imageHttp.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { resp ->
                        val bytes = resp.body?.bytes()
                        if (!resp.isSuccessful || bytes == null || bytes.size > EchoCommands.MAX_IMAGE_BYTES) null
                        else EchoCommands.decodeScaled(bytes)
                    }
                }.getOrNull()
            }
        }

    /** Messages that didn't come from an Echo turn: replies sent to the Echo, gadget alerts, Muse's own. */
    private suspend fun onFeedUpdate(update: ChatFeed.Update) {
        val reply = (update as? ChatFeed.Update.Assistant)?.reply ?: return
        if (!reply.final) return
        val prefs = Prefs(this)
        val source = when (reply.origin) {
            // Replies to an Echo turn are handled by talk(); anything else tagged for the Echo shows here.
            ChatFeed.Origin.THIS_DEVICE -> if (turnJob?.isActive == true) return else GadgetState.museName.value
            ChatFeed.Origin.OTHER_GADGET -> if (!prefs.showGadgetAlerts) return else alertLabel(reply.prompt)
            ChatFeed.Origin.MUSE_INITIATED -> if (!prefs.showMuseInitiated) return else GadgetState.museName.value
            ChatFeed.Origin.OTHER -> return
        }
        // Don't talk over someone mid-sentence or mid-turn.
        if (GadgetState.talk.value == Talk.LISTENING || turnJob?.isActive == true) return
        GadgetState.log("message from ${reply.origin.name.lowercase().replace('_', ' ')}")
        GadgetState.setHeard("")
        history.add(com.rusty.museecho.integrations.Exchange(System.currentTimeMillis(), reply.prompt.orEmpty(), reply.text, source))
        if (!QuietHours.isQuiet(prefs)) softChime()
        deliver(reply, source, wakeScreen = true)
    }

    /** Real alerts (from the ntfy forwarder) say so; anything else from a gadget is just "From the server". */
    private fun alertLabel(prompt: String?): String {
        val p = prompt.orEmpty()
        val isAlert = p.startsWith("URGENT server alert") || p.startsWith("Server notice")
        if (!isAlert) return "From the server"
        // "URGENT server alert from my homelab (Title): body" -> "Alert: Title"
        val title = Regex("""\(([^)]*)\)""").find(p)?.groupValues?.get(1)
        return "Alert" + (title?.let { ": ${it.take(80)}" } ?: "")
    }

    private fun softChime() {
        val beep = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 50)
        beep.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 200)
        scope.launch {
            delay(500)
            beep.release()
        }
    }

    fun stopSpeaking() {
        speaker.stop()
        GadgetState.setFollowUp(false)
    }

    fun togglePlayback() = commands.togglePause()

    fun stopPlayback() = commands.stopPlayer()

    // -- Voice notes (push-to-talk and wake word) ---------------------------------------

    private fun micAllowed() = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Start a voice note. [handsFree] ends it on silence (wake word and
     * follow-ups); a follow-up keeps Muse's answer on screen while it listens.
     */
    fun startTalking(handsFree: Boolean = false): Boolean {
        val followUp = GadgetState.followUp.value
        if (GadgetState.micOff.value) {
            GadgetState.setFollowUp(false)
            return false
        }
        if (GadgetState.talk.value == Talk.LISTENING) return true
        if (!micAllowed()) {
            GadgetState.setReply("Microphone permission is needed to talk")
            GadgetState.setTalk(Talk.ERROR)
            return false
        }
        speaker.stop()
        wakeWord.pause()
        val ok = recorder.start(endOnSilence = handsFree, noiseFloor = wakeWord.noiseFloor) { scope.launch { stopTalking() } }
        if (!ok) {
            wakeWord.resume()
            GadgetState.setReply("Couldn't open the microphone")
            GadgetState.setTalk(Talk.ERROR)
            return false
        }
        if (!followUp) {
            GadgetState.setHeard("")
            GadgetState.setReply("")
            GadgetState.setReplyImages(emptyList())
            GadgetState.setReplySource(null)
        }
        GadgetState.setHandsFree(handsFree)
        GadgetState.setTalk(Talk.LISTENING)
        return true
    }

    fun stopTalking() {
        if (GadgetState.talk.value != Talk.LISTENING) return
        val wav = recorder.stop()
        recorder.summary?.let { GadgetState.log(it); recorder.summary = null }
        val wasFollowUp = GadgetState.followUp.value
        GadgetState.setFollowUp(false)
        wakeWord.resume()
        if (wav == null) {
            // Nobody answered the follow-up: leave Muse's reply up.
            GadgetState.setTalk(if (wasFollowUp) Talk.REPLIED else Talk.IDLE)
            return
        }
        talk(null, wav)
    }

    fun micLevel(): Float = recorder.level

    private fun onWake() {
        GadgetState.log("wake word heard")
        bringToFront(force = true)
        if (briefPending && Prefs(this).morningBrief && GadgetState.link.value == Link.CONNECTED) {
            briefPending = false
            wakeWord.resume()
            morningBrief()
            return
        }
        val beep = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 60)
        beep.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 150)
        scope.launch {
            delay(400)
            beep.release()
        }
        if (!startTalking(handsFree = true)) wakeWord.resume()
    }

    /** (Re)apply the wake-word setting; returns an error message if it can't run. */
    fun applyWakeWord(): String? {
        val prefs = Prefs(this)
        if (!prefs.wakeWordEnabled || QuietHours.isQuiet(prefs) || prefs.micOff) {
            wakeWord.stop()
            return null
        }
        if (!micAllowed()) return "Microphone permission is needed for the wake word"
        val error = wakeWord.start(prefs.wakePhrase, prefs.wakeSensitivity)
        if (error != null) GadgetState.log("wake word: $error")
        return error
    }

    fun wakeModelPresent() = wakeWord.modelPresent

    // -- Pairing --------------------------------------------------------------------

    private fun startPairing() {
        if (pairingJob?.isActive == true) return
        val sdkToken = runCatching { store.sdkToken() }.getOrNull()
        if (sdkToken == null) {
            GadgetState.setBle(BlePairing.FAILED, "Add your SDK token first (gadgets.muse.ai → SDK tokens)")
            return
        }
        val pairing = PairingSession(
            nodeId = identity.nodeId,
            deviceId = identity.deviceId,
            mac = identity.mac,
            firmwareVersion = BuildConfig.VERSION_NAME,
            sdkToken = sdkToken,
        )
        lateinit var controller: SetupController
        val server = BleSetupServer(
            context = this,
            localName = identity.bleName,
            onWrite = { controller.onWrite(it) },
            onConnected = { GadgetState.setBle(BlePairing.CONNECTED, "Phone connected - follow the Muse app") },
            onDisconnect = {
                controller.onDisconnect()
                if (GadgetState.ble.value != BlePairing.DONE) {
                    GadgetState.setBle(BlePairing.ADVERTISING, "Waiting for the Muse app")
                }
            },
            log = { GadgetState.log("ble: $it") },
        )
        controller = SetupController(
            pairing = pairing,
            identity = identity,
            version = BuildConfig.VERSION_NAME,
            transport = server,
            network = AndroidNetwork(this),
            provision = ::verifyAndSave,
            onComplete = {
                GadgetState.setBle(BlePairing.DONE, "Paired!")
                scope.launch {
                    delay(2_000) // let the final status notification reach the phone
                    stopPairing(null)
                    runner.poke()
                }
            },
            log = { GadgetState.log("setup: $it") },
        )
        server.failure = { why ->
            GadgetState.setBle(BlePairing.UNSUPPORTED, "Bluetooth: $why")
            scope.launch { stopPairing(null) }
        }
        when (val result = server.start()) {
            is BleSetupServer.StartResult.Unsupported -> {
                controller.stop()
                GadgetState.setBle(BlePairing.UNSUPPORTED, result.why)
                GadgetState.log("pairing unavailable: ${result.why}")
                return
            }
            BleSetupServer.StartResult.Ok -> Unit
        }
        ble = server
        setup = controller
        GadgetState.setBle(BlePairing.ADVERTISING, "Open the Muse app → Settings → Devices → Add device, and pick ${identity.bleName}")
        pairingJob = scope.launch {
            delay(PAIRING_WINDOW_MS)
            if (GadgetState.ble.value != BlePairing.DONE) stopPairing("Pairing window closed after 10 minutes")
        }
    }

    private fun stopPairing(message: String?) {
        pairingJob?.cancel()
        pairingJob = null
        ble?.stop()
        ble = null
        setup?.stop()
        setup = null
        if (message != null) GadgetState.setBle(BlePairing.OFF, message)
        else if (GadgetState.ble.value != BlePairing.DONE && GadgetState.ble.value != BlePairing.UNSUPPORTED) {
            GadgetState.setBle(BlePairing.OFF)
        }
    }

    /** Check the device token the phone handed over, then save it (port of the CLI's `_verify_and_save`). */
    private fun verifyAndSave(credentials: SetupController.Credentials, commit: (() -> Boolean) -> Boolean) {
        val apiUrl = credentials.apiUrl.takeIf { it.startsWith("https://") } ?: ""
        val apiUrlV2 = credentials.apiUrlV2.takeIf { it.startsWith("https://") } ?: ""
        val (vms, status) = api.fetchVms(credentials.accessToken, MuseApi.apiRoot(apiUrlV2))
        if (vms.isEmpty()) {
            GadgetState.log("device token check failed (HTTP $status, 0 VMs)")
            throw SetupController.ProvisionFailed("auth_failed")
        }
        val record = PairingRecord(
            access_token = credentials.accessToken,
            refresh_token = credentials.refreshToken,
            username = credentials.username,
            api_url = apiUrl,
            api_url_v2 = apiUrlV2,
            noise_host = credentials.noiseHost,
            access_token_saved_at = System.currentTimeMillis() / 1000,
        )
        if (!commit { store.savePairing(record); true }) throw SetupController.ProvisionFailed("error_storage")
    }

    // -- Screen ---------------------------------------------------------------------

    private fun bringToFront() = bringToFront(force = false)

    private fun bringToFront(force: Boolean) {
        if ((!force && !Prefs(this).comeToFront) || MainActivity.visible) return
        @Suppress("DEPRECATION")
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "museecho:show")
            .acquire(5_000)
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(MainActivity.EXTRA_AUTO, true))
    }

    // -- Notification ---------------------------------------------------------------

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Muse connection", NotificationManager.IMPORTANCE_LOW))
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // The microphone type keeps wake-word listening and voice notes
            // allowed while another app is in front.
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && micAllowed()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val text = when (GadgetState.link.value) {
            Link.CONNECTED -> "Connected to ${GadgetState.linkDetail.value}"
            Link.NOT_PAIRED -> "Not paired yet"
            Link.NO_TOKEN -> "Needs an SDK token"
            Link.CONNECTING -> "Connecting…"
            Link.RECONNECTING -> GadgetState.linkDetail.value.ifEmpty { "Reconnecting…" }
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_muse)
            .setContentTitle("Muse Echo")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_PAIR = "com.rusty.museecho.PAIR"
        const val ACTION_STOP_PAIR = "com.rusty.museecho.STOP_PAIR"
        const val ACTION_RECONNECT = "com.rusty.museecho.RECONNECT"
        private const val CHANNEL = "muse"
        private const val NOTIFICATION_ID = 1
        const val PAIRING_WINDOW_MS = 10 * 60_000L
        const val RING_MAX_MS = 2 * 60_000L
        const val RECONNECT_KICK_MS = 5 * 60_000L
        const val BRIEF_PROMPT = "Good morning! Give me my morning brief, under 80 words and written to be spoken aloud " +
            "(no lists or markdown): today's weather here, what's on my calendar today, anything I asked you to remind " +
            "me about, and whether anything is wrong on the Latitude server. Also call agenda.set on the Echo Show with " +
            "today's events so its agenda page is current."

        @Volatile
        var instance: GadgetService? = null
            private set

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, GadgetService::class.java).setAction(action)
            context.startForegroundService(intent)
        }
    }
}

/** What the setup protocol needs to know about the network (port of `network.py`). */
class AndroidNetwork(private val context: Context) : SetupController.Network {
    override fun isOnline(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("api.muse.ai", 443), 5_000) }
        true
    } catch (e: Exception) {
        false
    }

    /**
     * The single scan entry offered when the device is already online. It is
     * marked open so the apps skip the password field; whatever credentials
     * come back are ignored.
     */
    override fun currentConnectionEntry(): JsonObject {
        @Suppress("DEPRECATION")
        val ssid = runCatching {
            context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
                .connectionInfo?.ssid?.removeSurrounding("\"")
        }.getOrNull()?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
        return buildJsonObject {
            put("ssid", ssid ?: "Use current connection")
            put("rssi", -40)
            put("secure", false)
        }
    }
}

/** Small user preferences. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("museecho", Context.MODE_PRIVATE)

    var speakReplies: Boolean
        get() = prefs.getBoolean("speak_replies", true)
        set(v) = prefs.edit().putBoolean("speak_replies", v).apply()

    var comeToFront: Boolean
        get() = prefs.getBoolean("come_to_front", true)
        set(v) = prefs.edit().putBoolean("come_to_front", v).apply()

    var displayName: String
        get() = prefs.getString("display_name", null) ?: "Echo Show"
        set(v) = prefs.edit().putString("display_name", v).apply()

    var museName: String
        get() = prefs.getString("muse_name", null) ?: "Muse"
        set(v) = prefs.edit().putString("muse_name", v).apply()

    /** Hands-free wake word (see WakeWord). */
    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean("wake_word", false)
        set(v) = prefs.edit().putBoolean("wake_word", v).apply()

    /** After Muse answers, listen briefly for a reply without the wake word. */
    var followUp: Boolean
        get() = prefs.getBoolean("follow_up", true)
        set(v) = prefs.edit().putBoolean("follow_up", v).apply()

    /** Show messages Muse sends on its own (reminders, scheduled tasks). */
    var showMuseInitiated: Boolean
        get() = prefs.getBoolean("show_muse_initiated", true)
        set(v) = prefs.edit().putBoolean("show_muse_initiated", v).apply()

    /** Announce messages from other gadgets (e.g. the Latitude's alerts) and Muse's answers. */
    var showGadgetAlerts: Boolean
        get() = prefs.getBoolean("show_gadget_alerts", true)
        set(v) = prefs.edit().putBoolean("show_gadget_alerts", v).apply()

    /** Photo-frame slideshow after this many idle minutes (0 = off). */
    var frameDelayMin: Int
        get() = prefs.getInt("frame_delay_min", 5)
        set(v) = prefs.edit().putInt("frame_delay_min", v).apply()

    /** Quiet hours: turn the screen off after this many idle minutes (0 = just dim). */
    var nightOffMin: Int
        get() = prefs.getInt("night_off_min", 5)
        set(v) = prefs.edit().putInt("night_off_min", v).apply()

    /** Show the server status pill by the connection chip. */
    var showServerPill: Boolean
        get() = prefs.getBoolean("show_server_pill", true)
        set(v) = prefs.edit().putBoolean("show_server_pill", v).apply()

    var micOff: Boolean
        get() = prefs.getBoolean("mic_off", false)
        set(v) = prefs.edit().putBoolean("mic_off", v).apply()

    /** The first "hey muse" after quiet hours gets the morning brief. */
    var morningBrief: Boolean
        get() = prefs.getBoolean("morning_brief", true)
        set(v) = prefs.edit().putBoolean("morning_brief", v).apply()

    /** Weather location; defaults to Jacksonville, FL (Sofa's setting). */
    var weatherLat: Double
        get() = prefs.getFloat("weather_lat", 30.3322f).toDouble()
        set(v) = prefs.edit().putFloat("weather_lat", v.toFloat()).apply()

    var weatherLon: Double
        get() = prefs.getFloat("weather_lon", -81.6557f).toDouble()
        set(v) = prefs.edit().putFloat("weather_lon", v.toFloat()).apply()

    var quietEnabled: Boolean
        get() = prefs.getBoolean("quiet_enabled", true)
        set(v) = prefs.edit().putBoolean("quiet_enabled", v).apply()

    var quietStart: String
        get() = prefs.getString("quiet_start", null) ?: "22:00"
        set(v) = prefs.edit().putString("quiet_start", v).apply()

    var quietEnd: String
        get() = prefs.getString("quiet_end", null) ?: "07:00"
        set(v) = prefs.edit().putString("quiet_end", v).apply()

    var wakeSensitivity: WakeWord.Sensitivity
        get() = runCatching { WakeWord.Sensitivity.valueOf(prefs.getString("wake_sensitivity", null) ?: "HIGH") }
            .getOrDefault(WakeWord.Sensitivity.HIGH)
        set(v) = prefs.edit().putString("wake_sensitivity", v.name).apply()

    /** "SYSTEM" for the installed TTS engine, or a [NeuralVoice.Level] name. */
    var voiceLevel: String
        get() = prefs.getString("voice_level", null) ?: "SYSTEM"
        set(v) = prefs.edit().putString("voice_level", v).apply()

    var wakePhrase: String
        get() = prefs.getString("wake_phrase", null) ?: "hey muse"
        set(v) = prefs.edit().putString("wake_phrase", v.trim().lowercase()).apply()
}
