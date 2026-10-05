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
import com.rusty.museecho.gadget.VoiceRecorder
import com.rusty.museecho.ui.MuseEchoApp
import com.rusty.museecho.ui.UiActions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val recorder = VoiceRecorder()
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
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTO, false)) autoLaunched = true
    }

    override fun onResume() {
        super.onResume()
        visible = true
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    override fun onDestroy() {
        recorder.cancel()
        super.onDestroy()
    }

    /**
     * When Muse pulled this app to the front (over Fully Kiosk's dashboard),
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

    private fun startTalking() {
        GadgetService.instance?.stopSpeaking()
        if (!recorder.start { runOnUiThread { stopTalking() } }) {
            GadgetState.setReply("Couldn't open the microphone")
            GadgetState.setTalk(Talk.ERROR)
            return
        }
        GadgetState.setHeard("")
        GadgetState.setReply("")
        GadgetState.setTalk(Talk.LISTENING)
    }

    private fun stopTalking() {
        if (GadgetState.talk.value != Talk.LISTENING) return
        val wav = recorder.stop()
        if (wav == null) {
            GadgetState.setTalk(Talk.IDLE)
            return
        }
        GadgetService.instance?.talk(null, wav)
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

        override fun micLevel(): Float = recorder.level

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
    }

    companion object {
        const val EXTRA_AUTO = "auto"
        const val RETREAT_AFTER_MS = 60_000L

        @Volatile
        var visible = false
    }
}
