package com.bright.onepluscare.data

import android.content.Context
import com.bright.onepluscare.core.io.AtomicTextFile
import com.bright.onepluscare.core.security.SpywareIndicators
import com.bright.onepluscare.firewall.AppNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Spyware indicators from the MVT index (bundled snapshot, refreshed only when the user taps Update).
 * Downloads go only to raw.githubusercontent.com, with validated paths and size caps.
 */
class IndicatorsRepository(private val context: Context) {
    private val file = File(context.filesDir, "spyware_indicators.tsv")
    @Volatile private var cached: SpywareIndicators? = null

    suspend fun indicators(): SpywareIndicators = withContext(Dispatchers.IO) { cached ?: loadSync().also { cached = it } }

    /** Blocking load for the DNS filter thread. */
    fun loadSync(): SpywareIndicators {
        cached?.let { return it }
        val stored = runCatching { AtomicTextFile(file.toPath()).read() }.getOrNull()?.let(SpywareIndicators::parseTsv)
        val r = stored?.takeIf { it.domainCount + it.packageCount > 0 }
            ?: runCatching { context.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()?.let(SpywareIndicators::parseTsv)
            ?: SpywareIndicators.EMPTY
        cached = r
        return r
    }

    fun updatedAtMillis(): Long? = file.takeIf { it.isFile }?.lastModified()

    suspend fun update(): IocUpdateResult = withContext(Dispatchers.IO) {
        val index = fetch(INDEX_URL, 512 * 1024) ?: return@withContext IocUpdateResult.NETWORK_ERROR
        val entries = SpywareIndicators.parseIndex(index)
        if (entries.isEmpty()) return@withContext IocUpdateResult.INVALID
        val rows = ArrayList<SpywareIndicators.Row>()
        var total = 0L
        for (e in entries) {
            currentCoroutineContext().ensureActive()
            val text = fetch(e.url, SpywareIndicators.MAX_STIX_BYTES) ?: continue
            total += text.length
            if (total > 60L * 1024 * 1024) break
            rows += SpywareIndicators.parseStix(text, e.name)
        }
        if (rows.size < SpywareIndicators.MIN_ROWS_FOR_UPDATE) return@withContext IocUpdateResult.INVALID
        val ind = SpywareIndicators.of(rows)
        AtomicTextFile(file.toPath()).write(ind.toTsv())
        cached = ind
        IocUpdateResult.UPDATED
    }

    private fun fetch(url: String, max: Int): String? = try {
        val u = URL(url)
        require(u.protocol == "https" && u.host == "raw.githubusercontent.com")
        val net = AppNetwork.forOwnTraffic(context)
        val conn = (net?.openConnection(u) ?: u.openConnection()) as HttpsURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = false
        try {
            if (conn.responseCode != 200) null else conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > max) return null
                }
                out.toString(Charsets.UTF_8.name())
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val ASSET = "spyware_indicators.tsv"
        const val INDEX_URL = "https://raw.githubusercontent.com/mvt-project/mvt-indicators/main/indicators.yaml"
    }
}
