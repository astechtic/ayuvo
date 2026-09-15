package com.ayuvo.health.records.processing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bundled assets/records files must equal shared/records files byte for byte. */
class RecordsAssetsContractTest {
    private fun compare(name: String, required: Boolean) {
        val shared = RecordsTestFiles.shared(name)
        if (shared == null) {
            assertTrue("shared/records/$name missing", !required)
            return
        }
        val asset = RecordsTestFiles.asset(name)
        assertTrue("assets/records/$name missing (copy shared/records/$name)", asset != null)
        assertArrayEquals("assets/records/$name differs from shared/records/$name", shared.readBytes(), asset!!.readBytes())
    }

    @Test fun recordTypes() = compare("record_types.json", required = true)
    @Test fun units() = compare("units.json", required = true)
    @Test fun analytes() = compare("analytes.json", required = true)
    @Test fun coachTools() = compare("coach_tools.json", required = true)
}
