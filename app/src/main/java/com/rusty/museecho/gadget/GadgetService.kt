package com.rusty.museecho.gadget

import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private lateinit var specialDays: com.rusty.museecho.integrations.SpecialDays
    private lateinit var reminders: com.rusty.museecho.integrations.Reminders
    private lateinit var assignments: com.rusty.museecho.integrations.Assignments
    private val weatherAlerts = com.rusty.museecho.integrations.WeatherAlerts()
    lateinit var quickActions: com.rusty.museecho.integrations.QuickActions
        private set
    lateinit var quickSets: com.rusty.museecho.integrations.QuickActionTimes
        private set
    lateinit var routines: com.rusty.museecho.integrations.Routines
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
    private lateinit var diary: Diary
    lateinit var life: BlobLife
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
        GadgetState.outputLevel = { neural.outputLevel() }
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
        GadgetState.setBlobEnergy(Prefs(this).blobEnergy)
        GadgetState.setClockFace(Prefs(this).clockFace)
        GadgetState.setFrameClock(Prefs(this).frameClock)
        GadgetState.setNightScene(Prefs(this).nightScene)
        GadgetState.setTheme(Prefs(this).theme)
        GadgetState.setBackground(Prefs(this).background)
        GadgetState.setDismissedAlerts(Prefs(this).dismissedAlerts)
        hub = com.rusty.museecho.integrations.Hub(this)
        agenda = com.rusty.museecho.integrations.Agenda(java.io.File(filesDir, "agenda.json"))
        GadgetState.agendaToday = {
            agenda.value.value.let { d -> if (BlobBrain.isToday(d.date, java.time.LocalDate.now())) d.items else emptyList() }
        }
        specialDays = com.rusty.museecho.integrations.SpecialDays(java.io.File(filesDir, "countdowns.json"))
        reminders = com.rusty.museecho.integrations.Reminders(java.io.File(filesDir, "reminders.json"))
        assignments = com.rusty.museecho.integrations.Assignments(java.io.File(filesDir, "assignments.json"))
        frame = com.rusty.museecho.integrations.PhotoFrame(java.io.File(getExternalFilesDir(null), "frame"))
        quickActions = com.rusty.museecho.integrations.QuickActions(java.io.File(filesDir, "quick_actions.json"))
        quickSets = com.rusty.museecho.integrations.QuickActionTimes(java.io.File(filesDir, "quick_action_sets.json"))
        routines = com.rusty.museecho.integrations.Routines(java.io.File(filesDir, "routines.json"))
        DayLog.init(java.io.File(filesDir, "daylog.json"))
        diary = Diary(java.io.File(filesDir, "diary.json"))
        GadgetState.setDiary(diary.load())
        GadgetState.setDiaryOn(Prefs(this).diary)
        GadgetState.setPages(Prefs(this).pageOrder, Prefs(this).pagesHidden)
        GadgetState.setBlobName(Prefs(this).blobName)
        GadgetState.setStillUntil(Prefs(this).stillUntil)
        GadgetState.setRoomTheme(getSharedPreferences("museecho", MODE_PRIVATE).getString("room_theme", "") ?: "")
        Prefs(this).dailyThingJson?.let { j ->
            runCatching { kotlinx.serialization.json.Json.decodeFromString(DailyThing.serializer(), j) }.getOrNull()
                ?.takeIf { Prefs(this).dailyThing }?.let(GadgetState::setDailyThing)
        }
        Prefs(this).desktopColorsJson?.let { j ->
            runCatching {
                (kotlinx.serialization.json.Json.parseToJsonElement(j) as kotlinx.serialization.json.JsonObject)
                    .mapValues { (it.value as kotlinx.serialization.json.JsonPrimitive).content }
            }.getOrNull()?.let(GadgetState::setDesktopColors)
        }
        GadgetState.setDesktopWallpaper(java.io.File(filesDir, DESKTOP_WALLPAPER))
        val home = HomeCommands(this, roku, timers, laptop, ::bringToFront, notes, glance, frame, quickActions,
            showFrame = {
                GadgetState.showFrame(true)
                bringToFront(force = true)
            },
            agenda = agenda, specialDays = specialDays, reminders = reminders, assignments = assignments)
        commands = EchoCommands(this, speaker, ::bringToFront, home, ::isQuietNow, { healthExtras() })
        runner = GadgetRunner(
            store = store,
            identity = identity,
            api = api,
            connector = OkHttpWsConnector(ua),
            displayName = Prefs(this).displayName,
            version = BuildConfig.VERSION_NAME,
            commands = commands.specs,
            runCommand = ::runWatched,
            onRegistered = { chat.runChat { runner.chatSession() } },
        )
        chat = ChatClient(identity.nodeId)
        scope.launch { chat.updates.collect { runCatching { onFeedUpdate(it) } } }
        startInForeground()
        runnerJob = scope.launch { runner.run() }
        scope.launch { tickTimers() }
        scope.launch { watchTv() }
        GadgetState.setStyle(BlobStyles.load(this))
        scope.launch { watchdog() }
        scope.launch { refreshWeather() }
        scope.launch { watchServer() }
        scope.launch { watchMuseElsewhere() }
        scope.launch { watchLaptop() }
        scope.launch { pickQuickActions() }
        scope.launch { runRoutines() }
        scope.launch { wellnessNudges() }
        scope.launch { watchPresence() }
        scope.launch { writeDiary() }
        scope.launch { fetchDailyThing() }
        scope.launch { fillAgenda() }
        scope.launch { watchDesktop() }
        life = BlobLife(this, scope, hub, ::isQuietNow, ::quietAsk, { diary.load().size }, { frame.photos().size }).also { it.start() }
        scope.launch { postJournal() }
        chores = Chores(this, scope, hub, timers, frame, agenda, laptop, ::applySettings,
            say = { text -> if (Prefs(this).speakReplies) speaker.speak(text) },
            chime = ::softChime,
            toFront = { bringToFront(force = true) },
            isQuiet = ::isQuietNow,
        ).also {
            it.quickActionsNow = { quickActions.value.value }
            it.quickSetsNow = { quickSets.value.value }
            it.routinesNow = { routines.value.value.items }
            it.askNow = ::askNow
            it.start()
        }
        // The blob's little sounds: its reactions, and taps from the screen.
        scope.launch { GadgetState.reaction.collect { r -> r?.let { BlobSounds.forReaction(it.reaction)?.let(::playBlobSound) } } }
        scope.launch { GadgetState.sounds.collect(::playBlobSound) }
        scope.launch { specialDays.value.collect { GadgetState.setSpecialDays(it.days) } }
        scope.launch { reminders.value.collect { GadgetState.setReminders(it) } }
        scope.launch { assignments.value.collect { GadgetState.setAssignments(it.items) } }
        GadgetState.setGuestUntil(Prefs(this).guestUntil)
        GadgetState.setLooks(Prefs(this))
        GadgetState.setGuestWifi(Prefs(this).guestWifiSsid.isNotBlank())
        GadgetState.setFrameMarks(Prefs(this).frameFavs, Prefs(this).frameHidden)
        scope.launch { watchAlerts() }
        scope.launch { watchReminders() }
        scope.launch { watchFrame() }
        watchVolume()
        watchLight()
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
            onLoud = ::onLoudNoise,
            noisy = { GadgetState.tvPlaying.value || GadgetState.nowPlaying.value != null || GadgetState.laptopStatus.value?.playing == true },
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
        volumeReceiver?.let { runCatching { unregisterReceiver(it) } }
        lightListener?.let { getSystemService(android.hardware.SensorManager::class.java)?.unregisterListener(it) }
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
            // Launched early in onCreate: wait until the rest of the service is set up.
            if (!::wakeWord.isInitialized || !::runner.isInitialized) {
                delay(500)
                continue
            }
            val prefs = Prefs(this)
            if (litSince > 0 && System.currentTimeMillis() - litSince >= 120_000) morningStartsEarly()
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
    /** Hold still for [minutes] (-1: until turned off; 0: off). */
    fun setStill(minutes: Int) {
        val until = when {
            minutes < 0 -> Long.MAX_VALUE
            minutes == 0 -> 0L
            else -> System.currentTimeMillis() + minutes * 60_000L
        }
        Prefs(this).stillUntil = until
        GadgetState.setStillUntil(until)
        GadgetState.log(if (until == 0L) "moving again" else "holding still" + if (minutes > 0) " for $minutes min" else "")
    }

    fun healthReport() = runCatching { commands.healthReport() }.getOrNull()

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
        val (heard, conf) = runCatching { wakeWord.recogniseWithConfidence(target, pcm) }.getOrElse { return@withContext "Test failed: ${it.message}" }
        val need = Prefs(this@GadgetService).wakeSensitivity.minConf
        GadgetState.log("wake phrase test \"$target\": heard \"$heard\" (%.0f%% sure, needs %.0f%%)".format(100 * (conf ?: 0.0), 100 * need))
        if (heard.contains(target) && (conf ?: 0.0) >= need) "Recognised \"$target\" ✓ (%.0f%% sure)".format(100 * (conf ?: 0.0))
        else if (heard.contains(target)) "Heard it, but only %.0f%% sure (needs %.0f%%): try Balanced or Eager, or say it clearly".format(100 * (conf ?: 0.0), 100 * need)
        else
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

    /** Make Rusty's phone ring (through echo-hub to the Puff app). What happened, in a few words. */
    suspend fun findPhone(): String {
        val listening = hub.findPhone()
        val said = when (listening) {
            null -> "I can't reach the Latitude to ring your phone 😕"
            true -> "Ringing your phone! 📱"
            false -> "Asked your phone to ring, but the Puff app hasn't checked in lately 🤔"
        }
        GadgetState.log("find my phone: ${listening ?: "hub unreachable"}")
        GadgetState.react(if (listening == true) Reaction.HOP else Reaction.SHAKE)
        GadgetState.say(said, 20)
        return said
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
                DayLog.timerRang()
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
        // Up and at 'em: a little party for getting up to an alarm.
        if (timers.ringing.value.any { it.isAlarm }) GadgetState.react(Reaction.CELEBRATE)
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
        // Background asks (diary, thing of the day) give way to anything real.
        quietJob?.cancel()
        if (label == null) {
            DayLog.talked()
            GadgetState.lastHuman = System.currentTimeMillis()
        }
        turnJob = scope.launch {
            // Muse unreachable: answer what the Echo can on its own (not for preset requests).
            // Also straight away when the internet itself is down (rather than waiting to time out).
            if (label == null && (GadgetState.link.value != Link.CONNECTED || !chat.feedOpen || GadgetState.internet.value == false)) {
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
            GadgetState.setTurnActive(true)
            val turnStart = System.currentTimeMillis()
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
                    if (label == null && changesCalendar(heardText)) refreshAgendaAfter(turnStart)
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
            } finally {
                GadgetState.setTurnActive(false)
            }
        }
    }

    /** "Put the dentist on Tuesday at 3", "move my meeting to Friday", "cancel practice on my calendar"... */
    private fun changesCalendar(heard: String): Boolean {
        val l = heard.lowercase()
        val verb = Regex("\\b(add|put|schedule|book|move|cancel|delete|remove|change|reschedule)\\b").containsMatchIn(l)
        val day = Regex("\\b(calendar|today|tonight|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday|next week|" +
            "january|february|march|april|may|june|july|august|september|october|november|december)\\b").containsMatchIn(l)
        return verb && day && ("calendar" in l || Regex("\\b(add|put|schedule|book)\\b.*\\b(on|at|for)\\b").containsMatchIn(l))
    }

    /** After a calendar change by voice, the agenda page catches up (unless Pixel already updated it). */
    private fun refreshAgendaAfter(since: Long) = scope.launch {
        delay(25_000)
        if (agenda.value.value.updatedMs >= since) return@launch
        GadgetState.log("calendar changed by voice; refreshing the agenda")
        quietAskImpl(AGENDA_PROMPT, waitForCalm = false)
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
        // The blob takes its face from the reply's tone, unless Pixel already chose one.
        if ((GadgetState.mood.value?.untilMs ?: 0L) < System.currentTimeMillis()) {
            BlobBrain.tone(reply.text)?.let { tone ->
                GadgetState.setMood(tone.mood, 30)
                if (tone.hop) GadgetState.react(Reaction.HOP)
            }
        }
        // A plain yes or no gets a nod or a head shake.
        BlobBrain.yesNo(reply.text)?.let { yes -> GadgetState.react(if (yes) Reaction.NOD else Reaction.SHAKE) }
        // A new reply counts as activity: it wakes a sleeping screen for the usual idle time.
        GadgetState.lastTouch = System.currentTimeMillis()
        GadgetState.setAsleep(false)
        if (wakeScreen && !quiet) bringToFront(force = false)
        scope.launch { GadgetState.setReplyImages(downloadImages(reply.images)) }
        if (!prefs.speakReplies || quiet || reply.text.isBlank()) return
        val spoken = Markdown.toSpeech(reply.text)
        if (spoken.isBlank()) return
        if (source == "Morning brief") presentBrief(spoken)
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
            ChatFeed.Origin.THIS_DEVICE -> if (turnJob?.isActive == true || quietAsking) return else GadgetState.museName.value
            ChatFeed.Origin.OTHER_GADGET -> if (!prefs.showGadgetAlerts) return else alertLabel(reply.prompt)
            // Guest mode: Muse's own messages (often personal reminders) wait in the history instead.
            ChatFeed.Origin.MUSE_INITIATED -> if (!prefs.showMuseInitiated) return else if (GadgetState.guestMode()) {
                history.add(com.rusty.museecho.integrations.Exchange(System.currentTimeMillis(), reply.prompt.orEmpty(), reply.text, GadgetState.museName.value))
                return
            } else GadgetState.museName.value
            ChatFeed.Origin.OTHER -> return
        }
        // Don't talk over someone mid-sentence or mid-turn.
        if (GadgetState.talk.value == Talk.LISTENING || turnJob?.isActive == true) return
        GadgetState.log("message from ${reply.origin.name.lowercase().replace('_', ' ')}")
        GadgetState.setHeard("")
        history.add(com.rusty.museecho.integrations.Exchange(System.currentTimeMillis(), reply.prompt.orEmpty(), reply.text, source))
        if (!QuietHours.isQuiet(prefs)) softChime()
        if (reply.origin != ChatFeed.Origin.THIS_DEVICE) {
            GadgetState.react(Reaction.HOP)
            GadgetState.setAlertGlow(true)
        }
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

    internal fun softChime() {
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

    // -- The blob's view of the room ------------------------------------------------------

    @Volatile
    private var lastStartle = 0L

    /** A bang the wake-word listener heard: the blob jumps, unless the Echo itself is making noise. */
    private fun onLoudNoise() {
        val now = System.currentTimeMillis()
        if (now - lastStartle < 20_000) return
        if (GadgetState.speaking.value || GadgetState.talk.value != Talk.IDLE || GadgetState.ringing.value ||
            GadgetState.nowPlaying.value != null || GadgetState.tvPlaying.value) return
        lastStartle = now
        GadgetState.react(Reaction.STARTLE)
    }

    /** Commands on the laptop and TV make the blob look over, then nod (worked) or shake its head (didn't). */
    private suspend fun runWatched(command: String, params: kotlinx.serialization.json.JsonObject, timeoutMs: Long?): kotlinx.serialization.json.JsonObject {
        val watched = command.startsWith("laptop.") || command.startsWith("tv.")
        if (!watched) return commands.run(command, params, timeoutMs)
        GadgetState.setWorkingOn(command)
        try {
            val result = commands.run(command, params, timeoutMs)
            val ok = (result["ok"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
            GadgetState.react(if (ok) Reaction.NOD else Reaction.SHAKE)
            return result
        } finally {
            GadgetState.setWorkingOn(null)
        }
    }

    /**
     * While the morning brief is spoken, the blob holds up a prop for each part
     * (weather, calendar, reminders, server). Speech progress isn't reported, so
     * it estimates where the voice is from the time since it started.
     */
    private fun presentBrief(spoken: String) = scope.launch {
        val t = spoken.lowercase()
        val wet = listOf("rain", "storm", "shower", "drizzle", "flood").any { it in t }
        val parts = listOf(
            listOf("weather", "degrees", "°", "rain", "storm", "sunny", "cloud", "forecast") to (if (wet) HeldItem.UMBRELLA else HeldItem.SUN),
            listOf("calendar", "today you", "on your schedule", "event", "appointment", "meeting") to HeldItem.CALENDAR,
            listOf("remind", "don't forget", "reminder") to HeldItem.NOTE,
            listOf("latitude", "server") to HeldItem.SERVER,
        ).mapNotNull { (words, item) -> words.mapNotNull { w -> t.indexOf(w).takeIf { it >= 0 } }.minOrNull()?.let { it to item } }
            .sortedBy { it.first }
        if (parts.isEmpty()) return@launch
        // Wait for the voice to start.
        val waitUntil = System.currentTimeMillis() + 20_000
        while (!GadgetState.speaking.value && System.currentTimeMillis() < waitUntil) delay(100)
        if (!GadgetState.speaking.value) return@launch
        val start = System.currentTimeMillis()
        try {
            while (GadgetState.speaking.value) {
                val charsSpoken = (System.currentTimeMillis() - start) / 1000f * BRIEF_CHARS_PER_SECOND
                GadgetState.setPresenting(parts.lastOrNull { it.first <= charsSpoken + 10 }?.second ?: parts.first().second)
                delay(200)
            }
        } finally {
            GadgetState.setPresenting(null)
        }
    }

    /** National Weather Service alerts: shown while active, and new ones announced once (overnight only the dangerous ones). */
    private suspend fun watchAlerts() {
        val prefs0 = Prefs(this)
        val announced = prefs0.announcedAlerts.toMutableSet()
        while (true) {
            val prefs = Prefs(this)
            val list = weatherAlerts.fetch(prefs.weatherLat, prefs.weatherLon)
            if (list != null) {
                val now = System.currentTimeMillis()
                val active = list.filter { it.endsMs == 0L || it.endsMs > now }
                GadgetState.setAlerts(active)
                // Forget closed banners once their alerts are over.
                val stillClosed = GadgetState.dismissedAlerts.value.intersect(active.map { it.id }.toSet())
                if (stillClosed != GadgetState.dismissedAlerts.value) {
                    GadgetState.setDismissedAlerts(stillClosed)
                    prefs.dismissedAlerts = stillClosed
                }
                val fresh = active.filter { it.id !in announced }
                if (fresh.isNotEmpty()) {
                    announced += fresh.map { it.id }
                    // Keep the remembered set small: only alerts still active.
                    announced.retainAll(active.map { it.id }.toSet())
                    prefs.announcedAlerts = announced
                    val quiet = QuietHours.isQuiet(prefs)
                    val worth = fresh.filter { !quiet || it.severity.equals("Extreme", true) }
                    if (worth.isNotEmpty()) {
                        GadgetState.log("weather alert: ${worth.joinToString { it.event }}")
                        GadgetState.react(Reaction.STARTLE)
                        if (prefs.speakReplies && GadgetState.talk.value == Talk.IDLE && !GadgetState.speaking.value) {
                            speaker.speak("Weather alert. " + worth.joinToString(". ") { alertSpoken(it) })
                        }
                    }
                }
            }
            delay(if (GadgetState.alerts.value.isEmpty()) 15 * 60_000L else 5 * 60_000L)
        }
    }

    private fun alertSpoken(a: com.rusty.museecho.integrations.WeatherAlert): String {
        val until = if (a.endsMs > 0) " until " + java.text.SimpleDateFormat("h a", java.util.Locale.US).format(java.util.Date(a.endsMs))
            .replace("AM", "a.m.").replace("PM", "p.m.") else ""
        return "${a.event}$until"
    }

    /** Today's reminders: the blob taps its note and shows each one as its time comes. */
    private suspend fun watchReminders() {
        val shown = mutableSetOf<String>()
        while (true) {
            delay(30_000)
            val day = reminders.value.value
            val today = java.time.LocalDate.now().toString()
            if (day.date != today) {
                if (day.items.isNotEmpty() && day.date.isNotEmpty()) reminders.clear()
                continue
            }
            val cal = java.util.Calendar.getInstance()
            val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            day.items.forEach { r ->
                val at = BlobBrain.minutesOfDay(r.time) ?: return@forEach
                val key = "$today ${r.time} ${r.text}"
                if (nowMin in at..at + 5 && shown.add(key)) {
                    GadgetState.log("reminder due")
                    GadgetState.react(Reaction.NUDGE)
                    GadgetState.say("Reminder: ${r.text}", 300)
                    if (!QuietHours.isQuiet(Prefs(this))) softChime()
                    bringToFront(force = false)
                }
            }
            // Countdowns and schoolwork: forget what has passed.
            specialDays.prune(java.time.LocalDate.now())
            assignments.prune(java.time.LocalDate.now())
        }
    }

    lateinit var chores: Chores
        private set
    private val blobSounds = BlobSounds()

    /** Very quiet, and never while it could get in the way: quiet hours, talking, listening, music, ringing. */
    private fun playBlobSound(sound: BlobSound) {
        val ui = sound.name.startsWith("UI_")
        if (!(if (ui) Prefs(this).uiSounds else Prefs(this).blobSounds) || isQuietNow() || GadgetState.speaking.value || GadgetState.ringing.value ||
            GadgetState.talk.value == Talk.LISTENING || GadgetState.nowPlaying.value != null
        ) return
        blobSounds.play(sound)
    }

    /** Restores the newest Latitude backup, then restarts the app so everything reloads it. */
    fun restoreFromBackup(onResult: (String) -> Unit) {
        scope.launch {
            val problem = chores.restore()
            if (problem != null) {
                onResult(problem)
                return@launch
            }
            onResult("Restored. Restarting…")
            delay(1_500)
            getSharedPreferences("museecho", MODE_PRIVATE).edit().putLong("last_crash_relaunch", 0).commit()
            ErrorLog.scheduleRelaunch(this@GadgetService)
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /**
     * Settings changed from the laptop (echo-hub's /settings page). Only known keys; each is
     * saved and applied the same way as from Setup. Returns what was applied.
     */
    fun applySettings(changes: kotlinx.serialization.json.JsonObject): List<String> {
        val p = Prefs(this)
        val done = mutableListOf<String>()
        fun str(k: String) = (changes[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        fun bool(k: String) = (changes[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()
        fun num(k: String) = (changes[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
        str("clock_face")?.let { v -> p.clockFace = v; GadgetState.setClockFace(v); done += "clock_face" }
        str("frame_clock")?.let { v -> p.frameClock = v; GadgetState.setFrameClock(v); done += "frame_clock" }
        str("night_scene")?.let { v -> p.nightScene = v; GadgetState.setNightScene(v); done += "night_scene" }
        str("theme")?.let { v -> p.theme = v; GadgetState.setTheme(v); done += "theme" }
        str("background")?.let { v -> p.background = v; GadgetState.setBackground(v); done += "background" }
        (changes["page_order"] as? kotlinx.serialization.json.JsonArray)?.let { arr ->
            p.pageOrder = arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            GadgetState.setPages(p.pageOrder, p.pagesHidden); done += "page_order"
        }
        (changes["pages_hidden"] as? kotlinx.serialization.json.JsonArray)?.let { arr ->
            p.pagesHidden = arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.toSet()
            GadgetState.setPages(p.pageOrder, p.pagesHidden); done += "pages_hidden"
        }
        num("still_minutes")?.let { v -> setStill(v.toInt()); done += "still_minutes" }
        num("quiet_minutes")?.let { v ->
            p.quietUntil = when { v < 0 -> Long.MAX_VALUE; v == 0.0 -> 0L; else -> System.currentTimeMillis() + (v * 60_000).toLong() }
            GadgetState.setQuiet(isQuietNow())
            done += "quiet_minutes"
        }
        str("blob_name")?.let { v -> p.blobName = v; GadgetState.setBlobName(p.blobName); done += "blob_name" }
        bool("blob_room")?.let { v -> p.blobRoom = v; life.loadRoom(); done += "blob_room" }
        bool("blob_friend")?.let { v -> p.blobFriend = v; if (!v) life.endVisit(); done += "blob_friend" }
        bool("day_night_look")?.let { v -> p.dayNightLook = v; GadgetState.setLooks(p); done += "day_night_look" }
        bool("ambience")?.let { v -> p.ambience = v; GadgetState.setLooks(p); done += "ambience" }
        bool("ui_sounds")?.let { v -> p.uiSounds = v; done += "ui_sounds" }
        str("guest_wifi_ssid")?.let { v -> p.guestWifiSsid = v; GadgetState.setGuestWifi(p.guestWifiSsid.isNotBlank()); done += "guest_wifi_ssid" }
        str("guest_wifi_pass")?.takeIf { it.isNotEmpty() }?.let { v -> p.guestWifiPass = v; done += "guest_wifi_pass" }
        str("guest_wifi_sec")?.takeIf { it in setOf("WPA", "WEP", "nopass") }?.let { v -> p.guestWifiSec = v; done += "guest_wifi_sec" }
        bool("weather_accents")?.let { v -> p.weatherAccents = v; GadgetState.setLooks(p); done += "weather_accents" }
        str("user_name")?.let { v -> p.userName = v; GadgetState.setLooks(p); done += "user_name" }
        bool("puff_sniffles")?.let { v -> p.puffSniffles = v; if (!v) life.friends.stopSniffles(); done += "puff_sniffles" }
        bool("puff_away_sleepovers")?.let { v -> p.puffAwaySleepovers = v; done += "puff_away_sleepovers" }
        bool("puff_house_build")?.let { v -> p.puffHouseBuild = v; done += "puff_house_build" }
        bool("laptop_break")?.let { v -> p.laptopBreak = v; done += "laptop_break" }
        bool("warm_evenings")?.let { v -> p.warmEvenings = v; if (!v) GadgetState.setWarmth(0f); done += "warm_evenings" }
        bool("bedtime_hint")?.let { v -> p.bedtimeHint = v; done += "bedtime_hint" }
        bool("room_sense")?.let { v -> p.roomSense = v; done += "room_sense" }
        bool("diary")?.let { v -> p.diary = v; GadgetState.setDiaryOn(v); done += "diary" }
        bool("daily_thing")?.let { v -> p.dailyThing = v; if (!v) GadgetState.setDailyThing(null); done += "daily_thing" }
        bool("wellness_water")?.let { v -> p.wellnessWater = v; done += "wellness_water" }
        bool("wellness_stretch")?.let { v -> p.wellnessStretch = v; done += "wellness_stretch" }
        bool("wellness_eyes")?.let { v -> p.wellnessEyes = v; done += "wellness_eyes" }
        num("wellness_start")?.let { v -> p.wellnessStart = v.toInt().coerceIn(0, 23); done += "wellness_start" }
        num("wellness_end")?.let { v -> p.wellnessEnd = v.toInt().coerceIn(1, 24); done += "wellness_end" }
        (changes["routines"] as? kotlinx.serialization.json.JsonArray)?.let { arr ->
            val list = arr.mapNotNull { e ->
                val o = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                fun s(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
                com.rusty.museecho.integrations.Routine(
                    id = s("id").ifBlank { java.util.UUID.randomUUID().toString().take(8) },
                    time = s("time"), label = s("label"), prompt = s("prompt"),
                    days = (o["days"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty(),
                    enabled = (o["enabled"] as? kotlinx.serialization.json.JsonPrimitive)?.content != "false",
                )
            }
            routines.set(list)
            done += "routines"
        }
        num("frame_delay_min")?.let { v -> p.frameDelayMin = v.toInt().coerceIn(0, 120); done += "frame_delay_min" }
        num("blob_energy")?.let { v -> p.blobEnergy = v.toFloat().coerceIn(0f, 1f); GadgetState.setBlobEnergy(p.blobEnergy); done += "blob_energy" }
        bool("blob_sounds")?.let { v -> p.blobSounds = v; done += "blob_sounds" }
        bool("speak_replies")?.let { v -> p.speakReplies = v; done += "speak_replies" }
        bool("follow_up")?.let { v -> p.followUp = v; done += "follow_up" }
        bool("come_to_front")?.let { v -> p.comeToFront = v; done += "come_to_front" }
        bool("return_to_front")?.let { v -> p.returnToFront = v; done += "return_to_front" }
        bool("show_server_pill")?.let { v -> p.showServerPill = v; GadgetState.setServerPill(v); done += "show_server_pill" }
        bool("auto_brightness")?.let { v -> p.autoBrightness = v; done += "auto_brightness" }
        bool("laptop_notifications")?.let { v -> p.laptopNotifications = v; done += "laptop_notifications" }
        str("wake_sensitivity")?.let { v ->
            WakeWord.Sensitivity.entries.firstOrNull { it.name == v }?.let { setWakeSensitivity(it); done += "wake_sensitivity" }
        }
        num("guest_hours")?.let { v -> setGuestMode(v.toInt()); done += "guest_hours" }
        (changes["quick_actions"] as? kotlinx.serialization.json.JsonArray)?.let { arr ->
            val list = arr.mapNotNull { e ->
                val o = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                fun s(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
                if (s("label").isEmpty() || s("prompt").isEmpty()) null else com.rusty.museecho.integrations.QuickAction(s("label").take(20), s("prompt").take(300))
            }.take(4)
            if (list.isNotEmpty()) { quickActions.set(list); done += "quick_actions" }
        }
        // Morning / afternoon / evening sets (an empty list hands that part of the day back to the all-day buttons).
        (changes["quick_sets"] as? kotlinx.serialization.json.JsonObject)?.forEach { (period, v) ->
            val arr = v as? kotlinx.serialization.json.JsonArray ?: return@forEach
            val list = arr.mapNotNull { e ->
                val o = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                fun s(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
                if (s("label").isEmpty() || s("prompt").isEmpty()) null else com.rusty.museecho.integrations.QuickAction(s("label").take(18), s("prompt").take(500))
            }
            quickSets.set(period, list)
            done += "quick_sets.$period"
        }
        if (done.isNotEmpty()) GadgetState.log("settings from the laptop: ${done.joinToString()}")
        return done
    }

    /** Keeps the quick-action row on this part of the day's set (checked every minute and on edits). */
    private suspend fun pickQuickActions() {
        kotlinx.coroutines.flow.combine(quickActions.value, quickSets.value, kotlinx.coroutines.flow.flow {
            while (true) {
                emit(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))
                delay(60_000)
            }
        }.distinctUntilChanged()) { all, sets, hour -> sets.forHour(hour).ifEmpty { all } }
            .collect { GadgetState.setQuickNow(it) }
    }

    /** The forecast page's data (fetched while that page is on screen). */
    suspend fun fetchForecast(): com.rusty.museecho.integrations.Forecast? {
        val p = Prefs(this)
        return weather.forecast(p.weatherLat, p.weatherLon)
    }

    /** A reply to the laptop: onto its clipboard, with a desktop notification saying so. */
    suspend fun sendToLaptop(text: String): Boolean {
        val ok = laptop.command("SET_CLIPBOARD", stringArg = text)
        if (ok) {
            val preview = text.replace(Regex("\\s+"), " ").take(120)
            laptop.command("DISPLAY_MESSAGE", stringArg = "From ${GadgetState.museName.value} (copied to the clipboard): $preview" +
                if (text.length > 120) "…" else "")
            GadgetState.log("sent a reply to the laptop")
        }
        return ok
    }

    /** Routines: each one asks Muse its request at its time (once per day per routine). */
    private suspend fun runRoutines() {
        val ran = HashSet<String>()
        while (true) {
            val now = java.time.LocalDateTime.now()
            val hhmm = "%02d:%02d".format(now.hour, now.minute)
            for (r in routines.value.value.items) {
                if (!r.enabled || r.time != hhmm) continue
                if (r.days.isNotEmpty() && now.dayOfWeek.name !in r.days) continue
                if (!ran.add("${now.toLocalDate()} ${r.id} $hhmm")) continue
                GadgetState.log("routine: ${r.label}")
                bringToFront(force = false)
                talk(r.prompt, null, label = "⏰ ${r.label}")
            }
            delay(20_000)
        }
    }

    /**
     * Wellness nudges in the chosen hours: water hourly, a stretch every 50 minutes, eyes
     * every 20 (look 20 feet away for 20 seconds). A bubble and a little move; never spoken,
     * and skipped while something else is going on.
     */
    private suspend fun wellnessNudges() {
        var lastWater = System.currentTimeMillis()
        var lastStretch = System.currentTimeMillis()
        var lastEyes = System.currentTimeMillis()
        while (true) {
            delay(60_000)
            val p = Prefs(this)
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val now = System.currentTimeMillis()
            val inHours = hour >= p.wellnessStart && hour < p.wellnessEnd
            val busy = GadgetState.talk.value != Talk.IDLE || GadgetState.speaking.value || GadgetState.ringing.value ||
                GadgetState.screen.value !is Screen.Idle || isQuietNow()
            if (!inHours || busy) {
                // Restart the clocks so the first nudge comes a full interval into the window.
                if (!inHours) { lastWater = now; lastStretch = now; lastEyes = now }
                continue
            }
            when {
                p.wellnessWater && now - lastWater >= 60 * 60_000L -> {
                    lastWater = now
                    DayLog.nudged()
                    GadgetState.react(Reaction.NUDGE)
                    GadgetState.hold(HeldItem.COFFEE, "", 1)
                    GadgetState.say("💧 Time for some water!", 30)
                }
                p.wellnessStretch && now - lastStretch >= 50 * 60_000L -> {
                    lastStretch = now
                    DayLog.nudged()
                    GadgetState.react(Reaction.STRETCH)
                    GadgetState.say("🙆 Stand up and stretch for a minute!", 30)
                }
                p.wellnessEyes && now - lastEyes >= 20 * 60_000L -> {
                    lastEyes = now
                    DayLog.nudged()
                    GadgetState.react(Reaction.PEEK)
                    GadgetState.say("👀 Rest your eyes: look at something far away for 20 seconds.", 25)
                }
            }
        }
    }

    // -- Remote pages ---------------------------------------------------------------------

    fun tvAvailable() = roku.savedIp != null

    suspend fun tvKey(key: String): Boolean = roku.ip()?.let { roku.keypress(it, key) } ?: false

    suspend fun tvApps(): List<Pair<String, String>> = roku.ip()?.let { ip -> roku.apps(ip).map { it.id to it.name } }.orEmpty()

    suspend fun tvLaunch(id: String): Boolean = roku.ip()?.let { roku.launch(it, id) } ?: false

    suspend fun laptopShortcuts(): List<Pair<String, String>> = laptop.shortcuts().map { it.key to it.label }

    suspend fun laptopShortcut(key: String): String {
        val (ok, msg) = laptop.run(key)
        return if (ok) "✓ ${msg.ifBlank { "done" }}".take(80) else "✗ ${msg.take(80)}"
    }

    /** The touch pad: a timer for [ms]. Returns null, or what was wrong. */
    fun addTimerByTouch(ms: Long): String? = runCatching {
        val t = timers.addTimer(ms, "")
        GadgetState.react(Reaction.HOP)
        GadgetState.say("⏱ ${t.label} started", 4)
        null
    }.getOrElse { it.message ?: "couldn't set that timer" }

    /** The touch pad: an alarm at [time] ("7:30 am") on [days] (none = once). */
    fun addAlarmByTouch(time: String, days: List<String>): String? = runCatching {
        val a = timers.addAlarm(time, "Alarm", days)
        GadgetState.react(Reaction.HOP)
        GadgetState.say("⏰ Alarm set for ${com.rusty.museecho.integrations.Timers.clock(a.dueAtMs)}", 5)
        null
    }.getOrElse { it.message ?: "couldn't set that alarm" }

    /** Music on the laptop from its chip: a command, then a fresh status for the chip. */
    fun laptopMedia(command: String, intArg: Int = 0) {
        scope.launch {
            laptop.command(command, intArg)
            kotlinx.coroutines.delay(400)
            laptop.status()?.let { GadgetState.setLaptopStatus(it) }
        }
    }

    /** Guest mode for [hours] (0 turns it off). */
    fun setGuestMode(hours: Int) {
        val until = if (hours <= 0) 0L else System.currentTimeMillis() + hours.coerceAtMost(24) * 3_600_000L
        Prefs(this).guestUntil = until
        GadgetState.setGuestUntil(until)
        GadgetState.log(if (until == 0L) "guest mode off" else "guest mode on for $hours h")
    }

    /** The blob acts out the quick action you tapped. */
    fun quickActionReaction(label: String) {
        val l = label.lowercase()
        when {
            // (A sleepover invites a friend instead; Pixel does that.)
            "sleepover" in l -> GadgetState.react(Reaction.HOP)
            "night" in l || "bed" in l || "sleep" in l -> goodnight()
            "movie" in l || "film" in l || "tv" in l -> {
                // Popcorn for the length of a film (not saved: Pixel's own style comes back after).
                val now = System.currentTimeMillis()
                val current = GadgetState.style.value.takeIf { it.active(now) } ?: BlobStyle()
                GadgetState.setStyle(current.copy(accessory = Accessory.POPCORN, untilMs = now + 3 * 3_600_000L))
                GadgetState.react(Reaction.HOP)
            }
            "out" in l || "leav" in l || "bye" in l -> {
                GadgetState.react(Reaction.WAVE)
                GadgetState.hold(HeldItem.SUITCASE, "", 10)
                GadgetState.say("Bye! 👋", 10)
            }
            "what" in l || "news" in l || "brief" in l -> GadgetState.setMood(Mood.CURIOUS, 20)
            else -> GadgetState.react(Reaction.NOD)
        }
    }

    /** Yawn, wave, tuck in, then fade the screen out over a minute and sleep until touched. */
    /** Run [block] on the service's own scope (outlives a command's timeout). */
    fun scopeLaunch(block: suspend () -> Unit) = scope.launch { block() }

    fun goodnight() = scope.launch {
        // A one-line bedtime story first, in its bubble while it settles down.
        if (::life.isInitialized) GadgetState.say(life.friends.bedtimeStory(), 20)
        GadgetState.react(Reaction.YAWN)
        delay(Reaction.YAWN.ms)
        GadgetState.react(Reaction.WAVE)
        delay(Reaction.WAVE.ms)
        GadgetState.setTuckedIn(true)
        GadgetState.react(Reaction.TUCK_IN)
        delay(Reaction.TUCK_IN.ms + 4_000)
        val steps = 60
        for (i in 0..steps) {
            if (!GadgetState.tuckedIn.value) {
                GadgetState.setDim(null)
                return@launch
            }
            GadgetState.setDim(1f - 0.97f * i / steps)
            delay(1_000)
        }
        // Stays at the bottom of the fade (asleep) until a touch untucks it.
        if (GadgetState.tuckedIn.value) GadgetState.setDim(GOODNIGHT_DIM) else GadgetState.setDim(null)
    }

    // -- Room sense ----------------------------------------------------------------------

    /** The lights went off: at night, that's bedtime. */
    private fun onRoomDark() {
        if (!Prefs(this).roomSense) return
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (!(isQuietNow() || hour >= 21 || hour < 5)) return
        if (GadgetState.tuckedIn.value || GadgetState.asleep.value || GadgetState.ringing.value ||
            GadgetState.talk.value == Talk.LISTENING || GadgetState.talk.value == Talk.SENDING || GadgetState.speaking.value
        ) return
        GadgetState.log("room sense: dark at night, going to sleep")
        DayLog.event("the lights went off, so I went to sleep", "sleep")
        goodnight()
    }

    /** The lights came on in the morning: wake up and stretch. */
    /**
     * Room sense: the lights are on on a morning that's still in quiet hours (before 7 by default). The
     * morning starts now: quiet hours end for today (normal brightness, wake word, voice), back tonight.
     */
    /** Since when the room's been lit (0 = it isn't); the light sensor only reports changes, so the watchdog times it. */
    @Volatile private var litSince = 0L
    private var lastLuxSaved = 0L

    private fun morningStartsEarly() {
        val p = Prefs(this)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (!p.roomSense || hour !in 5..11 || !isQuietNow() || System.currentTimeMillis() < p.quietUntil) return
        val end = QuietHours.parse(p.quietEnd) ?: return
        val endMs = java.time.LocalDate.now().atTime(end).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (endMs <= System.currentTimeMillis()) return
        p.quietSkipUntil = endMs
        GadgetState.log("room sense: lights on before quiet hours end, the morning starts now")
    }

    private fun onLightsOn() {
        if (!Prefs(this).roomSense) return
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        morningStartsEarly()
        if (hour !in 5..11 || !(GadgetState.tuckedIn.value || GadgetState.asleep.value)) return
        GadgetState.log("room sense: lights on in the morning, waking up")
        DayLog.event("the lights came on and I woke up", "wake")
        GadgetState.setTuckedIn(false)
        GadgetState.setDim(null)
        GadgetState.lastTouch = System.currentTimeMillis()
        GadgetState.setAsleep(false)
        scope.launch {
            delay(800)
            GadgetState.react(Reaction.YAWN)
            delay(Reaction.YAWN.ms)
            GadgetState.react(Reaction.STRETCH)
            GadgetState.say("Morning! ☀️", 20)
        }
    }

    // -- Background asks (diary, thing of the day) ---------------------------------------

    @Volatile
    private var quietAsking = false
    private var quietJob: kotlinx.coroutines.Job? = null

    /**
     * Ask Pixel something without showing or saying anything. Waits for a calm moment (no one
     * has touched or talked to the Echo for a few minutes) and gives way to anything real:
     * talking cancels it. Null if there was no answer.
     */
    private suspend fun quietAsk(prompt: String): String? = quietAskImpl(prompt, waitForCalm = true)

    private suspend fun quietAskImpl(prompt: String, waitForCalm: Boolean): String? {
        val calm = kotlinx.coroutines.withTimeoutOrNull(20 * 60_000L) {
            while (!(GadgetState.link.value == Link.CONNECTED && chat.feedOpen && turnJob?.isActive != true && quietJob?.isActive != true &&
                    (GadgetState.talk.value == Talk.IDLE || GadgetState.talk.value == Talk.REPLIED) && !GadgetState.speaking.value &&
                    (!waitForCalm || System.currentTimeMillis() - GadgetState.lastHuman > 3 * 60_000L))
            ) delay(if (waitForCalm) 15_000 else 2_000)
            true
        } ?: return null
        if (!calm) return null
        val job = scope.async {
            quietAsking = true
            try {
                chat.turn(prompt, null) { _, _ -> }?.text
            } finally {
                // Let a late second part of the reply arrive without popping up as a message.
                scope.launch { delay(30_000); if (quietJob?.isActive != true) quietAsking = false }
            }
        }
        quietJob = job
        return try {
            job.await()?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (!job.isCancelled) throw e
            GadgetState.log("background ask gave way")
            null
        } catch (e: Exception) {
            GadgetState.log("background ask failed: ${e.message}")
            null
        }
    }

    /** After 9pm, Pixel writes the blob's diary entry for the day (a few tries if it's busy). */
    private suspend fun writeDiary() {
        var tries = 0
        var triedFor = ""
        while (true) {
            delay(5 * 60_000L)
            val now = java.time.LocalDateTime.now()
            val today = now.toLocalDate().toString()
            if (!Prefs(this).diary || now.hour < 21 || GadgetState.diary.value.any { it.date == today }) continue
            if (triedFor != today) { triedFor = today; tries = 0 }
            if (tries >= 3) continue
            tries++
            diaryNow(waitForCalm = true)
        }
    }

    /** Write (or rewrite) today's diary entry now. */
    suspend fun diaryNow(waitForCalm: Boolean): Boolean {
        val today = java.time.LocalDate.now().toString()
        val w = GadgetState.weather.value?.let { "${it.summary}, high ${it.highF}° low ${it.lowF}°" }
        val text = quietAskImpl(DayWriting.diaryPrompt(DayLog.today(), w, GadgetState.blobName.value), waitForCalm) ?: return false
        if (java.time.LocalDate.now().toString() != today) return false
        GadgetState.setDiary(diary.add(DiaryEntry(today, text.take(1500), System.currentTimeMillis())))
        GadgetState.log("diary written")
        scope.launch { postJournalOnce() }
        return true
    }

    /**
     * Each morning once quiet hours are over, and again at noon and 5 pm: Pixel quietly fills in the
     * agenda page from the calendars, so it's there without the morning brief and catches events added
     * during the day.
     */
    private suspend fun fillAgenda() {
        var tries = 0
        var triedFor = ""
        delay(90_000)
        while (true) {
            val now = java.time.LocalDateTime.now()
            val slot = AGENDA_HOURS.lastOrNull { now.hour >= it }
            if (slot != null && !isQuietNow()) {
                val slotStart = now.toLocalDate().atTime(slot, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                val key = "${now.toLocalDate()} $slot"
                if (agenda.value.value.updatedMs < slotStart) {
                    if (triedFor != key) { triedFor = key; tries = 0 }
                    if (tries < 4) {
                        tries++
                        GadgetState.log("filling in today's agenda")
                        quietAskImpl(AGENDA_PROMPT, waitForCalm = false)
                    }
                }
            }
            delay(10 * 60_000L)
        }
    }

    /** Each morning: a word, fact or bit of history for the little sign on the clock page. */
    private suspend fun fetchDailyThing() {
        var tries = 0
        var triedFor = ""
        delay(60_000)
        while (true) {
            val p = Prefs(this)
            val today = java.time.LocalDate.now().toString()
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            if (!p.dailyThing) GadgetState.setDailyThing(null)
            else if (GadgetState.dailyThing.value?.date != today && hour >= 6 && !isQuietNow()) {
                if (triedFor != today) { triedFor = today; tries = 0 }
                if (tries < 4) {
                    tries++
                    thingNow(waitForCalm = true)
                }
            }
            delay(5 * 60_000L)
        }
    }

    /** A new thing of the day now (replacing today's). */
    suspend fun thingNow(waitForCalm: Boolean): Boolean {
        val today = java.time.LocalDate.now().toString()
        val t = quietAskImpl(DayWriting.THING_PROMPT, waitForCalm)?.let { DayWriting.parseThing(it, today) } ?: return false
        val p = Prefs(this)
        p.dailyThingJson = kotlinx.serialization.json.Json.encodeToString(DailyThing.serializer(), t)
        val ser = kotlinx.serialization.builtins.ListSerializer(DailyThing.serializer())
        val past = runCatching { kotlinx.serialization.json.Json.decodeFromString(ser, p.thingHistoryJson ?: "[]") }.getOrDefault(emptyList())
        p.thingHistoryJson = kotlinx.serialization.json.Json.encodeToString(ser, (past + t).takeLast(30))
        GadgetState.setDailyThing(t)
        GadgetState.log("thing of the day: ${t.title}")
        // Shown off for a few minutes when it arrives.
        if (!GadgetState.asleep.value) {
            GadgetState.react(Reaction.HOP)
            GadgetState.hold(HeldItem.NOTE, "${DayWriting.thingIcon(t.kind)} ${t.title}", 5)
        }
        scope.launch { postJournalOnce() }
        return true
    }

    /** "How are you, Puff?": Pixel answers in Puff's voice from what's really been going on; Puff says it in its bubble. */
    fun askPuff() {
        scope.launch {
            val name = GadgetState.blobCalled()
            GadgetState.react(Reaction.LEAN_IN)
            GadgetState.say("Hmm, let me think… 💭", 20)
            val d = life.friends.store.data
            val day = DayLog.today()
            val facts = buildList {
                GadgetState.weather.value?.let { add("weather: ${it.summary}, ${it.tempF}°F") }
                addAll(day.events.takeLast(8))
                if (day.talks > 0) add("Rusty talked to it ${day.talks} times today")
                d.story?.takeIf { !it.done && it.at > 0 }?.let { add("the friends' adventure \"${it.title}\": ${it.chapters[it.at - 1].line}") }
                d.pet?.takeIf { it.name.isNotBlank() }?.let { add("its pet ${it.label} ${it.name}" + if (it.fedDate == java.time.LocalDate.now().toString()) " (fed today)" else "") }
                d.letters.lastOrNull()?.let { l -> add("latest letter: " + if (l.fromBlob) "it wrote to a friend" else "from ${d.friends.firstOrNull { it.id == l.friendId }?.name ?: "a friend"}") }
                d.treasure?.takeIf { !it.found }?.let { add("there's a treasure hunt on that nobody has solved yet") }
                GadgetState.mood.value?.let { add("mood right now: ${it.mood.name.lowercase()}") }
            }.ifEmpty { listOf("a quiet day at home") }
            val answer = quietAskImpl(FriendsBrain.askPrompt(name, facts), waitForCalm = false)
            if (answer == null) {
                GadgetState.say("I'm good! Just a bit sleepy. 😊", 8)
                return@launch
            }
            GadgetState.react(Reaction.HOP)
            for (part in answer.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }.take(6)) {
                GadgetState.say(part, 6)
                delay(5_500)
            }
            DayLog.event("Rusty asked how it was doing", "talk")
        }
    }

    /** From the settings page: the diary or a new thing of the day, now. */
    fun askNow(what: String) {
        scope.launch {
            val ok = if (what == "diary") diaryNow(waitForCalm = false) else thingNow(waitForCalm = false)
            GadgetState.log("asked from the laptop for the $what: ${if (ok) "done" else "no answer"}")
        }
    }

    // -- The settings page's journal -------------------------------------------------------

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private suspend fun postJournal() {
        delay(30_000)
        // Soon after Puff's world changes (a letter, a sticker…) so the phone hears quickly, and every 5 minutes anyway.
        scope.launch {
            GadgetState.friends.debounce(20_000).collect {
                if (System.currentTimeMillis() - lastJournalMs > 60_000) postJournalOnce()
            }
        }
        // And when the agenda or its leave times change, so the phone's alerts are current.
        scope.launch {
            GadgetState.leaveBy.drop(1).debounce(10_000).collect { postJournalOnce() }
        }
        scope.launch {
            agenda.value.drop(1).debounce(10_000).collect { postJournalOnce() }
        }
        while (true) {
            if (System.currentTimeMillis() - lastJournalMs > 4 * 60_000L) postJournalOnce()
            delay(5 * 60_000L)
        }
    }

    @Volatile private var lastJournalMs = 0L

    suspend fun postJournalOnce() {
        lastJournalMs = System.currentTimeMillis()
        val json = kotlinx.serialization.json.Json
        val things = Prefs(this).thingHistoryJson ?: "[]"
        hub.postJournal(kotlinx.serialization.json.buildJsonObject {
            put("diary", json.parseToJsonElement(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(DiaryEntry.serializer()),
                GadgetState.diary.value.takeLast(30))))
            put("things", json.parseToJsonElement(things))
            put("day", json.parseToJsonElement(json.encodeToString(DayFacts.serializer(), DayLog.today())))
            put("room", kotlinx.serialization.json.JsonArray(GadgetState.room.value.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            GadgetState.weekRecap.value?.let { (text, at) -> put("week_recap", text); put("week_recap_ms", at) }
            put("friends", json.parseToJsonElement(json.encodeToString(FriendsData.serializer(), GadgetState.friends.value.copy(today = emptyMap()))))
            put("street", life.friends.streetInfo())
            // The agenda page, with when to leave for events that have an address (for the phone's agenda and alerts).
            put("agenda", agenda.value.value.let { d ->
                val leave = GadgetState.leaveBy.value
                fun items(list: List<com.rusty.museecho.integrations.AgendaItem>, withLeave: Boolean) = kotlinx.serialization.json.JsonArray(list.map { i ->
                    kotlinx.serialization.json.buildJsonObject {
                        put("time", i.time); put("title", i.title); put("location", i.location)
                        if (withLeave) leave["${i.title}|${i.time}"]?.let { put("leave_min", it) }
                    }
                })
                val isToday = BlobBrain.isToday(d.date, java.time.LocalDate.now())
                kotlinx.serialization.json.buildJsonObject {
                    put("date", d.date)
                    put("day", if (isToday) java.time.LocalDate.now().toString() else "")
                    put("updated_ms", d.updatedMs)
                    put("items", items(d.items, isToday))
                    put("upcoming", kotlinx.serialization.json.JsonArray(d.upcoming.map { u ->
                        kotlinx.serialization.json.buildJsonObject { put("date", u.date); put("items", items(u.items, false)) } }))
                }
            })
            // For the phone's remote.
            put("remote", kotlinx.serialization.json.buildJsonObject {
                put("tv", tvAvailable())
                put("laptop", laptop.configured)
                put("shortcuts", kotlinx.serialization.json.JsonArray(runCatching { laptopShortcuts() }.getOrDefault(emptyList()).map { (k, l) ->
                    kotlinx.serialization.json.buildJsonObject { put("key", k); put("label", l) } }))
            })
            put("catalog", kotlinx.serialization.json.buildJsonObject {
                put("stickers", kotlinx.serialization.json.JsonArray(FriendsBrain.STICKERS.map { s ->
                    kotlinx.serialization.json.buildJsonObject { put("id", s.id); put("emoji", s.emoji); put("title", s.title); put("who", s.who) } }))
                put("collectibles", kotlinx.serialization.json.JsonArray(FriendsBrain.COLLECTIBLES.map { c ->
                    kotlinx.serialization.json.buildJsonObject { put("id", c.id); put("emoji", c.emoji); put("name", c.name); put("set", c.set) } }))
            })
            put("today_activities", kotlinx.serialization.json.buildJsonObject {
                val today = java.time.LocalDate.now().toString()
                GadgetState.friends.value.today.filterKeys { it.startsWith("$today|") }.forEach { (k, v) ->
                    put(k.substringAfter('|'), kotlinx.serialization.json.JsonArray(v.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                }
            })
            put("friend_album", kotlinx.serialization.json.JsonArray(GadgetState.friendAlbum.value.takeLast(60).map { a ->
                kotlinx.serialization.json.buildJsonObject { put("date", a.date); put("caption", a.caption) }
            }))
        })
    }

    // -- Who's home ----------------------------------------------------------------------

    /** Rusty's phone joining or leaving the home Wi-Fi (echo-hub watches for it), and visitors. Also counts TV minutes. */
    private suspend fun watchPresence() {
        var last: com.rusty.museecho.integrations.Presence? = null
        var tvWas = false
        val greeted = HashSet<String>()
        var first = true
        while (true) {
            val tv = GadgetState.tvPlaying.value
            if (tv) DayLog.tvMinute()
            if (tv != tvWas) DayLog.mark(if (tv) "tv_on" else "tv_off")
            tvWas = tv
            val p = hub.presence()
            val now = p?.takeIf { it.configured && it.home != null }
            val prev = last
            if (now != null) {
                if (prev?.home != null && prev.home != now.home) onPresence(now.home == true, prev.since)
                last = now
            }
            // A new phone on the Wi-Fi: say hi and offer guest mode (not if it's already on, or at night).
            // (Ones already there when the app starts were greeted before.)
            val fresh = p?.visitors?.filter { greeted.add(it.mac) }.orEmpty()
            if (!first) fresh.lastOrNull()?.let { onVisitor(it.name) }
            if (p != null) first = false
            delay(60_000)
        }
    }

    private fun noteLightsOff() {
        if (::life.isInitialized) life.lightsOff()
    }

    private fun onVisitor(name: String) {
        val nice = name.substringBefore('.').replace('-', ' ').takeIf { it.isNotBlank() && !it.first().isDigit() }.orEmpty()
        GadgetState.log("visitor on the Wi-Fi${if (nice.isNotEmpty()) ": $nice" else ""}")
        DayLog.event("a visitor's phone joined the Wi-Fi", "visitor")
        if (isQuietNow() || GadgetState.guestMode()) return
        GadgetState.react(Reaction.WAVE)
        GadgetState.setMood(Mood.HAPPY, 60)
        GadgetState.say("Oh, a visitor! Hi there! 👋", 30)
        GadgetState.setVisitorPrompt(nice)
    }

    private fun onPresence(home: Boolean, prevSince: Double?) {
        if (home) {
            val away = prevSince?.let { System.currentTimeMillis() - (it * 1000).toLong() }
            DayLog.event("you came home" + (away?.let { " after ${DayWriting.awayFor(it)} out" } ?: ""), "home")
            GadgetState.setMood(Mood.NEUTRAL, 0)
            // Short trips (and not knowing how long) don't get the fuss.
            if (away == null || away < 20 * 60_000L) return
            GadgetState.log("phone is home (out ${DayWriting.awayFor(away)})")
            GadgetState.react(Reaction.HOP)
            GadgetState.setMood(Mood.HAPPY, 120)
            GadgetState.say("Welcome home! 🏠 You were out ${DayWriting.awayFor(away)}.", 90)
        } else {
            GadgetState.log("phone left")
            DayLog.event("you went out", "left")
            GadgetState.react(Reaction.WAVE)
            GadgetState.say("Bye! 👋 See you later.", 60)
            // Dozes while you're out (until you're back, or a few hours).
            scope.launch {
                delay(Reaction.WAVE.ms + 60_000)
                GadgetState.setMood(Mood.SLEEPY, 6 * 3600)
            }
        }
    }

    // -- The laptop's desktop ------------------------------------------------------------

    /** The laptop's colours and wallpaper from the echo-hub (sent by tools/desktop-sync). */
    private suspend fun watchDesktop() {
        while (true) {
            val p = Prefs(this)
            hub.desktop()?.let { d ->
                val stamp = (d.updated_at * 1000).toLong()
                if (stamp > p.desktopUpdatedMs && d.colors.isNotEmpty()) {
                    if (d.wallpaper) hub.desktopWallpaper()?.let { bytes ->
                        val f = java.io.File(filesDir, DESKTOP_WALLPAPER)
                        val tmp = java.io.File(filesDir, "$DESKTOP_WALLPAPER.part")
                        runCatching { tmp.writeBytes(bytes); tmp.renameTo(f) }
                        GadgetState.setDesktopWallpaper(f)
                    }
                    p.desktopColorsJson = kotlinx.serialization.json.JsonObject(d.colors.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }).toString()
                    GadgetState.setDesktopColors(d.colors)
                    p.desktopUpdatedMs = stamp
                    GadgetState.log("laptop desktop colours updated")
                }
            }
            delay(2 * 60_000L)
        }
    }

    private var volumeReceiver: android.content.BroadcastReceiver? = null

    /** Volume changes: covers its ears when it's loud, leans in when it's quiet, shows a mute sign at zero. */
    private fun watchVolume() {
        val audio = getSystemService(android.media.AudioManager::class.java) ?: return
        fun fraction() = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() /
            audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        GadgetState.setMuted(fraction() == 0f)
        var settle: kotlinx.coroutines.Job? = null
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: Intent) {
                if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != android.media.AudioManager.STREAM_MUSIC) return
                GadgetState.showLevel("volume", fraction())
                // Wait for the presses to stop, then react once to where it ended up.
                settle?.cancel()
                settle = scope.launch {
                    delay(700)
                    val f = fraction()
                    GadgetState.setMuted(f == 0f)
                    when {
                        f >= 0.85f -> GadgetState.react(Reaction.COVER_EARS)
                        f > 0f && f <= 0.2f -> GadgetState.react(Reaction.LEAN_IN)
                    }
                }
            }
        }
        registerReceiver(receiver, android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        volumeReceiver = receiver
    }

    /** The laptop coming and going: the blob says hi when it shows up and waves bye when it leaves. */
    private suspend fun watchLaptop() {
        var was: Boolean? = null
        while (true) {
            if (laptop.configured && !isQuietNow()) {
                val online = laptop.online()
                if (was == false && online) {
                    GadgetState.react(Reaction.HOP)
                    GadgetState.say("Laptop's here! 💻", 30)
                } else if (was == true && !online) {
                    GadgetState.react(Reaction.WAVE)
                    GadgetState.say("Bye, laptop!", 20)
                }
                was = online
            } else {
                was = null
            }
            delay(120_000)
        }
    }

    /** New photos in the frame (frame.add or pushed from the laptop) get an excited look. */
    private suspend fun watchFrame() {
        var known = frame.photos().map { it.name }.toSet()
        while (true) {
            delay(60_000)
            val now = frame.photos().map { it.name }.toSet()
            if ((now - known).isNotEmpty()) {
                GadgetState.react(Reaction.HOP)
                GadgetState.hold(HeldItem.CAMERA, "", 2)
                GadgetState.say(if ((now - known).size == 1) "Ooh, a new photo!" else "Ooh, ${(now - known).size} new photos!", 20)
            }
            known = now
        }
    }

    /** Asks the echo-hub whether Muse is busy on the Latitude, so the blob can show it. */
    private suspend fun watchMuseElsewhere() {
        while (true) {
            val busy = !isQuietNow() && hub.activity()?.busy == true
            GadgetState.setMuseBusy(busy)
            delay(if (isQuietNow()) 120_000 else 20_000)
        }
    }

    private var lightListener: android.hardware.SensorEventListener? = null

    /**
     * The light sensor, for "the room went dark" / "lights came on". The
     * screen's own glow reaches it too, so dark means very little light, held
     * for a minute; bright means clearly more than that.
     */
    private fun watchLight() {
        val sensors = getSystemService(android.hardware.SensorManager::class.java) ?: return
        val sensor = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_LIGHT) ?: return
        var darkSince = 0L
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val lux = e.values.firstOrNull() ?: return
                GadgetState.lux = lux
                val now = System.currentTimeMillis()
                // Remembered across restarts: this sensor only reports changes, so a restart in a steadily
                // lit (or dark) room would otherwise know nothing until a light is switched.
                if (now - lastLuxSaved > 30_000) {
                    lastLuxSaved = now
                    getSharedPreferences("museecho", MODE_PRIVATE).edit().putFloat("last_lux", lux).putLong("last_lux_at", now).apply()
                }
                val wasDark = GadgetState.roomDark.value
                if (lux < DARK_LUX) {
                    litSince = 0L
                    if (darkSince == 0L) darkSince = now
                    if (wasDark != true && now - darkSince >= 60_000) {
                        GadgetState.setRoomDark(true)
                        GadgetState.log("room went dark (%.0f lux)".format(lux))
                        DayLog.mark("lights_off")
                        noteLightsOff()
                        onRoomDark()
                    }
                } else {
                    darkSince = 0L
                    // How long the room's been lit (the watchdog starts the morning early after a couple of minutes).
                    if (lux > LIGHT_LUX) { if (litSince == 0L) litSince = now } else litSince = 0L
                    if (lux > LIGHT_LUX && wasDark != false) {
                        GadgetState.setRoomDark(false)
                        if (wasDark == true) {
                            GadgetState.log("lights came on (%.0f lux)".format(lux))
                            DayLog.mark("lights_on")
                            onLightsOn()
                        }
                    }
                }
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, accuracy: Int) {}
        }
        sensors.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
        lightListener = listener
        // No fresh reading after a restart: start from the last one (if it's from the last 12 hours).
        scope.launch {
            delay(5_000)
            val prefs = getSharedPreferences("museecho", MODE_PRIVATE)
            val last = prefs.getFloat("last_lux", -1f)
            if (GadgetState.lux < 0f && last >= 0f && System.currentTimeMillis() - prefs.getLong("last_lux_at", 0L) < 12 * 3600_000L) {
                GadgetState.lux = last
                if (last > LIGHT_LUX && litSince == 0L) litSince = System.currentTimeMillis()
                GadgetState.log("light sensor: starting from the last reading (%.0f lux)".format(last))
            }
        }
    }

    /** Polls the Roku now and then, so the blob can watch along. Never searches the network for it. */
    private suspend fun watchTv() {
        while (true) {
            val ip = roku.savedIp
            val playing = if (ip == null || isQuietNow()) false else roku.playing(ip) == true
            GadgetState.setTvPlaying(playing)
            delay(if (playing) 60_000 else 120_000)
        }
    }

    private fun onWake() {
        // Its own voice trailing off can sound like the phrase: ignore wakes right after speaking.
        if (System.currentTimeMillis() - GadgetState.speechEndedAt < WAKE_GRACE_MS) {
            GadgetState.log("wake word ignored (just finished speaking)")
            wakeWord.resume()
            return
        }
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
        const val WAKE_GRACE_MS = 2_500L
        const val GOODNIGHT_DIM = 0.03f
        const val DESKTOP_WALLPAPER = "desktop_wallpaper.jpg"
        /** Rough speaking rate of the voices, for pacing the brief's props. */
        const val BRIEF_CHARS_PER_SECOND = 14f
        const val DARK_LUX = 5f
        // Rusty's room with the lights on reads only 12-13 lux at the Echo; dark is under 5.
        const val LIGHT_LUX = 9f
        const val ACTION_PAIR = "com.rusty.museecho.PAIR"
        const val ACTION_STOP_PAIR = "com.rusty.museecho.STOP_PAIR"
        const val ACTION_RECONNECT = "com.rusty.museecho.RECONNECT"
        private const val CHANNEL = "muse"
        private const val NOTIFICATION_ID = 1
        const val PAIRING_WINDOW_MS = 10 * 60_000L
        const val RING_MAX_MS = 2 * 60_000L
        const val RECONNECT_KICK_MS = 5 * 60_000L
        /** When the agenda is refreshed from the calendars (hours). */
        val AGENDA_HOURS = listOf(5, 12, 17)
        const val AGENDA_PROMPT = "Quietly, with no reply needed beyond \"done\": on the Echo Show, call agenda.set with " +
            "today's events from ALL my Google calendars (school, church, family and the rest, not just the primary one) " +
            "plus the next 3 days as upcoming. Give events a location (street address) when they have one. Call it even " +
            "if today is empty, so the Echo knows it's up to date."
        const val BRIEF_PROMPT = "Good morning! Give me my morning brief, under 80 words and written to be spoken aloud " +
            "(no lists or markdown): today's weather here, what's on my calendars today, anything I asked you to remind " +
            "me about, and whether anything is wrong on the Latitude server. Check ALL my Google calendars (school, " +
            "church, family and the rest, not just the primary one, which is usually empty). Also, on the Echo Show: " +
            "call agenda.set with today's events from all of them plus the next 3 days as upcoming, and reminders.set " +
            "with anything I should be reminded of today (with times when they have one). If something big is coming " +
            "up (a trip, a birthday, a holiday) and isn't on its countdowns yet, add it with countdown.add. Give events " +
            "a location (street address) when they have one, so the Echo can say when to leave. Finally, pick today's " +
            "look for the blob with blob.style (hours 14): a colour and/or accessory that suits the weather or what's " +
            "on today (e.g. umbrella weather, a church day, a birthday). And send schoolwork due in the next two weeks from " +
            "my school calendar with assignments.set."

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
    var dismissedAlerts: Set<String>
        get() = prefs.getStringSet("dismissed_alerts", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("dismissed_alerts", v.toSet()).apply()

    var announcedAlerts: Set<String>
        get() = prefs.getStringSet("announced_alerts", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("announced_alerts", v.toSet()).apply()

    /** Come back to the front after a minute behind another app. */
    /** Brightness picked on the swipe-down panel while auto brightness is off (-1: the system's). */
    var manualBrightness: Float
        get() = prefs.getFloat("manual_brightness", -1f)
        set(v) = prefs.edit().putFloat("manual_brightness", v).apply()

    var autoBrightness: Boolean
        get() = prefs.getBoolean("auto_brightness", true)
        set(v) = prefs.edit().putBoolean("auto_brightness", v).apply()

    var guestUntil: Long
        get() = prefs.getLong("guest_until", 0L)
        set(v) = prefs.edit().putLong("guest_until", v).apply()

    var frameFavs: Set<String>
        get() = prefs.getStringSet("frame_favs", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("frame_favs", v.toSet()).apply()

    var frameHidden: Set<String>
        get() = prefs.getStringSet("frame_hidden", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("frame_hidden", v.toSet()).apply()

    var laptopNotifications: Boolean
        get() = prefs.getBoolean("laptop_notifications", true)
        set(v) = prefs.edit().putBoolean("laptop_notifications", v).apply()

    /** Laptop apps whose notifications stay off the Echo. */
    var laptopMutedApps: Set<String>
        get() = prefs.getStringSet("laptop_muted_apps", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("laptop_muted_apps", v.toSet()).apply()

    /** Laptop apps seen so far, for the setup list. */
    var laptopKnownApps: Set<String>
        get() = prefs.getStringSet("laptop_known_apps", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("laptop_known_apps", v.toSet()).apply()

    var returnToFront: Boolean
        get() = prefs.getBoolean("return_to_front", true)
        set(v) = prefs.edit().putBoolean("return_to_front", v).apply()

    var blobSounds: Boolean
        get() = prefs.getBoolean("blob_sounds", true)
        set(v) = prefs.edit().putBoolean("blob_sounds", v).apply()

    var theme: String
        get() = prefs.getString("theme", "MIDNIGHT") ?: "MIDNIGHT"
        set(v) = prefs.edit().putString("theme", v).apply()

    var background: String
        get() = prefs.getString("background", "PLAIN") ?: "PLAIN"
        set(v) = prefs.edit().putString("background", v).apply()

    /** Lights off at night: go to sleep; lights on in the morning: wake up. */
    var pageOrder: List<String>
        get() = prefs.getString("page_order", "")!!.split(',').filter { it.isNotEmpty() }
        set(v) = prefs.edit().putString("page_order", v.joinToString(",")).apply()

    var pagesHidden: Set<String>
        get() = prefs.getString("pages_hidden", "")!!.split(',').filter { it.isNotEmpty() }.toSet()
        set(v) = prefs.edit().putString("pages_hidden", v.joinToString(",")).apply()

    var blobName: String
        get() = prefs.getString("blob_name", "") ?: ""
        set(v) = prefs.edit().putString("blob_name", v.trim().take(16)).apply()

    /** The blob's own birthday ("MM-dd"): the day the Muse Echo first came alive. */
    var blobBirthday: String
        get() = prefs.getString("blob_birthday", null) ?: "10-05"
        set(v) = prefs.edit().putString("blob_birthday", v).apply()

    /** Hold still until (epoch ms; Long.MAX_VALUE = until turned off; 0 = off). */
    var stillUntil: Long
        get() = prefs.getLong("still_until", 0L)
        set(v) = prefs.edit().putLong("still_until", v).apply()

    /** Quiet now, from the phone (epoch ms; Long.MAX_VALUE = until turned off; 0 = off), on top of the nightly hours. */
    var quietUntil: Long
        get() = prefs.getLong("quiet_until", 0L)
        set(v) = prefs.edit().putLong("quiet_until", v).apply()

    /** The morning started early (the lights came on before quiet hours ended): not quiet until this (epoch ms). */
    var quietSkipUntil: Long
        get() = prefs.getLong("quiet_skip_until", 0L)
        set(v) = prefs.edit().putLong("quiet_skip_until", v).apply()

    var blobRoom: Boolean
        get() = prefs.getBoolean("blob_room", true)
        set(v) = prefs.edit().putBoolean("blob_room", v).apply()

    var blobFriend: Boolean
        get() = prefs.getBoolean("blob_friend", true)
        set(v) = prefs.edit().putBoolean("blob_friend", v).apply()

    /** Looks: the room's light shifts the UI lighter / deeper; faint drifting ambience; soft UI sounds. */
    var dayNightLook: Boolean
        get() = prefs.getBoolean("day_night_look", true)
        set(v) = prefs.edit().putBoolean("day_night_look", v).apply()
    var ambience: Boolean
        get() = prefs.getBoolean("ambience", true)
        set(v) = prefs.edit().putBoolean("ambience", v).apply()
    var uiSounds: Boolean
        get() = prefs.getBoolean("ui_sounds", false)
        set(v) = prefs.edit().putBoolean("ui_sounds", v).apply()
    var weatherAccents: Boolean
        get() = prefs.getBoolean("weather_accents", true)
        set(v) = prefs.edit().putBoolean("weather_accents", v).apply()
    /** Guest Wi-Fi for the QR code visitors scan (security WPA, WEP or nopass). The password never leaves the Echo. */
    var guestWifiSsid: String
        get() = prefs.getString("guest_wifi_ssid", "") ?: ""
        set(v) = prefs.edit().putString("guest_wifi_ssid", v.trim().take(32)).apply()
    var guestWifiPass: String
        get() = prefs.getString("guest_wifi_pass", "") ?: ""
        set(v) = prefs.edit().putString("guest_wifi_pass", v.take(63)).apply()
    var guestWifiSec: String
        get() = prefs.getString("guest_wifi_sec", "WPA") ?: "WPA"
        set(v) = prefs.edit().putString("guest_wifi_sec", v).apply()

    /** What the greeting calls you. */
    var userName: String
        get() = prefs.getString("user_name", null) ?: "Rusty"
        set(v) = prefs.edit().putString("user_name", v.trim().take(20)).apply()

    /** Puff's world extras that can be switched off: sniffly days, overnight stays at friends', new friends via a week-long house build. */
    var puffSniffles: Boolean
        get() = prefs.getBoolean("puff_sniffles", true)
        set(v) = prefs.edit().putBoolean("puff_sniffles", v).apply()
    var puffAwaySleepovers: Boolean
        get() = prefs.getBoolean("puff_away_sleepovers", true)
        set(v) = prefs.edit().putBoolean("puff_away_sleepovers", v).apply()
    var puffHouseBuild: Boolean
        get() = prefs.getBoolean("puff_house_build", true)
        set(v) = prefs.edit().putBoolean("puff_house_build", v).apply()

    /** Break and wind-down nudges from laptop use. */
    var laptopBreak: Boolean
        get() = prefs.getBoolean("laptop_break", true)
        set(v) = prefs.edit().putBoolean("laptop_break", v).apply()

    var warmEvenings: Boolean
        get() = prefs.getBoolean("warm_evenings", true)
        set(v) = prefs.edit().putBoolean("warm_evenings", v).apply()

    var bedtimeHint: Boolean
        get() = prefs.getBoolean("bedtime_hint", true)
        set(v) = prefs.edit().putBoolean("bedtime_hint", v).apply()

    var roomSense: Boolean
        get() = prefs.getBoolean("room_sense", true)
        set(v) = prefs.edit().putBoolean("room_sense", v).apply()

    var diary: Boolean
        get() = prefs.getBoolean("diary", true)
        set(v) = prefs.edit().putBoolean("diary", v).apply()

    var dailyThing: Boolean
        get() = prefs.getBoolean("daily_thing", true)
        set(v) = prefs.edit().putBoolean("daily_thing", v).apply()

    var thingHistoryJson: String?
        get() = prefs.getString("thing_history", null)
        set(v) = prefs.edit().putString("thing_history", v).apply()

    var dailyThingJson: String?
        get() = prefs.getString("daily_thing_json", null)
        set(v) = prefs.edit().putString("daily_thing_json", v).apply()

    var desktopColorsJson: String?
        get() = prefs.getString("desktop_colors", null)
        set(v) = prefs.edit().putString("desktop_colors", v).apply()

    var desktopUpdatedMs: Long
        get() = prefs.getLong("desktop_updated_ms", 0L)
        set(v) = prefs.edit().putLong("desktop_updated_ms", v).apply()

    var wellnessWater: Boolean
        get() = prefs.getBoolean("wellness_water", false)
        set(v) = prefs.edit().putBoolean("wellness_water", v).apply()

    var wellnessStretch: Boolean
        get() = prefs.getBoolean("wellness_stretch", false)
        set(v) = prefs.edit().putBoolean("wellness_stretch", v).apply()

    var wellnessEyes: Boolean
        get() = prefs.getBoolean("wellness_eyes", false)
        set(v) = prefs.edit().putBoolean("wellness_eyes", v).apply()

    var wellnessStart: Int
        get() = prefs.getInt("wellness_start", 9)
        set(v) = prefs.edit().putInt("wellness_start", v).apply()

    var wellnessEnd: Int
        get() = prefs.getInt("wellness_end", 21)
        set(v) = prefs.edit().putInt("wellness_end", v).apply()

    var nightScene: String
        get() = prefs.getString("night_scene", "BLACK") ?: "BLACK"
        set(v) = prefs.edit().putString("night_scene", v).apply()

    var frameClock: String
        get() = prefs.getString("frame_clock", "CORNER") ?: "CORNER"
        set(v) = prefs.edit().putString("frame_clock", v).apply()

    var clockFace: String
        get() = prefs.getString("clock_face", "CLASSIC") ?: "CLASSIC"
        set(v) = prefs.edit().putString("clock_face", v).apply()

    var blobEnergy: Float
        get() = prefs.getFloat("blob_energy", 0.5f)
        set(v) = prefs.edit().putFloat("blob_energy", v).apply()

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
