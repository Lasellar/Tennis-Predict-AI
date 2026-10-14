package ru.tennispredictor.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.ln

data class StoredMatch(
    val id: Long,
    val record: MatchRecord
)

data class DatabaseImportSummary(
    val inserted: Int,
    val duplicates: Int
)

data class PredictionToStore(
    val targetMatchDate: String,
    val playerA: String,
    val playerB: String,
    val surface: Surface,
    val probabilityA: Double,
    val lowerProbabilityA: Double,
    val upperProbabilityA: Double,
    val overallMatchesA: Int,
    val overallMatchesB: Int,
    val surfaceMatchesA: Int,
    val surfaceMatchesB: Int,
    val recentFormA: String,
    val recentFormB: String,
    val source: String
)

data class StoredPrediction(
    val id: Long,
    val createdAtMillis: Long,
    val targetMatchDate: String,
    val playerA: String,
    val playerB: String,
    val surface: Surface,
    val probabilityA: Double,
    val lowerProbabilityA: Double,
    val upperProbabilityA: Double,
    val overallMatchesA: Int,
    val overallMatchesB: Int,
    val surfaceMatchesA: Int,
    val surfaceMatchesB: Int,
    val recentFormA: String,
    val recentFormB: String,
    val source: String,
    val settledWinner: String?,
    val settledAtMillis: Long?,
    val logLoss: Double?,
    val brierScore: Double?
) {
    val predictedWinner: String
        get() = if (probabilityA >= 0.5) playerA else playerB

    val wasCorrect: Boolean?
        get() = settledWinner?.let { normalize(it) == normalize(predictedWinner) }

    private fun normalize(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
}

/**
 * On-device SQLite storage for imported results and saved forecasts.
 * Neither player history nor forecast data is sent to a remote service.
 */
class MatchDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_MATCHES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                match_date TEXT NOT NULL DEFAULT '',
                player_a TEXT NOT NULL,
                player_b TEXT NOT NULL,
                winner TEXT NOT NULL,
                surface TEXT NOT NULL,
                match_key TEXT NOT NULL UNIQUE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_matches_date ON $TABLE_MATCHES(match_date)")
        db.execSQL("CREATE INDEX idx_matches_surface ON $TABLE_MATCHES(surface)")
        createPredictionsTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 2 adds forecast persistence without deleting imported match history.
        if (oldVersion < 2) createPredictionsTable(db)
    }

    fun loadMatches(): List<StoredMatch> {
        val result = mutableListOf<StoredMatch>()
        readableDatabase.query(
            TABLE_MATCHES,
            arrayOf("id", "match_date", "player_a", "player_b", "winner", "surface"),
            null,
            null,
            null,
            null,
            "id ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val surfaceName = cursor.getString(cursor.getColumnIndexOrThrow("surface"))
                val surface = runCatching { Surface.valueOf(surfaceName) }.getOrDefault(Surface.OTHER)
                val record = MatchRecord(
                    playerA = cursor.getString(cursor.getColumnIndexOrThrow("player_a")),
                    playerB = cursor.getString(cursor.getColumnIndexOrThrow("player_b")),
                    winner = cursor.getString(cursor.getColumnIndexOrThrow("winner")),
                    surface = surface,
                    date = cursor.getString(cursor.getColumnIndexOrThrow("match_date"))
                )
                result += StoredMatch(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    record = record
                )
            }
        }
        return result
    }

    /**
     * Adds a batch in one transaction. A stable signature prevents the same match
     * from being added repeatedly when a user imports the same CSV again.
     */
    fun insertMatches(matches: List<MatchRecord>): DatabaseImportSummary {
        var inserted = 0
        var duplicates = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            matches.forEach { match ->
                val values = ContentValues().apply {
                    put("match_date", match.date)
                    put("player_a", match.playerA.trim())
                    put("player_b", match.playerB.trim())
                    put("winner", match.winner.trim())
                    put("surface", match.surface.name)
                    put("match_key", matchSignature(match))
                }
                val rowId = db.insertWithOnConflict(
                    TABLE_MATCHES,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE
                )
                if (rowId == -1L) duplicates++ else inserted++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return DatabaseImportSummary(inserted = inserted, duplicates = duplicates)
    }

    fun savePrediction(prediction: PredictionToStore): Long {
        val values = ContentValues().apply {
            put("created_at_ms", System.currentTimeMillis())
            put("target_match_date", prediction.targetMatchDate.trim())
            put("player_a", prediction.playerA.trim())
            put("player_b", prediction.playerB.trim())
            put("surface", prediction.surface.name)
            put("probability_a", prediction.probabilityA.coerceIn(0.001, 0.999))
            put("lower_probability_a", prediction.lowerProbabilityA.coerceIn(0.001, 0.999))
            put("upper_probability_a", prediction.upperProbabilityA.coerceIn(0.001, 0.999))
            put("overall_matches_a", prediction.overallMatchesA)
            put("overall_matches_b", prediction.overallMatchesB)
            put("surface_matches_a", prediction.surfaceMatchesA)
            put("surface_matches_b", prediction.surfaceMatchesB)
            put("recent_form_a", prediction.recentFormA)
            put("recent_form_b", prediction.recentFormB)
            put("source", prediction.source)
        }
        val id = writableDatabase.insert(TABLE_PREDICTIONS, null, values)
        check(id != -1L) { "Не удалось сохранить прогноз в локальной базе." }
        return id
    }

    fun loadPredictions(limit: Int = 50): List<StoredPrediction> {
        val result = mutableListOf<StoredPrediction>()
        readableDatabase.query(
            TABLE_PREDICTIONS,
            arrayOf(
                "id", "created_at_ms", "target_match_date", "player_a", "player_b", "surface",
                "probability_a", "lower_probability_a", "upper_probability_a",
                "overall_matches_a", "overall_matches_b", "surface_matches_a", "surface_matches_b",
                "recent_form_a", "recent_form_b", "source", "settled_winner", "settled_at_ms",
                "log_loss", "brier_score"
            ),
            null,
            null,
            null,
            null,
            "id DESC",
            limit.coerceIn(1, 1000).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val surfaceName = cursor.getString(cursor.getColumnIndexOrThrow("surface"))
                val surface = runCatching { Surface.valueOf(surfaceName) }.getOrDefault(Surface.OTHER)
                val settledWinnerIndex = cursor.getColumnIndexOrThrow("settled_winner")
                val settledAtIndex = cursor.getColumnIndexOrThrow("settled_at_ms")
                val logLossIndex = cursor.getColumnIndexOrThrow("log_loss")
                val brierIndex = cursor.getColumnIndexOrThrow("brier_score")
                result += StoredPrediction(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    createdAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow("created_at_ms")),
                    targetMatchDate = cursor.getString(cursor.getColumnIndexOrThrow("target_match_date")),
                    playerA = cursor.getString(cursor.getColumnIndexOrThrow("player_a")),
                    playerB = cursor.getString(cursor.getColumnIndexOrThrow("player_b")),
                    surface = surface,
                    probabilityA = cursor.getDouble(cursor.getColumnIndexOrThrow("probability_a")),
                    lowerProbabilityA = cursor.getDouble(cursor.getColumnIndexOrThrow("lower_probability_a")),
                    upperProbabilityA = cursor.getDouble(cursor.getColumnIndexOrThrow("upper_probability_a")),
                    overallMatchesA = cursor.getInt(cursor.getColumnIndexOrThrow("overall_matches_a")),
                    overallMatchesB = cursor.getInt(cursor.getColumnIndexOrThrow("overall_matches_b")),
                    surfaceMatchesA = cursor.getInt(cursor.getColumnIndexOrThrow("surface_matches_a")),
                    surfaceMatchesB = cursor.getInt(cursor.getColumnIndexOrThrow("surface_matches_b")),
                    recentFormA = cursor.getString(cursor.getColumnIndexOrThrow("recent_form_a")),
                    recentFormB = cursor.getString(cursor.getColumnIndexOrThrow("recent_form_b")),
                    source = cursor.getString(cursor.getColumnIndexOrThrow("source")),
                    settledWinner = if (cursor.isNull(settledWinnerIndex)) null else cursor.getString(settledWinnerIndex),
                    settledAtMillis = if (cursor.isNull(settledAtIndex)) null else cursor.getLong(settledAtIndex),
                    logLoss = if (cursor.isNull(logLossIndex)) null else cursor.getDouble(logLossIndex),
                    brierScore = if (cursor.isNull(brierIndex)) null else cursor.getDouble(brierIndex)
                )
            }
        }
        return result
    }

    /** Records an actual winner and stores the per-forecast Log Loss and Brier Score. */
    fun settlePrediction(predictionId: Long, winner: String): Boolean {
        val prediction = loadPredictions(1000).firstOrNull { it.id == predictionId } ?: return false
        if (prediction.settledWinner != null) return false
        val winnerKey = normalize(winner)
        val aKey = normalize(prediction.playerA)
        val bKey = normalize(prediction.playerB)
        if (winnerKey != aKey && winnerKey != bKey) return false

        val actualA = winnerKey == aKey
        val pA = prediction.probabilityA.coerceIn(0.001, 0.999)
        val probabilityOfActual = if (actualA) pA else 1.0 - pA
        val logLoss = -ln(probabilityOfActual.coerceIn(0.001, 0.999))
        val brier = (pA - if (actualA) 1.0 else 0.0) * (pA - if (actualA) 1.0 else 0.0)
        val displayWinner = if (actualA) prediction.playerA else prediction.playerB
        val values = ContentValues().apply {
            put("settled_winner", displayWinner)
            put("settled_at_ms", System.currentTimeMillis())
            put("log_loss", logLoss)
            put("brier_score", brier)
        }
        return writableDatabase.update(
            TABLE_PREDICTIONS,
            values,
            "id = ? AND settled_winner IS NULL",
            arrayOf(predictionId.toString())
        ) == 1
    }

    /**
     * Automatically verifies saved forecasts when an imported result has the exact
     * expected date, player pair and surface. Forecasts without a target date are left
     * for manual settlement, avoiding guesses about which meeting a row represents.
     */
    fun settlePredictionsFromResults(matches: List<StoredMatch>): Int {
        var verified = 0
        val pending = loadPredictions(1000).filter {
            it.settledWinner == null && it.targetMatchDate.isNotBlank()
        }
        pending.forEach { prediction ->
            val expectedDate = normalizeDate(prediction.targetMatchDate)
            val a = normalize(prediction.playerA)
            val b = normalize(prediction.playerB)
            val result = matches.firstOrNull { stored ->
                val match = stored.record
                normalizeDate(match.date) == expectedDate &&
                    match.surface == prediction.surface &&
                    setOf(normalize(match.playerA), normalize(match.playerB)) == setOf(a, b) &&
                    (normalize(match.winner) == a || normalize(match.winner) == b)
            }
            if (result != null && settlePrediction(prediction.id, result.record.winner)) verified++
        }
        return verified
    }

    private fun createPredictionsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PREDICTIONS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at_ms INTEGER NOT NULL,
                target_match_date TEXT NOT NULL DEFAULT '',
                player_a TEXT NOT NULL,
                player_b TEXT NOT NULL,
                surface TEXT NOT NULL,
                probability_a REAL NOT NULL,
                lower_probability_a REAL NOT NULL,
                upper_probability_a REAL NOT NULL,
                overall_matches_a INTEGER NOT NULL,
                overall_matches_b INTEGER NOT NULL,
                surface_matches_a INTEGER NOT NULL,
                surface_matches_b INTEGER NOT NULL,
                recent_form_a TEXT NOT NULL,
                recent_form_b TEXT NOT NULL,
                source TEXT NOT NULL,
                settled_winner TEXT,
                settled_at_ms INTEGER,
                log_loss REAL,
                brier_score REAL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_predictions_target_date ON $TABLE_PREDICTIONS(target_match_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_predictions_settled ON $TABLE_PREDICTIONS(settled_winner)")
    }

    private fun matchSignature(match: MatchRecord): String {
        val players = listOf(normalize(match.playerA), normalize(match.playerB)).sorted()
        val canonical = listOf(
            match.date.trim(),
            match.surface.name,
            players[0],
            players[1],
            normalize(match.winner)
        ).joinToString(separator = "\u001F")

        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun normalizeDate(value: String): String = value.trim().take(10)

    private fun normalize(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private companion object {
        const val DATABASE_NAME = "tennis_predictor_history.db"
        const val DATABASE_VERSION = 2
        const val TABLE_MATCHES = "matches"
        const val TABLE_PREDICTIONS = "predictions"
    }
}
