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
    private lateinit var wakeWord: WakeWord
    private var chime: android.media.Ringtone? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(filesDir)
        importDroppedFiles()
        identity = store.loadOrCreateIdentity()
        val ua = "musegadget/${BuildConfig.VERSION_NAME} (Echo Show 5; Android ${Build.VERSION.RELEASE}) museecho"
        api = MuseApi(ua, log = GadgetState::log)
        speaker = Speaker(this)
        timers = com.rusty.museecho.integrations.Timers(java.io.File(filesDir, "timers.json"))
        laptop = com.rusty.museecho.integrations.Laptop(this)
        roku = com.rusty.museecho.integrations.Roku(this)
        importDroppedSettings()
        GadgetState.setMuseName(Prefs(this).museName)
        val home = HomeCommands(this, roku, timers, laptop, ::bringToFront)
        commands = EchoCommands(this, speaker, ::bringToFront, home)
        runner = GadgetRunner(
            store = store,
            identity = identity,
            api = api,
            connector = OkHttpWsConnector(ua),
            displayName = Prefs(this).displayName,
            version = BuildConfig.VERSION_NAME,
            commands = commands.specs,
            runCommand = commands::run,
        )
        chat = ChatClient({ runner.current }, identity.nodeId)
        startInForeground()
        runnerJob = scope.launch { runner.run() }
        scope.launch { tickTimers() }
        wakeWord = WakeWord(
            modelDir = java.io.File(getExternalFilesDir(null), WakeWord.MODEL_DIR_NAME),
            onWake = { scope.launch { onWake() } },
            log = GadgetState::log,
        )
        applyWakeWord()
        scope.launch {
            GadgetState.link.collectLatest { updateNotification() }
        }
        GadgetState.log("gadget ${identity.nodeId} started")
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

    // -- Timers -----------------------------------------------------------------------

    private suspend fun tickTimers() {
        while (true) {
            val rang = timers.tick()
            if (rang.isNotEmpty()) {
                GadgetState.log("ringing: ${rang.joinToString { it.label }}")
                bringToFront(force = true)
                startChime()
                // Let the chime get going, then say what it's for.
                delay(1_500)
                speaker.speak(rang.joinToString(". ") { if (it.isAlarm) it.label else "${it.label} is done" })
                scope.launch {
                    delay(RING_MAX_MS)
                    if (timers.ringing.value.isNotEmpty()) timers.dismiss()
                }
            }
            if (timers.ringing.value.isEmpty()) stopChime()
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
    fun talk(text: String?, wav: ByteArray?) {
        turnJob?.cancel()
        turnJob = scope.launch {
            GadgetState.setTalk(Talk.SENDING)
            GadgetState.setHeard(text ?: "")
            GadgetState.setReply("")
            try {
                val reply = chat.turn(text, wav) { tracker ->
                    if (tracker.heard.isNotEmpty()) GadgetState.setHeard(tracker.heard)
                    GadgetState.setTalk(if (tracker.replyText.isEmpty()) Talk.WAITING else Talk.REPLIED)
                    GadgetState.setReply(tracker.replyText)
                }
                GadgetState.setReply(reply)
                GadgetState.setTalk(Talk.REPLIED)
                if (Prefs(this@GadgetService).speakReplies && reply.isNotBlank()) speaker.speak(reply)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                GadgetState.log("chat failed: ${e.message}")
                GadgetState.setReply(e.message ?: "Something went wrong")
                GadgetState.setTalk(Talk.ERROR)
            }
        }
    }

    fun stopSpeaking() = speaker.stop()

    // -- Voice notes (push-to-talk and wake word) ---------------------------------------

    private fun micAllowed() = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Start a voice note. [handsFree] ends it on silence (after the wake word). */
    fun startTalking(handsFree: Boolean = false): Boolean {
        if (GadgetState.talk.value == Talk.LISTENING) return true
        if (!micAllowed()) {
            GadgetState.setReply("Microphone permission is needed to talk")
            GadgetState.setTalk(Talk.ERROR)
            return false
        }
        speaker.stop()
        wakeWord.pause()
        val ok = recorder.start(endOnSilence = handsFree) { scope.launch { stopTalking() } }
        if (!ok) {
            wakeWord.resume()
            GadgetState.setReply("Couldn't open the microphone")
            GadgetState.setTalk(Talk.ERROR)
            return false
        }
        GadgetState.setHeard("")
        GadgetState.setReply("")
        GadgetState.setTalk(Talk.LISTENING)
        return true
    }

    fun stopTalking() {
        if (GadgetState.talk.value != Talk.LISTENING) return
        val wav = recorder.stop()
        wakeWord.resume()
        if (wav == null) {
            GadgetState.setTalk(Talk.IDLE)
            return
        }
        talk(null, wav)
    }

    fun micLevel(): Float = recorder.level

    private fun onWake() {
        GadgetState.log("wake word heard")
        if (GadgetState.link.value != Link.CONNECTED) {
            wakeWord.resume()
            return
        }
        bringToFront(force = true)
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
        if (!prefs.wakeWordEnabled) {
            wakeWord.stop()
            return null
        }
        if (!micAllowed()) return "Microphone permission is needed for the wake word"
        val error = wakeWord.start(prefs.wakePhrase)
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

    var wakePhrase: String
        get() = prefs.getString("wake_phrase", null) ?: "hey muse"
        set(v) = prefs.edit().putString("wake_phrase", v.trim().lowercase()).apply()
}
