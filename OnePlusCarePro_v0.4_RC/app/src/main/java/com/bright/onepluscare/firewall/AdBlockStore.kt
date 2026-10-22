package com.bright.onepluscare.firewall

import android.content.Context
import com.bright.onepluscare.core.dns.Blocklist
import com.bright.onepluscare.core.dns.DnsPolicy
import com.bright.onepluscare.core.io.AtomicTextFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class AdBlockStatus(
    val running: Boolean = false,
    val queries: Int = 0,
    val blocked: Int = 0,
    /** Most-blocked hosts this session (in memory only, cleared when the filter stops). */
    val topBlocked: List<Pair<String, Int>> = emptyList(),
    val noUpstream: Boolean = false,
)

data class ListInfo(val entries: Int, val updatedAtMillis: Long?)

enum class ListUpdateResult { UPDATED, NETWORK_ERROR, INVALID }

/**
 * Ad/tracker host list and user exceptions. The list is downloaded only when the user taps Update
 * (StevenBlack/hosts, MIT licence) and kept in files/ (not backed up). Exceptions live in their own
 * preferences file, also excluded from backup.
 */
class AdBlockStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("care_adblock", Context.MODE_PRIVATE)
    private val file = File(context.filesDir, "adblock_hosts.txt")
    @Volatile private var cached: Blocklist? = null

    private val _exceptions = MutableStateFlow(prefs.getStringSet(KEY_EXCEPTIONS, emptySet()).orEmpty().toSortedSet())
    val exceptions: StateFlow<Set<String>> = _exceptions.asStateFlow()

    private val _info = MutableStateFlow<ListInfo?>(null)
    val info: StateFlow<ListInfo?> = _info.asStateFlow()

    fun list(): Blocklist {
        cached?.let { return it }
        val l = runCatching { AtomicTextFile(file.toPath()).read() }.getOrNull()?.let(Blocklist::parse) ?: Blocklist.EMPTY
        cached = l
        _info.value = ListInfo(l.size, if (l.size > 0) file.lastModified() else null)
        return l
    }

    fun policy(): DnsPolicy = DnsPolicy(list(), _exceptions.value)

    suspend fun loadInfo() = withContext(Dispatchers.IO) { list() }

    fun addException(host: String) = synchronized(this) {
        val h = host.lowercase().trimEnd('.').take(253)
        if (h.isEmpty()) return
        val next = (_exceptions.value + h).toSortedSet()
        prefs.edit().putStringSet(KEY_EXCEPTIONS, next).apply()
        _exceptions.value = next
    }

    fun removeException(host: String) = synchronized(this) {
        val next = (_exceptions.value - host).toSortedSet()
        prefs.edit().putStringSet(KEY_EXCEPTIONS, next).apply()
        _exceptions.value = next
    }

    /** User-initiated only. HTTPS with system CAs, no redirects, size-capped, validated before replacing. */
    suspend fun update(): ListUpdateResult = withContext(Dispatchers.IO) {
        val text = try {
            val net = AppNetwork.forOwnTraffic(context)
            val conn = (net?.openConnection(URL(LIST_URL)) ?: URL(LIST_URL).openConnection()) as HttpsURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            try {
                if (conn.responseCode != 200) return@withContext ListUpdateResult.NETWORK_ERROR
                conn.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > Blocklist.MAX_BYTES) return@withContext ListUpdateResult.INVALID
                    }
                    out.toByteArray().toString(Charsets.UTF_8)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext ListUpdateResult.NETWORK_ERROR
        }
        val parsed = Blocklist.parse(text)
        if (parsed.size < Blocklist.MIN_ENTRIES) return@withContext ListUpdateResult.INVALID
        AtomicTextFile(file.toPath()).write(text)
        cached = parsed
        _info.value = ListInfo(parsed.size, System.currentTimeMillis())
        ListUpdateResult.UPDATED
    }

    companion object {
        const val LIST_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
        private const val KEY_EXCEPTIONS = "exceptions"
        val status = MutableStateFlow(AdBlockStatus())
    }
}
