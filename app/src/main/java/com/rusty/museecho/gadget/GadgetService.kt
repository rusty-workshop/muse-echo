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

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(filesDir)
        importDroppedFiles()
        identity = store.loadOrCreateIdentity()
        val ua = "musegadget/${BuildConfig.VERSION_NAME} (Echo Show 5; Android ${Build.VERSION.RELEASE}) museecho"
        api = MuseApi(ua, log = GadgetState::log)
        speaker = Speaker(this)
        commands = EchoCommands(this, speaker, ::bringToFront)
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

    private fun bringToFront() {
        if (!Prefs(this).comeToFront || MainActivity.visible) return
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
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
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
}
