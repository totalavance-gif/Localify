package com.nexos.localify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.random.Random

/** Estado observable por la UI (mismo proceso). */
object PlayerState {
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var albumId by mutableStateOf(-1L)
    var playing by mutableStateOf(false)
    var position by mutableStateOf(0L)
    var duration by mutableStateOf(0L)
    var hasSong by mutableStateOf(false)
    var shuffle by mutableStateOf(false)
    var repeatOne by mutableStateOf(false)

    fun reset() {
        title = ""
        artist = ""
        albumId = -1L
        playing = false
        position = 0L
        duration = 0L
        hasSong = false
    }
}

class PlayerService : Service(),
    MediaPlayer.OnPreparedListener,
    MediaPlayer.OnCompletionListener,
    AudioManager.OnAudioFocusChangeListener {

    companion object {
        const val ACTION_PLAY_PAUSE = "com.nexos.localify.PLAY_PAUSE"
        const val ACTION_NEXT = "com.nexos.localify.NEXT"
        const val ACTION_PREV = "com.nexos.localify.PREV"
        const val ACTION_PLAY_INDEX = "com.nexos.localify.PLAY_INDEX"
        const val ACTION_SEEK = "com.nexos.localify.SEEK"
        const val ACTION_SHUFFLE = "com.nexos.localify.SHUFFLE"
        const val ACTION_REPEAT = "com.nexos.localify.REPEAT"
        const val ACTION_STOP = "com.nexos.localify.STOP"
        const val EXTRA_INDEX = "index"
        const val EXTRA_POS = "pos"

        @Volatile
        var queue: List<Song> = emptyList()

        fun cmd(context: Context, action: String, index: Int = -1,
                pos: Long = -1L) {
            val intent = Intent(context, PlayerService::class.java)
                .setAction(action)
            if (index >= 0) intent.putExtra(EXTRA_INDEX, index)
            if (pos >= 0) intent.putExtra(EXTRA_POS, pos)
            context.startService(intent)
        }
    }

    private val channelId = "localify_playback"
    private val notifId = 5101
    private var player: MediaPlayer? = null
    private var mediaSession: MediaSession? = null
    private var index = -1
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val progressTask = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && PlayerState.hasSong) {
                try {
                    if (p.isPlaying) PlayerState.position = p.currentPosition.toLong()
                } catch (e: Exception) {
                    // Estado transitorio.
                }
                handler.postDelayed(this, 500L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        player = MediaPlayer().apply {
            setAudioAttributes(attrs)
            setOnPreparedListener(this@PlayerService)
            setOnCompletionListener(this@PlayerService)
        }
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "Localify:Playback")
        } catch (e: Exception) {
            wakeLock = null
        }
        setupSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> toggle()
            ACTION_NEXT -> next()
            ACTION_PREV -> prev()
            ACTION_PLAY_INDEX ->
                playIndex(intent?.getIntExtra(EXTRA_INDEX, 0) ?: 0)
            ACTION_SEEK ->
                seekTo(intent?.getLongExtra(EXTRA_POS, 0L) ?: 0L)
            ACTION_SHUFFLE -> {
                PlayerState.shuffle = !PlayerState.shuffle
                updateNotification()
            }
            ACTION_REPEAT -> {
                PlayerState.repeatOne = !PlayerState.repeatOne
                updateNotification()
            }
            ACTION_STOP -> stopAll()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(progressTask)
        try {
            mediaSession?.release()
        } catch (e: Exception) {
        }
        try {
            player?.release()
        } catch (e: Exception) {
        }
        player = null
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    // --- Audio focus: pausa si otra app pide el audio. ---

    override fun onAudioFocusChange(focusChange: Int) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pause()
        }
    }

    private fun requestFocus(): Boolean {
        return try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.requestAudioFocus(this, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (e: Exception) {
            true
        }
    }

    // --- Transporte. ---

    private fun toggle() {
        if (PlayerState.playing) {
            pause()
        } else if (PlayerState.hasSong) {
            start()
        } else if (queue.isNotEmpty()) {
            playIndex(0)
        }
    }

    private fun start() {
        if (!requestFocus()) return
        try {
            player?.start()
        } catch (e: Exception) {
            return
        }
        PlayerState.playing = true
        holdWake(true)
        updateNotification()
    }

    private fun pause() {
        try {
            if (player?.isPlaying == true) player?.pause()
        } catch (e: Exception) {
        }
        PlayerState.playing = false
        holdWake(false)
        updateNotification()
    }

    private fun playIndex(i: Int) {
        if (queue.isEmpty()) return
        index = i.coerceIn(0, queue.size - 1)
        val song = queue[index]
        if (!requestFocus()) return
        try {
            player?.reset()
            player?.setDataSource(this, song.uri)
            player?.prepareAsync()
        } catch (e: Exception) {
            return
        }
        PlayerState.title = song.title
        PlayerState.artist = song.artist
        PlayerState.albumId = song.albumId
        PlayerState.duration = song.duration
        PlayerState.position = 0L
        PlayerState.hasSong = true
        PlayerState.playing = false
        handler.removeCallbacks(progressTask)
        handler.post(progressTask)
        updateNotification()
    }

    private fun next() {
        if (queue.isEmpty()) return
        if (PlayerState.shuffle && queue.size > 1) {
            var n = index
            while (n == index) n = Random.nextInt(queue.size)
            playIndex(n)
        } else {
            playIndex((index + 1) % queue.size)
        }
    }

    private fun prev() {
        if (queue.isEmpty()) return
        if (PlayerState.position > 3000L) {
            seekTo(0L)
            return
        }
        playIndex((index - 1 + queue.size) % queue.size)
    }

    private fun seekTo(ms: Long) {
        try {
            player?.seekTo(ms.toInt())
            PlayerState.position = ms
        } catch (e: Exception) {
        }
    }

    private fun stopAll() {
        handler.removeCallbacks(progressTask)
        try {
            player?.reset()
        } catch (e: Exception) {
        }
        holdWake(false)
        PlayerState.reset()
        index = -1
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onPrepared(mp: MediaPlayer) {
        try {
            PlayerState.duration = mp.duration.toLong()
            mp.start()
        } catch (e: Exception) {
            return
        }
        PlayerState.playing = true
        holdWake(true)
        updateNotification()
    }

    override fun onCompletion(mp: MediaPlayer) {
        if (PlayerState.repeatOne) {
            seekTo(0L)
            start()
        } else {
            next()
        }
    }

    private fun holdWake(hold: Boolean) {
        try {
            if (hold && wakeLock?.isHeld == false) wakeLock?.acquire()
            else if (!hold && wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
        }
    }

    // --- MediaSession (audífonos, Bluetooth, sistema). ---

    private fun setupSession() {
        try {
            mediaSession = MediaSession(this, "Localify").apply {
                setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() = start()
                    override fun onPause() = pause()
                    override fun onSkipToNext() = next()
                    override fun onSkipToPrevious() = prev()
                    override fun onSeekTo(pos: Long) = seekTo(pos)
                })
                isActive = true
            }
        } catch (e: Exception) {
            mediaSession = null
        }
    }

    private fun updateSession() {
        val session = mediaSession ?: return
        try {
            val state = PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SEEK_TO)
                .setState(if (PlayerState.playing)
                    PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlayerState.position, 1.0f)
                .build()
            session.setPlaybackState(state)
            val meta = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, PlayerState.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, PlayerState.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, PlayerState.duration)
                .build()
            session.setMetadata(meta)
        } catch (e: Exception) {
        }
    }

    // --- Notificación con controles. ---

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Reproducción",
                NotificationManager.IMPORTANCE_LOW)
            channel.description = "Controles de reproducción"
            channel.setShowBadge(false)
            (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)
                ?.createNotificationChannel(channel)
        }
    }

    private fun actionIntent(action: String, code: Int): PendingIntent {
        val intent = Intent(this, PlayerService::class.java).setAction(action)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, code, intent, flags)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val openApp = PendingIntent.getActivity(this, 1, openIntent, flags)
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channelId)
        } else {
            Notification.Builder(this)
        }
        val playing = PlayerState.playing
        builder.setContentTitle(
            PlayerState.title.ifEmpty { "Localify" })
            .setContentText(
                PlayerState.artist.ifEmpty { "Tu música local" })
            .setSmallIcon(if (playing) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_previous, "Anterior",
                actionIntent(ACTION_PREV, 2))
            .addAction(if (playing) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play,
                if (playing) "Pausar" else "Reproducir",
                actionIntent(ACTION_PLAY_PAUSE, 3))
            .addAction(android.R.drawable.ic_media_next, "Siguiente",
                actionIntent(ACTION_NEXT, 4))
        if (Build.VERSION.SDK_INT >= 21 && mediaSession != null) {
            try {
                builder.setStyle(Notification.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2))
            } catch (e: Exception) {
            }
        }
        return builder.build()
    }

    private fun ensureForeground() {
        updateSession()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(notifId, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(notifId, notif)
        }
    }

    private fun updateNotification() {
        updateSession()
        (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)
            ?.notify(notifId, buildNotification())
    }
}
