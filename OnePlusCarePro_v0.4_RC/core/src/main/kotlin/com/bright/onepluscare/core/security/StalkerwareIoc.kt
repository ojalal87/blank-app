package com.bright.onepluscare.core.security

import java.util.Locale

/** One stalkerware family from the Echap indicator list (https://github.com/AssoEchap/stalkerware-indicators, CC-BY 4.0). */
data class IocEntry(val name: String, val packages: Set<String>, val certificates: Set<String>, val certificatesSha256: Set<String> = emptySet())

class IocDatabase(val entries: List<IocEntry>) {
    private val byPackage: Map<String, IocEntry> = buildMap { this@IocDatabase.entries.forEach { e -> e.packages.forEach { put(it, e) } } }
    private val byCert: Map<String, IocEntry> = buildMap { this@IocDatabase.entries.forEach { e -> e.certificates.forEach { put(it, e) } } }
    private val byCert256: Map<String, IocEntry> = buildMap { this@IocDatabase.entries.forEach { e -> e.certificatesSha256.forEach { put(it.uppercase(), e) } } }

    val packageCount: Int get() = byPackage.size
    val certificateCount: Int get() = byCert.size

    /** Matches by exact package name or by signing-certificate SHA-1 (uppercase hex, no colons). */
    fun match(pkg: String, certSha1s: Collection<String>, certSha256s: Collection<String> = emptyList()): IocEntry? =
        byPackage[pkg] ?: certSha1s.firstNotNullOfOrNull { byCert[normalizeCert(it)] } ?: certSha256s.firstNotNullOfOrNull { byCert256[normalizeCert(it)] }

    operator fun plus(other: IocDatabase): IocDatabase = IocDatabase(entries + other.entries)

    companion object {
        val EMPTY = IocDatabase(emptyList())
        /** An update is accepted only if it looks like the real list (protects against truncated/hijacked downloads). */
        const val MIN_ENTRIES_FOR_UPDATE = 50
        const val MAX_BYTES = 2_000_000

        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        private val SHA1 = Regex("^[0-9A-F]{40}$")

        fun normalizeCert(raw: String): String = raw.replace(":", "").trim().uppercase(Locale.ROOT)

        /**
         * Minimal parser for the list's fixed YAML shape:
         *   - name: X
         *     packages:
         *     - com.example
         *     certificates:
         *     - 40-HEX
         * Unknown keys are skipped; malformed values are dropped, never executed or interpreted.
         */
        fun parse(yaml: String): IocDatabase {
            if (yaml.length > MAX_BYTES) return EMPTY
            val entries = ArrayList<IocEntry>()
            var name: String? = null
            var pkgs = LinkedHashSet<String>()
            var certs = LinkedHashSet<String>()
            var list: String? = null
            fun flush() {
                val n = name ?: return
                if (pkgs.isNotEmpty() || certs.isNotEmpty()) entries += IocEntry(n, pkgs, certs)
            }
            for (rawLine in yaml.lineSequence()) {
                val line = rawLine.trimEnd()
                when {
                    line.startsWith("- name:") -> {
                        flush()
                        name = line.removePrefix("- name:").trim().trim('"', '\'').take(120)
                        pkgs = LinkedHashSet(); certs = LinkedHashSet(); list = null
                    }
                    line.startsWith("  ") && !line.startsWith("  -") && line.trim().endsWith(":") -> list = line.trim().removeSuffix(":")
                    line.startsWith("  ") && !line.startsWith("  -") && line.contains(':') -> list = null
                    line.startsWith("  - ") -> {
                        val v = line.removePrefix("  - ").trim().trim('"', '\'')
                        when (list) {
                            "packages" -> if (PACKAGE.matches(v) && v.length <= 200) pkgs += v
                            "certificates" -> normalizeCert(v).takeIf { SHA1.matches(it) }?.let { certs += it }
                        }
                    }
                }
            }
            flush()
            return IocDatabase(entries)
        }
    }
}
