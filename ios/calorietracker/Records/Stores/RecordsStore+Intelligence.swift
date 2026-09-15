import Foundation

// Phase 2 façade: processing queue, AI mode, review, duplicates, splits and universal search.
extension RecordsStore {
    // MARK: - Processing

    func startProcessing(repository: RecordsRepository) async {
        guard processingQueue == nil else { return }
        var dependencies = queueDependencies ?? RecordProcessingQueue.Dependencies()
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-ayuvoRecordsNoBackgroundTasks") {
            dependencies.backgroundTasks = NoBackgroundTasks()
        }
        #endif
        let queue = RecordProcessingQueue(repository: repository, dependencies: dependencies)
        processingQueue = queue
        await queue.setObserver { [weak self] event in
            await self?.handleQueueEvent(event)
        }
        aiEnvironment = RecordsAIEnvironment.current()
        await queue.start()
    }

    /// Launch / scene-active.
    func resumeProcessing() async {
        guard let repository = await openIfNeeded() else { return }
        if processingQueue == nil { await startProcessing(repository: repository) }
        aiEnvironment = RecordsAIEnvironment.current()
        await processingQueue?.start()
    }

    func handleQueueEvent(_ event: RecordsQueueEvent) {
        switch event {
        case .progress(let recordID, let page, let total):
            processingProgress = RecordsProcessingProgress(recordID: recordID, page: page + 1, total: total)
        case .changed(let recordID):
            if processingProgress?.recordID != recordID { processingProgress = nil }
            scheduleProcessingRefresh(recordID: recordID)
        case .idle:
            processingProgress = nil
            scheduleProcessingRefresh(recordID: nil)
        }
    }

    /// Coalesces queue events into one reload every 400 ms so scrolling stays smooth.
    private func scheduleProcessingRefresh(recordID: String?) {
        if let recordID { pendingRefreshIDs.insert(recordID) }
        guard refreshTask == nil else { return }
        refreshTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 400_000_000)
            guard let self else { return }
            let ids = self.pendingRefreshIDs
            self.pendingRefreshIDs = []
            self.refreshTask = nil
            await self.refreshProcessingState(recordIDs: Array(ids))
        }
    }

    func refreshProcessingState(recordIDs: [String]) async {
        bumpRevision()
        await reload()
        guard let repository else { return }
        for id in recordIDs where autoReviewIDs.contains(id) {
            guard let record = try? await repository.record(id: id) else { continue }
            if record.processingStatus == .aiPendingConsent {
                // The AI choice comes first; the review sheet stays closed while the banner waits.
                autoReviewIDs.remove(id)
            } else if record.processingStatus == .ready || record.processingStatus == .failedPartial {
                autoReviewIDs.remove(id)
                if record.reviewStatus == .needsReview, reviewRequest == nil, !record.archived {
                    reviewRequest = id
                }
            }
        }
    }

    func reloadSections(repository: RecordsRepository) async {
        async let summary = repository.processingSummary()
        async let review = repository.needsReview(limit: 10)
        async let highlights = repository.importantHighlights(limit: 8)
        async let duplicates = repository.database.pendingDuplicateCandidates(limit: 50)
        if let value = try? await summary { processingSummary = value }
        if let value = try? await review { needsReviewRecords = value }
        if let value = try? await highlights {
            importantHighlights = value.map { RecordHighlightItem(highlight: $0.0, record: $0.1) }
        }
        if let value = try? await duplicates { nearDuplicateCount = value.count }
    }

    // MARK: - AI mode

    func setAIMode(_ mode: RecordsAIMode) {
        defaults.set(mode.rawValue, forKey: RecordsAIMode.storageKey)
        aiMode = mode
        Task { await processingQueue?.kick() }
    }

    func refreshAIEnvironment() {
        aiEnvironment = RecordsAIEnvironment.current()
        aiMode = RecordsAIMode.stored(in: defaults)
    }

    /// "On this device" / "Online AI · <provider>" / "Not now" for one record or every waiting one.
    func decideAI(ids: [String], request: RecordsAIRequest) async {
        guard let repository = await openIfNeeded() else { return }
        if processingQueue == nil { await startProcessing(repository: repository) }
        await processingQueue?.decide(ids: ids, request: request)
        await refreshProcessingState(recordIDs: ids)
    }

    func awaitingConsentIDs() async -> [String] {
        guard let repository = await openIfNeeded() else { return [] }
        return (try? await repository.database.awaitingConsentRecordIDs()) ?? []
    }

    /// Reprocess from the text stage (detail menu).
    func reprocess(id: String) async {
        guard let repository = await openIfNeeded() else { return }
        if processingQueue == nil { await startProcessing(repository: repository) }
        await processingQueue?.enqueue(ids: [id], restart: true)
    }

    // MARK: - Review

    func setFieldState(_ field: RecordField, state: RecordFieldState, editedValue: String? = nil) async {
        guard let repository = await openIfNeeded() else { return }
        try? await repository.database.setFieldState(fieldID: field.id, state: state, editedValue: editedValue)
        await reevaluateReview(recordID: field.recordID)
    }

    func addField(recordID: String, key: RecordFieldKey, value: String) async {
        guard let repository = await openIfNeeded() else { return }
        try? await repository.database.addUserField(recordID: recordID, key: key, value: value)
        await reevaluateReview(recordID: recordID)
    }

    func confirmAll(recordID: String) async {
        guard let repository = await openIfNeeded() else { return }
        try? await repository.database.confirmAllFields(recordID: recordID)
        await refreshProcessingState(recordIDs: [recordID])
    }

    func dismissHighlight(_ highlight: RecordHighlight) async {
        guard let repository = await openIfNeeded() else { return }
        try? await repository.database.dismissHighlight(id: highlight.id)
        await refreshProcessingState(recordIDs: [highlight.recordID])
    }

    /// Re-runs the §15 rule after a manual change (review stays `reviewed` once set).
    func reevaluateReview(recordID: String) async {
        guard let repository = await openIfNeeded(), let record = try? await repository.record(id: recordID) else { return }
        let database = repository.database
        let fields = (try? await database.fields(recordID: recordID)) ?? []
        let highlights = RecordHighlightBuilder.build(fields: fields)
        try? await database.replaceRuleHighlights(recordID: recordID, highlights)
        let pendingSplit = (try? await database.splitProposal(recordID: recordID))?.status == .pending
        let pendingDuplicate = !((try? await database.duplicateCandidates(recordID: recordID, pendingOnly: true)) ?? []).isEmpty
        let pages = (try? await database.pages(recordID: recordID)) ?? []
        let status = RecordReviewEvaluator.status(.init(record: record, fields: fields, pendingSplit: pendingSplit, pendingDuplicate: pendingDuplicate, pages: pages))
        if status != record.reviewStatus { try? await database.setReviewStatus(id: recordID, status) }
        await refreshProcessingState(recordIDs: [recordID])
    }

    // MARK: - Duplicates

    func duplicatePrompt(for candidate: RecordDuplicateCandidate) async -> RecordDuplicatePrompt? {
        guard let repository = await openIfNeeded(),
              let new = try? await repository.record(id: candidate.recordID),
              let existing = try? await repository.record(id: candidate.existingID) else { return nil }
        return RecordDuplicatePrompt(newRecord: new, existing: existing, reason: candidate.reason, score: candidate.score)
    }

    func openFirstNearDuplicate() async {
        guard let repository = await openIfNeeded(),
              let candidate = try? await repository.database.pendingDuplicateCandidates(limit: 1).first,
              let prompt = await duplicatePrompt(for: candidate) else { return }
        duplicateSheetRequest = prompt
    }

    func resolveDuplicateReplace(_ prompt: RecordDuplicatePrompt) async {
        duplicatePrompts.removeAll { $0.id == prompt.id }
        guard let repository = await openIfNeeded() else { return }
        await processingQueue?.cancel(ids: [prompt.newRecord.id, prompt.existing.id])
        try? await Task.detached(priority: .userInitiated) {
            try await repository.replaceDuplicate(newID: prompt.newRecord.id, existingID: prompt.existing.id)
        }.value
        await processingQueue?.enqueue(ids: [prompt.existing.id])
        showBanner(String(localized: "Replaced with the new file"))
        await refreshProcessingState(recordIDs: [prompt.existing.id])
    }

    func resolveDuplicateMerge(_ prompt: RecordDuplicatePrompt) async {
        duplicatePrompts.removeAll { $0.id == prompt.id }
        guard let repository = await openIfNeeded() else { return }
        await processingQueue?.cancel(ids: [prompt.newRecord.id])
        try? await Task.detached(priority: .userInitiated) {
            try await repository.mergeDuplicate(newID: prompt.newRecord.id, existingID: prompt.existing.id)
        }.value
        showBanner(String(localized: "Merged into the existing record"))
        await reevaluateReview(recordID: prompt.existing.id)
    }

    // MARK: - Splits

    func acceptSplit(parentID: String, segments: [RecordSplitSegment]) async -> [String] {
        guard let repository = await openIfNeeded() else { return [] }
        let ids = (try? await repository.database.acceptSplit(parentID: parentID, segments: segments)) ?? []
        if !ids.isEmpty {
            await processingQueue?.enqueue(ids: [])
            await processingQueue?.kick()
            showBanner(String(localized: "Saved as \(ids.count) records"))
        }
        await refreshProcessingState(recordIDs: [parentID] + ids)
        return ids
    }

    func keepSplitAsOne(parentID: String) async {
        guard let repository = await openIfNeeded() else { return }
        try? await repository.database.setSplitStatus(recordID: parentID, .rejected)
        await reevaluateReview(recordID: parentID)
    }

    // MARK: - Filters sheet

    func filterSuggestions() async -> (doctors: [RecordEntity], facilities: [RecordEntity], tags: [String]) {
        guard let repository = await openIfNeeded() else { return ([], [], []) }
        let doctors = (try? await repository.database.entities(kind: .doctor)) ?? []
        let facilities = (try? await repository.database.entities(kind: .facility)) ?? []
        let tags = (try? await repository.allTagNames()) ?? []
        return (doctors, facilities, tags)
    }

    // MARK: - Universal search

    /// Parses the text (§17), applies parsed chips on top of `base` and runs the ranked FTS
    /// search when free-text terms remain (else the filtered timeline).
    func runSearch(text: String, base: RecordQuery) {
        searchTask?.cancel()
        var state = searchState
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            searchState = RecordsSearchState()
            setQuery(base)
            return
        }
        if state.text != text { state.aiParsed = nil }
        state.text = text
        let today = RecordDates.dayString(from: Date())
        state.parsed = RecordQueryParser.parse(text, today: today)
        let active = state.aiParsed ?? state.parsed
        let query = active.applied(to: base, removing: state.removedChips)
        state.isSearching = !active.terms.isEmpty
        state.offerAIRewrite = false
        searchState = state
        setQuery(query)
        let conditions = active.analyteConditions.filter { !state.removedChips.contains(ParsedRecordQuery.Chip.analyte($0, label: "").id) }
        if conditions.isEmpty {
            searchState.valueHits = []
        } else {
            let archived = query.archivedOnly
            Task { [weak self] in
                guard let self, let repository = await self.openIfNeeded() else { return }
                let values = (try? await repository.database.valueHits(conditions: conditions, archived: archived)) ?? []
                guard self.searchState.text == text else { return }
                self.searchState.valueHits = values
            }
        }
        guard !active.terms.isEmpty else {
            searchState.hits = nil
            return
        }
        let terms = active.terms
        searchTask = Task { [weak self] in
            guard let self, let repository = await self.openIfNeeded() else { return }
            let hits = (try? await repository.search(query: query, terms: terms, today: today)) ?? []
            guard !Task.isCancelled, self.searchState.text == text else { return }
            self.searchState.hits = hits
            self.searchState.isSearching = false
            let offer = RecordsAIQueryRewriter.shouldOffer(parsed: active, hitCount: hits.count) && self.searchState.aiParsed == nil
            switch self.aiMode {
            case .ask: self.searchState.offerAIRewrite = offer && self.aiEnvironment.localAvailable || offer && self.aiEnvironment.cloudProviderName != nil
            case .local, .cloud: if offer { await self.rewriteSearchWithAI(base: base) }
            case .off, .none: self.searchState.offerAIRewrite = false
            }
        }
    }

    func removeSearchChip(_ chip: ParsedRecordQuery.Chip, base: RecordQuery) {
        searchState.removedChips.insert(chip.id)
        runSearch(text: searchState.text, base: base)
    }

    /// "Search smarter with AI": only the query text is sent.
    func rewriteSearchWithAI(base: RecordQuery) async {
        let environment = aiEnvironment
        let resolution = RecordsAIModeResolver.resolve(
            mode: aiMode == .ask ? nil : aiMode,
            request: aiMode == .ask ? (environment.localAvailable ? .local : .cloud) : nil,
            environment: environment
        )
        guard case .run(let engine) = resolution else { return }
        let text = searchState.text
        searchState.isRewriting = true
        searchState.offerAIRewrite = false
        let today = RecordDates.dayString(from: Date())
        let parsed = try? await Task.detached(priority: .userInitiated) {
            try await RecordsAIQueryRewriter().rewrite(query: text, today: today, engine: engine)
        }.value
        searchState.isRewriting = false
        guard searchState.text == text, let parsed, !(parsed.terms.isEmpty && parsed.chips.isEmpty) else { return }
        searchState.aiParsed = parsed
        runSearch(text: text, base: base)
    }
}
