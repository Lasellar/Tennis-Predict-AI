package ru.tennispredictor.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

@Composable
fun RealMatchPredictionCard(
    matches: List<StoredMatch>,
    database: MatchDatabase,
    model: LogisticRegressionModel?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var playerA by remember { mutableStateOf("") }
    var playerB by remember { mutableStateOf("") }
    var matchDate by remember { mutableStateOf(LocalDate.now().plusDays(1).toString()) }
    var selectedSurface by remember { mutableStateOf(Surface.HARD) }
    var surfaceMenuExpanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var forecast by remember { mutableStateOf<MatchForecast?>(null) }
    var savedPredictions by remember { mutableStateOf<List<SavedPrediction>>(emptyList()) }

    val players = remember(matches) { MatchPredictionEngine.availablePlayers(matches) }

    LaunchedEffect(matches, model) {
        savedPredictions = withContext(Dispatchers.IO) {
            database.reconcileSavedPredictions(matches)
            database.loadSavedPredictions()
        }
    }

    fun refreshPredictions() {
        scope.launch {
            savedPredictions = withContext(Dispatchers.IO) { database.loadSavedPredictions() }
        }
    }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
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
                    "Игроков можно выбрать из импортированной истории или ввести вручную. " +
                        "Расчёт учитывает только результаты до указанной даты матча."
                )

                PlayerSearchField(
                    label = "Игрок A",
                    value = playerA,
                    players = players,
                    onValueChange = {
                        playerA = it
                        forecast = null
                    },
                    onSelect = {
                        playerA = it
                        forecast = null
                    }
                )
                PlayerSearchField(
                    label = "Игрок B",
                    value = playerB,
                    players = players,
                    onValueChange = {
                        playerB = it
                        forecast = null
                    },
                    onSelect = {
                        playerB = it
                        forecast = null
                    }
                )

                androidx.compose.material3.OutlinedTextField(
                    value = matchDate,
                    onValueChange = {
                        matchDate = it
                        forecast = null
                    },
                    label = { Text("Дата матча (ГГГГ-ММ-ДД)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Box {
                    OutlinedButton(
                        onClick = { surfaceMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Покрытие: ${selectedSurface.displayName}") }
                    DropdownMenu(
                        expanded = surfaceMenuExpanded,
                        onDismissRequest = { surfaceMenuExpanded = false }
                    ) {
                        listOf(Surface.HARD, Surface.CLAY, Surface.GRASS, Surface.INDOOR).forEach { surface ->
                            DropdownMenuItem(
                                text = { Text(surface.displayName) },
                                onClick = {
                                    selectedSurface = surface
                                    surfaceMenuExpanded = false
                                    forecast = null
                                }
                            )
                        }
                    }
                }

                Text(
                    "В базе: ${players.size} игроков · ${matches.size} матчей",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = {
                        val validDate = runCatching { LocalDate.parse(matchDate.trim()).toString() }.getOrNull()
                        when {
                            playerA.isBlank() || playerB.isBlank() -> {
                                message = "Укажи имена обоих игроков."
                                messageIsError = true
                            }
                            playerA.trim().equals(playerB.trim(), ignoreCase = true) -> {
                                message = "Игроки должны различаться."
                                messageIsError = true
                            }
                            validDate == null -> {
                                message = "Дата должна быть в формате ГГГГ-ММ-ДД, например 2026-10-15."
                                messageIsError = true
                            }
                            else -> {
                                busy = true
                                message = null
                                messageIsError = false
                                scope.launch {
                                    try {
                                        val result = withContext(Dispatchers.Default) {
                                            MatchPredictionEngine.calculate(
                                                history = matches,
                                                playerA = playerA.trim(),
                                                playerB = playerB.trim(),
                                                surface = selectedSurface,
                                                matchDate = validDate,
                                                model = model
                                            )
                                        }
                                        val a = result.playerAEvidence
                                        val b = result.playerBEvidence
                                        val record = SavedPrediction(
                                            createdAt = System.currentTimeMillis(),
                                            matchDate = result.matchDate,
                                            playerA = result.playerA,
                                            playerB = result.playerB,
                                            surface = result.surface,
                                            probabilityA = result.probabilityA,
                                            overallEloA = a.overallElo,
                                            overallEloB = b.overallElo,
                                            surfaceEloA = a.surfaceElo,
                                            surfaceEloB = b.surfaceElo,
                                            formA = a.recentFormLabel,
                                            formB = b.recentFormLabel,
                                            totalMatchesA = a.totalMatches,
                                            totalMatchesB = b.totalMatches,
                                            surfaceMatchesA = a.surfaceMatches,
                                            surfaceMatchesB = b.surfaceMatches,
                                            source = result.source,
                                            uncertaintyLabel = result.uncertaintyLabel,
                                            uncertaintyReason = result.uncertaintyReason,
                                            probabilityLow = result.probabilityLow,
                                            probabilityHigh = result.probabilityHigh
                                        )
                                        val rowId = withContext(Dispatchers.IO) { database.savePrediction(record) }
                                        if (rowId < 0L) throw IllegalStateException("Не удалось сохранить прогноз в локальной базе.")
                                        forecast = result
                                        savedPredictions = withContext(Dispatchers.IO) { database.loadSavedPredictions() }
                                        message = "Прогноз рассчитан и сохранён на устройстве. После матча импортируй результат или отметь победителя ниже."
                                        messageIsError = false
                                    } catch (exception: Exception) {
                                        message = exception.message ?: "Не удалось рассчитать прогноз."
                                        messageIsError = true
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
                    Text(if (busy) "Рассчитываем…" else "Рассчитать и сохранить прогноз")
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let {
                    Text(
                        it,
                        color = if (messageIsError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        forecast?.let { item ->
            Card {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Прогноз сохранён · ${item.matchDate}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(item.source, style = MaterialTheme.typography.bodySmall)
                    Text(item.playerA, fontWeight = FontWeight.SemiBold)
                    Text(
                        TennisPredictor.formatPercent(item.probabilityA),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(item.playerB, fontWeight = FontWeight.SemiBold)
                    Text(
                        TennisPredictor.formatPercent(item.probabilityB),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    HorizontalDivider()
                    Text(
                        "Неопределённость: ${item.uncertaintyLabel.lowercase(Locale.ROOT)}",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(item.uncertaintyReason, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Диапазон чувствительности вероятности A: " +
                            "${TennisPredictor.formatPercent(item.probabilityLow)}–${TennisPredictor.formatPercent(item.probabilityHigh)}",
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Диапазон рассчитан при изменении Elo каждого игрока на ±100 пунктов; " +
                            "это не статистический доверительный интервал.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    HorizontalDivider()
                    Text("История до матча", fontWeight = FontWeight.SemiBold)
                    Text(
                        "${item.playerA}: Elo ${item.playerAEvidence.overallElo.toInt()} · " +
                            "последние ${item.playerAEvidence.recentFormLabel} · " +
                            "на покрытии матчей: ${item.playerAEvidence.surfaceMatches}"
                    )
                    Text(
                        "${item.playerB}: Elo ${item.playerBEvidence.overallElo.toInt()} · " +
                            "последние ${item.playerBEvidence.recentFormLabel} · " +
                            "на покрытии матчей: ${item.playerBEvidence.surfaceMatches}"
                    )
                }
            }
        }

        SavedPredictionsCard(
            predictions = savedPredictions,
            onResolve = { item, winner ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        database.resolvePredictionResult(item.id, winner)
                    }
                    refreshPredictions()
                }
            }
        )
    }
}

@Composable
private fun PlayerSearchField(
    label: String,
    value: String,
    players: List<String>,
    onValueChange: (String) -> Unit,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val suggestions = remember(value, players) {
        if (value.isBlank()) emptyList()
        else players.filter { it.contains(value.trim(), ignoreCase = true) }
            .filterNot { it.equals(value.trim(), ignoreCase = true) }
            .take(8)
    }
    Box {
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        DropdownMenu(
            expanded = expanded && suggestions.isNotEmpty(),
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 280.dp)
        ) {
            suggestions.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text(suggestion) },
                    onClick = {
                        onSelect(suggestion)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SavedPredictionsCard(
    predictions: List<SavedPrediction>,
    onResolve: (SavedPrediction, String) -> Unit
) {
    val resolved = remember(predictions) { SavedPredictionEvaluator.evaluate(predictions) }
    Card {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Сохранённые прогнозы и точность",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text("Всего прогнозов: ${predictions.size} · проверено результатов: ${resolved.resolved}")
            if (resolved.resolved > 0) {
                Text(
                    "Точность выбранного исхода: ${TennisPredictor.formatPercent(resolved.accuracy)} · " +
                        "Log Loss: ${formatMetric(resolved.logLoss)} · Brier: ${formatMetric(resolved.brierScore)}"
                )
            } else {
                Text("После завершения матчей отметь победителя или импортируй результат на запланированную дату.")
            }

            if (predictions.isEmpty()) {
                Text("Здесь появятся сохранённые прогнозы.")
            } else {
                predictions.take(12).forEach { item ->
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${item.matchDate} · ${item.surface.displayName}", style = MaterialTheme.typography.bodySmall)
                        Text("${item.playerA} — ${item.playerB}", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Вероятности: ${TennisPredictor.formatPercent(item.probabilityA)} / " +
                                "${TennisPredictor.formatPercent(1.0 - item.probabilityA)} · неопределённость: ${item.uncertaintyLabel.lowercase(Locale.ROOT)}"
                        )
                        if (!item.resultWinner.isNullOrBlank()) {
                            val winnerIsA = item.resultWinner.trim().equals(item.playerA, ignoreCase = true)
                            val correct = (item.probabilityA >= 0.5) == winnerIsA
                            Text(
                                "Результат: ${item.resultWinner} · ${if (correct) "прогноз угадан" else "прогноз не сбылся"}",
                                color = if (correct) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        } else {
                            Text("Отметить результат:")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onResolve(item, item.playerA) },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Победил A", maxLines = 1) }
                                Button(
                                    onClick = { onResolve(item, item.playerB) },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Победил B", maxLines = 1) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "При импорте архивов прогноз автоматически сверяется только с матчем, совпадающим по дате, обоим игрокам и покрытию. Результат можно отметить вручную.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun formatMetric(value: Double): String =
    String.format(Locale.US, "%.4f", value)
