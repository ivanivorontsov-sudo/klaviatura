package ru.klaviatura

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.klaviatura.bg.ImageChecks
import kotlin.random.Random

class ImageChecksTest {
    @Test fun qrDetected() {
        // 29×29 random modules scaled to 64 px with a white quiet zone
        val r = Random(1); val mod = Array(29) { BooleanArray(29) { r.nextBoolean() } }
        val lum = IntArray(64 * 64) { i -> val x = i % 64 - 3; val y = i / 64 - 3
            if (x !in 0 until 58 || y !in 0 until 58) 255 else if (mod[y / 2][x / 2]) 20 else 240 }
        assertTrue(ImageChecks.qrLike(lum, 64))
    }

    @Test fun smoothArtNotQr() {
        val grad = IntArray(64 * 64) { i -> 60 + (i % 64) * 2 + (i / 64) }
        assertFalse(ImageChecks.qrLike(grad, 64))
        val r = Random(2) // noisy but mid-tone painting
        assertFalse(ImageChecks.qrLike(IntArray(64 * 64) { 90 + r.nextInt(80) }, 64))
        // high-contrast photo with large areas (few transitions): night sky with moon
        assertFalse(ImageChecks.qrLike(IntArray(64 * 64) { i -> val x = i % 64 - 40; val y = i / 64 - 20; if (x * x + y * y < 100) 250 else 10 }, 64))
    }

    @Test fun sourcesAndSize() {
        assertTrue(ImageChecks.badSource("https://x/static/qrcode.png", ""))
        assertTrue(ImageChecks.badSource("https://x/u/avatar_123.jpg", ""))
        assertTrue(ImageChecks.badSource("https://x/a.png", "Logo"))
        assertFalse(ImageChecks.badSource("https://cdn.qwen.ai/output/7f3a.webp?x=qr", "generated image"))
        assertFalse(ImageChecks.bigEnough(400, 900)); assertTrue(ImageChecks.bigEnough(1024, 576))
    }
}
