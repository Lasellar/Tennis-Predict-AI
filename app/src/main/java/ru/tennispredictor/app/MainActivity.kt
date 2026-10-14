package ru.tennispredictor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme=lightColorScheme()) { Surface(Modifier.fillMaxSize()) { PredictorScreen() } } }
    }
}

@Composable
private fun PredictorScreen() {
    var playerA by remember { mutableStateOf("Player A") }
    var playerB by remember { mutableStateOf("Player B") }
    var ratingA by remember { mutableStateOf("1600") }
    var ratingB by remember { mutableStateOf("1500") }
    var prediction by remember { mutableStateOf(TennisPredictor.predict(playerA,playerB,1600.0,1500.0)) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("TENNIS PREDICTOR AI",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        Text("Вероятностная оценка матча на основе рейтинга Elo")
        Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Участники матча",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                OutlinedTextField(value=playerA,onValueChange={playerA=it},label={Text("Игрок A")},singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(value=ratingA,onValueChange={ratingA=it},label={Text("Рейтинг Elo игрока A")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
                OutlinedTextField(value=playerB,onValueChange={playerB=it},label={Text("Игрок B")},singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(value=ratingB,onValueChange={ratingB=it},label={Text("Рейтинг Elo игрока B")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
                Button(onClick={
                    val a=ratingA.toDoubleOrNull(); val b=ratingB.toDoubleOrNull()
                    error=when {
                        playerA.isBlank() || playerB.isBlank() -> "Укажи имена обоих игроков."
                        playerA.trim().equals(playerB.trim(),true) -> "Игроки должны различаться."
                        a==null || b==null || a !in 500.0..3000.0 || b !in 500.0..3000.0 -> "Рейтинг Elo должен быть числом от 500 до 3000."
                        else -> null
                    }
                    if(error==null) prediction=TennisPredictor.predict(playerA.trim(),playerB.trim(),a!!,b!!)
                },modifier=Modifier.fillMaxWidth()) { Text("Рассчитать прогноз") }
                error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            }
        }
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Вероятности победы",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                Text(prediction.playerA)
                Text(TennisPredictor.formatPercent(prediction.probabilityA),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
                Text(prediction.playerB)
                Text(TennisPredictor.formatPercent(prediction.probabilityB),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
                Text("Разница рейтингов: ${prediction.ratingDifference.toInt()} Elo")
            }
        }
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("О модели",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("Это базовая модель Elo, а не гарантия результата и не рекомендация по ставкам. Качество прогноза зависит от полноты и актуальности исторических данных.")
            }
        }
        Text("Следующие этапы: импорт CSV, рейтинги по покрытиям и проверка качества на отложенной выборке.",style=MaterialTheme.typography.bodySmall)
    }
}
