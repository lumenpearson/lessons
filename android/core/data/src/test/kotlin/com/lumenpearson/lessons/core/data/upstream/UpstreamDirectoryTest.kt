package com.lumenpearson.lessons.core.data.upstream

import com.lumenpearson.lessons.core.data.catalog.CatalogNetSchool
import com.lumenpearson.lessons.core.data.catalog.CatalogPlatform
import com.lumenpearson.lessons.core.data.catalog.CatalogRegion
import com.lumenpearson.lessons.core.data.catalog.CatalogSystem
import com.lumenpearson.lessons.core.data.catalog.RegionCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The allow-list, as the phone builds it from the bundled catalog — the only
 * place a host the phone sends a password to can come from.
 */
class UpstreamDirectoryTest {

    private val bundled: RegionCatalog by lazy {
        RegionCatalog.parse(
            javaClass.classLoader!!.getResourceAsStream(RegionCatalog.ASSET)!!
                .bufferedReader(Charsets.UTF_8).use { it.readText() },
        )
    }

    @Test
    fun `the directory refuses an http, path-carrying or credentialed origin`() {
        for (refused in listOf(
            "http://sgo.example.ru",
            "https://sgo.example.ru/webapi",
            "https://sgo.example.ru/",
            "https://sgo.example.ru?x=1",
            "https://user:pass@sgo.example.ru",
            "https://sgo.example.ru#top",
            "ftp://sgo.example.ru",
            "not a url",
        )) {
            assertNull(refused, CatalogUpstreamDirectory.originOrNull(refused))
        }
        assertNotNull(CatalogUpstreamDirectory.originOrNull("https://sgo.example.ru"))
    }

    @Test
    fun `a catalog row with an unusable origin never reaches the allow-list`() {
        val catalog = RegionCatalog(
            regions = listOf(
                region("good", "https://good.example.ru"),
                region("plain", "http://plain.example.ru"),
                region("pathy", "https://pathy.example.ru/webapi"),
            ),
        )

        val directory = CatalogUpstreamDirectory(catalog)

        assertNotNull(directory.netschool("good"))
        assertNull(directory.netschool("plain"))
        assertNull(directory.netschool("pathy"))
        assertEquals(setOf(UpstreamOrigin("https", "good.example.ru", 443)), directory.allowedOrigins())
    }

    /**
     * The bundled file, read the way the app reads it: every «Сетевой город»
     * region the server's allow-list has, on `https`, and nothing else.
     * `regions.py` has nineteen rows, three of them Госуслуги-only.
     */
    @Test
    fun `the bundled catalog yields the server's allow-list and only that`() {
        val directory = CatalogUpstreamDirectory(bundled)
        val netschool = bundled.regions.flatMap { it.systems }.mapNotNull { it.netschool }

        assertEquals(19, netschool.map { it.region }.toSet().size)
        for (row in netschool) {
            val region = directory.netschool(row.region)
            assertNotNull(row.region, region)
            assertEquals(row.region, "https", region!!.origin.scheme)
            assertEquals(row.region, row.password, region.password)
            assertEquals(row.region, row.zone, region.zone)
        }
        assertEquals(setOf("altai-krai", "primorye", "tula"), netschool.filterNot { it.password }.map { it.region }.toSet())
        // Nineteen regions and Petersburg, and no other origin at all.
        assertEquals(20, directory.allowedOrigins().size)
        assertTrue(directory.allowedOrigins().all { it.scheme == "https" })
    }

    @Test
    fun `the Sakhalin origin keeps its port`() {
        val sakhalin = CatalogUpstreamDirectory(bundled).netschool("sakhalin")!!
        assertEquals(11111, sakhalin.origin.port)
        assertTrue(UpstreamOrigin("https", "netcity.admsakhalin.ru", 11111) in CatalogUpstreamDirectory(bundled).allowedOrigins())
    }

    /**
     * K19: a «Сетевой город» region that takes Госуслуги only hands off to its
     * own site, which is what the catalog says — not to a Госуслуги page the
     * app guessed.
     */
    @Test
    fun `a Gosuslugi-only region hands off to the catalog's own address`() {
        val directory = CatalogUpstreamDirectory(bundled)
        for (key in listOf("altai-krai", "primorye", "tula")) {
            val region = directory.netschool(key)!!
            assertFalse(key, region.password)
            assertEquals(key, region.origin.toString().trimEnd('/'), region.handoffUrl?.trimEnd('/'))
        }
    }

    @Test
    fun `Petersburg comes from the catalog's platform row`() {
        assertEquals("dnevnik2.petersburgedu.ru", CatalogUpstreamDirectory(bundled).petersburg?.host)
        val none = CatalogUpstreamDirectory(RegionCatalog(platforms = mapOf("x" to CatalogPlatform(provider = "netschool"))))
        assertNull(none.petersburg)
    }

    private fun region(key: String, origin: String) = CatalogRegion(
        key = key,
        zone = "Europe/Moscow",
        systems = listOf(
            CatalogSystem(
                platform = "netschool",
                action = "signin",
                netschool = CatalogNetSchool(region = key, origin = origin, password = true, zone = "Europe/Moscow"),
            ),
        ),
    )
}
