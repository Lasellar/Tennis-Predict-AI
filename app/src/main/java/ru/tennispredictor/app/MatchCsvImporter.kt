package ru.tennispredictor.app

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

data class CsvParseResult(
    val records: List<MatchRecord>,
    val dataRows: Int,
    val invalidRows: Int,
    val issues: List<String>
)

/**
 * Supports both a compact CSV format:
 * date,player_a,player_b,winner,surface
 * and common ATP/WTA archive columns such as:
 * tourney_date,winner_name,loser_name,surface.
 */
object MatchCsvImporter {
    fun parse(csv: String): CsvParseResult {
        val lines = csv.removePrefix("\uFEFF").lineSequence().toList()
        val headerLineIndex = lines.indexOfFirst { it.isNotBlank() }
        require(headerLineIndex >= 0) { "CSV-файл пуст." }

        val delimiter = detectDelimiter(lines[headerLineIndex])
        val headers = parseLine(lines[headerLineIndex], delimiter).map(::normalizeHeader)
        val columns = ColumnIndexes(
            playerA = headers.indexOfAny(
                "player_a", "playera", "player_1", "player1", "player_a_name",
                "player1_name", "name_a", "player_one"
            ),
            playerB = headers.indexOfAny(
                "player_b", "playerb", "player_2", "player2", "player_b_name",
                "player2_name", "name_b", "player_two"
            ),
            winner = headers.indexOfAny("winner", "match_winner", "winner_name"),
            loser = headers.indexOfAny("loser", "loser_name", "match_loser"),
            date = headers.indexOfAny("date", "match_date", "played_on", "tourney_date", "tournament_date"),
            surface = headers.indexOfAny("surface", "court_surface")
        )

        val hasExplicitPlayers = columns.playerA >= 0 && columns.playerB >= 0
        val hasWinnerLoserPair = columns.winner >= 0 && columns.loser >= 0
        require((hasExplicitPlayers && columns.winner >= 0) || (!hasExplicitPlayers && hasWinnerLoserPair)) {
            "Неизвестный формат CSV. Нужны player_a, player_b, winner или архивные winner_name и loser_name."
        }

        val records = mutableListOf<MatchRecord>()
        val issues = mutableListOf<String>()
        var dataRows = 0
        var invalidRows = 0

        for (lineIndex in headerLineIndex + 1 until lines.size) {
            val line = lines[lineIndex]
            if (line.isBlank()) continue
            dataRows++
            try {
                val fields = parseLine(line, delimiter)
                val playerA: String
                val playerB: String
                val winner: String

                if (hasExplicitPlayers) {
                    playerA = fields.valueAt(columns.playerA).cleanName()
                    playerB = fields.valueAt(columns.playerB).cleanName()
                    val winnerCell = fields.valueAt(columns.winner).trim()
                    winner = resolveWinner(winnerCell, playerA, playerB)
                        ?: throw IllegalArgumentException("победитель не совпадает с player_a или player_b")
                } else {
                    // In standard tour archives the winner/loser columns identify the result.
                    playerA = fields.valueAt(columns.winner).cleanName()
                    playerB = fields.valueAt(columns.loser).cleanName()
                    winner = playerA
                }

                if (playerA.isBlank() || playerB.isBlank() || playerA.equals(playerB, ignoreCase = true)) {
                    throw IllegalArgumentException("пустые имена или один и тот же игрок")
                }

                val date = if (columns.date >= 0) normalizeDate(fields.valueAt(columns.date)) else ""
                val surface = if (columns.surface >= 0) parseSurface(fields.valueAt(columns.surface)) else Surface.OTHER
                records += MatchRecord(
                    playerA = playerA,
                    playerB = playerB,
                    winner = winner,
                    surface = surface,
                    date = date
                )
            } catch (exception: Exception) {
                invalidRows++
                if (issues.size < 5) {
                    issues += "Строка ${lineIndex + 1}: ${exception.message ?: "не удалось прочитать матч"}"
                }
            }
        }

        return CsvParseResult(
            records = records,
            dataRows = dataRows,
            invalidRows = invalidRows,
            issues = issues
        )
    }

    private data class ColumnIndexes(
        val playerA: Int,
        val playerB: Int,
        val winner: Int,
        val loser: Int,
        val date: Int,
        val surface: Int
    )

    private fun List<String>.indexOfAny(vararg names: String): Int =
        names.firstOrNull { name -> indexOf(name) >= 0 }?.let { indexOf(it) } ?: -1

    private fun List<String>.valueAt(index: Int): String =
        if (index in indices) this[index] else ""

    private fun String.cleanName(): String = trim().replace(Regex("\\s+"), " ")

    private fun normalizeHeader(value: String): String =
        value.trim().removePrefix("\uFEFF").lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')

    private fun resolveWinner(value: String, playerA: String, playerB: String): String? {
        if (value.equals(playerA, ignoreCase = true)) return playerA
        if (value.equals(playerB, ignoreCase = true)) return playerB
        return when (value.trim().lowercase(Locale.ROOT)) {
            "a", "player_a", "playera", "player 1", "player1", "1", "first" -> playerA
            "b", "player_b", "playerb", "player 2", "player2", "2", "second" -> playerB
            else -> null
        }
    }

    private fun parseSurface(rawValue: String): Surface {
        val value = rawValue.trim().lowercase(Locale.ROOT)
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
        return when (value) {
            "hard", "hard court", "outdoor hard", "indoor hard", "indoor hard court", "h" -> Surface.HARD
            "clay", "clay court", "red clay", "c" -> Surface.CLAY
            "grass", "grass court", "g" -> Surface.GRASS
            "carpet", "indoor carpet", "indoor", "i" -> Surface.INDOOR
            else -> Surface.OTHER
        }
    }

    private fun normalizeDate(rawValue: String): String {
        val value = rawValue.trim()
        if (value.isEmpty()) return ""
        val candidate = when {
            Regex("^\\d{8}$").matches(value) ->
                runCatching { LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE).toString() }.getOrNull()
            Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(value) -> value.take(10)
            Regex("^\\d{4}/\\d{2}/\\d{2}$").matches(value) ->
                runCatching { LocalDate.parse(value, DateTimeFormatter.ofPattern("yyyy/MM/dd")).toString() }.getOrNull()
            Regex("^\\d{2}/\\d{2}/\\d{4}$").matches(value) ->
                runCatching { LocalDate.parse(value, DateTimeFormatter.ofPattern("dd/MM/yyyy")).toString() }.getOrNull()
            else -> null
        }
        if (candidate != null) return candidate
        // Preserve unfamiliar date strings rather than losing source information.
        return try {
            LocalDate.parse(value).toString()
        } catch (_: DateTimeParseException) {
            value
        }
    }

    private fun detectDelimiter(headerLine: String): Char {
        val commaCount = countDelimiter(headerLine, ',')
        val semicolonCount = countDelimiter(headerLine, ';')
        val tabCount = countDelimiter(headerLine, '\t')
        return when {
            tabCount > commaCount && tabCount > semicolonCount -> '\t'
            semicolonCount > commaCount -> ';'
            else -> ','
        }
    }

    private fun countDelimiter(line: String, delimiter: Char): Int {
        var inQuotes = false
        var count = 0
        var index = 0
        while (index < line.length) {
            val char = line[index]
            if (char == '"') {
                if (inQuotes && index + 1 < line.length && line[index + 1] == '"') {
                    index++
                } else {
                    inQuotes = !inQuotes
                }
            } else if (char == delimiter && !inQuotes) {
                count++
            }
            index++
        }
        return count
    }

    private fun parseLine(line: String, delimiter: Char): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> inQuotes = !inQuotes
                char == delimiter && !inQuotes -> {
                    fields += current.toString()
                    current.setLength(0)
                }
                else -> current.append(char)
            }
            index++
        }
        fields += current.toString()
        return fields
    }
}
