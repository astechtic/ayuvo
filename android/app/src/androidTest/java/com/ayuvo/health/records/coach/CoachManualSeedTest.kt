package com.ayuvo.health.records.coach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ayuvo.health.AyuvoApp
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Manual-check helper, skipped unless run with `-e coachSeed 1`: writes the Coach test CBCs into the
 * app's real records database so the Coach entry points can be exercised by hand on an emulator.
 */
@RunWith(AndroidJUnit4::class)
class CoachManualSeedTest {
    @Test
    fun seedAppRecords() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("coachSeed") == "1")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AyuvoApp
        app.container.analyteCatalog
        app.container.prefs.setOnboardingCompleted(true)
        CoachRecordsSeed.seed(app.container.recordsStore)
        Unit
    }
}
