package com.lumenpearson.lessons.core.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled region catalog, read by the model the app reads it with.
 *
 * The file is the server's generator's output (`server/app/catalog/data/
 * regions.json`), put on this module's asset path and its test classpath in
 * place. A catalog that does not parse here would be a phone that cannot pick a
 * region or sign in anywhere, and nothing else would say so before a device.
 */
class RegionCatalogTest {

    private val text: String by lazy {
        javaClass.classLoader!!.getResourceAsStream(RegionCatalog.ASSET)!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Test
    fun `the bundled catalog parses whole`() {
        val catalog = RegionCatalog.parse(text)

        assertEquals(1, catalog.schema)
        assertEquals(89, catalog.regions.size)
        assertEquals(89, catalog.regions.map { it.key }.toSet().size)
        assertNotNull(catalog.survey.month)
        assertTrue(catalog.search.stopWords.isNotEmpty())
    }

    /** K16: names live in the JSON only, so the English twin is held here rather than by the resource test. */
    @Test
    fun `every region and platform has a name in both languages`() {
        val catalog = RegionCatalog.parse(text)
        for (region in catalog.regions) {
            assertTrue(region.key, region.nameRu.isNotBlank() && region.nameEn.isNotBlank())
        }
        for ((key, platform) in catalog.platforms) {
            assertTrue(key, platform.nameRu.isNotBlank() && platform.nameEn.isNotBlank())
        }
    }

    /** The generator decides; the phone reads. A recommendation pointing past the list would be a crash. */
    @Test
    fun `every recommendation points at a system the region has`() {
        val catalog = RegionCatalog.parse(text)
        for (region in catalog.regions) {
            val index = region.recommended?.system ?: continue
            assertTrue(region.key, index in region.systems.indices)
        }
    }

    @Test
    fun `a newer generator's keys are ignored, not fatal`() {
        val catalog = RegionCatalog.parse(
            """
            { "schema": 2, "later": {"x": 1},
              "regions": [ { "key": "samara", "name_ru": "Самарская область", "name_en": "Samara Oblast",
                             "zone": "Europe/Samara", "future": true,
                             "systems": [ { "platform": "netschool", "action": "signin", "new_field": 1,
                                            "netschool": { "region": "samara", "origin": "https://asurso.ru",
                                                           "password": true, "zone": "Europe/Samara" } } ] } ] }
            """.trimIndent(),
        )

        assertEquals("https://asurso.ru", catalog.region("samara")?.systems?.single()?.netschool?.origin)
        assertNull(catalog.region("tomsk"))
        assertNull(catalog.region(null))
    }
}
