package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.example.data.local.AvatarCropTransform
import com.example.data.local.renderAvatarCrop
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarCropTest {
    @Test fun everyRotationZoomAndPanKeepsTheSquareCovered() {
        for ((width, height) in listOf(2048 to 512, 512 to 2048, 512 to 512)) {
            for (turns in 0..3) for (zoom in listOf(.1f, 1f, 2f, 5f, 10f)) {
                for (pan in listOf(-20f, 0f, 20f)) {
                    val crop = AvatarCropTransform(turns, zoom, pan, -pan)
                    val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
                    crop.matrix(width, height, 512f).mapRect(bounds)
                    assertTrue("$crop: $bounds", bounds.left <= .01f && bounds.top <= .01f &&
                        bounds.right >= 511.99f && bounds.bottom >= 511.99f)
                }
            }
        }
    }

    @Test fun pinchKeepsTheImageUnderItsFocusWhileClampingZoomAndPanning() {
        val first = AvatarCropTransform(zoom = 2f, offsetX = .1f, offsetY = -.1f)
        val point = floatArrayOf(60f, 45f)
        first.matrix(100, 100, 100f).mapPoints(point)
        val next = first.gesture(100, 100, 0f, 0f, 1.5f, point[0] / 100f, point[1] / 100f)
        val after = floatArrayOf(60f, 45f)
        next.matrix(100, 100, 100f).mapPoints(after)
        assertArrayEquals(point, after, .001f)
        assertEquals(5f, next.gesture(100, 100, 50f, 50f, 100f, .5f, .5f).zoom, .001f)
        assertEquals(1f, next.gesture(100, 100, 0f, 0f, .001f, .5f, .5f).zoom, .001f)
    }

    @Test fun storedCropMatchesPreviewRotationAndSelectedSide() {
        val source = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        for (y in 0 until 80) for (x in 0 until 120) source.setPixel(x, y,
            if (y < 40) { if (x < 60) Color.RED else Color.GREEN }
            else { if (x < 60) Color.BLUE else Color.YELLOW })
        try {
            val rotated = renderAvatarCrop(source, AvatarCropTransform(quarterTurns = 1), 80)
            try {
                assertEquals(Color.BLUE, rotated.getPixel(10, 10))
                assertEquals(Color.RED, rotated.getPixel(70, 10))
                assertEquals(Color.YELLOW, rotated.getPixel(10, 70))
                assertEquals(Color.GREEN, rotated.getPixel(70, 70))
            } finally { rotated.recycle() }
            val selectedLeft = renderAvatarCrop(source, AvatarCropTransform(zoom = 2f, offsetX = 1f), 80)
            try { assertEquals(Color.RED, selectedLeft.getPixel(40, 10)) }
            finally { selectedLeft.recycle() }
            assertFalse("Rendering does not consume the editor's source", source.isRecycled)
        } finally { source.recycle() }
    }
}
