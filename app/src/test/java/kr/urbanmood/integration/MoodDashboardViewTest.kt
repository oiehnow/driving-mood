package kr.urbanmood.integration

import kr.urbanmood.ui.MoodGradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import kr.urbanmood.contracts.*
import kr.urbanmood.ui.MoodDashboardView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MoodDashboardViewTest {
    @Test fun progressStopsOnCompletionAndMoodChangesColor() {
        val view = MoodDashboardView(RuntimeEnvironment.getApplication())
        val progress = views(view).filterIsInstance<ProgressBar>().single()
        view.working("분석 중", "장면 확인")
        assertEquals(View.VISIBLE, progress.visibility)
        view.complete(RecommendationResult(Availability.AVAILABLE, commonMoodKeywords = listOf("따뜻한")), false)
        val warm = (view.getChildAt(0).background as MoodGradientDrawable).palette.toList()
        assertEquals(View.GONE, progress.visibility)
        assertTrue(views(view).filterIsInstance<TextView>().any { it.text == "따뜻한" })
        view.complete(RecommendationResult(Availability.AVAILABLE, commonMoodKeywords = listOf("차가운")), false)
        assertNotEquals(warm, (view.getChildAt(0).background as MoodGradientDrawable).palette.toList())
        view.working("촬영 중", "1 / 30초")
        view.interrupted()
        assertEquals(View.GONE, progress.visibility)
        assertTrue(views(view).filterIsInstance<TextView>().any { it.text == "잠시 멈췄어요" })
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
