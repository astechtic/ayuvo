package com.ayuvo.health.services.googlehealth

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** `dataPoints.list` over a local MockWebServer: pagination, 401 refresh, 429 backoff, scopes and optional types. */
class GoogleHealthClientTest {
    private val map = GoogleHealthTestFiles.map
    private lateinit var server: MockWebServer
    private val sleeps = mutableListOf<Long>()
    private val tokenCalls = mutableListOf<Boolean>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = GoogleHealthClient(
        map = map,
        tokens = GoogleHealthTokenProvider { force -> tokenCalls += force; if (force) "fresh" else "stale" },
        http = OkHttpClient(),
        baseUrl = server.url("/v4").toString(),
        sleep = { sleeps += it }
    )

    private fun page(ids: List<String>, next: String?): String {
        val points = ids.joinToString(",") { """{"name":"users/me/dataTypes/steps/dataPoints/$it"}""" }
        return """{"dataPoints":[$points]${next?.let { ""","nextPageToken":"$it"""" } ?: ""}}"""
    }

    @Test
    fun followsPageTokensAndSendsTheFilter() = runBlocking {
        server.enqueue(MockResponse().setBody(page(listOf("a", "b"), "p2")))
        server.enqueue(MockResponse().setBody(page(listOf("c"), null)))
        val steps = map.type("steps")!!
        val c = client()
        val first = c.listDataPoints(steps, "steps.interval.civil_start_time >= \"2026-07-03T05:30:00\"", null, 10_000)
        assertEquals(2, first.points.size)
        assertEquals("p2", first.nextPageToken)
        val second = c.listDataPoints(steps, "f", first.nextPageToken, 10_000)
        assertEquals(1, second.points.size)
        assertNull(second.nextPageToken)

        val r1 = server.takeRequest()
        assertEquals("/v4/users/me/dataTypes/steps/dataPoints", r1.requestUrl!!.encodedPath)
        assertEquals("steps.interval.civil_start_time >= \"2026-07-03T05:30:00\"", r1.requestUrl!!.queryParameter("filter"))
        assertEquals("10000", r1.requestUrl!!.queryParameter("pageSize"))
        assertNull(r1.requestUrl!!.queryParameter("pageToken"))
        assertEquals("Bearer stale", r1.getHeader("Authorization"))
        assertEquals("p2", server.takeRequest().requestUrl!!.queryParameter("pageToken"))
    }

    @Test
    fun unauthorizedRefreshesTheTokenOnceThenRetries() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody(page(listOf("a"), null)))
        val result = client().listDataPoints(map.type("steps")!!, "f", null, 10)
        assertEquals(1, result.points.size)
        assertEquals(listOf(false, true), tokenCalls)
        server.takeRequest()
        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun secondUnauthorizedNeedsReconnect() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            client().listDataPoints(map.type("steps")!!, "f", null, 10)
            fail("expected Auth")
        } catch (_: GoogleHealthException.Auth) {
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun rateLimitBacksOffHonouringRetryAfter() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7"))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody(page(listOf("a"), null)))
        val result = client().listDataPoints(map.type("steps")!!, "f", null, 10)
        assertEquals(1, result.points.size)
        assertEquals(listOf(7_000L, 2_000L), sleeps)
    }

    @Test
    fun persistentServerErrorsGiveUp() = runBlocking {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(500)) }
        try {
            client().listDataPoints(map.type("steps")!!, "f", null, 10)
            fail("expected Http")
        } catch (e: GoogleHealthException.Http) {
            assertEquals(500, e.code)
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun forbiddenIsAScopeError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        try {
            client().listDataPoints(map.type("sleep")!!, "f", null, 25)
            fail("expected Scope")
        } catch (_: GoogleHealthException.Scope) {
        }
    }

    @Test
    fun missingOptionalTypeIsUnsupportedButRequiredTypeIsAnError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        val optional = map.type("height")!!
        assertTrue(optional.optional)
        try {
            client().listDataPoints(optional, "f", null, 10)
            fail("expected Unsupported")
        } catch (e: GoogleHealthException.Unsupported) {
            assertEquals(404, e.code)
        }
        try {
            client().listDataPoints(map.type("steps")!!, "f", null, 10)
            fail("expected Http")
        } catch (e: GoogleHealthException.Http) {
            assertEquals(404, e.code)
        }
    }

    @Test
    fun emptyBodyIsAnEmptyLastPage() {
        val page = GoogleHealthClient.parsePage("{}")
        assertTrue(page.points.isEmpty())
        assertNull(page.nextPageToken)
    }
}
