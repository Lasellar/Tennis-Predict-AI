package ru.tennispredictor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TennisPredictorTest {
    @Test fun equalRatingsGiveFiftyFifty() { assertEquals(0.5,TennisPredictor.probability(1500.0,1500.0),0.000001) }
    @Test fun higherRatingHasHigherProbability() {
        assertTrue(TennisPredictor.probability(1700.0,1500.0)>0.5)
        assertTrue(TennisPredictor.probability(1500.0,1700.0)<0.5)
    }
    @Test fun probabilitiesSumToOne() {
        val p=TennisPredictor.predict("A","B",1650.0,1520.0)
        assertEquals(1.0,p.probabilityA+p.probabilityB,0.000001)
    }
    @Test fun eloTrainingRewardsWinner() {
        val matches=listOf(MatchRecord("A","B","A"),MatchRecord("A","B","A"),MatchRecord("B","A","B"))
        val ratings=TennisPredictor.trainElo(matches)
        assertTrue(ratings.getValue("A")>ratings.getValue("B"))
    }
    @Test fun emptyEvaluationIsSafe() { assertEquals(0,TennisPredictor.evaluate(emptyList(),emptyMap()).matches) }
}
