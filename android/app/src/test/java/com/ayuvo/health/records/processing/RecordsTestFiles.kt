package com.ayuvo.health.records.processing

import java.io.File

/** Locates shared contract files from the Gradle unit-test working directory (android/app). */
object RecordsTestFiles {
    fun shared(relative: String): File? =
        listOf("../../shared/records/$relative", "../shared/records/$relative", "shared/records/$relative")
            .map(::File).firstOrNull { it.exists() }

    fun asset(relative: String): File? =
        listOf("src/main/assets/records/$relative", "app/src/main/assets/records/$relative", "android/app/src/main/assets/records/$relative")
            .map(::File).firstOrNull { it.exists() }

    val config: RecordTypesConfig by lazy {
        RecordTypesConfig.parse((shared("record_types.json") ?: asset("record_types.json")!!).readText())
    }
}
