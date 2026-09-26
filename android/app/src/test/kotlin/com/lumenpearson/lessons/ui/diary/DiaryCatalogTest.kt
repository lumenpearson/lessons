package com.lumenpearson.lessons.ui.diary

import com.lumenpearson.lessons.core.data.catalog.RegionCatalog
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the screens read out of the bundled catalog — against the real file,
 * `server/app/catalog/data/regions.json`, the one the APK carries.
 *
 * The host is the part that matters: the sign-in form names the one host the
 * password will go to, the privacy policy promises that it does, and a host
 * read differently from how the sign-in's allow-list reads it would be a
 * promise about a server the password never reaches.
 */
class DiaryCatalogTest {

    private val catalog: RegionCatalog by lazy { RegionCatalog.parse(catalogFile().readText()) }

    @Test
    fun `the form names Petersburg's diary host`() {
        val place = diaryPlaceOf(catalog, DiaryTarget.petersburg("parent@example.com"))
        assertEquals("dnevnik2.petersburgedu.ru", place.host)
        assertEquals("Санкт-Петербург", place.regionRu)
        assertEquals("Петербургское образование", place.systemRu)
    }

    @Test
    fun `the form names a «Сетевой город» region's own host`() {
        val target = DiaryTarget.netschool("samara", 1, "Школа № 1", "ivanova", "Europe/Samara")
        val place = diaryPlaceOf(catalog, target)
        assertEquals("asurso.ru", place.host)
        assertEquals("Samara Oblast", place.regionEn)
    }

    @Test
    fun `a region the catalog does not know names no host`() {
        val target = DiaryTarget.netschool("atlantis", 1, null, "x", "Europe/Moscow")
        assertNull(diaryPlaceOf(catalog, target).host)
    }

    /** The allow-list's own rule: https, and nothing after the authority. */
    @Test
    fun `only an origin the allow-list would keep names a host`() {
        assertEquals("asurso.ru", httpsHostOf("https://asurso.ru"))
        assertEquals("sakh.example:11111", httpsHostOf("https://sakh.example:11111"))
        assertNull(httpsHostOf("http://asurso.ru"))
        assertNull(httpsHostOf("https://asurso.ru/"))
        assertNull(httpsHostOf("https://asurso.ru/login"))
        assertNull(httpsHostOf("https://user@asurso.ru"))
        assertNull(httpsHostOf("https://asurso.ru?x=1"))
        assertNull(httpsHostOf("not a url"))
    }

    @Test
    fun `what each kind of region offers`() {
        assertEquals(DiaryRegionChoice.Petersburg, diaryChoiceOf(region("saint-petersburg")))
        assertEquals(DiaryRegionChoice.NetSchool("samara", "Europe/Samara"), diaryChoiceOf(region("samara")))

        // Госуслуги only: the browser, to the region's own site, never a form.
        val tula = diaryChoiceOf(region("tula"))
        assertTrue("$tula", tula is DiaryRegionChoice.Elsewhere && tula.gosuslugi)
        assertEquals("https://sgo1.edu71.ru", (tula as DiaryRegionChoice.Elsewhere).url)

        // A system this app does not speak: not a form either.
        assertTrue(diaryChoiceOf(region("moscow")) is DiaryRegionChoice.Elsewhere)
    }

    /**
     * Exactly the regions the sign-in can serve open a form — the design's
     * count, one Petersburg and sixteen «Сетевой город» regions that take a
     * password — so the picker never offers a form the sign-in would refuse.
     */
    @Test
    fun `the regions that open a form are exactly the signable ones`() {
        val choices = catalog.regions.map(::diaryChoiceOf)
        assertEquals(1, choices.count { it == DiaryRegionChoice.Petersburg })
        assertEquals(16, choices.count { it is DiaryRegionChoice.NetSchool })
    }

    private fun region(key: String) = checkNotNull(catalog.region(key)) { "no region $key" }

    private fun catalogFile(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val file = File(directory, "server/app/catalog/data/regions.json")
            if (file.isFile) return file
            directory = directory.parentFile
        }
        error("regions.json not found from ${File("").absolutePath}")
    }
}
