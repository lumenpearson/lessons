package com.lumenpearson.lessons.core.data.catalog

import java.util.Locale

/**
 * Finding one of the 89 regions from what somebody types — «самара»,
 * «Samara», «cfvfhf» typed on the wrong layout, «moskva», «ХМАО», «Тольятти»,
 * «47» — without a request.
 *
 * Every word it treats specially comes from the catalog's `search` block
 * ([CatalogSearch]): the fold map, the region type words it ignores, the two
 * keyboard layouts, the transliteration table, the English words that stand
 * for others («St», «region») and the words a school's name is made of. The
 * server's directory reads the same block (`catalog.school_words`), so «a
 * name too common to look up» means one thing on both sides, and no
 * Russian word is written into this file.
 *
 * Ranking, best first — ties go to the constitution's order, which is the
 * list's:
 *
 *  0. the region's name, exactly;
 *  1. every word typed is a whole word of the name;
 *  2. every word typed starts a word of the name («моск»);
 *  3. an alias («Питер», «ХМАО»), exactly or by its start;
 *  4. a city in the region («Тольятти»), exactly or by its start;
 *  5. the two-digit subject code («47», «78»);
 *  6. anywhere inside a name, alias or city (three characters or more).
 *
 * An ambiguous query answers every region it fits: «Алтай» is both Altai
 * regions, and choosing between them is the person's.
 */
class RegionSearch(val catalog: RegionCatalog) {

    private val lexicon = catalog.search

    private val fold: Map<Char, String> = lexicon.fold.entries
        .filter { it.key.length == 1 }
        .associate { it.key[0] to it.value }

    private val stopWords: Set<String> = lexicon.stopWords.flatMap { normaliseTokens(it, drop = emptySet()) }.toSet()

    private val schoolWords: Set<String> = lexicon.schoolWords.map { foldCase(it) }.toSet()

    private val latinToCyrillic: Map<Char, Char> = layoutMap(lexicon.latinLayout, lexicon.cyrillicLayout)
    private val cyrillicToLatin: Map<Char, Char> = layoutMap(lexicon.cyrillicLayout, lexicon.latinLayout)

    /** A word typed, and the words of a name it may stand for besides itself («st», «saint»). */
    private val synonyms: Map<String, List<String>> = lexicon.synonyms
        .mapKeys { (typed, _) -> typed.lowercase(Locale.ROOT) }
        .mapValues { (_, meant) -> meant.map { it.lowercase(Locale.ROOT) }.filter { it.isNotEmpty() } }

    /** Latin sequences, longest first, as the table lists them. */
    private val translit: List<Pair<String, String>> = lexicon.translit
        .filter { it.size == 2 && it[0].isNotEmpty() }
        .map { it[0] to it[1] }

    private val index: List<Entry> = catalog.regions.map { region ->
        Entry(
            region = region,
            names = listOf(region.nameRu, region.nameEn).filter { it.isNotBlank() }.map { normalise(it) },
            aliases = region.aliases.map { normalise(it) },
            cities = region.cities.map { normalise(it) },
            code = region.code.toIntOrNull(),
        )
    }

    private class Entry(
        val region: CatalogRegion,
        val names: List<List<String>>,
        val aliases: List<List<String>>,
        val cities: List<List<String>>,
        val code: Int?,
    )

    /**
     * The regions [query] may mean, best first. A blank query is every region
     * in the list's order, so a screen can show the whole list and narrow it
     * as the person types.
     */
    fun search(query: String): List<RegionMatch> {
        if (query.isBlank()) {
            return catalog.regions.sortedBy { it.order }.map { RegionMatch(it, MatchVia.LIST, "", LIST_SCORE) }
        }
        val candidates = candidates(query).map { normalise(it) }.filter { it.isNotEmpty() }
            .flatMap { variants(it) }
            .distinct()
        val digits = query.trim().takeIf { it.length in 1..3 && it.all(Char::isDigit) }?.toIntOrNull()
        return index.mapNotNull { entry ->
            val scored = candidates.mapNotNull { score(entry, it) }
            val byCode = if (digits != null && digits == entry.code) {
                RegionMatch(entry.region, MatchVia.CODE, entry.region.code, CODE_SCORE)
            } else {
                null
            }
            (scored + listOfNotNull(byCode)).minByOrNull { it.score }
        }.sortedWith(compareBy<RegionMatch> { it.score }.thenBy { it.region.order })
    }

    /**
     * Whether [query] looks like a school's name rather than a place — the
     * question of whether to ask the server's directory at all. It does when it
     * carries a word schools are named with («лицей», «гимназия», «№»), or a
     * number beside a word («1 казань»), or a number too long to be a subject
     * code.
     */
    fun looksLikeSchool(query: String): Boolean {
        val tokens = schoolTokens(query)
        if (tokens.any { it in schoolWords }) return true
        val numbers = tokens.filter { token -> token.all(Char::isDigit) }
        if (numbers.any { it.length > 3 }) return true
        return numbers.isNotEmpty() && tokens.any { token -> token.none(Char::isDigit) }
    }

    /**
     * The server's `services/schools.is_generic`, token for token: with the
     * words a school's name is made of taken away, is anything left that tells
     * schools apart — a town, a name, a number of three digits or more? If not
     * («школа № 5», «СОШ 12», «гимназия 3»), the name is in every region and
     * asking the directory would spend a request to place nothing.
     */
    fun isGenericSchool(query: String): Boolean =
        schoolTokens(query)
            .filterNot { it in schoolWords }
            .all { token -> token.all(Char::isDigit) && token.length <= 2 }

    /**
     * What else [query] could have been typed as: itself; the same keys on the
     * other layout, both ways (a Russian word typed on an English keyboard);
     * and a Latin query spelt out in Cyrillic («moskva»).
     */
    internal fun candidates(query: String): List<String> {
        val lower = query.lowercase(Locale.ROOT)
        val swappedToCyrillic = lower.map { latinToCyrillic[it] ?: it }.joinToString("")
        val swappedToLatin = lower.map { cyrillicToLatin[it] ?: it }.joinToString("")
        val latin = lower.any { it in 'a'..'z' }
        return listOfNotNull(
            lower,
            swappedToCyrillic.takeIf { it != lower },
            swappedToLatin.takeIf { it != lower },
            transliterate(lower).takeIf { latin },
        ).distinct()
    }

    /**
     * The words of [text] as the search compares them: lower case, folded
     * («ё» as «е», every dash a space), punctuation dropped, and the region
     * type words («область», «край», «республика») left out — nobody picks
     * a region by the word «область».
     */
    internal fun normalise(text: String): List<String> = normaliseTokens(text, stopWords)

    private fun normaliseTokens(text: String, drop: Set<String>): List<String> {
        val folded = buildString {
            for (char in text.lowercase(Locale.ROOT)) {
                val mapped = fold[char] ?: char.toString()
                for (c in mapped) append(if (c.isLetterOrDigit()) c else ' ')
            }
        }
        return folded.split(' ').filter { it.isNotEmpty() && it !in drop }
    }

    private fun score(entry: Entry, words: List<String>): RegionMatch? {
        val joined = words.joinToString(" ")
        entry.names.forEach { name ->
            if (name == words) return RegionMatch(entry.region, MatchVia.NAME, joined, 0)
        }
        entry.names.forEach { name ->
            if (words.all { it in name }) return RegionMatch(entry.region, MatchVia.NAME, joined, 1)
        }
        entry.names.forEach { name ->
            if (startsWords(name, words)) return RegionMatch(entry.region, MatchVia.NAME, joined, 2)
        }
        entry.aliases.forEach { alias ->
            if (alias == words || startsWords(alias, words)) {
                return RegionMatch(entry.region, MatchVia.ALIAS, alias.joinToString(" "), 3)
            }
        }
        entry.cities.forEach { city ->
            if (city == words || startsWords(city, words)) {
                return RegionMatch(entry.region, MatchVia.CITY, city.joinToString(" "), 4)
            }
        }
        if (joined.length >= SUBSTRING_MIN) {
            val inside = (entry.names + entry.aliases + entry.cities)
                .firstOrNull { joined in it.joinToString(" ") }
            if (inside != null) return RegionMatch(entry.region, MatchVia.SUBSTRING, inside.joinToString(" "), 6)
        }
        return null
    }

    /**
     * [words], and every way of reading them with a word swapped for what the
     * catalog says it may stand for: «st petersburg» is also «saint
     * petersburg», «moscow region» also «moscow oblast». A handful at most —
     * the table is short and a query is a few words — but capped anyway, so a
     * pasted paragraph of «st st st» cannot multiply without bound.
     */
    private fun variants(words: List<String>): List<List<String>> {
        var out = listOf(words)
        for ((at, word) in words.withIndex()) {
            val meant = synonyms[word] ?: continue
            out = out.flatMap { variant ->
                listOf(variant) + meant.map { replacement ->
                    variant.toMutableList().also { it[at] = replacement }
                }
            }.take(MAX_VARIANTS)
        }
        return out
    }

    /** Every word typed starts some word of [target]. */
    private fun startsWords(target: List<String>, words: List<String>): Boolean =
        words.isNotEmpty() && words.all { word -> target.any { it.startsWith(word) } }

    private fun transliterate(text: String): String = buildString {
        var at = 0
        while (at < text.length) {
            val hit = translit.firstOrNull { (latin, _) -> text.startsWith(latin, at) }
            if (hit == null) {
                append(text[at])
                at += 1
            } else {
                append(hit.second)
                at += hit.first.length
            }
        }
    }

    /**
     * The server's tokens for a school's name (`services/schools._TOKENS`): the
     * numero sign alone, a run of digits, or a run of letters, after folding
     * case and «ё».
     */
    private fun schoolTokens(query: String): List<String> =
        SCHOOL_TOKEN.findAll(foldCase(query)).map { it.value }.toList()

    /** Lower case and the letter folds only («ё» as «е»), as the server's `_fold`. */
    private fun foldCase(text: String): String = buildString {
        for (char in text.lowercase(Locale.ROOT)) {
            append(if (char.isLetter()) fold[char] ?: char.toString() else char.toString())
        }
    }

    private fun layoutMap(from: String, to: String): Map<Char, Char> =
        if (from.length != to.length) emptyMap() else from.indices.associate { from[it] to to[it] }

    private companion object {
        const val CODE_SCORE = 5
        const val LIST_SCORE = 7
        const val SUBSTRING_MIN = 3
        const val MAX_VARIANTS = 16

        /** `№|\d+|[^\W\d_]+`, in Java's spelling; the numero sign is U+2116. */
        val SCHOOL_TOKEN = Regex("№|\\p{Nd}+|[\\p{L}\\p{M}]+")
    }
}

/** How a region was found; see [RegionSearch]. */
enum class MatchVia { NAME, ALIAS, CITY, CODE, SUBSTRING, LIST }

/**
 * One region a query may mean.
 *
 * @property matched the normalised words it was found by — a city's name when
 *   [via] is [MatchVia.CITY], so a screen can say «Тольятти — Самарская
 *   область».
 * @property score lower is better; see [RegionSearch].
 */
data class RegionMatch(
    val region: CatalogRegion,
    val via: MatchVia,
    val matched: String,
    val score: Int,
)
