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

/** The blob's face. Pixel can set one for a while (blob.mood); otherwise it follows what's going on. */
enum class Mood { NEUTRAL, HAPPY, CURIOUS, WORRIED, SLEEPY, DROWSY, SURPRISED, SAD, WINK }

/** One-off blob animations: from Pixel (blob.react), events, or its own idle antics. */
enum class Reaction(val ms: Long) {
    HOP(900), NOD(900), SHAKE(900), WINK(1100), CELEBRATE(2800),
    YAWN(2600), STRETCH(2000), HICCUP(1300), PEEK(2600), WANDER(6000),
    WAVE(2000), STARTLE(1400),
}

data class Bubble(val text: String, val untilMs: Long)

data class MoodSet(val mood: Mood, val untilMs: Long)

data class ReactionEvent(val reaction: Reaction, val atMs: Long)

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

    /** The Echo is speaking right now (the orb pulses with it). */
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    fun setSpeaking(on: Boolean) {
        _speaking.value = on
    }

    /** Mic switched off on screen: no wake word, no voice notes. */
    private val _micOff = MutableStateFlow(false)
    val micOff: StateFlow<Boolean> = _micOff.asStateFlow()

    fun setMicOff(off: Boolean) {
        _micOff.value = off
    }

    /** What audio.play_url is playing, if anything. */
    private val _nowPlaying = MutableStateFlow<String?>(null)
    val nowPlaying: StateFlow<String?> = _nowPlaying.asStateFlow()

    fun setNowPlaying(title: String?) {
        _nowPlaying.value = title
    }

    private val _weather = MutableStateFlow<com.rusty.museecho.integrations.WeatherNow?>(null)
    val weather: StateFlow<com.rusty.museecho.integrations.WeatherNow?> = _weather.asStateFlow()

    fun setWeather(w: com.rusty.museecho.integrations.WeatherNow?) {
        _weather.value = w
    }

    /** Homelab health from the Latitude's echo-hub (null until first fetched or if unreachable). */
    private val _server = MutableStateFlow<com.rusty.museecho.integrations.ServerStatus?>(null)
    val server: StateFlow<com.rusty.museecho.integrations.ServerStatus?> = _server.asStateFlow()

    fun setServer(s: com.rusty.museecho.integrations.ServerStatus?) {
        _server.value = s
    }

    /** Whether the server status pill is shown (a setup toggle). */
    private val _serverPill = MutableStateFlow(true)
    val serverPill: StateFlow<Boolean> = _serverPill.asStateFlow()

    fun setServerPill(on: Boolean) {
        _serverPill.value = on
    }

    /** A newer Muse Echo build waiting on the echo-hub. */
    private val _update = MutableStateFlow<com.rusty.museecho.integrations.Release?>(null)
    val update: StateFlow<com.rusty.museecho.integrations.Release?> = _update.asStateFlow()

    fun setUpdate(r: com.rusty.museecho.integrations.Release?) {
        _update.value = r
    }

    /** The photo-frame slideshow is showing (asked for now, or started by the idle timer). */
    private val _frameShowing = MutableStateFlow(false)
    val frameShowing: StateFlow<Boolean> = _frameShowing.asStateFlow()

    fun showFrame(on: Boolean) {
        _frameShowing.value = on
    }

    /** Overnight: backlight off and the screen black until a tap, alarm or alert. */
    private val _asleep = MutableStateFlow(false)
    val asleep: StateFlow<Boolean> = _asleep.asStateFlow()

    fun setAsleep(on: Boolean) {
        _asleep.value = on
    }

    /** Last time someone touched the screen (for the idle slideshow). */
    @Volatile
    var lastTouch = System.currentTimeMillis()

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

    private val _mood = MutableStateFlow<MoodSet?>(null)
    val mood: StateFlow<MoodSet?> = _mood.asStateFlow()

    /** Show [mood] for [seconds]; NEUTRAL clears it. */
    fun setMood(mood: Mood, seconds: Int) {
        _mood.value = if (mood == Mood.NEUTRAL) null else MoodSet(mood, System.currentTimeMillis() + seconds * 1000L)
    }

    private val _reaction = MutableStateFlow<ReactionEvent?>(null)
    val reaction: StateFlow<ReactionEvent?> = _reaction.asStateFlow()

    fun react(reaction: Reaction) {
        _reaction.value = ReactionEvent(reaction, System.currentTimeMillis())
    }

    /** Something arrived while nobody was looking; the blob glows until the screen is touched. */
    private val _alertGlow = MutableStateFlow(false)
    val alertGlow: StateFlow<Boolean> = _alertGlow.asStateFlow()

    fun setAlertGlow(on: Boolean) {
        _alertGlow.value = on
    }

    /** A silent speech bubble beside the blob (blob.say, or its morning check-in). */
    private val _bubble = MutableStateFlow<Bubble?>(null)
    val bubble: StateFlow<Bubble?> = _bubble.asStateFlow()

    fun say(text: String, seconds: Int) {
        _bubble.value = if (text.isBlank()) null else Bubble(text.trim(), System.currentTimeMillis() + seconds * 1000L)
    }

    private val _style = MutableStateFlow(BlobStyle())
    val style: StateFlow<BlobStyle> = _style.asStateFlow()

    fun setStyle(style: BlobStyle) {
        _style.value = style
    }

    /** The living-room Roku is playing something (polled while it's on). */
    private val _tvPlaying = MutableStateFlow(false)
    val tvPlaying: StateFlow<Boolean> = _tvPlaying.asStateFlow()

    fun setTvPlaying(on: Boolean) {
        _tvPlaying.value = on
    }

    /** A laptop.* or tv.* command Pixel is running right now (the blob looks over). */
    private val _workingOn = MutableStateFlow<String?>(null)
    val workingOn: StateFlow<String?> = _workingOn.asStateFlow()

    fun setWorkingOn(command: String?) {
        _workingOn.value = command
    }

    /** Loudness of music from audio.play_url, 0..1, or -1 when unknown. */
    @Volatile
    var musicLevel: () -> Float = { -1f }

    /** Loudness of the Echo's own voice, 0..1, or -1 when unknown (system TTS). */
    @Volatile
    var outputLevel: () -> Float = { -1f }

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
