package com.bright.onepluscare.core

import com.bright.onepluscare.core.usage.UidUsage
import com.bright.onepluscare.core.usage.UnusualReason
import com.bright.onepluscare.core.usage.UsageAnalyzer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UsageTest {
    private val mb = 1024L * 1024

    @Test
    fun `steady uploader is not flagged, a spike is`() {
        val history = List(14) { 30 * mb + it * mb }
        assertNull(UsageAnalyzer.judge(1, 35 * mb, history))
        val r = UsageAnalyzer.judge(1, 3000 * mb, history)!!
        assertEquals(UnusualReason.BACKGROUND_UPLOAD_SPIKE, r.reason)
    }

    @Test
    fun `quiet app that suddenly uploads a lot is a new background uploader`() {
        assertEquals(UnusualReason.NEW_BACKGROUND_UPLOADER, UsageAnalyzer.judge(1, 200 * mb, List(10) { 10_000L })!!.reason)
    }

    @Test
    fun `small volumes, short or unknown history are never judged`() {
        assertNull(UsageAnalyzer.judge(1, 5 * mb, List(14) { 1L }))
        assertNull(UsageAnalyzer.judge(1, 500 * mb, List(6) { 1L }))
        assertNull(UsageAnalyzer.judge(1, 500 * mb, List(14) { if (it < 8) null else 1L }))
    }

    @Test
    fun `constant history does not divide by zero`() {
        assertNull(UsageAnalyzer.judge(1, 51 * mb, List(14) { 50 * mb }))
    }

    @Test
    fun `usage sums by field`() {
        val u = UidUsage(1, wifiRx = 1, wifiTx = 2) + UidUsage(1, mobileRx = 3, mobileTx = 4, backgroundTx = 5)
        assertEquals(10, u.total)
        assertEquals(3, u.wifi)
        assertEquals(5, u.background)
    }
}
