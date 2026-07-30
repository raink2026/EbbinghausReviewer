package com.ebbinghaus.review.data.sync

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class ProfileDayBoundary(
    val date: LocalDate,
    val startInclusiveMillis: Long,
    val endExclusiveMillis: Long
)

class ProfileTimeService(
    timezone: String,
    private val clock: Clock = Clock.systemUTC()
) {
    val zoneId: ZoneId = ZoneId.of(timezone)
    val timezoneId: String = zoneId.id

    fun now(): Instant = clock.instant()

    fun nowWithOffset(): OffsetDateTime = now().atZone(zoneId).toOffsetDateTime()

    fun localDateAt(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate()

    fun localDateAt(instant: Instant): LocalDate = instant.atZone(zoneId).toLocalDate()

    fun repositoryDate(epochMillis: Long = now().toEpochMilli()): String =
        DATE_FORMATTER.format(localDateAt(epochMillis))

    fun repositoryPath(
        category: RepositoryDateCategory,
        filename: String,
        epochMillis: Long = now().toEpochMilli()
    ): String {
        require(SAFE_FILENAME.matches(filename)) { "Invalid repository filename" }
        return "${repositoryDate(epochMillis)}/${category.directory}/$filename"
    }

    fun dayBoundary(date: LocalDate): ProfileDayBoundary {
        val start = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        return ProfileDayBoundary(date, start, end)
    }

    fun dayBoundary(epochMillis: Long): ProfileDayBoundary = dayBoundary(localDateAt(epochMillis))

    fun reviewAtStartOfDay(learningStartedAt: Long, intervalDays: Int): Long {
        require(intervalDays >= 0) { "Review interval cannot be negative" }
        return localDateAt(learningStartedAt)
            .plusDays(intervalDays.toLong())
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
    }

    fun parseOffsetDateTime(value: String): ZonedDateTime =
        OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).atZoneSameInstant(zoneId)

    fun formatOffsetDateTime(epochMillis: Long): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            Instant.ofEpochMilli(epochMillis).atZone(zoneId).toOffsetDateTime()
        )

    fun legacyTimestampToRepositoryDate(epochMillis: Long): String = repositoryDate(epochMillis)

    fun calendarDate(epochMillis: Long): LocalDate = localDateAt(epochMillis)

    companion object {
        private val DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE
        private val SAFE_FILENAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")

        fun forProfile(profile: Profile, clock: Clock = Clock.systemUTC()): ProfileTimeService =
            ProfileTimeService(profile.timezone, clock)
    }
}

enum class RepositoryDateCategory(val directory: String) {
    NOTES("notes"),
    ASSETS("assets"),
    EVENTS("events")
}

data class DesktopProfileMetadata(
    val profileId: String,
    val timezone: String,
    val currentRepositoryDate: String,
    val algorithmId: String,
    val algorithmVersion: Int,
    val algorithmParametersJson: String
) {
    companion object {
        fun from(profile: Profile, timeService: ProfileTimeService): DesktopProfileMetadata {
            require(profile.timezone == timeService.timezoneId)
            return DesktopProfileMetadata(
                profileId = profile.profileId,
                timezone = timeService.timezoneId,
                currentRepositoryDate = timeService.repositoryDate(),
                algorithmId = profile.algorithmId,
                algorithmVersion = profile.algorithmVersion,
                algorithmParametersJson = profile.algorithmParametersJson
            )
        }
    }
}
