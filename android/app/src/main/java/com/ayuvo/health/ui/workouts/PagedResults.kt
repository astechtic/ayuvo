package com.ayuvo.health.ui.workouts

/**
 * Incremental rendering window over an in-memory result list, mirroring iOS `PagedResults`.
 * Filtering produces [all]; lists render only [visible] and grow it by [pageSize] once a
 * row within [prefetchThreshold] of the end appears.
 */
data class PagedResults<T>(
    val all: List<T> = emptyList(),
    val visibleCount: Int = minOf(PAGE_SIZE, all.size),
    val pageSize: Int = PAGE_SIZE,
    val prefetchThreshold: Int = PREFETCH_THRESHOLD
) {
    val visible: List<T> get() = all.subList(0, visibleCount.coerceIn(0, all.size))

    val hasMore: Boolean get() = visibleCount < all.size

    /** Next window when [index] (position in [visible]) is close enough to the end; else this. */
    fun loadingNextPageIfNeeded(index: Int): PagedResults<T> {
        if (!hasMore || index < visibleCount - prefetchThreshold) return this
        return copy(visibleCount = minOf(visibleCount + pageSize, all.size))
    }

    companion object {
        const val PAGE_SIZE = 30
        const val PREFETCH_THRESHOLD = 5

        /** Fresh first page (query, filter or sort changed). */
        fun <T> firstPage(all: List<T>): PagedResults<T> = PagedResults(all)
    }
}
