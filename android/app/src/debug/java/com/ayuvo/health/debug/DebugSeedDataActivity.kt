package com.ayuvo.health.debug

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.ayuvo.health.AyuvoApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ADB-only entry point that is compiled into the debug APK and absent from release builds. */
class DebugSeedDataActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val app = application as AyuvoApp
                    // `--ez health true` also writes demo sleep / steps / heart rate to the local health mirror.
                    if (intent.getBooleanExtra("health", false)) {
                        Log.i(TAG, "Demo health rows: ${DebugHealthSeeder(app).seed()}")
                    }
                    DebugDemoDataSeeder(application = app).seed(futureDays = intent.getIntExtra("future_days", 0))
                }
            }.onSuccess { report ->
                Log.i(TAG, "Demo seed complete: $report")
                Toast.makeText(
                    this@DebugSeedDataActivity,
                    "Debug demo data ready (${report.foodEntries} food logs, through ${report.throughDate})",
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure { error ->
                Log.e(TAG, "Demo seed failed", error)
                Toast.makeText(
                    this@DebugSeedDataActivity,
                    "Debug seed failed: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
            finish()
        }
    }

    private companion object {
        const val TAG = "AyuvoDebugSeeder"
    }
}
