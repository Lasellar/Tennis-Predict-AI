package ru.tennispredictor.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun ModelTrainingCard(
    database: MatchDatabase,
    matchCount: Int,
    onModelTrained: (LogisticRegressionModel) -> Unit
) {
    val context = LocalContext.current
    val workManager = remember { WorkManager.getInstance(context.applicationContext) }
    val operationWorkInfos by remember(workManager) {
        workManager.getWorkInfosForUniqueWorkFlow(BackgroundOperationWorker.UNIQUE_WORK_NAME)
    }.collectAsState(initial = emptyList())
    val operationWorkInfo = operationWorkInfos.firstOrNull { !it.state.isFinished }
        ?: operationWorkInfos.lastOrNull()
    val operationInProgress = operationWorkInfo?.state?.isFinished == false
    val operationType = when {
        operationWorkInfo?.tags?.contains(BackgroundOperationWorker.TAG_RESTORE) == true -> BackgroundOperationWorker.OP_RESTORE
        operationWorkInfo?.tags?.contains(BackgroundOperationWorker.TAG_IMPORT) == true -> BackgroundOperationWorker.OP_IMPORT
        operationWorkInfo?.tags?.contains(BackgroundOperationWorker.TAG_TRAIN) == true -> BackgroundOperationWorker.OP_TRAIN
        else -> null
    }
    val busy = operationInProgress && operationType == BackgroundOperationWorker.OP_TRAIN
    val trainingProgress = if (busy) {
        operationWorkInfo?.progress?.getInt(BackgroundOperationWorker.KEY_PROGRESS, 0) ?: 0
    } else 0
    val trainingStatus = operationWorkInfo?.progress?.getString(BackgroundOperationWorker.KEY_STATUS)
        ?: "Подготовка к обучению"
    val startAfterNotificationPermission = rememberNotificationPermissionGate()
    var message by remember { mutableStateOf("Сравним варианты по историческим результатам") }
    var report by remember { mutableStateOf(BackgroundOperationResults.latestTrainingReport) }

    LaunchedEffect(
        operationWorkInfo?.id,
        operationWorkInfo?.state,
        operationWorkInfo?.progress?.getInt(BackgroundOperationWorker.KEY_PROGRESS, 0),
        operationWorkInfo?.progress?.getString(BackgroundOperationWorker.KEY_STATUS)
    ) {
        val info = operationWorkInfo ?: return@LaunchedEffect
        if (!info.tags.contains(BackgroundOperationWorker.TAG_TRAIN)) {
            return@LaunchedEffect
        }
        when (info.state) {
            WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                report = null
                message = info.progress.getString(BackgroundOperationWorker.KEY_STATUS)
                    ?: "Обучение модели выполняется в фоне"
            }
            WorkInfo.State.SUCCEEDED -> {
                report = BackgroundOperationResults.latestTrainingReport
                message = info.outputData.getString(BackgroundOperationWorker.KEY_MESSAGE)
                    ?: "Обучение модели завершено."
                val savedModel = withContext(Dispatchers.IO) { ModelStore(context).load() }
                if (savedModel != null) onModelTrained(savedModel)
            }
            WorkInfo.State.FAILED -> {
                report = null
                message = info.outputData.getString(BackgroundOperationWorker.KEY_ERROR) ?: "Ошибка обучения модели"
            }
            else -> Unit
        }
    }

    Card {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(message, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Сравниваются 10 наборов признаков и 2 конфигурации обучения (20 вариантов). " +
                    "Недавняя форма и личные встречи проходят отдельные парные проверки. " +
                    "Кандидат допускается к прогнозам только если Log Loss и Brier не ухудшаются на каждом из трёх временных интервалов относительно Elo, " +
                    "а новые факторы доказали полезность. Последние 15% дат — отдельная финальная проверка."
            )
            if (matchCount < ModelTrainer.MINIMUM_DATED_MATCHES) {
                Text("Матчей импортировано: ${matchCount}. Для обучения требуется минимум ${ModelTrainer.MINIMUM_DATED_MATCHES} корректных датированных матчей.")
            }
            if (busy) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("$trainingProgress%", fontWeight = FontWeight.SemiBold)
                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                    Text(
                        trainingStatus,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LinearProgressIndicator(
                    progress = trainingProgress / 100f,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            report?.let { r ->
                HorizontalDivider()
                Text(
                    if (r.logisticSelectedForPrediction) {
                        "Для прогнозов выбрана логистическая регрессия"
                    } else {
                        "Для прогнозов оставлен Elo: модель не улучшила средний Log Loss на проверочных интервалах"
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = if (r.logisticSelectedForPrediction) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Выбранный вариант: ${r.selectedModelName}")
                Text(
                    "Исторические проверки (среднее по 3 интервалам): " +
                        "Log Loss — Elo ${fmt(r.validationEloMeanLogLoss)}, модель ${fmt(r.validationModelMeanLogLoss)}; " +
                        "Brier — Elo ${fmt(r.validationEloMeanBrier)}, модель ${fmt(r.validationModelMeanBrier)}."
                )
                HorizontalDivider()
                Text("Независимая финальная проверка", fontWeight = FontWeight.SemiBold)
                Text("Обучающая часть: ${r.trainStartDate} — ${r.trainEndDate} · ${r.trainMatches} матчей")
                Text("Отложенная часть: ${r.testStartDate} — ${r.testEndDate} · ${r.testMatches} матчей")
                Row(Modifier.fillMaxWidth()) {
                    Text("Метрика", Modifier.weight(1.2f), fontWeight = FontWeight.SemiBold)
                    Text("Elo", Modifier.weight(0.8f), textAlign = TextAlign.End, fontWeight = FontWeight.SemiBold)
                    Text("Регрессия", Modifier.weight(1.2f), textAlign = TextAlign.End, fontWeight = FontWeight.SemiBold)
                }
                MetricLine("Log Loss", r.eloMetrics.logLoss, r.logisticMetrics.logLoss)
                MetricLine("Brier Score", r.eloMetrics.brierScore, r.logisticMetrics.brierScore)
                MetricLine("Точность", r.eloMetrics.accuracy, r.logisticMetrics.accuracy)

                HorizontalDivider()
                Text("Последовательные временные интервалы", fontWeight = FontWeight.SemiBold)
                r.validationFolds.forEachIndexed { index, fold ->
                    Text("Интервал ${index + 1}: ${fold.startDate} — ${fold.endDate}; обучение ${fold.trainMatches}, проверка ${fold.testMatches} матчей")
                    Text(
                        "Log Loss: Elo ${fmt(fold.eloMetrics.logLoss)} / модель ${fmt(fold.modelMetrics.logLoss)}; " +
                            "Brier: Elo ${fmt(fold.eloMetrics.brierScore)} / модель ${fmt(fold.modelMetrics.brierScore)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                HorizontalDivider()
                Text("Полезность новых факторов: парные проверки", fontWeight = FontWeight.SemiBold)
                Text(
                    "Δ = метрика с фактором минус метрика без него; отрицательная разница лучше. " +
                        "Фактор проходит проверку, если ни в одном из трёх интервалов не ухудшены обе метрики более чем на 0,0001, " +
                        "и хотя бы одна средняя метрика улучшилась минимум на 0,0001.",
                    style = MaterialTheme.typography.bodySmall
                )
                r.featureEffects.forEach { effect ->
                    HorizontalDivider()
                    Text(
                        "${effect.factorName} · ${effect.candidateName}",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "База: ${effect.baselineCandidateName}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Средняя Δ Log Loss: ${signedMetric(effect.meanLogLossDelta)} · " +
                            "Δ Brier: ${signedMetric(effect.meanBrierDelta)} · " +
                            if (effect.passesNoWorseGate) "фактор подтверждён" else "фактор не подтверждён",
                        color = if (effect.passesNoWorseGate) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "По интервалам Log Loss: " +
                            effect.folds.joinToString(" / ") { signedMetric(it.logLossDelta) } +
                            "; Brier: " +
                            effect.folds.joinToString(" / ") { signedMetric(it.brierDelta) },
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                HorizontalDivider()
                Text("Сравнение вариантов", fontWeight = FontWeight.SemiBold)
                r.candidateResults.forEachIndexed { index, candidate ->
                    Text(
                        "${index + 1}. ${candidate.name}" +
                            if (index == 0) " · лучший по CV" else "",
                        fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal
                    )
                    Text(
                        "Признаки: ${candidate.featureIndices.size}; итерации: ${candidate.iterations}; " +
                            "шаг: ${candidate.learningRate}; L2: ${candidate.l2Strength}. " +
                            "Средние Log Loss/Brier: ${fmt(candidate.meanLogLoss)} / ${fmt(candidate.meanBrierScore)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        when {
                            candidate.eligibleForPrediction -> "Допущен к прогнозам: оба quality gate пройдены"
                            !candidate.passesEloTemporalGate -> "Не допущен: Log Loss/Brier не прошли проверку против Elo на всех интервалах"
                            !candidate.featureEvidencePassed -> "Не допущен: форма или личные встречи не прошли отдельную проверку"
                            else -> "Не допущен к прогнозам"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (candidate.eligibleForPrediction) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "В анализ вошло ${r.eligibleMatches} датированных матчей. Без корректной даты исключено: " +
                        "${r.excludedUndatedMatches}; записей с некорректными игроками/победителем: ${r.excludedInvalidMatches}.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Коэффициенты модели сохранены на устройстве. Финальная отложенная выборка не использовалась для выбора конфигурации.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Button(
                onClick = {
                    message = "Ставим обучение в очередь…"
                    startAfterNotificationPermission {
                        BackgroundOperations.enqueueTraining(context)
                    }
                },
                enabled = !operationInProgress && matchCount >= ModelTrainer.MINIMUM_DATED_MATCHES,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        busy -> "Обучаем и проверяем… $trainingProgress%"
                        operationInProgress && operationType == BackgroundOperationWorker.OP_RESTORE ->
                            "Восстанавливаем историю…"
                        operationInProgress -> "Выполняется импорт архивов…"
                        else -> "Обучить, сравнить и выбрать модель"
                    }
                )
            }
        }
    }
}

@Composable
private fun MetricLine(title: String, elo: Double, logistic: Double) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(title, Modifier.weight(1.2f))
        Text(fmt(elo), Modifier.weight(0.8f), textAlign = TextAlign.End)
        Text(fmt(logistic), Modifier.weight(1.2f), textAlign = TextAlign.End)
    }
}

private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.4f", value)

private fun signedMetric(value: Double): String =
    String.format(Locale.ROOT, "%+.4f", value)
