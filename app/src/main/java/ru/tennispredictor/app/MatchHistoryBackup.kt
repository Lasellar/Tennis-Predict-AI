package ru.tennispredictor.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedWriter
import java.io.OutputStreamWriter

/**
 * A shared-storage copy of the local history allows it to be restored if a user
 * has to uninstall a sideloaded APK before installing a new build.
 *
 * The database remains the source of truth while the app is installed. The backup
 * is replaced atomically through a temporary MediaStore item, so a failed write
 * never overwrites the last known-good copy.
 */
object MatchHistoryBackup {
    const val BACKUP_FILE_NAME = "tennis_predict_history_backup.csv"
    private const val MIME_TYPE = "text/csv"
    private const val DIRECTORY = "${Environment.DIRECTORY_DOWNLOADS}/TennisPredict/"

    fun findBackup(context: Context): Uri? = findBackupUris(context).firstOrNull()

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun writeBackup(
        context: Context,
        database: MatchDatabase,
        onProgress: (percentage: Int) -> Unit = {}
    ): Uri? {
        if (!isSupported()) return null

        val resolver = context.applicationContext.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val previousBackups = findBackupUris(context)
        val stagingName = "tennis_predict_history_${System.currentTimeMillis()}.tmp.csv"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, stagingName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, DIRECTORY)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val stagingUri = requireNotNull(resolver.insert(collection, values)) {
            "Не удалось создать резервную копию истории в папке Downloads/TennisPredict."
        }

        try {
            val output = requireNotNull(resolver.openOutputStream(stagingUri, "wt")) {
                "Не удалось открыть файл резервной копии истории."
            }
            BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8), 64 * 1024).use { writer ->
                database.exportMatchesCsv(writer) { processed, total ->
                    val percentage = if (total > 0) processed * 100 / total else 100
                    onProgress(percentage.coerceIn(0, 100))
                }
            }

            val publish = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, BACKUP_FILE_NAME)
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            val updated = resolver.update(stagingUri, publish, null, null)
            check(updated > 0) { "Не удалось завершить сохранение резервной копии истории." }

            previousBackups.forEach { oldUri ->
                if (oldUri != stagingUri) runCatching { resolver.delete(oldUri, null, null) }
            }
            onProgress(100)
            return stagingUri
        } catch (exception: Exception) {
            runCatching { resolver.delete(stagingUri, null, null) }
            throw exception
        }
    }

    private fun findBackupUris(context: Context): List<Uri> {
        if (!isSupported()) return emptyList()

        val resolver = context.applicationContext.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(BACKUP_FILE_NAME, DIRECTORY)
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        val matches = mutableListOf<Uri>()
        resolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) {
                matches += ContentUris.withAppendedId(collection, cursor.getLong(idIndex))
            }
        }
        return matches
    }
}
