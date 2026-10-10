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
        // Started at boot only to bring the service up from the foreground.
        if (intent?.getBooleanExtra(EXTRA_BOOT, false) == true) moveTaskToBack(true)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTO, false)) autoLaunched = true
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
            GadgetState.lastTouch = System.currentTimeMillis()
            GadgetState.setAlertGlow(false)
            // The first tap on a sleeping screen only wakes it.
            if (GadgetState.asleep.value) {
                GadgetState.setAsleep(false)
                window.attributes = window.attributes.apply { screenBrightness = 0.05f }
                return true
            }
            // A tap anywhere ends the photo frame (and does nothing else).
            if (GadgetState.frameShowing.value) {
                GadgetState.showFrame(false)
                return true
            }
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
                val asleep = quiet && calm && offAfter > 0 && idleMs >= offAfter * 60_000L
                if (asleep != GadgetState.asleep.value) GadgetState.setAsleep(asleep)
                val brightness = when {
                    asleep -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
                    quiet && calm -> 0.05f
                    else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
                if (window.attributes.screenBrightness != brightness) {
                    window.attributes = window.attributes.apply { screenBrightness = brightness }
                }
                // Wake promptly when something happens; otherwise check every few seconds.
                kotlinx.coroutines.withTimeoutOrNull(5_000) {
                    combine(GadgetState.talk, GadgetState.ringing, GadgetState.screen, GadgetState.quiet) { a, b, c, d -> listOf(a, b, c, d) }
                        .drop(1).first()
                }
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
        override fun quickActions() = GadgetService.instance?.quickActions?.value ?: emptyActions
        override fun runQuickAction(a: com.rusty.museecho.integrations.QuickAction) {
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
    }
}
