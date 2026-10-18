package com.rusty.museecho.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toAndroidRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rusty.museecho.gadget.AlbumShot
import com.rusty.museecho.gadget.GadgetService
import com.rusty.museecho.gadget.GadgetState
import com.rusty.museecho.gadget.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything about the blob in one place: the blob itself in its room (where its friend visits),
 * and tabs for its diary and photo album.
 */
@Composable
internal fun BlobPage(actions: UiActions) {
    val diaryOn by GadgetState.diaryOn.collectAsState()
    val guest = rememberGuest()
    var tab by remember { mutableStateOf(if (diaryOn && !guest) "diary" else "album") }
    Row(Modifier.fillMaxSize()) {
        // Its room, with the blob in the middle (a full-size copy of the one on the clock).
        var center by remember { mutableStateOf(Offset.Unspecified) }
        var half by remember { mutableStateOf(0f) }
        Box(Modifier.width(360.dp).fillMaxHeight().padding(top = 56.dp, bottom = 78.dp)
            .onGloballyPositioned { GadgetState.blobBounds[com.rusty.museecho.gadget.ROOM_BOUNDS] = it.boundsInWindow().toAndroidRect() }) {
            BlobRoom(center, half, Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.Center).padding(end = 20.dp).size(200.dp).onGloballyPositioned {
                half = it.size.width / 2f
                // In the room's own space (the padded box both share).
                center = (it.parentLayoutCoordinates?.localPositionOf(it, Offset.Zero) ?: Offset.Zero) +
                    Offset(it.size.width / 2f, it.size.height / 2f)
                GadgetState.blobBounds[8] = it.boundsInWindow().toAndroidRect()
            }) {
                val away = GadgetState.trip.collectAsState().value != null
                Blob(Modifier.fillMaxSize().graphicsLayer { alpha = if (away) 0f else 1f }, actions, follower = true)
                if (away) TripCard(actions, Modifier.fillMaxSize())
            }
            FriendLayer(center, half, actions)
            ShowLayer(center, half)
            TreasureTapLayer(center, half)
            BookshelfTapTarget(center, half)
            WindowTapTarget(center, half)
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(top = 64.dp, end = 40.dp, bottom = 84.dp)) {
            val blobName by GadgetState.blobName.collectAsState()
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Text(blobName.ifBlank { "Blob" }, color = Palette.amber, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(14.dp))
                if (diaryOn && !guest) Tab("📔 Diary", tab == "diary") { tab = "diary" }
                Spacer(Modifier.width(6.dp))
                Tab("📸 Album", tab == "album") { tab = "album" }
                Spacer(Modifier.width(6.dp))
                Tab("👯 Friends", tab == "friends") { tab = "friends" }
                Spacer(Modifier.width(6.dp))
                val unread = GadgetState.friends.collectAsState().value.letters.count { !it.read }
                Tab(if (unread > 0) "✉️ $unread" else "✉️", tab == "mail") { tab = "mail" }
                Spacer(Modifier.width(6.dp))
                Tab("🎮", tab == "play") { tab = "play" }
                Spacer(Modifier.width(6.dp))
                Tab("👗", tab == "wardrobe") { tab = "wardrobe" }
                Spacer(Modifier.width(6.dp))
                Tab("🏅", tab == "stickers") { tab = "stickers" }
                Spacer(Modifier.width(10.dp))
                // Not a tab: Puff answers in its bubble.
                Text("💬 How are you?", color = Palette.night, fontSize = 14.sp,
                    modifier = Modifier.background(Palette.teal, RoundedCornerShape(50)).clickable { GadgetService.instance?.askPuff() }
                        .padding(horizontal = 12.dp, vertical = 6.dp))
            }
            Spacer(Modifier.height(8.dp))
            when {
                tab == "diary" && diaryOn && !guest -> DiaryAndWeather(Modifier.fillMaxSize())
                tab == "friends" -> FriendsContent(actions, Modifier.fillMaxSize())
                tab == "mail" -> MailContent(Modifier.fillMaxSize())
                tab == "play" -> PlayContent(Modifier.fillMaxSize())
                tab == "wardrobe" -> WardrobeContent(Modifier.fillMaxSize())
                tab == "stickers" -> StickerBook(Modifier.fillMaxSize())
                else -> AlbumContent(actions, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun Tab(label: String, on: Boolean, onClick: () -> Unit) {
    Text(label, color = if (on) Palette.night else Palette.ink, fontSize = 14.sp,
        modifier = Modifier.background(if (on) Palette.amber else Palette.card, RoundedCornerShape(50)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp))
}

/** Thumbnails of the blob's daily snapshots, newest first; tap one to see it big. */
@Composable
private fun AlbumContent(actions: UiActions, modifier: Modifier) {
    var friendsOnly by remember { mutableStateOf(false) }
    Column(modifier) {
        Row(Modifier.padding(bottom = 6.dp)) {
            Tab("Daily", !friendsOnly) { friendsOnly = false }
            Spacer(Modifier.width(6.dp))
            Tab("With friends", friendsOnly) { friendsOnly = true }
        }
        if (friendsOnly) {
            val shots by GadgetState.friendAlbum.collectAsState()
            if (shots.isEmpty()) Text("Every friend visit gets a picture here (when the Blob page is on screen).", color = Palette.muted, fontSize = 15.sp)
            else ShotGrid(shots, actions, Modifier.fillMaxSize())
        } else DailyAlbum(actions, Modifier.fillMaxSize())
    }
}

@Composable
private fun ShotGrid(shots: List<AlbumShot>, actions: UiActions, modifier: Modifier) {
    LazyVerticalGrid(GridCells.Fixed(3), modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(shots.reversed(), key = { it.file }) { shot -> AlbumThumb(shot, actions) }
    }
}

@Composable
private fun DailyAlbum(actions: UiActions, modifier: Modifier) {
    val shots by GadgetState.album.collectAsState()
    if (shots.isEmpty()) {
        Text("No pictures yet. Once a day the Echo snaps ${GadgetState.blobCalled()} doing something cute (a friend visit, a new hat, " +
            "something new in its room), or just hanging out in the afternoon.", color = Palette.muted, fontSize = 15.sp, modifier = modifier)
        return
    }
    ShotGrid(shots, actions, modifier)
}

@Composable
private fun AlbumThumb(shot: AlbumShot, actions: UiActions) {
    var img by remember(shot.file, shot.atMs) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(shot.file, shot.atMs) {
        img = withContext(Dispatchers.IO) {
            actions.albumFile(shot)?.let { f ->
                runCatching {
                    android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    val date = runCatching {
        java.time.LocalDate.parse(shot.date).format(java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.US))
    }.getOrDefault(shot.date)
    Column(Modifier.clickable {
        actions.albumFile(shot)?.let { f ->
            android.graphics.BitmapFactory.decodeFile(f.path)?.let { GadgetState.show(Screen.Image(it, "$date · ${shot.caption}")) }
        }
    }) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).let { if (img == null) it.shimmer() else it.glass(RoundedCornerShape(10.dp)) }) {
            img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Text("$date · ${shot.caption}", color = Palette.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The little blob on the other swipe pages: same mood and colour, peeking up at the page. */
@Composable
internal fun PageBlob(modifier: Modifier, actions: UiActions) {
    Blob(modifier.size(64.dp), actions, mini = true, gaze = Offset(-0.6f, -0.7f))
}

/** The blob's friends: a card each; tap one for its diary, postcards and pictures. */
@Composable
private fun FriendsContent(actions: UiActions, modifier: Modifier) {
    val data by GadgetState.friends.collectAsState()
    var open by remember { mutableStateOf<String?>(null) }
    val friend = data.friends.firstOrNull { it.id == open }
    if (friend != null) {
        FriendDetail(friend, data, actions, modifier) { open = null }
        return
    }
    if (data.friends.isEmpty()) {
        Text("No friends yet. One will turn up soon!", color = Palette.muted, fontSize = 15.sp, modifier = modifier)
        return
    }
    androidx.compose.foundation.lazy.LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The adventure they're in the middle of.
        data.story?.takeIf { !it.done }?.let { s ->
            item {
                Column(Modifier.fillMaxWidth().background(Palette.amber.copy(alpha = 0.15f), RoundedCornerShape(14.dp)).padding(10.dp)) {
                    Text("📖 ${s.title}", color = Palette.amber, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Chapter ${s.at} of ${s.chapters.size}" + if (s.at > 0) ": \"${s.chapters[s.at - 1].line}\"" else " (starts on the next visit)",
                        color = Palette.ink, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        items(data.friends.size) { i ->
            val f = data.friends[i]
            Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(14.dp)).clickable { open = f.id }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Blob(Modifier.size(52.dp), actions, mini = true, moodOverride = com.rusty.museecho.gadget.Mood.HAPPY,
                    tintOverride = androidx.compose.ui.graphics.Color(f.color), ownStyle = false)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(f.shownName, color = androidx.compose.ui.graphics.Color(f.color), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(friendLine(f), color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (f.bio.isNotEmpty()) Text(f.bio, color = Palette.ink, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    ClosenessBar(f.closeness)
                    BuddiesLine(f, data)
                }
            }
        }
    }
}

/** Who a friend is closest to among the others. */
@Composable
private fun BuddiesLine(f: com.rusty.museecho.gadget.Friend, data: com.rusty.museecho.gadget.FriendsData) {
    val buddies = data.buddies(f.id).take(3)
    if (buddies.isEmpty()) return
    Text("Close to: " + buddies.joinToString { (b, n) -> b.shownName + if (n >= 30) " 💛" else "" },
        color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** The blob's mailbox: letters from its friends, newest first; tap one to read it. */
@Composable
private fun MailContent(modifier: Modifier) {
    val data by GadgetState.friends.collectAsState()
    val letters = data.letters.reversed()
    if (letters.isEmpty()) {
        Text("No letters yet. Friends write every couple of days.", color = Palette.muted, fontSize = 15.sp, modifier = modifier)
        return
    }
    androidx.compose.foundation.lazy.LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(letters.size) { i ->
            val l = letters[i]
            val f = data.friends.firstOrNull { it.id == l.friendId } ?: if (l.friendId == "rusty") com.rusty.museecho.gadget.Friend("rusty", "Rusty 💌", color = 0xFFFFC266) else data.penpal?.takeIf { l.friendId == com.rusty.museecho.gadget.FriendsBrain.PENPAL_ID }?.let {
                com.rusty.museecho.gadget.Friend(l.friendId, "${it.name} 🌍", color = it.color)
            }
            Row(Modifier.fillMaxWidth().background(Palette.card.copy(alpha = if (l.read) 0.5f else 0.9f), RoundedCornerShape(12.dp))
                .clickable {
                    GadgetService.instance?.life?.friends?.markLetterRead(l.atMs)
                    GadgetState.show(Screen.Text((if (l.fromBlob) "📮 To " else "✉️ From ") + "${f?.shownName ?: "a friend"} · ${l.date}", l.text))
                }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (l.fromBlob) "📮" else if (l.read) "📭" else "📬", fontSize = 20.sp)
                Column(Modifier.padding(start = 10.dp)) {
                    Text((if (l.fromBlob) "To " else "From ") + (f?.shownName ?: "a friend"), color = f?.let { androidx.compose.ui.graphics.Color(it.color) } ?: Palette.ink,
                        fontSize = 15.sp, fontWeight = if (l.read) FontWeight.Normal else FontWeight.SemiBold)
                    Text(l.text.replace("\n", " "), color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun friendLine(f: com.rusty.museecho.gadget.Friend): String = listOfNotNull(
    f.p.label,
    f.birthday.takeIf { it.length == 5 }?.let { b ->
        "🎂 " + runCatching { java.time.MonthDay.parse("--$b").format(java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.US)) }.getOrDefault(b)
    },
    "${f.visits} visit${if (f.visits == 1) "" else "s"}",
    f.favourite()?.let { "loves ${com.rusty.museecho.gadget.FriendsBrain.activityLabel(it)}" },
    f.lastVisitMs.takeIf { it > 0 }?.let { "last here " + java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.US).format(java.util.Date(it)) },
).joinToString(" · ")

@Composable
private fun ClosenessBar(c: Int) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (c >= 70) "💛 best friends" else if (c >= 30) "🤝 good friends" else "👋 getting to know each other",
            color = Palette.muted, fontSize = 11.sp, modifier = Modifier.width(150.dp))
        Box(Modifier.weight(1f).height(5.dp).background(Palette.night, RoundedCornerShape(50))) {
            Box(Modifier.fillMaxWidth(c.coerceIn(2, 100) / 100f).height(5.dp).background(Palette.coral, RoundedCornerShape(50)))
        }
    }
}

@Composable
private fun FriendDetail(f: com.rusty.museecho.gadget.Friend, data: com.rusty.museecho.gadget.FriendsData, actions: UiActions, modifier: Modifier, onBack: () -> Unit) {
    val album by GadgetState.friendAlbum.collectAsState()
    val pics = album.filter { f.name.isNotBlank() && f.name in it.caption }
    Column(modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tab("← All friends", false, onBack)
            Spacer(Modifier.width(10.dp))
            Text(f.shownName, color = androidx.compose.ui.graphics.Color(f.color), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(friendLine(f) + if (f.wins + f.losses > 0) " · games: ${f.wins} won, ${f.losses} lost" else "",
            color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        if (f.bio.isNotEmpty()) Text(f.bio, color = Palette.ink, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        ClosenessBar(f.closeness)
        BuddiesLine(f, data)
        val cards = data.postcards.filter { it.friendId == f.id }
        if (cards.isNotEmpty()) {
            Text("💌 Postcards", color = Palette.amber, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
            cards.reversed().forEach { Text("\"${it.text}\"  (${it.date})", color = Palette.ink, fontSize = 13.sp) }
        }
        Text("📔 ${f.shownName}'s diary", color = Palette.amber, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        val entries = data.diary.filter { it.friendId == f.id }.reversed()
        if (entries.isEmpty()) Text("Nothing yet: it writes one in the evening after a visit.", color = Palette.muted, fontSize = 13.sp)
        entries.forEach { e ->
            Text(e.date, color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            Text(e.text, color = Palette.ink, fontSize = 14.sp, lineHeight = 20.sp)
        }
        if (pics.isNotEmpty()) {
            Text("📸 Pictures together", color = Palette.amber, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pics.reversed().take(12).forEach { shot -> Box(Modifier.width(110.dp)) { AlbumThumb(shot, actions) } }
            }
        }
    }
}

private fun propEmoji(p: String) = when (p) {
    "umbrella" -> "☂️"; "coffee" -> "☕"; "letter" -> "✉️"; "gift" -> "🎁"; "cake" -> "🎂"; "star" -> "⭐"; "heart" -> "💛"
    "note" -> "📝"; "hourglass" -> "⏳"; "balloon" -> "🎈"; "package" -> "📦"; "sunglasses" -> "😎"; else -> ""
}

/** Play against Puff by touch: tic-tac-toe (you're X) or rock-paper-scissors, with a scoreboard. */
@Composable
private fun PlayContent(modifier: Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("museecho", android.content.Context.MODE_PRIVATE) }
    val name = GadgetState.blobName.collectAsState().value.ifBlank { "Puff" }
    var score by remember { mutableStateOf(Triple(prefs.getInt("play_you", 0), prefs.getInt("play_puff", 0), prefs.getInt("play_draw", 0))) }
    var game by remember { mutableStateOf("ttt") }
    var board by remember { mutableStateOf(CharArray(9) { ' ' }.concatToString()) }
    var result by remember { mutableStateOf("") }
    var rps by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun finish(outcome: Int, what: String) {
        // outcome: 1 you won, -1 Puff won, 0 draw.
        score = when (outcome) {
            1 -> score.copy(first = score.first + 1)
            -1 -> score.copy(second = score.second + 1)
            else -> score.copy(third = score.third + 1)
        }
        prefs.edit().putInt("play_you", score.first).putInt("play_puff", score.second).putInt("play_draw", score.third).apply()
        result = when (outcome) { 1 -> "You win! 🏆"; -1 -> "$name wins! 🎉"; else -> "A draw 🤝" }
        when (outcome) {
            1 -> { GadgetState.setMood(com.rusty.museecho.gadget.Mood.SAD, 6); GadgetState.say("Aww, you got me! 😤 Again?", 6) }
            -1 -> { GadgetState.react(com.rusty.museecho.gadget.Reaction.CELEBRATE); GadgetState.say("I win! 🎉", 5) }
            else -> { GadgetState.react(com.rusty.museecho.gadget.Reaction.NOD); GadgetState.say("A draw! 🤝", 5) }
        }
        com.rusty.museecho.gadget.DayLog.event("played $what with Rusty (${when (outcome) { 1 -> "Rusty won"; -1 -> "$name won"; else -> "a draw" }})", "game")
    }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tab("Tic-tac-toe", game == "ttt") { game = "ttt"; result = "" }
            Spacer(Modifier.width(6.dp))
            Tab("Rock-paper-scissors", game == "rps") { game = "rps"; result = "" }
            Spacer(Modifier.width(6.dp))
            Tab("Tricks", game == "tricks") { game = "tricks"; result = "" }
            Spacer(Modifier.weight(1f))
            Text("You ${score.first} · $name ${score.second} · draws ${score.third}", color = Palette.muted, fontSize = 12.sp)
        }
        val boardWins = GadgetState.friends.collectAsState().value.boardWins
        if (boardWins.isNotEmpty()) Text("🎲 Snakes and ladders: " + boardWins.entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key} ${it.value}" },
            color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        if (game == "tricks") TricksContent()
        else if (game == "ttt") Row(verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (r in 0 until 3) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (c in 0 until 3) {
                        val i = r * 3 + c
                        val ch = board[i]
                        Box(Modifier.size(58.dp).glass(RoundedCornerShape(8.dp)).clickable(enabled = ch == ' ' && result.isEmpty()) {
                            val b = board.toCharArray()
                            b[i] = 'X'
                            board = b.concatToString()
                            if (com.rusty.museecho.gadget.FriendsBrain.winner(b) == 'X') { finish(1, "tic-tac-toe"); return@clickable }
                            if (b.none { it == ' ' }) { finish(0, "tic-tac-toe"); return@clickable }
                            scope.launch {
                                kotlinx.coroutines.delay(700)
                                val move = com.rusty.museecho.gadget.FriendsBrain.puffMove(b, java.util.Random())
                                b[move] = 'O'
                                board = b.concatToString()
                                GadgetState.react(com.rusty.museecho.gadget.Reaction.LEAN_IN)
                                if (com.rusty.museecho.gadget.FriendsBrain.winner(b) == 'O') finish(-1, "tic-tac-toe")
                                else if (b.none { it == ' ' }) finish(0, "tic-tac-toe")
                            }
                        }, contentAlignment = Alignment.Center) {
                            Text(if (ch == ' ') "" else ch.toString(), color = if (ch == 'X') Palette.teal else Palette.coral, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Column(Modifier.padding(start = 16.dp)) {
                Text(if (result.isNotEmpty()) result else "You're X. Tap a square!", color = Palette.ink, fontSize = 16.sp)
                if (result.isNotEmpty()) Spacer(Modifier.height(8.dp))
                if (result.isNotEmpty()) Tab("Play again", false) { board = CharArray(9) { ' ' }.concatToString(); result = "" }
            }
        } else Column {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                com.rusty.museecho.gadget.FriendsBrain.RPS_EMOJI.forEachIndexed { i, e ->
                    Box(Modifier.size(64.dp).glass(RoundedCornerShape(14.dp)).clickable {
                        val puff = java.util.Random().nextInt(3)
                        rps = i to puff
                        GadgetState.say(com.rusty.museecho.gadget.FriendsBrain.RPS_EMOJI[puff], 4)
                        finish(com.rusty.museecho.gadget.FriendsBrain.rps(i, puff), "rock-paper-scissors")
                    }, contentAlignment = Alignment.Center) { Text(e, fontSize = 30.sp) }
                }
            }
            rps?.let { (me, puff) ->
                Text("You ${com.rusty.museecho.gadget.FriendsBrain.RPS_EMOJI[me]}  vs  $name ${com.rusty.museecho.gadget.FriendsBrain.RPS_EMOJI[puff]}  ·  $result",
                    color = Palette.ink, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

/** The Diary tab: the evening diary, or the weather calendar. */
@Composable
private fun DiaryAndWeather(modifier: Modifier) {
    var calendar by remember { mutableStateOf(false) }
    Column(modifier) {
        Row(Modifier.padding(bottom = 4.dp)) {
            Tab("📔 Diary", !calendar) { calendar = false }
            Spacer(Modifier.width(6.dp))
            Tab("📅 Weather", calendar) { calendar = true }
        }
        if (calendar) WeatherCalendar(Modifier.fillMaxSize()) else DiaryContent(Modifier.fillMaxSize())
    }
}

/** Puff's outfits: tap one to wear it today (or nothing). */
@Composable
private fun WardrobeContent(modifier: Modifier) {
    val data by GadgetState.friends.collectAsState()
    val today = java.time.LocalDate.now().toString()
    val wearing = data.outfit.takeIf { it.startsWith(today) }?.substringAfter('|')
    val emoji = com.rusty.museecho.gadget.FunLife.OUTFIT_EMOJI
    Column(modifier) {
        Text("${GadgetState.blobName.collectAsState().value.ifBlank { "Puff" }} picks an outfit each morning for the weather and its mood. Tap one to choose for it today.",
            color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (data.wardrobe + "NONE").forEach { name ->
                val on = (wearing ?: "NONE") == name
                Column(Modifier.width(84.dp).background(if (on) Palette.amber.copy(alpha = 0.3f) else Palette.card, RoundedCornerShape(12.dp))
                    .clickable { GadgetService.instance?.life?.friends?.wearToday(name.takeIf { it != "NONE" }) }.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (name == "NONE") "🫥" else emoji[name] ?: "✨", fontSize = 28.sp)
                    Text(if (name == "NONE") "Nothing" else name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
                        color = Palette.ink, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text("New outfits come from gifts, holidays and a new costume every month.", color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

/** The Play tab's tricks: pick one, practise a few times a day, and it gets better over the days. */
@Composable
private fun TricksContent() {
    val data by GadgetState.friends.collectAsState()
    val today = java.time.LocalDate.now().toString()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        com.rusty.museecho.gadget.FriendsBrain.TRICKS.forEach { (key, label) ->
            val skill = data.tricks[key] ?: 0
            val goes = data.practice["$today|$key"] ?: 0
            Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(12.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label.replaceFirstChar { it.uppercase() }, color = Palette.ink, fontSize = 14.sp)
                    Text("${com.rusty.museecho.gadget.FriendsBrain.trickLevel(skill)} · $skill/100 · practised $goes× today", color = Palette.muted, fontSize = 11.sp)
                    Box(Modifier.fillMaxWidth().padding(top = 3.dp).height(4.dp).background(Palette.night, RoundedCornerShape(50))) {
                        Box(Modifier.fillMaxWidth(skill.coerceIn(2, 100) / 100f).height(4.dp).background(Palette.teal, RoundedCornerShape(50)))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Tab("Practise", false) { GadgetService.instance?.life?.friends?.practise(key) }
                Spacer(Modifier.width(4.dp))
                if (skill > 0) Tab("Show me", false) {
                    GadgetService.instance?.scopeLaunch { GadgetService.instance?.life?.friends?.perform(key, skill) }
                }
            }
        }
    }
}

/** Milestone stickers, Puff's and Rusty's: earned ones in colour with the day, the rest still to find. */
@Composable
private fun StickerBook(modifier: Modifier) {
    val data by GadgetState.friends.collectAsState()
    val all = com.rusty.museecho.gadget.FriendsBrain.STICKERS
    Column(modifier.verticalScroll(rememberScrollState())) {
        Text("🏅 ${data.stickers.size} of ${all.size} stickers", color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        listOf("puff" to "Puff's stickers", "you" to "Rusty's stickers").forEach { (who, title) ->
            Text(title, color = Palette.amber, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp))
            all.filter { it.who == who }.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { s ->
                        val got = data.stickers[s.id]
                        Column(Modifier.weight(1f).background(Palette.card.copy(alpha = if (got != null) 0.9f else 0.35f), RoundedCornerShape(12.dp)).padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (got != null) s.emoji else "🔒", fontSize = 24.sp, modifier = Modifier.alpha(if (got != null) 1f else 0.5f))
                            Text(s.title, color = if (got != null) Palette.ink else Palette.muted, fontSize = 11.sp, maxLines = 2,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 13.sp)
                            if (got != null) Text(got, color = Palette.muted, fontSize = 9.sp)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** The weather calendar: a month grid of Puff's doodles of each day's weather and its mood. */
@Composable
private fun WeatherCalendar(modifier: Modifier) {
    val data by GadgetState.friends.collectAsState()
    var month by remember { mutableStateOf(java.time.YearMonth.now()) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("◀", color = Palette.ink, fontSize = 16.sp, modifier = Modifier.clickable { month = month.minusMonths(1) }.padding(6.dp))
            Text(month.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.US)), color = Palette.amber, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.width(150.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("▶", color = Palette.ink, fontSize = 16.sp, modifier = Modifier.clickable { if (month < java.time.YearMonth.now()) month = month.plusMonths(1) }.padding(6.dp))
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, color = Palette.muted, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        val lead = month.atDay(1).dayOfWeek.value - 1
        val cells = List(lead) { 0 } + (1..month.lengthOfMonth()).toList()
        Column(Modifier.verticalScroll(rememberScrollState())) {
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    week.forEach { day ->
                        val entry = if (day > 0) data.weatherDays[month.atDay(day).toString()] else null
                        Column(Modifier.weight(1f).padding(1.dp).background(Palette.card.copy(alpha = if (day > 0) 0.6f else 0f), RoundedCornerShape(6.dp)).padding(2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            if (day > 0) {
                                Text("$day", color = Palette.muted, fontSize = 9.sp)
                                Text(entry?.replace("|", "") ?: " ", fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    }
                    repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
