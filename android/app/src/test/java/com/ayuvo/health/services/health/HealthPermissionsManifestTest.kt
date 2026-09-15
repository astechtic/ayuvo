package com.ayuvo.health.services.health

import com.ayuvo.health.models.HealthDataType
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every readable registry type must be declared in the manifest, or Health Connect hides it. */
class HealthPermissionsManifestTest {
    @Test
    fun everySdkTypePermissionIsDeclared() {
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml", "android/app/src/main/AndroidManifest.xml")
            .map(::File).firstOrNull { it.exists() }
            ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")
        val text = manifest.readText()
        val missing = HealthDataType.sdkTypes
            .mapNotNull { it.hcPermission }
            .distinct()
            .filter { !text.contains("android:name=\"$it\"") }
        assertTrue("manifest lacks: $missing", missing.isEmpty())
        assertTrue(text.contains("android:name=\"${HealthHubPermissions.HISTORY}\""))
    }
}
