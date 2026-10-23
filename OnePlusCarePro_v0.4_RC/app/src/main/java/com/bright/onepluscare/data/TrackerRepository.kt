package com.bright.onepluscare.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.bright.onepluscare.core.io.AtomicTextFile
import com.bright.onepluscare.core.trackers.DexClassNames
import com.bright.onepluscare.core.trackers.Tracker
import com.bright.onepluscare.core.trackers.TrackerDb
import com.bright.onepluscare.core.trackers.TrackerMatcher
import com.bright.onepluscare.firewall.AppNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.util.zip.ZipFile
import javax.net.ssl.HttpsURLConnection

data class AppTrackers(val packageName: String, val label: String, val trackers: List<Tracker>, val isSystem: Boolean)

data class TrackerReport(val apps: List<AppTrackers>, val scanned: Int, val unreadable: Int, val finishedAtMillis: Long)

/**
 * Finds tracker SDKs inside installed apps the way Exodus Privacy does: class names from the app's DEX
 * files matched against Exodus code signatures. Runs entirely on the phone (APK files are readable by any
 * app); results are cached per APK file (path, size, date) and never leave the device.
 */
class TrackerRepository(private val context: Context) {
    private val pm: PackageManager = context.packageManager
    private val file = File(context.filesDir, "exodus_trackers.tsv")
    private val cacheFile = File(context.noBackupFilesDir, "tracker_cache.json")
    @Volatile private var cached: List<Tracker>? = null

    suspend fun trackers(): List<Tracker> = withContext(Dispatchers.IO) {
        cached ?: run {
            val stored = runCatching { AtomicTextFile(file.toPath()).read() }.getOrNull()?.let(TrackerDb::parseTsv)?.takeIf { it.size >= TrackerDb.MIN_TRACKERS }
            stored ?: runCatching { context.assets.open(ASSET).use { TrackerDb.parseTsv(it.readBytes().toString(Charsets.UTF_8)) } }.getOrDefault(emptyList())
        }.also { cached = it }
    }

    fun updatedAtMillis(): Long? = file.takeIf { it.isFile }?.lastModified()

    suspend fun update(): IocUpdateResult = withContext(Dispatchers.IO) {
        val text = try {
            val u = URL(EXODUS_URL)
            val net = AppNetwork.forOwnTraffic(context)
            val conn = (net?.openConnection(u) ?: u.openConnection()) as HttpsURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            try {
                if (conn.responseCode != 200) return@withContext IocUpdateResult.NETWORK_ERROR
                conn.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > TrackerDb.MAX_BYTES) return@withContext IocUpdateResult.INVALID
                    }
                    out.toString(Charsets.UTF_8.name())
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext IocUpdateResult.NETWORK_ERROR
        }
        val list = TrackerDb.parseExodus(text)?.takeIf { it.size >= TrackerDb.MIN_TRACKERS } ?: return@withContext IocUpdateResult.INVALID
        AtomicTextFile(file.toPath()).write(TrackerDb.toTsv(list))
        cached = list
        runCatching { cacheFile.delete() } // signatures changed: results must be recomputed
        IocUpdateResult.UPDATED
    }

    private fun loadCache(): MutableMap<String, List<Int>> = runCatching {
        val o = JSONObject(cacheFile.readText())
        o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getInt(it) } } }.toMutableMap()
    }.getOrDefault(HashMap())

    private fun saveCache(m: Map<String, List<Int>>) {
        runCatching { cacheFile.writeText(JSONObject(m.mapValues { JSONArray(it.value) }).toString()) }
    }

    /** Tracker ids for one APK set, or null when the files cannot be read. */
    private suspend fun scanFiles(ai: ApplicationInfo, trackers: List<Tracker>, cache: MutableMap<String, List<Int>>): List<Int>? {
        val paths = listOfNotNull(ai.sourceDir) + ai.splitSourceDirs.orEmpty()
        val key = paths.joinToString(";") { p -> File(p).let { "$p|${it.length()}|${it.lastModified()}" } }
        cache[key]?.let { return it }
        val names = ArrayList<String>()
        var total = 0L
        for (p in paths) {
            val f = File(p)
            if (!f.canRead()) return null
            runCatching {
                ZipFile(f).use { zip ->
                    for (e in zip.entries()) {
                        currentCoroutineContext().ensureActive()
                        if (!(e.name.startsWith("classes") && e.name.endsWith(".dex")) || e.name.contains('/')) continue
                        if (e.size > MAX_DEX_BYTES) continue
                        total += e.size
                        if (total > MAX_TOTAL_DEX) break
                        val bytes = zip.getInputStream(e).use { it.readBytes() }
                        DexClassNames.read(bytes)?.let { names += it }
                    }
                }
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
        val ids = TrackerMatcher.match(names, trackers).map { it.id }
        cache[key] = ids
        return ids
    }

    suspend fun scanAll(includeSystem: Boolean, onProgress: (Int, Int) -> Unit): TrackerReport = withContext(Dispatchers.IO) {
        val trackers = trackers()
        val byId = trackers.associateBy { it.id }
        val apps = pm.getInstalledApplications(0).filter { it.packageName != context.packageName }
            .filter { includeSystem || it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0 }
        val cache = loadCache()
        var unreadable = 0
        val out = ArrayList<AppTrackers>()
        apps.forEachIndexed { i, ai ->
            currentCoroutineContext().ensureActive()
            onProgress(i, apps.size)
            val ids = scanFiles(ai, trackers, cache)
            if (ids == null) { unreadable++; return@forEachIndexed }
            val label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(ai.packageName)
            out += AppTrackers(ai.packageName, label, ids.mapNotNull(byId::get), ai.flags and ApplicationInfo.FLAG_SYSTEM != 0)
            if (i % 10 == 0) saveCache(cache)
        }
        saveCache(cache.entries.toList().takeLast(3_000).associate { it.key to it.value })
        onProgress(apps.size, apps.size)
        TrackerReport(out.sortedWith(compareByDescending<AppTrackers> { it.trackers.size }.thenBy { it.label.lowercase() }), apps.size, unreadable, System.currentTimeMillis())
    }

    companion object {
        const val ASSET = "exodus_trackers.tsv"
        const val EXODUS_URL = "https://reports.exodus-privacy.eu.org/api/trackers"
        private const val MAX_DEX_BYTES = 64L * 1024 * 1024
        private const val MAX_TOTAL_DEX = 256L * 1024 * 1024
    }
}
