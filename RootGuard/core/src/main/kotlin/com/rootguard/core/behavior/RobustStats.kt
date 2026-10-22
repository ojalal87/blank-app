package com.rootguard.core.behavior

import kotlin.math.abs
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min

/**
 * Kotlin port of handoff reference/behavior_reference.py (single upload feature). Thresholds and weights
 * are the reference's illustrative values, not validated production tuning. Probability is always null.
 */
data class UploadAssessment(
    val anomalyScore: Double?,
    val riskScore: Double?,
    val dataConfidence: Double,
    val reason: String,
    val trainingEligible: Boolean,
) {
    val malwareProbability: Double? get() = null
}

object RobustStats {
    fun median(values: List<Double>): Double {
        require(values.isNotEmpty())
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    fun assessUpload(
        current: Double?,
        history: List<Double>,
        completeness: Double = 1.0,
        knownIoc: Boolean = false,
        minimumHistory: Int = 30,
        scaleFloor: Double = 0.15,
    ): UploadAssessment {
        require(completeness.isFinite() && completeness in 0.0..1.0) { "completeness must be finite in [0,1]" }
        require(minimumHistory >= 1) { "minimum_history must be positive" }
        require(scaleFloor.isFinite() && scaleFloor > 0) { "scale_floor must be finite and positive" }
        require(history.all { it.isFinite() && it >= 0 }) { "history must contain finite nonnegative values" }
        require(current == null || (current.isFinite() && current >= 0)) { "current must be finite and nonnegative" }
        val confidence = min(1.0, history.size.toDouble() / minimumHistory) * completeness
        val iocRisk = if (knownIoc) 90.0 else null
        if (current == null) return UploadAssessment(null, iocRisk, 0.0, "MISSING_FEATURE", false)
        if (history.size < minimumHistory) return UploadAssessment(null, iocRisk, confidence, "INSUFFICIENT_HISTORY", false)
        if (completeness < 0.9) return UploadAssessment(null, iocRisk, confidence, "INCOMPLETE_WINDOW", false)
        val values = history.map { ln1p(it) }
        val center = median(values)
        val mad = median(values.map { abs(it - center) })
        val z = max(0.0, (ln1p(current) - center) / max(1.4826 * mad, scaleFloor))
        val score = min(100.0, z * 10.0)
        val risk = max(if (knownIoc) 90.0 else 0.0, score * 0.5)
        return UploadAssessment(score, risk, confidence, if (score >= 60) "UPLOAD_DEVIATION" else "WITHIN_BASELINE", score < 60 && !knownIoc)
    }

    fun ewmaNext(previous: Double?, observation: Double, alpha: Double = 0.2): Double {
        require(alpha.isFinite() && alpha > 0 && alpha <= 1) { "alpha must be in (0,1]" }
        require(observation.isFinite() && (previous == null || previous.isFinite())) { "finite observations required" }
        return if (previous == null) observation else alpha * observation + (1 - alpha) * previous
    }

    /** null = reset or unavailable: neither zero traffic nor a huge spike. */
    fun deltaCounter(previous: Long?, current: Long, previousBoot: String?, currentBoot: String): Long? {
        require(current >= 0 && (previous == null || previous >= 0)) { "negative counter" }
        if (previous == null || previousBoot != currentBoot || current < previous) return null
        return current - previous
    }
}
