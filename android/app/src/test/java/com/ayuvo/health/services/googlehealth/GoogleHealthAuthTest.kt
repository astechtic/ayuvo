package com.ayuvo.health.services.googlehealth

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PKCE, the consent URL and the loopback redirect parsing of the custom-client flow. */
class GoogleHealthAuthTest {

    @Test
    fun codeChallengeMatchesRfc7636AppendixB() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            GoogleHealthAuth.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        )
        val verifier = GoogleHealthAuth.codeVerifier()
        assertTrue(verifier.length in 43..128)
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun authUrlCarriesPkceStateAndOfflineAccess() {
        val map = GoogleHealthTestFiles.map
        val url = GoogleHealthAuth.authUrl(
            map.api.authUrl, "id.apps.googleusercontent.com", "http://127.0.0.1:5555",
            map.scopesFor(listOf("sleep")), "challenge", "st"
        ).toHttpUrl()
        assertEquals("accounts.google.com", url.host)
        assertEquals("http://127.0.0.1:5555", url.queryParameter("redirect_uri"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("challenge", url.queryParameter("code_challenge"))
        assertEquals("st", url.queryParameter("state"))
        assertEquals("offline", url.queryParameter("access_type"))
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("https://www.googleapis.com/auth/googlehealth.sleep.readonly openid email", url.queryParameter("scope"))
    }

    @Test
    fun redirectRequestLineIsParsed() {
        assertEquals(
            mapOf("state" to "a b", "code" to "4/0Ab", "scope" to "openid email"),
            GoogleHealthAuth.parseRedirectRequestLine("GET /?state=a%20b&code=4%2F0Ab&scope=openid+email HTTP/1.1")
        )
        assertEquals(mapOf("error" to "access_denied"), GoogleHealthAuth.parseRedirectRequestLine("GET /?error=access_denied HTTP/1.1"))
        assertNull(GoogleHealthAuth.parseRedirectRequestLine("GET /favicon.ico HTTP/1.1"))
        assertNull(GoogleHealthAuth.parseRedirectRequestLine(""))
    }
}
