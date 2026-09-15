import Foundation

/// Incrementally reveals an in-memory, already filtered result list so long searches
/// render one page at a time instead of building every row up front.
/// Mirrors Android's `PagedResults` (same page size and prefetch threshold).
nonisolated enum PagedResultsDefaults {
    static let pageSize = 30
    /// Load the next page once a row this close to the end of the visible slice appears.
    static let prefetchThreshold = 5
}

nonisolated struct PagedResults<Element: Identifiable> {
    private(set) var all: [Element]
    private(set) var visibleCount: Int
    let pageSize: Int
    let prefetchThreshold: Int

    init(
        _ all: [Element] = [],
        pageSize: Int = PagedResultsDefaults.pageSize,
        prefetchThreshold: Int = PagedResultsDefaults.prefetchThreshold
    ) {
        self.pageSize = max(pageSize, 1)
        self.prefetchThreshold = max(prefetchThreshold, 0)
        self.all = all
        self.visibleCount = min(all.count, self.pageSize)
    }

    var visible: ArraySlice<Element> { all.prefix(visibleCount) }
    var totalCount: Int { all.count }
    var hasMore: Bool { visibleCount < all.count }
    var isEmpty: Bool { all.isEmpty }

    /// Replaces the results and resets to the first page (new query, filter, or sort).
    mutating func reset(_ results: [Element]) {
        all = results
        visibleCount = min(results.count, pageSize)
    }

    /// Replaces the results in place (e.g. a bookmark toggled) without collapsing rows the
    /// user already scrolled through.
    mutating func update(_ results: [Element]) {
        all = results
        visibleCount = min(results.count, max(visibleCount, pageSize))
    }

    /// Call when a row appears; reveals the next page if that row is near the end.
    /// Returns true when more rows became visible.
    @discardableResult
    mutating func loadNextPageIfNeeded(currentID: Element.ID) -> Bool {
        guard hasMore else { return false }
        let triggerStart = max(visibleCount - prefetchThreshold, 0)
        guard all[triggerStart..<visibleCount].contains(where: { $0.id == currentID }) else { return false }
        return loadNextPage()
    }

    @discardableResult
    mutating func loadNextPage() -> Bool {
        guard hasMore else { return false }
        visibleCount = min(visibleCount + pageSize, all.count)
        return true
    }
}
