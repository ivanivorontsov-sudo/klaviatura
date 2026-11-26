package ru.klaviatura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.klaviatura.clip.Screenshots

class ScreenshotsTest {
    @Test fun recognizesCommonScreenshotDirectoriesAndNames() {
        assertTrue(Screenshots.isScreenshot("DCIM/Screenshots/", "IMG_20261126.jpg"))
        assertTrue(Screenshots.isScreenshot("Pictures/Screenshots/", "image.png"))
        assertTrue(Screenshots.isScreenshot("Pictures/", "Screenshot_2026-11-26.png"))
        assertTrue(Screenshots.isScreenshot(null, "Скриншот_2026-11-26.png"))
        assertFalse(Screenshots.isScreenshot("DCIM/Camera/", "IMG_20261126.jpg"))
    }

    @Test fun staleCaptureTimeFallsBackToRecentMediaStoreInsertionTime() {
        val now = 1_800_000_000_000L
        assertEquals(
            1_799_999_500_123L,
            Screenshots.effectiveTimestamp(now, 1_799_999_500L, 1_700_000_000_000L, 123L)
        )
    }

    @Test fun recentCaptureTimeIsPreservedForQueueOrdering() {
        val now = 1_800_000_000_000L
        val captured = 1_799_999_900_456L
        assertEquals(captured, Screenshots.effectiveTimestamp(now, 1_799_999_900L, captured, 456L))
    }

    @Test fun missingOrFutureCaptureTimeFallsBackToMediaStoreTime() {
        val now = 1_800_000_000_000L
        assertEquals(
            1_799_999_500_789L,
            Screenshots.effectiveTimestamp(now, 1_799_999_500L, 0L, 789L)
        )
        assertEquals(
            1_799_999_500_789L,
            Screenshots.effectiveTimestamp(now, 1_799_999_500L, 1_900_000_000_000L, 789L)
        )
    }
}
