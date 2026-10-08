package com.ayuvo.health.partner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ayuvo.health.partner.logic.PartnerCatalog
import com.ayuvo.health.partner.net.Endpoint
import com.ayuvo.health.partner.net.PartnerNsd
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket

/** `_ayuvo-partner._tcp` advertise → browse → resolve on one device (docs/partner-sync.md §11). */
@RunWith(AndroidJUnit4::class)
class PartnerNsdTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun advertisedServiceIsResolved() = runBlocking {
        PartnerCatalog.install(context)
        ServerSocket(0).use { server ->
            val advertiser = PartnerNsd(context, this)
            val browser = PartnerNsd(context, this)
            assertNotEquals(advertiser.instanceName, browser.instanceName)
            val found = CompletableDeferred<Endpoint>()
            try {
                advertiser.advertise(server.localPort)
                browser.browse { if (it.port == server.localPort) found.complete(it) }
                val ep = withTimeoutOrNull(20_000) { found.await() }
                assertNotNull("advertised port ${server.localPort} not resolved (nsd failed: ${advertiser.failed}/${browser.failed})", ep)
            } finally {
                browser.close()
                advertiser.close()
            }
        }
    }
}
