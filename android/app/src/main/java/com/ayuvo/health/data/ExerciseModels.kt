package com.ayuvo.health.data

/**
 * Raw record as stored in exercises.json (shared/exercises, generated from
 * hasaneyldrm/exercises-dataset by scripts/import_exercises_dataset.py).
 */
data class ExerciseRecord(
    val id: String = "",
    val name: String = "",
    val bodyPart: String? = null,
    val target: String? = null,
    val equipment: String? = null,
    val secondaryMuscles: List<String> = emptyList(),
    val instructions: List<String> = emptyList(),
    val gifUrl: String? = null,
    val imageUrl: String? = null
)

/**
 * Domain model mirroring the iOS `ExerciseLibraryItem`.
 *
 * Catalogue exercises carry a single target muscle ([primaryMuscles] = `[target]`) and
 * remote media ([imageUrl] thumbnail, [gifUrl] animation). User-created exercises may list
 * several primary muscles and keep their on-device photo filename in [imagePaths].
 */
data class ExerciseItem(
    val id: String,
    val name: String,
    val bodyPart: String,
    val equipment: String,
    val primaryMuscles: List<String>,
    val secondaryMuscles: List<String>,
    val instructions: List<String>,
    val imagePaths: List<String> = emptyList(),
    val imageUrl: String? = null,
    val gifUrl: String? = null
) {
    val target: String
        get() = primaryMuscles.firstOrNull() ?: "Unspecified"

    val isCardio: Boolean
        get() = bodyPart.equals("cardio", ignoreCase = true)

    val primaryMusclesTitle: String
        get() = if (primaryMuscles.isEmpty()) "Unspecified" else primaryMuscles.joinToString(", ")

    val secondaryMusclesTitle: String
        get() = if (secondaryMuscles.isEmpty()) "None" else secondaryMuscles.joinToString(", ")

    /** Lowercased haystack for free-text search (precomputed once). Instructions are excluded. */
    val searchableText: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        buildList {
            add(name)
            add(bodyPart)
            add(equipment)
            add(primaryMuscles.joinToString(" "))
            add(secondaryMuscles.joinToString(" "))
        }.joinToString(" ").lowercase()
    }

    companion object {
        const val MEDIA_ATTRIBUTION = "© Gym visual — https://gymvisual.com/"

        fun from(record: ExerciseRecord): ExerciseItem? {
            val id = record.id.trim()
            val name = record.name.trim()
            if (id.isEmpty() || name.isEmpty()) return null
            return ExerciseItem(
                id = id,
                name = metadataTitle(name),
                bodyPart = metadataTitle(record.bodyPart),
                equipment = metadataTitle(record.equipment),
                primaryMuscles = metadataTitles(listOfNotNull(record.target)),
                secondaryMuscles = metadataTitles(record.secondaryMuscles),
                instructions = record.instructions.map { it.trim() }.filter { it.isNotEmpty() },
                imageUrl = record.imageUrl?.trim()?.takeIf { it.startsWith("https://") },
                gifUrl = record.gifUrl?.trim()?.takeIf { it.startsWith("https://") }
            )
        }

        private fun metadataTitles(values: List<String>): List<String> =
            values.map { metadataTitle(it) }.filter { it != "Unspecified" }

        /** Title-cases a metadata token (splitting on spaces and hyphens), or "Unspecified". */
        fun metadataTitle(value: String?): String {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isEmpty()) return "Unspecified"
            // Split on runs of whitespace (drops empties) to match Swift's split(separator:" ").
            return trimmed.split(Regex("\\s+")).joinToString(" ") { word ->
                word.split("-").joinToString("-") { segment ->
                    if (segment.isEmpty()) {
                        ""
                    } else {
                        segment.first().uppercase() + segment.drop(1).lowercase()
                    }
                }
            }
        }
    }
}

/** Sort options, mirroring iOS `ExerciseLibrarySort`. */
enum class ExerciseSort(val titleRes: Int) {
    NAME(com.ayuvo.health.R.string.label_name),
    BODY_PART(com.ayuvo.health.R.string.label_body_part),
    TARGET(com.ayuvo.health.R.string.label_target),
    SECONDARY(com.ayuvo.health.R.string.label_secondary),
    EQUIPMENT(com.ayuvo.health.R.string.label_equipment)
}
