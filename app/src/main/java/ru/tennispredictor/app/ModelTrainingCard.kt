package ru.tennispredictor.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun ModelTrainingCard(
    matches: List<StoredMatch>,
    onModelTrained: (LogisticRegressionModel) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Этап 2 — проверка и выбор модели") }
    var report by remember { mutableStateOf<ModelComparisonReport?>(null) }

    Card {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(message, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Сравниваются 3 набора признаков и 2 конфигурации оптимизации. " +
                    "Выбор делается по трём последовательным временным интервалам; последние 15% дат — отдельная финальная проверка."
            )
            if (matches.size < ModelTrainer.MINIMUM_DATED_MATCHES) {
                Text("Матчей импортировано: ${matches.size}. Для обучения требуется минимум ${ModelTrainer.MINIMUM_DATED_MATCHES} корректных датированных матчей.")
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

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
                    else MaterialTheme.colorScheme.error
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
                    val snapshot = matches.toList()
                    busy = true
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.Default) {
                                ModelTrainer.trainAndEvaluate(snapshot)
                            }
                            withContext(Dispatchers.IO) { ModelStore(context).save(result.model) }
                            onModelTrained(result.model)
                            message = "Проверка завершена: ${result.testMatches} финальных тестовых матчей"
                            report = result
                        } catch (e: Exception) {
                            message = e.message ?: "Ошибка обучения"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && matches.size >= ModelTrainer.MINIMUM_DATED_MATCHES,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) "Обучаем и проверяем…" else "Обучить, сравнить и выбрать модель")
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
