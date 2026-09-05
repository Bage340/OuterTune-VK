package com.dd3boh.outertune.playback.downloadManager

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner.Companion.scanDfRecursive
import com.dd3boh.outertune.utils.scanners.documentFileFromUri
import java.io.IOException
import java.io.InputStream

class DownloadDirectoryManagerOt(private var context: Context, private var dir: Uri, extraDirs: List<Uri>) {
    val TAG = DownloadDirectoryManagerOt::class.simpleName.toString()
    var mainDir: DocumentFile? = null
    var allDirs: List<DocumentFile> = mutableListOf()

    private val fileIndexLock = Any()
    private var availableFiles: Set<DocumentFile> = emptySet()
    private var availableFilesById: Map<String, DocumentFile> = emptyMap()

    init {
        doInit(context, dir, extraDirs)
    }

    fun doInit(context: Context, dir: Uri, extraDirs: List<Uri>) {
        Log.i(TAG, "Initializing download manager (directory configured=${dir != Uri.EMPTY})")
        this.context = context
        this.dir = dir
        try {
            mainDir = documentFileFromUri(context, dir)
            if (mainDir == null || !mainDir!!.isDirectory) {
                throw IOException("Invalid directory")
            }

            // TODO: .nomedia for downloads folder (permission denied)
//            if (!mainDir!!.listFiles().any { it.name == ".nomedia" }) {
//                documentFileFromUri(context, dir)?.createFile("audio/mka", ".nomedia")
//            }

            val newAllDirs = mutableListOf<DocumentFile>()
            newAllDirs.add(mainDir!!)
            if (extraDirs.isNotEmpty()) {
                newAllDirs.addAll(
                    documentFileFromUri(context, extraDirs.filterNot { it == dir }).filter { it.isDirectory }
                )
            }
            allDirs = newAllDirs.toList()
            replaceFileIndex(emptyList())
            Log.i(TAG, "Download manager initialized successfully. ${allDirs.size}")
        } catch (e: Exception) {
            if (mainDir == null) {
                Log.w(TAG, "Failed to initiate download manager: No directory provided")
            } else if (!mainDir!!.isDirectory) {
                Log.w(TAG, "Failed to initiate download manager: Not a valid directory")
            } else {
                Log.e(TAG, "Failed to initiate download manager: " + e.message)
            }

            mainDir = null
            allDirs = mutableListOf()
            replaceFileIndex(emptyList())
//            reportException(e)
//            Toast.makeText(context, "Failed to initiate download manager: " + e.message, Toast.LENGTH_LONG).show()
            // TODO: snackbar for failed uri or not set up?
        }
    }

    fun deleteFile(mediaId: String): Boolean {
        val file = isExists(mediaId)
        val deleted = file?.delete() == true
        if (deleted) {
            synchronized(fileIndexLock) {
                availableFiles = availableFiles - file
                availableFilesById = availableFilesById - mediaId
            }
        }
        return deleted
    }

    fun saveFile(mediaId: String, input: InputStream, displayName: String?): Uri? {
        val resolver = context.contentResolver
        val directory = DocumentFile.fromTreeUri(context, dir)

        if (directory == null || !directory.isDirectory) {
            throw IOException("Invalid directory")
        }

        val fileName = "$displayName [$mediaId].mka"
        val newFile = directory.createFile("audio/mka", fileName)

        newFile?.uri?.let { uri ->
            val output = resolver.openOutputStream(uri) ?: run {
                newFile.delete()
                return null
            }
            output.use { out ->
                input.copyTo(out)
            }
            addToFileIndex(mediaId, newFile)
            return uri
        }

        return null
    }

    fun isExists(mediaId: String): DocumentFile? {
        val file = synchronized(fileIndexLock) { availableFilesById[mediaId] } ?: return null
        if (file.exists()) return file
        synchronized(fileIndexLock) {
            availableFiles = availableFiles - file
            availableFilesById = availableFilesById - mediaId
        }
        return null
    }

    fun getFilePathIfExists(mediaId: String): Uri? {
        return isExists(mediaId)?.uri
    }

    fun getMissingFiles(mediaId: List<Song>): List<Song> {
        val missingFiles = mediaId.toMutableSet()
        val result = getAvailableFiles(false)
        missingFiles.removeIf { f -> result.any { it.key == f.id } }
        return missingFiles.toList()
    }

    fun getAvailableFiles() = getAvailableFiles(true)

    fun getAvailableFiles(useCache: Boolean = true): Map<String, Uri> {
        if (useCache) {
            return synchronized(fileIndexLock) {
                availableFilesById.mapValues { it.value.uri }
            }
        }

        val result = ArrayList<DocumentFile>()
        for (dir in allDirs) {
            scanDfRecursive(dir, result, true)
        }
        replaceFileIndex(result)
        return synchronized(fileIndexLock) {
            availableFilesById.mapValues { it.value.uri }
        }
    }

    fun getMainDlStorageUsage(): Long {
        if (mainDir == null) return -1L
        val result = ArrayList<DocumentFile>()
        scanDfRecursive(mainDir!!, result, true)

        return result.filter { it.name != null }.sumOf { it.length() }
    }

    fun getTotalDlStorageUsage(): Long {
        if (allDirs.isEmpty()) return 0
        return synchronized(fileIndexLock) { availableFiles.sumOf { it.length() } }
    }

    fun getExtraDlStorageUsage(): Long {
        val dirs = allDirs.filter { it != mainDir }
        if (dirs.isEmpty()) return 0
        val result = ArrayList<DocumentFile>()
        for (dir in dirs) {
            scanDfRecursive(dir, result, true)
        }

        return result.filter { it.name != null }.sumOf { it.length() }
    }

    private fun addToFileIndex(mediaId: String, file: DocumentFile) {
        synchronized(fileIndexLock) {
            availableFiles = availableFiles + file
            availableFilesById = availableFilesById + (mediaId to file)
        }
    }

    private fun replaceFileIndex(files: Collection<DocumentFile>) {
        val candidates = files.filter { it.isFile }.mapNotNull { file ->
            mediaIdFromDownloadFileName(file.name)?.let { mediaId -> mediaId to file }
        }
        // Keep a playable copy when main and extra download directories contain the same ID.
        val indexedFiles = candidates.distinctBy { it.first }.toMap()
        synchronized(fileIndexLock) {
            availableFiles = files.toSet()
            availableFilesById = indexedFiles
        }
    }

}

internal fun mediaIdFromDownloadFileName(fileName: String?): String? {
    val name = fileName ?: return null
    if (!name.endsWith(".mka", ignoreCase = true)) return null
    val stem = name.dropLast(4)
    if (!stem.endsWith(']')) return null
    val openingBracket = stem.lastIndexOf(" [")
    if (openingBracket < 0 || openingBracket + 2 >= stem.lastIndex) return null
    return stem.substring(openingBracket + 2, stem.lastIndex)
}
