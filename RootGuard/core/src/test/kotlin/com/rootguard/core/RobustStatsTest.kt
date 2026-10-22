package com.rootguard.core

import com.rootguard.core.behavior.RobustStats
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Golden cases ported one-to-one from handoff tests/test_handoff.py BehaviorTests. */
class RobustStatsTest {
    private val h1000 = List(40) { 1000.0 }

    @Test fun `constant history stable and burst`() {
        assertEquals(0.0, RobustStats.assessUpload(1000.0, h1000).anomalyScore)
        val r = RobustStats.assessUpload(1_000_000.0, h1000)
        assertTrue(r.anomalyScore!! >= 60)
        assertFalse(r.trainingEligible)
        assertNull(r.malwareProbability)
    }

    @Test fun `score does not train`() {
        val h = h1000.toMutableList()
        RobustStats.assessUpload(1_000_000.0, h)
        assertEquals(h1000, h)
    }

    @Test fun `missing is not zero`() {
        val r = RobustStats.assessUpload(null, h1000)
        assertNull(r.anomalyScore)
        assertFalse(r.trainingEligible)
        assertEquals(0.0, RobustStats.assessUpload(0.0, List(40) { 0.0 }).anomalyScore)
    }

    @Test fun `cold start and incomplete`() {
        assertEquals("INSUFFICIENT_HISTORY", RobustStats.assessUpload(1.0, listOf(1.0)).reason)
        assertEquals("INCOMPLETE_WINDOW", RobustStats.assessUpload(1.0, List(40) { 1.0 }, completeness = 0.3).reason)
    }

    @Test fun `known ioc survives cold start`() {
        val r = RobustStats.assessUpload(1.0, emptyList(), knownIoc = true)
        assertEquals(90.0, r.riskScore)
        assertFalse(r.trainingEligible)
    }

    @Test fun `invalid input`() {
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { v -> assertThrows<IllegalArgumentException> { RobustStats.assessUpload(v, List(40) { 1.0 }) } }
        assertThrows<IllegalArgumentException> { RobustStats.assessUpload(1.0, List(40) { 1.0 }, scaleFloor = 0.0) }
    }

    @Test fun ewma() {
        assertEquals(120.0, RobustStats.ewmaNext(100.0, 200.0, 0.2), 1e-9)
        assertEquals(7.0, RobustStats.ewmaNext(null, 7.0))
    }

    @Test fun `counter reset`() {
        assertNull(RobustStats.deltaCounter(100, 5, "b", "b"))
        assertNull(RobustStats.deltaCounter(100, 150, "a", "b"))
        assertEquals(50L, RobustStats.deltaCounter(100, 150, "b", "b"))
    }
}
