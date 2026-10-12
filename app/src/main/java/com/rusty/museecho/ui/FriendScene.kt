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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
    val v = visit
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Smooth (20 fps) only while something's actually moving; otherwise a few times a second is plenty.
    val busyMotion = v?.activity in setOf("tag", "dance", "party", "blockparty", "danceclub", "catch", "snowball") ||
        scene.throwAt > 0 || scene.petsPlay || scene.hug || scene.apart || scene.hidingAt != null || scene.groupPhoto
    val fastTick = androidx.compose.runtime.rememberUpdatedState(busyMotion)
    LaunchedEffect(v) {
        while (v != null) {
            kotlinx.coroutines.delay(if (fastTick.value || System.currentTimeMillis() - v.startMs < 2_500) 50 else 250)
            now = System.currentTimeMillis()
        }
    }
    val here = v != null && now < v.untilMs
    val enter by animateFloatAsState(if (here) 1f else 0f, tween(1600), label = "friendsIn")
    if (enter <= 0.001f || blob == Offset.Unspecified || half <= 0f) return
    val density = LocalDensity.current
    val u = half / 110f
    val dp = with(density) { 1.dp.toPx() }
    val t = (now - (v?.startMs ?: now)) / 1000f
    val friends = v?.friendIds.orEmpty().mapNotNull { id -> data.friends.firstOrNull { it.id == id } }
        .ifEmpty { listOf(Friend(id = "", color = v?.color ?: 0xFFFF9EC7)) }
    val act = v?.activity.orEmpty()
    val vibe = laptop?.takeIf { it.playing }?.musicVibe.orEmpty()

    // Where each friend wants to be; they glide there.
    val spots = friends.mapIndexed { i, f ->
        // Beyond the first two (group photo day), the rest stand further out, a bit smaller.
        val far = i >= 2
        // A back row: smaller, standing behind and above, peeking over the front row's shoulders.
        val size = 80 * dp * f.p.scale * (if (far) 0.62f else 1f)
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
        // Walking in from the side, then whatever the activity asks.
        var x = pos.x + (1f - enter) * side * 260 * dp
        var y = pos.y
        when {
            // Playing catch: stay put so the ball lands on its head.
            scene.throwAt > 0 -> {}
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
            Modifier.offset { IntOffset((x - size / 2).toInt(), (y - size / 2).toInt()) }
                .size(sizeDp, if (hiding) sizeDp * 0.48f else sizeDp).clipToBounds()
                .clickable(remember { MutableInteractionSource() }, null) { GadgetService.instance?.life?.friends?.tapped(f.id) },
        ) {
            Blob(Modifier.requiredSize(sizeDp).align(Alignment.TopCenter), actions, mini = true, gaze = gaze, moodOverride = mood,
                tintOverride = Color(f.color), ownStyle = false, accessoryOverride = accessory)
        }
        // Its own speech bubble, and a name tag.
        (says[f.id] ?: says[""].takeIf { i == 0 })?.let { text ->
            Text(text, color = Palette.night, fontSize = 14.sp, maxLines = 2,
                modifier = Modifier.offset { IntOffset((x - size * 0.4f).toInt(), (y - size * 1.05f - 8 * dp).toInt()) }
                    .widthIn(max = 200.dp).background(Color(f.color).copy(alpha = 0.92f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp))
        }
        if (enter > 0.9f && !hiding && f.name.isNotBlank()) Text(f.name, color = Color(f.color), fontSize = 11.sp,
            modifier = Modifier.offset { IntOffset((x - size * 0.25f).toInt(), (y + size * 0.42f).toInt()) })
        // Its pet tags along at its feet, and runs over to Puff's pet to play now and then.
        if (f.petKind.isNotBlank() && !hiding) {
            val petHome = Offset(x + side * size * 0.45f, y + size * 0.3f)
            val miso = Offset(blob.x + PET_SPOT.x * u, blob.y + 92 * u - 10 * u)
            val to by androidx.compose.animation.core.animateOffsetAsState(if (scene.petsPlay && i == 0) miso + Offset(14 * u, 0f) else petHome,
                androidx.compose.animation.core.tween(1200), label = "pet$i")
            val hop = if (scene.petsPlay) kotlin.math.abs(sin(t * 6f)) * 6 * dp else 0f
            Text(com.rusty.museecho.gadget.FriendsBrain.petEmoji(f.petKind), fontSize = 16.sp,
                modifier = Modifier.offset { IntOffset((to.x - 9 * dp).toInt(), (to.y - 10 * dp - hop).toInt()) })
        }
    }
    // Sleepover: a little quilt over the friend's lower half (on top of it).
    if (scene.pillow && enter > 0.5f) Canvas(Modifier.fillMaxSize()) {
        val (f0, s0) = spots.first()
        drawQuilt(f0, s0, Color(friends.first().color), enter)
    }
    // Group photo: the camera's flash.
    if (scene.flashAt > 0 && now - scene.flashAt < 600) {
        val a = 1f - (now - scene.flashAt) / 600f
        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.8f * a)))
    }
    // The snack in the middle of tea time, as an emoji.
    if (scene.teacups && scene.snack.isNotEmpty()) {
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
