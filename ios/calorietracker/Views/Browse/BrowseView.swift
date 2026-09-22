import SwiftUI

/// Browse tab root (docs/ui-structure.md §2): searchable domain list in catalog order, Apple Health
/// permission when needed, and the Health sync status row.
struct BrowseView: View {
    @Environment(AppNavigator.self) private var navigator
    @Environment(HealthDataStore.self) private var store
    @Environment(FoodStore.self) private var foodStore
    @Environment(WaterStore.self) private var waterStore
    @Environment(FastingStore.self) private var fastingStore
    @Environment(MedicationStore.self) private var medicationStore
    @Environment(RecordsStore.self) private var recordsStore
    @Environment(WeightStore.self) private var weightStore
    @Environment(BodyFatStore.self) private var bodyFatStore
    @Environment(ProfileStore.self) private var profileStore
    @AppStorage(WaterSettings.enabledKey) private var waterTrackingEnabled = false
    @AppStorage(FastingSettings.enabledKey) private var fastingTrackingEnabled = false

    @State private var query = ""
    @State private var showExport = false
    @State private var showImport = false
    @State private var isRebuilding = false
    @State private var showLogWeight = false
    @State private var showLogBodyFat = false

    private var categories: [BrowseCategory] {
        BrowseCategory.ordered.filter { category in
            switch category {
            case .hydration: return waterTrackingEnabled || !waterStore.entries.isEmpty
            case .fasting: return fastingTrackingEnabled || !fastingStore.sessions.isEmpty
            default: return true
            }
        }
    }

    var body: some View {
        @Bindable var navigator = navigator
        NavigationStack(path: $navigator.browsePath) {
            List {
                if !query.trimmingCharacters(in: .whitespaces).isEmpty {
                    searchSection
                } else {
                    if !store.isEnabled || (store.needsGrant == true && !store.hasAnyData) {
                        Section {
                            HealthPermissionView(compact: true)
                        }
                    }

                    Section {
                        ForEach(categories) { category in
                            BrowseCategoryRow(
                                category: category,
                                subtitle: subtitle(for: category),
                                dimmed: isDimmed(category)
                            )
                        }
                    }

                    Section {
                        BrowseHealthStatusRow()
                    } footer: {
                        Text("Apple Health data is kept on this device, never stored in iCloud backup, and shared with your AI provider only through Coach.")
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Browse")
            .navigationBarTitleDisplayMode(.large)
            .searchable(text: $query, prompt: Text("Search"))
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    actionsMenu
                }
            }
            .refreshable {
                _ = await store.sync(.manual)
            }
            .sheet(isPresented: $showExport) {
                ExportHealthDataView()
            }
            .sheet(isPresented: $showImport) {
                ImportHealthDataView()
            }
            .sheet(isPresented: $showLogWeight) {
                LogWeightSheet(currentWeightKg: weightStore.latestEntry?.weightKg ?? profileStore.profile.weightKg) { weightKg in
                    weightStore.addEntry(WeightEntry(weightKg: weightKg))
                }
            }
            .sheet(isPresented: $showLogBodyFat) {
                let seed = bodyFatStore.latestEntry?.bodyFatFraction ?? profileStore.profile.bodyFatPercentage ?? 0.20
                LogBodyFatSheet(currentFraction: seed) { fraction in
                    bodyFatStore.addEntry(BodyFatEntry(bodyFatFraction: fraction))
                }
            }
            .task(id: store.snapshotRevision) {
                if store.typeSummaries.isEmpty {
                    await store.refreshSnapshots()
                }
            }
            .task {
                if store.isEnabled, store.needsGrant == nil {
                    await store.refreshAuthorizationStatus()
                }
            }
            .browseRouteDestinations(path: $navigator.browsePath)
            .healthRouteDestinations()
            .medicationRouteDestinations()
            .recordsRouteDestinations()
        }
    }

    // MARK: Rows

    private func hasHealthData(_ category: HealthCategory) -> Bool {
        store.types(in: category).contains { (store.summary(for: $0.id)?.count ?? 0) > 0 }
    }

    private func isDimmed(_ category: BrowseCategory) -> Bool {
        if let health = category.healthCategory { return !hasHealthData(health) }
        return false
    }

    private func subtitle(for category: BrowseCategory) -> String? {
        switch category {
        case .nutrition:
            let kcal = foodStore.calories(for: .now)
            return kcal > 0 ? String(localized: "\(kcal.formatted()) kcal today") : nil
        case .hydration:
            let ml = waterStore.total(on: .now)
            guard ml > 0 else { return nil }
            return String(localized: "\(MetricCatalog.waterUnit.formatted(milliliters: ml)) today")
        case .fasting:
            return fastingStore.activeSession == nil ? nil : String(localized: "Fasting now")
        case .medications:
            let active = medicationStore.activeCount
            return active > 0 ? (active == 1 ? String(localized: "1 active medicine") : String(localized: "\(active) active medicines")) : nil
        case .records:
            let count = recordsStore.totalCount
            guard recordsStore.hasLoadedOnce, count > 0 else { return nil }
            return count == 1 ? String(localized: "1 record") : String(localized: "\(count) records")
        default:
            guard let health = category.healthCategory else { return nil }
            let withData = store.types(in: health).filter { (store.summary(for: $0.id)?.count ?? 0) > 0 }.count
            if withData == 0 { return String(localized: "No data yet") }
            return withData == 1 ? String(localized: "1 data type") : String(localized: "\(withData) data types")
        }
    }

    // MARK: Search

    /// Search results: "Go to" features (destinations and actions) first, then metric trends.
    @ViewBuilder
    private var searchSection: some View {
        let features = BrowseFeatureCatalog.search(query)
        let results = MetricCatalog.search(query, health: store).filter { !$0.browseHidden }
        if features.isEmpty && results.isEmpty {
            Section {
                ContentUnavailableView.search(text: query)
            }
        } else {
            if !features.isEmpty {
                Section {
                    ForEach(features) { feature in
                        featureRow(feature)
                    }
                } header: {
                    Text("Go to")
                }
            }
            if !results.isEmpty {
                Section {
                    ForEach(results) { descriptor in
                        NavigationLink(value: MetricRoute.detail(descriptor.key)) {
                            searchRow(descriptor)
                        }
                        .accessibilityIdentifier("browse.metric.\(descriptor.key.id)")
                    }
                } header: {
                    Text("Trends")
                }
            }
        }
    }

    private func featureRow(_ feature: BrowseFeature) -> some View {
        Button {
            open(feature)
        } label: {
            HStack {
                MetricRow(systemImage: feature.systemImage, tint: feature.tint, title: feature.title, subtitle: feature.subtitle)
                Image(systemName: feature.leavesBrowse ? "arrow.up.forward" : "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(feature.accessibilityID)
    }

    private func open(_ feature: BrowseFeature) {
        switch feature.destination {
        case .browse(let routes):
            navigator.openBrowse(routes)
        case .metric(let key):
            navigator.browsePath.append(MetricRoute.detail(key))
        case .logFood(let action):
            navigator.openNutrition(action: action)
        case .logWeight:
            showLogWeight = true
        case .logBodyFat:
            showLogBodyFat = true
        case .logWorkout:
            navigator.openWorkoutLogging()
        case .addMedication:
            medicationStore.addMedicationRequested = true
            navigator.openMedications()
        case .recordsTab:
            navigator.selectedTab = .records
        case .addRecord:
            recordsStore.requestAddRecord()
        case .coach:
            navigator.selectedTab = .coach
        case .settings:
            navigator.openSettings()
        }
    }

    @ViewBuilder
    private func searchRow(_ descriptor: MetricDescriptor) -> some View {
        switch descriptor.key {
        case .app(let metric):
            AppMetricLinkRow(metric: metric)
        case .health(let id):
            let type = store.metricType(for: id)
            HealthMetricRow(type: type, summary: store.summary(for: id))
        }
    }

    // MARK: Actions

    private var actionsMenu: some View {
        Menu {
            Button {
                Task { _ = await store.sync(.manual) }
            } label: {
                Label("Sync Now", systemImage: "arrow.triangle.2.circlepath")
            }
            .disabled(!store.isEnabled || store.isSyncing)
            NavigationLink(value: MetricRoute.favourites) {
                Label("Edit Favourites", systemImage: "star")
            }
            Divider()
            Button {
                showExport = true
            } label: {
                Label("Export Health Data", systemImage: "square.and.arrow.up")
            }
            .disabled(!store.hasAnyData)
            Button {
                showImport = true
            } label: {
                Label("Import Health Data", systemImage: "square.and.arrow.down")
            }
            Divider()
            Button {
                guard !isRebuilding else { return }
                isRebuilding = true
                Task {
                    await store.rebuildRollups()
                    isRebuilding = false
                }
            } label: {
                Label("Rebuild Summaries", systemImage: "wand.and.stars")
            }
            .disabled(isRebuilding || !store.hasAnyData)
        } label: {
            Image(systemName: "ellipsis.circle")
        }
        .accessibilityLabel(Text("More"))
        .accessibilityIdentifier("browse.more")
    }
}

/// One domain row; the destination follows the catalog `target`.
struct BrowseCategoryRow: View {
    let category: BrowseCategory
    var subtitle: String?
    var dimmed = false
    @Environment(AppNavigator.self) private var navigator

    var body: some View {
        let row = MetricRow(systemImage: category.systemImage, tint: category.tint, title: category.title, subtitle: subtitle, dimmed: dimmed)
        Group {
            switch category.target {
            case "screen:nutrition":
                NavigationLink(value: BrowseRoute.nutrition) { row }
            case "screen:fasting":
                NavigationLink(value: BrowseRoute.fasting) { row }
            case "screen:body":
                NavigationLink(value: BrowseRoute.body) { row }
            case "screen:activity":
                NavigationLink(value: BrowseRoute.activity) { row }
            case "screen:medications":
                NavigationLink(value: BrowseRoute.medications) { row }
            case "tab:records":
                Button {
                    navigator.selectedTab = .records
                } label: {
                    HStack {
                        row
                        Image(systemName: "arrow.up.forward")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.tertiary)
                    }
                }
                .buttonStyle(.plain)
            default:
                if category.target.hasPrefix("metric:"), let key = MetricKey(pinID: String(category.target.dropFirst("metric:".count))) {
                    NavigationLink(value: MetricRoute.detail(key)) { row }
                } else if let health = category.healthCategory {
                    NavigationLink(value: HealthRoute.category(health)) { row }
                } else {
                    row
                }
            }
        }
        .accessibilityIdentifier("browse.row.\(category.rawValue)")
    }
}

/// Footer row: Apple Health connection and last sync; opens Settings.
struct BrowseHealthStatusRow: View {
    @Environment(HealthDataStore.self) private var store
    @Environment(AppNavigator.self) private var navigator

    private var status: String {
        if !store.isEnabled { return String(localized: "Off") }
        if store.isSyncing { return String(localized: "Syncing…") }
        if let last = store.lastSyncAt {
            return String(localized: "Last synced \(HealthUnitFormatting.relativeText(last))")
        }
        return String(localized: "Connected")
    }

    var body: some View {
        Button {
            navigator.openSettings(.healthData)
        } label: {
            HStack {
                MetricRow(systemImage: "heart.fill", tint: AyuvoPalette.heart, title: String(localized: "Apple Health"), subtitle: status)
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("browse.healthStatus")
    }
}
