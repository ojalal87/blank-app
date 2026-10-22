package com.bright.onepluscare.core.tweaks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class SettingNamespace(val cli: String) {
    @SerialName("global") GLOBAL("global"),
    @SerialName("secure") SECURE("secure"),
    @SerialName("system") SYSTEM("system"),
}

@Serializable
enum class PackageListFilter { SYSTEM, DISABLED, SYSTEM_INCLUDING_UNINSTALLED }

/**
 * Every privileged action the Shizuku service can perform, as typed data.
 *
 * Trust model: the app builds these, but the *service* re-validates each one with [TweakCommands.argvFor]
 * before running anything. There is no op that carries a command line, path or free-form argument; every
 * field is checked against an allow-list or a strict pattern, so a compromised or buggy caller can only
 * request what is listed here.
 */
@Serializable
sealed interface TweakOp {
    /** True for ops that only read state. */
    val readOnly: Boolean get() = false

    @Serializable @SerialName("setting_get")
    data class SettingGet(val ns: SettingNamespace, val key: String) : TweakOp { override val readOnly get() = true }

    @Serializable @SerialName("setting_put")
    data class SettingPut(val ns: SettingNamespace, val key: String, val value: String) : TweakOp

    @Serializable @SerialName("setting_delete")
    data class SettingDelete(val ns: SettingNamespace, val key: String) : TweakOp

    @Serializable @SerialName("appop_get")
    data class AppOpGet(val pkg: String, val op: String) : TweakOp { override val readOnly get() = true }

    @Serializable @SerialName("appop_set")
    data class AppOpSet(val pkg: String, val op: String, val mode: String) : TweakOp

    @Serializable @SerialName("idle_whitelist_list")
    data object IdleWhitelistList : TweakOp { override val readOnly get() = true }

    @Serializable @SerialName("idle_whitelist_add")
    data class IdleWhitelistAdd(val pkg: String) : TweakOp

    @Serializable @SerialName("idle_whitelist_remove")
    data class IdleWhitelistRemove(val pkg: String) : TweakOp

    @Serializable @SerialName("standby_get")
    data class StandbyGet(val pkg: String) : TweakOp { override val readOnly get() = true }

    @Serializable @SerialName("standby_set")
    data class StandbySet(val pkg: String, val bucket: String) : TweakOp

    @Serializable @SerialName("packages_list")
    data class PackagesList(val filter: PackageListFilter) : TweakOp { override val readOnly get() = true }

    @Serializable @SerialName("package_disable")
    data class PackageDisable(val pkg: String) : TweakOp

    @Serializable @SerialName("package_enable")
    data class PackageEnable(val pkg: String) : TweakOp

    /** `pm uninstall -k --user 0`: removes for this user only; the APK stays on /system and can be reinstalled. */
    @Serializable @SerialName("package_uninstall_user")
    data class PackageUninstallUser(val pkg: String) : TweakOp

    @Serializable @SerialName("package_install_existing")
    data class PackageInstallExisting(val pkg: String) : TweakOp

    @Serializable @SerialName("density_get")
    data object DensityGet : TweakOp { override val readOnly get() = true }

    @Serializable @SerialName("density_set")
    data class DensitySet(val dpi: Int) : TweakOp

    @Serializable @SerialName("density_reset")
    data object DensityReset : TweakOp

    companion object {
        val JSON = Json { ignoreUnknownKeys = false; classDiscriminator = "type"; encodeDefaults = true }

        fun encode(op: TweakOp): String = JSON.encodeToString(serializer(), op)

        /** Returns null for anything that is not a well-formed op (never throws on hostile input). */
        fun decode(raw: String?): TweakOp? = if (raw == null || raw.length > 2_000) null else try {
            JSON.decodeFromString(serializer(), raw)
        } catch (_: Exception) {
            null
        }
    }
}

/** What a setting key may be set to. */
sealed interface ValueRule {
    fun accepts(value: String): Boolean

    data class OneOf(val values: Set<String>) : ValueRule {
        override fun accepts(value: String) = value in values
    }

    data class FloatRange(val min: Float, val max: Float) : ValueRule {
        override fun accepts(value: String): Boolean {
            if (!FLOAT.matches(value)) return false
            val f = value.toFloatOrNull() ?: return false
            return f in min..max
        }
    }

    data object Hostname : ValueRule {
        private val HOST = Regex("^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")
        override fun accepts(value: String) = HOST.matches(value)
    }

    companion object {
        private val FLOAT = Regex("^[0-9]{1,3}(\\.[0-9]{1,3})?$")
    }
}

data class SettingSpec(val ns: SettingNamespace, val key: String, val rule: ValueRule)

/** The closed set of system settings the app may read or change. */
object SettingsAllowList {
    val ANIMATION_SCALES = listOf("0.0", "0.5", "0.75", "1.0", "1.25", "1.5", "2.0")

    val WINDOW_ANIMATION = SettingSpec(SettingNamespace.GLOBAL, "window_animation_scale", ValueRule.OneOf(ANIMATION_SCALES.toSet()))
    val TRANSITION_ANIMATION = SettingSpec(SettingNamespace.GLOBAL, "transition_animation_scale", ValueRule.OneOf(ANIMATION_SCALES.toSet()))
    val ANIMATOR_DURATION = SettingSpec(SettingNamespace.GLOBAL, "animator_duration_scale", ValueRule.OneOf(ANIMATION_SCALES.toSet()))
    val PEAK_REFRESH = SettingSpec(SettingNamespace.SYSTEM, "peak_refresh_rate", ValueRule.FloatRange(30f, 240f))
    val MIN_REFRESH = SettingSpec(SettingNamespace.SYSTEM, "min_refresh_rate", ValueRule.FloatRange(0f, 240f))
    val PRIVATE_DNS_MODE = SettingSpec(SettingNamespace.GLOBAL, "private_dns_mode", ValueRule.OneOf(setOf("off", "opportunistic", "hostname")))
    val PRIVATE_DNS_HOST = SettingSpec(SettingNamespace.GLOBAL, "private_dns_specifier", ValueRule.Hostname)
    /** Android 14+ developer option "Disable child process restrictions" (phantom process killer). */
    val PHANTOM_PROCS = SettingSpec(SettingNamespace.GLOBAL, "settings_enable_monitor_phantom_procs", ValueRule.OneOf(setOf("true", "false")))

    val ALL: List<SettingSpec> = listOf(
        WINDOW_ANIMATION, TRANSITION_ANIMATION, ANIMATOR_DURATION, PEAK_REFRESH, MIN_REFRESH,
        PRIVATE_DNS_MODE, PRIVATE_DNS_HOST, PHANTOM_PROCS,
    )

    fun find(ns: SettingNamespace, key: String): SettingSpec? = ALL.firstOrNull { it.ns == ns && it.key == key }
}

object AppOpsAllowList {
    val OPS = setOf("RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND")
    val MODES = setOf("allow", "ignore", "deny", "default")
}

object StandbyBuckets {
    val SETTABLE = setOf("active", "working_set", "frequent", "rare", "restricted")

    /** Numeric value printed by `am get-standby-bucket` → name accepted by set-standby-bucket. */
    fun nameOf(code: Int): String? = when (code) {
        10 -> "active"
        20 -> "working_set"
        30 -> "frequent"
        40 -> "rare"
        45 -> "restricted"
        else -> null // 5 exempted, 50 never: not restorable by name; undo leaves them to the system
    }
}
