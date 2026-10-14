package ru.tennispredictor.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.util.Locale

data class StoredMatch(
    val id: Long,
    val record: MatchRecord
)

data class DatabaseImportSummary(
    val inserted: Int,
    val duplicates: Int
)

/**
 * A small on-device SQLite store. Imported results remain on this phone and are
 * not uploaded to a remote service.
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
        // Keep imported history intact while adding the Stage 3 prediction ledger.
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


    fun savePrediction(prediction: SavedPrediction): Long {
        val values = ContentValues().apply {
            put("created_at", prediction.createdAt)
            put("match_date", prediction.matchDate)
            put("player_a", prediction.playerA)
            put("player_b", prediction.playerB)
            put("surface", prediction.surface.name)
            put("probability_a", prediction.probabilityA)
            put("overall_elo_a", prediction.overallEloA)
            put("overall_elo_b", prediction.overallEloB)
            put("surface_elo_a", prediction.surfaceEloA)
            put("surface_elo_b", prediction.surfaceEloB)
            put("form_a", prediction.formA)
            put("form_b", prediction.formB)
            put("total_matches_a", prediction.totalMatchesA)
            put("total_matches_b", prediction.totalMatchesB)
            put("surface_matches_a", prediction.surfaceMatchesA)
            put("surface_matches_b", prediction.surfaceMatchesB)
            put("source", prediction.source)
            put("uncertainty_label", prediction.uncertaintyLabel)
            put("uncertainty_reason", prediction.uncertaintyReason)
            put("probability_low", prediction.probabilityLow)
            put("probability_high", prediction.probabilityHigh)
            prediction.resultWinner?.let { put("result_winner", it) }
        }
        return writableDatabase.insert(TABLE_PREDICTIONS, null, values)
    }

    fun loadSavedPredictions(): List<SavedPrediction> {
        val result = mutableListOf<SavedPrediction>()
        readableDatabase.query(
            TABLE_PREDICTIONS,
            null,
            null,
            null,
            null,
            null,
            "created_at DESC, id DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val rawSurface = cursor.getString(cursor.getColumnIndexOrThrow("surface"))
                val surface = runCatching { Surface.valueOf(rawSurface) }.getOrDefault(Surface.OTHER)
                result += SavedPrediction(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                    matchDate = cursor.getString(cursor.getColumnIndexOrThrow("match_date")),
                    playerA = cursor.getString(cursor.getColumnIndexOrThrow("player_a")),
                    playerB = cursor.getString(cursor.getColumnIndexOrThrow("player_b")),
                    surface = surface,
                    probabilityA = cursor.getDouble(cursor.getColumnIndexOrThrow("probability_a")),
                    overallEloA = cursor.getDouble(cursor.getColumnIndexOrThrow("overall_elo_a")),
                    overallEloB = cursor.getDouble(cursor.getColumnIndexOrThrow("overall_elo_b")),
                    surfaceEloA = cursor.getDouble(cursor.getColumnIndexOrThrow("surface_elo_a")),
                    surfaceEloB = cursor.getDouble(cursor.getColumnIndexOrThrow("surface_elo_b")),
                    formA = cursor.getString(cursor.getColumnIndexOrThrow("form_a")),
                    formB = cursor.getString(cursor.getColumnIndexOrThrow("form_b")),
                    totalMatchesA = cursor.getInt(cursor.getColumnIndexOrThrow("total_matches_a")),
                    totalMatchesB = cursor.getInt(cursor.getColumnIndexOrThrow("total_matches_b")),
                    surfaceMatchesA = cursor.getInt(cursor.getColumnIndexOrThrow("surface_matches_a")),
                    surfaceMatchesB = cursor.getInt(cursor.getColumnIndexOrThrow("surface_matches_b")),
                    source = cursor.getString(cursor.getColumnIndexOrThrow("source")),
                    uncertaintyLabel = cursor.getString(cursor.getColumnIndexOrThrow("uncertainty_label")),
                    uncertaintyReason = cursor.getString(cursor.getColumnIndexOrThrow("uncertainty_reason")),
                    probabilityLow = cursor.getDouble(cursor.getColumnIndexOrThrow("probability_low")),
                    probabilityHigh = cursor.getDouble(cursor.getColumnIndexOrThrow("probability_high")),
                    resultWinner = cursor.getString(cursor.getColumnIndexOrThrow("result_winner"))
                        ?.takeIf { it.isNotBlank() }
                )
            }
        }
        return result
    }

    fun resolvePredictionResult(predictionId: Long, winner: String): Boolean {
        val cleanWinner = winner.trim()
        if (cleanWinner.isBlank()) return false
        val values = ContentValues().apply { put("result_winner", cleanWinner) }
        return writableDatabase.update(
            TABLE_PREDICTIONS,
            values,
            "id = ? AND (result_winner IS NULL OR result_winner = '')",
            arrayOf(predictionId.toString())
        ) > 0
    }

    /**
     * Resolve saved forecasts only when an imported result matches the planned date,
     * both players (in either order), and the selected surface. No fuzzy name matching.
     */
    fun reconcileSavedPredictions(history: List<StoredMatch>): Int {
        var resolved = 0
        val pending = loadSavedPredictions().filter { it.resultWinner.isNullOrBlank() }
        pending.forEach { prediction ->
            val match = history.firstOrNull { stored ->
                normalizeMatchDate(stored.record.date) == prediction.matchDate &&
                    stored.record.surface == prediction.surface &&
                    setOf(normalize(stored.record.playerA), normalize(stored.record.playerB)) ==
                    setOf(normalize(prediction.playerA), normalize(prediction.playerB)) &&
                    stored.record.winner.isNotBlank()
            }
            if (match != null && resolvePredictionResult(prediction.id, match.record.winner)) resolved++
        }
        return resolved
    }

    private fun createPredictionsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PREDICTIONS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at INTEGER NOT NULL,
                match_date TEXT NOT NULL,
                player_a TEXT NOT NULL,
                player_b TEXT NOT NULL,
                surface TEXT NOT NULL,
                probability_a REAL NOT NULL,
                overall_elo_a REAL NOT NULL,
                overall_elo_b REAL NOT NULL,
                surface_elo_a REAL NOT NULL,
                surface_elo_b REAL NOT NULL,
                form_a TEXT NOT NULL,
                form_b TEXT NOT NULL,
                total_matches_a INTEGER NOT NULL,
                total_matches_b INTEGER NOT NULL,
                surface_matches_a INTEGER NOT NULL,
                surface_matches_b INTEGER NOT NULL,
                source TEXT NOT NULL,
                uncertainty_label TEXT NOT NULL,
                uncertainty_reason TEXT NOT NULL,
                probability_low REAL NOT NULL,
                probability_high REAL NOT NULL,
                result_winner TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_predictions_match_date ON $TABLE_PREDICTIONS(match_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_predictions_result ON $TABLE_PREDICTIONS(result_winner)")
    }

    private fun normalizeMatchDate(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.matches(Regex("\\d{8}"))) {
            "${trimmed.substring(0, 4)}-${trimmed.substring(4, 6)}-${trimmed.substring(6, 8)}"
        } else {
            trimmed.take(10)
        }
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

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)

    private companion object {
        const val DATABASE_NAME = "tennis_predictor_history.db"
        const val DATABASE_VERSION = 2
        const val TABLE_MATCHES = "matches"
        const val TABLE_PREDICTIONS = "saved_predictions"
    }
}
