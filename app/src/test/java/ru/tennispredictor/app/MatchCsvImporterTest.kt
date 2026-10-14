package ru.tennispredictor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchCsvImporterTest {
    @Test
    fun parsesCompactCsvAndQuotedNames() {
        val csv = """
            date,player_a,player_b,winner,surface
            2024-01-05,"Player, One",Player B,"Player, One",hard
            2024-01-06,Player B,Player C,Player C,clay
        """.trimIndent()

        val result = MatchCsvImporter.parse(csv)

        assertEquals(2, result.records.size)
        assertEquals("Player, One", result.records[0].playerA)
        assertEquals("2024-01-05", result.records[0].date)
        assertEquals(Surface.HARD, result.records[0].surface)
        assertEquals("Player C", result.records[1].winner)
        assertEquals(Surface.CLAY, result.records[1].surface)
        assertEquals(0, result.invalidRows)
    }

    @Test
    fun parsesCommonAtpArchiveColumns() {
        val csv = """
            tourney_name,surface,tourney_date,winner_name,loser_name
            Example Open,Hard,20240105,Carlos Example,Alex Player
            Clay Cup,Clay,20240106,Alex Player,Carlos Example
        """.trimIndent()

        val result = MatchCsvImporter.parse(csv)

        assertEquals(2, result.records.size)
        assertEquals("Carlos Example", result.records[0].playerA)
        assertEquals("Carlos Example", result.records[0].winner)
        assertEquals("2024-01-05", result.records[0].date)
        assertEquals(Surface.HARD, result.records[0].surface)
        assertEquals(Surface.CLAY, result.records[1].surface)
    }

    @Test
    fun marksMalformedRowsWithoutDiscardingValidRows() {
        val csv = """
            player_a,player_b,winner,surface
            A,B,A,Hard
            A,,A,Grass
            B,C,C,Grass
        """.trimIndent()

        val result = MatchCsvImporter.parse(csv)

        assertEquals(2, result.records.size)
        assertEquals(1, result.invalidRows)
        assertTrue(result.issues.isNotEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedHeaders() {
        MatchCsvImporter.parse("name,score\nA,6-2")
    }

    @Test
    fun detectsSemicolonDelimitedCsv() {
        val csv = """
            date;player_a;player_b;winner;surface
            2024-02-01;A;B;B;Grass
        """.trimIndent()

        val result = MatchCsvImporter.parse(csv)

        assertEquals(1, result.records.size)
        assertEquals(Surface.GRASS, result.records.single().surface)
    }
}
