package ru.klaviatura

import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.emoji.EmojiLayoutParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.3.2 crash: opening the emoji palette a second time (e.g. from the toolbar) threw ClassCastException */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EmojiReopenCrashTest {
    @Test fun reopenEmojiPaletteDoesNotCrash() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val pager = ViewPager2(ctx)
        root.addView(pager, LinearLayout.LayoutParams(-1, -2))
        val listener = Mockito.mock(KeyboardActionListener::class.java)
        repeat(3) { // every startEmojiPalettes(): set height, then attach the GIF panel
            EmojiLayoutParams.applyListHeight(pager, 500, 0)
            ru.klaviatura.emoji.GifPanel.attach(root, pager, listener)
        }
        EmojiLayoutParams.applyListHeight(pager, 600, 0)
        val wrapper = pager.parent as View
        assertTrue(wrapper is FrameLayout)
        assertEquals(600, wrapper.layoutParams.height)
        assertEquals(-1, pager.layoutParams.height)
        assertEquals(1, root.childCount)
    }
}
