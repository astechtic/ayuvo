import SwiftUI
import UniformTypeIdentifiers

/// Browse › Medications (docs §8): summary, Today timeline, as-needed medicines, the searchable
/// list with status chips, add / import entry points and the archive export. Pushed on the Browse
/// stack, so it uses the real navigation bar and pushes its detail screens through `path`.
struct MedicationsHomeView: View {
    @Binding var path: NavigationPath
    @Environment(MedicationStore.self) private var store
    @Environment(NotificationManager.self) private var notificationManager
    @AppStorage("notificationsEnabled") private var notificationsEnabled = false

    @State private var showAddForm = false
    @State private var prnMedication: Medication?
    @State private var doseItem: MedicationTodayTimeline.Item?
    @State private var showRecordPicker = false
    @State private var exportDocument: MedicationArchiveDocument?
    @State private var showExporter = false
    @State private var showImporter = false
    @State private var importMessage: String?
    @State private var isExporting = false

    private var timeline: MedicationTodayTimeline { store.today }

    var body: some View {
        @Bindable var store = store
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) {
                if let error = store.openError {
                    RecordsCard {
                        Label("Medications couldn't be opened.", systemImage: "exclamationmark.triangle")
                            .font(.system(.subheadline, design: .rounded, weight: .semibold))
                        Text(error)
                            .font(.system(.caption2, design: .rounded))
                            .foregroundStyle(.secondary)
                            .lineLimit(3)
                        Button("Try again") { Task { await store.reload() } }
                            .buttonStyle(.bordered)
                            .tint(AppColors.calorie)
                            .controlSize(.small)
                    }
                } else if !store.hasLoadedOnce {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .padding(.top, 40)
                } else if store.totalCount == 0 {
                    emptyState
                } else {
                    permissionBanner
                    if timeline.summary.total > 0 {
                        MedicationSummaryStrip(summary: timeline.summary)
                    }
                    if !timeline.slots.isEmpty {
                        MedicationTodaySection(
                            timeline: timeline,
                            onTake: { item in Task { await take(item) } },
                            onSkip: { item in Task { await skip(item) } },
                            onSnooze: { item, minutes in Task { await snooze(item, minutes: minutes) } },
                            onSelect: { item in doseItem = item }
                        )
                    } else if store.activeCount > 0, timeline.prn.isEmpty {
                        RecordsCard {
                            Label("No doses scheduled today", systemImage: "calendar")
                                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                            Text("Medicines on specific days or with a later start date show here on their days.")
                                .font(.system(.caption, design: .rounded))
                                .foregroundStyle(.secondary)
                        }
                    }
                    if !timeline.prn.isEmpty {
                        MedicationPRNSection(
                            timeline: timeline,
                            onLog: { prnMedication = $0 },
                            onSelect: { path.append(MedicationRoute.detail($0.id)) }
                        )
                    }
                    allMedications
                }
                MedicationDisclaimerFooter()
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .background(AppColors.appBackground)
        .navigationTitle("Medications")
        .navigationBarTitleDisplayMode(.large)
        .searchable(text: $store.searchText, prompt: Text("Search medicines"))
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                overflowMenu
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showAddForm = true
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel(Text("Add medication"))
                .accessibilityIdentifier("medications.add")
            }
        }
        .refreshable {
            await store.materializeMissedAndCompletions()
            await store.reload()
        }
        .task {
            await store.openIfNeeded()
            if let route = store.navigationRequest {
                store.navigationRequest = nil
                path.append(route)
            }
            if !store.hasLoadedOnce {
                await store.materializeMissedAndCompletions()
                await store.reload()
            }
        }
        .onChange(of: store.navigationRequest) { _, route in
            guard let route else { return }
            store.navigationRequest = nil
            path.append(route)
        }
        .overlay(alignment: .top) {
            if let banner = store.banner {
                RecordsBannerView(banner: banner)
                    .padding(.top, 8)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.snappy, value: store.banner)
        .sheet(isPresented: $showAddForm) {
            MedicationFormView(mode: .create())
        }
        .sheet(item: $prnMedication) { medication in
            PRNLogSheet(medication: medication)
        }
        .sheet(item: $doseItem) { item in
            if let medication = store.today.medication(item.medicationID) {
                DoseActionSheet(item: item, medication: medication)
            }
        }
        .sheet(isPresented: $showRecordPicker) {
            RecordPickerSheet { record in
                showRecordPicker = false
                path.append(MedicationRoute.importFromRecord(record.id))
            }
        }
        .fileExporter(isPresented: $showExporter, document: exportDocument, contentType: .json,
                      defaultFilename: "ayuvo-medications") { result in
            exportDocument = nil
            if case .success = result {
                store.showBanner(String(localized: "Medications exported"), systemImage: "square.and.arrow.up")
            }
        }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.json]) { result in
            guard case .success(let url) = result else { return }
            Task { await importFile(url) }
        }
        .alert("Import", isPresented: Binding(get: { importMessage != nil }, set: { if !$0 { importMessage = nil } })) {
            Button("OK", role: .cancel) { importMessage = nil }
        } message: {
            Text(importMessage ?? "")
        }
        .accessibilityIdentifier("medications.home")
    }

    // MARK: Toolbar

    private var overflowMenu: some View {
        Menu {
            Button {
                showRecordPicker = true
            } label: {
                Label("Add from prescription", systemImage: "doc.text.magnifyingglass")
            }
            Divider()
            Button {
                Task { await exportArchive() }
            } label: {
                Label("Export medications…", systemImage: "square.and.arrow.up")
            }
            .disabled(store.totalCount == 0 || isExporting)
            Button {
                showImporter = true
            } label: {
                Label("Import…", systemImage: "square.and.arrow.down")
            }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
        .accessibilityLabel(Text("More"))
        .accessibilityIdentifier("medications.menu")
    }

    // MARK: Sections

    @ViewBuilder
    private var permissionBanner: some View {
        if store.activeCount > 0, notificationsEnabled, notificationManager.authorizationStatus == .denied {
            RecordsCard {
                Label("Dose reminders can't be shown", systemImage: "bell.slash")
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                Button {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                } label: {
                    Text("Notifications are disabled in system settings. Tap to open Settings.")
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(AppColors.calorie)
                        .multilineTextAlignment(.leading)
                }
            }
            .accessibilityIdentifier("medications.banner.permission")
        } else if store.activeCount > 0, !notificationsEnabled {
            RecordsCard {
                Label("Dose reminders are off", systemImage: "bell.slash")
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                Text("Turn on Notifications in Settings › Notifications to be reminded when a dose is due.")
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            .accessibilityIdentifier("medications.banner.remindersOff")
        }
    }

    private var emptyState: some View {
        ContentUnavailableView {
            Label("No medications", systemImage: "pills")
        } description: {
            Text("Add the medicines you take and Ayuvo will remind you when a dose is due. Everything stays on this device.")
        } actions: {
            Button {
                showAddForm = true
            } label: {
                Label("Add medication", systemImage: "plus")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(AppColors.calorie)
            .controlSize(.large)
            .accessibilityIdentifier("medications.empty.add")
            Button {
                showRecordPicker = true
            } label: {
                Label("Add from prescription", systemImage: "doc.text.magnifyingglass")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .tint(AppColors.calorie)
            .controlSize(.large)
            .accessibilityIdentifier("medications.empty.import")
        }
        .padding(.top, 24)
        // Contain, so the buttons keep their own identifiers instead of inheriting this one.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("medications.empty")
    }

    private var allMedications: some View {
        @Bindable var store = store
        return VStack(alignment: .leading, spacing: 12) {
            MedicationSectionHeader(title: String(localized: "All medicines"), systemImage: "list.bullet",
                                    trailing: "\(store.totalCount)")
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(MedicationFilter.allCases) { item in
                        chipButton(item)
                    }
                }
                .padding(.vertical, 2)
            }
            .scrollClipDisabled()
            if store.medications.isEmpty {
                RecordsCard {
                    Text(store.searchText.isEmpty ? noMedicinesText : String(localized: "No medicines match “\(store.searchText)”."))
                        .font(.system(.subheadline, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            } else {
                RecordsCard(padding: 12) {
                    ForEach(store.medications) { medication in
                        NavigationLink(value: MedicationRoute.detail(medication.id)) {
                            MedicationRow(medication: medication, subtitle: listSubtitle(medication))
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("medications.row.\(medication.id)")
                        if medication.id != store.medications.last?.id { Divider() }
                    }
                }
            }
        }
    }

    private var noMedicinesText: String {
        switch store.filter {
        case .active: String(localized: "No active medicines.")
        case .paused: String(localized: "No paused medicines.")
        case .completed: String(localized: "No completed medicines.")
        case .stopped: String(localized: "No stopped medicines.")
        case .all: String(localized: "No medicines yet.")
        }
    }

    private func listSubtitle(_ medication: Medication) -> String {
        var parts = [MedicationFormatting.doseText(medication)]
        if medication.isPRN {
            parts.append(String(localized: "As needed"))
        } else if medication.foodRelation != .anytime {
            parts.append(medication.foodRelation.title)
        }
        if let end = medication.endDate {
            parts.append(String(localized: "until \(MedicationFormatting.dateText(iso: end))"))
        }
        return parts.joined(separator: " · ")
    }

    private func chipButton(_ item: MedicationFilter) -> some View {
        let selected = store.filter == item
        return Button {
            withAnimation(.snappy) { store.filter = item }
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
        .accessibilityIdentifier("medications.chip.\(item.rawValue)")
    }

    // MARK: Actions

    private func take(_ item: MedicationTodayTimeline.Item) async {
        let outcome = await store.act(.taken, on: item.occurrence)
        if !outcome.ok, let error = outcome.error {
            store.showBanner(MedicationFormatting.actionErrorText(error), systemImage: "exclamationmark.circle")
        }
    }

    private func skip(_ item: MedicationTodayTimeline.Item) async {
        let outcome = await store.act(.skipped, on: item.occurrence)
        if !outcome.ok, let error = outcome.error {
            store.showBanner(MedicationFormatting.actionErrorText(error), systemImage: "exclamationmark.circle")
        }
    }

    private func snooze(_ item: MedicationTodayTimeline.Item, minutes: Int) async {
        let outcome = await store.act(.snoozed, on: item.occurrence, snoozeMinutes: minutes)
        if !outcome.ok, let error = outcome.error {
            store.showBanner(MedicationFormatting.actionErrorText(error), systemImage: "exclamationmark.circle")
        } else {
            store.showBanner(String(localized: "Snoozed for \(minutes) min"), systemImage: "clock")
        }
    }

    private func exportArchive() async {
        isExporting = true
        defer { isExporting = false }
        do {
            exportDocument = try await store.exportDocument()
            showExporter = true
        } catch {
            store.showBanner(String(localized: "Export failed"), systemImage: "exclamationmark.circle")
        }
    }

    private func importFile(_ url: URL) async {
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        do {
            let data = try Data(contentsOf: url)
            let result = try await store.importArchive(data)
            let added = result.insertedMedications
            let updated = result.updatedMedications
            importMessage = String(localized: "Imported \(MedicationFormatting.medicationCount(added)) (new) and updated \(updated). Nothing was deleted.")
        } catch MedicationStoreError.archive(let code) {
            importMessage = code == "unsupported_version"
                ? String(localized: "This file was made by a newer version of Ayuvo. Update the app to import it.")
                : String(localized: "This file isn't an Ayuvo medications export.")
        } catch {
            importMessage = String(localized: "The file couldn't be imported.")
        }
    }
}
