import SwiftUI

/// Apple-Health-Browse-style hub: status, pinned types, 13 categories (empty ones
/// dimmed and last), search across every type, pull-to-refresh.
struct HealthHubView: View {
    /// `true` when the hub is pushed as its own screen (nav title, `.searchable`, "…" toolbar menu).
    /// The Health tab embeds it with `false`: its navigation bar stays hidden so the
    /// Progress | Health Data selector never moves, and search + actions live in the list.
    var showsNavigationChrome = true

    @Environment(HealthDataStore.self) private var store
    @State private var query = ""
    @FocusState private var isSearchFocused: Bool
    @State private var showExport = false
    @State private var showImport = false
    @State private var isRebuilding = false

    private var categoriesWithData: [HealthCategory] {
        HealthCategory.allCases.filter { hasData($0) }
    }

    private var categoriesWithoutData: [HealthCategory] {
        HealthCategory.allCases.filter { !hasData($0) }
    }

    private func hasData(_ category: HealthCategory) -> Bool {
        store.types(in: category).contains { (store.summary(for: $0.id)?.count ?? 0) > 0 }
    }

    private var searchResults: [HealthMetricType] {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !needle.isEmpty else { return [] }
        return store.knownTypes
            .filter { $0.displayName.localizedCaseInsensitiveContains(needle) || $0.id.localizedCaseInsensitiveContains(needle) }
            .sorted { lhs, rhs in
                let l = store.summary(for: lhs.id)?.count ?? 0, r = store.summary(for: rhs.id)?.count ?? 0
                if (l > 0) != (r > 0) { return l > 0 }
                return lhs.displayName.localizedCaseInsensitiveCompare(rhs.displayName) == .orderedAscending
            }
    }

    var body: some View {
        List {
            if !showsNavigationChrome {
                embeddedSearchRow
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                    .listRowSeparator(.hidden)
            }

            Section {
                HealthSyncStatusView()
                    .listRowBackground(AppColors.appCard)
            }

            if !store.isEnabled || (store.needsGrant == true && !store.hasAnyData) {
                Section {
                    HealthPermissionView(compact: true)
                        .listRowBackground(AppColors.appCard)
                }
            }

            if !query.isEmpty {
                Section {
                    if searchResults.isEmpty {
                        ContentUnavailableView.search(text: query)
                    } else {
                        ForEach(searchResults) { type in
                            NavigationLink(value: HealthRoute.metric(type.id)) {
                                HealthMetricRow(type: type, summary: store.summary(for: type.id))
                            }
                        }
                    }
                }
                .listRowBackground(AppColors.appCard)
            } else {
                if !store.pinnedTypeIDs.isEmpty, store.hasAnyData {
                    Section {
                        ForEach(store.pinnedTypeIDs, id: \.self) { typeID in
                            let type = store.metricType(for: typeID)
                            NavigationLink(value: HealthRoute.metric(typeID)) {
                                HealthMetricRow(type: type, summary: store.summary(for: typeID))
                            }
                        }
                    } header: {
                        Text("Pinned")
                    }
                    .listRowBackground(AppColors.appCard)
                }

                Section {
                    ForEach(categoriesWithData) { category in
                        categoryRow(category, dimmed: false)
                    }
                    ForEach(categoriesWithoutData) { category in
                        categoryRow(category, dimmed: true)
                    }
                } header: {
                    Text("Health Categories")
                } footer: {
                    Text("Kept on this device, never stored in iCloud backup, shared with your AI provider only through Coach. Delete or edit records in the Health app.")
                        .font(.system(.caption2, design: .rounded))
                }
                .listRowBackground(AppColors.appCard)

                if store.databaseSizeBytes > 0 {
                    Section {
                        LabeledContent("Storage used", value: HealthUnitFormatting.byteCountText(store.databaseSizeBytes))
                            .font(.system(.footnote, design: .rounded))
                    }
                    .listRowBackground(AppColors.appCard)
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.appBackground)
        .scrollDismissesKeyboard(.immediately)
        // Embedded under the Health tab's selector: trim the grouped list's top inset so the
        // search row sits snugly below the header instead of floating in empty space.
        .contentMargins(.top, showsNavigationChrome ? nil : 4, for: .scrollContent)
        .listSectionSpacing(showsNavigationChrome ? .default : .compact)
        .modifier(HealthHubNavigationChrome(
            isEnabled: showsNavigationChrome,
            query: $query,
            actionsMenu: actionsMenu {
                Image(systemName: "ellipsis.circle")
            }
        ))
        .refreshable {
            _ = await store.sync(.manual)
        }
        .sheet(isPresented: $showExport) {
            ExportHealthDataView()
        }
        .sheet(isPresented: $showImport) {
            ImportHealthDataView()
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
    }

    /// Search field + circular "…" menu shown in place of the navigation bar when embedded.
    private var embeddedSearchRow: some View {
        HStack(spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
                TextField(String(localized: "Search data types"), text: $query)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.search)
                    .focused($isSearchFocused)
                    .accessibilityIdentifier("healthHub.search")
                if !query.isEmpty {
                    Button {
                        query = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(.tertiary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("Clear search"))
                }
            }
            .font(.system(.body, design: .rounded))
            .padding(.horizontal, 12)
            .frame(height: 40)
            .background(AppColors.appCard, in: Capsule())

            actionsMenu {
                Image(systemName: "ellipsis")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(AppColors.calorie)
                    .frame(width: 40, height: 40)
                    .background(AppColors.appCard, in: Circle())
                    .contentShape(Circle())
            }
            .accessibilityIdentifier("healthHub.moreMenu")
        }
    }

    /// The hub's actions, shared by the toolbar (pushed hub) and the embedded search row.
    private func actionsMenu<MenuLabel: View>(@ViewBuilder label: () -> MenuLabel) -> some View {
        Menu {
            Button {
                Task { _ = await store.sync(.manual) }
            } label: {
                Label("Sync Now", systemImage: "arrow.triangle.2.circlepath")
            }
            .disabled(!store.isEnabled || store.isSyncing)
            NavigationLink(value: HealthRoute.pins) {
                Label("Edit Home Tiles", systemImage: "square.grid.2x2")
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
            label()
        }
        .accessibilityLabel(Text("More"))
    }

    private func categoryRow(_ category: HealthCategory, dimmed: Bool) -> some View {
        let types = store.types(in: category)
        let withData = types.filter { (store.summary(for: $0.id)?.count ?? 0) > 0 }
        return NavigationLink(value: HealthRoute.category(category)) {
            HStack(spacing: 12) {
                HealthIconBubble(systemImage: category.systemImage, tint: category.tint)
                VStack(alignment: .leading, spacing: 2) {
                    Text(category.displayName)
                        .font(.system(.body, design: .rounded, weight: .medium))
                    Text(withData.isEmpty
                        ? String(localized: "No data yet")
                        : String(localized: "\(withData.count) data types"))
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }
            .opacity(dimmed ? 0.55 : 1)
        }
    }
}

/// Title, `.searchable` and the trailing toolbar menu for the pushed hub. Skipped entirely
/// when the hub is embedded in the Health tab, whose navigation bar stays hidden.
private struct HealthHubNavigationChrome<ActionsMenu: View>: ViewModifier {
    let isEnabled: Bool
    @Binding var query: String
    let actionsMenu: ActionsMenu

    func body(content: Content) -> some View {
        if isEnabled {
            content
                .navigationTitle("Health Data")
                .navigationBarTitleDisplayMode(.large)
                .searchable(text: $query, prompt: Text("Search data types"))
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        actionsMenu
                    }
                }
        } else {
            content
        }
    }
}

struct HealthIconBubble: View {
    let systemImage: String
    let tint: Color

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(tint)
            .frame(width: 32, height: 32)
            .background(tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 9, style: .continuous))
            .accessibilityHidden(true)
    }
}

/// One data type row: name, latest value + unit, relative time. Cumulative / duration /
/// session types read as the latest day's total ("18,465 · Today"), not the last chunk.
struct HealthMetricRow: View {
    let type: HealthMetricType
    let summary: HealthTypeSummary?
    @Environment(HealthDataStore.self) private var store

    private var dayTotal: HealthDayTotal? {
        guard (summary?.count ?? 0) > 0 else { return nil }
        return store.dayTotal(for: type.id)
    }

    private var latestText: String {
        if let dayTotal {
            return HealthUnitFormatting.display(dayTotal.value, type: type).text
        }
        guard let latest = summary?.latest else { return "—" }
        if type.isBloodPressure {
            return HealthUnitFormatting.bloodPressureText(systolic: latest.value, diastolic: latest.value2) + " mmHg"
        }
        if type.kind == .category, type.isSleep == false {
            return latest.valueText ?? String(localized: "Logged")
        }
        if let value = latest.value {
            return HealthUnitFormatting.display(value, type: type).text
        }
        return latest.valueText ?? "—"
    }

    private var captionText: String? {
        if let dayTotal {
            return dayTotal.isToday ? String(localized: "Today") : HealthUnitFormatting.dayText(dayTotal.day, calendar: store.calendar)
        }
        return summary?.latest.map { HealthUnitFormatting.relativeText($0.endDate) }
    }

    var body: some View {
        HStack(spacing: 12) {
            HealthIconBubble(systemImage: type.category.systemImage, tint: type.category.tint)
            VStack(alignment: .leading, spacing: 2) {
                Text(type.displayName)
                    .font(.system(.body, design: .rounded, weight: .medium))
                    .lineLimit(2)
                if let captionText {
                    Text(captionText)
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                } else {
                    Text("No data yet")
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.tertiary)
                }
            }
            Spacer()
            Text(latestText)
                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                .foregroundStyle(summary?.latest == nil ? .tertiary : .primary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .accessibilityElement(children: .combine)
    }
}
