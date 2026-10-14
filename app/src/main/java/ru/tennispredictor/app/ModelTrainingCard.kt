package ru.tennispredictor.app

import androidx.compose.material3.Button
import androidx.compose.material3.Text
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
import androidx.compose.foundation.layout.Column
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
        Text(message)
        report?.let { r ->
            Text("Обучение: ${r.trainStartDate} — ${r.trainEndDate}; проверка: ${r.testStartDate} — ${r.testEndDate}")
            Text("Log Loss: Elo ${r.eloMetrics.logLoss}; регрессия ${r.logisticMetrics.logLoss}")
            Text("Brier Score: Elo ${r.eloMetrics.brierScore}; регрессия ${r.logisticMetrics.brierScore}")
            Text("Точность: Elo ${r.eloMetrics.accuracy}; регрессия ${r.logisticMetrics.accuracy}")
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
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Обучаем…" else "Обучить и сравнить с Elo") }
    } }
}
