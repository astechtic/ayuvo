package com.ayuvo.health.services.googlehealth

import java.io.File

/** The shared Google Health contract files, read straight from `shared/` (working dir is `android/app`). */
object GoogleHealthTestFiles {
    fun shared(path: String): File? =
        listOf("../../shared/health/$path", "../shared/health/$path", "shared/health/$path").map(::File).firstOrNull { it.exists() }

    val map: GoogleHealthMap by lazy { GoogleHealthMap.parse(shared("google_health_map.json")!!.readText()) }
}
