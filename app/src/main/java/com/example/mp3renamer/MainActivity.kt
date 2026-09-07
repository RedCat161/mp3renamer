package com.example.mp3renamer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyHint: TextView
    private lateinit var tvFolderSummary: TextView
    private lateinit var adapter: Mp3Adapter

    // Мини-плеер
    private lateinit var miniPlayer: View
    private lateinit var tvNowPlaying: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: ImageView
    private var userIsSeeking = false

    private var treeUri: Uri? = null
    private val items = mutableListOf<Mp3Item>()

    // Длительности файлов, ключ — имя файла
    private val durationsMs = mutableMapOf<String, Long>()
    private val durationExecutor: ExecutorService = Executors.newFixedThreadPool(3)

    // Восстановление позиции воспроизведения после перезапуска приложения
    private var pendingResumeName: String? = null
    private var pendingResumePositionMs: Int = 0
    private var lastResumeSaveMs = 0L

    // Служба воспроизведения
    private var playbackService: PlaybackService? = null
    private var serviceBound = false

    // Убирает СТАРУЮ нумерацию перед названием. Признак нумерации — это цифры
    // в начале имени, сразу за которыми (может быть через пробелы) идёт ЗНАК
    // ПУНКТУАЦИИ, а ПОСЛЕ этого знака — НЕ цифра (иначе это десятичное число вроде "3.14").
    private val numberPrefixRegex = Regex("""^\d+\s*[^\p{L}\p{N}\s]\s*(?!\d)""")

    // Достаёт число из начала имени файла (для кнопки "Сортировать по номеру").
    private val leadingDigitsRegex = Regex("""^(\d+)""")

    private val prefs by lazy { getSharedPreferences("mp3renamer_prefs", MODE_PRIVATE) }

    private val openFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            handleFolderSelected(uri)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* результат не критичен: без разрешения просто не будет видно уведомление */ }

    private val playbackListener = object : PlaybackListener {
        override fun onPlaybackState(isPlaying: Boolean, index: Int, positionMs: Int, durationMs: Int) {
            runOnUiThread {
                updateMiniPlayerUi(isPlaying, index, positionMs, durationMs)
                adapter.setPlayingPosition(if (isPlaying) index else -1)
                saveResumeState(index, positionMs, forceImmediate = !isPlaying)
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as PlaybackService.LocalBinder
            playbackService = localBinder.getService()
            playbackService?.setListener(playbackListener)
            serviceBound = true
            refreshPlaybackUiFromService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        emptyHint = findViewById(R.id.tvEmptyHint)
        tvFolderSummary = findViewById(R.id.tvFolderSummary)

        miniPlayer = findViewById(R.id.miniPlayer)
        tvNowPlaying = findViewById(R.id.tvNowPlaying)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        seekBar = findViewById(R.id.seekBar)
        btnPlayPause = findViewById(R.id.btnPlayPause)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = Mp3Adapter(
            items,
            onPlayClick = { position -> togglePlay(position) },
            durationProvider = { name -> durationsMs[name] }
        )
        recyclerView.adapter = adapter

        val touchHelper = ItemTouchHelper(
            DragTouchHelperCallback(adapter) {
                saveCurrentOrder()
                syncServicePlaylist()
            }
        )
        touchHelper.attachToRecyclerView(recyclerView)

        findViewById<MaterialButton>(R.id.btnChooseFolder).setOnClickListener {
            openFolderLauncher.launch(null)
        }
        findViewById<MaterialButton>(R.id.btnNumber).setOnClickListener { numberFiles() }
        findViewById<MaterialButton>(R.id.btnShuffle).setOnClickListener { shuffleFiles() }
        findViewById<MaterialButton>(R.id.btnSortAsc).setOnClickListener { sortAscendingByNumber() }

        findViewById<ImageView>(R.id.btnPrev).setOnClickListener { playbackService?.previous() }
        findViewById<ImageView>(R.id.btnNext).setOnClickListener { playbackService?.next() }
        findViewById<ImageView>(R.id.btnSleepTimer).setOnClickListener { showSleepTimerDialog() }
        btnPlayPause.setOnClickListener {
            val service = playbackService ?: return@setOnClickListener
            if (service.isPlaying()) service.pause() else service.resume()
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

        miniPlayer.setOnClickListener {
            if ((playbackService?.getCurrentIndex() ?: -1) >= 0) {
                startActivity(Intent(this, NowPlayingActivity::class.java))
            }
        }

        requestNotificationPermissionIfNeeded()
        restoreLastFolderIfPossible()
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
        saveCurrentOrder()
    }

    override fun onDestroy() {
        super.onDestroy()
        durationExecutor.shutdownNow()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun restoreLastFolderIfPossible() {
        val savedUriString = prefs.getString(KEY_TREE_URI, null) ?: return
        val uri = Uri.parse(savedUriString)
        val stillGranted = contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (stillGranted) {
            treeUri = uri
            loadMp3Files(uri)
        }
    }

    private fun handleFolderSelected(uri: Uri) {
        treeUri = uri
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
        loadMp3Files(uri)
    }

    private fun loadMp3Files(uri: Uri) {
        val dir = DocumentFile.fromTreeUri(this, uri)
        items.clear()
        durationsMs.clear()

        if (dir != null && dir.isDirectory) {
            val files = dir.listFiles()
                .filter { it.isFile && (it.name?.lowercase()?.endsWith(".mp3") == true) }

            var ordered = files.sortedBy { it.name?.lowercase() }

            val savedOrder = prefs.getString(orderKey(uri), null)
            if (savedOrder != null) {
                val savedNames = savedOrder.split(ORDER_SEPARATOR)
                val indexOf = savedNames.withIndex().associate { (i, name) -> name to i }
                ordered = ordered.sortedWith(
                    compareBy(
                        { indexOf[it.name] ?: Int.MAX_VALUE },
                        { it.name?.lowercase() }
                    )
                )
            }

            items.addAll(ordered.map { Mp3Item(it) })
        }

        adapter.notifyDataSetChanged()
        updateEmptyState()
        checkPendingResume(uri)
        loadDurationsAsync()
        syncServicePlaylist()

        if (items.isEmpty()) {
            Toast.makeText(this, "MP3-файлы не найдены в выбранной папке", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateEmptyState() {
        emptyHint.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun shuffleFiles() {
        if (items.size < 2) return
        Collections.shuffle(items)
        adapter.notifyDataSetChanged()
        saveCurrentOrder()
        syncServicePlaylist()
    }

    /** Сортирует список по числу в начале имени файла (по возрастанию). Файлы без номера — в конец. */
    private fun sortAscendingByNumber() {
        if (items.size < 2) return
        items.sortWith(
            compareBy(
                { leadingDigitsRegex.find(it.documentFile.name ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE },
                { it.documentFile.name?.lowercase() }
            )
        )
        adapter.notifyDataSetChanged()
        saveCurrentOrder()
        syncServicePlaylist()
        Toast.makeText(this, "Отсортировано по номеру", Toast.LENGTH_SHORT).show()
    }

    private fun stripExistingNumber(name: String): String = name.replace(numberPrefixRegex, "")

    private fun orderKey(uri: Uri) = KEY_ORDER_PREFIX + uri.toString()

    private fun saveCurrentOrder() {
        val uri = treeUri ?: return
        if (items.isEmpty()) return
        val serialized = items.joinToString(ORDER_SEPARATOR) { it.documentFile.name ?: "" }
        prefs.edit().putString(orderKey(uri), serialized).apply()
    }

    /**
     * Нумерует файлы в текущем порядке списка (001, 002, ...), убирая старую нумерацию.
     * Порядок элементов в списке НЕ меняется — переименовываем файл на его текущей позиции,
     * поэтому после переименования достаточно перерисовать экран текущим списком,
     * без повторного чтения папки с диска (это раньше вызывало "прыгающие" позиции).
     */
    private fun numberFiles() {
        if (items.isEmpty()) {
            Toast.makeText(this, "Список пуст", Toast.LENGTH_SHORT).show()
            return
        }
        if (treeUri == null) {
            Toast.makeText(this, "Сначала выберите папку", Toast.LENGTH_SHORT).show()
            return
        }

        val originalNames = items.map { it.documentFile.name ?: "unknown.mp3" }
        var errorCount = 0

        for (item in items) {
            val tempName = "tmp_${UUID.randomUUID()}.mp3"
            try {
                item.documentFile.renameTo(tempName)
            } catch (e: Exception) {
                errorCount++
            }
        }

        items.forEachIndexed { index, item ->
            val cleanedName = stripExistingNumber(originalNames[index])
            val number = String.format("%03d", index + 1)
            val newName = "$number - $cleanedName"
            try {
                item.documentFile.renameTo(newName)
            } catch (e: Exception) {
                errorCount++
            }
        }

        adapter.notifyDataSetChanged()
        saveCurrentOrder()
        durationsMs.clear()
        loadDurationsAsync()
        syncServicePlaylist()

        if (errorCount == 0) {
            Toast.makeText(this, "Файлы пронумерованы (${items.size})", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "Готово, но с ошибками: $errorCount файл(ов) не удалось переименовать",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ---------- Длительности файлов ----------

    private fun loadDurationsAsync() {
        val snapshot = items.toList()
        for (item in snapshot) {
            val name = item.documentFile.name ?: continue
            if (durationsMs.containsKey(name)) continue
            val uri = item.documentFile.uri
            durationExecutor.execute {
                val retriever = MediaMetadataRetriever()
                var duration = 0L
                try {
                    retriever.setDataSource(applicationContext, uri)
                    duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                } catch (e: Exception) {
                    // не удалось прочитать метаданные — оставим 0, не критично
                } finally {
                    try {
                        retriever.release()
                    } catch (e: Exception) {
                    }
                }
                runOnUiThread {
                    durationsMs[name] = duration
                    val idx = items.indexOfFirst { it.documentFile.name == name }
                    if (idx >= 0) adapter.notifyItemChanged(idx)
                    updateFolderSummary()
                }
            }
        }
    }

    private fun updateFolderSummary() {
        if (items.isEmpty()) {
            tvFolderSummary.visibility = View.GONE
            return
        }
        tvFolderSummary.visibility = View.VISIBLE
        val totalMs = items.sumOf { durationsMs[it.documentFile.name] ?: 0L }
        val knownCount = items.count { durationsMs.containsKey(it.documentFile.name) }
        tvFolderSummary.text = if (knownCount == items.size) {
            "${items.size} треков • всего ${formatDuration(totalMs)}"
        } else {
            "${items.size} треков"
        }
    }

    // ---------- Служба воспроизведения ----------

    private fun togglePlay(position: Int) {
        if (position < 0 || position >= items.size) return
        val service = playbackService ?: return

        if (service.getCurrentIndex() == position) {
            if (service.isPlaying()) service.pause() else service.resume()
            return
        }

        ContextCompat.startForegroundService(this, Intent(this, PlaybackService::class.java))
        syncServicePlaylist()

        var startPos = 0
        val resumeName = pendingResumeName
        if (resumeName != null && items.getOrNull(position)?.documentFile?.name == resumeName) {
            startPos = pendingResumePositionMs
            pendingResumeName = null
            if (startPos > 0) {
                Toast.makeText(this, "Продолжаем с ${formatDuration(startPos.toLong())}", Toast.LENGTH_SHORT).show()
            }
        }
        service.playAt(position, startPos)
    }

    /** Передаёт в сервис актуальный плейлист и корректирует индекс текущего трека после перестановки. */
    private fun syncServicePlaylist() {
        val service = playbackService ?: return
        val currentName = service.getCurrentTrackName()
        val tracks = items.map { Track(it.documentFile.uri, it.documentFile.name ?: "") }
        service.setPlaylist(tracks)
        if (currentName != null) {
            val newIndex = items.indexOfFirst { it.documentFile.name == currentName }
            service.updateCurrentIndex(newIndex)
        }
    }

    private fun refreshPlaybackUiFromService() {
        val service = playbackService ?: return
        val name = service.getCurrentTrackName()
        if (name == null) {
            miniPlayer.visibility = View.GONE
            return
        }
        val idx = items.indexOfFirst { it.documentFile.name == name }
        if (idx < 0) {
            miniPlayer.visibility = View.GONE
            return
        }
        updateMiniPlayerUi(service.isPlaying(), idx, service.getCurrentPositionMs(), service.getDurationMs())
        adapter.setPlayingPosition(if (service.isPlaying()) idx else -1)
    }

    private fun updateMiniPlayerUi(isPlaying: Boolean, index: Int, positionMs: Int, durationMs: Int) {
        if (index < 0 || index >= items.size) {
            miniPlayer.visibility = View.GONE
            return
        }
        miniPlayer.visibility = View.VISIBLE
        tvNowPlaying.text = items[index].documentFile.name ?: ""
        btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        tvCurrentTime.text = formatDuration(positionMs.toLong())
        tvTotalTime.text = formatDuration(durationMs.toLong())
        if (!userIsSeeking) {
            seekBar.max = if (durationMs > 0) durationMs else 100
            seekBar.progress = positionMs.coerceAtMost(seekBar.max)
        }
    }

    private fun showSleepTimerDialog() {
        val labels = arrayOf("15 минут", "30 минут", "45 минут", "60 минут", "Отключить таймер")
        val minutesValues = arrayOf(15, 30, 45, 60, null)
        AlertDialog.Builder(this)
            .setTitle("Таймер сна")
            .setItems(labels) { _, which ->
                playbackService?.setSleepTimer(minutesValues[which])
                val message = if (minutesValues[which] != null) {
                    "Таймер сна: ${labels[which]}"
                } else {
                    "Таймер сна отключён"
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ---------- Восстановление позиции воспроизведения ----------

    private fun checkPendingResume(uri: Uri) {
        val savedFolder = prefs.getString(KEY_RESUME_FOLDER, null)
        if (savedFolder != uri.toString()) {
            pendingResumeName = null
            return
        }
        val savedName = prefs.getString(KEY_RESUME_FILENAME, null)
        if (savedName != null && items.any { it.documentFile.name == savedName }) {
            pendingResumeName = savedName
            pendingResumePositionMs = prefs.getInt(KEY_RESUME_POSITION, 0)
        }
    }

    private fun saveResumeState(index: Int, positionMs: Int, forceImmediate: Boolean) {
        val uri = treeUri ?: return
        if (index < 0 || index >= items.size) return
        val now = System.currentTimeMillis()
        if (!forceImmediate && now - lastResumeSaveMs < 3000) return
        lastResumeSaveMs = now
        val name = items[index].documentFile.name ?: return
        prefs.edit()
            .putString(KEY_RESUME_FOLDER, uri.toString())
            .putString(KEY_RESUME_FILENAME, name)
            .putInt(KEY_RESUME_POSITION, positionMs)
            .apply()
    }

    companion object {
        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_ORDER_PREFIX = "order_"
        private const val ORDER_SEPARATOR = "||"
        private const val KEY_RESUME_FOLDER = "resume_folder"
        private const val KEY_RESUME_FILENAME = "resume_filename"
        private const val KEY_RESUME_POSITION = "resume_position"
    }
}
