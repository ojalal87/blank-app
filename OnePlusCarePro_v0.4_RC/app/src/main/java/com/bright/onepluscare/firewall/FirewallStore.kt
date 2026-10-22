package com.bright.onepluscare.firewall

import android.content.Context
import com.bright.onepluscare.core.security.FirewallPolicy
import com.bright.onepluscare.core.security.FirewallRules
import com.bright.onepluscare.core.security.NetKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the firewall is doing right now. */
data class FirewallStatus(
    val running: Boolean = false,
    val network: NetKind = NetKind.NONE,
    /** Apps blocked on the current network. */
    val blockingNow: Int = 0,
    /** Nothing to block on this network: only this app is in the tunnel (with bypass). */
    val selfOnly: Boolean = false,
)

/**
 * Per-network blocked-app lists. Stored in their own preferences file, which backup_rules excludes (only
 * care_settings.xml is backed up), so the lists never leave the device. v0.4.0-rc3 had one list meaning
 * "block everywhere"; it is migrated into both lists once.
 */
class FirewallStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("care_firewall", Context.MODE_PRIVATE)

    init {
        migrate()
    }

    private val _policy = MutableStateFlow(read())
    val policy: StateFlow<FirewallPolicy> = _policy.asStateFlow()

    private fun migrate() {
        synchronized(LOCK) {
            val legacy = prefs.getStringSet(KEY_LEGACY, null) ?: return
            prefs.edit()
            .putStringSet(KEY_WIFI, (prefs.getStringSet(KEY_WIFI, emptySet()).orEmpty() + legacy))
            .putStringSet(KEY_MOBILE, (prefs.getStringSet(KEY_MOBILE, emptySet()).orEmpty() + legacy))
            .remove(KEY_LEGACY)
            .commit()
        }
    }

    private fun read(): FirewallPolicy = FirewallPolicy(
        FirewallRules.sanitize(prefs.getStringSet(KEY_WIFI, emptySet()).orEmpty(), context.packageName),
        FirewallRules.sanitize(prefs.getStringSet(KEY_MOBILE, emptySet()).orEmpty(), context.packageName),
    )

    fun current(): FirewallPolicy = synchronized(LOCK) { read() }

    fun set(pkg: String, kind: NetKind, blocked: Boolean) = synchronized(LOCK) {
        require(kind == NetKind.WIFI || kind == NetKind.MOBILE)
        val p = read()
        val key = if (kind == NetKind.WIFI) KEY_WIFI else KEY_MOBILE
        val old = if (kind == NetKind.WIFI) p.blockWifi else p.blockMobile
        val next = FirewallRules.sanitize(if (blocked) old + pkg else old - pkg, context.packageName)
        prefs.edit().putStringSet(key, next).commit()
        _policy.value = read()
    }

    fun setEverywhere(pkg: String, blocked: Boolean) {
        set(pkg, NetKind.WIFI, blocked)
        set(pkg, NetKind.MOBILE, blocked)
    }

    companion object {
        private const val KEY_LEGACY = "blocked"
        private const val KEY_WIFI = "blocked_wifi"
        private const val KEY_MOBILE = "blocked_mobile"
        private val LOCK = Any()

        /** Process-wide state, updated by [FirewallVpnService]. */
        val status = MutableStateFlow(FirewallStatus())
    }
}
