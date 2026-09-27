package com.ayuvo.health.ui.metrics

import androidx.compose.runtime.Composable
import com.ayuvo.health.AppContainer
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.ui.health.HealthTypeDetailScreen

/** `metric/{key}` (and the `health/type/{id}` alias): app, nutrient and health metrics share one layout. */
@Composable
fun MetricDetailScreen(
    container: AppContainer,
    key: MetricKey,
    onBack: () -> Unit,
    destinations: AppMetricDestinations = AppMetricDestinations()
) {
    when (key) {
        is MetricKey.App -> AppMetricDetailScreen(container, key.id, onBack, destinations)
        is MetricKey.Nutrient -> NutrientMetricDetailScreen(container, key, onBack, destinations)
        is MetricKey.Health -> HealthTypeDetailScreen(container, key.typeId, onBack, destinations)
    }
}
