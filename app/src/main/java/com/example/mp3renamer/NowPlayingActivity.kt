package com.example.mp3renamer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class NowPlayingActivity : AppCompatActivity() {

    private lateinit var ivArt: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: ImageView
    private lateinit var btnRepeat: ImageView

    private var userIsSeeking = false
    private var playbackService: PlaybackService? = null
    private var serviceBound = false
    private var lastLoadedUri: Uri? = null

    private val metadataExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val playbackListener = object : PlaybackListener {
        override fun onPlaybackState(isPlaying: Boolean, index: Int, positionMs: Int, durationMs: Int) {
            runOnUiThread {
                updatePlaybackUi(isPlaying, positionMs, durationMs)
                maybeReloadTrackInfo()
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as PlaybackService.LocalBinder
            playbackService = localBinder.getService()
            playbackService?.setListener(playbackListener)
            serviceBound = true
            val service = playbackService ?: return
            updatePlaybackUi(service.isPlaying(), service.getCurrentPositionMs(), service.getDurationMs())
            updateRepeatIcon(service.isRepeatMode())
            loadTrackInfo(service.getCurrentTrackUri(), service.getCurrentTrackName())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)

        ivArt = findViewById(R.id.ivArt)
        tvTitle = findViewById(R.id.tvTitle)
        tvArtist = findViewById(R.id.tvArtist)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        seekBar = findViewById(R.id.seekBar)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnRepeat = findViewById(R.id.btnRepeat)

        findViewById<ImageView>(R.id.btnCollapse).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.btnPrev).setOnClickListener { playbackService?.previous() }
        findViewById<ImageView>(R.id.btnNext).setOnClickListener { playbackService?.next() }

        btnPlayPause.setOnClickListener {
            val service = playbackService ?: return@setOnClickListener
            if (service.isPlaying()) service.pause() else service.resume()
        }

        btnRepeat.setOnClickListener {
            val service = playbackService ?: return@setOnClickListener
            val newState = !service.isRepeatMode()
            service.setRepeatMode(newState)
            updateRepeatIcon(newState)
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userIsSeeking = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userIsSeeking = false
                playbackService?.seekTo(seekBar?.progress ?: 0)
            }
        })
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, PlaybackService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            playbackService?.setListener(null)
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        metadataExecutor.shutdownNow()
    }

    private fun maybeReloadTrackInfo() {
        val service = playbackService ?: return
        val uri = service.getCurrentTrackUri()
        if (uri != lastLoadedUri) {
            loadTrackInfo(uri, service.getCurrentTrackName())
        }
    }

    private fun updatePlaybackUi(isPlaying: Boolean, positionMs: Int, durationMs: Int) {
        btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        tvCurrentTime.text = formatDuration(positionMs.toLong())
        tvTotalTime.text = formatDuration(durationMs.toLong())
        if (!userIsSeeking) {
            seekBar.max = if (durationMs > 0) durationMs else 100
            seekBar.progress = positionMs.coerceAtMost(seekBar.max)
        }
    }

    private fun updateRepeatIcon(enabled: Boolean) {
        btnRepeat.alpha = if (enabled) 1.0f else 0.4f
    }

    /** Загружает название/исполнителя из ID3-тегов (если есть) и встроенную обложку. */
    private fun loadTrackInfo(uri: Uri?, fallbackName: String?) {
        lastLoadedUri = uri

        tvTitle.text = fallbackName?.substringBeforeLast(".") ?: ""
        tvArtist.visibility = View.GONE
        ivArt.scaleType = ImageView.ScaleType.FIT_CENTER
        ivArt.setPadding(40, 40, 40, 40)
        ivArt.setImageResource(R.drawable.ic_launcher_foreground)

        if (uri == null) return

        metadataExecutor.execute {
            val retriever = MediaMetadataRetriever()
            var title: String? = null
            var artist: String? = null
            var artBytes: ByteArray? = null
            try {
                retriever.setDataSource(applicationContext, uri)
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                artBytes = retriever.embeddedPicture
            } catch (e: Exception) {
                // тегов нет или файл повреждён — покажем то, что есть по умолчанию
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) {
                }
            }

            runOnUiThread {
                if (lastLoadedUri != uri) return@runOnUiThread // за это время трек уже сменился
                if (!title.isNullOrBlank()) tvTitle.text = title
                if (!artist.isNullOrBlank()) {
                    tvArtist.text = artist
                    tvArtist.visibility = View.VISIBLE
                }
                val bytes = artBytes
                if (bytes != null) {
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        ivArt.scaleType = ImageView.ScaleType.CENTER_CROP
                        ivArt.setPadding(0, 0, 0, 0)
                        ivArt.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }
}
