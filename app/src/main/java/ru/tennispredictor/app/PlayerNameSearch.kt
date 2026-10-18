package ru.tennispredictor.app

/**
 * Returns a bounded suggestion set. Archive names remain canonical: aliases only
 * affect matching, so prediction/history lookups continue using the original names.
 */
internal fun findPlayerSuggestions(
    playerNames: List<String>,
    rawQuery: String,
    limit: Int = 8
): List<String> {
    val normalizedQuery = normalizePlayerSearchText(rawQuery)
    if (normalizedQuery.length < 2 || limit <= 0) return emptyList()

    // Compute transliteration once per keystroke, not once for every player in the database.
    val normalizedTransliteration =
        normalizePlayerSearchText(transliterateRussianQuery(rawQuery))
    val prefixMatches = ArrayList<String>(limit)
    val otherMatches = ArrayList<String>(limit)

    for (name in playerNames) {
        val normalizedName = normalizePlayerSearchText(name)
        if (normalizedName == normalizedQuery) continue

        val matches = playerNameMatchesNormalizedQuery(
            normalizedName,
            normalizedQuery,
            normalizedTransliteration
        )
        if (!matches) continue

        val transliteratedPrefix = normalizedTransliteration.length >= 2 &&
            (normalizedName.startsWith(normalizedTransliteration) ||
                normalizedName.split(" ").any { it.startsWith(normalizedTransliteration) })
        val isPrefix = normalizedName.startsWith(normalizedQuery) ||
            transliteratedPrefix ||
            playerNameAliasStartsWithQuery(name, normalizedQuery)

        if (isPrefix) {
            if (prefixMatches.size < limit) prefixMatches.add(name)
        } else if (otherMatches.size < limit) {
            otherMatches.add(name)
        }
        if (prefixMatches.size >= limit && otherMatches.size >= limit) break
    }
    return (prefixMatches + otherMatches).take(limit)
}
