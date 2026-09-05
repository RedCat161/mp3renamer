package com.example.mp3renamer

import androidx.documentfile.provider.DocumentFile

/**
 * Обёртка над DocumentFile для одного mp3-файла в списке.
 * DocumentFile сам обновляет свой внутренний Uri после renameTo(),
 * поэтому достаточно хранить ссылку на него.
 */
data class Mp3Item(val documentFile: DocumentFile)
