package com.bright.onepluscare.firewall

import android.content.Context
import com.bright.onepluscare.core.security.FirewallRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Blocked-app list for the firewall. Stored in its own preferences file, which backup_rules excludes (only
 * care_settings.xml is backed up), so the list never leaves the device.
 */
class FirewallStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("care_firewall", Context.MODE_PRIVATE)
    private val _blocked = MutableStateFlow(read())
    val blocked: StateFlow<Set<String>> = _blocked.asStateFlow()

    private fun read(): Set<String> = FirewallRules.sanitize(prefs.getStringSet(KEY, emptySet()).orEmpty(), context.packageName)

    fun current(): Set<String> = read()

    fun set(pkg: String, blocked: Boolean) = synchronized(this) {
        val next = if (blocked) read() + pkg else read() - pkg
        val clean = FirewallRules.sanitize(next, context.packageName)
        prefs.edit().putStringSet(KEY, clean).apply()
        _blocked.value = clean
    }

    companion object {
        private const val KEY = "blocked"
        /** Process-wide running state, updated by [FirewallVpnService]. */
        val running = MutableStateFlow(false)
    }
}
