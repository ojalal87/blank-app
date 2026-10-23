package com.bright.onepluscare.core.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Android-relevant indicators from the MVT indicators index (Amnesty International, Citizen Lab, Google,
 * Lookout, Echap and others; MIT / CC BY). Each value keeps the collection (campaign) it came from, so a
 * match can say "Pegasus" or "Predator" rather than just "bad".
 */
class SpywareIndicators private constructor(
    private val packages: Map<String, String>,
    private val certSha1: Map<String, String>,
    private val certSha256: Map<String, String>,
    private val sha256: Map<String, String>,
    private val domains: Map<String, String>,
) {
    enum class Kind { APP, CERT_SHA1, CERT_SHA256, SHA256, DOMAIN }
    data class Row(val kind: Kind, val value: String, val collection: String)

    val packageCount get() = packages.size
    val domainCount get() = domains.size
    val hashCount get() = sha256.size
    val collections: Set<String> get() = (packages.values + domains.values + sha256.values + certSha1.values).toSet()

    /** Suffix walk on DNS label boundaries: "a.b.evil.com" matches an indicator "evil.com". */
    fun matchDomain(host: String): String? {
        var h = host.lowercase().trimEnd('.')
        while (true) {
            domains[h]?.let { return it }
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return null // never match a bare TLD
            h = h.substring(dot + 1)
        }
    }

    /** The indicators as stalkerware-style IOC entries (one per collection) for the app scanner. */
    fun asIoc(): IocDatabase {
        val names = (packages.values + certSha1.values + certSha256.values).toSet()
        return IocDatabase(names.sorted().map { c ->
            IocEntry(
                c,
                packages.filterValues { it == c }.keys,
                certSha1.filterValues { it == c }.keys,
                certSha256.filterValues { it == c }.keys,
            )
        })
    }

    fun hashEntries(): List<HashEntry> = sha256.map { (h, c) -> HashEntry(h, HashSource.MVT, c) }

    fun toTsv(): String = buildString {
        fun dump(k: Kind, m: Map<String, String>) = m.toSortedMap().forEach { (v, c) -> append(k.name).append('\t').append(v).append('\t').append(c).append('\n') }
        dump(Kind.APP, packages); dump(Kind.CERT_SHA1, certSha1); dump(Kind.CERT_SHA256, certSha256); dump(Kind.SHA256, sha256); dump(Kind.DOMAIN, domains)
    }

    companion object {
        const val MAX_STIX_BYTES = 12 * 1024 * 1024
        const val MAX_TSV_BYTES = 16 * 1024 * 1024
        const val MIN_ROWS_FOR_UPDATE = 1000
        val EMPTY = of(emptyList())

        private val PACKAGE = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$")
        private val DOMAIN = Regex("^([a-z0-9_]([a-z0-9_-]{0,62})?\\.)+[a-z0-9-]{2,63}$")
        private val HEX40 = Regex("^[0-9a-f]{40}$")
        private val HEX64 = Regex("^[0-9a-f]{64}$")
        private val PATTERN = Regex("^\\[([a-z0-9:._-]+)\\s*=\\s*'([^']*)'\\]$")
        private val NAME = Regex("[^A-Za-z0-9 ()._+-]")

        private fun valid(kind: Kind, v: String): Boolean = when (kind) {
            Kind.APP -> v.length <= 200 && PACKAGE.matches(v)
            Kind.CERT_SHA1 -> HEX40.matches(v)
            Kind.CERT_SHA256, Kind.SHA256 -> HEX64.matches(v)
            Kind.DOMAIN -> v.length <= 253 && v.count { it == '.' } >= 1 && DOMAIN.matches(v)
        }

        fun cleanCollection(s: String) = s.replace(" Indicators of Compromise", "").replace(NAME, "").trim().take(60).ifEmpty { "unknown" }

        fun of(rows: Collection<Row>): SpywareIndicators {
            val m = Kind.entries.associateWith { HashMap<String, String>() }
            for (r in rows) {
                val v = r.value.trim().lowercase().removeSuffix(".")
                if (valid(r.kind, v)) m.getValue(r.kind).putIfAbsent(v, cleanCollection(r.collection))
            }
            return SpywareIndicators(m.getValue(Kind.APP), m.getValue(Kind.CERT_SHA1), m.getValue(Kind.CERT_SHA256), m.getValue(Kind.SHA256), m.getValue(Kind.DOMAIN))
        }

        fun parseTsv(text: String): SpywareIndicators {
            if (text.length > MAX_TSV_BYTES) return EMPTY
            return of(text.lineSequence().mapNotNull { l ->
                val p = l.split('\t')
                if (p.size != 3) null else runCatching { Row(Kind.valueOf(p[0]), p[1], p[2]) }.getOrNull()
            }.toList())
        }

        /** One STIX 2 bundle as published in the MVT index. Unknown indicator types are ignored. */
        fun parseStix(json: String, collection: String): List<Row> {
            if (json.length > MAX_STIX_BYTES) return emptyList()
            val objects = runCatching { Json.parseToJsonElement(json).jsonObject["objects"]!!.jsonArray }.getOrNull() ?: return emptyList()
            return objects.mapNotNull { el ->
                val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                if (o["type"]?.jsonPrimitive?.contentOrNull != "indicator") return@mapNotNull null
                val m = PATTERN.find(o["pattern"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null) ?: return@mapNotNull null
                val kind = when (m.groupValues[1]) {
                    "app:id" -> Kind.APP
                    "app:cert.sha1" -> Kind.CERT_SHA1
                    "app:cert.sha256" -> Kind.CERT_SHA256
                    "file:hashes.sha256" -> Kind.SHA256
                    "domain-name:value" -> Kind.DOMAIN
                    else -> return@mapNotNull null
                }
                Row(kind, m.groupValues[2], collection)
            }
        }

        /** Entries of the MVT `indicators.yaml` index that point at a STIX file in a GitHub repository. */
        data class IndexEntry(val name: String, val owner: String, val repo: String, val branch: String, val path: String) {
            val url: String get() = "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
        }

        private val SAFE_SEG = Regex("^[A-Za-z0-9_.-]{1,100}$")
        private val SAFE_PATH = Regex("^[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*\\.stix2$")

        /** Minimal reader for the index format; anything unexpected is skipped, and paths are validated. */
        fun parseIndex(yaml: String): List<IndexEntry> {
            if (yaml.length > 512 * 1024) return emptyList()
            val out = ArrayList<IndexEntry>()
            var cur = HashMap<String, String>()
            fun flush() {
                val e = IndexEntry(cur["name"] ?: "", cur["owner"] ?: "", cur["repo"] ?: "", cur["branch"] ?: "", cur["path"] ?: "")
                if (cur["type"] == "github" && e.name.isNotBlank() && listOf(e.owner, e.repo, e.branch).all { SAFE_SEG.matches(it) && it != ".." } &&
                    SAFE_PATH.matches(e.path) && !e.path.split('/').contains("..")
                ) out += e
                cur = HashMap()
            }
            for (raw in yaml.lineSequence()) {
                val line = raw.trim()
                if (line == "-") { if (cur.isNotEmpty()) flush(); continue }
                val k = line.substringBefore(':').trim()
                val v = line.substringAfter(':', "").trim()
                if (k in setOf("type", "name", "owner", "repo", "branch", "path") && v.isNotEmpty() && cur[k] == null) cur[k] = v
            }
            if (cur.isNotEmpty()) flush()
            return out
        }
    }
}
