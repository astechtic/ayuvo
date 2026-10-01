import Foundation
import Testing
@testable import calorietracker

/// `GoogleHealthClient` against a `URLProtocol` stub, and `GoogleHealthSyncEngine` end to end
/// on an in-memory mirror: pagination, 401 refresh, 429 backoff, partial scopes, resume and
/// the origin-3 database rules.
@Suite(.serialized)
struct GoogleHealthClientTests {
    private typealias F = HealthTestFixtures

    // MARK: - Stubs

    /// Routes requests by the `X-Stub-ID` header so suites never share a handler.
    final class StubURLProtocol: URLProtocol, @unchecked Sendable {
        typealias Handler = @Sendable (URLRequest) -> (Int, [String: String], Data)
        private static let lock = NSLock()
        nonisolated(unsafe) private static var handlers: [String: Handler] = [:]

        static func register(_ handler: @escaping Handler) -> URLSession {
            let id = UUID().uuidString
            lock.withLock { handlers[id] = handler }
            let configuration = URLSessionConfiguration.ephemeral
            configuration.protocolClasses = [StubURLProtocol.self]
            configuration.httpAdditionalHeaders = ["X-Stub-ID": id]
            return URLSession(configuration: configuration)
        }

        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

        override func startLoading() {
            let id = request.value(forHTTPHeaderField: "X-Stub-ID") ?? ""
            let handler = Self.lock.withLock { Self.handlers[id] }
            let (status, headers, body) = handler?(request) ?? (500, [:], Data())
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: body)
            client?.urlProtocolDidFinishLoading(self)
        }

        override func stopLoading() {}
    }

    /// Records every request; answers from a per-type script.
    final class Recorder: @unchecked Sendable {
        private let lock = NSLock()
        private(set) var requests: [URLRequest] = []
        private var responses: [String: [(Int, [String: String], Data)]]

        init(_ responses: [String: [(Int, [String: String], Data)]]) {
            self.responses = responses
        }

        func handle(_ request: URLRequest) -> (Int, [String: String], Data) {
            lock.withLock {
                requests.append(request)
                let path = request.url?.path ?? ""
                let type = path.components(separatedBy: "/dataTypes/").last?.components(separatedBy: "/").first ?? ""
                var queue = responses[type] ?? []
                guard !queue.isEmpty else { return (200, [:], Data(#"{"dataPoints":[]}"#.utf8)) }
                let next = queue.removeFirst()
                responses[type] = queue
                return next
            }
        }

        func requests(for type: String) -> [URLRequest] {
            lock.withLock { requests.filter { $0.url?.path.contains("/dataTypes/\(type)/") == true } }
        }
    }

    final class FakeTokens: GoogleHealthTokenProviding, @unchecked Sendable {
        private let lock = NSLock()
        private(set) var calls: [Bool] = []
        var error: Error?

        func accessToken(forceRefresh: Bool) async throws -> String {
            try lock.withLock {
                calls.append(forceRefresh)
                if let error { throw error }
                return forceRefresh ? "fresh" : "stale"
            }
        }
    }

    final class SleepLog: @unchecked Sendable {
        private let lock = NSLock()
        private(set) var delays: [TimeInterval] = []
        func record(_ delay: TimeInterval) { lock.withLock { delays.append(delay) } }
    }

    static func stepsPoint(_ id: String, start: String, end: String, count: Int) -> [String: Any] {
        [
            "name": "users/me/dataTypes/steps/dataPoints/\(id)",
            "dataSource": ["platform": "ANDROID", "application": ["packageName": "com.google.android.apps.fitness"]],
            "steps": [
                "interval": ["startTime": start, "startUtcOffset": "0s", "endTime": end, "endUtcOffset": "0s"],
                "count": String(count),
            ],
        ]
    }

    static func body(_ points: [[String: Any]], next: String? = nil) -> Data {
        var object: [String: Any] = ["dataPoints": points]
        if let next { object["nextPageToken"] = next }
        return try! JSONSerialization.data(withJSONObject: object)
    }

    static func makeClient(_ recorder: Recorder, tokens: FakeTokens, sleeps: SleepLog = SleepLog()) throws -> GoogleHealthClient {
        let session = StubURLProtocol.register { recorder.handle($0) }
        return GoogleHealthClient(api: try GoogleHealthMapTests.sharedMap().api, tokens: tokens, session: session, maxRetries: 3, baseDelay: 0.5) {
            sleeps.record($0)
        }
    }

    static let utc: Calendar = GoogleHealthMapTests.calendar("UTC")
    static let now = Date(timeIntervalSince1970: 1_772_668_800) // 2026-03-05T00:00:00Z

    static func engine(
        client: any GoogleHealthFetching, db: HealthDatabase, types: [String], scopes: Set<String>? = nil, now: Date = now
    ) throws -> GoogleHealthSyncEngine {
        let map = try GoogleHealthMapTests.sharedMap()
        let granted = scopes ?? Set(map.requestedScopes(groups: Set(map.scopeGroups.map(\.id))))
        return GoogleHealthSyncEngine(
            fetcher: client, database: db, map: map, types: types.compactMap(map.type), grantedScopes: granted,
            calendar: utc, now: { now }
        )
    }

    // MARK: - Client

    @Test func paginationFollowsNextPageTokenAndCommitsEveryPage() async throws {
        let recorder = Recorder(["steps": [
            (200, [:], Self.body([Self.stepsPoint("a", start: "2026-03-04T01:00:00Z", end: "2026-03-04T01:15:00Z", count: 100)], next: "p2")),
            (200, [:], Self.body([Self.stepsPoint("b", start: "2026-03-04T02:00:00Z", end: "2026-03-04T02:15:00Z", count: 50)])),
        ]])
        let client = try Self.makeClient(recorder, tokens: FakeTokens())
        let db = try await HealthDatabase.inMemory()
        let outcome = try await Self.engine(client: client, db: db, types: ["steps"]).sync()

        #expect(outcome == .synced(types: 1, rows: 2, failures: 0))
        let requests = recorder.requests(for: "steps")
        #expect(requests.count == 2)
        let first = URLComponents(url: requests[0].url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
        let second = URLComponents(url: requests[1].url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
        #expect(first.first { $0.name == "pageToken" } == nil)
        #expect(second.first { $0.name == "pageToken" }?.value == "p2")
        #expect(first.first { $0.name == "filter" }?.value == #"steps.interval.civil_start_time >= "2025-12-05T00:00:00""#)
        #expect(first.first { $0.name == "pageSize" }?.value == "10000")
        #expect(requests[0].value(forHTTPHeaderField: "Authorization") == "Bearer stale")

        let state = try #require(try await db.googleSyncState(ghType: "steps"))
        #expect(state.pageToken == nil)
        #expect(state.status == "idle")
        #expect(state.cursorMs == F.ms(ISO8601DateFormatter().date(from: "2026-03-04T02:15:00Z")!))
        #expect(try await db.sample(id: "gh:a")?.origin == 3)
        #expect(try await db.dailyRollups(type: "steps", fromDay: "2026-03-04", toDay: "2026-03-04").first?.sum == 150)
        // Steps have an Apple Health target and come from a watch: queued for write-back.
        #expect(try await db.mirrorStatusCounts() == ["pending": 2])

        // Second run starts at cursor − 2 days.
        let again = Recorder([:])
        _ = try await Self.engine(client: try Self.makeClient(again, tokens: FakeTokens()), db: db, types: ["steps"]).sync()
        let filter = URLComponents(url: again.requests(for: "steps")[0].url!, resolvingAgainstBaseURL: false)?
            .queryItems?.first { $0.name == "filter" }?.value
        #expect(filter == #"steps.interval.civil_start_time >= "2026-03-02T02:15:00""#)
    }

    @Test func unauthorizedRefreshesTheTokenAndRetriesOnce() async throws {
        let recorder = Recorder(["steps": [(401, [:], Data()), (200, [:], Self.body([]))]])
        let tokens = FakeTokens()
        let client = try Self.makeClient(recorder, tokens: tokens)
        let page = try await client.listDataPoints(ghType: "steps", filter: "x", pageSize: 10, pageToken: nil)
        #expect(page.points.isEmpty)
        #expect(tokens.calls == [false, true])
        #expect(recorder.requests(for: "steps").last?.value(forHTTPHeaderField: "Authorization") == "Bearer fresh")

        let denied = Recorder(["steps": [(401, [:], Data()), (401, [:], Data())]])
        await #expect(throws: GoogleHealthAPIError.unauthorized) {
            _ = try await Self.makeClient(denied, tokens: FakeTokens()).listDataPoints(ghType: "steps", filter: "x", pageSize: 10, pageToken: nil)
        }
    }

    @Test func rateLimitsAndServerErrorsBackOff() async throws {
        let recorder = Recorder(["steps": [
            (429, ["Retry-After": "2"], Data()), (503, [:], Data()), (500, [:], Data()), (200, [:], Self.body([])),
        ]])
        let sleeps = SleepLog()
        let client = try Self.makeClient(recorder, tokens: FakeTokens(), sleeps: sleeps)
        _ = try await client.listDataPoints(ghType: "steps", filter: "x", pageSize: 10, pageToken: nil)
        #expect(sleeps.delays == [2, 1, 2])

        let exhausted = Recorder(["steps": Array(repeating: (500, [:], Data(#"{"error":{"message":"boom"}}"#.utf8)), count: 5)])
        await #expect(throws: GoogleHealthAPIError.http(status: 500, message: "boom")) {
            _ = try await Self.makeClient(exhausted, tokens: FakeTokens()).listDataPoints(ghType: "steps", filter: "x", pageSize: 10, pageToken: nil)
        }
    }

    @Test func partialScopesSkipTypesWithoutAGrant() async throws {
        let recorder = Recorder([:])
        let client = try Self.makeClient(recorder, tokens: FakeTokens())
        let db = try await HealthDatabase.inMemory()
        let map = try GoogleHealthMapTests.sharedMap()
        let activityOnly = Set(map.requestedScopes(groups: ["activity"]))
        let outcome = try await Self.engine(client: client, db: db, types: ["steps", "heart-rate"], scopes: activityOnly).sync()

        #expect(outcome == .synced(types: 1, rows: 0, failures: 0))
        #expect(recorder.requests(for: "heart-rate").isEmpty)
        #expect(recorder.requests(for: "steps").count == 1)
        #expect(try await db.googleSyncState(ghType: "heart-rate")?.status == "error:scope")
    }

    @Test func optionalTypesThatDoNotExistAreUnsupported() async throws {
        let recorder = Recorder(["height": [(404, [:], Data())], "weight": [(404, [:], Data())]])
        let client = try Self.makeClient(recorder, tokens: FakeTokens())
        let db = try await HealthDatabase.inMemory()
        let outcome = try await Self.engine(client: client, db: db, types: ["height", "weight"]).sync()
        #expect(try await db.googleSyncState(ghType: "height")?.status == "unsupported")
        #expect(try await db.googleSyncState(ghType: "weight")?.status == "error:404")
        #expect(outcome == .failed(GoogleHealthAPIError.http(status: 404, message: nil).localizedDescription))
    }

    @Test func refusedTokenStopsTheRunWithReauthRequired() async throws {
        let tokens = FakeTokens()
        tokens.error = GoogleHealthAuthError.consentLost
        let client = try Self.makeClient(Recorder([:]), tokens: tokens)
        let db = try await HealthDatabase.inMemory()
        #expect(try await Self.engine(client: client, db: db, types: ["steps"]).sync() == .reauthRequired)
    }

    // MARK: - Engine rules

    /// Scripted fetcher: fails after the first page to simulate an interrupted fetch.
    final class ScriptedFetcher: GoogleHealthFetching, @unchecked Sendable {
        private let lock = NSLock()
        var pages: [GoogleHealthPage]
        var failAfter: Int?
        private(set) var tokensSeen: [String?] = []

        init(_ pages: [GoogleHealthPage], failAfter: Int? = nil) {
            self.pages = pages
            self.failAfter = failAfter
        }

        func listDataPoints(ghType: String, filter: String, pageSize: Int, pageToken: String?) async throws -> GoogleHealthPage {
            try lock.withLock {
                tokensSeen.append(pageToken)
                if let failAfter, tokensSeen.count > failAfter { throw GoogleHealthAPIError.network("offline") }
                return pages.isEmpty ? GoogleHealthPage(points: [], nextPageToken: nil) : pages.removeFirst()
            }
        }
    }

    @Test func interruptedFetchResumesFromTheSavedPageToken() async throws {
        let first = GoogleHealthPage(points: [RJ.from(Self.stepsPoint("a", start: "2026-03-04T01:00:00Z", end: "2026-03-04T01:15:00Z", count: 1))], nextPageToken: "p2")
        let second = GoogleHealthPage(points: [RJ.from(Self.stepsPoint("b", start: "2026-03-04T02:00:00Z", end: "2026-03-04T02:15:00Z", count: 1))], nextPageToken: nil)
        let db = try await HealthDatabase.inMemory()
        let failing = ScriptedFetcher([first, second], failAfter: 1)
        _ = try await Self.engine(client: failing, db: db, types: ["steps"]).sync()
        var state = try #require(try await db.googleSyncState(ghType: "steps"))
        #expect(state.pageToken == "p2")
        #expect(state.cursorMs == nil)
        #expect(try await db.sampleCount(type: "steps") == 1)

        let resumed = ScriptedFetcher([second])
        _ = try await Self.engine(client: resumed, db: db, types: ["steps"], now: Self.now.addingTimeInterval(3600)).sync()
        #expect(resumed.tokensSeen == ["p2"])
        state = try #require(try await db.googleSyncState(ghType: "steps"))
        #expect(state.pageToken == nil)
        #expect(state.cursorMs != nil)
        #expect(try await db.sampleCount(type: "steps") == 2)
    }

    @Test func echoGuardSkipsPlatformDuplicatesAndAppleHealthOrigin() async throws {
        let db = try await HealthDatabase.inMemory()
        // An Apple Health row with the same window and value as Google point "dup".
        let start = ISO8601DateFormatter().date(from: "2026-03-04T01:00:00Z")!
        try await db.upsertSamples([F.row(id: "hk-1", type: F.steps, start: start, end: start.addingTimeInterval(900), value: 100)])
        var fromApple = Self.stepsPoint("apple", start: "2026-03-04T03:00:00Z", end: "2026-03-04T03:15:00Z", count: 7)
        fromApple["dataSource"] = ["platform": "IOS", "application": ["packageName": "com.apple.health"]]
        let page = GoogleHealthPage(points: [
            RJ.from(Self.stepsPoint("dup", start: "2026-03-04T01:00:30Z", end: "2026-03-04T01:15:00Z", count: 100)),
            RJ.from(Self.stepsPoint("own", start: "2026-03-04T05:00:00Z", end: "2026-03-04T05:15:00Z", count: 30)),
            RJ.from(fromApple),
        ], nextPageToken: nil)
        _ = try await Self.engine(client: ScriptedFetcher([page]), db: db, types: ["steps"]).sync()
        #expect(try await db.mirrorStatusCounts() == ["skipped_dup": 2, "pending": 1])
        #expect(try await db.pendingMirrorCandidates(limit: 10).map(\.row.id) == ["gh:own"])
    }

    @Test func refetchedPointsOverwriteOnlyWhenAValueChanges() async throws {
        let db = try await HealthDatabase.inMemory()
        let point = RJ.from(Self.stepsPoint("a", start: "2026-03-04T01:00:00Z", end: "2026-03-04T01:15:00Z", count: 10))
        _ = try await Self.engine(client: ScriptedFetcher([GoogleHealthPage(points: [point], nextPageToken: nil)]), db: db, types: ["steps"]).sync()
        try await db.markMirror(["gh:a"], status: .mirrored, platformIDs: ["gh:a": "uuid-1"], nowMs: 5)
        let firstUpdated = try #require(try await db.sample(id: "gh:a")).updatedMs

        // Same values, later clock: unchanged, still mirrored.
        _ = try await Self.engine(client: ScriptedFetcher([GoogleHealthPage(points: [point], nextPageToken: nil)]), db: db, types: ["steps"], now: Self.now.addingTimeInterval(60)).sync()
        #expect(try await db.sample(id: "gh:a")?.updatedMs == firstUpdated)
        #expect(try await db.mirrorStatusCounts() == ["mirrored": 1])

        // Changed value: overwritten and queued to replace the Apple Health copy.
        let changed = RJ.from(Self.stepsPoint("a", start: "2026-03-04T01:00:00Z", end: "2026-03-04T01:15:00Z", count: 12))
        _ = try await Self.engine(client: ScriptedFetcher([GoogleHealthPage(points: [changed], nextPageToken: nil)]), db: db, types: ["steps"], now: Self.now.addingTimeInterval(120)).sync()
        #expect(try await db.sample(id: "gh:a")?.value == 12)
        let candidate = try #require(try await db.pendingMirrorCandidates(limit: 10).first)
        #expect(candidate.platformID == "uuid-1")
    }

    @Test func googleOnlyTypesAreStoredButNeverMirroredAndDisconnectCanDeleteThem() async throws {
        let db = try await HealthDatabase.inMemory()
        let point = RJ.from([
            "name": "users/me/dataTypes/active-zone-minutes/dataPoints/z1",
            "dataSource": ["platform": "ANDROID"],
            "activeZoneMinutes": [
                "interval": ["startTime": "2026-03-04T06:00:00Z", "endTime": "2026-03-04T06:01:00Z"],
                "activeZoneMinutes": "1", "heartRateZone": "CARDIO",
            ],
        ])
        _ = try await Self.engine(client: ScriptedFetcher([GoogleHealthPage(points: [point], nextPageToken: nil)]), db: db, types: ["active-zone-minutes"]).sync()
        let row = try #require(try await db.sample(id: "gh:z1"))
        #expect(row.typeID == "active_zone_minutes")
        #expect(row.value == 60)
        #expect(row.categoryValue == 2)
        #expect(try await db.mirrorStatusCounts() == ["unsupported": 1])

        let touched = try await db.clearGoogleHealth(deleteRows: true)
        #expect(touched["active_zone_minutes"] == ["2026-03-04"])
        #expect(try await db.googleSampleCount() == 0)
        #expect(try await db.mirrorStatusCounts().isEmpty)
        #expect(try await db.allGoogleSyncStates().isEmpty)
    }

    @Test func platformTombstonesNeverTouchGoogleRows() async throws {
        let db = try await HealthDatabase.inMemory()
        let point = RJ.from(Self.stepsPoint("a", start: "2026-03-04T01:00:00Z", end: "2026-03-04T01:15:00Z", count: 10))
        _ = try await Self.engine(client: ScriptedFetcher([GoogleHealthPage(points: [point], nextPageToken: nil)]), db: db, types: ["steps"]).sync()
        try await db.tombstone(ids: ["gh:a"], nowMs: Int64.max / 2)
        #expect(try await db.sample(id: "gh:a")?.deleted == 0)
    }
}
