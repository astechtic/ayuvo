package com.ayuvo.health.ui.metrics

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.BakeryDining
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Egg
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stairs
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.ui.graphics.vector.ImageVector

/** Catalog `icon.android` names → vectors. Every name in the catalog must resolve (MetricCatalogContractTest). */
object MetricIcons {
    private val icons: Map<String, ImageVector> by lazy {
        mapOf(
            "AutoMirrored.Filled.DirectionsWalk" to Icons.AutoMirrored.Filled.DirectionsWalk,
            "Filled.Accessibility" to Icons.Filled.Accessibility,
            "Filled.Air" to Icons.Filled.Air,
            "Filled.BakeryDining" to Icons.Filled.BakeryDining,
            "Filled.Bedtime" to Icons.Filled.Bedtime,
            "Filled.Description" to Icons.Filled.Description,
            "Filled.Egg" to Icons.Filled.Egg,
            "Filled.Favorite" to Icons.Filled.Favorite,
            "Filled.FitnessCenter" to Icons.Filled.FitnessCenter,
            "Filled.Grass" to Icons.Filled.Grass,
            "Filled.HealthAndSafety" to Icons.Filled.HealthAndSafety,
            "Filled.Hearing" to Icons.Filled.Hearing,
            "Filled.LocalFireDepartment" to Icons.Filled.LocalFireDepartment,
            "Filled.Loop" to Icons.Filled.Loop,
            "Filled.Medication" to Icons.Filled.Medication,
            "Filled.MonitorHeart" to Icons.Filled.MonitorHeart,
            "Filled.MonitorWeight" to Icons.Filled.MonitorWeight,
            "Filled.MoreHoriz" to Icons.Filled.MoreHoriz,
            "Filled.Opacity" to Icons.Filled.Opacity,
            "Filled.Percent" to Icons.Filled.Percent,
            "Filled.Restaurant" to Icons.Filled.Restaurant,
            "Filled.Schedule" to Icons.Filled.Schedule,
            "Filled.SelfImprovement" to Icons.Filled.SelfImprovement,
            "Filled.Timer" to Icons.Filled.Timer,
            "Filled.Bloodtype" to Icons.Filled.Bloodtype,
            "Filled.Bolt" to Icons.Filled.Bolt,
            "Filled.Calculate" to Icons.Filled.Calculate,
            "Filled.Height" to Icons.Filled.Height,
            "Filled.Speed" to Icons.Filled.Speed,
            "Filled.Stairs" to Icons.Filled.Stairs,
            "Filled.Straighten" to Icons.Filled.Straighten,
            "Filled.Thermostat" to Icons.Filled.Thermostat,
            "Filled.WaterDrop" to Icons.Filled.WaterDrop
        )
    }

    val names: Set<String> get() = icons.keys

    fun byName(name: String): ImageVector? = icons[name]

    fun byNameOrDefault(name: String): ImageVector = icons[name] ?: Icons.Filled.MoreHoriz
}
