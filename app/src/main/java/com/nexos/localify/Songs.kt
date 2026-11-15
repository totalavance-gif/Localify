package com.nexos.localify

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,
    val uri: Uri
)

/** Lee las canciones del almacenamiento con MediaStore. */
fun loadSongs(cr: ContentResolver): List<Song> {
    val collection = if (Build.VERSION.SDK_INT >= 29) {
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION
    )
    val selection = MediaStore.Audio.Media.IS_MUSIC + "!= 0 AND " +
        MediaStore.Audio.Media.DURATION + "> 30000"
    val out = mutableListOf<Song>()
    cr.query(collection, projection, selection, null,
        MediaStore.Audio.Media.TITLE + " ASC")?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val albumIdCol =
            c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        while (c.moveToNext()) {
            val id = c.getLong(idCol)
            out.add(Song(
                id = id,
                title = c.getString(titleCol) ?: "Sin título",
                artist = c.getString(artistCol) ?: "Artista desconocido",
                album = c.getString(albumCol) ?: "",
                albumId = c.getLong(albumIdCol),
                duration = c.getLong(durCol),
                uri = ContentUris.withAppendedId(collection, id)
            ))
        }
    }
    return out
}

/** Carátulas por álbum, con caché chica en memoria. */
object ArtCache {
    private val cache = LinkedHashMap<Long, Bitmap?>(64, 0.75f, true)
    private const val MAX = 60

    @Synchronized
    fun getArt(cr: ContentResolver, albumId: Long): Bitmap? {
        if (cache.containsKey(albumId)) return cache[albumId]
        var bmp: Bitmap? = null
        if (Build.VERSION.SDK_INT >= 29 && albumId > 0) {
            try {
                val uri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    albumId)
                bmp = cr.loadThumbnail(uri, Size(256, 256), null)
            } catch (e: Exception) {
                bmp = null
            }
        }
        cache[albumId] = bmp
        while (cache.size > MAX) {
            cache.remove(cache.keys.iterator().next())
        }
        return bmp
    }
}

fun fmtTime(ms: Long): String {
    val total = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
