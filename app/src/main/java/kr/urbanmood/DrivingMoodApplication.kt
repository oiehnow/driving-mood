package kr.urbanmood

import android.app.Application
import android.content.Context
import android.system.Os

/** Runs before library ContentProviders. No ORT classes are referenced here. */
class DrivingMoodApplication : Application() {
    override fun attachBaseContext(base: Context) {
        Os.setenv("ORT_DISABLE_TELEMETRY", "1", true)
        check(Os.getenv("ORT_DISABLE_TELEMETRY") == "1")
        super.attachBaseContext(base)
    }
}

