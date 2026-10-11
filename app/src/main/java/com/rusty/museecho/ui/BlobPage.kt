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
        Box(Modifier.width(360.dp).fillMaxHeight().padding(top = 56.dp, bottom = 78.dp)) {
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
            FriendLayer(center, 80.dp, actions)
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(top = 64.dp, end = 40.dp, bottom = 84.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Blob", color = Palette.amber, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(16.dp))
                if (diaryOn && !guest) Tab("📔 Diary", tab == "diary") { tab = "diary" }
                Spacer(Modifier.width(6.dp))
                Tab("📸 Album", tab == "album") { tab = "album" }
            }
            Spacer(Modifier.height(8.dp))
            if (tab == "diary" && diaryOn && !guest) DiaryContent(Modifier.fillMaxSize())
            else AlbumContent(actions, Modifier.fillMaxSize())
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
    val shots by GadgetState.album.collectAsState()
    if (shots.isEmpty()) {
        Text("No pictures yet. Once a day the Echo snaps the blob doing something cute (a friend visit, a new hat, " +
            "something new in its room), or just hanging out in the afternoon.", color = Palette.muted, fontSize = 15.sp, modifier = modifier)
        return
    }
    LazyVerticalGrid(GridCells.Fixed(3), modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(shots.reversed(), key = { it.date }) { shot -> AlbumThumb(shot, actions) }
    }
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
