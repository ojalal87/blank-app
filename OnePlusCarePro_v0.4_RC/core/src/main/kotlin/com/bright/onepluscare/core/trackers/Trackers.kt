package com.bright.onepluscare.core.trackers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One tracker from the Exodus Privacy database (ODbL). [codeSignature] is a regex over Java class names. */
data class Tracker(val id: Int, val name: String, val categories: List<String>, val codeSignature: String) {
    val regex: Regex? by lazy { runCatching { Regex(codeSignature) }.getOrNull() }
    /** Signatures that name classes (uppercase letters) must be tested against class names, not packages. */
    val classLevel: Boolean get() = codeSignature.any { it.isUpperCase() }
}

object TrackerDb {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MIN_TRACKERS = 100
    private val NAME = Regex("[\\t\\n\\r]")

    /** Exodus API `/api/trackers` response: {"trackers": {"<id>": {id, name, code_signature, categories, ...}}}. */
    fun parseExodus(json: String): List<Tracker>? {
        if (json.length > MAX_BYTES) return null
        return runCatching {
            val t = Json.parseToJsonElement(json).jsonObject["trackers"]!!.jsonObject
            t.values.mapNotNull { el ->
                val o = el.jsonObject
                val sig = o["code_signature"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (sig.isEmpty() || sig.length > 2000) return@mapNotNull null
                Tracker(
                    o["id"]!!.jsonPrimitive.int,
                    (o["name"]?.jsonPrimitive?.contentOrNull ?: "?").replace(NAME, " ").take(80),
                    o["categories"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.replace(NAME, " ")?.take(40) }.orEmpty(),
                    sig,
                ).takeIf { it.regex != null }
            }.sortedBy { it.id }
        }.getOrNull()
    }

    /** Compact stored form: id TAB name TAB categories(comma) TAB code_signature. */
    fun toTsv(list: List<Tracker>): String = list.joinToString("\n") { "${it.id}\t${it.name}\t${it.categories.joinToString(",") { c -> c.replace(",", " ") }}\t${it.codeSignature}" }

    fun parseTsv(text: String): List<Tracker> = text.lineSequence().mapNotNull { l ->
        val p = l.split('\t')
        if (p.size != 4) null else p[0].toIntOrNull()?.let { id -> Tracker(id, p[1], p[2].split(',').filter { it.isNotBlank() }, p[3]).takeIf { it.regex != null } }
    }.toList()
}

/**
 * Minimal DEX reader: returns the class names (dotted) of the types a DEX file defines or references.
 * Only the header, string_ids and type_ids sections are read, with bounds checks on every offset.
 */
object DexClassNames {
    fun read(dex: ByteArray): List<String>? {
        if (dex.size < 0x70 || dex[0] != 'd'.code.toByte() || dex[1] != 'e'.code.toByte() || dex[2] != 'x'.code.toByte() || dex[3] != '\n'.code.toByte()) return null
        fun u32(off: Int): Int? = if (off < 0 || off + 4 > dex.size) null else
            (dex[off].toInt() and 0xff) or ((dex[off + 1].toInt() and 0xff) shl 8) or ((dex[off + 2].toInt() and 0xff) shl 16) or ((dex[off + 3].toInt() and 0xff) shl 24)
        val stringIdsSize = u32(0x38) ?: return null
        val stringIdsOff = u32(0x3C) ?: return null
        val typeIdsSize = u32(0x40) ?: return null
        val typeIdsOff = u32(0x44) ?: return null
        if (stringIdsSize < 0 || typeIdsSize < 0 || typeIdsSize > 1_000_000) return null
        val out = ArrayList<String>(typeIdsSize)
        for (i in 0 until typeIdsSize) {
            val strIdx = u32(typeIdsOff + 4 * i) ?: return null
            if (strIdx < 0 || strIdx >= stringIdsSize) return null
            val dataOff = u32(stringIdsOff + 4 * strIdx) ?: return null
            var p = dataOff
            // uleb128 length (in UTF-16 units), then MUTF-8 bytes terminated by 0.
            var shift = 0
            while (true) {
                if (p >= dex.size || shift > 28) return null
                val b = dex[p++].toInt() and 0xff
                if (b and 0x80 == 0) break
                shift += 7
            }
            val start = p
            while (p < dex.size && dex[p].toInt() != 0) p++
            if (p >= dex.size || p - start > 1024) continue
            if (dex[start] != 'L'.code.toByte() || dex[p - 1] != ';'.code.toByte()) continue
            out += String(dex, start + 1, p - start - 2, Charsets.UTF_8).replace('/', '.')
        }
        return out
    }
}

object TrackerMatcher {
    /** Exodus semantics: a tracker is present when its signature regex is found in a class name. */
    fun match(classNames: Collection<String>, trackers: List<Tracker>): List<Tracker> {
        if (classNames.isEmpty()) return emptyList()
        val packages = HashSet<String>()
        for (c in classNames) {
            val dot = c.lastIndexOf('.')
            if (dot > 0) packages += c.substring(0, dot + 1)
        }
        return trackers.filter { t ->
            val r = t.regex ?: return@filter false
            if (t.classLevel) classNames.any { r.containsMatchIn(it) } else packages.any { r.containsMatchIn(it) }
        }
    }
}
