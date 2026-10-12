package com.crabot.voice

import android.content.ComponentName
import android.content.pm.ActivityInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeyapps.barcodescanner.CaptureActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only package check: safe on the human phone, with no storage or microphone access. */
@RunWith(AndroidJUnit4::class)
class PairingUiTest {
    @Test fun scanningAndReturningCannotForceLandscape() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (activity in listOf(MainActivity::class.java, CaptureActivity::class.java)) {
            val info = context.packageManager.getActivityInfo(ComponentName(context, activity), 0)
            assertEquals(activity.name, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, info.screenOrientation)
        }
    }
}
