package ru.tennispredictor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { PredictorScreen() }
            }
        }
    }
}

private data class ImportUiResult(
    val inserted: Int,
    val duplicates: Int,
    val invalidRows: Int,
    val parseIssues: List<String>,
    val allMatches: List<StoredMatch>
)

@Composable
private fun PredictorScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember { MatchDatabase(context.applicationContext) }

    var playerA by remember { mutableStateOf("Player A") }
    var playerB by remember { mutableStateOf("Player B") }
    var ratingA by remember { mutableStateOf("1600") }
    var ratingB by remember { mutableStateOf("1500") }
    var prediction by remember {
        mutableStateOf(TennisPredictor.predict(playerA, playerB, 1600.0, 1500.0))
    }
    var error by remember { mutableStateOf<String?>(null) }

    var storedMatches by remember { mutableStateOf<List<StoredMatch>>(emptyList()) }
    var isImporting by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var importMessageIsError by remember { mutableStateOf(false) }

    var selectedRatingSurface by remember { mutableStateOf<Surface?>(null) }
    var surfaceMenuExpanded by remember { mutableStateOf(false) }
    var historicalMessage by remember { mutableStateOf<String?>(null) }
    var trainedModel by remember { mutableStateOf<LogisticRegressionModel?>(null) }
    var predictionSource by remember { mutableStateOf("Источник: базовая модель Elo") }

    val ratingSnapshot = remember(storedMatches) { RatingCalculator.calculate(storedMatches) }

    DisposableEffect(database) {
        onDispose { database.close() }
    }

    LaunchedEffect(database) {
        storedMatches = withContext(Dispatchers.IO) { database.loadMatches() }
        trainedModel = withContext(Dispatchers.IO) { ModelStore(context).load() }
    }

    val csvPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isImporting = true
                importMessage = null
                try {
                    val result = withContext(Dispatchers.IO) {
                        val csv = requireNotNull(context.contentResolver.openInputStream(uri)) {
                            "Не удалось открыть выбранный файл."
                        }.bufferedReader(Charsets.UTF_8).use { it.readText() }

                        val parsed = MatchCsvImporter.parse(csv)
                        require(parsed.records.isNotEmpty()) {
                            "В CSV не найдено корректных матчей. Проверь заголовки и содержимое файла."
                        }
                        val saved = database.insertMatches(parsed.records)
                        ImportUiResult(
                            inserted = saved.inserted,
                            duplicates = saved.duplicates,
                            invalidRows = parsed.invalidRows,
                            parseIssues = parsed.issues,
                            allMatches = database.loadMatches()
                        )
                    }
                    storedMatches = result.allMatches
                    importMessageIsError = false
                    importMessage = buildString {
                        append("Импорт завершён. Добавлено: ${result.inserted}; повторов пропущено: ${result.duplicates}.")
                        if (result.invalidRows > 0) append(" Ошибочных строк: ${result.invalidRows}.")
                        if (result.parseIssues.isNotEmpty()) {
                            append(" Пример: ${result.parseIssues.first()}.")
                        }
                    }
                    historicalMessage = null
                } catch (exception: Exception) {
                    importMessageIsError = true
                    importMessage = exception.message ?: "Не удалось импортировать CSV-файл."
                } finally {
                    isImporting = false
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "TENNIS PREDICTOR AI",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text("Прогнозы на основе Elo и локальной истории матчей")

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Участники матча",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = playerA,
                    onValueChange = { playerA = it },
                    label = { Text("Игрок A") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ratingA,
                    onValueChange = { ratingA = it },
                    label = { Text("Рейтинг Elo игрока A") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = playerB,
                    onValueChange = { playerB = it },
                    label = { Text("Игрок B") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ratingB,
                    onValueChange = { ratingB = it },
                    label = { Text("Рейтинг Elo игрока B") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )

                Box {
                    OutlinedButton(onClick = { surfaceMenuExpanded = true }) {
                        Text("Рейтинги / покрытие: ${selectedRatingSurface?.displayName ?: "Все покрытия"}")
                    }
                    DropdownMenu(
                        expanded = surfaceMenuExpanded,
                        onDismissRequest = { surfaceMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Все покрытия") },
                            onClick = {
                                selectedRatingSurface = null
                                surfaceMenuExpanded = false
                            }
                        )
                        listOf(Surface.HARD, Surface.CLAY, Surface.GRASS).forEach { surface ->
                            DropdownMenuItem(
                                text = { Text(surface.displayName) },
                                onClick = {
                                    selectedRatingSurface = surface
                                    surfaceMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                Text(
                    "Покрытие применяется к признакам модели. При выборе «Все покрытия» для будущего матча используется хард.",
                    style = MaterialTheme.typography.bodySmall
                )

                OutlinedButton(
                    onClick = {
                        val rows = ratingSnapshot.playersFor(selectedRatingSurface)
                        val foundA = rows.firstOrNull { it.name.equals(playerA.trim(), ignoreCase = true) }
                        val foundB = rows.firstOrNull { it.name.equals(playerB.trim(), ignoreCase = true) }
                        ratingA = (foundA?.rating?.roundToInt() ?: 1500).toString()
                        ratingB = (foundB?.rating?.roundToInt() ?: 1500).toString()
                        historicalMessage = buildString {
                            append("Подставлены исторические Elo: ")
                            append("${foundA?.rating?.roundToInt() ?: 1500} и ${foundB?.rating?.roundToInt() ?: 1500}.")
                            if (foundA == null || foundB == null) {
                                append(" Для игрока без записей используется базовый рейтинг 1500.")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Подставить Elo из истории")
                }
                historicalMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

                Button(
                    onClick = {
                        val a = ratingA.toDoubleOrNull()
                        val b = ratingB.toDoubleOrNull()
                        error = when {
                            playerA.isBlank() || playerB.isBlank() -> "Укажи имена обоих игроков."
                            playerA.trim().equals(playerB.trim(), true) -> "Игроки должны различаться."
                            a == null || b == null || a !in 500.0..3000.0 || b !in 500.0..3000.0 ->
                                "Рейтинг Elo должен быть числом от 500 до 3000."
                            else -> null
                        }
                        if (error == null) {
                            val enteredA = a!!
                            val enteredB = b!!
                            val matchA = playerA.trim()
                            val matchB = playerB.trim()
                            val matchSurface = selectedRatingSurface ?: Surface.HARD
                            val modelSnapshot = trainedModel
                            val historySnapshot = storedMatches.toList()
                            scope.launch {
                                val learnedProbability = withContext(Dispatchers.Default) {
                                    modelSnapshot?.let {
                                        ModelTrainer.predictFutureProbability(
                                            history = historySnapshot,
                                            playerA = matchA,
                                            playerB = matchB,
                                            surface = matchSurface,
                                            eloA = enteredA,
                                            eloB = enteredB,
                                            model = it
                                        )
                                    }
                                }
                                if (learnedProbability != null) {
                                    prediction = Prediction(
                                        playerA = matchA,
                                        playerB = matchB,
                                        probabilityA = learnedProbability,
                                        probabilityB = 1.0 - learnedProbability,
                                        ratingDifference = enteredA - enteredB
                                    )
                                    predictionSource = "Источник: логистическая регрессия (${modelSnapshot?.profileName ?: "модель"})"
                                } else {
                                    prediction = TennisPredictor.predict(matchA, matchB, enteredA, enteredB)
                                    predictionSource = when {
                                        modelSnapshot == null -> "Источник: Elo — обученной модели пока нет"
                                        !modelSnapshot.recommendedForPrediction -> "Источник: Elo — модель не улучшила качество на временной проверке"
                                        else -> "Источник: Elo — история изменилась после обучения; переобучи модель"
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Рассчитать прогноз") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }

        Card {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Вероятности победы",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(predictionSource, style = MaterialTheme.typography.bodySmall)
                Text(prediction.playerA)
                Text(
                    TennisPredictor.formatPercent(prediction.probabilityA),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(prediction.playerB)
                Text(
                    TennisPredictor.formatPercent(prediction.probabilityB),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text("Разница рейтингов: ${prediction.ratingDifference.toInt()} Elo")
            }
        }

        Card {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Исторические данные",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text("Матчей на устройстве: ${storedMatches.size}")
                Text("Игроков в рейтинге: ${ratingSnapshot.overall.size}")
                Text(
                    "Импортируй CSV из памяти телефона. История сохраняется локально и не отправляется на сервер.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = { csvPicker.launch(arrayOf("*/*")) },
                    enabled = !isImporting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isImporting) "Импортируем…" else "Импортировать матчи из CSV")
                }
                if (isImporting) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                importMessage?.let {
                    Text(
                        it,
                        color = if (importMessageIsError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    "Поддерживаются player_a, player_b, winner, surface, date; а также ATP/WTA-архивы с winner_name, loser_name, surface и tourney_date. Даты и поверхность можно не указывать.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        RealMatchPredictionCard(matches = storedMatches, database = database, model = trainedModel)

        ModelTrainingCard(matches = storedMatches, onModelTrained = { trainedModel = it })

        RankingCard(
            title = "Общий рейтинг Elo",
            subtitle = "Все покрытия · ${storedMatches.size} матчей",
            players = ratingSnapshot.overall
        )
        RankingCard(
            title = "Рейтинг на харде",
            subtitle = "${storedMatches.count { it.record.surface == Surface.HARD }} матчей",
            players = ratingSnapshot.hard
        )
        RankingCard(
            title = "Рейтинг на грунте",
            subtitle = "${storedMatches.count { it.record.surface == Surface.CLAY }} матчей",
            players = ratingSnapshot.clay
        )
        RankingCard(
            title = "Рейтинг на траве",
            subtitle = "${storedMatches.count { it.record.surface == Surface.GRASS }} матчей",
            players = ratingSnapshot.grass
        )

        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("О модели", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Elo — базовая модель, а не гарантия результата и не рекомендация по ставкам. " +
                        "Исторические рейтинги пересчитываются по датам матчей, если даты присутствуют во всём наборе. " +
                        "Для игрока без истории используется стартовый Elo 1500."
                )
            }
        }
    }
}

@Composable
private fun RankingCard(
    title: String,
    subtitle: String,
    players: List<PlayerRating>
) {
    Card {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
            if (players.isEmpty()) {
                Text("Пока нет матчей для этого рейтинга. Импортируй CSV с результатами.")
            } else {
                players.take(10).forEachIndexed { index, player ->
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${index + 1}. ${player.name}",
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2
                            )
                            Text(
                                "${player.matches} матчей · ${player.wins} побед · ${player.losses} поражений",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${player.rating.roundToInt()} Elo",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (players.size > 10) {
                    Text("Показаны первые 10 из ${players.size} игроков.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
