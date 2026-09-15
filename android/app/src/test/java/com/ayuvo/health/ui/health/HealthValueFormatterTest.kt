package com.ayuvo.health.ui.health

import com.ayuvo.health.models.HealthDataType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class HealthValueFormatterTest {
    private val metric = HealthUnitPrefs()
    private val imperial = HealthUnitPrefs(metricMass = false, metricLength = false, glucoseMgDl = true, fahrenheit = true)

    @Test
    fun countsAndEnergyAreGroupedIntegers() {
        assertEquals("12,345", HealthValueFormatter.format("steps", 12_345.4).text)
        assertEquals("512 kcal", HealthValueFormatter.format("active_energy", 511.6).text)
        assertEquals("1,650 kcal/day", HealthValueFormatter.format("basal_metabolic_rate", 1650.0).text)
    }

    @Test
    fun massAndLengthFollowPreferences() {
        assertEquals("80.4 kg", HealthValueFormatter.format("weight", 80.4, metric).text)
        assertEquals("177.3 lb", HealthValueFormatter.format("weight", 80.4, imperial).text)
        assertEquals("178 cm", HealthValueFormatter.format("height", 1.78, metric).text)
        assertEquals("5 ft 10 in", HealthValueFormatter.format("height", 1.78, imperial).text)
        assertEquals("3.25 km", HealthValueFormatter.format("distance", 3250.0, metric).text)
        assertEquals("850 m", HealthValueFormatter.format("distance", 850.0, metric).text)
        assertEquals("2.02 mi", HealthValueFormatter.format("distance", 3250.0, imperial).text)
    }

    @Test
    fun glucoseTemperatureAndPercent() {
        assertEquals("5.6 mmol/L", HealthValueFormatter.format("blood_glucose", 5.6, metric).text)
        assertEquals("101 mg/dL", HealthValueFormatter.format("blood_glucose", 5.6, imperial).text)
        assertEquals("36.6 °C", HealthValueFormatter.format("body_temperature", 36.6, metric).text)
        assertEquals("97.9 °F", HealthValueFormatter.format("body_temperature", 36.6, imperial).text)
        assertEquals("23.5 %", HealthValueFormatter.format("body_fat", 23.5).text)
        assertEquals("97 %", HealthValueFormatter.format("blood_oxygen", 97.4).text)
    }

    @Test
    fun heartUnitsDurationsAndBloodPressure() {
        assertEquals("72 bpm", HealthValueFormatter.format("heart_rate", 72.3).text)
        assertEquals("16 /min", HealthValueFormatter.format("respiratory_rate", 16.0).text)
        assertEquals("42 ms", HealthValueFormatter.format("hrv_rmssd", 42.4).text)
        assertEquals("7h 32m", HealthValueFormatter.format("sleep", 7 * 3600.0 + 32 * 60).text)
        assertEquals("45 min", HealthValueFormatter.duration(2700.0))
        assertEquals("2h", HealthValueFormatter.duration(7200.0))
        assertEquals("30 s", HealthValueFormatter.duration(30.0))
        assertEquals("121/79 mmHg", HealthValueFormatter.formatBloodPressure(121.0, 79.0).text)
        assertEquals("1.25 L", HealthValueFormatter.format("hydration", 1250.0).text)
        assertEquals("250 mL", HealthValueFormatter.format("hydration", 250.0).text)
    }

    @Test
    fun categoryLabelsAndMissingValues() {
        assertEquals("—", HealthValueFormatter.format("steps", null).text)
        assertEquals("deep", HealthValueFormatter.categoryLabel(HealthDataType.SLEEP.id, 4))
        assertEquals("heavy", HealthValueFormatter.categoryLabel(HealthDataType.MENSTRUAL_FLOW.id, 4))
        assertEquals("sitting down", HealthValueFormatter.categoryLabel(HealthDataType.BLOOD_PRESSURE.id, 2))
        assertEquals("9", HealthValueFormatter.categoryLabel("unknown_type", 9))
    }

    @Test
    fun decimalsTrimTrailingZerosPerLocale() {
        assertEquals("80", HealthValueFormatter.decimal(80.0, 1))
        assertEquals("80.5", HealthValueFormatter.decimal(80.5, 1))
        assertEquals("1.234,5", HealthValueFormatter.decimal(1234.5, 1, Locale.GERMANY))
        assertEquals("1,5 kg", HealthValueFormatter.format("weight", 1.5, metric, Locale.GERMANY).text)
        assertEquals("2.5 MB", HealthValueFormatter.bytes(2_621_440L))
        assertEquals("512 KB", HealthValueFormatter.bytes(524_288L))
    }
}
