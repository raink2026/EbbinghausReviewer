package com.ebbinghaus.review.data.sync

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProfileTimeServiceTest {
    @Test
    fun repositoryDateUsesProfileTimezoneWhenDeviceTimezoneChanges() {
        val instant = Instant.parse("2026-07-22T16:30:00Z")
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val utcDeviceDate = ProfileTimeService(
                "Asia/Shanghai",
                Clock.fixed(instant, ZoneOffset.UTC)
            ).repositoryDate()

            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val pacificDeviceDate = ProfileTimeService(
                "Asia/Shanghai",
                Clock.fixed(instant, ZoneOffset.UTC)
            ).repositoryDate()

            assertEquals("2026-07-23", utcDeviceDate)
            assertEquals(utcDeviceDate, pacificDeviceDate)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun dayBoundaryHonorsDaylightSavingTransition() {
        val service = ProfileTimeService("America/New_York")
        val boundary = service.dayBoundary(java.time.LocalDate.parse("2026-03-08"))

        assertEquals(
            Duration.ofHours(23).toMillis(),
            boundary.endExclusiveMillis - boundary.startInclusiveMillis
        )
    }

    @Test
    fun sameInstantCanMapToDifferentProfileDirectories() {
        val instant = Instant.parse("2026-07-22T16:30:00Z")
        val utcClock = Clock.fixed(instant, ZoneOffset.UTC)
        val shanghai = ProfileTimeService("Asia/Shanghai", utcClock)
        val losAngeles = ProfileTimeService("America/Los_Angeles", utcClock)

        assertEquals(
            "2026-07-23/notes/revision.md",
            shanghai.repositoryPath(RepositoryDateCategory.NOTES, "revision.md")
        )
        assertEquals(
            "2026-07-22/notes/revision.md",
            losAngeles.repositoryPath(RepositoryDateCategory.NOTES, "revision.md")
        )
        assertNotEquals(shanghai.repositoryDate(), losAngeles.repositoryDate())
    }
}
