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

data class MatchDatabaseSummary(
    val matchCount: Int,
    val hardMatchCount: Int,
    val clayMatchCount: Int,
    val grassMatchCount: Int,
    val ratings: RatingSnapshot
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
        db.execSQL("CREATE INDEX idx_matches_date_id ON $TABLE_MATCHES(match_date, id)")
        db.execSQL("CREATE INDEX idx_matches_surface ON $TABLE_MATCHES(surface)")
        createPredictionsTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 2 adds the current prediction ledger. Version 3 also handles users
        // who previously installed the alternate 0.4.0 Stage 3 build, whose table was
        // named "predictions" and used a different set of columns.
        if (oldVersion < 2 && !tableExists(db, TABLE_PREDICTIONS)) {
            createPredictionsTable(db)
        }
        if (oldVersion < 3 && !tableExists(db, TABLE_PREDICTIONS)) {
            val hasLegacyPredictions = tableExists(db, LEGACY_TABLE_PREDICTIONS)
            createPredictionsTable(db)
            if (hasLegacyPredictions) migrateLegacyPredictions(db)
        }
        if (oldVersion < 4) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_matches_date_id ON $TABLE_MATCHES(match_date, id)")
        }
    }

    /**
     * Loads complete history only for an explicit analysis action. The home screen
     * uses loadSummary(), which streams rows from SQLite without allocating one
     * StoredMatch object per historical row.
     */
    fun loadMatches(): List<StoredMatch> {
        val db = readableDatabase
        val orderBy = if (hasUndatedMatches(db)) "id ASC" else "match_date ASC, id ASC"
        val result = ArrayList<StoredMatch>()
        db.query(
            TABLE_MATCHES,
            arrayOf("id", "match_date", "player_a", "player_b", "winner", "surface"),
            null,
            null,
            null,
            null,
            orderBy
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursorToStoredMatch(cursor)
        }
        return result
    }

    /**
     * Counts rows and computes the four Elo tables in a single cursor pass. The
     * UI calls this on Dispatchers.IO, so large archives never block composition.
     */
    fun loadSummary(): MatchDatabaseSummary {
        val db = readableDatabase
        // One aggregate query replaces five independent table scans.
        val counts = db.rawQuery(
            """
            SELECT
                COUNT(*),
                COALESCE(SUM(CASE WHEN surface = ? THEN 1 ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN surface = ? THEN 1 ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN surface = ? THEN 1 ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN match_date IS NULL OR TRIM(match_date) = '' THEN 1 ELSE 0 END), 0)
            FROM $TABLE_MATCHES
            """.trimIndent(),
            arrayOf(Surface.HARD.name, Surface.CLAY.name, Surface.GRASS.name)
        ).use { cursor ->
            if (cursor.moveToFirst()) IntArray(5) { cursor.getInt(it) } else IntArray(5)
        }

        val orderBy = if (counts[4] > 0) "id ASC" else "match_date ASC, id ASC"
        val accumulator = RatingCalculator.Accumulator()
        db.query(
            TABLE_MATCHES,
            arrayOf("id", "match_date", "player_a", "player_b", "winner", "surface"),
            null,
            null,
            null,
            null,
            orderBy
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val surface = runCatching {
                    Surface.valueOf(cursor.getString(5))
                }.getOrDefault(Surface.OTHER)
                accumulator.add(
                    rawPlayerA = cursor.getString(2),
                    rawPlayerB = cursor.getString(3),
                    rawWinner = cursor.getString(4),
                    surface = surface
                )
            }
        }
        return MatchDatabaseSummary(
            matchCount = counts[0],
            hardMatchCount = counts[1],
            clayMatchCount = counts[2],
            grassMatchCount = counts[3],
            ratings = accumulator.snapshot()
        )
    }

    /**
     * Names are much smaller than the match table and are loaded separately.
     * Deduplicate case/whitespace variants to keep autocomplete useful with large
     * ATP/WTA archives.
     */
    fun loadPlayerNames(): List<String> {
        val result = ArrayList<String>()
        val seen = HashSet<String>()
        readableDatabase.rawQuery(
            """
            SELECT player FROM (
                SELECT TRIM(player_a) AS player FROM $TABLE_MATCHES
                UNION
                SELECT TRIM(player_b) AS player FROM $TABLE_MATCHES
            )
            WHERE player <> ''
            ORDER BY player COLLATE NOCASE ASC
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0).trim()
                if (name.isNotBlank() && seen.add(normalize(name))) result += name
            }
        }
        return result
    }

    private fun cursorToStoredMatch(cursor: android.database.Cursor): StoredMatch {
        val surfaceName = cursor.getString(cursor.getColumnIndexOrThrow("surface"))
        val surface = runCatching { Surface.valueOf(surfaceName) }.getOrDefault(Surface.OTHER)
        return StoredMatch(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            record = MatchRecord(
                playerA = cursor.getString(cursor.getColumnIndexOrThrow("player_a")),
                playerB = cursor.getString(cursor.getColumnIndexOrThrow("player_b")),
                winner = cursor.getString(cursor.getColumnIndexOrThrow("winner")),
                surface = surface,
                date = cursor.getString(cursor.getColumnIndexOrThrow("match_date"))
            )
        )
    }

    private fun hasUndatedMatches(db: SQLiteDatabase): Boolean =
        db.rawQuery(
            "SELECT 1 FROM $TABLE_MATCHES WHERE match_date IS NULL OR TRIM(match_date) = '' LIMIT 1",
            null
        ).use { cursor -> cursor.moveToFirst() }

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
    /**
     * Reconciles only dates referenced by unresolved forecasts. This avoids a
     * forecast-count × 96k-history scan and does not need to load match history.
     */
    fun reconcileSavedPredictions(): Int {
        val pending = loadSavedPredictions().filter { it.resultWinner.isNullOrBlank() }
        if (pending.isEmpty()) return 0
        val db = readableDatabase
        var resolved = 0
        pending.forEach { prediction ->
            val matchDate = prediction.matchDate.trim()
            if (matchDate.isBlank()) return@forEach
            val compactDate = matchDate.replace("-", "")
            var winner: String? = null
            db.query(
                TABLE_MATCHES,
                arrayOf("match_date", "player_a", "player_b", "winner", "surface"),
                "(match_date = ? OR match_date = ?) AND surface = ?",
                arrayOf(matchDate, compactDate, prediction.surface.name),
                null,
                null,
                "id DESC"
            ).use { cursor ->
                while (winner == null && cursor.moveToNext()) {
                    val date = cursor.getString(cursor.getColumnIndexOrThrow("match_date"))
                    val playerA = cursor.getString(cursor.getColumnIndexOrThrow("player_a"))
                    val playerB = cursor.getString(cursor.getColumnIndexOrThrow("player_b"))
                    val resultWinner = cursor.getString(cursor.getColumnIndexOrThrow("winner"))
                    val playersMatch =
                        setOf(normalize(playerA), normalize(playerB)) ==
                            setOf(normalize(prediction.playerA), normalize(prediction.playerB))
                    if (normalizeMatchDate(date) == matchDate &&
                        playersMatch &&
                        !resultWinner.isNullOrBlank()
                    ) {
                        winner = resultWinner
                    }
                }
            }
            if (winner != null && resolvePredictionResult(prediction.id, winner!!)) resolved++
        }
        return resolved
    }

    /**
     * Backward-compatible overload. Matching is performed through indexed,
     * date-scoped database queries rather than scanning the supplied full list.
     */
    fun reconcileSavedPredictions(@Suppress("UNUSED_PARAMETER") history: List<StoredMatch>): Int =
        reconcileSavedPredictions()

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


    private fun tableExists(db: SQLiteDatabase, tableName: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(tableName)
        ).use { cursor -> cursor.moveToFirst() }

    /**
     * Preserve forecasts from the first 0.4.0 Stage 3 prototype. That version
     * stored counts/forms/probability bounds and settled outcomes, but not Elo
     * snapshots or the newer uncertainty metadata.
     */
    private fun migrateLegacyPredictions(db: SQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO $TABLE_PREDICTIONS (
                id, created_at, match_date, player_a, player_b, surface,
                probability_a, overall_elo_a, overall_elo_b, surface_elo_a, surface_elo_b,
                form_a, form_b, total_matches_a, total_matches_b,
                surface_matches_a, surface_matches_b, source,
                uncertainty_label, uncertainty_reason, probability_low, probability_high,
                result_winner
            )
            SELECT
                id,
                created_at_ms,
                substr(COALESCE(target_match_date, ''), 1, 10),
                player_a,
                player_b,
                surface,
                probability_a,
                1500.0,
                1500.0,
                1500.0,
                1500.0,
                recent_form_a,
                recent_form_b,
                overall_matches_a,
                overall_matches_b,
                surface_matches_a,
                surface_matches_b,
                source,
                'Не оценена',
                'Перенесено из версии 0.4.0; подробная оценка неопределённости не сохранялась.',
                lower_probability_a,
                upper_probability_a,
                settled_winner
            FROM $LEGACY_TABLE_PREDICTIONS
            """.trimIndent()
        )
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

    private fun normalize(value: String): String = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private companion object {
        const val DATABASE_NAME = "tennis_predictor_history.db"
        const val DATABASE_VERSION = 4
        const val TABLE_MATCHES = "matches"
        const val TABLE_PREDICTIONS = "saved_predictions"
        const val LEGACY_TABLE_PREDICTIONS = "predictions"
    }
}


/**
 * Application-scoped database helper. Keeping one helper for the process avoids
 * closing an active SQLite cursor when Android recreates the Activity on rotation.
 */
object MatchDatabaseProvider {
    @Volatile
    private var instance: MatchDatabase? = null

    fun get(context: Context): MatchDatabase {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: MatchDatabase(context.applicationContext).also { instance = it }
        }
    }
}
