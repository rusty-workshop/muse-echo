package com.rusty.museecho.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
                Blob(Modifier.fillMaxSize(), actions, follower = true)
            }
            FriendLayer(center, half, actions)
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(top = 64.dp, end = 40.dp, bottom = 84.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Blob", color = Palette.amber, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(16.dp))
                if (diaryOn && !guest) Tab("📔 Diary", tab == "diary") { tab = "diary" }
                Spacer(Modifier.width(6.dp))
                Tab("📸 Album", tab == "album") { tab = "album" }
                Spacer(Modifier.width(6.dp))
                Tab("👯 Friends", tab == "friends") { tab = "friends" }
                Spacer(Modifier.width(6.dp))
                val unread = GadgetState.friends.collectAsState().value.letters.count { !it.read }
                Tab(if (unread > 0) "✉️ $unread" else "✉️", tab == "mail") { tab = "mail" }
            }
            Spacer(Modifier.height(8.dp))
            when {
                tab == "diary" && diaryOn && !guest -> DiaryContent(Modifier.fillMaxSize())
                tab == "friends" -> FriendsContent(actions, Modifier.fillMaxSize())
                tab == "mail" -> MailContent(Modifier.fillMaxSize())
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
        Text("No pictures yet. Once a day the Echo snaps the blob doing something cute (a friend visit, a new hat, " +
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
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Palette.card, RoundedCornerShape(10.dp))) {
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
        items(data.friends.size) { i ->
            val f = data.friends[i]
            Row(Modifier.fillMaxWidth().background(Palette.card.copy(alpha = 0.8f), RoundedCornerShape(14.dp)).clickable { open = f.id }.padding(10.dp),
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
            val f = data.friends.firstOrNull { it.id == l.friendId }
            Row(Modifier.fillMaxWidth().background(Palette.card.copy(alpha = if (l.read) 0.5f else 0.9f), RoundedCornerShape(12.dp))
                .clickable {
                    GadgetService.instance?.life?.friends?.markLetterRead(l.atMs)
                    GadgetState.show(Screen.Text("✉️ From ${f?.shownName ?: "a friend"} · ${l.date}", l.text))
                }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (l.read) "📭" else "📬", fontSize = 20.sp)
                Column(Modifier.padding(start = 10.dp)) {
                    Text("From ${f?.shownName ?: "a friend"}", color = f?.let { androidx.compose.ui.graphics.Color(it.color) } ?: Palette.ink,
                        fontSize = 15.sp, fontWeight = if (l.read) FontWeight.Normal else FontWeight.SemiBold)
                    Text(l.text.replace("\n", " "), color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun friendLine(f: com.rusty.museecho.gadget.Friend): String = listOfNotNull(
    f.p.label,
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
