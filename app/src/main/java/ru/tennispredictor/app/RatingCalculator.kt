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
 * Rebuilds ratings from local match history. If every row has a date, matches are
 * processed chronologically (and kept in import order for same-day ties). If dates
 * are absent or mixed, insertion order is retained to avoid inventing chronology.
 */
object RatingCalculator {
    fun calculate(matches: List<StoredMatch>): RatingSnapshot =
        RatingSnapshot(
            overall = buildRankings(matches),
            hard = buildRankings(matches.filter { it.record.surface == Surface.HARD }),
            clay = buildRankings(matches.filter { it.record.surface == Surface.CLAY }),
            grass = buildRankings(matches.filter { it.record.surface == Surface.GRASS })
        )

    private fun buildRankings(input: List<StoredMatch>): List<PlayerRating> {
        if (input.isEmpty()) return emptyList()
        val ordered = if (input.all { it.record.date.isNotBlank() }) {
            input.sortedWith(compareBy<StoredMatch> { it.record.date }.thenBy { it.id })
        } else {
            input.sortedBy { it.id }
        }
        val valid = ordered.map { it.record }.filter { match ->
            match.playerA.isNotBlank() && match.playerB.isNotBlank() &&
                !match.playerA.equals(match.playerB, ignoreCase = true) &&
                (match.winner == match.playerA || match.winner == match.playerB)
        }
        if (valid.isEmpty()) return emptyList()

        val ratings = TennisPredictor.trainElo(valid)
        val stats = linkedMapOf<String, MutableStats>()
        valid.forEach { match ->
            val playerA = match.playerA.trim()
            val playerB = match.playerB.trim()
            val winner = match.winner.trim()
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

        return stats.map { (name, value) ->
            PlayerRating(
                name = name,
                rating = ratings[name] ?: 1500.0,
                matches = value.matches,
                wins = value.wins,
                losses = value.losses
            )
        }.sortedWith(compareByDescending<PlayerRating> { it.rating }.thenBy { it.name })
    }

    private data class MutableStats(
        var matches: Int = 0,
        var wins: Int = 0,
        var losses: Int = 0
    )
}
