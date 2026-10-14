package ru.tennispredictor.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun RealMatchPredictionCard(
    history: List<StoredMatch>,
    trainedModel: LogisticRegressionModel?,
    savedPredictions: List<StoredPrediction>,
    database: MatchDatabase,
    onPredictionsChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val playerNames = remember(history) { RealMatchPredictor.playerNames(history) }

    var playerA by remember { mutableStateOf("") }
    var playerB by remember { mutableStateOf("") }
    var surface by remember { mutableStateOf(Surface.HARD) }
    var surfaceMenuExpanded by remember { mutableStateOf(false) }
    var targetMatchDate by remember { mutableStateOf("") }
    var assessment by remember { mutableStateOf<RealMatchAssessment?>(null) }
    var assessedTargetDate by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    LaunchedEffect(history.size, trainedModel?.trainedThroughDate, trainedModel?.recommendedForPrediction) {
        assessment = null
        message = null
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Этап 3 · Прогноз реального матча",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Имена берутся из локальной истории матчей. Прогноз использует рейтинг на выбранном покрытии, " +
                        "последние результаты и обученную модель, только если временная проверка подтвердила её пользу."
                )

                PlayerAutocompleteField(
                    label = "Игрок A",
                    value = playerA,
                    names = playerNames,
                    onValueChange = {
                        playerA = it
                        assessment = null
                    },
                    onSelect = {
                        playerA = it
                        assessment = null
                    }
                )
                PlayerAutocompleteField(
                    label = "Игрок B",
                    value = playerB,
                    names = playerNames,
                    onValueChange = {
                        playerB = it
                        assessment = null
                    },
                    onSelect = {
                        playerB = it
                        assessment = null
                    }
                )

                Box {
                    OutlinedButton(
                        onClick = { surfaceMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Покрытие: ${surface.displayName}")
                    }
                    DropdownMenu(
                        expanded = surfaceMenuExpanded,
                        onDismissRequest = { surfaceMenuExpanded = false }
                    ) {
                        listOf(Surface.HARD, Surface.CLAY, Surface.GRASS, Surface.INDOOR).forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.displayName) },
                                onClick = {
                                    surface = option
                                    surfaceMenuExpanded = false
                                    assessment = null
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = targetMatchDate,
                    onValueChange = {
                        targetMatchDate = it
                        assessment = null
                    },
                    label = { Text("Дата матча (необязательно, ГГГГ-ММ-ДД)") },
                    supportingText = {
                        Text("Если указать дату, прогноз можно будет автоматически проверить после импорта результата.")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        val a = playerA.trim()
                        val b = playerB.trim()
                        val date = targetMatchDate.trim()
                        val validDate = date.isEmpty() || runCatching { LocalDate.parse(date) }.isSuccess
                        when {
                            a.isBlank() || b.isBlank() -> {
                                isError = true
                                message = "Укажи имена обоих игроков."
                            }
                            a.equals(b, ignoreCase = true) -> {
                                isError = true
                                message = "Выбери двух разных игроков."
                            }
                            !validDate -> {
                                isError = true
                                message = "Дата должна быть в формате ГГГГ-ММ-ДД, например 2026-10-15."
                            }
                            else -> {
                                isError = false
                                message = null
                                busy = true
                                val historySnapshot = history.toList()
                                val selectedModel = trainedModel
                                val selectedSurface = surface
                                scope.launch {
                                    try {
                                        val result = withContext(Dispatchers.Default) {
                                            RealMatchPredictor.assess(
                                                history = historySnapshot,
                                                playerA = a,
                                                playerB = b,
                                                surface = selectedSurface,
                                                model = selectedModel
                                            )
                                        }
                                        assessment = result
                                        assessedTargetDate = date
                                        message = "Прогноз рассчитан. Его можно сохранить для последующей проверки."
                                    } catch (exception: Exception) {
                                        isError = true
                                        message = exception.message ?: "Не удалось рассчитать прогноз."
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "Рассчитываем…" else "Рассчитать прогноз")
                }

                if (busy) {
                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                message?.let {
                    Text(
                        it,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                assessment?.let { result ->
                    HorizontalDivider()
                    Text("Вероятности победы", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(result.source, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(result.playerA, fontWeight = FontWeight.SemiBold)
                            Text(
                                TennisPredictor.formatPercent(result.probabilityA),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Диапазон: ${TennisPredictor.formatPercent(result.lowerProbabilityA)}–" +
                                    "${TennisPredictor.formatPercent(result.upperProbabilityA)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(result.playerB, fontWeight = FontWeight.SemiBold)
                            Text(
                                TennisPredictor.formatPercent(result.probabilityB),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Диапазон: ${TennisPredictor.formatPercent(1.0 - result.upperProbabilityA)}–" +
                                    "${TennisPredictor.formatPercent(1.0 - result.lowerProbabilityA)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Text(result.supportLabel, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Ориентировочный 80% диапазон рассчитан эвристически по объёму истории игрока и покрытия; " +
                            "это не калиброванный доверительный интервал."
                    )
                    HorizontalDivider()
                    Text("Последние результаты", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    FormDetails(result.playerA, result.formA, surface)
                    FormDetails(result.playerB, result.formB, surface)
                    Text(
                        "Elo на выбранном покрытии: ${result.ratingA.roundToInt()} / ${result.ratingB.roundToInt()}",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Button(
                        onClick = {
                            val draft = PredictionToStore(
                                targetMatchDate = assessedTargetDate,
                                playerA = result.playerA,
                                playerB = result.playerB,
                                surface = result.surface,
                                probabilityA = result.probabilityA,
                                lowerProbabilityA = result.lowerProbabilityA,
                                upperProbabilityA = result.upperProbabilityA,
                                overallMatchesA = result.formA.totalMatches,
                                overallMatchesB = result.formB.totalMatches,
                                surfaceMatchesA = result.formA.surfaceMatches,
                                surfaceMatchesB = result.formB.surfaceMatches,
                                recentFormA = "общая: ${result.formA.recentOverallResults}; покрытие: ${result.formA.recentSurfaceResults}",
                                recentFormB = "общая: ${result.formB.recentOverallResults}; покрытие: ${result.formB.recentSurfaceResults}",
                                source = result.source
                            )
                            scope.launch {
                                busy = true
                                try {
                                    withContext(Dispatchers.IO) {
                                        database.savePrediction(draft)
                                        database.settlePredictionsFromResults(history)
                                    }
                                    onPredictionsChanged()
                                    message = "Прогноз сохранён на устройстве."
                                    isError = false
                                } catch (exception: Exception) {
                                    message = exception.message ?: "Не удалось сохранить прогноз."
                                    isError = true
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Сохранить прогноз")
                    }
                }
            }
        }

        SavedPredictionsCard(
            predictions = savedPredictions,
            database = database,
            onPredictionsChanged = onPredictionsChanged
        )
    }
}

@Composable
private fun PlayerAutocompleteField(
    label: String,
    value: String,
    names: List<String>,
    onValueChange: (String) -> Unit,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val suggestions = remember(value, names) { RealMatchPredictor.searchPlayers(value, names, limit = 8) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = it.isNotBlank()
            },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        DropdownMenu(
            expanded = expanded && suggestions.isNotEmpty(),
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            suggestions.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelect(name)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun FormDetails(
    playerName: String,
    form: PlayerFormSnapshot,
    surface: Surface
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "$playerName · всего матчей: ${form.totalMatches}, на покрытии «${surface.displayName}»: ${form.surfaceMatches}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
        Text(
            "Последние ${form.recentOverallCount}: ${form.recentOverallResults} · " +
                "побед ${form.recentOverallWins}/${form.recentOverallCount}",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Последние на покрытии ${form.recentSurfaceCount}: ${form.recentSurfaceResults}",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SavedPredictionsCard(
    predictions: List<StoredPrediction>,
    database: MatchDatabase,
    onPredictionsChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var busyPredictionId by remember { mutableStateOf<Long?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }

    Card {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Сохранённые прогнозы", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (predictions.isEmpty()) {
                Text("Пока нет сохранённых прогнозов. Рассчитай прогноз реального матча и нажми «Сохранить прогноз».")
            } else {
                val settled = predictions.filter { it.logLoss != null && it.brierScore != null }
                Text("Сохранено: ${predictions.size}. Проверено: ${settled.size}.")
                if (settled.isNotEmpty()) {
                    val meanLogLoss = settled.mapNotNull { it.logLoss }.average()
                    val meanBrier = settled.mapNotNull { it.brierScore }.average()
                    val correct = settled.count { it.wasCorrect == true }
                    Text(
                        "По проверенным прогнозам: попадание ${(100.0 * correct / settled.size).roundToInt()}%; " +
                            "средний Log Loss ${formatMetric(meanLogLoss)}, Brier Score ${formatMetric(meanBrier)}."
                    )
                } else {
                    Text("После получения результата отметь победителя или импортируй матч с указанной датой.")
                }

                predictions.take(10).forEach { prediction ->
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            "${prediction.playerA} — ${prediction.playerB}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Создан: ${formatTimestamp(prediction.createdAtMillis)} · " +
                                "дата матча: ${prediction.targetMatchDate.ifBlank { "не указана" }} · ${prediction.surface.displayName}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "${prediction.playerA}: ${TennisPredictor.formatPercent(prediction.probabilityA)} " +
                                "(диапазон ${TennisPredictor.formatPercent(prediction.lowerProbabilityA)}–" +
                                "${TennisPredictor.formatPercent(prediction.upperProbabilityA)}); " +
                                "${prediction.playerB}: ${TennisPredictor.formatPercent(1.0 - prediction.probabilityA)}"
                        )
                        Text("Форма на момент прогноза: ${prediction.playerA} — ${prediction.recentFormA}; ${prediction.playerB} — ${prediction.recentFormB}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Источник: ${prediction.source}", style = MaterialTheme.typography.bodySmall)
                        if (prediction.settledWinner != null) {
                            val resultText = if (prediction.wasCorrect == true) "верен" else "неверен"
                            Text(
                                "Результат: победил ${prediction.settledWinner}. Прогноз ${resultText}. " +
                                    "Log Loss ${formatMetric(prediction.logLoss ?: 0.0)}, Brier ${formatMetric(prediction.brierScore ?: 0.0)}.",
                                color = if (prediction.wasCorrect == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        } else {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        settle(prediction, prediction.playerA, database, scope, onPredictionsChanged) {
                                            busyPredictionId = it
                                            actionMessage = null
                                        }
                                    },
                                    enabled = busyPredictionId == null,
                                    modifier = Modifier.weight(1f)
                                ) { Text("Победил ${prediction.playerA}") }
                                Button(
                                    onClick = {
                                        settle(prediction, prediction.playerB, database, scope, onPredictionsChanged) {
                                            busyPredictionId = it
                                            actionMessage = null
                                        }
                                    },
                                    enabled = busyPredictionId == null,
                                    modifier = Modifier.weight(1f)
                                ) { Text("Победил ${prediction.playerB}") }
                            }
                            if (busyPredictionId == prediction.id) {
                                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                if (predictions.size > 10) {
                    Text("Показаны 10 последних прогнозов из ${predictions.size}.")
                }
            }
            actionMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun settle(
    prediction: StoredPrediction,
    winner: String,
    database: MatchDatabase,
    scope: kotlinx.coroutines.CoroutineScope,
    onPredictionsChanged: () -> Unit,
    onBusyChanged: (Long?) -> Unit
) {
    onBusyChanged(prediction.id)
    scope.launch {
        try {
            val updated = withContext(Dispatchers.IO) {
                database.settlePrediction(prediction.id, winner)
            }
            onPredictionsChanged()
            onBusyChanged(null)
        } catch (_: Exception) {
            onBusyChanged(null)
        }
    }
}

private fun formatTimestamp(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.getDefault()))

private fun formatMetric(value: Double): String =
    String.format(Locale.ROOT, "%.4f", value)
