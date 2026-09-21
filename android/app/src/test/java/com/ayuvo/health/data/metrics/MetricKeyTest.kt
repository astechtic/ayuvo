package com.ayuvo.health.data.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetricKeyTest {
    @Test
    fun appKeysRoundTrip() {
        for (id in AppMetricId.entries) {
            val key = MetricKey.App(id)
            assertEquals("app:${id.slug}", key.storageId)
            assertEquals(key, MetricKey.parse(key.storageId))
        }
    }

    @Test
    fun healthIdsPassThroughUnprefixed() {
        assertEquals(MetricKey.Health("steps"), MetricKey.parse("steps"))
        assertEquals("heart_rate", MetricKey.Health("heart_rate").storageId)
        assertEquals(MetricKey.Health("HKQuantityTypeIdentifierX"), MetricKey.parse(" HKQuantityTypeIdentifierX "))
    }

    @Test
    fun unknownAppKeysAndBlanksAreRejected() {
        assertNull(MetricKey.parse("app:nope"))
        assertNull(MetricKey.parse(""))
        assertNull(MetricKey.parse("   "))
    }
}
