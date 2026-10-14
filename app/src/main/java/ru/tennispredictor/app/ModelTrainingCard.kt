package ru.tennispredictor.app

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import java.util.Locale
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
@Composable
fun ModelTrainingCard(matches: List<StoredMatch>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Этап 2 — обучение модели") }
    var report by remember { mutableStateOf<ModelComparisonReport?>(null) }
    Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(message, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (matches.size < ModelTrainer.MINIMUM_DATED_MATCHES) {
            Text("Матчей импортировано: ${matches.size}. Нужно минимум ${ModelTrainer.MINIMUM_DATED_MATCHES} корректных матчей с датами и минимум два разных игровых дня.")
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        report?.let { r ->
            HorizontalDivider()
            Text("Метрики на отложенных матчах", fontWeight = FontWeight.SemiBold)
            Text("Обучение: ${r.trainStartDate} — ${r.trainEndDate} · ${r.trainMatches} матчей")
            Text("Проверка на более поздних матчах: ${r.testStartDate} — ${r.testEndDate} · ${r.testMatches} матчей")
            Row(Modifier.fillMaxWidth()) {
                Text("Метрика", Modifier.weight(1.2f), fontWeight = FontWeight.SemiBold)
                Text("Elo", Modifier.weight(0.8f), textAlign = TextAlign.End, fontWeight = FontWeight.SemiBold)
                Text("Регрессия", Modifier.weight(1.2f), textAlign = TextAlign.End, fontWeight = FontWeight.SemiBold)
            }
            MetricLine("Log Loss", r.eloMetrics.logLoss, r.logisticMetrics.logLoss)
            MetricLine("Brier Score", r.eloMetrics.brierScore, r.logisticMetrics.brierScore)
            MetricLine("Точность", r.eloMetrics.accuracy, r.logisticMetrics.accuracy)
            Text("Использовано ${r.eligibleMatches} датированных матчей. Без корректной даты исключено: ${r.excludedUndatedMatches}; некорректных записей: ${r.excludedInvalidMatches}.")
            Text("Итоговая модель обучена на всей доступной датированной истории и сохранена на устройстве для следующего этапа.", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = {
            val snapshot = matches.toList()
            busy = true
            scope.launch {
                try {
                    val result = withContext(Dispatchers.Default) { ModelTrainer.trainAndEvaluate(snapshot) }
                    withContext(Dispatchers.IO) { ModelStore(context).save(result.model) }
                    message = "Обучение завершено: ${result.testMatches} тестовых матчей"
                    report = result
                } catch (e: Exception) { message = e.message ?: "Ошибка обучения" }
                finally { busy = false }
            }
        }, enabled = !busy && matches.size >= ModelTrainer.MINIMUM_DATED_MATCHES, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Обучаем…" else "Обучить модель и сравнить с Elo") }
    } }
}

@Composable
private fun MetricLine(title: String, elo: Double, logistic: Double) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(title, Modifier.weight(1.2f))
        Text(String.format(Locale.ROOT, "%.4f", elo), Modifier.weight(0.8f), textAlign = TextAlign.End)
        Text(String.format(Locale.ROOT, "%.4f", logistic), Modifier.weight(1.2f), textAlign = TextAlign.End)
    }
}
