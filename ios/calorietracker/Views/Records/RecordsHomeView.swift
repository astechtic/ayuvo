import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// Records tab root: search, Timeline | List | Grid, filter chips, Recent strip, paging,
/// multi-select and the Add Record flows.
struct RecordsHomeView: View {
    @Environment(RecordsStore.self) private var store
    @Environment(ChatStore.self) private var chatStore
    @State private var path = NavigationPath()
    @State private var searchText = ""
    @State private var chip: RecordsFilterChip = .all
    @State private var searchTask: Task<Void, Never>?
    @State private var filters = RecordQuery()
    @State private var showFilters = false
    @State private var reviewRecordID: String?
    @State private var consentWaiting: [String] = []

    @State private var showAddSheet = false
    @State private var pendingAction: AddRecordAction?
    @State private var showScanner = false
    @State private var showCamera = false
    @State private var cameraImage: UIImage?
    @State private var showPhotoPicker = false
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var showFileImporter = false
    @State private var fileImporterTypes: [UTType] = [.pdf, .image, .plainText, .text]
    @State private var textEntryMode: RecordTextEntrySheet.Mode?
    @State private var showPrivacy = false

    @State private var isSelecting = false
    @State private var selection: Set<String> = []
    @State private var showBulkDeleteConfirmation = false
    /// Non-empty while the §34 "What will be shared" sheet is open.
    @State private var shareSelection: [String] = []

    private let gridColumns = [GridItem(.adaptive(minimum: 104, maximum: 160), spacing: 12)]

    var body: some View {
        NavigationStack(path: $path) {
            content
                .background(AppColors.appBackground)
                .navigationTitle("Records")
                .searchable(text: $searchText, placement: .navigationBarDrawer(displayMode: .always), prompt: Text("Search records"))
                .toolbar { toolbarContent }
                .recordsRouteDestinations()
                .safeAreaInset(edge: .bottom, spacing: 0) {
                    if isSelecting { selectionBar }
                }
                .overlay(alignment: .top) {
                    if let banner = store.banner {
                        RecordsBannerView(banner: banner)
                            .padding(.top, 8)
                            .transition(.move(edge: .top).combined(with: .opacity))
                    }
                }
                .animation(.snappy, value: store.banner)
        }
        .task {
            store.refreshAIEnvironment()
            // A Coach "Used records" chip may have asked for a record before this tab existed.
            if let id = store.navigationRequest {
                store.navigationRequest = nil
                path.append(RecordsRoute.detail(id))
            }
            if !store.hasLoadedOnce { await store.reload() }
        }
        .onChange(of: searchText) { _, newValue in
            searchTask?.cancel()
            searchTask = Task {
                try? await Task.sleep(nanoseconds: 150_000_000)
                guard !Task.isCancelled else { return }
                store.runSearch(text: newValue, base: baseQuery)
            }
        }
        .onChange(of: chip) { _, _ in
            store.runSearch(text: searchText, base: baseQuery)
        }
        .onChange(of: filters) { _, _ in
            store.runSearch(text: searchText, base: baseQuery)
        }
        .onChange(of: store.reviewRequest) { _, id in
            guard let id else { return }
            store.reviewRequest = nil
            if reviewRecordID == nil, !showAddSheet, store.duplicatePrompts.isEmpty { reviewRecordID = id }
        }
        .task(id: store.processingSummary.awaitingConsent) {
            consentWaiting = store.processingSummary.awaitingConsent > 0 ? await store.awaitingConsentIDs() : []
        }
        .sheet(isPresented: $showFilters) {
            RecordsFilterSheet(filters: $filters)
        }
        .sheet(item: Binding(get: { reviewRecordID.map(RecordIDBox.init) }, set: { reviewRecordID = $0?.id })) { box in
            RecordReviewSheet(recordID: box.id)
        }
        .sheet(item: Binding(get: { store.duplicateSheetRequest }, set: { store.duplicateSheetRequest = $0 })) { prompt in
            RecordDuplicateSheet(prompt: prompt)
        }
        .onChange(of: store.navigationRequest) { _, id in
            guard let id else { return }
            store.navigationRequest = nil
            path.append(RecordsRoute.detail(id))
        }
        .sheet(isPresented: $showAddSheet, onDismiss: presentPendingAction) {
            AddRecordSheet { action in
                pendingAction = action
                showAddSheet = false
            }
        }
        .fullScreenCover(isPresented: $showScanner) {
            DocumentScannerView { data in
                showScanner = false
                guard let data else { return }
                importItems([RecordImportItem(payload: .data(data), source: .scan, importMethod: .documentScanner)])
            }
            .ignoresSafeArea()
        }
        .fullScreenCover(isPresented: $showCamera) {
            CameraView(image: $cameraImage, title: String(localized: "Photograph a record"), onCancel: { showCamera = false })
                .ignoresSafeArea()
        }
        .onChange(of: cameraImage) { _, image in
            guard let image else { return }
            cameraImage = nil
            showCamera = false
            guard let jpeg = image.jpegData(compressionQuality: 0.9) else { return }
            importItems([RecordImportItem(payload: .data(jpeg), source: .camera, importMethod: .camera)])
        }
        .photosPicker(isPresented: $showPhotoPicker, selection: $photoItems, maxSelectionCount: 20, matching: .images, preferredItemEncoding: .current)
        .onChange(of: photoItems) { _, items in
            guard !items.isEmpty else { return }
            photoItems = []
            Task { await importPhotos(items) }
        }
        .fileImporter(isPresented: $showFileImporter, allowedContentTypes: fileImporterTypes, allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result, !urls.isEmpty else { return }
            let items = urls.map {
                RecordImportItem(payload: .file($0), source: .import, importMethod: .filePicker, originalFilename: $0.lastPathComponent)
            }
            importItems(items)
        }
        .sheet(item: Binding(get: { textEntryMode.map(TextEntryModeBox.init) }, set: { textEntryMode = $0?.mode })) { box in
            RecordTextEntrySheet(mode: box.mode) { title, text in
                let item = box.mode == .note
                    ? RecordImportItem(payload: .text(text), source: .note, importMethod: .noteEditor, userTitle: title)
                    : RecordImportItem(payload: .text(text), source: .paste, importMethod: .pasteText, userTitle: title)
                importItems([item])
            }
        }
        .sheet(item: Binding(get: { store.duplicatePrompts.first }, set: { _ in })) { prompt in
            RecordDuplicateSheet(prompt: prompt)
        }
        .sheet(isPresented: Binding(get: { !shareSelection.isEmpty }, set: { if !$0 { shareSelection = [] } })) {
            ShareRecordScreen(recordIDs: shareSelection) { endSelection() }
        }
        .sheet(isPresented: $showPrivacy) {
            NavigationStack {
                ScrollView {
                    RecordsPrivacyExplainer()
                        .padding()
                }
                .navigationTitle("How Ayuvo handles your health records")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done") { showPrivacy = false }
                    }
                }
            }
            .presentationDetents([.medium, .large])
        }
        .alert("Couldn't import", isPresented: Binding(get: { store.importErrorMessage != nil }, set: { if !$0 { store.importErrorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(store.importErrorMessage ?? "")
        }
        .confirmationDialog(
            selection.count == 1 ? "Delete this record?" : "Delete \(selection.count) records?",
            isPresented: $showBulkDeleteConfirmation,
            titleVisibility: .visible
        ) {
            Button("Delete", role: .destructive) {
                let ids = Array(selection)
                Task {
                    await store.delete(ids: ids)
                    endSelection()
                }
            }
        } message: {
            Text("The records and their original files are removed from this iPhone. This can't be undone.")
        }
    }

    // MARK: - Content

    @ViewBuilder
    private var content: some View {
        if let error = store.loadError, store.records.isEmpty {
            ContentUnavailableView {
                Label("Couldn't load records", systemImage: "exclamationmark.triangle")
            } description: {
                Text(error)
            } actions: {
                Button("Retry") { Task { await store.reload() } }
            }
        } else {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0, pinnedViews: store.viewMode == .timeline ? [.sectionHeaders] : []) {
                    header
                        .padding(.horizontal)
                        .padding(.bottom, 8)

                    intelligenceSections

                    if store.isLoadingFirstPage && store.records.isEmpty {
                        skeletonRows
                    } else if let hits = store.searchState.hits, store.searchState.isActive {
                        if hits.isEmpty { emptyState } else { searchResults(hits) }
                    } else if store.records.isEmpty {
                        emptyState
                    } else {
                        if showsRecent { recentSection }
                        switch store.viewMode {
                        case .timeline: timeline
                        case .list: list
                        case .grid: grid
                        }
                        if store.hasMore {
                            ProgressView()
                                .frame(maxWidth: .infinity)
                                .padding()
                        }
                    }
                }
                .padding(.bottom, 24)
            }
            .scrollDismissesKeyboard(.immediately)
            .refreshable { await store.reload() }
        }
    }

    private var showsRecent: Bool {
        store.query.isUnfiltered && store.recent.count > 1 && !isSelecting
    }

    /// Chip query plus the Filters sheet.
    private var baseQuery: RecordQuery {
        var query = chip.query(text: "")
        if !filters.recordTypes.isEmpty {
            query.recordTypes = query.recordTypes.isEmpty ? filters.recordTypes : query.recordTypes.intersection(filters.recordTypes)
        }
        if !filters.categories.isEmpty {
            query.categories = query.categories.isEmpty ? filters.categories : query.categories.intersection(filters.categories)
        }
        query.dateFrom = filters.dateFrom
        query.dateTo = filters.dateTo
        query.doctor = filters.doctor
        query.facility = filters.facility
        query.doctorEntityID = filters.doctorEntityID
        query.facilityEntityID = filters.facilityEntityID
        query.flags = filters.flags
        query.tags = filters.tags
        query.aiProcessedOnly = filters.aiProcessedOnly
        query.userConfirmedOnly = filters.userConfirmedOnly
        return query
    }

    private var showsSections: Bool {
        !store.searchState.isActive && chip == .all && RecordsFilterSheet.activeCount(filters) == 0 && !isSelecting
    }

    @ViewBuilder
    private var intelligenceSections: some View {
        if store.searchState.isActive {
            searchHeader
                .padding(.horizontal)
                .padding(.bottom, 8)
        } else if showsSections {
            VStack(alignment: .leading, spacing: 12) {
                if store.hasLoadedOnce, store.aiMode == nil, store.totalCount > 0 || store.recent.isEmpty == false {
                    RecordsAIChooserCard()
                }
                RecordsProcessingStrip()
                if store.processingSummary.awaitingConsent > 0, !consentWaiting.isEmpty {
                    RecordAIConsentBanner(recordIDs: [consentWaiting[0]], waitingCount: consentWaiting.count)
                }
                if !store.needsReviewRecords.isEmpty { needsReviewSection }
                if store.nearDuplicateCount > 0 {
                    Button {
                        Task { await store.openFirstNearDuplicate() }
                    } label: {
                        Label(store.nearDuplicateCount == 1 ? String(localized: "1 possible duplicate to check") : String(localized: "\(store.nearDuplicateCount) possible duplicates to check"), systemImage: "doc.on.doc")
                            .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    }
                    .accessibilityIdentifier("records.nearDuplicates")
                }
                if !store.importantHighlights.isEmpty { highlightsSection }
            }
            .padding(.horizontal)
            .padding(.bottom, 8)
        }
    }

    private var needsReviewSection: some View {
        RecordsCard {
            RecordsSectionTitle(title: "Needs review", systemImage: "exclamationmark.circle.fill", trailing: "\(store.processingSummary.needsReview)")
            ForEach(store.needsReviewRecords.prefix(3)) { record in
                Button {
                    reviewRecordID = record.id
                } label: {
                    RecordRow(record: record, compact: true)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("records.needsReview.\(record.id)")
            }
            if store.processingSummary.needsReview > 3 {
                Button("Show all") { withAnimation(.snappy) { chip = .needsReview } }
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("records.needsReviewSection")
    }

    private var highlightsSection: some View {
        RecordsCard {
            RecordsSectionTitle(title: "Important highlights", systemImage: "waveform.path.ecg")
            ForEach(store.importantHighlights) { item in
                NavigationLink(value: RecordsRoute.detail(item.record.id)) {
                    VStack(alignment: .leading, spacing: 2) {
                        RecordHighlightRow(text: item.highlight.text)
                        Text("\(item.record.title) · \(RecordFormatting.dateText(item.record))")
                            .font(.system(.caption, design: .rounded))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .padding(.leading, 20)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("records.highlightsSection")
    }

    @ViewBuilder
    private var searchHeader: some View {
        let state = store.searchState
        VStack(alignment: .leading, spacing: 8) {
            if !state.visibleChips.isEmpty {
                RecordsSearchChipsRow(chips: state.visibleChips) { chip in
                    store.removeSearchChip(chip, base: baseQuery)
                }
            }
            if state.isRewriting {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("Searching smarter with AI…").font(.system(.footnote, design: .rounded))
                }
            } else if state.offerAIRewrite {
                Button {
                    Task { await store.rewriteSearchWithAI(base: baseQuery) }
                } label: {
                    Label("Search smarter with AI", systemImage: "sparkles")
                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                }
                .accessibilityIdentifier("records.search.smarter")
            }
            if !state.valueHits.isEmpty {
                valuesGroup(state.valueHits)
            }
            if state.aiParsed != nil {
                Text("AI turned your search into filters. Only the search text was sent.")
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            if store.processingSummary.activeRecords > 0 {
                Label("Some records are still processing and may not appear yet", systemImage: "hourglass")
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            }
        }
    }

    /// §23 "Values": observation chips "Hb 7.6 g/dL ↓ · CBC Sep 12" opening the record at the source.
    private func valuesGroup(_ hits: [RecordValueHit]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Values")
                .font(.system(.subheadline, design: .rounded, weight: .bold))
                .foregroundStyle(.secondary)
            ForEach(hits.prefix(8)) { hit in
                let o = hit.observation
                NavigationLink(value: RecordsRoute.detailSource(recordID: hit.record.id, observationID: o.id)) {
                    HStack(spacing: 8) {
                        Image(systemName: "drop.fill")
                            .font(.caption)
                            .foregroundStyle(o.flag.isAbnormal ? o.flag.tint : AppColors.calorie)
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(spacing: 4) {
                                Text(o.displayName())
                                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                                    .lineLimit(1)
                                Text(o.valueWithUnit)
                                    .font(.system(.subheadline, design: .rounded, weight: .bold))
                                    .monospacedDigit()
                                    .foregroundStyle(o.flag.isAbnormal ? o.flag.tint : .primary)
                                    .lineLimit(1)
                                if let symbol = o.flag.symbol {
                                    Text(symbol).font(.system(.subheadline, design: .rounded, weight: .bold)).foregroundStyle(o.flag.tint)
                                }
                            }
                            Text("\(hit.record.title) · \(o.observedDate.flatMap { RecordDates.date(fromDay: $0)?.formatted(date: .abbreviated, time: .omitted) } ?? RecordFormatting.dateText(hit.record))")
                                .font(.system(.caption, design: .rounded))
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        Spacer(minLength: 0)
                        Image(systemName: "chevron.right").font(.caption2).foregroundStyle(.tertiary)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 7)
                    .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("records.search.value.\(o.id)")
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("records.search.values")
    }

    private func searchResults(_ hits: [RecordSearchHit]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Records")
                .font(.system(.subheadline, design: .rounded, weight: .bold))
                .foregroundStyle(.secondary)
                .padding(.horizontal)
                .padding(.vertical, 6)
            ForEach(hits) { hit in
                recordLink(hit.record) {
                    RecordSearchResultRow(hit: hit)
                }
                .padding(.horizontal)
                Divider().padding(.leading, 76)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("records.searchResults")
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(RecordsFilterChip.allCases) { item in
                        chipButton(item)
                    }
                }
                .padding(.vertical, 2)
            }
            .scrollClipDisabled()
        }
        .padding(.top, 4)
    }

    private func chipButton(_ item: RecordsFilterChip) -> some View {
        let selected = chip == item
        return Button {
            withAnimation(.snappy) { chip = item }
        } label: {
            Text(item.title)
                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                .foregroundStyle(selected ? Color.white : Color.primary)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background {
                    if selected {
                        Capsule().fill(LinearGradient(colors: AppColors.calorieGradient, startPoint: .leading, endPoint: .trailing))
                    } else {
                        Capsule().fill(AppColors.appCard)
                    }
                }
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("records.chip.\(item.rawValue)")
    }

    private var recentSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Recent")
                .font(.system(.headline, design: .rounded))
                .padding(.horizontal)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 12) {
                    ForEach(store.recent) { record in
                        NavigationLink(value: RecordsRoute.detail(record.id)) {
                            RecordGridCell(record: record)
                                .frame(width: 104)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal)
            }
        }
        .padding(.vertical, 8)
    }

    private var groupedByMonth: [RecordMonthGroup] {
        var groups: [RecordMonthGroup] = []
        for record in store.records {
            if groups.last?.key == record.monthKey {
                groups[groups.count - 1].records.append(record)
            } else {
                groups.append(RecordMonthGroup(key: record.monthKey, records: [record]))
            }
        }
        return groups
    }

    private var timeline: some View {
        ForEach(groupedByMonth) { group in
            Section {
                ForEach(Array(group.records.enumerated()), id: \.element.id) { index, record in
                    let neighbours = store.episodeLinks[record.id]
                    let next = index + 1 < group.records.count ? group.records[index + 1] : nil
                    recordLink(record) {
                        RecordRow(record: record, isSelecting: isSelecting, isSelected: selection.contains(record.id), episode: neighbours != nil)
                    }
                    .padding(.horizontal)
                    .overlay(alignment: .bottomLeading) {
                        // Thin connector between consecutive linked rows (plan §3.10).
                        if let next, neighbours?.contains(next.id) == true, !isSelecting {
                            Capsule()
                                .fill(Color.teal.opacity(0.55))
                                .frame(width: 3, height: 18)
                                .offset(x: 38.5, y: 9)
                                .accessibilityHidden(true)
                        }
                    }
                    .zIndex(neighbours == nil ? 0 : 1)
                    Divider().padding(.leading, 76)
                }
            } header: {
                Text(RecordFormatting.monthTitle(group.key))
                    .font(.system(.subheadline, design: .rounded, weight: .bold))
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal)
                    .padding(.vertical, 6)
                    .background(AppColors.appBackground)
            }
        }
    }

    private var list: some View {
        VStack(spacing: 0) {
            ForEach(store.records) { record in
                recordLink(record) {
                    RecordRow(record: record, isSelecting: isSelecting, isSelected: selection.contains(record.id), compact: true)
                }
                .padding(.horizontal)
                Divider().padding(.leading, 68)
            }
        }
        .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .padding(.horizontal)
    }

    private var grid: some View {
        LazyVGrid(columns: gridColumns, alignment: .leading, spacing: 16) {
            ForEach(store.records) { record in
                recordLink(record) {
                    RecordGridCell(record: record, isSelecting: isSelecting, isSelected: selection.contains(record.id))
                }
            }
        }
        .padding(.horizontal)
    }

    @ViewBuilder
    private func recordLink<RowLabel: View>(_ record: HealthRecord, @ViewBuilder label: () -> RowLabel) -> some View {
        Group {
            if isSelecting {
                Button {
                    toggleSelection(record.id)
                } label: {
                    label()
                }
            } else {
                NavigationLink(value: RecordsRoute.detail(record.id)) {
                    label()
                }
            }
        }
        .buttonStyle(.plain)
        .onAppear { store.loadNextPageIfNeeded(currentID: record.id) }
        .contextMenu {
            Button {
                Task { await store.setFavorite(ids: [record.id], !record.favorite) }
            } label: {
                Label(record.favorite ? "Unfavorite" : "Favorite", systemImage: record.favorite ? "star.slash" : "star")
            }
            Button {
                Task { await store.setArchived(ids: [record.id], !record.archived) }
            } label: {
                Label(record.archived ? "Unarchive" : "Archive", systemImage: "archivebox")
            }
            Button {
                isSelecting = true
                selection = [record.id]
            } label: {
                Label("Select", systemImage: "checkmark.circle")
            }
        }
        .accessibilityIdentifier("records.row.\(record.id)")
    }

    private var skeletonRows: some View {
        VStack(spacing: 12) {
            ForEach(0..<6, id: \.self) { _ in
                HStack(spacing: 12) {
                    RoundedRectangle(cornerRadius: 10).fill(AppColors.appCard).frame(width: 48, height: 48)
                    VStack(alignment: .leading, spacing: 6) {
                        RoundedRectangle(cornerRadius: 4).fill(AppColors.appCard).frame(height: 12)
                        RoundedRectangle(cornerRadius: 4).fill(AppColors.appCard).frame(width: 120, height: 10)
                    }
                }
            }
        }
        .padding()
        .redacted(reason: .placeholder)
    }

    @ViewBuilder
    private var emptyState: some View {
        if store.query.isUnfiltered {
            VStack(spacing: 16) {
                Image(systemName: "list.clipboard.fill")
                    .font(.system(size: 52))
                    .foregroundStyle(AppColors.calorie)
                    .padding(.top, 36)
                Text("Keep all your health records in one place")
                    .font(.system(.title3, design: .rounded, weight: .bold))
                    .multilineTextAlignment(.center)
                Text("Lab reports, prescriptions, scans and notes — saved as the original.")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                VStack(spacing: 10) {
                    if AddRecordAction.isScannerSupported {
                        emptyButton(.scan, prominent: true)
                    }
                    emptyButton(.files, prominent: !AddRecordAction.isScannerSupported)
                    emptyButton(.paste, prominent: false)
                }
                .padding(.top, 4)
                Button {
                    showPrivacy = true
                } label: {
                    Label("Stored on this iPhone and not included in iCloud backup.", systemImage: "lock.fill")
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .buttonStyle(.plain)
            }
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 32)
        } else {
            ContentUnavailableView {
                Label(store.searchState.isActive ? "No results for \"\(store.searchState.text.trimmingCharacters(in: .whitespaces))\"" : "No records here", systemImage: "magnifyingglass")
            } description: {
                Text("Check the spelling or remove filters.")
            }
            .padding(.top, 40)
        }
    }

    private func emptyButton(_ action: AddRecordAction, prominent: Bool) -> some View {
        Button {
            perform(action)
        } label: {
            Label(action.title, systemImage: action.systemImage)
                .font(.system(.headline, design: .rounded))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 6)
        }
        .buttonStyle(ProminentOrBorderedButtonStyle(prominent: prominent))
        .accessibilityIdentifier("records.empty.\(action.rawValue)")
    }

    // MARK: - Toolbar & selection

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            if !store.records.isEmpty {
                Button(isSelecting ? "Done" : "Select") {
                    if isSelecting { endSelection() } else { isSelecting = true }
                }
            }
        }
        ToolbarItem(placement: .topBarTrailing) {
            Menu {
                ForEach(RecordsViewMode.allCases) { mode in
                    Button {
                        store.viewMode = mode
                    } label: {
                        Label(mode.title, systemImage: mode.systemImage)
                    }
                    .accessibilityIdentifier("records.viewMode.\(mode.title.lowercased())")
                }
            } label: {
                Image(systemName: store.viewMode.systemImage)
                    .font(.system(size: 18))
            }
            .accessibilityLabel("View settings")
            .accessibilityIdentifier("records.viewMode")
        }
        ToolbarItem(placement: .topBarTrailing) {
            Button {
                showFilters = true
            } label: {
                let count = RecordsFilterSheet.activeCount(filters)
                Image(systemName: count > 0 ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease.circle")
                    .font(.system(size: 20))
                    .overlay(alignment: .topTrailing) {
                        if count > 0 {
                            Text("\(count)")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundStyle(.white)
                                .padding(3)
                                .background(AppColors.calorie, in: Circle())
                                .offset(x: 6, y: -6)
                        }
                    }
            }
            .accessibilityLabel("Filters")
            .accessibilityIdentifier("records.filters")
        }
        ToolbarItem(placement: .topBarTrailing) {
            Button {
                showAddSheet = true
            } label: {
                Image(systemName: "plus.circle.fill")
                    .font(.system(size: 22))
            }
            .accessibilityLabel("Add record")
            .accessibilityIdentifier("records.add")
        }
    }

    private var selectionBar: some View {
        HStack(spacing: 4) {
            Button {
                let ids = Array(selection)
                let allFavorite = store.records.filter { selection.contains($0.id) }.allSatisfy(\.favorite)
                Task {
                    await store.setFavorite(ids: ids, !allFavorite)
                    endSelection()
                }
            } label: {
                Label("Favorite", systemImage: "star")
            }
            .help("Favorite")
            .accessibilityLabel("Favorite")
            Spacer()
            Button {
                let ids = Array(selection)
                let archive = !chip.query(text: "").archivedOnly
                Task {
                    await store.setArchived(ids: ids, archive)
                    endSelection()
                }
            } label: {
                Label(chip == .archived ? "Unarchive" : "Archive", systemImage: "archivebox")
            }
            .help(chip == .archived ? "Unarchive" : "Archive")
            .accessibilityLabel(chip == .archived ? "Unarchive" : "Archive")
            Spacer()
            Button {
                let ids = Array(selection)
                Task {
                    let refs = await store.recordRefs(ids: ids)
                    chatStore.requestHandoff(records: refs, prompt: "")
                    endSelection()
                }
            } label: {
                Label("Ask Coach", systemImage: "bubble.left.and.text.bubble.right")
            }
            .help("Ask Coach")
            .accessibilityLabel("Ask Coach")
            .accessibilityIdentifier("records.selection.askCoach")
            Spacer()
            Button {
                shareSelection = Array(selection).sorted()
            } label: {
                Label("Share", systemImage: "square.and.arrow.up")
            }
            .help("Share")
            .accessibilityLabel("Share")
            .accessibilityIdentifier("records.selection.share")
            Spacer()
            Button(role: .destructive) {
                showBulkDeleteConfirmation = true
            } label: {
                Label("Delete", systemImage: "trash")
            }
            .help("Delete")
            .accessibilityLabel("Delete")
        }
        .labelStyle(.iconOnly)
        .font(.system(size: 22, weight: .regular))
        .disabled(selection.isEmpty)
        .padding(.horizontal, 24)
        .padding(.vertical, 12)
        .background(.bar)
    }

    private func toggleSelection(_ id: String) {
        if selection.contains(id) {
            selection.remove(id)
        } else {
            selection.insert(id)
        }
    }

    private func endSelection() {
        isSelecting = false
        selection = []
    }

    // MARK: - Import flows

    private func presentPendingAction() {
        guard let action = pendingAction else { return }
        pendingAction = nil
        perform(action)
    }

    private func perform(_ action: AddRecordAction) {
        switch action {
        case .scan:
            showScanner = AddRecordAction.isScannerSupported
        case .camera:
            showCamera = AddRecordAction.isCameraAvailable
        case .photos:
            showPhotoPicker = true
        case .files:
            fileImporterTypes = [.pdf, .image, .plainText, .text]
            showFileImporter = true
        case .importPDF:
            fileImporterTypes = [.pdf]
            showFileImporter = true
        case .paste:
            textEntryMode = .paste
        case .note:
            textEntryMode = .note
        }
    }

    private func importItems(_ items: [RecordImportItem]) {
        Task { await store.importItems(items) }
    }

    /// Loads each picked photo's original bytes (HEIC/JPEG as stored in the library).
    private func importPhotos(_ items: [PhotosPickerItem]) async {
        var imports: [RecordImportItem] = []
        for item in items {
            if let data = try? await item.loadTransferable(type: Data.self) {
                imports.append(RecordImportItem(payload: .data(data), source: .photos, importMethod: .photoPicker))
            }
        }
        if imports.count < items.count {
            store.importErrorMessage = String(localized: "Some photos couldn't be loaded.")
        }
        await store.importItems(imports)
    }
}

private struct RecordMonthGroup: Identifiable {
    let key: String
    var records: [HealthRecord]
    var id: String { key }
}

private struct RecordIDBox: Identifiable {
    let id: String
}

private struct TextEntryModeBox: Identifiable {
    let mode: RecordTextEntrySheet.Mode
    var id: String { mode == .paste ? "paste" : "note" }
}

private struct ProminentOrBorderedButtonStyle: PrimitiveButtonStyle {
    let prominent: Bool

    func makeBody(configuration: Configuration) -> some View {
        if prominent {
            Button(role: configuration.role, action: configuration.trigger) { configuration.label }
                .buttonStyle(.borderedProminent)
                .tint(AppColors.calorie)
        } else {
            Button(role: configuration.role, action: configuration.trigger) { configuration.label }
                .buttonStyle(.bordered)
                .tint(AppColors.calorie)
        }
    }
}
