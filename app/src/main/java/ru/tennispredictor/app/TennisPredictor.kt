package ru.tennispredictor.app

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.round

enum class Surface { HARD, CLAY, GRASS, INDOOR }
data class MatchRecord(val playerA:String,val playerB:String,val winner:String,val surface:Surface=Surface.HARD,val eloA:Double=1500.0,val eloB:Double=1500.0)
data class Prediction(val playerA:String,val playerB:String,val probabilityA:Double,val probabilityB:Double,val ratingDifference:Double)
data class EvaluationMetrics(val accuracy:Double,val logLoss:Double,val brierScore:Double,val matches:Int)

object TennisPredictor {
    private const val SCALE=400.0
    private const val DEFAULT_ELO=1500.0
    private const val K_FACTOR=32.0
    fun probability(ratingA:Double,ratingB:Double):Double = 1.0/(1.0+exp((ratingB-ratingA)/SCALE*ln(10.0)))
    fun predict(playerA:String,playerB:String,eloA:Double,eloB:Double):Prediction {
        val p=probability(eloA,eloB).coerceIn(0.001,0.999)
        return Prediction(playerA,playerB,p,1.0-p,eloA-eloB)
    }
    fun trainElo(matches:List<MatchRecord>,initialRating:Double=DEFAULT_ELO):Map<String,Double> {
        val ratings=mutableMapOf<String,Double>()
        for (m in matches) {
            val a=m.playerA.trim(); val b=m.playerB.trim(); val winner=m.winner.trim()
            if (a.isEmpty() || b.isEmpty() || a==b || (winner!=a && winner!=b)) continue
            val ra=ratings.getOrPut(a){initialRating}; val rb=ratings.getOrPut(b){initialRating}
            val expectedA=probability(ra,rb); val actualA=if(winner==a) 1.0 else 0.0
            ratings[a]=ra+K_FACTOR*(actualA-expectedA)
            ratings[b]=rb-K_FACTOR*(actualA-expectedA)
        }
        return ratings.toMap()
    }
    fun evaluate(records:List<MatchRecord>,ratings:Map<String,Double>):EvaluationMetrics {
        val valid=records.filter { it.playerA.isNotBlank() && it.playerB.isNotBlank() && (it.winner==it.playerA || it.winner==it.playerB) }
        if(valid.isEmpty()) return EvaluationMetrics(0.0,0.0,0.0,0)
        var correct=0; var loss=0.0; var brier=0.0
        valid.forEach { m ->
            val p=probability(ratings[m.playerA] ?: m.eloA,ratings[m.playerB] ?: m.eloB).coerceIn(0.001,0.999)
            val actual=if(m.winner==m.playerA) 1.0 else 0.0
            if((p>=0.5)==(actual==1.0)) correct++
            loss-=actual*ln(p)+(1.0-actual)*ln(1.0-p)
            brier+=(p-actual)*(p-actual)
        }
        return EvaluationMetrics(correct.toDouble()/valid.size,loss/valid.size,brier/valid.size,valid.size)
    }
    fun formatPercent(value:Double):String = "${round(value*1000.0)/10.0}%"
}
