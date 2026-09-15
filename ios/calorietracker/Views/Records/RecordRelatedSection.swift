import SwiftUI

/// "Related records" at the bottom of the detail (§19, §22, plan §3.10): Linked first, then
/// Suggested with ✓ / ✕, plus "Link record".
struct RecordRelatedSection: View {
    let detail: RecordDetail
    @Environment(RecordsStore.self) private var store
    @State private var showLinkSheet = false
    @State private var unlinkTarget: RecordRelated?

    var body: some View {
        let linked = detail.related.filter { $0.link.status == .accepted }
        let suggested = detail.related.filter { $0.link.status == .suggested }
        RecordsCard {
            RecordsSectionTitle(title: "Related records", systemImage: "link")
            if linked.isEmpty && suggested.isEmpty {
                Text("Link follow-ups, prescriptions and earlier reports to keep an episode together.")
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            if !linked.isEmpty {
                subheading("Linked")
                ForEach(linked) { item in
                    HStack(spacing: 8) {
                        NavigationLink(value: RecordsRoute.detail(item.record.id)) {
                            VStack(alignment: .leading, spacing: 2) {
                                RecordRow(record: item.record, compact: true)
                                kindLine(item)
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("records.related.linked.\(item.record.id)")
                        Menu {
                            Button(role: .destructive) {
                                unlinkTarget = item
                            } label: {
                                Label("Unlink", systemImage: "xmark.circle")
                            }
                        } label: {
                            Image(systemName: "ellipsis.circle")
                                .font(.system(size: 18))
                                .foregroundStyle(.secondary)
                                .frame(width: 32, height: 32)
                        }
                        .accessibilityLabel(Text("Link options"))
                        .accessibilityIdentifier("records.related.menu.\(item.record.id)")
                    }
                }
            }
            if !suggested.isEmpty {
                subheading("Suggested")
                ForEach(suggested) { item in
                    HStack(spacing: 8) {
                        NavigationLink(value: RecordsRoute.detail(item.record.id)) {
                            VStack(alignment: .leading, spacing: 2) {
                                RecordRow(record: item.record, compact: true)
                                kindLine(item)
                            }
                        }
                        .buttonStyle(.plain)
                        Button {
                            Task { await store.acceptSuggestion(detail.record.id, item.record.id) }
                        } label: {
                            Image(systemName: "checkmark.circle.fill").font(.system(size: 26)).foregroundStyle(.green)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(Text("Accept suggestion"))
                        .accessibilityIdentifier("records.related.accept.\(item.record.id)")
                        Button {
                            Task { await store.unlink(detail.record.id, item.record.id) }
                        } label: {
                            Image(systemName: "xmark.circle.fill").font(.system(size: 26)).foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(Text("Dismiss suggestion"))
                        .accessibilityIdentifier("records.related.reject.\(item.record.id)")
                    }
                }
            }
            Button {
                showLinkSheet = true
            } label: {
                Label("Link record", systemImage: "link.badge.plus")
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
            }
            .accessibilityIdentifier("records.related.link")
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("records.detail.related")
        .sheet(isPresented: $showLinkSheet) {
            RecordLinkSheet(record: detail.record, alreadyLinked: Set(linked.map(\.record.id)))
        }
        .confirmationDialog("Unlink these records?", isPresented: Binding(get: { unlinkTarget != nil }, set: { if !$0 { unlinkTarget = nil } }), titleVisibility: .visible) {
            Button("Unlink", role: .destructive) {
                if let target = unlinkTarget {
                    Task { await store.unlink(detail.record.id, target.record.id) }
                }
                unlinkTarget = nil
            }
        } message: {
            Text("Both records stay saved. Only the link is removed.")
        }
    }

    private func subheading(_ title: LocalizedStringKey) -> some View {
        Text(title)
            .font(.system(.caption, design: .rounded, weight: .bold))
            .foregroundStyle(.secondary)
            .textCase(.uppercase)
            .padding(.top, 2)
    }

    private func kindLine(_ item: RecordRelated) -> some View {
        let older = item.record.effectiveDate < detail.record.effectiveDate
        return HStack(spacing: 4) {
            Image(systemName: item.link.kind.systemImage)
            Text(item.link.kind.title)
            Text("·")
            Text(older ? String(localized: "Earlier") : String(localized: "Later"))
            if item.link.status == .suggested, let reason = reasonText(item.link.reasons.first) {
                Text("·")
                Text(reason)
            }
        }
        .font(.system(.caption2, design: .rounded, weight: .medium))
        .foregroundStyle(item.link.status == .suggested ? .purple : .teal)
        .padding(.leading, 52)
        .lineLimit(1)
    }

    private func reasonText(_ reason: String?) -> String? {
        switch reason {
        case "same_panel": String(localized: "Same tests")
        case "same_report_name": String(localized: "Same report")
        case "shared_analytes": String(localized: "Shared tests")
        case "same_doctor": String(localized: "Same doctor")
        case "same_facility": String(localized: "Same hospital or lab")
        case "prescription_after_visit": String(localized: "Prescribed after the visit")
        case "follow_up_date": String(localized: "Follow-up date")
        default: nil
        }
    }
}

/// "Link record" sheet: search + recent records with checkboxes and the relation kind.
struct RecordLinkSheet: View {
    let record: HealthRecord
    var alreadyLinked: Set<String> = []
    @Environment(RecordsStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var candidates: [HealthRecord] = []
    @State private var selection: Set<String> = []
    @State private var kind: RecordLinkKind = .related
    @State private var isLoading = true

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Picker("Relation", selection: $kind) {
                        ForEach(RecordLinkKind.userChoices) { Text($0.title).tag($0) }
                    }
                    .accessibilityIdentifier("records.linkSheet.kind")
                }
                Section(text.isEmpty ? "Recent records" : "Results") {
                    if candidates.isEmpty, !isLoading {
                        Text("No records found").foregroundStyle(.secondary)
                    }
                    ForEach(candidates) { candidate in
                        Button {
                            if selection.contains(candidate.id) { selection.remove(candidate.id) } else { selection.insert(candidate.id) }
                        } label: {
                            HStack {
                                Image(systemName: selection.contains(candidate.id) || alreadyLinked.contains(candidate.id) ? "checkmark.circle.fill" : "circle")
                                    .font(.system(size: 20))
                                    .foregroundStyle(selection.contains(candidate.id) ? AppColors.calorie : (alreadyLinked.contains(candidate.id) ? .teal : .secondary))
                                RecordRow(record: candidate, compact: true)
                            }
                        }
                        .buttonStyle(.plain)
                        .disabled(alreadyLinked.contains(candidate.id))
                        .accessibilityIdentifier("records.linkSheet.candidate.\(candidate.id)")
                    }
                }
            }
            .searchable(text: $text, placement: .navigationBarDrawer(displayMode: .always), prompt: Text("Search records"))
            .navigationTitle("Link record")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(selection.count > 1 ? "Link \(selection.count)" : "Link") {
                        let ids = Array(selection)
                        let recordID = record.id
                        let kind = kind
                        Task { await store.linkRecords(recordID, ids, kind: kind) }
                        dismiss()
                    }
                    .fontWeight(.semibold)
                    .disabled(selection.isEmpty)
                    .accessibilityIdentifier("records.linkSheet.save")
                }
            }
            .task(id: text) {
                if !text.isEmpty { try? await Task.sleep(nanoseconds: 150_000_000) }
                guard !Task.isCancelled else { return }
                candidates = await store.linkCandidates(for: record.id, text: text)
                isLoading = false
            }
        }
    }
}
