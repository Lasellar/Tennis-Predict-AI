package ru.tennispredictor.app

data class PlayerRating(
    val name: String,
    val rating: Double,
    val matches: Int,
    val wins: Int,
    val losses: Int
)

data class RatingSnapshot(
    val overall: List<PlayerRating>,
    val hard: List<PlayerRating>,
    val clay: List<PlayerRating>,
    val grass: List<PlayerRating>
) {
    fun playersFor(surface: Surface?): List<PlayerRating> = when (surface) {
        null -> overall
        Surface.HARD -> hard
        Surface.CLAY -> clay
        Surface.GRASS -> grass
        else -> overall
    }
}

/**
 * Builds all four rating tables in one chronological pass.
 *
 * Accumulator is also used by MatchDatabase.loadSummary(), which feeds it directly
 * from a SQLite cursor. That avoids creating 96k+ StoredMatch objects just to render
 * the home screen and avoids re-sorting/re-scanning the same history four times.
 */
object RatingCalculator {
    fun calculate(matches: List<StoredMatch>): RatingSnapshot {
        val ordered = if (matches.all { it.record.date.isNotBlank() }) {
            matches.sortedWith(compareBy<StoredMatch> { it.record.date }.thenBy { it.id })
        } else {
            matches.sortedBy { it.id }
        }
        val accumulator = Accumulator()
        ordered.forEach { accumulator.add(it.record) }
        return accumulator.snapshot()
    }

    class Accumulator {
        private val overallRatings = linkedMapOf<String, Double>()
        private val hardRatings = linkedMapOf<String, Double>()
        private val clayRatings = linkedMapOf<String, Double>()
        private val grassRatings = linkedMapOf<String, Double>()

        private val overallStats = linkedMapOf<String, MutableStats>()
        private val hardStats = linkedMapOf<String, MutableStats>()
        private val clayStats = linkedMapOf<String, MutableStats>()
        private val grassStats = linkedMapOf<String, MutableStats>()

        fun add(record: MatchRecord) = add(
            record.playerA,
            record.playerB,
            record.winner,
            record.surface
        )

        fun add(rawPlayerA: String, rawPlayerB: String, rawWinner: String, surface: Surface) {
            val playerA = rawPlayerA.trim()
            val playerB = rawPlayerB.trim()
            val winner = rawWinner.trim()
            if (playerA.isBlank() || playerB.isBlank() ||
                playerA.equals(playerB, ignoreCase = true) ||
                (winner != playerA && winner != playerB)
            ) return

            updateElo(overallRatings, playerA, playerB, winner)
            updateStats(overallStats, playerA, playerB, winner)

            when (surface) {
                Surface.HARD -> {
                    updateElo(hardRatings, playerA, playerB, winner)
                    updateStats(hardStats, playerA, playerB, winner)
                }
                Surface.CLAY -> {
                    updateElo(clayRatings, playerA, playerB, winner)
                    updateStats(clayStats, playerA, playerB, winner)
                }
                Surface.GRASS -> {
                    updateElo(grassRatings, playerA, playerB, winner)
                    updateStats(grassStats, playerA, playerB, winner)
                }
                else -> Unit
            }
        }

        fun snapshot(): RatingSnapshot = RatingSnapshot(
            overall = buildPlayers(overallStats, overallRatings),
            hard = buildPlayers(hardStats, hardRatings),
            clay = buildPlayers(clayStats, clayRatings),
            grass = buildPlayers(grassStats, grassRatings)
        )

        private fun updateElo(
            ratings: MutableMap<String, Double>,
            playerA: String,
            playerB: String,
            winner: String
        ) {
            val ratingA = ratings.getOrPut(playerA) { INITIAL_RATING }
            val ratingB = ratings.getOrPut(playerB) { INITIAL_RATING }
            val expectedA = TennisPredictor.probability(ratingA, ratingB)
            val actualA = if (winner == playerA) 1.0 else 0.0
            val change = K_FACTOR * (actualA - expectedA)
            ratings[playerA] = ratingA + change
            ratings[playerB] = ratingB - change
        }

        private fun updateStats(
            stats: MutableMap<String, MutableStats>,
            playerA: String,
            playerB: String,
            winner: String
        ) {
            val a = stats.getOrPut(playerA) { MutableStats() }
            val b = stats.getOrPut(playerB) { MutableStats() }
            a.matches++
            b.matches++
            if (winner == playerA) {
                a.wins++
                b.losses++
            } else {
                b.wins++
                a.losses++
            }
        }
    }

    private fun buildPlayers(
        stats: Map<String, MutableStats>,
        ratings: Map<String, Double>
    ): List<PlayerRating> = stats.map { (name, value) ->
        PlayerRating(
            name = name,
            rating = ratings[name] ?: INITIAL_RATING,
            matches = value.matches,
            wins = value.wins,
            losses = value.losses
        )
    }.sortedWith(compareByDescending<PlayerRating> { it.rating }.thenBy { it.name })

    private data class MutableStats(
        var matches: Int = 0,
        var wins: Int = 0,
        var losses: Int = 0
    )

    private const val INITIAL_RATING = 1500.0
    private const val K_FACTOR = 32.0
}
