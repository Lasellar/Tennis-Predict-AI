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
    val query = rawQuery.trim()
    if (query.length < 2 || limit <= 0) return emptyList()

    val prefixMatches = ArrayList<String>(limit)
    val otherMatches = ArrayList<String>(limit)
    for (name in playerNames) {
        if (normalizePlayerSearchText(name) == normalizePlayerSearchText(query)) continue

        val normalizedName = normalizePlayerSearchText(name)
        val normalizedQuery = normalizePlayerSearchText(query)
        val isPrefix = normalizedName.startsWith(normalizedQuery) ||
            playerNameMatchesQuery(name, query) &&
            transliteratedOrAliasPrefixMatch(name, query)

        if (isPrefix) {
            if (prefixMatches.size < limit) prefixMatches.add(name)
        } else if (otherMatches.size < limit && playerNameMatchesQuery(name, query)) {
            otherMatches.add(name)
        }
        if (prefixMatches.size >= limit && otherMatches.size >= limit) break
    }
    return (prefixMatches + otherMatches).take(limit)
}

private fun transliteratedOrAliasPrefixMatch(archiveName: String, query: String): Boolean {
    val normalizedQuery = normalizePlayerSearchText(query)
    val latinQuery = normalizePlayerSearchText(transliterateRussianQuery(query))
    val normalizedArchive = normalizePlayerSearchText(archiveName)
    return normalizedArchive.startsWith(latinQuery) ||
        normalizedArchive.split(" ").any { it.startsWith(latinQuery) } ||
        (normalizedQuery.length >= 2 && playerNameMatchesQuery(archiveName, query))
}
