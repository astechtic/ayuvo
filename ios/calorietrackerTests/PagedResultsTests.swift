import Foundation
import Testing
@testable import calorietracker

@Suite struct PagedResultsTests {
    private struct Row: Identifiable, Equatable {
        let id: Int
    }

    private func rows(_ count: Int) -> [Row] {
        (0..<count).map(Row.init(id:))
    }

    @Test func defaultsMatchAndroid() {
        #expect(PagedResultsDefaults.pageSize == 30)
        #expect(PagedResultsDefaults.prefetchThreshold == 5)
    }

    @Test func firstPageIsCappedAtPageSize() {
        let paged = PagedResults(rows(95))
        #expect(paged.visible.count == 30)
        #expect(paged.totalCount == 95)
        #expect(paged.hasMore)
    }

    @Test func shortResultsShowEverythingWithoutMore() {
        let paged = PagedResults(rows(12))
        #expect(paged.visible.count == 12)
        #expect(!paged.hasMore)
        #expect(!PagedResults<Row>().hasMore)
        #expect(PagedResults<Row>().isEmpty)
    }

    @Test func rowsNearTheEndLoadTheNextPage() {
        var paged = PagedResults(rows(95))

        let loaded1 = paged.loadNextPageIfNeeded(currentID: 24)
        #expect(!loaded1)
        #expect(paged.visibleCount == 30)

        let loaded2 = paged.loadNextPageIfNeeded(currentID: 25)
        #expect(loaded2)
        #expect(paged.visibleCount == 60)

        let loaded3 = paged.loadNextPageIfNeeded(currentID: 59)
        #expect(loaded3)
        #expect(paged.visibleCount == 90)

        let loaded4 = paged.loadNextPageIfNeeded(currentID: 89)
        #expect(loaded4)
        #expect(paged.visibleCount == 95)
        #expect(!paged.hasMore)
        let loaded5 = paged.loadNextPageIfNeeded(currentID: 94)
        #expect(!loaded5)
    }

    @Test func resetReturnsToTheFirstPage() {
        var paged = PagedResults(rows(95))
        paged.loadNextPage()
        paged.loadNextPage()
        #expect(paged.visibleCount == 90)

        paged.reset(rows(40))
        #expect(paged.visibleCount == 30)
        #expect(paged.totalCount == 40)
        #expect(paged.visible.map(\.id) == Array(0..<30))
    }

    @Test func updateKeepsRevealedRows() {
        var paged = PagedResults(rows(95))
        paged.loadNextPage()
        paged.update(rows(94))
        #expect(paged.visibleCount == 60)
        #expect(paged.totalCount == 94)

        paged.update(rows(10))
        #expect(paged.visibleCount == 10)
        #expect(!paged.hasMore)
    }

    @Test func customPageSizeAndThreshold() {
        var paged = PagedResults(rows(10), pageSize: 4, prefetchThreshold: 1)
        #expect(paged.visibleCount == 4)
        let loaded6 = paged.loadNextPageIfNeeded(currentID: 2)
        #expect(!loaded6)
        let loaded7 = paged.loadNextPageIfNeeded(currentID: 3)
        #expect(loaded7)
        #expect(paged.visibleCount == 8)
    }
}
