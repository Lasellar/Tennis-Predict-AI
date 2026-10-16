package ru.tennispredictor.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TennisPredictTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { PredictorScreen() }
            }
        }
    }
}

private object HistorySummaryCache {
    @Volatile var summary: MatchDatabaseSummary? = null
    @Volatile var playerNames: List<String>? = null

    fun getOrLoadSummary(loader: () -> MatchDatabaseSummary): MatchDatabaseSummary {
        summary?.let { return it }
        return synchronized(this) {
            summary ?: loader().also { summary = it }
        }
    }

    fun getOrLoadPlayerNames(loader: () -> List<String>): List<String> {
        playerNames?.let { return it }
        return synchronized(this) {
            playerNames ?: loader().also { playerNames = it }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PredictorScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember { MatchDatabaseProvider.get(context.applicationContext) }
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
    val isRestoring = operationInProgress && operationType == BackgroundOperationWorker.OP_RESTORE
    val isImporting = operationInProgress && operationType == BackgroundOperationWorker.OP_IMPORT
    val showHistoryProgress = isImporting || isRestoring
    val importProgress = if (showHistoryProgress) {
        operationWorkInfo?.progress?.getInt(BackgroundOperationWorker.KEY_PROGRESS, 0) ?: 0
    } else 0
    val importProgressStatus = operationWorkInfo?.progress?.getString(BackgroundOperationWorker.KEY_STATUS)
        ?: "Подготовка архивов"
    val startAfterNotificationPermission = rememberNotificationPermissionGate()

    var playerA by remember { mutableStateOf("Player A") }
    var playerB by remember { mutableStateOf("Player B") }
    var ratingA by remember { mutableStateOf("1600") }
    var ratingB by remember { mutableStateOf("1500") }
    var prediction by remember {
        mutableStateOf(TennisPredictor.predict(playerA, playerB, 1600.0, 1500.0))
    }
    var error by remember { mutableStateOf<String?>(null) }

    var matchCount by remember { mutableIntStateOf(0) }
    var hardMatchCount by remember { mutableIntStateOf(0) }
    var clayMatchCount by remember { mutableIntStateOf(0) }
    var grassMatchCount by remember { mutableIntStateOf(0) }
    var ratingSnapshot by remember {
        mutableStateOf(RatingSnapshot(emptyList(), emptyList(), emptyList(), emptyList()))
    }
    var playerNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var importMessageIsError by remember { mutableStateOf(false) }

    var selectedRatingSurface by remember { mutableStateOf<Surface?>(null) }
    var surfaceMenuExpanded by remember { mutableStateOf(false) }
    var historicalMessage by remember { mutableStateOf<String?>(null) }
    var trainedModel by remember { mutableStateOf<LogisticRegressionModel?>(null) }
    var predictionSource by remember { mutableStateOf("Источник: базовая модель Elo") }

    LaunchedEffect(database) {
        val summary = withContext(Dispatchers.IO) {
            HistorySummaryCache.getOrLoadSummary { database.loadSummary() }
        }
        matchCount = summary.matchCount
        hardMatchCount = summary.hardMatchCount
        clayMatchCount = summary.clayMatchCount
        grassMatchCount = summary.grassMatchCount
        ratingSnapshot = summary.ratings

        playerNames = withContext(Dispatchers.IO) {
            HistorySummaryCache.getOrLoadPlayerNames { database.loadPlayerNames() }
        }
        trainedModel = withContext(Dispatchers.IO) { ModelStore(context).load() }

        // Create a shared-storage backup once for existing installations. On a fresh
        // install, automatically queue restoration if the previous install left a backup.
        if (MatchHistoryBackup.isSupported()) {
            if (summary.matchCount > 0) {
                withContext(Dispatchers.IO) {
                    if (MatchHistoryBackup.findBackup(context) == null) {
                        runCatching { MatchHistoryBackup.writeBackup(context, database) }
                    }
                }
            } else {
                val backupUri = withContext(Dispatchers.IO) {
                    MatchHistoryBackup.findBackup(context)
                }
                if (backupUri != null) BackgroundOperations.enqueueRestore(context, backupUri)
            }
        }
    }

    LaunchedEffect(operationWorkInfo?.id, operationWorkInfo?.state) {
        val info = operationWorkInfo ?: return@LaunchedEffect
        when {
            info.tags.contains(BackgroundOperationWorker.TAG_IMPORT) -> when (info.state) {
                WorkInfo.State.SUCCEEDED -> {
                    val refreshed = withContext(Dispatchers.IO) {
                        database.loadSummary() to database.loadPlayerNames()
                    }
                    val summary = refreshed.first
                    val names = refreshed.second
                    HistorySummaryCache.summary = summary
                    HistorySummaryCache.playerNames = names
                    matchCount = summary.matchCount
                    hardMatchCount = summary.hardMatchCount
                    clayMatchCount = summary.clayMatchCount
                    grassMatchCount = summary.grassMatchCount
                    ratingSnapshot = summary.ratings
                    playerNames = names
                    importMessageIsError = false
                    importMessage = info.outputData.getString(BackgroundOperationWorker.KEY_MESSAGE)
                        ?: "Импорт архивов завершён."
                    historicalMessage = null
                }
                WorkInfo.State.FAILED -> {
                    importMessageIsError = true
                    importMessage = info.outputData.getString(BackgroundOperationWorker.KEY_ERROR)
                        ?: "Не удалось импортировать выбранные CSV-файлы."
                }
                else -> Unit
            }
            info.tags.contains(BackgroundOperationWorker.TAG_TRAIN) && info.state == WorkInfo.State.SUCCEEDED -> {
                trainedModel = withContext(Dispatchers.IO) { ModelStore(context).load() }
            }
        }
    }

    val csvPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            startAfterNotificationPermission {
                importMessage = null
                importMessageIsError = false
                BackgroundOperations.enqueueImport(context, uris)
            }
        }
    }


    var selectedTab by remember { mutableStateOf(0) }
    var manualExpanded by remember { mutableStateOf(false) }
    val navigationTabs = listOf("Прогноз", "Данные", "Рейтинги", "Модель")

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                "TENNIS PREDICT",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                "АНАЛИТИКА ТЕННИСНЫХ МАТЧЕЙ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f),
                                letterSpacing = 0.5.sp
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "${matchCount}",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "МАТЧЕЙ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f)
                            )
                        }
                    }
                }
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    navigationTabs.forEachIndexed { index, label ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                        )
                    }
                }
            }
        }
    ) { contentPadding ->
        when (selectedTab) {
            0 -> Column(
                Modifier.fillMaxSize()
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ScreenHeading(
                    title = "Прогноз матча",
                    subtitle = "Оцени шансы игроков с учётом истории, покрытия и формы."
                )
                if (matchCount == 0) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Начни с истории матчей",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "Пока нет импортированных результатов, оценка будет опираться на базовый Elo. CSV-архив поможет учитывать форму игроков и их рейтинги на покрытиях.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            OutlinedButton(
                                onClick = { selectedTab = 1 },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Перейти к импорту")
                            }
                        }
                    }
                }
                RealMatchPredictionCard(
                    matchCount = matchCount,
                    playerNames = playerNames,
                    database = database,
                    model = trainedModel
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { manualExpanded = !manualExpanded }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                "Ручной прогноз по Elo",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Для быстрой оценки по известным рейтингам",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            if (manualExpanded) "Свернуть" else "Настроить",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (manualExpanded) {
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
                            PlayerNameField(
                                value = playerA,
                                onValueChange = { playerA = it },
                                label = "Игрок A",
                                playerNames = playerNames
                            )
                            OutlinedTextField(
                                value = ratingA,
                                onValueChange = { ratingA = it },
                                label = { Text("Рейтинг Elo игрока A") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth()
                            )
                            PlayerNameField(
                                value = playerB,
                                onValueChange = { playerB = it },
                                label = "Игрок B",
                                playerNames = playerNames
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
                                        scope.launch {
                                            val historySnapshot = withContext(Dispatchers.IO) {
                                                database.loadMatches()
                                            }
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
                            Text(
                                predictionSource,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(prediction.playerA, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        TennisPredictor.formatPercent(prediction.probabilityA),
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(prediction.playerB, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        TennisPredictor.formatPercent(prediction.probabilityB),
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            LinearProgressIndicator(
                                progress = prediction.probabilityA.toFloat(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("Разница рейтингов: ${prediction.ratingDifference.toInt()} Elo")
                        }
                    }
                }
            }

            1 -> Column(
                Modifier.fillMaxSize()
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ScreenHeading(
                    title = "История матчей",
                    subtitle = "Импортируй результаты один раз — рейтинги и прогнозы обновятся автоматически."
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SummaryTile(
                        label = "Матчей на устройстве",
                        value = matchCount.toString(),
                        modifier = Modifier.weight(1f)
                    )
                    SummaryTile(
                        label = "Игроков в рейтинге",
                        value = ratingSnapshot.overall.size.toString(),
                        modifier = Modifier.weight(1f)
                    )
                }
                Card {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "Импорт результатов",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Выбери сразу один или несколько CSV-файлов. История сохраняется локально и не отправляется на сервер.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(
                            onClick = { csvPicker.launch(arrayOf("*/*")) },
                            enabled = !operationInProgress,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                when {
                                    isRestoring -> "Восстанавливаем историю… $importProgress%"
                                    isImporting -> "Импортируем… $importProgress%"
                                    operationInProgress -> "Выполняется другая задача…"
                                    else -> "Выбрать CSV-файлы"
                                }
                            )
                        }
                        if (showHistoryProgress) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("$importProgress%", fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        importProgressStatus,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = importProgress / 100f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
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
                        Text(
                            "История автоматически сохраняется в Downloads/TennisPredict и восстановится после переустановки приложения.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "Данные остаются на устройстве",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            "История матчей хранится локально и не отправляется на сервер. Повторно импортированные матчи пропускаются.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            2 -> Column(
                Modifier.fillMaxSize()
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ScreenHeading(
                    title = "Рейтинги игроков",
                    subtitle = "Сравни общий Elo и рейтинги игроков на разных покрытиях."
                )
                RankingCard(
                    title = "Общий рейтинг Elo",
                    subtitle = "Все покрытия · ${matchCount} матчей",
                    players = ratingSnapshot.overall
                )
                RankingCard(
                    title = "Рейтинг на харде",
                    subtitle = "${hardMatchCount} матчей",
                    players = ratingSnapshot.hard
                )
                RankingCard(
                    title = "Рейтинг на грунте",
                    subtitle = "${clayMatchCount} матчей",
                    players = ratingSnapshot.clay
                )
                RankingCard(
                    title = "Рейтинг на траве",
                    subtitle = "${grassMatchCount} матчей",
                    players = ratingSnapshot.grass
                )
            }

            else -> Column(
                Modifier.fillMaxSize()
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ScreenHeading(
                    title = "Обучение модели",
                    subtitle = "Сравни алгоритмы на прошлых датах и используй модель только при подтверждённом улучшении."
                )
                ModelTrainingCard(
                    database = database,
                    matchCount = matchCount,
                    onModelTrained = { trainedModel = it }
                )
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Как читать прогноз",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Elo — базовая модель, а не гарантия результата и не рекомендация по ставкам. " +
                                "Исторические рейтинги пересчитываются по датам матчей, если даты присутствуют во всём наборе. " +
                                "Для игрока без истории используется стартовый Elo 1500.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun ScreenHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SummaryTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}


@Composable
private fun PlayerNameField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    playerNames: List<String>
) {
    var expanded by remember { mutableStateOf(false) }
    val query = value.trim()
    val suggestions = remember(query, playerNames) {
        findPlayerSuggestions(playerNames, query, limit = 8)
    }

    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = it.trim().length >= 2
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
            suggestions.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onValueChange(name)
                        expanded = false
                    }
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
