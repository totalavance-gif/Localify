package com.nexos.localify

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Green = Color(0xFF1DB954)
private val Bg = Color(0xFF121212)
private val Card = Color(0xFF1E1E1E)

class MainActivity : ComponentActivity() {

    var songs by mutableStateOf(listOf<Song>())
    var hasPerm by mutableStateOf(false)

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            checkPerm()
            if (hasPerm) refreshSongs()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPerm()
        if (hasPerm) refreshSongs()
        setContent {
            App(this)
        }
        if (!hasPerm) permLauncher.launch(requiredPerms())
    }

    fun requiredPerms(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun checkPerm() {
        // La notificacion es opcional: basta el permiso de audio.
        val audio = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        hasPerm = checkSelfPermission(audio) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun requestPerms() {
        permLauncher.launch(requiredPerms())
    }

    fun refreshSongs() {
        Thread {
            val list = try {
                loadSongs(contentResolver)
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                songs = list
                PlayerService.queue = list
            }
        }.start()
    }
}

@Composable
fun App(activity: MainActivity) {
    var query by remember { mutableStateOf("") }
    var openPlayer by remember { mutableStateOf(false) }
    val songs = activity.songs
    val filtered = remember(songs, query) {
        if (query.isBlank()) songs
        else songs.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true)
        }
    }

    BackHandler(enabled = openPlayer) { openPlayer = false }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
            if (openPlayer && PlayerState.hasSong) {
                NowPlaying(onBack = { openPlayer = false })
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp, 20.dp, 20.dp, 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Tu música", fontSize = 26.sp,
                            fontWeight = FontWeight.Bold, color = Color.White)
                        Text("${songs.size}",
                            color = Color.Gray, fontSize = 14.sp)
                    }
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Buscar canción o artista") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Card,
                            unfocusedContainerColor = Card,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                    if (!activity.hasPerm) {
                        Text("Permiso denegado: sin acceso al audio " +
                            "no hay canciones.",
                            color = Color(0xFFEF5350),
                            modifier = Modifier.padding(horizontal = 20.dp))
                        Button(
                            onClick = { activity.requestPerms() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Green),
                            modifier = Modifier.padding(horizontal = 20.dp)
                        ) {
                            Text("Dar permiso", color = Color.Black)
                        }
                    } else if (filtered.isEmpty()) {
                        Text(if (songs.isEmpty())
                            "No se encontró música en este equipo."
                        else "Sin resultados para \"$query\".",
                            color = Color.Gray,
                            modifier = Modifier.padding(20.dp))
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            itemsIndexed(filtered) { _, song ->
                                val index = songs.indexOf(song)
                                SongRow(song, onTap = {
                                    PlayerService.cmd(activity,
                                        PlayerService.ACTION_PLAY_INDEX, index)
                                })
                            }
                        }
                    }
                    if (PlayerState.hasSong) {
                        MiniPlayer(onTap = { openPlayer = true })
                    }
                }
            }
        }
    }
}

@Composable
fun SongRow(song: Song, onTap: () -> Unit) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onTap)
        .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ArtThumb(song.albumId, 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(song.title, color = Color.White, fontSize = 15.sp,
                maxLines = 1)
            Text(song.artist, color = Color.Gray, fontSize = 13.sp,
                maxLines = 1)
        }
        Text(fmtTime(song.duration), color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable
fun MiniPlayer(onTap: () -> Unit) {
    val context = LocalContext.current
    Row(modifier = Modifier
        .fillMaxWidth()
        .background(Card)
        .clickable(onClick = onTap)
        .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ArtThumb(PlayerState.albumId, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(PlayerState.title, color = Color.White, fontSize = 14.sp,
                maxLines = 1)
            Text(PlayerState.artist, color = Color.Gray, fontSize = 12.sp,
                maxLines = 1)
        }
        IconButton(onClick = {
            PlayerService.cmd(context, PlayerService.ACTION_PLAY_PAUSE)
        }) {
            Text(if (PlayerState.playing) "❚❚" else "▶",
                color = Color.White, fontSize = 22.sp)
        }
    }
}

@Composable
fun NowPlaying(onBack: () -> Unit) {
    val context = LocalContext.current
    val pos = PlayerState.position
    val dur = PlayerState.duration.coerceAtLeast(1L)
    Column(modifier = Modifier
        .fillMaxSize()
        .padding(24.dp)) {
        IconButton(onClick = onBack) {
            Text("‹", color = Color.White, fontSize = 32.sp)
        }
        Spacer(Modifier.height(8.dp))
        ArtBig(PlayerState.albumId)
        Spacer(Modifier.height(24.dp))
        Text(PlayerState.title, color = Color.White, fontSize = 22.sp,
            fontWeight = FontWeight.Bold, maxLines = 2)
        Text(PlayerState.artist, color = Color.Gray, fontSize = 16.sp,
            maxLines = 1)
        Spacer(Modifier.height(16.dp))
        Slider(
            value = pos.toFloat().coerceIn(0f, dur.toFloat()),
            onValueChange = {
                PlayerService.cmd(context, PlayerService.ACTION_SEEK, pos = it.toLong())
            },
            valueRange = 0f..dur.toFloat(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmtTime(pos), color = Color.Gray, fontSize = 12.sp)
            Text(fmtTime(dur), color = Color.Gray, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                PlayerService.cmd(context, PlayerService.ACTION_SHUFFLE)
            }) {
                Text("⇄", fontSize = 24.sp,
                    color = if (PlayerState.shuffle) Green else Color.Gray)
            }
            IconButton(onClick = {
                PlayerService.cmd(context, PlayerService.ACTION_PREV)
            }) {
                Text("⏮", fontSize = 30.sp, color = Color.White)
            }
            IconButton(
                onClick = {
                    PlayerService.cmd(context, PlayerService.ACTION_PLAY_PAUSE)
                },
                modifier = Modifier
                    .size(72.dp)
                    .background(Green, CircleShape)
            ) {
                Text(if (PlayerState.playing) "❚❚" else "▶",
                    fontSize = 28.sp, color = Color.Black)
            }
            IconButton(onClick = {
                PlayerService.cmd(context, PlayerService.ACTION_NEXT)
            }) {
                Text("⏭", fontSize = 30.sp, color = Color.White)
            }
            IconButton(onClick = {
                PlayerService.cmd(context, PlayerService.ACTION_REPEAT)
            }) {
                Text("⟳", fontSize = 24.sp,
                    color = if (PlayerState.repeatOne) Green else Color.Gray)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center) {
            IconButton(onClick = {
                PlayerService.cmd(context, PlayerService.ACTION_STOP)
                onBack()
            }) {
                Text("⏹ Detener", color = Color.Gray, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun ArtThumb(albumId: Long, size: Dp) {
    val context = LocalContext.current
    var bmp by remember(albumId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(albumId) {
        bmp = withContext(Dispatchers.IO) {
            ArtCache.getArt(context.contentResolver, albumId)
        }
    }
    if (bmp != null) {
        Image(bmp!!.asImageBitmap(), contentDescription = "Carátula",
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(6.dp)))
    } else {
        Box(modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF2E7D32)),
            contentAlignment = Alignment.Center) {
            Text("♪", color = Color.White, fontSize = 20.sp)
        }
    }
}

@Composable
fun ArtBig(albumId: Long) {
    val context = LocalContext.current
    var bmp by remember(albumId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(albumId) {
        bmp = withContext(Dispatchers.IO) {
            ArtCache.getArt(context.contentResolver, albumId)
        }
    }
    if (bmp != null) {
        Image(bmp!!.asImageBitmap(), contentDescription = "Carátula",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp)))
    } else {
        Box(modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF2E7D32)),
            contentAlignment = Alignment.Center) {
            Text("♪", color = Color.White, fontSize = 72.sp)
        }
    }
}
