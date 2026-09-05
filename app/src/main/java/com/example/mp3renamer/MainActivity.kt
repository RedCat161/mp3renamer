package com.example.mp3renamer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.util.Collections
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyHint: TextView
    private lateinit var adapter: Mp3Adapter

    private var treeUri: Uri? = null
    private val items = mutableListOf<Mp3Item>()

    // Убирает СТАРУЮ нумерацию перед названием. Признак нумерации — это цифры
    // в начале имени, сразу за которыми (может быть через пробелы) идёт ЗНАК
    // ПУНКТУАЦИИ (точка, подчёркивание, дефис и т.п.), а ПОСЛЕ этого знака —
    // НЕ цифра (иначе это просто десятичное число вроде "3.14", а не номер).
    // Примеры, которые СРЕЗАЮТСЯ: "01.Song.mp3", "02_Song.mp3", "001 - Song.mp3".
    // Примеры, которые НЕ трогаются:
    //   "50 Cent - ...", "1990 Song.mp3"   (после цифр пробел и буква)
    //   "3.14 Pi Song.mp3"                  (после точки снова цифра — не номер)
    // \p{L} — любая буква любого языка, \p{N} — любая цифра.
    private val numberPrefixRegex = Regex("""^\d+\s*[^\p{L}\p{N}\s]\s*(?!\d)""")

    private val prefs by lazy { getSharedPreferences("mp3renamer_prefs", MODE_PRIVATE) }

    private val openFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            handleFolderSelected(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        emptyHint = findViewById(R.id.tvEmptyHint)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = Mp3Adapter(items)
        recyclerView.adapter = adapter

        val touchHelper = ItemTouchHelper(
            DragTouchHelperCallback(adapter) { saveCurrentOrder() }
        )
        touchHelper.attachToRecyclerView(recyclerView)

        findViewById<MaterialButton>(R.id.btnChooseFolder).setOnClickListener {
            openFolderLauncher.launch(null)
        }

        findViewById<MaterialButton>(R.id.btnNumber).setOnClickListener {
            numberFiles()
        }

        findViewById<MaterialButton>(R.id.btnShuffle).setOnClickListener {
            shuffleFiles()
        }

        restoreLastFolderIfPossible()
    }

    override fun onPause() {
        super.onPause()
        saveCurrentOrder()
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

        if (dir != null && dir.isDirectory) {
            val files = dir.listFiles()
                .filter { it.isFile && (it.name?.lowercase()?.endsWith(".mp3") == true) }

            // Порядок по умолчанию — по алфавиту
            var ordered = files.sortedBy { it.name?.lowercase() }

            // Если для этой папки был сохранён пользовательский порядок — применяем его.
            // Новые файлы (которых не было в сохранённом порядке) уходят в конец по алфавиту.
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
    }

    private fun stripExistingNumber(name: String): String {
        return name.replace(numberPrefixRegex, "")
    }

    private fun orderKey(uri: Uri) = KEY_ORDER_PREFIX + uri.toString()

    private fun saveCurrentOrder() {
        val uri = treeUri ?: return
        if (items.isEmpty()) return
        val serialized = items.joinToString(ORDER_SEPARATOR) { it.documentFile.name ?: "" }
        prefs.edit().putString(orderKey(uri), serialized).apply()
    }

    /**
     * Нумерует файлы в текущем порядке списка (001, 002, ...),
     * убирая старую нумерацию (если она в формате "3 цифры + разделитель"),
     * и сохраняя остальную часть названия.
     *
     * Переименование делается в 2 прохода:
     * 1) все файлы получают временные уникальные имена — чтобы избежать
     *    конфликтов, если новое имя совпадёт с ещё не переименованным старым;
     * 2) затем каждому присваивается финальное имя "NNN - Название.mp3".
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

        // Проход 1: временные уникальные имена
        for (item in items) {
            val tempName = "tmp_${UUID.randomUUID()}.mp3"
            try {
                item.documentFile.renameTo(tempName)
            } catch (e: Exception) {
                errorCount++
            }
        }

        // Проход 2: финальные имена с нумерацией
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

        treeUri?.let { loadMp3Files(it) }
        saveCurrentOrder()

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

    companion object {
        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_ORDER_PREFIX = "order_"
        private const val ORDER_SEPARATOR = "||"
    }
}
