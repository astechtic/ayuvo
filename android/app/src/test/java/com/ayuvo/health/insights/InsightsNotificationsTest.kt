package com.ayuvo.health.insights

import com.ayuvo.health.actions.ActionDeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.time.LocalTime
import javax.xml.parsers.DocumentBuilderFactory

/**
 * "Your recovery is ready" / "Your daily review is ready" (docs/insights.md §4): the text never
 * holds a value or a format argument, taps land on the Insights actions, and the morning worker
 * posts at most once per day, only when opted in.
 */
class InsightsNotificationsTest {
    private fun strings(): Map<String, String> {
        val f = listOf("src/main/res/values/strings_insights.xml", "app/src/main/res/values/strings_insights.xml").map(::File).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).documentElement
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { (nodes.item(it) as Element).getAttribute("name") to nodes.item(it).textContent }
    }

    @Test
    fun notificationTextCarriesNoValues() {
        val s = strings()
        for (name in listOf(
            "notif_recovery_ready_title", "notif_recovery_ready_text", "notif_review_ready_title", "notif_review_ready_text",
            "notif_channel_insights", "notif_channel_insights_desc", "notif_channel_daily_review", "notif_channel_daily_review_desc"
        )) {
            val text = s.getValue(name)
            assertFalse("$name has a digit: $text", text.any { it.isDigit() })
            assertFalse("$name has a format argument: $text", text.contains('%'))
        }
        assertEquals("Your recovery is ready", s["notif_recovery_ready_title"])
        assertEquals("Your daily review is ready", s["notif_review_ready_title"])
    }

    @Test
    fun tapsOpenTheInsightsActions() {
        val recovery = ActionDeepLink.parse(InsightsNotifications.RECOVERY_LINK) as ActionDeepLink.Parsed.Ok
        assertEquals("insights.recovery.get", recovery.id)
        val review = ActionDeepLink.parse(InsightsNotifications.REVIEW_LINK) as ActionDeepLink.Parsed.Ok
        assertEquals("insights.dailyReview.get", review.id)
        assertEquals(mapOf("day" to "today"), review.params)
    }

    @Test
    fun morningWindowIsFiveToNoon() {
        assertFalse(InsightsWorker.inMorningWindow(LocalTime.of(4, 59)))
        assertTrue(InsightsWorker.inMorningWindow(LocalTime.of(5, 0)))
        assertTrue(InsightsWorker.inMorningWindow(LocalTime.of(11, 59)))
        assertFalse(InsightsWorker.inMorningWindow(LocalTime.of(12, 0)))
    }

    @Test
    fun morningNotificationPostsOncePerDayOnlyWhenOptedIn() {
        fun post(ready: Boolean = true, morning: Boolean = true, notifs: Boolean = true, canPost: Boolean = true, last: String? = null) =
            InsightsWorker.shouldNotify(ready, morning, notifs, canPost, last, "2026-09-20")
        assertTrue(post())
        assertFalse("default is off", post(morning = false))
        assertFalse(post(ready = false))
        assertFalse(post(notifs = false))
        assertFalse(post(canPost = false))
        assertFalse("already posted today", post(last = "2026-09-20"))
        assertTrue(post(last = "2026-09-19"))
    }
}
