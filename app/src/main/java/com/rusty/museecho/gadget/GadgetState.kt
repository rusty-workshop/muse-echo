package com.rusty.museecho.gadget

import android.graphics.Bitmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the screen shows when it isn't the idle face. */
sealed class Screen {
    data object Idle : Screen()
    data class Image(val bitmap: Bitmap, val caption: String) : Screen()
    data class Text(val title: String, val body: String) : Screen()
}

enum class Link { NOT_PAIRED, NO_TOKEN, CONNECTING, CONNECTED, RECONNECTING }

enum class Talk { IDLE, LISTENING, SENDING, WAITING, REPLIED, ERROR }

enum class BlePairing { OFF, ADVERTISING, CONNECTED, CONFIRMED, DONE, UNSUPPORTED, FAILED }

/**
 * Process-wide state shared by the foreground service and the activity. Both
 * live in the same process, so plain StateFlows are enough.
 */
object GadgetState {
    private val _link = MutableStateFlow(Link.NOT_PAIRED)
    val link: StateFlow<Link> = _link.asStateFlow()

    private val _linkDetail = MutableStateFlow("")
    val linkDetail: StateFlow<String> = _linkDetail.asStateFlow()

    private val _screen = MutableStateFlow<Screen>(Screen.Idle)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _talk = MutableStateFlow(Talk.IDLE)
    val talk: StateFlow<Talk> = _talk.asStateFlow()

    /** What Muse heard (the transcript of the last voice note or the typed text). */
    private val _heard = MutableStateFlow("")
    val heard: StateFlow<String> = _heard.asStateFlow()

    /** Muse's latest reply, streamed in as it arrives. */
    private val _reply = MutableStateFlow("")
    val reply: StateFlow<String> = _reply.asStateFlow()

    /** Pictures that came with the reply (rich replies). */
    private val _replyImages = MutableStateFlow<List<Bitmap>>(emptyList())
    val replyImages: StateFlow<List<Bitmap>> = _replyImages.asStateFlow()

    fun setReplyImages(images: List<Bitmap>) {
        _replyImages.value = images
    }

    /** Where the current caption came from, when it isn't an Echo turn ("From the server", "Muse"). */
    private val _replySource = MutableStateFlow<String?>(null)
    val replySource: StateFlow<String?> = _replySource.asStateFlow()

    fun setReplySource(source: String?) {
        _replySource.value = source
    }

    /** True while quiet hours are in effect. */
    private val _quiet = MutableStateFlow(false)
    val quiet: StateFlow<Boolean> = _quiet.asStateFlow()

    fun setQuiet(quiet: Boolean) {
        _quiet.value = quiet
    }

    /** A timer or alarm is ringing (the ring screen must stay bright). */
    private val _ringing = MutableStateFlow(false)
    val ringing: StateFlow<Boolean> = _ringing.asStateFlow()

    fun setRinging(on: Boolean) {
        _ringing.value = on
    }

    /** The current note ends by itself when you stop talking (wake word / follow-up). */
    private val _handsFree = MutableStateFlow(false)
    val handsFree: StateFlow<Boolean> = _handsFree.asStateFlow()

    fun setHandsFree(on: Boolean) {
        _handsFree.value = on
    }

    /** A follow-up listen (no wake word) is in progress. */
    private val _followUp = MutableStateFlow(false)
    val followUp: StateFlow<Boolean> = _followUp.asStateFlow()

    fun setFollowUp(on: Boolean) {
        _followUp.value = on
    }

    private val _ble = MutableStateFlow(BlePairing.OFF)
    val ble: StateFlow<BlePairing> = _ble.asStateFlow()

    private val _bleDetail = MutableStateFlow("")
    val bleDetail: StateFlow<String> = _bleDetail.asStateFlow()

    /** What the screen calls the Muse (set by Muse via display.set_profile, or in setup). */
    private val _museName = MutableStateFlow("Muse")
    val museName: StateFlow<String> = _museName.asStateFlow()

    fun setMuseName(name: String) {
        _museName.value = name.ifBlank { "Muse" }
    }

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    /** Bumped whenever the screen content should bring the app to the front. */
    private val _attention = MutableStateFlow(0)
    val attention: StateFlow<Int> = _attention.asStateFlow()

    fun setLink(link: Link, detail: String = "") {
        _link.value = link
        _linkDetail.value = detail
    }

    fun show(screen: Screen) {
        _screen.value = screen
        if (screen !is Screen.Idle) _attention.update { it + 1 }
    }

    fun setTalk(talk: Talk) {
        _talk.value = talk
    }

    fun setHeard(text: String) {
        _heard.value = text
    }

    fun setReply(text: String) {
        _reply.value = text
        if (text.isNotEmpty()) _attention.update { it + 1 }
    }

    fun setBle(state: BlePairing, detail: String = "") {
        _ble.value = state
        _bleDetail.value = detail
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(line: String) {
        android.util.Log.i("MuseEcho", line)
        val stamped = "${timeFormat.format(Date())}  $line"
        _log.update { (it + stamped).takeLast(200) }
    }
}
