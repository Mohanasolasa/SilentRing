package com.example.autosilent.scheduler

import android.content.Intent
import android.net.Uri
import com.example.autosilent.domain.model.BoundaryEvent
import com.example.autosilent.domain.model.BoundaryType
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AndroidAlarmSchedulerIdentityTest {

    @Test
    fun createEventUri_producesDistinctUris() {
        val t1 = Instant.parse("2026-10-12T07:00:00Z")
        val t2 = Instant.parse("2026-10-13T07:00:00Z")

        val uriSchedule1Start = AndroidAlarmScheduler.createEventUriString(1L, BoundaryType.START, t1)
        val uriSchedule1End = AndroidAlarmScheduler.createEventUriString(1L, BoundaryType.END, t1)
        val uriSchedule2Start = AndroidAlarmScheduler.createEventUriString(2L, BoundaryType.START, t1)
        val uriSchedule1NextDay = AndroidAlarmScheduler.createEventUriString(1L, BoundaryType.START, t2)

        // All 4 URIs MUST be distinct to ensure Intent.filterEquals() returns false!
        val uris = setOf(uriSchedule1Start, uriSchedule1End, uriSchedule2Start, uriSchedule1NextDay)
        assertEquals(4, uris.size)
    }

    @Test
    fun computeRequestCode_producesDistinctCodes() {
        val t1 = Instant.parse("2026-10-12T07:00:00Z")
        val t2 = Instant.parse("2026-10-13T07:00:00Z")

        val code1 = AndroidAlarmScheduler.computeRequestCode(1L, BoundaryType.START, t1)
        val code2 = AndroidAlarmScheduler.computeRequestCode(1L, BoundaryType.END, t1)
        val code3 = AndroidAlarmScheduler.computeRequestCode(2L, BoundaryType.START, t1)
        val code4 = AndroidAlarmScheduler.computeRequestCode(1L, BoundaryType.START, t2)

        val codes = setOf(code1, code2, code3, code4)
        assertEquals(4, codes.size)
    }
}
