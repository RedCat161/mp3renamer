package com.example.mp3renamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle

/** Один трек в текущем плейлисте сервиса. */
data class Track(val uri: Uri, val displayName: String)

/** Слушатель состояния воспроизведения — реализуется активностью. */
interface PlaybackListener {
    fun onPlaybackState(isPlaying: Boolean, index: Int, positionMs: Int, durationMs: Int)
}

class PlaybackService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    private val binder = LocalBinder()
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var mediaSession: MediaSessionCompat

    private var playlist: List<Track> = emptyList()
    private var currentIndex: Int = -1
    private var listener: PlaybackListener? = null
    private var repeatMode: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private var sleepTimerRunnable: Runnable? = null
    private var progressRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        mediaSession = MediaSessionCompat(this, "Mp3RenamerSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { resume() }
                override fun onPause() { pause() }
                override fun onSkipToNext() { next() }
                override fun onSkipToPrevious() { previous() }
                override fun onSeekTo(pos: Long) { seekTo(pos.toInt()) }
                override fun onStop() { stopPlayback() }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> resume()
            ACTION_PAUSE -> pause()
            ACTION_NEXT -> next()
            ACTION_PREV -> previous()
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setListener(l: PlaybackListener?) {
        listener = l
    }

    fun setPlaylist(tracks: List<Track>) {
        playlist = tracks
    }

    /** Обновляет индекс текущего трека без остановки плеера — используется после перестановки строк. */
    fun updateCurrentIndex(newIndex: Int) {
        currentIndex = newIndex
    }

    fun getCurrentIndex(): Int = currentIndex

    fun getCurrentTrackName(): String? = playlist.getOrNull(currentIndex)?.displayName

    fun getCurrentTrackUri(): Uri? = playlist.getOrNull(currentIndex)?.uri

    /** enabled == true — по окончании трека проигрывать его же заново вместо перехода к следующему. */
    fun setRepeatMode(enabled: Boolean) {
        repeatMode = enabled
    }

    fun isRepeatMode(): Boolean = repeatMode

    fun isPlaying(): Boolean = try {
        mediaPlayer?.isPlaying == true
    } catch (e: Exception) {
        false
    }

    fun getCurrentPositionMs(): Int = try {
        mediaPlayer?.currentPosition ?: 0
    } catch (e: Exception) {
        0
    }

    fun getDurationMs(): Int = try {
        mediaPlayer?.duration ?: 0
    } catch (e: Exception) {
        0
    }

    /** Запускает трек по индексу, при необходимости сразу перематывая на startPositionMs (восстановление позиции). */
    fun playAt(index: Int, startPositionMs: Int = 0) {
        if (index < 0 || index >= playlist.size) return
        releasePlayer()
        currentIndex = index
        val track = playlist[index]
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(this@PlaybackService, track.uri)
                setOnPreparedListener {
                    if (startPositionMs > 0) it.seekTo(startPositionMs)
                    it.start()
                    updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING)
                    startForegroundNotification()
                    startProgressUpdates()
                    notifyState()
                }
                setOnCompletionListener {
                    onTrackFinished()
                }
                setOnErrorListener { _, _, _ ->
                    onTrackFinished()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            onTrackFinished()
        }
    }

    private fun onTrackFinished() {
        if (repeatMode) {
            playAt(currentIndex)
            return
        }
        if (currentIndex + 1 < playlist.size) {
            playAt(currentIndex + 1)
        } else {
            stopPlayback()
        }
    }

    fun resume() {
        val player = mediaPlayer ?: return
        try {
            player.start()
            updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING)
            startForegroundNotification()
            startProgressUpdates()
            notifyState()
        } catch (e: Exception) {
        }
    }

    fun pause() {
        val player = mediaPlayer ?: return
        try {
            if (player.isPlaying) player.pause()
            updateMediaSessionState(PlaybackStateCompat.STATE_PAUSED)
            updateNotification()
            notifyState()
        } catch (e: Exception) {
        }
    }

    fun next() {
        if (currentIndex + 1 < playlist.size) playAt(currentIndex + 1)
    }

    fun previous() {
        if (currentIndex - 1 >= 0) playAt(currentIndex - 1)
    }

    fun seekTo(ms: Int) {
        try {
            mediaPlayer?.seekTo(ms)
            notifyState()
        } catch (e: Exception) {
        }
    }

    fun stopPlayback() {
        handler.removeCallbacks(progressRunnable ?: Runnable {})
        releasePlayer()
        currentIndex = -1
        updateMediaSessionState(PlaybackStateCompat.STATE_STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifyState()
        stopSelf()
    }

    private fun releasePlayer() {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (e: Exception) {
            }
            it.release()
        }
        mediaPlayer = null
    }

    // ---------- Таймер сна ----------

    /** minutes == null — отключить таймер. */
    fun setSleepTimer(minutes: Int?) {
        sleepTimerRunnable?.let { handler.removeCallbacks(it) }
        sleepTimerRunnable = null
        if (minutes != null && minutes > 0) {
            val runnable = Runnable { pause() }
            sleepTimerRunnable = runnable
            handler.postDelayed(runnable, minutes * 60_000L)
        }
    }

    // ---------- Прогресс воспроизведения ----------

    private fun startProgressUpdates() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                notifyState()
                if (isPlaying()) {
                    handler.postDelayed(this, 500)
                }
            }
        }
        progressRunnable = runnable
        handler.postDelayed(runnable, 500)
    }

    private fun notifyState() {
        listener?.onPlaybackState(isPlaying(), currentIndex, getCurrentPositionMs(), getDurationMs())
    }

    // ---------- MediaSession / уведомление ----------

    private fun updateMediaSessionState(state: Int) {
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, getCurrentPositionMs().toLong(), 1f)
                .build()
        )

        val title = playlist.getOrNull(currentIndex)?.displayName ?: ""
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, getDurationMs().toLong())
                .build()
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Воспроизведение MP3", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun pendingIntentFor(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getService(this, action.hashCode(), intent, flags)
    }

    private fun buildNotification(): Notification {
        val title = playlist.getOrNull(currentIndex)?.displayName ?: "MP3 Renamer"
        val playing = isPlaying()

        val playPauseAction = if (playing) {
            NotificationCompat.Action(R.drawable.ic_pause, "Пауза", pendingIntentFor(ACTION_PAUSE))
        } else {
            NotificationCompat.Action(R.drawable.ic_play, "Play", pendingIntentFor(ACTION_PLAY))
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("MP3 Renamer")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_skip_previous, "Назад", pendingIntentFor(ACTION_PREV))
            .addAction(playPauseAction)
            .addAction(R.drawable.ic_skip_next, "Далее", pendingIntentFor(ACTION_NEXT))
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startForegroundNotification() {
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        mediaSession.isActive = false
        mediaSession.release()
    }

    companion object {
        const val CHANNEL_ID = "mp3renamer_playback"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "com.example.mp3renamer.action.PLAY"
        const val ACTION_PAUSE = "com.example.mp3renamer.action.PAUSE"
        const val ACTION_NEXT = "com.example.mp3renamer.action.NEXT"
        const val ACTION_PREV = "com.example.mp3renamer.action.PREV"
        const val ACTION_STOP = "com.example.mp3renamer.action.STOP"
    }
}
