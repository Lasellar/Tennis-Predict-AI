package ru.tennispredictor.app

/**
 * Returns a small, stable suggestion set without sorting every partial match.
 * The input list is already sorted case-insensitively by MatchDatabase.
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
        if (name.equals(query, ignoreCase = true)) continue
        if (name.startsWith(query, ignoreCase = true)) {
            if (prefixMatches.size < limit) prefixMatches.add(name)
        } else if (otherMatches.size < limit && name.contains(query, ignoreCase = true)) {
            otherMatches.add(name)
        }
        if (prefixMatches.size >= limit && otherMatches.size >= limit) break
    }
    return (prefixMatches + otherMatches).take(limit)
}
