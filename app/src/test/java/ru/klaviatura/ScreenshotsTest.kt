package ru.klaviatura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.klaviatura.clip.Screenshots
import ru.klaviatura.clip.ClipKind

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
    @Test fun successfulResumeImportReopensDismissedScreenshotQueue() {
        assertTrue(Screenshots.keepQueueDismissed(wasDismissed = true, importedCount = 0))
        assertFalse(Screenshots.keepQueueDismissed(wasDismissed = true, importedCount = 1))
        assertFalse(Screenshots.keepQueueDismissed(wasDismissed = true, importedCount = 3))
        assertFalse(Screenshots.keepQueueDismissed(wasDismissed = false, importedCount = 0))
    }

    @Test fun primaryClipboardImageIsDetectedFromUriOrDescriptionMimeType() {
        assertTrue(ClipKind.isImage("image/jpeg", listOf("text/plain")))
        assertTrue(ClipKind.isImage(null, listOf("image/png")))
        assertTrue(ClipKind.isImage("IMAGE/WEBP", listOf("text/plain")))
        assertFalse(ClipKind.isImage("application/pdf", listOf("application/pdf")))
        assertFalse(ClipKind.isImage(null, listOf("text/plain")))
    }

    @Test fun newestScreenshotWinsAndHigherIdBreaksTimestampTies() {
        assertTrue(Screenshots.compareCaptureOrder(200L, 2L, 100L, 99L) > 0)
        assertTrue(Screenshots.compareCaptureOrder(200L, 3L, 200L, 2L) > 0)
        assertTrue(Screenshots.compareCaptureOrder(100L, 1L, 200L, 2L) < 0)
    }

}
