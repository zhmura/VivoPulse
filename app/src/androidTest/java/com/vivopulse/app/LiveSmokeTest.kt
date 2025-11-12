package com.vivopulse.app

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveSmokeTest {

    @Test
    fun liveDevice_smoke() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = ctx.packageManager
        val hasFront = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
        val hasBack = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA)
        // Basic capability check to run in CI without real camera
        assertTrue("Device should have at least one camera feature (front or back)", hasFront || hasBack)
    }
}


