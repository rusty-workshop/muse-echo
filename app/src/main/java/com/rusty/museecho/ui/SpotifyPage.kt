package com.rusty.museecho.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rusty.museecho.integrations.SpotifyItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Album art, loaded once per page visit. */
private val artCache = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, android.graphics.Bitmap>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap>?) = size > 20
})

@Composable
private fun Art(url: String, size: Dp, actions: UiActions) {
    var bmp by remember(url) { mutableStateOf(artCache[url]) }
    LaunchedEffect(url) { if (bmp == null && url.isNotEmpty()) actions.spotifyImage(url)?.let { artCache[url] = it; bmp = it } }
    Box(Modifier.size(size).background(Palette.card, RoundedCornerShape(6.dp))) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** Spotify by touch: search, play now or add to the queue, and see what's next (through the laptop). */
@Composable
internal fun SpotifyPage(actions: UiActions) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SpotifyItem>>(emptyList()) }
    var note by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf<SpotifyItem?>(null) }
    var next by remember { mutableStateOf<List<SpotifyItem>>(emptyList()) }
    var queueTick by remember { mutableStateOf(0) }
    val queued = remember { mutableStateMapOf<String, Boolean>() }
    // What's playing and up next, every 15 s while the page is open (and right after a change).
    LaunchedEffect(queueTick) {
        while (true) {
            actions.spotifyQueue()?.let { (c, n) -> current = c; next = n }
            delay(15_000)
        }
    }
    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        searching = true
        note = ""
        scope.launch {
            val (items, err) = actions.spotifySearch(q)
            results = items
            note = err ?: if (items.isEmpty()) "Nothing found" else ""
            searching = false
        }
    }
    fun play(item: SpotifyItem, queue: Boolean) = scope.launch {
        val (ok, msg) = actions.spotify(item.uri, queue)
        note = if (ok) (if (queue) "Added to the queue: ${item.title}" else "Playing ${item.title}") else "Couldn't: $msg"
        if (ok && queue) queued[item.uri] = true
        delay(1_500)
        queueTick++
    }

    Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 64.dp, bottom = 84.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Spotify", color = Palette.amber, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            if (note.isNotEmpty()) Text("   $note", color = if (note.startsWith("Couldn't") || note == "Nothing found") Palette.coral else Palette.teal,
                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1.3f).fillMaxHeight()) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it.take(80) }, singleLine = true,
                    placeholder = { Text(if (searching) "Searching…" else "Search songs, albums, playlists") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(results, key = { it.uri }) { item ->
                        Row(
                            Modifier.fillMaxWidth().background(Palette.card.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                                .clickable { play(item, queue = false) }.padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Art(item.image, 40.dp, actions)
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(item.title, color = Palette.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text((if (item.kind != "track") item.kind.replaceFirstChar { it.uppercase() } + " · " else "") + item.sub,
                                    color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (item.kind == "track") {
                                val done = queued[item.uri] == true
                                Text(if (done) "✓" else "+ Queue", color = if (done) Palette.teal else Palette.ink, fontSize = 13.sp,
                                    modifier = Modifier.background(Palette.violet.copy(alpha = if (done) 0.15f else 0.4f), RoundedCornerShape(50))
                                        .clickable(enabled = !done) { play(item, queue = true) }.padding(horizontal = 12.dp, vertical = 7.dp))
                            }
                        }
                    }
                }
            }
            // Now playing and up next (ends above the round mic button).
            Column(Modifier.weight(1f).fillMaxHeight().padding(bottom = 30.dp)) {
                Text("Now playing", color = Palette.muted, fontSize = 13.sp)
                val c = current
                if (c == null) Text("Nothing on Spotify right now", color = Palette.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                else Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Art(c.image, 52.dp, actions)
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(c.title, color = Palette.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.sub, color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text("Up next", color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(next) { n ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Art(n.image, 28.dp, actions)
                            Text("${n.title} · ${n.sub}", color = Palette.ink.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}
