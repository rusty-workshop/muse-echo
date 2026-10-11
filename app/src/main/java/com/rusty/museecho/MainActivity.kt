package com.rusty.museecho

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.rusty.museecho.gadget.GadgetService
import com.rusty.museecho.gadget.GadgetState
import com.rusty.museecho.gadget.Screen
import com.rusty.museecho.gadget.Talk
import com.rusty.museecho.ui.MuseEchoApp
import com.rusty.museecho.ui.UiActions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var autoLaunched = false
    private var pendingTalk = false
    private var retreatJob: Job? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingTalk) startTalking()
        else if (!granted) GadgetState.setReply("Microphone permission is needed for push-to-talk")
        pendingTalk = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GadgetService.start(this)
        autoLaunched = intent?.getBooleanExtra(EXTRA_AUTO, false) == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            MuseEchoApp(actions)
        }
        watchForRetreat()
        dimDuringQuietHours()
        autoBrightness()
        // Started at boot only to bring the service up from the foreground.
        if (intent?.getBooleanExtra(EXTRA_BOOT, false) == true) moveTaskToBack(true)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTO, false)) autoLaunched = true
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        // Screen-cleaning mode: every touch is ignored until it ends.
        if (GadgetState.cleaning()) return true
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
            val away = System.currentTimeMillis() - GadgetState.lastTouch
            GadgetState.lastTouch = System.currentTimeMillis()
            GadgetState.setAlertGlow(false)
            // Back after a while: the blob waves hello.
            if (away > 30 * 60_000L && !GadgetState.quiet.value) {
                GadgetState.react(com.rusty.museecho.gadget.Reaction.WAVE)
                // ...with a hello for the time of day.
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                GadgetState.say(when (hour) {
                    in 5..10 -> "Morning! ☀️"
                    in 11..16 -> "Welcome back!"
                    in 17..21 -> "Evening! 🌙"
                    else -> "Hi there! 👋"
                }, 6)
            }
            // Tucked in for the night: a touch gets it up again.
            if (GadgetState.tuckedIn.value) {
                GadgetState.setTuckedIn(false)
                GadgetState.setDim(null)
            }
            // The first tap on a sleeping screen only wakes it.
            if (GadgetState.asleep.value) {
                GadgetState.setAsleep(false)
                window.attributes = window.attributes.apply { screenBrightness = 0.05f }
                return true
            }
            // The photo frame handles its own touches (a tap ends it, press and hold for favourite / hide).
        }
        // A tap anywhere cuts Muse off mid-sentence.
        if (ev.action == android.view.MotionEvent.ACTION_DOWN && GadgetState.speaking.value) {
            GadgetService.instance?.stopSpeaking()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        current = java.lang.ref.WeakReference(this)
        // Remembered across a crash: if the process dies while this is on screen, the service reopens it.
        getSharedPreferences("museecho", MODE_PRIVATE).edit().putBoolean("ui_in_front", true).commit()
    }

    override fun onPause() {
        visible = false
        getSharedPreferences("museecho", MODE_PRIVATE).edit().putBoolean("ui_in_front", false).apply()
        super.onPause()
    }

    /**
     * When Muse pulled this app to the front (over whatever was on screen),
     * step back once the screen has been idle for a while.
     */
    private fun watchForRetreat() {
        lifecycleScope.launch {
            combine(GadgetState.screen, GadgetState.talk, GadgetState.reply) { screen, talk, reply ->
                // The reply is part of the key so a fresh reply restarts the countdown.
                (screen is Screen.Idle && (talk == Talk.IDLE || talk == Talk.REPLIED || talk == Talk.ERROR)) to reply
            }.collectLatest { (idle, _) ->
                if (!idle || !autoLaunched) return@collectLatest
                delay(RETREAT_AFTER_MS)
                autoLaunched = false
                moveTaskToBack(true)
            }
        }
    }

    /**
     * Overnight: dim this window when idle, and after the configured idle
     * minutes switch the backlight off entirely (the screen stays awake, so a
     * tap still lands). Anything happening on screen lights it again.
     */
    private fun dimDuringQuietHours() {
        lifecycleScope.launch {
            while (true) {
                val quiet = GadgetState.quiet.value
                val calm = GadgetState.screen.value is Screen.Idle && !GadgetState.ringing.value &&
                    (GadgetState.talk.value == Talk.IDLE || GadgetState.talk.value == Talk.REPLIED) &&
                    !GadgetState.speaking.value && !GadgetState.frameShowing.value
                val offAfter = com.rusty.museecho.gadget.Prefs(this@MainActivity).nightOffMin
                val idleMs = System.currentTimeMillis() - GadgetState.lastTouch
                val dim = GadgetState.dim.value
                // Asleep overnight after a while, or after saying goodnight once the fade has finished.
                val asleep = (quiet && calm && offAfter > 0 && idleMs >= offAfter * 60_000L) ||
                    (GadgetState.tuckedIn.value && dim != null && dim <= com.rusty.museecho.gadget.GadgetService.GOODNIGHT_DIM)
                if (asleep != GadgetState.asleep.value) GadgetState.setAsleep(asleep)
                // Overrides win; otherwise the auto-brightness ticker below follows the room.
                brightnessOverride = when {
                    // Asleep: backlight off, or the faintest glow for the night screensaver.
                    asleep -> if (GadgetState.nightScene.value == "BLACK") WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF else 0.02f
                    dim != null -> dim
                    quiet && calm -> 0.05f
                    else -> null
                }
                applyBrightness()
                // Wake promptly when something happens; otherwise check every few seconds.
                kotlinx.coroutines.withTimeoutOrNull(5_000) {
                    combine(GadgetState.talk, GadgetState.ringing, GadgetState.screen, GadgetState.quiet, GadgetState.dim) { a, b, c, d, e -> listOf(a, b, c, d, e) }
                        .drop(1).first()
                }
            }
        }
    }

    /** Set by the quiet-hours / goodnight logic; null means "normal", where auto brightness applies. */
    @Volatile
    private var brightnessOverride: Float? = null
    private var autoLevel = -1f

    private fun applyBrightness() {
        val p = com.rusty.museecho.gadget.Prefs(this)
        val target = brightnessOverride
            ?: if (p.autoBrightness && autoLevel >= 0f) autoLevel
            else if (!p.autoBrightness && p.manualBrightness >= 0f) p.manualBrightness
            else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (abs(window.attributes.screenBrightness - target) > 0.008f) {
            window.attributes = window.attributes.apply { screenBrightness = target }
        }
    }

    /**
     * Auto brightness from the light sensor: brightness follows the log of the room's lux
     * (people see light roughly logarithmically), eased over a couple of seconds so it never
     * jumps, and only stepping when the change is noticeable (the screen's own glow reaches
     * the sensor, so tiny wobbles are ignored).
     */
    private fun autoBrightness() {
        lifecycleScope.launch {
            var smoothedLux = -1f
            while (true) {
                delay(250)
                val lux = GadgetState.lux
                if (lux < 0f) {
                    if (brightnessOverride == null) applyBrightness()
                    continue
                }
                smoothedLux = if (smoothedLux < 0f) lux else smoothedLux + (lux - smoothedLux) * 0.15f
                val target = (0.06f + 0.94f * (kotlin.math.log10(smoothedLux + 1f) / kotlin.math.log10(400f))).coerceIn(0.06f, 1f)
                autoLevel = if (autoLevel < 0f) target else autoLevel + (target - autoLevel) * 0.12f
                if (brightnessOverride == null) applyBrightness()
            }
        }
    }

    private fun startTalking() {
        GadgetService.instance?.startTalking()
    }

    private fun stopTalking() {
        GadgetService.instance?.stopTalking()
    }

    private val actions = object : UiActions {
        override fun toggleTalk() {
            if (GadgetState.talk.value == Talk.LISTENING) {
                stopTalking()
                return
            }
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startTalking()
            } else {
                pendingTalk = true
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        override fun sendText(text: String) {
            GadgetService.instance?.talk(text, null)
        }

        override fun micLevel(): Float = GadgetService.instance?.micLevel() ?: 0f

        override fun dismissScreen() = GadgetState.show(Screen.Idle)

        override fun clearCaptions() {
            GadgetService.instance?.stopSpeaking()
            GadgetState.setHeard("")
            GadgetState.setReply("")
            GadgetState.setTalk(Talk.IDLE)
        }

        override fun pair() = GadgetService.start(this@MainActivity, GadgetService.ACTION_PAIR)
        override fun cancelPair() = GadgetService.start(this@MainActivity, GadgetService.ACTION_STOP_PAIR)
        override fun reconnect() = GadgetService.start(this@MainActivity, GadgetService.ACTION_RECONNECT)
        override fun unpair() {
            GadgetService.instance?.unpair()
        }

        override fun saveToken(token: String): String? =
            GadgetService.instance?.saveSdkToken(token) ?: "The gadget service isn't running yet"

        override fun hasToken(): Boolean = GadgetService.instance?.hasSdkToken() == true
        override fun isPaired(): Boolean = GadgetService.instance?.isPaired() == true
        override fun bleName(): String = GadgetService.instance?.identity()?.bleName ?: "MuseGadget…"
        override fun nodeId(): String = GadgetService.instance?.identity()?.nodeId ?: ""
        override fun dismissRinging() {
            GadgetService.instance?.dismissRinging()
        }

        override fun cancelTimer(id: String) {
            GadgetService.instance?.timers?.cancel(id)
        }

        override fun blePairingSupported(): Boolean =
            packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

        override fun setMuseName(name: String) {
            if (name.isBlank()) return
            com.rusty.museecho.gadget.Prefs(this@MainActivity).museName = name
            GadgetState.setMuseName(name)
        }

        override fun laptopHost(): String = GadgetService.instance?.laptop?.host ?: com.rusty.museecho.integrations.Laptop.DEFAULT_HOST
        override fun hasLaptopToken(): Boolean = GadgetService.instance?.laptop?.configured == true
        override fun saveLaptop(host: String, token: String?) {
            val laptop = GadgetService.instance?.laptop ?: return
            if (host.isNotBlank()) laptop.host = host
            if (token != null) laptop.token = token
        }

        private val none = kotlinx.coroutines.flow.MutableStateFlow<List<com.rusty.museecho.integrations.Countdown>>(emptyList())
        override fun timers() = GadgetService.instance?.timers?.items ?: none
        override fun ringing() = GadgetService.instance?.timers?.ringing ?: none

        override fun showImage(bitmap: android.graphics.Bitmap) = GadgetState.show(Screen.Image(bitmap, ""))
        override fun setMicOff(off: Boolean) {
            GadgetService.instance?.setMicOff(off)
        }

        override fun morningBrief() {
            GadgetService.instance?.morningBrief()
        }

        private val emptyNote = kotlinx.coroutines.flow.MutableStateFlow(com.rusty.museecho.integrations.PinnedNote())
        private val emptyGlance = kotlinx.coroutines.flow.MutableStateFlow(com.rusty.museecho.integrations.GlanceLine())
        private val emptyHistory = kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.rusty.museecho.integrations.Exchange>())
        override fun notes() = GadgetService.instance?.notes?.value ?: emptyNote
        override fun removeNote(item: String) {
            GadgetService.instance?.notes?.remove(listOf(item))
        }

        override fun glance() = GadgetService.instance?.glance?.value ?: emptyGlance
        override fun history() = GadgetService.instance?.history?.value ?: emptyHistory
        override fun togglePlayback() {
            GadgetService.instance?.togglePlayback()
        }

        override fun stopPlayback() {
            GadgetService.instance?.stopPlayback()
        }

        override fun saveWeatherLocation(lat: Double, lon: Double) {
            val prefs = com.rusty.museecho.gadget.Prefs(this@MainActivity)
            prefs.weatherLat = lat
            prefs.weatherLon = lon
            GadgetService.instance?.reloadWeather()
        }

        override fun voiceLevel() = com.rusty.museecho.gadget.Prefs(this@MainActivity).voiceLevel
        override fun setVoiceLevel(choice: String) {
            GadgetService.instance?.setVoiceLevel(choice)
        }

        private val idleVoice = kotlinx.coroutines.flow.MutableStateFlow<com.rusty.museecho.gadget.NeuralVoice.Status>(
            com.rusty.museecho.gadget.NeuralVoice.Status.Idle)
        override fun voiceStatus() = GadgetService.instance?.neural?.status ?: idleVoice
        override fun voiceInstalled(level: com.rusty.museecho.gadget.NeuralVoice.Level) =
            GadgetService.instance?.neural?.installed(level) == true
        override suspend fun voiceSpeedTest() = GadgetService.instance?.voiceSpeedTest() ?: "Not running"
        override suspend fun homelab() = GadgetService.instance?.hub?.homelab()

        override fun ask(prompt: String, label: String) {
            GadgetService.instance?.talk(prompt, null, label = label)
        }

        override suspend fun backupNow(): String? = GadgetService.instance?.chores?.backupNow() ?: "the service isn't running"

        override fun restoreBackup(onResult: (String) -> Unit) {
            GadgetService.instance?.restoreFromBackup(onResult) ?: onResult("the service isn't running")
        }

        override fun lastBackupMs() = GadgetService.instance?.chores?.lastBackupMs() ?: 0L

        override suspend fun syncPhotosNow() = GadgetService.instance?.chores?.syncPhotos() ?: 0

        override fun laptopMedia(command: String, intArg: Int) {
            GadgetService.instance?.laptopMedia(command, intArg)
        }

        override suspend fun forecast() = GadgetService.instance?.fetchForecast()

        override fun canSendToLaptop() = GadgetService.instance?.laptop?.configured == true

        override fun sendToLaptop(text: String, onResult: (Boolean) -> Unit) {
            val svc = GadgetService.instance ?: return onResult(false)
            lifecycleScope.launch { onResult(svc.sendToLaptop(text)) }
        }

        override fun addTimer(ms: Long): String? = GadgetService.instance?.addTimerByTouch(ms) ?: "the service isn't running"

        override fun addAlarm(time: String, days: List<String>): String? =
            GadgetService.instance?.addAlarmByTouch(time, days) ?: "the service isn't running"

        override fun setGuestMode(hours: Int) {
            GadgetService.instance?.setGuestMode(hours)
        }

        override fun pinToHomeScreen(): String {
            if (!androidx.core.content.pm.ShortcutManagerCompat.isRequestPinShortcutSupported(this@MainActivity)) {
                return "Your home-screen app doesn't support adding shortcuts; find Muse Echo in its app list instead."
            }
            val info = androidx.core.content.pm.ShortcutInfoCompat.Builder(this@MainActivity, "open-museecho")
                .setShortLabel("Muse Echo")
                .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(this@MainActivity, R.mipmap.ic_launcher))
                .setIntent(android.content.Intent(this@MainActivity, MainActivity::class.java).setAction(android.content.Intent.ACTION_MAIN))
                .build()
            androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(this@MainActivity, info, null)
            return "Asked your home screen to add Muse Echo; confirm it there."
        }

        override suspend fun testWakePhrase(phrase: String) = GadgetService.instance?.testWakePhrase(phrase) ?: "Not running"

        private val emptyAgenda = kotlinx.coroutines.flow.MutableStateFlow(com.rusty.museecho.integrations.AgendaDay())
        override fun agenda() = GadgetService.instance?.agenda?.value ?: emptyAgenda
        override fun installUpdate(onResult: (String) -> Unit) {
            GadgetService.instance?.installUpdate { m -> runOnUiThread { onResult(m) } } ?: onResult("Not running")
        }

        override suspend fun checkForUpdate() = GadgetService.instance?.checkForUpdate() ?: "Not running"
        override fun hubHost() = GadgetService.instance?.hub?.host ?: com.rusty.museecho.integrations.Hub.DEFAULT_HOST
        override fun setHubHost(host: String) {
            GadgetService.instance?.hub?.host = host
        }

        private val emptyActions = kotlinx.coroutines.flow.MutableStateFlow(com.rusty.museecho.integrations.QuickActions.DEFAULTS)
        override fun quickActions() = GadgetState.quickNow

        override fun allDayQuickActions() = GadgetService.instance?.quickActions?.value?.value.orEmpty()
        override fun runQuickAction(a: com.rusty.museecho.integrations.QuickAction) {
            GadgetService.instance?.quickActionReaction(a.label)
            GadgetService.instance?.talk(a.prompt, null, label = a.label)
        }

        override fun saveQuickActions(list: List<com.rusty.museecho.integrations.QuickAction>) {
            GadgetService.instance?.quickActions?.set(list)
        }

        override fun framePhotos(): List<java.io.File> = GadgetService.instance?.frame?.photos().orEmpty()
        override fun frameDelayMin() = com.rusty.museecho.gadget.Prefs(this@MainActivity).frameDelayMin
        override fun setFrameDelayMin(min: Int) {
            com.rusty.museecho.gadget.Prefs(this@MainActivity).frameDelayMin = min
        }

        override fun wakeSensitivity() = com.rusty.museecho.gadget.Prefs(this@MainActivity).wakeSensitivity
        override fun setWakeSensitivity(s: com.rusty.museecho.gadget.WakeWord.Sensitivity) {
            GadgetService.instance?.setWakeSensitivity(s)
        }

        override fun refreshQuiet() {
            GadgetService.instance?.refreshQuiet()
        }

        override fun wakeWordEnabled(): Boolean = com.rusty.museecho.gadget.Prefs(this@MainActivity).wakeWordEnabled
        override fun wakePhrase(): String = com.rusty.museecho.gadget.Prefs(this@MainActivity).wakePhrase
        override fun wakeModelPresent(): Boolean = GadgetService.instance?.wakeModelPresent() == true
        override fun setWakeWord(enabled: Boolean, phrase: String): String? {
            val prefs = com.rusty.museecho.gadget.Prefs(this@MainActivity)
            prefs.wakeWordEnabled = enabled
            if (phrase.isNotBlank()) prefs.wakePhrase = phrase
            if (enabled && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            return GadgetService.instance?.applyWakeWord()
        }
    }

    companion object {
        const val EXTRA_AUTO = "auto"
        const val EXTRA_BOOT = "boot"
        const val RETREAT_AFTER_MS = 60_000L

        @Volatile
        var visible = false

        @Volatile
        private var current: java.lang.ref.WeakReference<MainActivity>? = null

        /** A JPEG of what's on screen right now (for the laptop's live view), or null if the app isn't showing. */
        suspend fun capture(): ByteArray? {
            val activity = current?.get()?.takeIf { visible } ?: return null
            return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                activity.runOnUiThread {
                    val view = activity.window.decorView
                    if (view.width <= 0 || view.height <= 0) { cont.resume(null) {}; return@runOnUiThread }
                    val bmp = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                    android.view.PixelCopy.request(activity.window, bmp, { result ->
                        val jpeg = if (result == android.view.PixelCopy.SUCCESS) {
                            java.io.ByteArrayOutputStream().also { out ->
                                android.graphics.Bitmap.createScaledBitmap(bmp, view.width * 2 / 3, view.height * 2 / 3, true)
                                    .compress(android.graphics.Bitmap.CompressFormat.JPEG, 72, out)
                            }.toByteArray()
                        } else null
                        cont.resume(jpeg) {}
                    }, android.os.Handler(android.os.Looper.getMainLooper()))
                }
            }
        }
    }
}
