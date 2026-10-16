package ru.tennispredictor.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

object BackgroundOperationResults {
    @Volatile
    var latestTrainingReport: ModelComparisonReport? = null
}

@Composable
fun rememberNotificationPermissionGate(): (() -> Unit) -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var requestInProgress by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        val action = pendingAction
        pendingAction = null
        requestInProgress = false
        action?.invoke()
    }
    return { action ->
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            if (!requestInProgress) {
                pendingAction = action
                requestInProgress = true
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            action()
        }
    }
}

object BackgroundOperations {
    fun enqueueImport(context: Context, uris: List<Uri>) {
        val input = Data.Builder()
            .putString(BackgroundOperationWorker.KEY_OPERATION, BackgroundOperationWorker.OP_IMPORT)
            .putStringArray(BackgroundOperationWorker.KEY_URIS, uris.map(Uri::toString).toTypedArray())
            .build()
        enqueue(context, input, BackgroundOperationWorker.TAG_IMPORT)
    }

    fun enqueueRestore(context: Context, backupUri: Uri) {
        val input = Data.Builder()
            .putString(BackgroundOperationWorker.KEY_OPERATION, BackgroundOperationWorker.OP_RESTORE)
            .putStringArray(BackgroundOperationWorker.KEY_URIS, arrayOf(backupUri.toString()))
            .build()
        enqueue(context, input, BackgroundOperationWorker.TAG_IMPORT, BackgroundOperationWorker.TAG_RESTORE)
    }

    fun enqueueTraining(context: Context) {
        val input = workDataOf(
            BackgroundOperationWorker.KEY_OPERATION to BackgroundOperationWorker.OP_TRAIN
        )
        enqueue(context, input, BackgroundOperationWorker.TAG_TRAIN)
    }

    private fun enqueue(
        context: Context,
        input: Data,
        tag: String,
        additionalTag: String? = null
    ) {
        val requestBuilder = OneTimeWorkRequestBuilder<BackgroundOperationWorker>()
            .setInputData(input)
            .addTag(tag)
        if (additionalTag != null) requestBuilder.addTag(additionalTag)
        val request = requestBuilder.build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            BackgroundOperationWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}

class BackgroundOperationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val operation = inputData.getString(KEY_OPERATION)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Не указан тип фоновой операции."))
        if (operation != OP_IMPORT && operation != OP_RESTORE && operation != OP_TRAIN) {
            return Result.failure(workDataOf(KEY_ERROR to "Неизвестный тип фоновой операции."))
        }

        createNotificationChannel(applicationContext)
        return try {
            setProgress(workDataOf(KEY_PROGRESS to 0, KEY_STATUS to "Подготовка к запуску"))
            setForeground(createForegroundInfo(operation, 0, "Подготовка к запуску"))
            val output = withProgressReporter(operation) { report ->
                when (operation) {
                    OP_IMPORT -> importArchives(report)
                    OP_RESTORE -> importArchives(report, restoringBackup = true)
                    OP_TRAIN -> trainModel(report)
                    else -> error("Неизвестный тип операции")
                }
            }
            Result.success(output)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            Result.failure(
                workDataOf(
                    KEY_ERROR to (exception.message ?: "Фоновая операция завершилась с ошибкой.")
                )
            )
        }
    }

    private suspend fun withProgressReporter(
        operation: String,
        action: suspend (report: (Int, String) -> Unit) -> Data
    ): Data = coroutineScope {
        val updates = Channel<ProgressUpdate>(capacity = Channel.CONFLATED)
        val updateJob = launch {
            var previousPercent = -1
            var previousStatus = ""
            for (update in updates) {
                val percent = update.percent.coerceIn(0, 100)
                if (percent == previousPercent && update.status == previousStatus) continue
                setProgress(
                    workDataOf(
                        KEY_PROGRESS to percent,
                        KEY_STATUS to update.status
                    )
                )
                setForeground(createForegroundInfo(operation, percent, update.status))
                previousPercent = percent
                previousStatus = update.status
            }
        }
        try {
            val output = action { percent, status ->
                updates.trySend(ProgressUpdate(percent.coerceIn(0, 100), status))
            }
            updates.send(
                ProgressUpdate(
                    100,
                    if (operation == OP_TRAIN) "Обучение модели завершено" else "История матчей готова"
                )
            )
            output
        } finally {
            updates.close()
            updateJob.join()
        }
    }

    private suspend fun importArchives(
        report: (Int, String) -> Unit,
        restoringBackup: Boolean = false
    ): Data {
        val uriStrings = inputData.getStringArray(KEY_URIS)?.toList().orEmpty()
        require(uriStrings.isNotEmpty()) { "Не выбраны CSV-файлы для импорта." }

        val combinedRecords = mutableListOf<MatchRecord>()
        val parseIssues = mutableListOf<String>()
        val fileErrors = mutableListOf<String>()
        var invalidRows = 0
        var filesWithMatches = 0
        val totalFiles = uriStrings.size

        val database = MatchDatabaseProvider.get(applicationContext)
        val saved = withContext(Dispatchers.IO) {
            uriStrings.forEachIndexed { index, uriString ->
                val uri = Uri.parse(uriString)
                report(
                    4 + index * 70 / totalFiles,
                    if (restoringBackup) "Восстановление истории из резервной копии"
                    else "Чтение архива ${index + 1} из $totalFiles"
                )
                try {
                    val csv = requireNotNull(applicationContext.contentResolver.openInputStream(uri)) {
                        "Не удалось открыть файл ${index + 1}."
                    }.bufferedReader(Charsets.UTF_8).use { it.readText() }

                    val parsed = MatchCsvImporter.parse(csv) { processed, total ->
                        val fraction = if (total > 0) processed.toDouble() / total else 1.0
                        val overallFraction = (index + fraction) / totalFiles
                        val percent = (5 + overallFraction * 70).toInt().coerceIn(5, 75)
                        report(
                            percent,
                            if (restoringBackup) "Проверка резервной копии истории"
                            else "Разбор архива ${index + 1} из $totalFiles"
                        )
                    }
                    invalidRows += parsed.invalidRows
                    if (parseIssues.size < 5) {
                        parseIssues += parsed.issues.take(5 - parseIssues.size)
                    }
                    if (parsed.records.isEmpty()) {
                        fileErrors += "Файл ${index + 1}: корректных матчей не найдено."
                    } else {
                        combinedRecords += parsed.records
                        filesWithMatches++
                    }
                } catch (exception: Exception) {
                    fileErrors += "Файл ${index + 1}: ${exception.message ?: "ошибка чтения"}."
                }
            }

            require(combinedRecords.isNotEmpty()) {
                "Не удалось найти корректные матчи ни в одном из выбранных CSV."
            }

            report(77, "Сохранение матчей в локальной базе данных")
            val inserted = database.insertMatches(combinedRecords) { processed, total ->
                val fraction = processed.toDouble() / total.coerceAtLeast(1)
                report(77 + (fraction * 16).toInt(), "Сохранение матчей в локальной базе данных")
            }
            report(94, "Обновление результатов сохранённых прогнозов")
            database.reconcileSavedPredictions()
            if (!restoringBackup) {
                if (MatchHistoryBackup.isSupported()) {
                report(96, "Создание резервной копии истории")
                MatchHistoryBackup.writeBackup(applicationContext, database) { percentage ->
                    report(96 + percentage * 3 / 100, "Сохранение резервной копии в Downloads/TennisPredict")
                }
            }
            }
            inserted
        }

        val message = buildString {
            if (restoringBackup) {
                append("История восстановлена из резервной копии. ")
            } else {
                append("Обработано CSV: $filesWithMatches/$totalFiles. ")
                append("Добавлено матчей: ${saved.inserted}; повторов пропущено: ${saved.duplicates}.")
                append(
                    if (MatchHistoryBackup.isSupported()) {
                        " Резервная копия сохранена в Downloads/TennisPredict."
                    } else {
                        " Системное резервное копирование Android также включено."
                    }
                )
            }
            if (invalidRows > 0) append(" Ошибочных строк: $invalidRows.")
            if (parseIssues.isNotEmpty()) append(" Пример ошибки строки: ${parseIssues.first()}.")
            if (fileErrors.isNotEmpty()) {
                append(" Проблемы с файлами: ${fileErrors.take(3).joinToString(" ")}")
                if (fileErrors.size > 3) append(" И ещё: ${fileErrors.size - 3}.")
            }
        }
        report(98, "Импорт завершён; обновление статистики")
        return workDataOf(KEY_MESSAGE to message)
    }

    private suspend fun trainModel(report: (Int, String) -> Unit): Data {
        report(1, "Чтение истории матчей")
        val database = MatchDatabaseProvider.get(applicationContext)
        val history = withContext(Dispatchers.IO) { database.loadMatches() }
        report(3, "Подготовка обучающей выборки")

        val result = withContext(Dispatchers.Default) {
            ModelTrainer.trainAndEvaluate(history) { percentage, status ->
                report(percentage, status)
            }
        }
        report(96, "Сохранение коэффициентов модели")
        withContext(Dispatchers.IO) {
            ModelStore(applicationContext).save(result.model)
        }
        BackgroundOperationResults.latestTrainingReport = result
        val message = buildString {
            append("Проверка завершена: ${result.testMatches} финальных тестовых матчей. ")
            append("Выбранный вариант: ${result.selectedModelName}. ")
            append(
                "Средние CV Log Loss/Brier: " +
                    String.format(Locale.ROOT, "%.4f", result.validationModelMeanLogLoss) + " / " +
                    String.format(Locale.ROOT, "%.4f", result.validationModelMeanBrier) + "."
            )
        }
        report(99, "Обучение проверено; сохранение завершено")
        return workDataOf(KEY_MESSAGE to message)
    }

    private fun createForegroundInfo(
        operation: String,
        percent: Int,
        status: String
    ): ForegroundInfo {
        val title = when (operation) {
            OP_IMPORT -> "Импорт теннисных архивов"
            OP_RESTORE -> "Восстановление истории Tennis Predict"
            else -> "Обучение модели Tennis Predict"
        }
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText("$status · $percent%")
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Импорт архивов и обучение модели",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Показывает ход длительных операций Tennis Predict"
                setShowBadge(false)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private data class ProgressUpdate(val percent: Int, val status: String)

    companion object {
        const val UNIQUE_WORK_NAME = "tennis_predict_foreground_operation"
        const val KEY_OPERATION = "operation"
        const val KEY_URIS = "uris"
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS = "status"
        const val KEY_MESSAGE = "message"
        const val KEY_ERROR = "error"
        const val OP_IMPORT = "import_archives"
        const val OP_RESTORE = "restore_history_backup"
        const val OP_TRAIN = "train_model"
        const val TAG_IMPORT = "tennis_predict_import"
        const val TAG_RESTORE = "tennis_predict_restore"
        const val TAG_TRAIN = "tennis_predict_train"
        private const val NOTIFICATION_ID = 6102
        private const val NOTIFICATION_CHANNEL_ID = "tennis_predict_long_operations"
    }
}
