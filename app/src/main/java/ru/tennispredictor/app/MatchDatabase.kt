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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Keep existing history intact. Add explicit migrations here if the schema changes.
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
        const val DATABASE_VERSION = 1
        const val TABLE_MATCHES = "matches"
    }
}
