package com.ayuvo.health.ui.health

import com.ayuvo.health.data.PreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.Locale

/** Display-unit preferences for health values, derived from the existing app unit prefs. */
fun healthUnitPrefsFlow(prefs: PreferencesStore): Flow<HealthUnitPrefs> = combine(
    prefs.weightUnit,
    prefs.heightUnit,
    prefs.healthGlucoseUnit,
    prefs.useMetric
) { weight, height, glucose, metric ->
    HealthUnitPrefs(
        metricMass = weight == "kg",
        metricLength = height == "cm",
        glucoseMgDl = when (glucose) {
            "mg/dL" -> true
            "mmol/L" -> false
            else -> HealthValueFormatter.defaultGlucoseMgDl(Locale.getDefault())
        },
        fahrenheit = !metric
    )
}
