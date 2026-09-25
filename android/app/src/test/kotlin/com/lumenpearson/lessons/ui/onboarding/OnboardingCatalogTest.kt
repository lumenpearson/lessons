package com.lumenpearson.lessons.ui.onboarding

import com.lumenpearson.lessons.core.data.catalog.MatchVia
import com.lumenpearson.lessons.core.data.repository.DiaryBinding
import com.lumenpearson.lessons.core.data.repository.DiaryProviderKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The region, school and provider steps against the catalog the APK carries.
 *
 * The screens read the generator's answers — `recommended`, `action`, `role` —
 * and only filter and name them (K18). These tests hold the filtering and the
 * few rules the phone adds: where a school step is shown, what can be signed
 * into, where a handoff goes, and that none of it names anything but https.
 */
class OnboardingCatalogTest {

    @Test
    fun `the school step is shown only where the signable diary lists its schools`() {
        val shown = realCatalog.regions.filter { regionPlanOf(it).showsSchool }.map { it.key }
        assertEquals(16, shown.size)
        assertTrue("samara" in shown)
        assertFalse("saint-petersburg" in shown)
        assertFalse("tula" in shown)
        assertEquals(realCatalog.regions.size - 16, realCatalog.regions.count { !regionPlanOf(it).showsSchool })
    }

    @Test
    fun `only Petersburg and the sixteen password regions can be signed into`() {
        val signable = realCatalog.regions.filter { regionPlanOf(it).signable }.map { it.key }
        assertEquals(17, signable.size)
        assertTrue("saint-petersburg" in signable)
    }

    @Test
    fun `no region has more than one signable diary`() {
        for (entry in realCatalog.regions) {
            assertTrue(entry.key, entry.systems.count(::isSignable) <= 1)
        }
    }

    @Test
    fun `at most one row is recommended, and every page builds`() {
        for (entry in realCatalog.regions) {
            val page = providerPageOf(realCatalog, entry, school = null)
            assertTrue(entry.key, page.rows.count { it.recommended } <= 1)
            assertTrue(entry.key, page.rows.isNotEmpty())
        }
    }

    @Test
    fun `TOR is never offered where the catalog says the region is not on it`() {
        for (entry in realCatalog.regions.filter { it.tor == "absent" }) {
            val page = providerPageOf(realCatalog, entry, school = null)
            assertTrue(entry.key, page.rows.none { it.platform == "tor-myschool" && !it.recommended })
        }
    }

    @Test
    fun `legacy, unclear, portal and admissions rows are dropped`() {
        for (entry in realCatalog.regions) {
            val page = providerPageOf(realCatalog, entry, school = null)
            for (row in page.rows.filterNot { it.recommended }) {
                assertTrue("${entry.key}/${row.platform}", row.role in setOf("primary", "secondary", "migrating_to"))
                val kind = realCatalog.platforms[row.platform]?.kind
                assertFalse("${entry.key}/${row.platform}", kind == "portal" || kind == "admissions")
            }
        }
    }

    @Test
    fun `every link a row opens is https`() {
        for (entry in realCatalog.regions) {
            for (row in providerPageOf(realCatalog, entry, school = null).rows) {
                row.url?.let { assertTrue("${entry.key}: $it", it.startsWith("https://")) }
                if (row.kind == ProviderRowKind.HANDOFF || row.kind == ProviderRowKind.SITE) {
                    assertNotNull("${entry.key}/${row.platform}", row.url)
                }
            }
        }
    }

    @Test
    fun `the three Gosuslugi-only regions hand off to their own origin`() {
        for (key in listOf("tula", "primorye", "altai-krai")) {
            val row = providerPageOf(realCatalog, region(key), school = null).rows.single { it.platform == "netschool" }
            assertEquals(key, ProviderRowKind.HANDOFF, row.kind)
            assertTrue(key, row.gosuslugi)
            assertFalse(key, row.url.orEmpty().contains("gosuslugi.ru"))
        }
        val tula = providerPageOf(realCatalog, region("tula"), school = null).rows.single { it.platform == "netschool" }
        assertEquals("https://sgo1.edu71.ru", tula.url)
    }

    @Test
    fun `TOR rows hand off to the Gosuslugi school page`() {
        val row = providerPageOf(realCatalog, region("volgograd"), school = null).rows.single { it.platform == "tor-myschool" }
        assertEquals(ProviderRowKind.HANDOFF, row.kind)
        assertEquals("https://www.gosuslugi.ru/school", row.url)
        assertTrue(row.gosuslugi)
    }

    @Test
    fun `the main action is the recommended row's`() {
        val samara = region("samara")
        val withoutSchool = providerPageOf(realCatalog, samara, school = null)
        assertEquals(ProviderRowKind.NEEDS_SCHOOL, withoutSchool.main?.kind)
        val withSchool = providerPageOf(realCatalog, samara, school = PickedSchool(1, "School 1"))
        assertEquals(ProviderRowKind.SIGN_IN, withSchool.main?.kind)
        assertEquals("asurso.ru", withSchool.main?.host)

        val petersburg = providerPageOf(realCatalog, region("saint-petersburg"), school = null)
        assertEquals(ProviderRowKind.SIGN_IN, petersburg.main?.kind)
        assertEquals("dnevnik2.petersburgedu.ru", petersburg.main?.host)

        // Moscow's diary is one the app cannot read; its site is what the
        // main button can open.
        val moscow = providerPageOf(realCatalog, region("moscow"), school = null).main
        assertEquals(ProviderRowKind.SITE, moscow?.kind)
        assertEquals("https://school.mos.ru", moscow?.url)

        // Paper registers leave nothing to open: the main button is the class code.
        assertNull(providerPageOf(realCatalog, region("sevastopol"), school = null).main)
    }

    @Test
    fun `every reason and modifier the catalog uses has a sentence`() {
        for (entry in realCatalog.regions) {
            val recommendation = entry.recommended ?: continue
            assertTrue(entry.key, WhyReason.entries.any { it.code == recommendation.reason })
            for (modifier in recommendation.modifiers) {
                assertTrue("${entry.key}: $modifier", WhyModifier.entries.any { it.code == modifier })
            }
        }
    }

    @Test
    fun `the why names a host only where the password could go`() {
        assertEquals("asurso.ru", providerPageOf(realCatalog, region("samara"), null).why?.host)
        assertNull(providerPageOf(realCatalog, region("tula"), null).why?.host)
        assertNull(providerPageOf(realCatalog, region("moscow"), null).why?.host)
    }

    @Test
    fun `a class's diary is a target only where this build can sign in`() {
        val petersburg = classTargetOf(realCatalog, DiaryBinding(DiaryProviderKey.PETERSBURG, null, null, null))
        assertEquals(DiaryProviderKey.PETERSBURG, petersburg?.provider)

        val samara = classTargetOf(realCatalog, DiaryBinding(DiaryProviderKey.NETSCHOOL, "samara", 12L, "School 12"))
        assertEquals("Europe/Samara", samara?.zone)
        assertEquals(12L, samara?.schoolId)

        assertNull(classTargetOf(realCatalog, DiaryBinding(DiaryProviderKey.NETSCHOOL, "tula", 12L, "School 12")))
        assertNull(classTargetOf(realCatalog, DiaryBinding(DiaryProviderKey.NETSCHOOL, "samara", null, null)))
        assertNull(classTargetOf(realCatalog, null))
    }

    @Test
    fun `a region row says why it matched and what can be done there`() {
        val byCity = realSearch.search("Togliatti").ifEmpty { realSearch.search("tolyatti") }
        val samaraMatch = realSearch.search("63").first { it.region.key == "samara" }
        val row = regionRowOf(realCatalog, samaraMatch)
        assertEquals(MatchVia.CODE, row.via)
        assertEquals("63", row.matched)
        assertEquals(OfferKind.SIGN_IN, row.offer.kind)
        byCity.firstOrNull { it.via == MatchVia.CITY }?.let { match ->
            assertTrue(regionRowOf(realCatalog, match).matched in match.region.cities)
        }

        assertEquals(OfferKind.GOSUSLUGI, regionOfferOf(realCatalog, region("tula")).kind)
        assertEquals(OfferKind.PAPER, regionOfferOf(realCatalog, region("sevastopol")).kind)
        assertEquals(OfferKind.UNSUPPORTED, regionOfferOf(realCatalog, region("moscow")).kind)
    }

    @Test
    fun `the school query keeps the number, else the longest distinctive word`() {
        val words = realCatalog.search.schoolWords
        assertEquals("239", schoolQueryFromHint("GBOU lyceum N 239", words))
        assertEquals("", schoolQueryFromHint(null, words))
        assertEquals("", schoolQueryFromHint("   ", words))
        // Words every school has are not what tells one school from another.
        val typed = words.first() + " Lomonosova"
        assertEquals("Lomonosova", schoolQueryFromHint(typed, words))
    }
}
