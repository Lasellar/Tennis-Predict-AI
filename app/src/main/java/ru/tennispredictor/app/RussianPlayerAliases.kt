package ru.tennispredictor.app

import java.util.Locale

private val knownRussianAliases: Map<String, Set<String>> = mapOf(
    "daniil medvedev" to setOf("даниил медведев", "данил медведев", "медведев"),
    "andrey rublev" to setOf("андрей рублев", "андрей рублёв", "рублев", "рублёв"),
    "karen khachanov" to setOf("карен хачанов", "хачанов"),
    "alexander zverev" to setOf("александр зверев", "зверев"),
    "carlos alcaraz" to setOf("карлос алькарас", "алькарас"),
    "jannik sinner" to setOf("янник синнер", "синнер"),
    "novak djokovic" to setOf("новак джокович", "джокович"),
    "rafael nadal" to setOf("рафаэль надаль", "рафаэль надал", "надаль", "надал"),
    "roger federer" to setOf("роджер федерер", "федерер"),
    "stefanos tsitsipas" to setOf("стефанос циципас", "циципас"),
    "holger rune" to setOf("хольгер руне", "руне"),
    "casper ruud" to setOf("каспер руд", "руд"),
    "taylor fritz" to setOf("тейлор фриц", "фриц"),
    "tommy paul" to setOf("томми пол", "пол"),
    "ben shelton" to setOf("бен шелтон", "шелтон"),
    "grigor dimitrov" to setOf("григор димитров", "димитров"),
    "felix auger-aliassime" to setOf("феликс оже-алиассим", "феликс оже алиассим", "оже-алиассим"),
    "alex de minaur" to setOf("алекс де минор", "де минор"),
    "aryna sabalenka" to setOf("арина соболенко", "соболенко"),
    "iga swiatek" to setOf("ига швентек", "швентек"),
    "coco gauff" to setOf("кори гафф", "коко гафф", "гафф"),
    "elena rybakina" to setOf("елена рыбакина", "рыбакина"),
    "naomi osaka" to setOf("наоми осака", "осака"),
    "jessica pegula" to setOf("джессика пегула", "пегула"),
    "mirra andreeva" to setOf("мирра андреева", "андреева")
).mapKeys { (archiveName, _) -> normalizePlayerSearchText(archiveName) }

/** Normalizes punctuation and treats Russian е/ё as equivalent for search. */
internal fun normalizePlayerSearchText(value: String): String =
    value.lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .replace(Regex("[’'`-]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

/** Fallback transliteration for Cyrillic queries; known aliases take priority. */
internal fun transliterateRussianQuery(value: String): String {
    val map = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d",
        'е' to "e", 'ё' to "yo", 'ж' to "zh", 'з' to "z", 'и' to "i",
        'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
        'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
        'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch",
        'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "",
        'э' to "e", 'ю' to "yu", 'я' to "ya"
    )
    return value.lowercase(Locale.ROOT).map { map[it] ?: it.toString() }.joinToString("")
}

internal fun playerNameMatchesQuery(archiveName: String, rawQuery: String): Boolean {
    val query = normalizePlayerSearchText(rawQuery)
    if (query.length < 2) return false

    val archive = normalizePlayerSearchText(archiveName)
    if (archive.contains(query)) return true

    val aliases = knownRussianAliases[archive]
    if (aliases.orEmpty().any { normalizePlayerSearchText(it).contains(query) }) return true

    val transliterated = normalizePlayerSearchText(transliterateRussianQuery(query))
    return transliterated.length >= 2 && archive.contains(transliterated)
}
