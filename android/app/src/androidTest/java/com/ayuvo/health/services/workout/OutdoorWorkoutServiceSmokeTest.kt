package com.ayuvo.health.services.workout

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ayuvo.health.AyuvoApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runtime smoke test of the GPS recorder (docs/workouts-gps.md §4): the real foreground service, fed by mock
 * locations moving north at 3 m/s, paused, resumed, lapped and ended, must save one GPS diary session whose
 * distance matches the moving time.
 */
@RunWith(AndroidJUnit4::class)
class OutdoorWorkoutServiceSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val lm = context.getSystemService(LocationManager::class.java)
    private val providers = buildList {
        add(LocationManager.GPS_PROVIDER)
        if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
    }

    private var lat = 19.0700
    private val lon = 72.8700

    private fun shell(cmd: String) {
        instrumentation.uiAutomation.executeShellCommand(cmd).close()
    }

    private fun setUpMockLocation() {
        val ui = instrumentation.uiAutomation
        ui.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        ui.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) ui.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        shell("appops set ${context.packageName} android:mock_location allow")
        SystemClock.sleep(500)
        for (p in providers) {
            runCatching { lm.removeTestProvider(p) }
            lm.addTestProvider(p, false, false, false, false, true, true, true,
                ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE)
            lm.setTestProviderEnabled(p, true)
        }
    }

    /** Pushes one fix per second for [seconds], moving north at [speed] m/s. */
    private fun move(seconds: Int, mps: Double) {
        repeat(seconds) {
            lat += mps / 111_195.0
            for (p in providers) {
                val loc = Location(p).apply {
                    latitude = lat
                    longitude = lon
                    altitude = 10.0
                    accuracy = 5f
                    speed = mps.toFloat()
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                lm.setTestProviderLocation(p, loc)
            }
            SystemClock.sleep(1_000)
        }
    }

    private fun command(action: String, sport: String? = null) {
        val intent = Intent(context, OutdoorWorkoutService::class.java).setAction(action)
        if (sport != null) intent.putExtra(OutdoorWorkoutService.EXTRA_SPORT, sport)
        if (action == OutdoorWorkoutService.ACTION_START) context.startForegroundService(intent) else context.startService(intent)
    }

    @After
    fun tearDown() {
        for (p in providers) runCatching { lm.removeTestProvider(p) }
        shell("appops set ${context.packageName} android:mock_location deny")
    }

    @Test
    fun recordsPausesAndSavesAGpsWalk() = runBlocking {
        setUpMockLocation()
        val repo = (context.applicationContext as AyuvoApp).container.workoutRepository
        val before = repo.gpsSessions.first().map { it.id }.toSet()

        command(OutdoorWorkoutService.ACTION_START, "walk")
        move(5, 1.3) // first fixes
        move(40, 1.3)
        val live = OutdoorWorkoutService.live.value
        assertNotNull("service publishes live state", live)
        assertTrue("distance while walking: ${live!!.distanceM}", live.distanceM in 35.0..70.0)

        command(OutdoorWorkoutService.ACTION_PAUSE)
        SystemClock.sleep(1_500)
        val pausedAt = OutdoorWorkoutService.live.value!!.distanceM
        move(10, 1.3) // moving while paused must not count
        SystemClock.sleep(2_500)
        assertEquals("paused distance holds", pausedAt, OutdoorWorkoutService.live.value!!.distanceM, 2.0)

        command(OutdoorWorkoutService.ACTION_RESUME)
        command(OutdoorWorkoutService.ACTION_LAP)
        move(30, 1.3)
        command(OutdoorWorkoutService.ACTION_END)
        SystemClock.sleep(2_000)
        command(OutdoorWorkoutService.ACTION_SKIP_RECOVERY)

        var saved: com.ayuvo.health.models.WorkoutSession? = null
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (saved == null && SystemClock.elapsedRealtime() < deadline) {
            saved = repo.gpsSessions.first().firstOrNull { it.id !in before }
            if (saved == null) SystemClock.sleep(1_000)
        }
        assertNotNull("a GPS session is saved to the diary", saved)
        val gps = saved!!.gps!!
        val expected = 1.3 * gps.movingSeconds
        assertTrue("distance ${gps.distanceM} vs 1.3 m/s × ${gps.movingSeconds}s", kotlin.math.abs(gps.distanceM - expected) <= 0.2 * expected + 5)
        assertTrue("real interval", saved.completedAt.isAfter(saved.startedAt))
        assertTrue("one lap recorded: ${gps.laps.size}", gps.laps.size >= 1)
        assertNotNull("route stored", (context.applicationContext as AyuvoApp).container.gpsTrackStore.load(saved.id.toString()))
        repo.deleteSession(saved.id)
    }
}
