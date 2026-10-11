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
    WAVE(2000), STARTLE(1400), DIZZY(2600), NUDGE(3000), COVER_EARS(2200), LEAN_IN(2000),
    TEAR_PAGE(1800), TUCK_IN(3000),
}

/** Something the blob holds up (blob.hold, or its own forecast props). */
enum class HeldItem { NONE, PACKAGE, UMBRELLA, LETTER, GIFT, COFFEE, HEART, STAR, BALLOON, CAKE, SUNGLASSES, SUITCASE, CAMERA, BAG, CALENDAR, NOTE, SERVER, SUN, HOURGLASS }

data class Held(val item: HeldItem, val text: String, val untilMs: Long)

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
        if (_speaking.value && !on) speechEndedAt = System.currentTimeMillis()
        _speaking.value = on
    }

    /** When the Echo last stopped speaking (its own voice can echo into the wake word). */
    @Volatile
    var speechEndedAt = 0L

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

    private val _held = MutableStateFlow<Held?>(null)
    val held: StateFlow<Held?> = _held.asStateFlow()

    fun hold(item: HeldItem, text: String, minutes: Int) {
        _held.value = if (item == HeldItem.NONE) null else Held(item, text.trim(), System.currentTimeMillis() + minutes * 60_000L)
    }

    /** Muse is busy on the Latitude right now (an SSH session or a gadget command), per the echo-hub. */
    private val _museBusy = MutableStateFlow(false)
    val museBusy: StateFlow<Boolean> = _museBusy.asStateFlow()

    fun setMuseBusy(on: Boolean) {
        _museBusy.value = on
    }

    /** The room's lights are off (the Echo's light sensor, with some hysteresis). Null: no sensor. */
    private val _roomDark = MutableStateFlow<Boolean?>(null)
    val roomDark: StateFlow<Boolean?> = _roomDark.asStateFlow()

    fun setRoomDark(dark: Boolean) {
        _roomDark.value = dark
    }

    /** How lively the blob is, 0 (calm) to 1 (hyper); a setup slider. */
    private val _blobEnergy = MutableStateFlow(0.5f)
    val blobEnergy: StateFlow<Float> = _blobEnergy.asStateFlow()

    fun setBlobEnergy(v: Float) {
        _blobEnergy.value = v.coerceIn(0f, 1f)
    }

    /** The speaker is muted (media volume 0). */
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    fun setMuted(on: Boolean) {
        _muted.value = on
    }

    /** Active National Weather Service alerts here. */
    private val _alerts = MutableStateFlow<List<com.rusty.museecho.integrations.WeatherAlert>>(emptyList())
    val alerts: StateFlow<List<com.rusty.museecho.integrations.WeatherAlert>> = _alerts.asStateFlow()

    fun setAlerts(list: List<com.rusty.museecho.integrations.WeatherAlert>) {
        _alerts.value = list
    }

    /** Alerts whose banner was closed (the blob still frets about them while they last). */
    private val _dismissedAlerts = MutableStateFlow<Set<String>>(emptySet())
    val dismissedAlerts: StateFlow<Set<String>> = _dismissedAlerts.asStateFlow()

    fun setDismissedAlerts(ids: Set<String>) {
        _dismissedAlerts.value = ids
    }

    /** Said goodnight: tucked in under a blanket until the next touch. */
    private val _tuckedIn = MutableStateFlow(false)
    val tuckedIn: StateFlow<Boolean> = _tuckedIn.asStateFlow()

    fun setTuckedIn(on: Boolean) {
        _tuckedIn.value = on
    }

    /** Screen brightness the goodnight fade has reached (null: not fading). */
    private val _dim = MutableStateFlow<Float?>(null)
    val dim: StateFlow<Float?> = _dim.asStateFlow()

    fun setDim(level: Float?) {
        _dim.value = level
    }

    /** What the blob is holding up while it presents the morning brief. */
    private val _presenting = MutableStateFlow<HeldItem?>(null)
    val presenting: StateFlow<HeldItem?> = _presenting.asStateFlow()

    fun setPresenting(item: HeldItem?) {
        _presenting.value = item
    }

    /** Big days Muse is counting down to (mirrored from the service's store). */
    private val _specialDays = MutableStateFlow<List<com.rusty.museecho.integrations.SpecialDay>>(emptyList())
    val specialDays: StateFlow<List<com.rusty.museecho.integrations.SpecialDay>> = _specialDays.asStateFlow()

    fun setSpecialDays(list: List<com.rusty.museecho.integrations.SpecialDay>) {
        _specialDays.value = list
    }

    /** Today's reminders from Muse (mirrored from the service's store). */
    private val _reminders = MutableStateFlow(com.rusty.museecho.integrations.ReminderDay())
    val reminders: StateFlow<com.rusty.museecho.integrations.ReminderDay> = _reminders.asStateFlow()

    fun setReminders(day: com.rusty.museecho.integrations.ReminderDay) {
        _reminders.value = day
    }

    /** The internet answers (null: not checked yet). Wi-Fi can be up while this is false. */
    private val _internet = MutableStateFlow<Boolean?>(null)
    val internet: StateFlow<Boolean?> = _internet.asStateFlow()

    fun setInternet(on: Boolean?) {
        _internet.value = on
    }

    /** When to leave for today's events: "title|time" to minutes after midnight. */
    private val _leaveBy = MutableStateFlow<Map<String, Int>>(emptyMap())
    val leaveBy: StateFlow<Map<String, Int>> = _leaveBy.asStateFlow()

    fun setLeaveBy(map: Map<String, Int>) {
        _leaveBy.value = map
    }

    /** A turn the Echo started is in flight (its reply streams into the caption). */
    private val _turnActive = MutableStateFlow(false)
    val turnActive: StateFlow<Boolean> = _turnActive.asStateFlow()

    fun setTurnActive(on: Boolean) {
        _turnActive.value = on
    }

    /** When the wake word was last heard (the blob's ears pop up). */
    @Volatile
    var earsAt = 0L

    /** The homelab page's data, fetched while it's on screen. */
    private val _homelab = MutableStateFlow<com.rusty.museecho.integrations.Homelab?>(null)
    val homelab: StateFlow<com.rusty.museecho.integrations.Homelab?> = _homelab.asStateFlow()

    fun setHomelab(h: com.rusty.museecho.integrations.Homelab?) {
        _homelab.value = h
    }

    /** Which idle page is showing: 0 clock, 1 agenda, 2 homelab. */
    private val _idlePage = MutableStateFlow(0)
    val idlePage: StateFlow<Int> = _idlePage.asStateFlow()

    fun setIdlePage(p: Int) {
        _idlePage.value = p
    }

    /** The cards under the blob (soon events, labels, signs), shown above the quick actions. */
    private val _blobCards = MutableStateFlow<List<Pair<String, Long>>>(emptyList())
    val blobCards: StateFlow<List<Pair<String, Long>>> = _blobCards.asStateFlow()

    fun setBlobCards(cards: List<Pair<String, Long>>) {
        _blobCards.value = cards
    }

    /** Little blob sounds asked for by the screen (a tap); the service plays them if allowed. */
    private val _sounds = kotlinx.coroutines.flow.MutableSharedFlow<BlobSound>(extraBufferCapacity = 4)
    val sounds: kotlinx.coroutines.flow.SharedFlow<BlobSound> = _sounds

    fun chirp(sound: BlobSound) {
        _sounds.tryEmit(sound)
    }

    /**
     * A short forced look from the settings page's blob playground: [kind] is accessory, prop,
     * color, weather, holiday or state, shown until [untilMs] without changing any setting.
     */
    data class BlobPreview(val kind: String, val value: String, val untilMs: Long)

    private val _preview = MutableStateFlow<BlobPreview?>(null)
    val preview: StateFlow<BlobPreview?> = _preview.asStateFlow()

    fun preview(kind: String, value: String, ms: Long) {
        _preview.value = BlobPreview(kind, value, System.currentTimeMillis() + ms)
    }

    /** Screen-cleaning mode: touches are ignored until this time. */
    private val _cleaningUntil = MutableStateFlow(0L)
    val cleaningUntil: StateFlow<Long> = _cleaningUntil.asStateFlow()

    fun startCleaning(seconds: Int) {
        _cleaningUntil.value = System.currentTimeMillis() + seconds * 1000L
    }

    fun cleaning(now: Long = System.currentTimeMillis()) = _cleaningUntil.value > now

    /** Room light from the sensor, lux (-1 until it reports). */
    @Volatile
    var lux = -1f

    /** Guest mode: personal things are hidden until this time (0 = off). */
    private val _guestUntil = MutableStateFlow(0L)
    val guestUntil: StateFlow<Long> = _guestUntil.asStateFlow()

    fun setGuestUntil(ms: Long) {
        _guestUntil.value = ms
    }

    fun guestMode(now: Long = System.currentTimeMillis()) = _guestUntil.value > now

    /** The laptop's battery and what it's playing, while it's home. */
    private val _laptopStatus = MutableStateFlow<com.rusty.museecho.integrations.LaptopStatus?>(null)
    val laptopStatus: StateFlow<com.rusty.museecho.integrations.LaptopStatus?> = _laptopStatus.asStateFlow()

    fun setLaptopStatus(s: com.rusty.museecho.integrations.LaptopStatus?) {
        _laptopStatus.value = s
    }

    /** Recent laptop notifications shown as chips (newest last). */
    private val _laptopNotes = MutableStateFlow<List<com.rusty.museecho.integrations.LaptopNotification>>(emptyList())
    val laptopNotes: StateFlow<List<com.rusty.museecho.integrations.LaptopNotification>> = _laptopNotes.asStateFlow()

    fun addLaptopNote(n: com.rusty.museecho.integrations.LaptopNotification) {
        _laptopNotes.update { (it + n).takeLast(3) }
    }

    fun dismissLaptopNote(id: Int) {
        _laptopNotes.update { list -> list.filterNot { it.id == id } }
    }

    /** Schoolwork due soon (mirrored from the service's store). */
    private val _assignments = MutableStateFlow<List<com.rusty.museecho.integrations.Assignment>>(emptyList())
    val assignments: StateFlow<List<com.rusty.museecho.integrations.Assignment>> = _assignments.asStateFlow()

    fun setAssignments(list: List<com.rusty.museecho.integrations.Assignment>) {
        _assignments.value = list
    }

    /** Photo-frame favourites and hidden photos (file names). */
    private val _frameFavs = MutableStateFlow<Set<String>>(emptySet())
    val frameFavs: StateFlow<Set<String>> = _frameFavs.asStateFlow()
    private val _frameHidden = MutableStateFlow<Set<String>>(emptySet())
    val frameHidden: StateFlow<Set<String>> = _frameHidden.asStateFlow()

    fun setFrameMarks(favs: Set<String>, hidden: Set<String>) {
        _frameFavs.value = favs
        _frameHidden.value = hidden
    }

    /** The photo slideshow's clock style (a FrameClock name). */
    private val _frameClock = MutableStateFlow("CORNER")
    val frameClock: StateFlow<String> = _frameClock.asStateFlow()

    fun setFrameClock(name: String) {
        _frameClock.value = name
    }

    /** The idle screen's clock style (a ClockFace name). */
    private val _clockFace = MutableStateFlow("CLASSIC")
    val clockFace: StateFlow<String> = _clockFace.asStateFlow()

    fun setClockFace(name: String) {
        _clockFace.value = name
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
        if (talk == Talk.LISTENING || talk == Talk.SENDING) lastChat = System.currentTimeMillis()
    }

    /** When someone last talked to Pixel through the Echo (the blob gets bored after a while). */
    @Volatile
    var lastChat = System.currentTimeMillis()

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
