package com.ayuvo.health.partner

import com.ayuvo.health.partner.data.Partner
import com.ayuvo.health.partner.logic.PartnerJson
import com.ayuvo.health.partner.logic.ReceivedGrant
import com.ayuvo.health.partner.logic.StoredRecord
import com.ayuvo.health.ui.partner.PartnerCategory
import com.ayuvo.health.ui.partner.PartnerDashboardBuilder
import com.ayuvo.health.ui.partner.PartnerTrend
import com.ayuvo.health.ui.partner.PartnerVital
import com.ayuvo.health.ui.partner.ReceivedShare
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The partner dashboard shows stored rows only: nothing is estimated, missing metrics are left out. */
class PartnerDashboardBuilderTest {
    private val today = LocalDate.parse("2026-10-08")
    private val partner = Partner("p1", "Ananya", "5cbe8b6c1a846a49e8e550ecf82c3931", "x", "e", "android", 0, updatedMs = 0)

    private fun rec(type: String, id: String, day: String?, data: String) =
        StoredRecord(type, id, day, null, PartnerJson.parse(data) as JsonObject)

    private val rows = listOf(
        rec("metric_day", "resting_heart_rate:2026-10-07", "2026-10-07", """{"type_id":"resting_heart_rate","day":"2026-10-07","unit":"bpm","avg":58}"""),
        rec("metric_day", "steps:2026-10-07", "2026-10-07", """{"type_id":"steps","day":"2026-10-07","unit":"count","sum":8421}"""),
        rec("analytics_day", "recovery_indicator:2026-10-07", "2026-10-07", """{"metric_id":"recovery_indicator","day":"2026-10-07","status":"ok","classification":"good","value":81.6}"""),
        rec("sleep_night", "2026-10-07", "2026-10-07", """{"day":"2026-10-07","start_ms":1,"end_ms":2,"asleep_min":444,"deep_min":70}"""),
        rec("food_entry", "f1", "2026-10-08", """{"name":"Oats","logged_ms":5,"calories":320,"protein_g":12.5}"""),
        rec("medication", "m1", null, """{"name":"Vitamin D3","form":"capsule","dose_quantity":1,"dose_unit":"capsule","start_date":"2026-09-01","status":"active"}"""),
        rec("dose_log", "d1", "2026-10-08", """{"medication_id":"m1","scheduled_at_ms":10,"status":"taken","dose_quantity":1,"dose_unit":"capsule","note":"with food"}"""),
        rec("report_overview", "r1", "2026-10-01", """{"title":"CBC","record_type":"lab_report","category":"blood","abnormal":[{"name":"Hemoglobin","value":"11.2","flag":"low"}],"results":[{"name":"Hemoglobin","value":"11.2","flag":"low"},{"name":"B12","value":"320","flag":"normal"}]}""")
    )

    private val grants = listOf(
        ReceivedGrant("vitals", true, null), ReceivedGrant("sleep", true, null), ReceivedGrant("nutrition", true, null),
        ReceivedGrant("workouts", false, 123L), ReceivedGrant("medicines", true, null), ReceivedGrant("report_overviews", true, null)
    )

    @Test
    fun buildsOnlyFromStoredRows() {
        val d = PartnerDashboardBuilder.build(partner, null, grants, rows, today)
        assertEquals(81.6, d.recovery!!.value, 0.0)
        assertEquals(444.0, d.sleep!!.asleepMin, 0.0)
        assertEquals(listOf(PartnerVital.RESTING_HR), d.vitals.map { it.vital })
        assertEquals(320.0, d.macros!!.calories, 0.0)
        assertNull(d.waterMl)
        assertTrue(d.workouts.isEmpty())
        assertEquals("Vitamin D3", d.doses.single().medicationName)
        assertEquals("with food", d.doses.single().note)
        val report = d.reports.single()
        assertEquals(1, report.abnormal.size)
        assertEquals(2, report.results.size)
        val steps = d.trends.first { it.trend == PartnerTrend.STEPS }
        assertEquals(30, steps.values.size)
        assertEquals(1, steps.values.count { it != null })
        assertEquals(8421.0, steps.latest(7)!!, 0.0)
        assertTrue(d.trends.first { it.trend == PartnerTrend.WEIGHT }.values.all { it == null })
    }

    @Test
    fun grantStatesDriveSections() {
        val d = PartnerDashboardBuilder.build(partner, null, grants.dropLast(1), rows, today)
        assertEquals(ReceivedShare.REVOKED, d.share(PartnerCategory.WORKOUTS))
        assertEquals(ReceivedShare.NEVER, d.share(PartnerCategory.REPORTS))
        assertEquals(ReceivedShare.SHARED, d.share(PartnerCategory.VITALS))
    }
}
