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
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun RealMatchPredictionCard(
    matchCount: Int,
    playerNames: List<String>,
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

    val players = remember(playerNames) { playerNames }

    LaunchedEffect(database) {
        savedPredictions = withContext(Dispatchers.IO) {
            database.reconcileSavedPredictions()
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
                    "Прогноз реального матча",
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
                    "В базе: ${players.size} игроков · ${matchCount} матчей",
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
                                        val history = withContext(Dispatchers.IO) {
                                            database.loadMatches()
                                        }
                                        val result = withContext(Dispatchers.Default) {
                                            MatchPredictionEngine.calculate(
                                                history = history,
                                                playerA = playerA.trim(),
                                                playerB = playerB.trim(),
                                                surface = selectedSurface,
                                                matchDate = validDate,
                                                model = model,
                                                historyAlreadyOrdered = true
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
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Прогноз на ${item.matchDate}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        item.source,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(item.playerA, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text(
                                TennisPredictor.formatPercent(item.probabilityA),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(item.playerB, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text(
                                TennisPredictor.formatPercent(item.probabilityB),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    LinearProgressIndicator(
                        progress = item.probabilityA.toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    HorizontalDivider()
                    val reliability = reliabilityLabel(item.uncertaintyLabel)
                    Text(
                        "Надёжность оценки: $reliability",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = when (reliability) {
                            "Высокая" -> MaterialTheme.colorScheme.primary
                            "Низкая" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                    Text(
                        "Почему: ${item.uncertaintyReason}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Диапазон при изменении Elo обоих игроков на ±100: " +
                            "${TennisPredictor.formatPercent(item.probabilityLow)}–${TennisPredictor.formatPercent(item.probabilityHigh)}",
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Это диапазон чувствительности, а не статистический доверительный интервал.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            PlayerFormComparisonCard(item)
            ForecastExplanationCard(item)
            HeadToHeadCard(item)
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

private fun reliabilityLabel(uncertainty: String): String = when (uncertainty) {
    "Низкая" -> "Высокая"
    "Средняя" -> "Средняя"
    else -> "Низкая"
}

@Composable
private fun PlayerFormComparisonCard(item: MatchForecast) {
    Card {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Форма игроков",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Последние результаты до даты прогноза и статистика на выбранном покрытии.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PlayerEvidenceCard(
                    evidence = item.playerAEvidence,
                    surface = item.surface,
                    modifier = Modifier.weight(1f)
                )
                PlayerEvidenceCard(
                    evidence = item.playerBEvidence,
                    surface = item.surface,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun PlayerEvidenceCard(
    evidence: PlayerEvidence,
    surface: Surface,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                evidence.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2
            )
            Text(
                "${evidence.overallElo.roundToInt()} Elo",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text("Общий рейтинг", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Text("Последние матчи", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            if (evidence.recentMatches > 0) {
                Text(
                    "${evidence.recentFormLabel} побед · ${TennisPredictor.formatPercent(evidence.recentWinRate)}",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text("Нет данных", style = MaterialTheme.typography.bodySmall)
            }
            Text("На ${surface.displayName.lowercase(Locale.ROOT)}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (evidence.surfaceRecentMatches > 0) {
                    "${evidence.surfaceRecentWins}/${evidence.surfaceRecentMatches} побед · Elo ${evidence.surfaceElo.roundToInt()}"
                } else {
                    "Матчей: ${evidence.surfaceMatches} · Elo ${evidence.surfaceElo.roundToInt()}"
                },
                style = MaterialTheme.typography.bodySmall
            )
            HorizontalDivider()
            Text("Всего матчей: ${evidence.totalMatches}", style = MaterialTheme.typography.bodySmall)
            Text("На покрытии: ${evidence.surfaceMatches}", style = MaterialTheme.typography.bodySmall)
            Text(
                if (evidence.lastMatchDate.isBlank()) {
                    "Последний матч: нет датированных данных"
                } else {
                    val date = displayHistoryDate(evidence.lastMatchDate)
                    val days = evidence.daysSinceLastMatch
                    if (days != null) "Последний матч: $date · $days дн. назад" else "Последний матч: $date"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (evidence.usedUndatedMatches > 0) {
                Text(
                    "Без даты: ${evidence.usedUndatedMatches}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ForecastExplanationCard(item: MatchForecast) {
    val trainedModelUsed = item.activeFeatureIndices.isNotEmpty()
    val indices = item.activeFeatureIndices
    val overallDelta = item.overallRatingDifference.roundToInt()
    val surfaceDelta = (item.playerAEvidence.surfaceElo - item.playerBEvidence.surfaceElo).roundToInt()
    val overallAdvantage = when {
        overallDelta > 0 -> "преимущество ${item.playerA}"
        overallDelta < 0 -> "преимущество ${item.playerB}"
        else -> "рейтинги равны"
    }
    val surfaceAdvantage = when {
        surfaceDelta > 0 -> "выше у ${item.playerA}"
        surfaceDelta < 0 -> "выше у ${item.playerB}"
        else -> "одинаковый рейтинг"
    }

    Card {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text(
                "Почему получилась такая вероятность",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (trainedModelUsed) {
                    "Использована обученная логистическая регрессия. Ниже — факторы, которые входят в выбранный профиль."
                } else {
                    "Использован проверенный Elo baseline. Форма, покрытие и личные встречи остаются справочной статистикой, пока модель не докажет улучшение Log Loss и Brier Score."
                },
                style = MaterialTheme.typography.bodyMedium
            )
            InsightRow(
                title = "Общий Elo",
                value = "${item.playerAEvidence.overallElo.roundToInt()} vs ${item.playerBEvidence.overallElo.roundToInt()}",
                detail = "Разница ${signedElo(overallDelta)} · $overallAdvantage"
            )

            if (!trainedModelUsed || 1 in indices) {
                InsightRow(
                    title = "Рейтинг на ${item.surface.displayName.lowercase(Locale.ROOT)}",
                    value = "${item.playerAEvidence.surfaceElo.roundToInt()} vs ${item.playerBEvidence.surfaceElo.roundToInt()}",
                    detail = "Разница ${signedElo(surfaceDelta)} · $surfaceAdvantage. Матчей на покрытии: ${item.playerAEvidence.surfaceMatches} у A и ${item.playerBEvidence.surfaceMatches} у B."
                )
            } else {
                Text(
                    "Рейтинг на покрытии не входит в признаки этого профиля модели.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!trainedModelUsed || 2 in indices) {
                InsightRow(
                    title = "Недавняя форма",
                    value = "${item.playerAEvidence.recentFormLabel} vs ${item.playerBEvidence.recentFormLabel}",
                    detail = "Побед за последние матчи: ${winRateLabel(item.playerAEvidence)} у A и ${winRateLabel(item.playerBEvidence)} у B."
                )
            } else {
                Text(
                    "Недавняя форма не входит в признаки этого профиля модели.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (trainedModelUsed && 3 in indices) {
                InsightRow(
                    title = "Общий опыт",
                    value = "${item.playerAEvidence.totalMatches} vs ${item.playerBEvidence.totalMatches} матчей",
                    detail = "Количество сыгранных матчей входит в модель как отдельный признак."
                )
            }
            if (trainedModelUsed && 4 in indices) {
                InsightRow(
                    title = "Опыт на покрытии",
                    value = "${item.playerAEvidence.surfaceMatches} vs ${item.playerBEvidence.surfaceMatches} матчей",
                    detail = "Количество матчей на выбранном покрытии входит в модель как отдельный признак."
                )
            }
            if (trainedModelUsed && 5 in indices) {
                InsightRow(
                    title = "Личные встречи в модели",
                    value = "${item.headToHead.winsA}–${item.headToHead.winsB}",
                    detail = "Используется сглаженное преимущество по H2H; небольшая выборка автоматически приближается к нейтральному значению."
                )
            }
            if (trainedModelUsed) {
                Text(
                    "Признаки активного профиля: " +
                        indices.map { modelFeatureLabel(it) }.joinToString(", ") + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                if (trainedModelUsed && 5 in indices) {
                    "История личных встреч входит в вероятность как отдельный признак: она прошла парную проверку на независимых временных интервалах."
                } else {
                    "Личные встречи показаны для контекста и не меняют вероятность в текущем профиле."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InsightRow(title: String, value: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(value, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HeadToHeadCard(item: MatchForecast) {
    val h2h = item.headToHead
    Card {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text(
                "Личные встречи",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Датированные матчи — до ${item.matchDate}. Записей без даты: ${h2h.undatedMatches}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(item.playerA, style = MaterialTheme.typography.labelMedium, maxLines = 2)
                    Text(
                        h2h.winsA.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text("побед", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(item.playerB, style = MaterialTheme.typography.labelMedium, maxLines = 2)
                    Text(
                        h2h.winsB.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text("побед", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "На ${item.surface.displayName.lowercase(Locale.ROOT)}: ${h2h.surfaceWinsA}–${h2h.surfaceWinsB} " +
                    "(${h2h.surfaceMatches} матчей)",
                fontWeight = FontWeight.SemiBold
            )
            HorizontalDivider()
            Text("Последние встречи в истории", fontWeight = FontWeight.SemiBold)
            if (h2h.totalMatches == 0) {
                Text(
                    "В импортированной истории личных встреч не найдено.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                h2h.recentMatches.forEach { match ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "${displayHistoryDate(match.date)} · ${match.surface.displayName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("Победитель: ${match.winner}", fontWeight = FontWeight.Medium)
                    }
                }
                if (h2h.totalMatches > h2h.recentMatches.size) {
                    Text(
                        "Показаны последние ${h2h.recentMatches.size} из ${h2h.totalMatches} встреч.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "Личные встречи доступны только в пределах импортированной истории и не гарантируют повторения прежнего результата.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun displayHistoryDate(value: String): String {
    val raw = value.trim()
    return when {
        raw.isBlank() -> "Дата неизвестна"
        raw.matches(Regex("\\d{8}")) -> "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}"
        else -> raw.take(10)
    }
}

private fun signedElo(value: Int): String = String.format(Locale.ROOT, "%+d", value)

private fun winRateLabel(evidence: PlayerEvidence): String =
    if (evidence.recentMatches == 0) "нет данных"
    else TennisPredictor.formatPercent(evidence.recentWinRate)

private fun modelFeatureLabel(index: Int): String = when (index) {
    0 -> "общий Elo"
    1 -> "Elo покрытия"
    2 -> "недавняя форма"
    3 -> "общий опыт"
    4 -> "опыт на покрытии"
    5 -> "сглаженная история личных встреч"
    else -> "признак $index"
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
        findPlayerSuggestions(players, value, limit = 8)
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
            properties = PopupProperties(focusable = false),
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
