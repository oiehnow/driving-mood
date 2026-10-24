package kr.urbanmood.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.widget.ImageView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.hypot

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LogoCircleGeometryTest {
    @Test fun buttonUsesUnmaskedArtworkInsteadOfDeviceLauncherMask() {
        val context: Context = RuntimeEnvironment.getApplication()
        val action = LogoJourneyView(context).actionView as ImageView
        assertFalse(action.drawable is AdaptiveIconDrawable)
        assertFalse(action.clipToOutline)
    }

    @Test fun headerAndButtonHaveRoundSymmetricEdgesAndTransparentGutter() {
        for (size in listOf(32, 128)) {
            val drawable = LogoJourneyView.createLogoDrawable(RuntimeEnvironment.getApplication())
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
            val radius = size / 2f - 1f
            for (y in 0 until size) for (x in 0 until size) {
                val alpha = Color.alpha(bitmap.getPixel(x, y))
                val distance = hypot(x + .5f - size / 2f, y + .5f - size / 2f)
                if (x == 0 || y == 0 || x == size - 1 || y == size - 1) assertEquals("gutter $size/$x,$y", 0, alpha)
                if (distance < radius - 1f) assertEquals("circle interior $size/$x,$y", 255, alpha)
                if (distance > radius + 1f) assertEquals("outside circle $size/$x,$y", 0, alpha)
            }
            // Skia's subpixel coverage may differ between quadrants. Compare the actual
            // 50%-coverage contour, not exact alpha values on partially covered pixels.
            // A flat/clipped edge still fails the radial checks above or this symmetry.
            for (axis in 0 until size) {
                val row = (0 until size).filter { Color.alpha(bitmap.getPixel(it, axis)) >= 128 }
                val column = (0 until size).filter { Color.alpha(bitmap.getPixel(axis, it)) >= 128 }
                if (row.isNotEmpty()) assertTrue("row contour symmetry $size/$axis: $row",
                    abs(row.first() + row.last() - (size - 1)) <= 1)
                if (column.isNotEmpty()) assertTrue("column contour symmetry $size/$axis: $column",
                    abs(column.first() + column.last() - (size - 1)) <= 1)
                // Each antialiased endpoint may cross 50% coverage in an adjacent pixel;
                // compare spans with at most one pixel per endpoint, not one per span.
                assertTrue("circular row/column extent $size/$axis: row=$row column=$column", abs(row.size - column.size) <= 2)
            }
            bitmap.recycle()
        }
    }
}
