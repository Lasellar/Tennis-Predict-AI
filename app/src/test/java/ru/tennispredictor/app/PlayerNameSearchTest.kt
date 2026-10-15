package ru.tennispredictor.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerNameSearchTest {
    @Test
    fun suggestionsPrioritizePrefixesAndStayBounded() {
        val names = listOf(
            "Alexander Zverev",
            "Alex de Minaur",
            "Alex Michelsen",
            "Alcaraz",
            "Carlos Alcaraz",
            "Daniil Medvedev",
            "Karen Khachanov"
        )

        val result = findPlayerSuggestions(names, "alex", limit = 2)

        assertEquals(listOf("Alexander Zverev", "Alex de Minaur"), result)
    }

    @Test
    fun suggestionsIgnoreShortQueriesAndExactMatch() {
        val names = listOf("Alex de Minaur", "Alexander Zverev")

        assertEquals(emptyList<String>(), findPlayerSuggestions(names, "a"))
        assertEquals(emptyList<String>(), findPlayerSuggestions(names, "Alex de Minaur"))
    }

    @Test
    fun suggestionsAreCaseInsensitiveAndFindInteriorMatches() {
        val names = listOf("Alex de Minaur", "Carlos Alcaraz", "Daniil Medvedev")

        assertEquals(
            listOf("Alex de Minaur", "Carlos Alcaraz"),
            findPlayerSuggestions(names, "al")
        )
    }
}
