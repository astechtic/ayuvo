package com.ayuvo.health.data

/** Tokenized AND search for the exercise library, with optional query aliases. */
object ExerciseSearch {
    // Ids from shared/exercises/exercises.json ("0201" = cable pushdown).
    private val aliases = mapOf(
        "cable pushdown" to "0201",
        "triceps cable pushdown" to "0201",
        "tricep cable pushdown" to "0201",
        "tricep pushdown" to "0201",
    )

    fun tokens(query: String): List<String> =
        query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun matches(searchableText: String, query: String, exerciseId: String): Boolean {
        val normalizedQuery = query.trim().lowercase()
        val queryTokens = tokens(normalizedQuery)
        if (queryTokens.isEmpty()) return true

        aliases[normalizedQuery]?.let { aliasId ->
            if (aliasId == exerciseId) return true
        }

        return queryTokens.all { searchableText.contains(it) }
    }
}
