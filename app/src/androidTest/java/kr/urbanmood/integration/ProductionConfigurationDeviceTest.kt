package kr.urbanmood.integration

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.ListView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kr.urbanmood.MainActivity
import kr.urbanmood.vision.SemanticLightAnalyzer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the shipped default and real notice menu after an update installation. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ProductionConfigurationDeviceTest {
    @Test fun identityDefaultObserverAndLicenseMenuAreProductionReady() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("kr.urbanmood",context.packageName)
        assertEquals("Driving Mood",context.applicationInfo.loadLabel(context.packageManager).toString())
        assertEquals("0.3.3",context.packageManager.getPackageInfo(context.packageName,0).versionName)
        assertEquals(11L,context.packageManager.getPackageInfo(context.packageName,0).longVersionCode)
        assertEquals("1",android.system.Os.getenv("ORT_DISABLE_TELEMETRY"))
        assertEquals("1",System.getenv("ORT_DISABLE_TELEMETRY"))
        val services=AppServices(context)
        try {
            assertTrue(services.vision is SemanticLightAnalyzer)
            val bitmap=android.graphics.Bitmap.createBitmap(32,32,android.graphics.Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.CYAN)
                val result=services.vision.analyze(kr.urbanmood.contracts.CameraFrame(bitmap,1000L,System.nanoTime(),0L))
                val sky=result.sceneObservations?.semantic?.skyColor
                assertNotNull("Actual AppServices must connect the sky calculator",sky)
                assertEquals("sky-rgb-v1",sky!!.measurementVersion)
                assertEquals(32,sky.rasterWidth)
            } finally { bitmap.recycle() }
        }
        finally { services.vision.close(); services.environment.close(); services.audio.close() }
        val required=listOf("Skia-LICENSE.txt","SKY-OBSERVATION-SOURCES.txt","DrivingMood-AGPL-3.0.txt","SEMANTIC-SOURCES.txt",
            "onnxruntime-LICENSE.txt","onnxruntime-ThirdPartyNotices.txt","ultralytics-LICENSE.txt")
        required.forEach { name -> context.assets.open("licenses/$name").use { assertTrue(it.available()>0) } }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val buttons=views(activity.window.decorView).filterIsInstance<Button>().toList()
                assertFalse(buttons.any { it.text.contains("개발 분석기") || it.text.contains("Dev") })
                val session=MainActivity::class.java.getDeclaredField("session").apply { isAccessible=true }.get(activity) as SessionState
                assertNull(session.activeRequestId)
                buttons.single { it.text=="오픈소스 라이선스" }.performClick()
                val list=WindowInspector.getGlobalWindowViews().asSequence().flatMap(::views).filterIsInstance<ListView>().first()
                val entries=(0 until list.adapter.count).map { list.adapter.getItem(it).toString() }
                required.forEach { assertTrue("Missing notice $it",entries.contains("licenses/$it")) }
                assertFalse(entries.any { it.contains("CANDIDATE-SOURCES") })
            }
        }
    }
    private fun views(view:View):Sequence<View> = sequence {
        yield(view)
        if(view is ViewGroup) for(i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
}
