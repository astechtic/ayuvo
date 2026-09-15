import Foundation
import Network
import UIKit

/// Events the queue reports to `RecordsStore` (which bumps its revision).
nonisolated enum RecordsQueueEvent: Sendable {
    case changed(recordID: String)
    case progress(recordID: String, page: Int, total: Int)
    case idle
}

/// `beginBackgroundTask` seam.
nonisolated protocol RecordsBackgroundTaskRunning: Sendable {
    func begin(name: String) async -> Int
    func end(_ identifier: Int) async
}

nonisolated struct UIKitBackgroundTasks: RecordsBackgroundTaskRunning {
    func begin(name: String) async -> Int {
        await MainActor.run {
            UIApplication.shared.beginBackgroundTask(withName: name, expirationHandler: nil).rawValue
        }
    }

    func end(_ identifier: Int) async {
        await MainActor.run {
            let task = UIBackgroundTaskIdentifier(rawValue: identifier)
            if task != .invalid { UIApplication.shared.endBackgroundTask(task) }
        }
    }
}

nonisolated struct NoBackgroundTasks: RecordsBackgroundTaskRunning {
    func begin(name: String) async -> Int { 0 }
    func end(_ identifier: Int) async {}
}

/// `NWPathMonitor` wrapper: current reachability plus a reconnect callback.
nonisolated final class RecordsConnectivity: @unchecked Sendable {
    static let shared = RecordsConnectivity()

    private let monitor = NWPathMonitor()
    private let lock = NSLock()
    private var satisfied = true
    private var handlers: [@Sendable () -> Void] = []
    private var started = false

    var isOnline: Bool {
        lock.lock()
        defer { lock.unlock() }
        return satisfied
    }

    func start() {
        lock.lock()
        guard !started else { lock.unlock(); return }
        started = true
        lock.unlock()
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            self.lock.lock()
            let wasOnline = self.satisfied
            self.satisfied = path.status == .satisfied
            let handlers = (!wasOnline && self.satisfied) ? self.handlers : []
            self.lock.unlock()
            handlers.forEach { $0() }
        }
        monitor.start(queue: DispatchQueue(label: "ayuvo.records.connectivity", qos: .utility))
    }

    func onReconnect(_ handler: @escaping @Sendable () -> Void) {
        lock.lock()
        handlers.append(handler)
        lock.unlock()
    }
}

/// §9 processing pipeline: a persisted stage machine in `processing_jobs`, one record at a time
/// at utility priority, `beginBackgroundTask` around each record, resumable after a crash.
actor RecordProcessingQueue {
    nonisolated struct Dependencies: Sendable {
        var textExtractor = RecordTextExtractor()
        var aiExtractor = RecordsAIExtractor()
        var environment: @Sendable () async -> RecordsAIEnvironment = { await MainActor.run { RecordsAIEnvironment.current() } }
        var aiMode: @Sendable () -> RecordsAIMode? = { RecordsAIMode.stored() }
        var isOnline: @Sendable () -> Bool = { RecordsConnectivity.shared.isOnline }
        var backgroundTasks: RecordsBackgroundTaskRunning = UIKitBackgroundTasks()
        var today: @Sendable () -> String = { RecordDates.dayString(from: Date()) }
        var dateOrder: @Sendable () -> RecordDateOrder = { RecordDateOrder.device }
        var now: @Sendable () -> Int64 = { RecordDates.nowMs() }
        /// AI retry delays after attempts 1 and 2 (the third failure continues without AI).
        var retryDelaysMs: [Int64] = [30_000, 300_000, 3_600_000]
        var busyDelayMs: Int64 = 20_000
        var offlineDelayMs: Int64 = 60_000
        var schedulesWakeups = true
    }

    nonisolated static let aiAttemptLimit = 3

    let repository: RecordsRepository
    let dependencies: Dependencies
    private var observer: (@Sendable (RecordsQueueEvent) async -> Void)?
    private var loopTask: Task<Void, Never>?
    private var currentTask: Task<Void, Never>?
    private var currentRecordID: String?
    private var cancelled = Set<String>()
    private var wakeTask: Task<Void, Never>?
    private var didStart = false
    private var isShutdown = false

    init(repository: RecordsRepository, dependencies: Dependencies = Dependencies()) {
        self.repository = repository
        self.dependencies = dependencies
    }

    private var database: RecordsDatabase { repository.database }

    func setObserver(_ observer: @escaping @Sendable (RecordsQueueEvent) async -> Void) {
        self.observer = observer
    }

    /// Launch / scene-active: one-time Phase 1 backfill, then resume every unfinished job.
    func start() async {
        if !didStart {
            didStart = true
            _ = try? await database.backfillProcessingJobsIfNeeded(nowMs: dependencies.now())
            _ = try? await database.backfillKnowledgeJobsIfNeeded(nowMs: dependencies.now())
            if dependencies.schedulesWakeups {
                RecordsConnectivity.shared.start()
                RecordsConnectivity.shared.onReconnect { [weak self] in
                    Task { await self?.kick() }
                }
            }
        }
        kick()
    }

    func enqueue(ids: [String], restart: Bool = false) async {
        guard !ids.isEmpty else { return }
        _ = try? await database.enqueueProcessing(ids: ids, nowMs: dependencies.now(), restart: restart)
        for id in ids { cancelled.remove(id) }
        await notify(.changed(recordID: ids[0]))
        kick()
    }

    func kick() {
        guard loopTask == nil, !isShutdown else { return }
        loopTask = Task.detached(priority: .utility) { [weak self] in
            await self?.runLoop()
        }
    }

    /// Delete All Data: stop the loop and any wake-up; the queue is discarded afterwards.
    func shutdown() async {
        isShutdown = true
        wakeTask?.cancel()
        currentTask?.cancel()
        if let loopTask { await loopTask.value }
    }

    /// Stops work for records about to be deleted.
    func cancel(ids: [String]) {
        cancelled.formUnion(ids)
        if let currentRecordID, ids.contains(currentRecordID) {
            currentTask?.cancel()
        }
    }

    /// "Use AI to find more details?" decision for records waiting in `ask` mode (or a later
    /// "Find more details with AI"). `.none` = "Not now".
    func decide(ids: [String], request: RecordsAIRequest) async {
        let now = dependencies.now()
        for id in ids {
            guard (try? await database.job(recordID: id)) != nil else {
                if request != .none {
                    _ = try? await database.enqueueProcessing(ids: [id], nowMs: now)
                    try? await database.updateJob(recordID: id, stage: .ai, requestedMode: .some(request.rawValue), nowMs: now)
                }
                continue
            }
            if request == .none {
                try? await database.updateJob(recordID: id, attempts: 0, requestedMode: .some(RecordsAIRequest.none.rawValue), awaitingConsent: false, nowMs: now)
                if let job = try? await database.job(recordID: id), job.stage == .done {
                    try? await finalizeStatus(recordID: id, awaitingConsent: false)
                }
            } else {
                try? await database.updateJob(recordID: id, stage: .ai, attempts: 0, nextAttemptMs: 0, requestedMode: .some(request.rawValue), awaitingConsent: false, nowMs: now)
                try? await database.setProcessingStatus(id: id, status: .queued)
            }
            await notify(.changed(recordID: id))
        }
        kick()
    }

    /// Runs until no job is due (tests).
    func drain() async {
        kick()
        while let task = loopTask {
            await task.value
        }
    }

    // MARK: - Loop

    private func runLoop() async {
        defer { loopTask = nil }
        while !Task.isCancelled, !isShutdown {
            let now = dependencies.now()
            guard let job = try? await database.nextRunnableJob(nowMs: now) else {
                if dependencies.schedulesWakeups, let next = try? await database.nextScheduledAttemptMs(), next > now {
                    scheduleWake(afterMs: next - now)
                }
                break
            }
            let task = Task(priority: .utility) { await self.process(job) }
            currentTask = task
            currentRecordID = job.recordID
            await task.value
            currentTask = nil
            currentRecordID = nil
        }
        await notify(.idle)
    }

    private func scheduleWake(afterMs delay: Int64) {
        wakeTask?.cancel()
        wakeTask = Task.detached(priority: .utility) { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(max(1_000, delay)) * 1_000_000)
            guard !Task.isCancelled else { return }
            await self?.kick()
        }
    }

    private enum Outcome {
        case advance
        case wait(ms: Int64)
        case stop
    }

    private func process(_ job: RecordProcessingJob) async {
        let identifier = await dependencies.backgroundTasks.begin(name: "records-processing")
        defer { Task { [dependencies] in await dependencies.backgroundTasks.end(identifier) } }
        var stage = job.stage
        let id = job.recordID
        while stage != .done {
            if Task.isCancelled || cancelled.contains(id) { return }
            guard let record = try? await database.record(id: id),
                  let current = try? await database.job(recordID: id) else { return }
            if !(current.awaitingConsent && stage != .ai) {
                try? await database.setProcessingStatus(id: id, status: stage.runningStatus)
            }
            let outcome = await run(stage: stage, record: record, job: current)
            if Task.isCancelled || cancelled.contains(id) { return }
            switch outcome {
            case .advance:
                stage = stage.next
                try? await database.updateJob(recordID: id, stage: stage, nextAttemptMs: 0, nowMs: dependencies.now())
                await notify(.changed(recordID: id))
            case .wait(let ms):
                try? await database.updateJob(recordID: id, nextAttemptMs: dependencies.now() + ms, nowMs: dependencies.now())
                try? await database.setProcessingStatus(id: id, status: .analyzing)
                await notify(.changed(recordID: id))
                return
            case .stop:
                return
            }
        }
        let awaiting = (try? await database.job(recordID: id))?.awaitingConsent ?? false
        try? await finalizeStatus(recordID: id, awaitingConsent: awaiting)
        await notify(.changed(recordID: id))
    }

    private func finalizeStatus(recordID: String, awaitingConsent: Bool) async throws {
        guard let record = try await database.record(id: recordID) else { return }
        let permanent: Set<String> = Set(RecordProcessingError.allCases.map(\.rawValue)).union(["unreadable_pdf", "unreadable_image", "unreadable_text"])
        let status: RecordProcessingStatus
        if awaitingConsent {
            status = .aiPendingConsent
        } else if let error = record.processingError, permanent.contains(error) {
            status = .failedPartial
        } else {
            status = .ready
        }
        try await database.setProcessingStatus(id: recordID, status: status)
    }

    private func notify(_ event: RecordsQueueEvent) async {
        await observer?(event)
    }

    // MARK: - Stages

    private func pageTexts(_ id: String) async -> [RecordPage] {
        (try? await database.pages(recordID: id)) ?? []
    }

    private func run(stage: RecordProcessingStage, record: HealthRecord, job: RecordProcessingJob) async -> Outcome {
        switch stage {
        case .text: return await runText(record)
        case .classify: return await runClassify(record)
        case .boundaries: return await runBoundaries(record)
        case .rules: return await runRules(record)
        case .ai: return await runAI(record, job: job)
        case .validate:
            try? await database.applyExtraction(recordID: record.id, RecordExtraction(), nowMs: dependencies.now())
            return .advance
        case .highlights:
            let fields = (try? await database.fields(recordID: record.id)) ?? []
            let highlights = await Task.detached(priority: .utility) { RecordHighlightBuilder.build(fields: fields) }.value
            try? await database.replaceRuleHighlights(recordID: record.id, highlights, nowMs: dependencies.now())
            return .advance
        case .review:
            await evaluateReview(record.id)
            return .advance
        case .nearDuplicate: return await runNearDuplicate(record)
        case .observations:
            try? await database.syncKnowledge(recordID: record.id, nowMs: dependencies.now())
            return .advance
        case .relations: return await runRelations(record)
        case .index:
            try? await database.reindex(recordID: record.id)
            return .advance
        case .done:
            return .advance
        }
    }

    private func runText(_ record: HealthRecord) async -> Outcome {
        guard record.parentID == nil || record.pageStart == nil else { return .advance }
        switch record.fileType {
        case .pdf, .image:
            guard let url = repository.originalURL(for: record) else { return .advance }
            let extractor = dependencies.textExtractor
            let id = record.id
            let observer = observer
            let result = await Task.detached(priority: .utility) {
                await extractor.extract(recordID: id, url: url, fileType: record.fileType) { page, total in
                    await observer?(.progress(recordID: id, page: page, total: total))
                }
            }.value
            if Task.isCancelled { return .stop }
            if !result.pages.isEmpty {
                try? await database.storePages(recordID: id, pages: result.pages, pageCount: result.pageCount)
            }
            try? await database.setSignatures(id: id, phash: result.firstPageHash, textSignature: record.textSignature)
            if let error = result.error {
                try? await database.setProcessingStatus(id: id, status: .extractingText, error: .some(error.rawValue))
            } else if record.processingError == RecordProcessingError.textUnavailable.rawValue || record.processingError == RecordProcessingError.ocrFailed.rawValue {
                try? await database.setProcessingStatus(id: id, status: .extractingText, error: .some(nil))
            }
            return .advance
        case .text:
            return .advance
        case .other:
            try? await database.setProcessingStatus(id: record.id, status: .extractingText, error: .some(RecordProcessingError.unsupported.rawValue))
            return .advance
        }
    }

    private func runClassify(_ record: HealthRecord) async -> Outcome {
        let pages = await pageTexts(record.id)
        let texts = pages.compactMap(\.text)
        guard texts.contains(where: { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) else { return .advance }
        let classification = await Task.detached(priority: .utility) { RecordsClassifier.classify(pages: texts) }.value
        try? await database.applyClassification(id: record.id, type: classification.type, confidence: classification.confidence, nowMs: dependencies.now())
        return .advance
    }

    private func runBoundaries(_ record: HealthRecord) async -> Outcome {
        guard record.parentID == nil, record.fileType == .pdf else { return .advance }
        let pages = await pageTexts(record.id)
        guard pages.count >= RecordsBoundaries.minimumPages else { return .advance }
        let texts = pages.map { $0.text ?? "" }
        let today = dependencies.today()
        let order = dependencies.dateOrder()
        let segments = await Task.detached(priority: .utility) { RecordsBoundaries.segments(pages: texts, today: today, order: order) }.value
        try? await database.upsertSplitProposal(recordID: record.id, segments: segments, nowMs: dependencies.now())
        return .advance
    }

    private func runRules(_ record: HealthRecord) async -> Outcome {
        let pages = await pageTexts(record.id)
        let texts = pages.map { $0.text ?? "" }
        guard texts.contains(where: { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) else { return .advance }
        let current = (try? await database.record(id: record.id)) ?? record
        let today = dependencies.today()
        let order = dependencies.dateOrder()
        let fields = await Task.detached(priority: .utility) { () -> [ExtractedField] in
            RecordsRules.extract(pages: texts, recordType: current.recordType, today: today, order: order)
        }.value
        try? await database.applyExtraction(recordID: record.id, RecordExtraction(fields: fields), nowMs: dependencies.now())
        return .advance
    }

    private func runAI(_ record: HealthRecord, job: RecordProcessingJob) async -> Outcome {
        let fields = (try? await database.fields(recordID: record.id)) ?? []
        let pages = await pageTexts(record.id)
        let request = job.requestedMode.flatMap(RecordsAIRequest.init(rawValue:))
        let gaps = RecordsAIGaps.gaps(record: record, fields: fields, pages: pages)
        // A user's explicit request runs even without gaps; otherwise no gap → no AI.
        guard !gaps.isEmpty || (request != nil && request != RecordsAIRequest.none) else { return .advance }
        let environment = await dependencies.environment()
        let resolution = RecordsAIModeResolver.resolve(mode: dependencies.aiMode(), request: request, environment: environment)
        switch resolution {
        case .askConsent:
            try? await database.updateJob(recordID: record.id, awaitingConsent: true, nowMs: dependencies.now())
            return .advance
        case .skip(let error):
            if let error, record.processingError == nil || record.processingError?.hasPrefix("ai_") == true {
                try? await database.setProcessingStatus(id: record.id, status: .analyzing, error: .some(error.rawValue))
            }
            return .advance
        case .run(let engine):
            if engine.isCloud, !dependencies.isOnline() {
                return .wait(ms: dependencies.offlineDelayMs)
            }
            let current = (try? await database.record(id: record.id)) ?? record
            let extractor = dependencies.aiExtractor
            let url = repository.originalURL(for: current)
            let today = dependencies.today()
            let order = dependencies.dateOrder()
            do {
                let hint = current.typeMethod == .rules ? current.recordType : RecordsClassifier.classify(pages: pages.map { $0.text ?? "" }).type
                var extraction = try await Task.detached(priority: .utility) {
                    try await extractor.extract(record: current, pages: pages, engine: engine, originalURL: url, today: today, order: order, rulesTypeHint: hint)
                }.value
                // §8.1 classification candidates: the rules classifier and the AI record_type, highest confidence wins.
                if let aiConfidence = extraction.typeConfidence, current.typeMethod == .rules, let rulesConfidence = current.typeConfidence, rulesConfidence >= aiConfidence {
                    extraction.recordType = nil
                    extraction.typeConfidence = nil
                }
                if Task.isCancelled { return .stop }
                try? await database.applyExtraction(recordID: record.id, extraction, nowMs: dependencies.now())
                try? await database.setAIModeUsed(id: record.id, mode: engine.modeUsed, provider: engine.providerName)
                if current.processingError?.hasPrefix("ai_") == true {
                    try? await database.setProcessingStatus(id: record.id, status: .analyzing, error: .some(nil))
                }
                try? await database.updateJob(recordID: record.id, attempts: 0, lastError: .some(nil), nowMs: dependencies.now())
                return .advance
            } catch RecordsAIError.busy {
                return .wait(ms: dependencies.busyDelayMs)
            } catch RecordsAIError.offline {
                return .wait(ms: dependencies.offlineDelayMs)
            } catch RecordsAIError.unavailable {
                try? await database.setProcessingStatus(id: record.id, status: .analyzing, error: .some(RecordProcessingError.aiUnavailable.rawValue))
                return .advance
            } catch is CancellationError {
                return .stop
            } catch {
                let attempts = job.attempts + 1
                try? await database.updateJob(recordID: record.id, attempts: attempts, lastError: .some(String(describing: error)), nowMs: dependencies.now())
                if attempts >= Self.aiAttemptLimit {
                    try? await database.setProcessingStatus(id: record.id, status: .analyzing, error: .some(RecordProcessingError.aiFailed.rawValue))
                    return .advance
                }
                let delays = dependencies.retryDelaysMs
                return .wait(ms: delays[min(attempts - 1, delays.count - 1)])
            }
        }
    }

    private func evaluateReview(_ id: String) async {
        guard let record = try? await database.record(id: id) else { return }
        let fields = (try? await database.fields(recordID: id)) ?? []
        let pendingSplit = (try? await database.splitProposal(recordID: id))?.status == .pending
        let pendingDuplicate = !((try? await database.duplicateCandidates(recordID: id, pendingOnly: true)) ?? []).isEmpty
        let pages = await pageTexts(id)
        let status = RecordReviewEvaluator.status(.init(record: record, fields: fields, pendingSplit: pendingSplit, pendingDuplicate: pendingDuplicate, pages: pages))
        if status != record.reviewStatus {
            try? await database.setReviewStatus(id: id, status, nowMs: dependencies.now())
        }
    }

    /// §22: suggestions against records within ±180 days (split children are linked at acceptance).
    private func runRelations(_ record: HealthRecord) async -> Outcome {
        guard let (profile, candidates, links) = try? await database.relationInputs(recordID: record.id) else { return .advance }
        let today = dependencies.today()
        let suggestions = await Task.detached(priority: .utility) {
            RecordRelationSuggester.suggest(for: profile, candidates: candidates, existingLinks: links, today: today)
        }.value
        if !suggestions.isEmpty {
            _ = try? await database.insertSuggestions(recordID: record.id, suggestions, nowMs: dependencies.now())
        }
        return .advance
    }

    private func runNearDuplicate(_ record: HealthRecord) async -> Outcome {
        let pages = await pageTexts(record.id)
        let text = pages.compactMap(\.text).joined(separator: "\n")
        let signature = await Task.detached(priority: .utility) { RecordNearDuplicate.textSignature(text: text) }.value
        let current = (try? await database.record(id: record.id)) ?? record
        try? await database.setSignatures(id: record.id, phash: current.phash, textSignature: signature)
        guard !current.isSplitChild else { return .advance }
        let others = (try? await database.signatureRows(excluding: record.id)) ?? []
        let childIDs = Set(((try? await database.children(parentID: record.id)) ?? []).map(\.id))
        var inserted = false
        for other in others where other.parentID == nil && !childIDs.contains(other.id) && other.id != current.parentID {
            guard let match = RecordNearDuplicate.match(phashA: current.phash, signatureA: signature, phashB: other.phash, signatureB: other.textSignature) else { continue }
            if let otherRecord = try? await database.record(id: other.id), otherRecord.checksumSHA256 != nil, otherRecord.checksumSHA256 == current.checksumSHA256 {
                continue // exact duplicates are the import-time prompt
            }
            if let existing = try? await database.duplicateCandidates(recordID: other.id, pendingOnly: false),
               existing.contains(where: { ($0.recordID == other.id && $0.existingID == record.id) || ($0.recordID == record.id && $0.existingID == other.id) }) {
                continue
            }
            switch match {
            case .phash(let score):
                try? await database.insertDuplicateCandidate(recordID: record.id, existingID: other.id, reason: .phash, score: score, nowMs: dependencies.now())
            case .content(let score):
                try? await database.insertDuplicateCandidate(recordID: record.id, existingID: other.id, reason: .content, score: score, nowMs: dependencies.now())
            }
            inserted = true
        }
        if inserted { await evaluateReview(record.id) }
        return .advance
    }
}

