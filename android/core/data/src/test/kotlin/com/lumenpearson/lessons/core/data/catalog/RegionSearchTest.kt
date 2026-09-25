package com.lumenpearson.lessons.core.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The region search, over the real bundled catalog read from the test
 * classpath — the file the app ships, with the lexicon the server's directory
 * reads too.
 */
class RegionSearchTest {

    private val catalog: RegionCatalog by lazy {
        RegionCatalog.parse(
            javaClass.classLoader!!.getResourceAsStream(RegionCatalog.ASSET)!!
                .bufferedReader(Charsets.UTF_8).use { it.readText() },
        )
    }

    private val search by lazy { RegionSearch(catalog) }

    private fun keys(query: String) = search.search(query).map { it.region.key }

    private fun first(query: String) = search.search(query).firstOrNull()?.region?.key

    @Test
    fun `a region's own name finds it first, in Russian and in English`() {
        assertEquals("samara", first("Самарская область"))
        assertEquals("samara", first("самарская"))
        assertEquals("samara", first("Samara Oblast"))
        assertEquals("moscow", first("Москва"))
        assertEquals("moscow", first("moscow"))
    }

    @Test
    fun `yo and dashes fold`() {
        assertEquals("khmao", first("ханты мансийский"))
        assertEquals("khmao", first("Ханты–Мансийский"))
        assertEquals("moscow-oblast", first("Королев"))
        assertEquals("moscow-oblast", first("Королёв"))
    }

    @Test
    fun `type words are ignored`() {
        assertEquals(first("Татарстан"), first("Республика Татарстан"))
        assertEquals("leningrad", first("ленинградская обл"))
    }

    @Test
    fun `layout swap finds Samara from cfvfhf`() {
        assertEquals("samara", first("cfvfhf"))
    }

    @Test
    fun `transliteration finds Moscow from moskva`() {
        assertEquals("moscow", first("moskva"))
    }

    /**
     * How the regions are usually written in English, which is neither a word
     * of `name_en` nor a clean transliteration: an abbreviation («St»), the
     * word «region» where the name says «Oblast» or «Krai», and «-ia» for the
     * Russian «-ия». Each of these used to find nothing at all.
     */
    @Test
    fun `common English spellings find their region first`() {
        val expected = listOf(
            "St Petersburg" to "saint-petersburg",
            "St. Petersburg" to "saint-petersburg",
            "st.petersburg" to "saint-petersburg",
            "Moscow region" to "moscow-oblast",
            "Leningrad region" to "leningrad",
            "Krasnodar region" to "krasnodar",
            "Chuvashia" to "chuvashia",
            "Udmurtia" to "udmurtia",
            "Bashkiria" to "bashkortostan",
            "Khakasia" to "khakassia",
            "Zabaikalye" to "zabaikalsky",
            "Primorye" to "primorye",
            "Yakutia" to "yakutia",
            "Yakutsk" to "yakutia",
            "Khanty-Mansiysk" to "khmao",
        )
        for ((query, key) in expected) assertEquals(query, key, first(query))
    }

    /**
     * «Moscow» still means the city first: «Oblast» is not a word the search
     * drops (that would tie the two and hand Moscow Oblast the city's name),
     * only a word «region» may stand for.
     */
    @Test
    fun `region stands for oblast without making oblast a stop word`() {
        assertEquals("moscow", first("moscow"))
        assertEquals(MatchVia.NAME, search.search("moscow").first().via)
        assertEquals("moscow-oblast", first("Moscow Oblast"))
        assertFalse("moscow" in keys("Moscow region"))
    }

    @Test
    fun `a code finds its region`() {
        assertEquals(listOf("leningrad"), keys("47"))
        assertEquals(listOf("saint-petersburg"), keys("78"))
        assertEquals(listOf("adygea"), keys("1"))
    }

    @Test
    fun `aliases and cities`() {
        assertEquals("saint-petersburg", first("Питер"))
        assertEquals("leningrad", first("Ленобласть"))
        assertEquals("khmao", first("ХМАО"))
        assertEquals("leningrad", first("Гатчина"))
        assertEquals("samara", first("Тольятти"))
        val city = search.search("Тольятти").first()
        assertEquals(MatchVia.CITY, city.via)
    }

    @Test
    fun `Altai returns both`() {
        val both = keys("Алтай")
        assertTrue(both.containsAll(listOf("altai-republic", "altai-krai")))
    }

    @Test
    fun `ranking prefers the name over a city`() {
        // «Самара» is a city of Samara Oblast and the start of nothing's name
        // exactly; «Москва» is a name, and Moscow Oblast only starts with it.
        val moscow = search.search("Москва")
        assertEquals("moscow", moscow.first().region.key)
        assertEquals(MatchVia.NAME, moscow.first().via)
    }

    @Test
    fun `a blank query is the whole list in its order`() {
        val all = search.search("  ")
        assertEquals(89, all.size)
        assertEquals(all.sortedBy { it.region.order }, all)
    }

    @Test
    fun `nonsense finds nothing`() {
        assertEquals(emptyList<String>(), keys("qqqqzzzz"))
    }

    /**
     * The server's own table (`test_directory.py`,
     * `test_a_type_word_and_a_short_number_is_answered_without_dadata`), plus
     * the edges of the same rule.
     */
    @Test
    fun `isGenericSchool is the server's rule`() {
        for (generic in listOf("Школа № 5", "СОШ 12", "гимназия 3", "МБОУ СОШ №7", "лицей N 2", "школа", "№5")) {
            assertTrue(generic, search.isGenericSchool(generic))
        }
        for (asked in listOf("лицей 1535", "гимназия 1 Казань", "Школа 179", "Президентский лицей")) {
            assertFalse(asked, search.isGenericSchool(asked))
        }
    }

    @Test
    fun `looksLikeSchool tells a school from a place`() {
        for (school in listOf("лицей 1535", "гимназия 1 Казань", "школа 5", "1535", "5 казань")) {
            assertTrue(school, search.looksLikeSchool(school))
        }
        for (place in listOf("Самара", "Тольятти", "47", "Ханты-Мансийск", "moskva")) {
            assertFalse(place, search.looksLikeSchool(place))
        }
    }
}
