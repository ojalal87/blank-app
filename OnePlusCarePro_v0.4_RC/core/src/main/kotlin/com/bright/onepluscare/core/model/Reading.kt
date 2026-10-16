package com.bright.onepluscare.core.model

/**
 * A value shown to the user is either measured from a named source or explicitly unavailable.
 * The UI must never render a default (0, -1, "Normal") in place of an unavailable reading.
 */
sealed interface Reading<out T> {
    data class Measured<T>(val value: T, val source: Source = Source.ANDROID_API) : Reading<T>
    data class Unavailable(val reason: UnavailableReason) : Reading<Nothing>

    fun valueOrNull(): T? = (this as? Measured<T>)?.value

    companion object {
        fun <T> of(value: T?, ifNull: UnavailableReason = UnavailableReason.NOT_REPORTED, source: Source = Source.ANDROID_API): Reading<T> =
            if (value == null) Unavailable(ifNull) else Measured(value, source)
    }
}

enum class Source {
    /** Documented Android SDK API. */
    ANDROID_API,

    /** Documented API whose value semantics vary by OEM (e.g. BatteryManager current sign/units). */
    OEM_DEPENDENT,

    /** Collected through the optional Shizuku read-only diagnostics service. */
    SHIZUKU,

    /** Computed by this app from other measured values. */
    DERIVED,
}

enum class UnavailableReason {
    API_LEVEL_TOO_LOW,
    NOT_REPORTED,
    PERMISSION_REQUIRED,
    OEM_RESTRICTED,
    ERROR,
}
