package com.bright.onepluscare.core

import com.bright.onepluscare.core.security.SpywareIndicators
import com.bright.onepluscare.core.trackers.DexClassNames
import com.bright.onepluscare.core.trackers.TrackerDb
import com.bright.onepluscare.core.trackers.TrackerMatcher
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpywareIndicatorsTest {
    private val stix = """{"type":"bundle","objects":[
      {"type":"malware","name":"Predator"},
      {"type":"indicator","pattern":"[domain-name:value = 'evil-c2.example']"},
      {"type":"indicator","pattern":"[app:id = 'com.spy.predator']"},
      {"type":"indicator","pattern":"[app:cert.sha256 = '${"a".repeat(64)}']"},
      {"type":"indicator","pattern":"[file:hashes.sha256 = '${"b".repeat(64)}']"},
      {"type":"indicator","pattern":"[process:name = 'bh']"},
      {"type":"indicator","pattern":"[domain-name:value = 'com']"},
      {"type":"indicator","pattern":"[app:id = 'Bad Package;rm']"}
    ]}"""

    @Test
    fun `stix parsing keeps android types and validates values`() {
        val ind = SpywareIndicators.of(SpywareIndicators.parseStix(stix, "Predator Spyware Indicators of Compromise"))
        assertEquals(1, ind.packageCount)
        assertEquals(1, ind.domainCount)
        assertEquals(1, ind.hashCount)
        assertEquals("Predator Spyware", ind.matchDomain("cdn.EVIL-C2.example."))
        assertNull(ind.matchDomain("notevil-c2.example"))
        assertEquals("Predator Spyware", ind.asIoc().match("com.spy.predator", emptyList())?.name)
        assertEquals("Predator Spyware", ind.asIoc().match("x.y", emptyList(), listOf("A".repeat(64)))?.name)
        assertEquals(1, ind.hashEntries().size)
        val back = SpywareIndicators.parseTsv(ind.toTsv())
        assertEquals(ind.toTsv(), back.toTsv())
    }

    @Test
    fun `bundled snapshot is plausible`() {
        val ind = SpywareIndicators.parseTsv(File("../app/src/main/assets/spyware_indicators.tsv").readText())
        assertTrue(ind.domainCount > 5000 && ind.packageCount > 500 && ind.hashCount > 3000, "${ind.domainCount} ${ind.packageCount} ${ind.hashCount}")
        assertNotNull(ind.matchDomain("123tramites.com"))
        assertTrue(ind.collections.any { it.contains("Pegasus") })
    }

    @Test
    fun `index parsing rejects path tricks`() {
        val y = """
            indicators:
              -
                type: github
                name: Good
                github:
                  owner: mvt-project
                  repo: mvt-indicators
                  branch: main
                  path: a/b.stix2
              -
                type: github
                name: Bad
                github:
                  owner: mvt-project
                  repo: mvt-indicators
                  branch: main
                  path: ../../etc/passwd
        """.trimIndent()
        val e = SpywareIndicators.parseIndex(y)
        assertEquals(listOf("Good"), e.map { it.name })
        assertEquals("https://raw.githubusercontent.com/mvt-project/mvt-indicators/main/a/b.stix2", e[0].url)
    }
}

class TrackersTest {
    /** Builds a minimal valid-enough DEX containing the given type descriptors. */
    private fun dex(descriptors: List<String>): ByteArray {
        val header = ByteArray(0x70)
        "dex\n035\u0000".toByteArray().copyInto(header)
        val stringIdsOff = 0x70
        val typeIdsOff = stringIdsOff + 4 * descriptors.size
        var dataOff = typeIdsOff + 4 * descriptors.size
        val ids = ByteArrayOutputStream(); val types = ByteArrayOutputStream(); val data = ByteArrayOutputStream()
        fun le(o: ByteArrayOutputStream, v: Int) { o.write(v and 0xff); o.write((v shr 8) and 0xff); o.write((v shr 16) and 0xff); o.write((v shr 24) and 0xff) }
        descriptors.forEachIndexed { i, d ->
            le(ids, dataOff); le(types, i)
            val b = d.toByteArray()
            data.write(b.size); data.write(b); data.write(0)
            dataOff += 1 + b.size + 1
        }
        fun put(off: Int, v: Int) { header[off] = v.toByte(); header[off + 1] = (v shr 8).toByte(); header[off + 2] = (v shr 16).toByte(); header[off + 3] = (v shr 24).toByte() }
        put(0x38, descriptors.size); put(0x3C, stringIdsOff); put(0x40, descriptors.size); put(0x44, typeIdsOff)
        return header + ids.toByteArray() + types.toByteArray() + data.toByteArray()
    }

    private val exodus = File("src/test/resources/exodus_trackers_sample.json").readText()

    @Test
    fun `dex reader extracts class names`() {
        val names = DexClassNames.read(dex(listOf("Lcom/facebook/ads/AdView;", "I", "[Ljava/lang/String;", "Lcom/example/Main;")))!!
        assertEquals(listOf("com.facebook.ads.AdView", "com.example.Main"), names)
        assertNull(DexClassNames.read(ByteArray(10)))
        val broken = dex(listOf("Lcom/a/B;")).also { it[0x44] = 0x7f }
        assertNull(DexClassNames.read(broken))
    }

    @Test
    fun `exodus json parse and matching`() {
        val trackers = TrackerDb.parseExodus(exodus)!!
        assertEquals(3, trackers.size)
        val found = TrackerMatcher.match(listOf("com.facebook.ads.AdView", "com.example.Main", "com.google.firebase.analytics.FirebaseAnalytics"), trackers)
        assertEquals(setOf("Facebook Ads", "Google Firebase Analytics"), found.map { it.name }.toSet())
        assertTrue(TrackerMatcher.match(listOf("com.example.Main"), trackers).isEmpty())
        assertEquals(trackers.map { it.name }, TrackerDb.parseTsv(TrackerDb.toTsv(trackers)).map { it.name })
        assertNull(TrackerDb.parseExodus("{}"))
    }

    @Test
    fun `bundled tracker list is plausible`() {
        assertTrue(TrackerDb.parseTsv(File("../app/src/main/assets/exodus_trackers.tsv").readText()).size > 300)
    }
}
