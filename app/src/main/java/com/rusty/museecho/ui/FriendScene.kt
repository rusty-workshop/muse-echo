package com.rusty.museecho.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rusty.museecho.gadget.Accessory
import com.rusty.museecho.gadget.Decor
import com.rusty.museecho.gadget.Friend
import com.rusty.museecho.gadget.FriendScene
import com.rusty.museecho.gadget.GadgetService
import com.rusty.museecho.gadget.GadgetState
import com.rusty.museecho.gadget.Mood
import com.rusty.museecho.gadget.Personality
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The visiting friends on the Blob page: they walk in, sit beside the blob (one on each side when
 * two come), and act out the visit's activity (games, tea, a story, hide and seek...). [blob] is
 * the blob's centre and [half] its box's half-size, both in this layer's pixels.
 */
@Composable
internal fun FriendLayer(blob: Offset, half: Float, actions: UiActions) {
    val visit by GadgetState.friend.collectAsState()
    val says by GadgetState.friendSay.collectAsState()
    val scene by GadgetState.friendScene.collectAsState()
    val data by GadgetState.friends.collectAsState()
    val laptop by GadgetState.laptopStatus.collectAsState()
    val guestNow by GadgetState.guest.collectAsState()
    val live = visit
    var lastVisit by remember { mutableStateOf<com.rusty.museecho.gadget.FriendVisit?>(null) }
    var lastGuest by remember { mutableStateOf<Friend?>(null) }
    androidx.compose.runtime.SideEffect {
        if (live != null) { lastVisit = live; lastGuest = guestNow }
    }
    // During the walk out, the visit that just ended.
    val v = live ?: lastVisit
    val guest = if (live != null) guestNow else lastGuest
    // Two clocks: a slow one for what the layer shows (who's here, moods: rebuilding the layer is the
    // expensive part), and a smooth ~30 fps one that only moves and redraws things (read inside the
    // offset and draw lambdas, so it never rebuilds anything).
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val clock = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(v) {
        while (v != null) {
            kotlinx.coroutines.delay(250)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(v) {
        while (v != null) {
            // 25 fps, on the same beat as the blobs (see awaitBeat).
            awaitBeat(40)
            androidx.compose.runtime.withFrameMillis { }
            clock.longValue = System.currentTimeMillis()
        }
    }
    fun tAt(ms: Long) = (ms - (v?.startMs ?: ms)) / 1000f
    val here = live != null && now < live.untilMs
    val enter by animateFloatAsState(if (here) 1f else 0f, tween(1600), label = "friendsIn")
    if (enter <= 0.001f && live == null && lastVisit != null) androidx.compose.runtime.SideEffect { lastVisit = null; lastGuest = null }
    if (enter <= 0.001f || blob == Offset.Unspecified || half <= 0f) return
    val density = LocalDensity.current
    val u = half / 110f
    val dp = with(density) { 1.dp.toPx() }
    val t = (now - (v?.startMs ?: now)) / 1000f
    val friends = v?.friendIds.orEmpty().mapNotNull { id -> data.friends.firstOrNull { it.id == id } ?: guest?.takeIf { it.id == id } }
        .ifEmpty { listOf(Friend(id = "", color = v?.color ?: 0xFFFF9EC7)) }
    val act = v?.activity.orEmpty()
    val vibe = laptop?.takeIf { it.playing }?.musicVibe.orEmpty()

    // Where each friend wants to be; they glide there.
    val spots = friends.mapIndexed { i, f ->
        // Beyond the first two (group photo day), the rest stand further out, a bit smaller.
        val far = i >= 2
        // A back row: smaller, standing behind and above, peeking over the front row's shoulders.
        val size = 80 * dp * f.scale * (if (far) 0.62f else 1f)
        val side = if (i % 2 == 0) 1f else -1f
        val shyGap = if (f.p == Personality.SHY && f.closeness < 40) 16 * dp else 0f
        val home = if (far) Offset(blob.x + side * (58 + (i / 4) * 34) * dp, blob.y - 72 * dp)
            else Offset(blob.x + side * (84 * dp + shyGap + (size - 80 * dp) / 2), blob.y + 20 * dp + (80 * dp - size) / 4)
        val target = when {
            i == 0 && scene.hidingAt != null -> {
                val (x, top) = decorSpot(Decor.valueOf(scene.hidingAt!!))
                Offset(blob.x + x * u, blob.y + 92 * u - top * u - size * 0.22f)
            }
            scene.hug -> Offset(blob.x + side * 58 * dp, home.y)
            i == 0 && scene.apart -> Offset(blob.x + 140 * dp, home.y)
            else -> home
        }
        animateOffsetAsState(target, tween(900), label = "friend$i").value to size
    }

    // Props between them: the board, the ball, teacups, a book, a pillow, sparkles, hearts.
    Canvas(Modifier.fillMaxSize()) {
        val now = clock.longValue
        val t = tAt(now)
        val (f0, s0) = spots.first()
        val mid = Offset((blob.x + f0.x) / 2, blob.y + 74 * dp)
        scene.board?.let { drawBoard(it, mid, 42 * dp, Color(friends.first().color)) }
        if (scene.throwAt > 0) {
            val p = ((now - scene.throwAt) / 1200f).coerceIn(0f, 1f)
            // From the top of one head to the top of the other (the core is about half the box's radius).
            val puffHead = blob + Offset(0f, -half * 0.5f)
            val friendHead = f0 + Offset(0f, -s0 * 0.26f)
            val from = if (scene.toFriend) puffHead else friendHead
            val to = if (scene.toFriend) friendHead else puffHead
            val pos = from + (to - from) * p + Offset(0f, -60 * dp * sin(p * PI.toFloat()))
            drawCircle(if (scene.snow) Color.White else Color(0xFFFF7A8A), 6 * dp, pos)
            drawCircle(Color.White.copy(alpha = 0.6f), 2 * dp, pos + Offset(-2 * dp, -2 * dp))
        }
        if (scene.teacups) {
            drawCup(blob + Offset(34 * dp, 62 * dp), dp)
            drawCup(f0 + Offset(-18 * dp, s0 * 0.42f), dp)
        }
        if (scene.book) drawBook(Offset(mid.x, blob.y + 52 * dp), dp)
        scene.snakes?.let { (a, b, roll) -> drawSnakesBoard(Offset(mid.x, blob.y + 68 * dp), 54 * dp, a, b, roll, Color(friends.first().color)) }
        // A picnic: a checked blanket and a basket under everyone.
        if (scene.picnic) {
            val xs = spots.map { it.first.x } + blob.x
            val l = xs.min() - 50 * dp
            val r = xs.max() + 50 * dp
            val y = blob.y + 70 * dp
            val sq = 14 * dp
            var k = 0
            var x = l
            while (x < r) {
                for (row in 0..1) drawRect(if ((k + row) % 2 == 0) Color(0xFFE04848).copy(alpha = 0.75f) else Color.White.copy(alpha = 0.75f),
                    Offset(x, y + row * sq * 0.5f), Size(sq, sq * 0.5f))
                x += sq; k++
            }
            val bk = Offset(mid.x + 30 * dp, y - 14 * dp)
            drawRoundRect(Color(0xFFB08A5A), bk, Size(30 * dp, 18 * dp), CornerRadius(4 * dp))
            drawArc(Color(0xFF8A6A3A), 180f, 180f, false, bk + Offset(4 * dp, -10 * dp), Size(22 * dp, 20 * dp), style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f * dp))
        }
        // Making a card: a folded card and crayons.
        if (scene.crafting) {
            val c = Offset(mid.x, blob.y + 60 * dp)
            drawRect(Color(0xFFFFF3D6), Offset(c.x - 22 * dp, c.y - 14 * dp), Size(44 * dp, 28 * dp))
            drawLine(Color(0xFFD8C8A8), Offset(c.x, c.y - 14 * dp), Offset(c.x, c.y + 14 * dp), 1 * dp)
            drawHeartShape(Offset(c.x + 11 * dp, c.y), 6 * dp, Color(0xFFFF7A8A))
            for (k in 0..2) drawCircle(Color(0xFFFFF3B0).copy(alpha = 0.4f + 0.5f * abs(sin(t * 3f + k))), 1.5f * dp, Offset(c.x - 14 * dp + k * 6 * dp, c.y - 6 * dp + k * 4 * dp))
            listOf(Color(0xFF45D3C7), Color(0xFF8B6CFF), Color(0xFFFFC266)).forEachIndexed { k, col ->
                drawLine(col, Offset(c.x + 28 * dp, c.y + (k * 5 - 6) * dp), Offset(c.x + 44 * dp, c.y + (k * 5 - 9) * dp), 3 * dp)
            }
        }
        // Stargazing: the night sky over the room, tonight's moon, a telescope, shooting stars in a shower.
        if (scene.stargaze) drawStargazing(blob, half, f0, s0, t, dp)
        // Baking: a mixing bowl with a whisk going round.
        if (scene.bowl) {
            val b = Offset(mid.x, blob.y + 58 * dp)
            val wa = t * 6f
            drawLine(Color(0xFFBFC6D0), b + Offset(kotlin.math.cos(wa) * 6 * dp, -4 * dp), b + Offset(kotlin.math.cos(wa) * 10 * dp, -26 * dp), 2.5f * dp)
            drawArc(Color(0xFFE8DCC8), 0f, 180f, true, Offset(b.x - 18 * dp, b.y - 14 * dp), Size(36 * dp, 28 * dp))
            drawOval(Color(0xFFF5E6B8), Offset(b.x - 16 * dp, b.y - 3 * dp), Size(32 * dp, 6 * dp))
            if ((t % 1.2f) < 0.6f) drawCircle(Color.White.copy(alpha = 0.6f), 2 * dp, b + Offset(sin(t * 4f) * 10 * dp, -10 * dp - (t % 0.6f) * 20 * dp))
        }
        // Puddle jumping: a puddle under everyone, splashes on each hop, wellies on.
        if (scene.puddles) {
            drawOval(Color(0xFF5B8DB8).copy(alpha = 0.55f), Offset(blob.x - 170 * dp, blob.y + 78 * dp), Size(340 * dp, 26 * dp))
            (listOf(blob to half * 0.9f) + spots).forEachIndexed { i, (p, s) ->
                val hop = abs(sin(t * 2.4f + i))
                if (hop < 0.25f) for (k in -2..2) drawCircle(Color(0xFFA9D2F0).copy(alpha = 0.8f),
                    2.5f * dp, Offset(p.x + k * 9 * dp, blob.y + 80 * dp - (1 - hop * 4) * 14 * dp * (1 - abs(k) * 0.25f)))
                for (side in listOf(-1f, 1f)) drawRoundRect(Color(0xFFE6C229), Offset(p.x + side * s * 0.18f - 7 * dp, blob.y + 62 * dp), Size(14 * dp, 16 * dp), CornerRadius(4 * dp))
            }
        }
        // Kites on the wind, one per friend, strings down to their paws.
        if (scene.kites) spots.forEachIndexed { i, (p, s) ->
            val k = Offset(p.x + (if (i % 2 == 0) 40f else -40f) * dp + sin(t * 1.7f + i) * 18 * dp, blob.y - 150 * dp + cos(t * 1.3f + i) * 12 * dp)
            val col = Color(friends[i].color)
            val kite = Path().apply {
                moveTo(k.x, k.y - 18 * dp); lineTo(k.x + 13 * dp, k.y); lineTo(k.x, k.y + 22 * dp); lineTo(k.x - 13 * dp, k.y); close()
            }
            drawLine(Color.White.copy(alpha = 0.5f), k + Offset(0f, 22 * dp), p + Offset(0f, -s * 0.1f), 1.2f * dp)
            drawPath(kite, col)
            drawLine(Color.White.copy(alpha = 0.6f), k + Offset(0f, -18 * dp), k + Offset(0f, 22 * dp), 1f * dp)
            for (b in 1..3) drawCircle(col, 2.5f * dp, k + Offset(sin(t * 3f + b) * 5 * dp, (22 + b * 8) * dp))
        }
        // Market day: a little stall with a striped awning behind each friend.
        if (scene.market) spots.forEach { (p, s) -> drawStall(p + Offset(0f, s * 0.15f), s) }
        if (scene.pillow) drawPillow(f0 + Offset(0f, s0 * 0.26f), s0)
        if (scene.hug) {
            val h = Offset((blob.x + f0.x) / 2, blob.y - 40 * dp + sin(t * 3f) * 4 * dp)
            drawHeartShape(h, 12 * dp, Color(0xFFFF7A8A))
        }
        friends.forEachIndexed { i, f ->
            val (pos, size) = spots[i]
            if (f.p == Personality.SPARKLY) for (k in 0 until 5) {
                val a = t * 0.8f + k * 1.26f
                val tw = 0.4f + 0.6f * abs(sin(t * 2.5f + k))
                drawCircle(Color(0xFFFFF3B0).copy(alpha = 0.7f * tw), 2.2f * dp, pos + Offset(kotlin.math.cos(a) * size * 0.55f, sin(a) * size * 0.45f))
            }
            // Blushing: shy ones always a little, anyone just after a tap.
            val tapped = scene.tappedId == f.id && now < scene.tappedUntil
            if (tapped || f.p == Personality.SHY) {
                val a = if (tapped) 0.55f else 0.25f
                for (s in listOf(-1f, 1f)) drawOval(Color(0xFFFF7A9A).copy(alpha = a), pos + Offset(s * size * 0.2f - size * 0.06f, size * 0.03f), Size(size * 0.12f, size * 0.06f))
            }
        }
    }

    friends.forEachIndexed { i, f ->
        val (pos, size) = spots[i]
        val side = if (i % 2 == 0) 1f else -1f
        // Walking in from the side, then whatever the activity asks (worked out every frame, at layout).
        val baseX = pos.x + (1f - enter) * side * 260 * dp
        val throwing = scene.throwAt > 0
        fun place(): Offset {
            val t = tAt(clock.longValue)
            var x = baseX
            var y = pos.y
            when {
                // Playing catch: stay put so the ball lands on its head.
                throwing -> {}
                act == "tag" && t > 2f -> { x += sin(t * 1.7f + i) * size * 1.1f; y -= abs(sin(t * 5f)) * size * 0.25f }
                act == "dance" || (act == "party" && i == 0) -> when (vibe) {
                    "calm" -> x += sin(t * 1.3f + i) * 8 * dp
                    "hype" -> y -= (1 - abs(sin(t * PI.toFloat() * 2.3f))) * 14 * dp
                    else -> y -= abs(sin(t * PI.toFloat() * 2f + i)) * 10 * dp
                }
                // Idle bobbing (not while Rusty has asked everyone to hold still).
                GadgetState.holdingStill() -> {}
                f.p == Personality.BOUNCY && (t % 3f) < 0.4f -> y -= sin((t % 3f) / 0.4f * PI.toFloat()) * 12 * dp
                f.p == Personality.SLEEPY -> y += sin(t * 0.8f) * 2 * dp
            }
            return Offset(x, y)
        }
        val napping = act == "nap" && t > 3f || act == "sleepover" && GadgetState.quiet.value
        val mood = when {
            napping -> Mood.SLEEPY
            scene.tappedId == f.id && now < scene.tappedUntil -> Mood.WINK
            act == "squabble" && scene.apart -> Mood.SAD
            scene.sadId == f.id -> Mood.SAD
            act == "story" && t > 25f && i == 0 -> Mood.DROWSY
            f.p == Personality.SLEEPY -> Mood.DROWSY
            else -> Mood.HAPPY
        }
        val accessory = when {
            scene.costume -> Accessory.WITCH_HAT
            scene.party -> Accessory.PARTY_HAT
            scene.popcorn -> Accessory.POPCORN
            f.p == Personality.SPARKLY -> Accessory.CROWN
            f.p == Personality.SHY -> Accessory.BOW
            f.p == Personality.TINY -> Accessory.FLOWER
            else -> null
        }
        val gaze = when {
            act == "movie" -> Offset(-0.9f, 0f)
            scene.board != null || scene.book -> Offset(-side * 0.5f, 0.6f)
            else -> Offset(-side * 0.8f, 0f)
        }
        val hiding = i == 0 && scene.hidingAt != null
        val sizeDp = with(density) { size.toDp() }
        // Hiding: only the top of it peeks out from behind the room item.
        Box(
            Modifier.offset { place().let { (x, y) -> IntOffset((x - size / 2).toInt(), (y - size / 2).toInt()) } }
                .size(sizeDp, if (hiding) sizeDp * 0.48f else sizeDp).clipToBounds().graphicsLayer { alpha = enter }
                .clickable(remember { MutableInteractionSource() }, null) { GadgetService.instance?.life?.friends?.tapped(f.id) },
        ) {
            Blob(Modifier.requiredSize(sizeDp).align(Alignment.TopCenter), actions, mini = true, gaze = gaze, moodOverride = mood,
                tintOverride = Color(f.color), ownStyle = false, accessoryOverride = accessory)
        }
        // Its own speech bubble, and a name tag.
        (says[f.id] ?: says[""].takeIf { i == 0 })?.let { text ->
            Text(text, color = Palette.night, fontSize = 14.sp, maxLines = 2,
                modifier = Modifier.offset { place().let { (x, y) -> IntOffset((x - size * 0.4f).toInt(), (y - size * 1.05f - 8 * dp).toInt()) } }
                    .widthIn(max = 200.dp).background(Color(f.color).copy(alpha = 0.92f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp))
        }
        if (enter > 0.9f && !hiding && f.name.isNotBlank()) Text(f.name, color = Color(f.color), fontSize = 11.sp,
            modifier = Modifier.offset { place().let { (x, y) -> IntOffset((x - size * 0.25f).toInt(), (y + size * 0.42f).toInt()) } })
        // Its pet tags along at its feet, and runs over to Puff's pet to play now and then.
        if (f.petKind.isNotBlank() && !hiding) {
            val petHome = Offset(baseX + side * size * 0.45f, pos.y + size * 0.3f)
            val miso = Offset(blob.x + PET_SPOT.x * u, blob.y + 92 * u - 10 * u)
            val to by androidx.compose.animation.core.animateOffsetAsState(if (scene.petsPlay && i == 0) miso + Offset(14 * u, 0f) else petHome,
                androidx.compose.animation.core.tween(1200), label = "pet$i")
            val petsPlay = scene.petsPlay
            Text(com.rusty.museecho.gadget.FriendsBrain.petEmoji(f.petKind), fontSize = 16.sp,
                modifier = Modifier.graphicsLayer { alpha = enter }.offset {
                    val hop = if (petsPlay) kotlin.math.abs(sin(tAt(clock.longValue) * 6f)) * 6 * dp else 0f
                    IntOffset((to.x - 9 * dp).toInt(), (to.y - 10 * dp - hop).toInt())
                })
        }
    }
    // Sleepover: a little quilt over the friend's lower half (on top of it).
    if (scene.pillow && enter > 0.5f) Canvas(Modifier.fillMaxSize()) {
        val (f0, s0) = spots.first()
        drawQuilt(f0, s0, Color(friends.first().color), enter)
    }
    // The blanket fort: a draped blanket over everyone (see-through enough to see them), lit by a torch.
    if (scene.fort && enter > 0.3f) Canvas(Modifier.fillMaxSize()) {
        val t = tAt(clock.longValue)
        val xs = spots.map { it.first.x } + blob.x
        val left = xs.min() - 70 * dp
        val right = xs.max() + 70 * dp
        val floor = blob.y + 92 * u
        val apex = Offset(blob.x, blob.y - half * 1.15f)
        val cloth = Color(friends.first().color).let { androidx.compose.ui.graphics.lerp(it, Color(0xFF3A3F8F), 0.5f) }
        val tent = Path().apply {
            moveTo(left, floor); quadraticTo(left + 10 * dp, apex.y + 30 * dp, apex.x, apex.y)
            quadraticTo(right - 10 * dp, apex.y + 30 * dp, right, floor); close()
        }
        drawPath(tent, cloth.copy(alpha = 0.32f * enter))
        drawPath(tent, Color.White.copy(alpha = 0.35f * enter), style = androidx.compose.ui.graphics.drawscope.Stroke(2 * dp))
        // Patches and the chair-back poking up.
        listOf(0.3f to 0.5f, 0.7f to 0.45f, 0.5f to 0.75f).forEachIndexed { k, (fx, fy) ->
            drawRoundRect(listOf(Color(0xFFFFC266), Color(0xFFFF9EC7), Color(0xFF7FE0A8))[k].copy(alpha = 0.25f * enter),
                Offset(left + (right - left) * fx - 9 * dp, apex.y + (floor - apex.y) * fy - 7 * dp), Size(18 * dp, 14 * dp), CornerRadius(3 * dp))
        }
        drawRect(Color(0xFF7A5536).copy(alpha = 0.7f * enter), Offset(apex.x - 2 * dp, apex.y - 14 * dp), Size(4 * dp, 16 * dp))
        // The torch's beam, swinging a little.
        val hand = blob + Offset(-half * 0.44f, half * 0.36f)
        val aim = Offset(apex.x + sin(t * 0.7f) * 50 * dp, apex.y + 30 * dp)
        val dir = (aim - hand).let { it / it.getDistance() }
        val perp = Offset(-dir.y, dir.x)
        drawPath(Path().apply {
            moveTo(hand.x, hand.y); lineTo(aim.x + perp.x * 34 * dp, aim.y + perp.y * 34 * dp); lineTo(aim.x - perp.x * 34 * dp, aim.y - perp.y * 34 * dp); close()
        }, Brush.linearGradient(listOf(Color(0xFFFFE9A8).copy(alpha = 0.45f * enter), Color.Transparent), hand, aim))
    }
    // A puppet show: a little theatre around the performers, curtains open, the title on top.
    if (scene.puppets && enter > 0.3f) {
        val xs = spots.map { it.first.x }
        val l = xs.min() - spots.maxOf { it.second } * 0.75f
        val r = xs.max() + spots.maxOf { it.second } * 0.75f
        val top = spots.minOf { it.first.y - it.second * 0.9f }
        val bottom = spots.maxOf { it.first.y + it.second * 0.45f }
        Canvas(Modifier.fillMaxSize()) {
            val red = Color(0xFFB0303A)
            drawRect(Color(0xFF7A5536), Offset(l, bottom), Size(r - l, 14 * dp))
            drawRect(red, Offset(l - 6 * dp, top - 18 * dp), Size(r - l + 12 * dp, 18 * dp))
            for (k in 0..7) drawCircle(Color(0xFFFFC266), 2 * dp, Offset(l + (r - l) * k / 7, top - 2 * dp))
            for (side in listOf(0, 1)) {
                val x0 = if (side == 0) l else r
                val dir = if (side == 0) 1f else -1f
                drawPath(Path().apply {
                    moveTo(x0, top); lineTo(x0 + dir * 26 * dp, top); quadraticTo(x0 + dir * 10 * dp, (top + bottom) / 2, x0 + dir * 4 * dp, bottom); lineTo(x0, bottom); close()
                }, red.copy(alpha = 0.95f))
            }
        }
        if (scene.playTitle.isNotEmpty()) Text("🎭 ${scene.playTitle}", color = Color(0xFFFFE9A8), fontSize = 12.sp, maxLines = 1,
            modifier = Modifier.offset { IntOffset((l + 4 * dp).toInt(), (top - 17 * dp).toInt()) }.widthIn(max = with(density) { (r - l).toDp() }))
    }
    // Puff's surprise party: the lights are off while everyone hides.
    if (scene.lightsOff) Box(Modifier.fillMaxSize().background(Color(0xFF05040A).copy(alpha = 0.82f)))
    // Group photo: the camera's flash.
    if (scene.flashAt > 0 && now - scene.flashAt < 600) {
        val a = 1f - (now - scene.flashAt) / 600f
        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.8f * a)))
    }
    // The snack in the middle of tea time, as an emoji.
    if (scene.snack.isNotEmpty()) {
        val (f0, _) = spots.first()
        Text(scene.snack, fontSize = 20.sp, modifier = Modifier.offset { IntOffset(((blob.x + f0.x) / 2 - 10 * dp).toInt(), (blob.y + 60 * dp).toInt()) })
    }
}

private fun DrawScope.drawBoard(cells: String, c: Offset, size: Float, friendColor: Color) {
    val cell = size / 3
    val tl = c - Offset(size / 2, size / 2)
    drawRoundRect(Color(0xFF231C30).copy(alpha = 0.85f), tl - Offset(4f, 4f), Size(size + 8f, size + 8f), CornerRadius(6f))
    val line = Color.White.copy(alpha = 0.5f)
    for (k in 1..2) {
        drawLine(line, tl + Offset(cell * k, 0f), tl + Offset(cell * k, size), 2f)
        drawLine(line, tl + Offset(0f, cell * k), tl + Offset(size, cell * k), 2f)
    }
    cells.forEachIndexed { i, ch ->
        val cc = tl + Offset(cell * (i % 3) + cell / 2, cell * (i / 3) + cell / 2)
        val r = cell * 0.3f
        when (ch) {
            'X' -> {
                drawLine(Palette.violet, cc + Offset(-r, -r), cc + Offset(r, r), 3f)
                drawLine(Palette.violet, cc + Offset(-r, r), cc + Offset(r, -r), 3f)
            }
            'O' -> drawCircle(friendColor, r, cc, style = Stroke(3f))
        }
    }
}

private fun DrawScope.drawCup(at: Offset, dp: Float) {
    drawRoundRect(Color.White.copy(alpha = 0.8f), at + Offset(-7 * dp, -8 * dp), Size(14 * dp, 10 * dp), CornerRadius(3 * dp))
    drawArc(Color.White.copy(alpha = 0.8f), -90f, 180f, false, at + Offset(5 * dp, -6 * dp), Size(6 * dp, 6 * dp), style = Stroke(1.5f * dp))
    drawOval(Color(0xFFC08552), at + Offset(-6 * dp, -8 * dp), Size(12 * dp, 3 * dp))
}

private fun DrawScope.drawBook(at: Offset, dp: Float) {
    val l = Path().apply { moveTo(at.x, at.y); lineTo(at.x - 18 * dp, at.y - 4 * dp); lineTo(at.x - 18 * dp, at.y + 10 * dp); lineTo(at.x, at.y + 14 * dp); close() }
    val r = Path().apply { moveTo(at.x, at.y); lineTo(at.x + 18 * dp, at.y - 4 * dp); lineTo(at.x + 18 * dp, at.y + 10 * dp); lineTo(at.x, at.y + 14 * dp); close() }
    drawPath(l, Color(0xFFF7F1E3)); drawPath(r, Color(0xFFEDE6D3))
    for (k in 0..2) {
        drawLine(Color.Gray.copy(alpha = 0.5f), at + Offset(-15 * dp, (k * 3) * dp), at + Offset(-4 * dp, (1 + k * 3) * dp), 1f)
        drawLine(Color.Gray.copy(alpha = 0.5f), at + Offset(4 * dp, (1 + k * 3) * dp), at + Offset(15 * dp, (k * 3) * dp), 1f)
    }
}

private fun DrawScope.drawHeartShape(c: Offset, s: Float, color: Color) {
    drawCircle(color, s * 0.5f, c + Offset(-s * 0.45f, 0f))
    drawCircle(color, s * 0.5f, c + Offset(s * 0.45f, 0f))
    drawPath(Path().apply { moveTo(c.x - s * 0.95f, c.y + s * 0.15f); lineTo(c.x + s * 0.95f, c.y + s * 0.15f); lineTo(c.x, c.y + s * 1.1f); close() }, color)
}


/** A puffy pillow under a sleeping friend: pinched corners, a soft shade and a seam. */
private fun DrawScope.drawPillow(c: Offset, s: Float) {
    val w = s * 0.95f
    val h = s * 0.3f
    val path = Path().apply {
        moveTo(c.x - w / 2, c.y - h / 2)
        quadraticTo(c.x, c.y - h * 0.62f, c.x + w / 2, c.y - h / 2)
        quadraticTo(c.x + w * 0.44f, c.y, c.x + w / 2, c.y + h / 2)
        quadraticTo(c.x, c.y + h * 0.62f, c.x - w / 2, c.y + h / 2)
        quadraticTo(c.x - w * 0.44f, c.y, c.x - w / 2, c.y - h / 2)
        close()
    }
    drawPath(path, Color(0xFFE9E4F5))
    drawPath(path, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0x33000000)), c.y - h / 2, c.y + h / 2))
    drawLine(Color(0x22000000), Offset(c.x - w * 0.36f, c.y), Offset(c.x + w * 0.36f, c.y), 1.5f)
}

/** A small patchwork quilt over the lower half of a sleeping friend ([c] its centre, [s] its size). */
private fun DrawScope.drawQuilt(c: Offset, s: Float, friend: Color, alpha: Float) {
    val top = c.y + s * 0.08f
    val w = s * 0.86f
    val h = s * 0.32f
    val left = c.x - w / 2
    val quilt = androidx.compose.ui.graphics.lerp(friend, Color(0xFF3A3F8F), 0.55f)
    drawRoundRect(quilt.copy(alpha = alpha), Offset(left, top), Size(w, h), CornerRadius(s * 0.08f))
    // Patches and stitching.
    for (k in 1..2) drawLine(Color.White.copy(alpha = 0.18f * alpha), Offset(left + w * k / 3, top + s * 0.05f), Offset(left + w * k / 3, top + h), 1.5f)
    drawCircle(Color(0xFFFFC266).copy(alpha = 0.5f * alpha), s * 0.03f, Offset(left + w / 6, top + h * 0.6f))
    drawCircle(Color(0xFFFF9EC7).copy(alpha = 0.5f * alpha), s * 0.03f, Offset(left + w / 2, top + h * 0.6f))
    drawCircle(Color(0xFF7FE0A8).copy(alpha = 0.5f * alpha), s * 0.03f, Offset(left + w * 5 / 6, top + h * 0.6f))
    // The folded-over top edge.
    drawRoundRect(Color(0xFFF2EEFA).copy(alpha = alpha), Offset(left - s * 0.02f, top - s * 0.02f), Size(w + s * 0.04f, s * 0.07f), CornerRadius(s * 0.035f))
}


private fun DrawScope.drawStall(c: Offset, s: Float) {
    val w = s * 1.3f
    val top = c.y - s * 0.75f
    drawRect(Color(0xFF8A5A3C).copy(alpha = 0.7f), Offset(c.x - w / 2, c.y + s * 0.2f), Size(w, s * 0.12f))
    for (x in listOf(-1f, 1f)) drawLine(Color(0xFF8A5A3C).copy(alpha = 0.7f), Offset(c.x + x * w * 0.45f, top), Offset(c.x + x * w * 0.45f, c.y + s * 0.2f), 3f)
    val stripes = 6
    for (i in 0 until stripes) drawRect((if (i % 2 == 0) Color(0xFFFF7A8A) else Color.White).copy(alpha = 0.8f),
        Offset(c.x - w / 2 + i * w / stripes, top), Size(w / stripes, s * 0.16f))
}


/** Puff's out at a friend's: a little window onto it there, where Puff usually sits. */
@Composable
internal fun TripCard(actions: UiActions, modifier: Modifier) {
    val trip by GadgetState.trip.collectAsState()
    val data by GadgetState.friends.collectAsState()
    val t = trip ?: return
    val f = data.friends.firstOrNull { it.id == t.friendId } ?: return
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    androidx.compose.foundation.layout.Column(modifier.background(Color(f.color).copy(alpha = 0.12f), RoundedCornerShape(18.dp)).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🧳 At ${f.shownName}'s · back soon!", color = Palette.ink, fontSize = 13.sp, maxLines = 1)
        Box(Modifier.fillMaxSize().padding(top = 4.dp)) {
            Canvas(Modifier.fillMaxSize()) { drawInterior(f, Color(f.color), hour) }
            androidx.compose.foundation.layout.Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
                Blob(Modifier.size(64.dp), actions, mini = true, moodOverride = Mood.HAPPY, gaze = Offset(0.8f, 0f))
                Blob(Modifier.size((56 * f.p.scale).dp), actions, mini = true, moodOverride = Mood.HAPPY, gaze = Offset(-0.8f, 0f),
                    tintOverride = Color(f.color), ownStyle = false)
            }
        }
    }
}

/** Stargazing: a dark sky over the room with twinkling stars, the real moon phase, a telescope, and shooting stars on shower nights. */
private fun DrawScope.drawStargazing(blob: Offset, half: Float, friend: Offset, fs: Float, t: Float, dp: Float) {
    val top = blob.y - half * 1.6f
    val skyRect = Size(half * 4.2f, half * 1.25f)
    val skyAt = Offset(blob.x - skyRect.width / 2, top)
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF0B1030).copy(alpha = 0.85f), Color(0xFF1B2050).copy(alpha = 0f)), skyAt.y, skyAt.y + skyRect.height),
        skyAt, skyRect, CornerRadius(30 * dp))
    for (i in 0 until 28) {
        val p = skyAt + Offset((i * 0.618034f * 997f) % skyRect.width, (i * 0.414214f * 613f) % (skyRect.height * 0.75f))
        drawCircle(Color.White.copy(alpha = 0.35f + 0.35f * abs(sin(t * (0.5f + (i % 5) * 0.2f) + i))), if (i % 7 == 0) 2f * dp else 1.2f * dp, p)
    }
    val today = java.time.LocalDate.now()
    val age = com.rusty.museecho.gadget.FriendsBrain.moonAge(today).toFloat()
    val m = skyAt + Offset(skyRect.width * 0.82f, skyRect.height * 0.25f)
    val r = 12 * dp
    drawCircle(Color(0xFFF2ECD0), r, m)
    // The shadow slides across: from the right side's light growing (waxing) to shrinking (waning).
    val lit = if (age < 14.77f) age / 14.77f else (29.53f - age) / 14.77f
    val shift = (if (age < 14.77f) -1f else 1f) * r * 2f * lit
    if (lit < 0.97f) drawCircle(Color(0xFF0B1030), r * 1.02f, m + Offset(shift, 0f))
    if (com.rusty.museecho.gadget.FriendsBrain.meteorShower(today) != null) {
        val ph = (t % 4f) / 4f
        if (ph < 0.3f) {
            val s0 = skyAt + Offset(skyRect.width * (0.2f + 0.4f * ((t / 4f).toInt() % 3) / 3f), skyRect.height * 0.1f)
            val head = s0 + Offset(ph / 0.3f * 90 * dp, ph / 0.3f * 30 * dp)
            drawLine(Brush.linearGradient(listOf(Color.Transparent, Color.White), s0, head), s0, head, 2 * dp)
        }
    }
    // A telescope on its tripod beside the friend, pointing up at the sky.
    val base = friend + Offset(fs * 0.55f, fs * 0.42f)
    val legTop = base + Offset(0f, -fs * 0.45f)
    for (k in listOf(-1f, 0f, 1f)) drawLine(Color(0xFF8A7A6A), legTop, base + Offset(k * fs * 0.18f, 0f), 2 * dp)
    drawLine(Color(0xFFD8D0E8), legTop + Offset(-fs * 0.2f, fs * 0.08f), legTop + Offset(fs * 0.15f, -fs * 0.18f), 7 * dp)
    drawCircle(Color(0xFF9AB8FF), 3.5f * dp, legTop + Offset(fs * 0.15f, -fs * 0.18f))
}

/** A little snakes-and-ladders board (5 x 6), the two tokens, and the last roll on a die. */
private fun DrawScope.drawSnakesBoard(c: Offset, size: Float, puff: Int, friend: Int, roll: Int, friendColor: Color) {
    val cols = 6
    val rows = 5
    val cell = size / cols
    val h = cell * rows
    val tl = c - Offset(size / 2, h / 2)
    for (r in 0 until rows) for (k in 0 until cols) drawRect(if ((r + k) % 2 == 0) Color(0xFFFFF3D6) else Color(0xFFBFE6F5), tl + Offset(k * cell, r * cell), Size(cell, cell))
    // Square n (1-30) snakes back and forth from the bottom left.
    fun at(n: Int): Offset {
        val i = (n - 1).coerceIn(0, 29)
        val row = i / cols
        val col = if (row % 2 == 0) i % cols else cols - 1 - i % cols
        return tl + Offset(col * cell + cell / 2, h - row * cell - cell / 2)
    }
    com.rusty.museecho.gadget.FriendsBrain.LADDERS.forEach { (a, b) ->
        val p = at(a); val q = at(b)
        for (o in listOf(-3f, 3f)) drawLine(Color(0xFF8A5A3C), p + Offset(o, 0f), q + Offset(o, 0f), 1.5f)
    }
    com.rusty.museecho.gadget.FriendsBrain.SNAKES.forEach { (a, b) ->
        val p = at(a); val q = at(b)
        val mid = (p + q) / 2f + Offset(cell * 0.4f, 0f)
        drawPath(Path().apply { moveTo(p.x, p.y); quadraticTo(mid.x, mid.y, q.x, q.y) }, Color(0xFF4FA060), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
        drawCircle(Color(0xFF4FA060), 3.5f, p)
    }
    drawCircle(Color(0xFF8B6CFF), cell * 0.28f, at(puff) + Offset(-cell * 0.12f, 0f))
    drawCircle(friendColor, cell * 0.28f, at(friend) + Offset(cell * 0.12f, 0f))
    if (roll > 0) {
        val die = tl + Offset(size + 8f, h - cell * 1.2f)
        drawRoundRect(Color.White, die, Size(cell, cell), CornerRadius(4f))
        val pips = mapOf(1 to listOf(4), 2 to listOf(0, 8), 3 to listOf(0, 4, 8), 4 to listOf(0, 2, 6, 8), 5 to listOf(0, 2, 4, 6, 8), 6 to listOf(0, 2, 3, 5, 6, 8))
        pips[roll]?.forEach { p -> drawCircle(Color(0xFF231C30), cell * 0.08f, die + Offset(cell * (0.25f + 0.25f * (p % 3)), cell * (0.25f + 0.25f * (p / 3)))) }
    }
}

/** Home page chips: a ticket while a puppet show's on, and today's holiday card from Puff and friends. */
@Composable
internal fun HomeEventChips(modifier: Modifier = Modifier) {
    val visit by GadgetState.friend.collectAsState()
    val scene by GadgetState.friendScene.collectAsState()
    val data by GadgetState.friends.collectAsState()
    val today = java.time.LocalDate.now().toString()
    val card = data.cards.lastOrNull { it.date == today }
    val show = visit?.activity == "puppetshow"
    if (!show && card == null) return
    androidx.compose.foundation.layout.Row(modifier, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        if (show) Text("🎟️ Puppet show" + if (scene.playTitle.isNotEmpty()) ": ${scene.playTitle}" else " starting!", color = Palette.night, fontSize = 13.sp, maxLines = 1,
            modifier = Modifier.background(Color(0xFFFFC266), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp))
        card?.let { c ->
            Text("💌 A ${c.holiday} card for you!", color = Palette.night, fontSize = 13.sp, maxLines = 1,
                modifier = Modifier.background(Color(0xFFFF9EC7), RoundedCornerShape(50))
                    .clickable { GadgetState.show(com.rusty.museecho.gadget.Screen.Text("💌 Happy ${c.holiday}!", "${c.text}\n\nLove from ${c.from}")) }
                    .padding(horizontal = 12.dp, vertical = 5.dp))
        }
    }
}
