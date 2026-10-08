package com.ayuvo.health.partner

import com.ayuvo.health.partner.identity.DeviceIdentity
import com.ayuvo.health.partner.identity.PartnerSecretStore
import com.ayuvo.health.partner.logic.PartnerKdf
import com.ayuvo.health.partner.logic.PartnerQr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityTest {
    private class MemorySecrets : PartnerSecretStore {
        val map = HashMap<String, String>()
        override fun load(key: String) = map[key]
        override fun save(key: String, value: String) { map[key] = value }
    }

    @Test
    fun createdLazilyPersistedAndStable() {
        PartnerTestFiles.install()
        val secrets = MemorySecrets()
        val a = DeviceIdentity(secrets)
        assertTrue("nothing is created before first use", secrets.map.isEmpty())
        val id = a.deviceId
        assertTrue(PartnerQr.RE_UUID.matches(id))
        assertEquals(32, PartnerKdf.b64urlDecode(a.x25519Public)!!.size)
        assertEquals(32, PartnerKdf.b64urlDecode(a.ed25519Public)!!.size)
        assertEquals(32, a.fingerprint.length)
        val b = DeviceIdentity(secrets)
        assertEquals(id, b.deviceId)
        assertEquals(a.x25519Public, b.x25519Public)
        assertEquals(a.ed25519Public, b.ed25519Public)
        assertNotEquals(id, DeviceIdentity(MemorySecrets()).deviceId)
    }

    @Test
    fun signAndVerify() {
        PartnerTestFiles.install()
        val me = DeviceIdentity(MemorySecrets())
        val msg = "{\"format\":\"ayuvo-partner-sync\"}".toByteArray()
        val sig = me.sign(msg)
        assertEquals(64, sig.size)
        assertTrue(DeviceIdentity.verify(me.ed25519Public, msg, sig))
        assertFalse(DeviceIdentity.verify(me.ed25519Public, msg + 0x20, sig))
        assertFalse(DeviceIdentity.verify(DeviceIdentity(MemorySecrets()).ed25519Public, msg, sig))
        assertFalse(DeviceIdentity.verify("not-a-key", msg, sig))
    }
}
