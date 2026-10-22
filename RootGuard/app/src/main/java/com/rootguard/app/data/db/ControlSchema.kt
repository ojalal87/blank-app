package com.rootguard.app.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * CHECK constraints and partial unique indexes from control_v1.sql that Room entities cannot declare,
 * enforced with BEFORE INSERT/UPDATE triggers. SQL NULL semantics match CHECK: a NULL condition passes.
 * Created on every open with IF NOT EXISTS, so a database created before a trigger existed gets it too.
 */
object ControlSchema {
    private fun enumIn(col: String, vararg values: String) = "NEW.$col IN (${values.joinToString(",") { "'$it'" }})"

    /** (table, check name, condition over NEW.*) */
    val CHECKS: List<Triple<String, String, String>> = listOf(
        Triple("app_installation", "removed_after_install", "NEW.removed_ms IS NULL OR NEW.removed_ms>=NEW.installed_ms"),
        Triple("principal", "uid_nonneg", "NEW.android_uid>=0"),
        Triple("principal", "attribution", enumIn("attribution", "EXACT", "SHARED", "UNKNOWN")),
        Triple("principal", "interval", "NEW.valid_until_ms IS NULL OR NEW.valid_until_ms>=NEW.valid_from_ms"),
        Triple("principal_member", "interval", "NEW.valid_until_ms IS NULL OR NEW.valid_until_ms>=NEW.valid_from_ms"),
        Triple("profile", "mode", enumIn("mode", "OBSERVE", "BALANCED", "PRIVACY", "STRICT", "DATA_SAVER", "CUSTOM")),
        Triple("profile", "default_action", enumIn("default_action", "ALLOW", "DENY")),
        Triple("profile", "behavior_response", enumIn("behavior_response", "LOG", "NOTIFY", "TEMP_CONTAIN")),
        Triple("profile", "failure_mode", enumIn("failure_mode", "PRESERVE_LAST_GOOD", "STRICT_FAIL_CLOSED")),
        Triple("policy_rule", "action", enumIn("action", "ALLOW", "DENY")),
        Triple("policy_rule", "layer", enumIn("layer", "EMERGENCY", "OVERRIDE", "THREAT", "USER")),
        Triple("policy_rule", "network", enumIn("network", "ANY", "WIFI", "MOBILE", "VPN", "LAN", "TETHER")),
        Triple("policy_rule", "destination_type", enumIn("destination_type", "ANY", "IP", "CIDR", "DOMAIN_SUFFIX")),
        Triple("policy_rule", "protocol", enumIn("protocol", "ANY", "TCP", "UDP", "ICMP", "ICMPV6")),
        Triple("policy_rule", "enabled", "NEW.enabled IN (0,1)"),
        Triple("policy_rule", "destination", "(NEW.destination_type='ANY' AND NEW.destination_value IS NULL) OR (NEW.destination_type<>'ANY' AND NEW.destination_value IS NOT NULL)"),
        Triple(
            "policy_rule", "ports",
            "(NEW.port_from IS NULL AND NEW.port_to IS NULL) OR (NEW.port_from IS NOT NULL AND NEW.port_to IS NOT NULL AND NEW.port_from>=1 AND NEW.port_to<=65535 AND NEW.port_from<=NEW.port_to AND NEW.protocol IN ('TCP','UDP'))",
        ),
        Triple("runtime_state", "singleton", "NEW.singleton=1"),
        Triple("runtime_state", "health", enumIn("health", "OFF", "ACTIVE", "DEGRADED", "UNKNOWN")),
        Triple("apply_transaction", "phase", enumIn("phase", "PREPARED", "APPLYING", "VERIFIED", "COMMITTED", "ROLLED_BACK", "FAILED")),
        Triple("temporary_override", "overrides_threat", "NEW.overrides_threat IN (0,1)"),
        Triple("temporary_override", "expiry", "NEW.expires_ms>NEW.created_ms"),
        Triple("consent", "revoked_after_grant", "NEW.revoked_ms IS NULL OR NEW.revoked_ms>=NEW.granted_ms"),
        Triple("outbox", "state", enumIn("state", "PENDING", "RUNNING", "DONE", "FAILED")),
        Triple("outbox", "attempts", "NEW.attempts>=0"),
    )

    private val PARTIAL_UNIQUE = listOf(
        // one_active_install: UNIQUE(user_serial,package_name) WHERE removed_ms IS NULL
        Triple(
            "app_installation", "one_active_install",
            "NEW.removed_ms IS NULL AND EXISTS(SELECT 1 FROM app_installation o WHERE o.user_serial=NEW.user_serial AND o.package_name=NEW.package_name AND o.removed_ms IS NULL AND o.app_id<>NEW.app_id)",
        ),
        // one_active_uid: UNIQUE(user_serial,android_uid) WHERE valid_until_ms IS NULL
        Triple(
            "principal", "one_active_uid",
            "NEW.valid_until_ms IS NULL AND EXISTS(SELECT 1 FROM principal o WHERE o.user_serial=NEW.user_serial AND o.android_uid=NEW.android_uid AND o.valid_until_ms IS NULL AND o.principal_id<>NEW.principal_id)",
        ),
    )

    fun triggerSql(): List<String> {
        val out = ArrayList<String>()
        for ((table, name, cond) in CHECKS) {
            for (op in listOf("INSERT", "UPDATE")) {
                out += "CREATE TRIGGER IF NOT EXISTS ck_${table}_${name}_${op.lowercase()} BEFORE $op ON $table " +
                    "WHEN NOT ($cond) BEGIN SELECT RAISE(ABORT, 'CHECK constraint failed: $table.$name'); END"
            }
        }
        for ((table, name, cond) in PARTIAL_UNIQUE) {
            for (op in listOf("INSERT", "UPDATE")) {
                out += "CREATE TRIGGER IF NOT EXISTS uq_${name}_${op.lowercase()} BEFORE $op ON $table " +
                    "WHEN $cond BEGIN SELECT RAISE(ABORT, 'UNIQUE constraint failed: $name'); END"
            }
        }
        return out
    }

    fun install(db: SupportSQLiteDatabase) {
        triggerSql().forEach(db::execSQL)
    }
}
